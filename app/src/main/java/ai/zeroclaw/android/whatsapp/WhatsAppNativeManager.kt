package ai.zeroclaw.android.whatsapp

import android.content.Context
import ai.zeroclaw.android.data.AppSettings
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Base64

/**
 * Phase 189 — native WhatsApp via the bundled whatsmeow Go bridge.
 *
 * Lifecycle (mirrors [ai.zeroclaw.android.tunnel.TunnelManager]):
 *   1. Caller resolves `libwhatsmeow.so` from `nativeLibraryDir`.
 *   2. [start] spawns the binary with `WHATSMEOW_DATA_DIR` pointing at app
 *      private storage so the SQLite session DB persists across runs.
 *   3. Two daemon coroutines: one drains stdout into [state], one keeps a
 *      writer open for sending stdin commands.
 *   4. Incoming `MSG …` lines are dispatched through [LlmRouter.call] and
 *      the reply is sent back via `SEND`.
 *
 * Bridge protocol — see whatsmeow-bridge/main.go.
 */
class WhatsAppNativeManager(private val context: Context) {

    companion object {
        private const val BINARY_NAME = "libwhatsmeow.so"
        private const val DATA_DIR_NAME = "whatsmeow"

        @Volatile private var INSTANCE: WhatsAppNativeManager? = null
        fun getInstance(context: Context): WhatsAppNativeManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: WhatsAppNativeManager(context.applicationContext).also { INSTANCE = it }
            }
    }

    /**
     * UI-facing state. Snapshots are atomic: every emission replaces the previous one
     * so the screen never has to manually reconcile partial updates.
     */
    data class NativeState(
        val running: Boolean = false,
        val phase: String = "idle",        // "idle" | "starting" | "qr_ready" | "pair_ready" | "connected" | "disconnected" | "error"
        val qrPayload: String? = null,     // present while phase == "qr_ready"
        val pairCode: String? = null,      // present after a successful PAIR command
        val jid: String? = null,           // populated once Connected event fires
        val lastError: String? = null,
        val binaryAvailable: Boolean = true,
        val lastEvent: String = ""         // human-readable status line for the UI
    )

    private val _state = MutableStateFlow(NativeState())
    val state: StateFlow<NativeState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var process: Process? = null
    private var stdinWriter: BufferedWriter? = null
    private val writeLock = Any()

    fun isBinaryReady(): Boolean = binaryFile().let { it.exists() && it.canExecute() }

    private fun binaryFile() = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    private fun dataDir(): File =
        File(context.filesDir, DATA_DIR_NAME).apply { mkdirs() }

    /**
     * Spawn the bridge if it isn't already running. Idempotent.
     */
    fun start() {
        if (process?.isAlive == true) return
        val bin = binaryFile()
        if (!bin.exists()) {
            _state.value = _state.value.copy(
                running = false,
                phase = "error",
                lastError = "libwhatsmeow.so not bundled — see whatsmeow-bridge/BUILD.md",
                binaryAvailable = false,
                lastEvent = "Binary missing"
            )
            ZeroClawService.log("WhatsApp(native): binary not found at ${bin.absolutePath}")
            return
        }
        if (!bin.canExecute()) bin.setExecutable(true)

        try {
            val pb = ProcessBuilder(bin.absolutePath)
                .directory(context.cacheDir)
                .redirectErrorStream(false)
            pb.environment()["WHATSMEOW_DATA_DIR"] = dataDir().absolutePath
            process = pb.start()
            stdinWriter = BufferedWriter(OutputStreamWriter(process!!.outputStream, Charsets.UTF_8))
            _state.value = NativeState(
                running = true, phase = "starting",
                binaryAvailable = true,
                lastEvent = "Spawned bridge"
            )
            ZeroClawService.log("WhatsApp(native): bridge spawned (pid ~ ${process!!.hashCode()})")

            scope.launch { drainStdout() }
            scope.launch { drainStderr() }
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                running = false, phase = "error",
                lastError = e.message ?: "spawn failed",
                lastEvent = "Spawn failed"
            )
            ZeroClawService.log("WhatsApp(native): spawn failed — ${e.message}")
        }
    }

    fun stop() {
        writeCommand("STOP")
        process?.destroyForcibly()
        process = null
        stdinWriter = null
        _state.value = _state.value.copy(
            running = false, phase = "disconnected", qrPayload = null, pairCode = null,
            lastEvent = "Stopped"
        )
        ZeroClawService.log("WhatsApp(native): stopped")
    }

    /**
     * Request an 8-digit pair code instead of QR (user enters it inside WhatsApp).
     * Phone must be E.164 without `+` (e.g. `15551234567`).
     */
    fun requestPairCode(e164: String) {
        val phone = e164.trim().removePrefix("+")
        if (phone.isEmpty()) return
        writeCommand("PAIR $phone")
    }

    /**
     * Public API mirror of TwilioWhatsAppManager.sendProactiveMessage so the rest of the
     * app can keep calling a single shape regardless of which backend is active.
     */
    fun sendProactiveMessage(jid: String, text: String) {
        if (text.isBlank()) return
        writeCommand("SEND ${jid.trim()} ${b64(text)}")
    }

    /**
     * Writes a single line to the bridge's stdin. Plain JVM `synchronized` so it can
     * be called from non-suspend contexts (e.g. [stop]) and from coroutines alike —
     * stdin writes are bounded and fast, so blocking the caller briefly is acceptable.
     */
    private fun writeCommand(line: String) {
        val w = stdinWriter ?: run {
            ZeroClawService.log("WhatsApp(native): write '$line' but bridge not running")
            return
        }
        synchronized(writeLock) {
            try {
                w.write(line); w.newLine(); w.flush()
            } catch (e: Exception) {
                ZeroClawService.log("WhatsApp(native): write error — ${e.message}")
            }
        }
    }

    private fun drainStdout() {
        val proc = process ?: return
        val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                handleEvent(line)
            }
        } catch (e: Exception) {
            ZeroClawService.log("WhatsApp(native): stdout reader exited — ${e.message}")
        } finally {
            _state.value = _state.value.copy(running = false, phase = "disconnected", lastEvent = "Bridge exited")
        }
    }

    private fun drainStderr() {
        val proc = process ?: return
        val reader = BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                ZeroClawService.log("WhatsApp(native).err: $line")
            }
        } catch (_: Exception) { }
    }

    private fun handleEvent(raw: String) {
        val line = raw.trimEnd()
        when {
            line.startsWith("STATUS ") -> handleStatus(line.removePrefix("STATUS ").trim())
            line.startsWith("QR ")     -> {
                val payload = line.removePrefix("QR ").trim()
                _state.value = _state.value.copy(
                    phase = "qr_ready", qrPayload = payload, pairCode = null,
                    lastEvent = "QR refreshed"
                )
            }
            line.startsWith("PAIRCODE ") -> {
                val code = line.removePrefix("PAIRCODE ").trim()
                _state.value = _state.value.copy(
                    phase = "pair_ready", pairCode = code, qrPayload = null,
                    lastEvent = "Pair code: $code"
                )
            }
            line.startsWith("MSG ") -> handleIncomingMessage(line.removePrefix("MSG ").trim())
            line.startsWith("LOG ") -> {
                val decoded = runCatching { String(Base64.getDecoder().decode(line.removePrefix("LOG ").trim())) }.getOrDefault("")
                if (decoded.isNotBlank()) ZeroClawService.log("WhatsApp(native): $decoded")
            }
            else -> ZeroClawService.log("WhatsApp(native): ?$line")
        }
    }

    private fun handleStatus(rest: String) {
        val parts = rest.split(' ', limit = 2)
        when (parts[0]) {
            "connecting" -> _state.value = _state.value.copy(phase = "starting", lastEvent = "Connecting…")
            "qr_ready"   -> _state.value = _state.value.copy(phase = "qr_ready", lastEvent = "Scan the QR")
            "pair_ready" -> _state.value = _state.value.copy(phase = "pair_ready", lastEvent = "Enter the pair code")
            "connected"  -> {
                val jid = parts.getOrNull(1).orEmpty()
                _state.value = _state.value.copy(
                    phase = "connected", jid = jid, qrPayload = null, pairCode = null,
                    lastEvent = "Connected as $jid"
                )
                ZeroClawService.whatsappConnected = true
                scope.launch { AppSettings(context).setWhatsAppNativeJid(jid) }
            }
            "pair_success" -> {
                val jid = parts.getOrNull(1).orEmpty()
                _state.value = _state.value.copy(jid = jid, lastEvent = "Pair success: $jid")
            }
            "pair_success_qr" -> _state.value = _state.value.copy(lastEvent = "QR pair success")
            "disconnected" -> {
                _state.value = _state.value.copy(phase = "disconnected", lastEvent = "Disconnected")
                ZeroClawService.whatsappConnected = false
            }
            "error" -> {
                val msg = parts.getOrNull(1)?.let { runCatching { String(Base64.getDecoder().decode(it)) }.getOrDefault(it) } ?: ""
                _state.value = _state.value.copy(phase = "error", lastError = msg, lastEvent = "Error: $msg")
            }
            else -> _state.value = _state.value.copy(lastEvent = rest)
        }
    }

    private fun handleIncomingMessage(rest: String) {
        // Format: <jid> <b64_pushname> <b64_text>
        val parts = rest.split(' ', limit = 3)
        if (parts.size < 3) return
        val (jid, b64Push, b64Text) = Triple(parts[0], parts[1], parts[2])
        val pushName = runCatching { String(Base64.getDecoder().decode(b64Push)) }.getOrDefault("")
        val text = runCatching { String(Base64.getDecoder().decode(b64Text)) }.getOrDefault("")
        if (text.isBlank()) return

        ZeroClawService.log("WhatsApp(native) ← $pushName ($jid): $text")

        // Dispatch to LLM and reply on the same bridge.
        scope.launch {
            try {
                val reply = LlmRouter.getInstance(context).call(text)
                if (reply.isNotBlank()) sendProactiveMessage(jid, reply)
            } catch (e: Exception) {
                ZeroClawService.log("WhatsApp(native): reply failed — ${e.message}")
            }
        }
    }

    private fun b64(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
}
