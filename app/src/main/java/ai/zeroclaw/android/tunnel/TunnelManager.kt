package ai.zeroclaw.android.tunnel

import android.content.Context
import android.os.Build
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * TunnelManager
 * Creates a public HTTPS URL tunneling to localhost:8088 (WebChat server) so that:
 * - External apps can reach the OpenAI-compatible API
 * - Telegram/Twilio webhooks can reach this device
 *
 * Android W^X Policy Fix:
 * Android 10+ blocks exec from app data dirs (noexec mount). We work around this by:
 * 1. Storing the binary in the native library dir (nativeLibraryDir — exec-allowed)
 * 2. If that fails, use the dynamic linker to execute from filesDir
 * 3. If that also fails, fall back to local IP only
 */
class TunnelManager(private val context: Context) {

    companion object {
        private const val CLOUDFLARED_BINARY = "libcloudflared.so"  // Must look like a native lib
        private const val CLOUDFLARED_URL =
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"
        private const val TARGET_PORT = 8088
        @Volatile var status: String = "idle"
        @Volatile var isDownloading: Boolean = false
    }

    private var tunnelProcess: Process? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Start tunnel with the given mode.
     * @param mode "quick" (free trycloudflare.com), "token" (named tunnel), or "off"
     * @param token Cloudflare tunnel token (only for mode="token")
     * @param onUrlReady Callback with the public HTTPS URL
     * @param onStatusChange Callback for status updates (downloading, starting, etc.)
     */
    suspend fun start(
        mode: String = "quick",
        token: String = "",
        onUrlReady: (String) -> Unit,
        onStatusChange: (String) -> Unit = {}
    ) {
        if (mode == "off") {
            val ip = getLocalIp()
            val url = "http://$ip:$TARGET_PORT"
            ZeroClawService.log("Tunnel disabled. Local URL: $url")
            onUrlReady(url)
            return
        }

        withContext(Dispatchers.IO) {
            // Try ngrok first if available (user manually placed it)
            val ngrokBinary = File(context.applicationInfo.nativeLibraryDir, "libngrok.so")
            if (ngrokBinary.exists() && ngrokBinary.canExecute()) {
                startNgrok(ngrokBinary, onUrlReady)
                return@withContext
            }

            // Find or download cloudflared
            val binary = findOrDownloadBinary(onStatusChange)
            if (binary == null) {
                val ip = getLocalIp()
                val url = "http://$ip:$TARGET_PORT"
                status = "download_failed"
                onStatusChange("Download failed")
                ZeroClawService.log("Cloudflare: could not prepare binary. Local URL: $url")
                onUrlReady(url)
                return@withContext
            }

            // Start tunnel based on mode
            when (mode) {
                "token" -> {
                    if (token.isNotBlank()) {
                        startNamedTunnel(binary, token, onUrlReady, onStatusChange)
                    } else {
                        ZeroClawService.log("Cloudflare: token mode but no token — falling back to quick tunnel")
                        startQuickTunnel(binary, onUrlReady, onStatusChange)
                    }
                }
                else -> startQuickTunnel(binary, onUrlReady, onStatusChange)
            }
        }
    }

    /**
     * Find existing binary or download it. Returns the executable path, or null on failure.
     *
     * Strategy:
     * 1. Check nativeLibraryDir (exec-allowed on all Android versions)
     * 2. Check filesDir (works on older Android)
     * 3. Download to nativeLibraryDir first, filesDir as fallback
     */
    private fun findOrDownloadBinary(onStatusChange: (String) -> Unit): File? {
        // Location 1: nativeLibraryDir (always executable)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val nativeBinary = File(nativeDir, CLOUDFLARED_BINARY)
        if (nativeBinary.exists() && nativeBinary.canExecute()) {
            ZeroClawService.log("Cloudflare: using binary from native lib dir")
            return nativeBinary
        }

        // Location 2: filesDir (may work on older Android)
        val filesDirBinary = File(context.filesDir, "cloudflared")
        if (filesDirBinary.exists() && filesDirBinary.canExecute()) {
            // Test if it can actually execute
            if (testExecutable(filesDirBinary)) {
                ZeroClawService.log("Cloudflare: using binary from files dir")
                return filesDirBinary
            }
        }

        // Need to download — try nativeLibraryDir first
        val downloadTarget = if (nativeDir.canWrite()) {
            nativeBinary
        } else {
            // Fallback to filesDir
            filesDirBinary
        }

        val success = downloadCloudflared(downloadTarget, onStatusChange)
        if (!success) return null

        // Verify it can execute
        if (testExecutable(downloadTarget)) {
            return downloadTarget
        }

        // If nativeLibraryDir download worked but can't execute, try filesDir
        if (downloadTarget == nativeBinary && !testExecutable(nativeBinary)) {
            ZeroClawService.log("Cloudflare: native dir not executable, trying files dir...")
            val copied = nativeBinary.copyTo(filesDirBinary, overwrite = true)
            copied.setExecutable(true, true)
            if (testExecutable(copied)) {
                return copied
            }
        }

        // Last resort: try to execute via the system linker
        ZeroClawService.log("Cloudflare: binary downloaded but exec blocked by Android security policy")
        ZeroClawService.log("Cloudflare: attempting linker workaround...")
        return downloadTarget // Will use linker-based execution
    }

