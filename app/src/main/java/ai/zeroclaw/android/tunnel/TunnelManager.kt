package ai.zeroclaw.android.tunnel

import android.content.Context
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * TunnelManager
 * Creates a public HTTPS URL tunneling to localhost:8088 (WebChat server).
 *
 * The cloudflared binary is bundled inside the APK as a native library
 * (jniLibs/arm64-v8a/libcloudflared.so). Android extracts it to nativeLibraryDir
 * on install, which has execute permission — bypassing the W^X policy.
 *
 * Modes:
 * - "quick"  → Free trycloudflare.com URL (no account, changes on restart)
 * - "token"  → Named tunnel with persistent URL (requires Cloudflare token)
 * - "off"    → LAN only, no tunnel
 */
class TunnelManager(private val context: Context) {

    companion object {
        private const val BINARY_NAME = "libcloudflared.so"
        private const val TARGET_PORT = 8088
        @Volatile var status: String = "idle"
    }

    private var tunnelProcess: Process? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Start tunnel.
     * @param mode "quick", "token", or "off"
     * @param token Cloudflare tunnel token (only for mode="token")
     */
    suspend fun start(
        mode: String = "quick",
        token: String = "",
        onUrlReady: (String) -> Unit,
        onStatusChange: (String) -> Unit = {}
    ) {
        if (mode == "off") {
            val url = "http://${getLocalIp()}:$TARGET_PORT"
            ZeroClawService.log("Tunnel disabled. Local URL: $url")
            onUrlReady(url)
            return
        }

        withContext(Dispatchers.IO) {
            val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

            if (!binary.exists()) {
                status = "missing"
                val msg = "Cloudflare: binary not found at ${binary.absolutePath}"
                ZeroClawService.log(msg)
                onStatusChange("Binary not found — reinstall app")
                onUrlReady("http://${getLocalIp()}:$TARGET_PORT")
                return@withContext
            }

            if (!binary.canExecute()) {
                // Should never happen for nativeLibraryDir, but just in case
                binary.setExecutable(true)
            }

            ZeroClawService.log("Cloudflare: binary ready at ${binary.absolutePath} (${binary.length() / 1024}KB)")

            when (mode) {
                "token" -> {
                    if (token.isNotBlank()) {
                        startNamedTunnel(binary, token, onUrlReady, onStatusChange)
                    } else {
                        ZeroClawService.log("Cloudflare: token mode but no token — using quick tunnel")
                        startQuickTunnel(binary, onUrlReady, onStatusChange)
                    }
                }
                else -> startQuickTunnel(binary, onUrlReady, onStatusChange)
            }
        }
    }

    /**
     * Quick Tunnel — free, no account, random trycloudflare.com URL.
     */
    private suspend fun startQuickTunnel(
        binary: File,
        onUrlReady: (String) -> Unit,
        onStatusChange: (String) -> Unit
    ) {
        try {
            status = "starting"
            onStatusChange("Starting Cloudflare Quick Tunnel...")
            ZeroClawService.log("Cloudflare: starting quick tunnel → localhost:$TARGET_PORT")

            tunnelProcess = ProcessBuilder(
                binary.absolutePath, "tunnel", "--url", "http://localhost:$TARGET_PORT"
            )
                .directory(context.cacheDir)
                .redirectErrorStream(true)
                .start()

            parseOutputForUrl(tunnelProcess!!, onUrlReady, onStatusChange)
        } catch (e: Exception) {
            status = "failed"
            onStatusChange("Tunnel error: ${e.message}")
            ZeroClawService.log("Cloudflare: quick tunnel error — ${e.message}")
            onUrlReady("http://${getLocalIp()}:$TARGET_PORT")
        }
    }