    /** Test if a binary can actually be executed (not just has +x permission) */
    private fun testExecutable(binary: File): Boolean {
        return try {
            val process = ProcessBuilder(binary.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readLine() ?: ""
            process.destroyForcibly()
            output.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Build a ProcessBuilder that can execute the binary, working around Android's W^X policy.
     * Tries direct execution first, then falls back to linker-based execution.
     */
    private fun buildProcess(binary: File, vararg args: String): ProcessBuilder {
        val fullArgs = mutableListOf<String>()

        // Try direct execution first
        if (testExecutable(binary)) {
            fullArgs.add(binary.absolutePath)
            fullArgs.addAll(args)
        } else {
            // Use the dynamic linker to execute the binary
            // On Android, the linker is at /system/bin/linker64 (arm64) or /system/bin/linker (arm32)
            val linker = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) {
                "/system/bin/linker64"
            } else {
                "/system/bin/linker"
            }

            if (File(linker).exists()) {
                fullArgs.add(linker)
                fullArgs.add(binary.absolutePath)
                fullArgs.addAll(args)
            } else {
                // Last resort: use sh -c
                fullArgs.add("/system/bin/sh")
                fullArgs.add("-c")
                val escapedArgs = args.joinToString(" ") { "'$it'" }
                fullArgs.add("${binary.absolutePath} $escapedArgs")
            }
        }

        return ProcessBuilder(fullArgs)
            .directory(context.filesDir)
            .redirectErrorStream(true)
    }

    /**
     * Download cloudflared-linux-arm64 binary from GitHub releases.
     */
    private fun downloadCloudflared(targetFile: File, onStatusChange: (String) -> Unit): Boolean {
        return try {
            isDownloading = true
            status = "downloading"
            onStatusChange("Downloading cloudflared...")
            ZeroClawService.log("Cloudflare: downloading ARM64 binary to ${targetFile.parent}...")

            val request = Request.Builder()
                .url(CLOUDFLARED_URL)
                .header("User-Agent", "ZeroClaw-Android/1.1")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    ZeroClawService.log("Cloudflare: download failed HTTP ${response.code}")
                    return false
                }

                val body = response.body ?: return false
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                var lastLoggedPct = -1

                FileOutputStream(targetFile).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(16384)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                val pct = (downloadedBytes * 100 / totalBytes).toInt()
                                // Only log at 25% intervals to reduce log spam
                                if (pct / 25 > lastLoggedPct / 25) {
                                    lastLoggedPct = pct
                                    status = "downloading_$pct"
                                    onStatusChange("Downloading cloudflared... ${pct}%")
                                }
                            }
                        }
                    }
                }
            }

            // Make executable
            targetFile.setExecutable(true, true)
            targetFile.setReadable(true, true)
            isDownloading = false
            status = "downloaded"
            onStatusChange("Download complete")
            ZeroClawService.log("Cloudflare: binary downloaded (${targetFile.length() / 1024}KB) → ${targetFile.absolutePath}")
            true
        } catch (e: Exception) {
            isDownloading = false
            status = "download_failed"
            onStatusChange("Download failed: ${e.message}")
            ZeroClawService.log("Cloudflare: download error — ${e.message}")
            targetFile.delete()
            false
        }
    }

    /**
     * Quick Tunnel — free, no account, generates random trycloudflare.com URL.
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

            tunnelProcess = buildProcess(
                binary, "tunnel", "--url", "http://localhost:$TARGET_PORT"
            ).start()

            val reader = tunnelProcess!!.inputStream.bufferedReader()
            withContext(Dispatchers.IO) {
                val urlRegex = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com")
                var urlFound = false
                var lineCount = 0

                while (!urlFound && lineCount < 300) {
                    val line = reader.readLine() ?: break
                    lineCount++

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
                    ZeroClawService.log("Cloudflare: failed to get URL after $lineCount lines of output")
                    val ip = getLocalIp()
                    onUrlReady("http://$ip:$TARGET_PORT")
                }

                // Keep reading to prevent buffer deadlock
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
        } catch (e: Exception) {
            status = "failed"
            onStatusChange("Tunnel error: ${e.message}")
            ZeroClawService.log("Cloudflare: quick tunnel error — ${e.message}")
            val ip = getLocalIp()
            onUrlReady("http://$ip:$TARGET_PORT")
        }
    }

    /**
     * Named Tunnel — persistent URL via Cloudflare token.
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

            tunnelProcess = buildProcess(
                binary, "tunnel", "run", "--token", token
            ).start()

            val reader = tunnelProcess!!.inputStream.bufferedReader()
            withContext(Dispatchers.IO) {
                val urlRegex = Regex("https://[a-z0-9.-]+\\.[a-z]{2,}")
                var urlFound = false
                var lineCount = 0

                while (!urlFound && lineCount < 300) {
                    val line = reader.readLine() ?: break
                    lineCount++

                    if (line.contains("ERR") || line.contains("error")) {
                        ZeroClawService.log("Cloudflare: $line")
                    }

                    if (line.contains("Registered tunnel connection") || line.contains("Connection registered")) {
                        status = "connected"
                        onStatusChange("Named tunnel connected")
                        ZeroClawService.log("Cloudflare: named tunnel connected")
                        urlFound = true
                    }

                    if (!urlFound) {
                        val match = urlRegex.find(line)
                        if (match != null && !match.value.contains("cloudflare.com/") && !match.value.contains("api.")) {
                            urlFound = true
                            status = "connected"
                            onStatusChange("Connected: ${match.value}")
                            ZeroClawService.log("Cloudflare: named tunnel live → ${match.value}")
                            onUrlReady(match.value)
                        }
                    }
                }

                if (!urlFound) {
                    status = "connected_no_url"
                    ZeroClawService.log("Cloudflare: named tunnel started — check Cloudflare dashboard for URL")
                    onStatusChange("Connected — check Cloudflare dashboard for URL")
                }

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
        } catch (e: Exception) {
            status = "failed"
            onStatusChange("Named tunnel error: ${e.message}")
            ZeroClawService.log("Cloudflare: named tunnel error — ${e.message}")
        }
    }

    private suspend fun startNgrok(binary: File, onUrlReady: (String) -> Unit) {
        try {
            ZeroClawService.log("Starting ngrok tunnel → localhost:$TARGET_PORT")
            tunnelProcess = ProcessBuilder(
                binary.absolutePath, "http", TARGET_PORT.toString(), "--log=stdout"
            )
                .redirectErrorStream(true)
                .start()
            delay(3000)
            pollNgrokApi(onUrlReady)
        } catch (e: Exception) {
            ZeroClawService.log("ngrok error: ${e.message}")
        }
    }

    private suspend fun pollNgrokApi(onUrlReady: (String) -> Unit) {
        repeat(10) { attempt ->
            delay(2000)
            try {
                val request = Request.Builder().url("http://127.0.0.1:4040/api/tunnels").build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@use
                    val json = JSONObject(body)
                    val tunnels = json.getJSONArray("tunnels")
                    for (i in 0 until tunnels.length()) {
                        val tunnel = tunnels.getJSONObject(i)
                        val proto = tunnel.getString("proto")
                        if (proto == "https") {
                            val url = tunnel.getString("public_url")
                            onUrlReady(url)
                            status = "connected"
                            ZeroClawService.log("ngrok URL: $url")
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                if (attempt == 9) ZeroClawService.log("ngrok API not responding. Is ngrok running?")
            }
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
        val native1 = File(context.applicationInfo.nativeLibraryDir, CLOUDFLARED_BINARY)
        val files1 = File(context.filesDir, "cloudflared")
        return (native1.exists() && native1.canExecute()) || (files1.exists() && files1.canExecute())
    }

    fun deleteBinary() {
        File(context.applicationInfo.nativeLibraryDir, CLOUDFLARED_BINARY).delete()
        File(context.filesDir, "cloudflared").delete()
        status = "idle"
    }

    fun stop() {
        tunnelProcess?.destroyForcibly()
        tunnelProcess = null
        status = "idle"
    }
}