    /**
     * Named Tunnel — persistent URL via Cloudflare token.
     * Create at: https://one.dash.cloudflare.com → Networks → Tunnels
     */
    private suspend fun startNamedTunnel(
        binary: File,
        token: String,
        onUrlReady: (String) -> Unit,
        onStatusChange: (String) -> Unit
    ) {
        try {
            status = "starting"
            onStatusChange("Starting Cloudflare Named Tunnel...")
            ZeroClawService.log("Cloudflare: starting named tunnel with token")

            tunnelProcess = ProcessBuilder(
                binary.absolutePath, "tunnel", "run", "--token", token
            )
                .directory(context.cacheDir)
                .redirectErrorStream(true)
                .start()

            val reader = tunnelProcess!!.inputStream.bufferedReader()
            withContext(Dispatchers.IO) {
                var connected = false
                var lineCount = 0

                while (!connected && lineCount < 300) {
                    val line = reader.readLine() ?: break
                    lineCount++

                    if (line.contains("ERR") || line.contains("error")) {
                        ZeroClawService.log("Cloudflare: $line")
                    }

                    if (line.contains("Registered tunnel connection") || line.contains("Connection registered")) {
                        connected = true
                        status = "connected"
                        onStatusChange("Named tunnel connected")
                        ZeroClawService.log("Cloudflare: named tunnel connected — URL is in your Cloudflare dashboard")
                    }

                    // Try to extract URL from output
                    val urlMatch = Regex("https://[a-z0-9.-]+\\.[a-z]{2,}").find(line)
                    if (urlMatch != null && !urlMatch.value.contains("api.cloudflare") && !urlMatch.value.contains("update.")) {
                        connected = true
                        status = "connected"
                        val url = urlMatch.value
                        onStatusChange("Connected: $url")
                        ZeroClawService.log("Cloudflare: named tunnel live → $url")
                        onUrlReady(url)
                    }
                }

                if (!connected) {
                    status = "failed"
                    ZeroClawService.log("Cloudflare: named tunnel failed after $lineCount lines")
                    onStatusChange("Named tunnel failed to connect")
                }

                // Drain output to prevent buffer deadlock
                drainOutput(reader)
            }
        } catch (e: Exception) {
            status = "failed"
            onStatusChange("Named tunnel error: ${e.message}")
            ZeroClawService.log("Cloudflare: named tunnel error — ${e.message}")
        }
    }

    /**
     * Parse cloudflared output looking for the trycloudflare.com URL.
     */
    private suspend fun parseOutputForUrl(
        process: Process,
        onUrlReady: (String) -> Unit,
        onStatusChange: (String) -> Unit
    ) {
        val reader = process.inputStream.bufferedReader()
        withContext(Dispatchers.IO) {
            val urlRegex = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com")
            var urlFound = false
            var lineCount = 0

            while (!urlFound && lineCount < 300) {
                val line = reader.readLine() ?: break
                lineCount++

                // Log errors and important messages
                if (line.contains("ERR") || line.contains("error") || line.contains("failed")) {
                    ZeroClawService.log("Cloudflare: $line")
                }

                val match = urlRegex.find(line)
                if (match != null) {
                    val url = match.value
                    urlFound = true
                    status = "connected"
                    onStatusChange("Connected: $url")
                    ZeroClawService.log("Cloudflare: tunnel live → $url")
                    onUrlReady(url)
                }
            }

            if (!urlFound) {
                status = "failed"
                onStatusChange("Tunnel failed to start")
                ZeroClawService.log("Cloudflare: no URL found after $lineCount lines")
                onUrlReady("http://${getLocalIp()}:$TARGET_PORT")
            }

            // Drain remaining output to prevent deadlock
            drainOutput(reader)
        }
    }

    /** Keep reading process output in background to prevent buffer deadlock */
    private fun CoroutineScope.drainOutput(reader: java.io.BufferedReader) {
        launch(Dispatchers.IO) {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.contains("ERR") || line.contains("Reconnecting")) {
                        ZeroClawService.log("Cloudflare: $line")
                    }
                }
            } catch (_: Exception) { }
        }
    }

    fun getLocalIp(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
            "127.0.0.1"
        } catch (e: Exception) { "127.0.0.1" }
    }

    fun isBinaryReady(): Boolean {
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        return binary.exists() && binary.canExecute()
    }

    fun stop() {
        tunnelProcess?.destroyForcibly()
        tunnelProcess = null
        status = "idle"
    }
}
