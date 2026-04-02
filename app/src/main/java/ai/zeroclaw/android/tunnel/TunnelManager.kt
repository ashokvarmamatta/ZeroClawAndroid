package ai.zeroclaw.android.tunnel

import android.content.Context
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
 * Strategy:
 * 1. Auto-download cloudflared ARM64 binary if not present
 * 2. Quick Tunnel mode (free, no account — trycloudflare.com)
 * 3. Named Tunnel mode (persistent URL — requires Cloudflare token)
 * 4. ngrok fallback (if binary manually placed)
 * 5. Local IP fallback (LAN only)
 */
class TunnelManager(private val context: Context) {

    companion object {
        private const val CLOUDFLARED_BINARY = "cloudflared"
        private const val CLOUDFLARED_URL =
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"
        private const val TARGET_PORT = 8088
        /** Current download/tunnel status for UI display */
        @Volatile var status: String = "idle"
        @Volatile var isDownloading: Boolean = false
    }

    private var tunnelProcess: Process? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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
            val binary = File(context.filesDir, CLOUDFLARED_BINARY)

            // Try ngrok first if available (user manually placed it)
            val ngrokBinary = File(context.filesDir, "ngrok")
            if (ngrokBinary.exists() && ngrokBinary.canExecute()) {
                startNgrok(ngrokBinary, onUrlReady)
                return@withContext
            }

            // Auto-download cloudflared if not present
            if (!binary.exists() || !binary.canExecute()) {
                val downloaded = downloadCloudflared(binary, onStatusChange)
                if (!downloaded) {
                    val ip = getLocalIp()
                    val url = "http://$ip:$TARGET_PORT"
                    status = "download_failed"
                    onStatusChange("Download failed")
                    ZeroClawService.log("Cloudflare: download failed. Local URL: $url")
                    onUrlReady(url)
                    return@withContext
                }
            }

            // Start tunnel based on mode
            when (mode) {
                "token" -> {
                    if (token.isNotBlank()) {
                        startNamedTunnel(binary, token, onUrlReady, onStatusChange)
                    } else {
                        ZeroClawService.log("Cloudflare: token mode selected but no token provided, falling back to quick tunnel")
                        startQuickTunnel(binary, onUrlReady, onStatusChange)
                    }
                }
                else -> startQuickTunnel(binary, onUrlReady, onStatusChange)
            }
        }
    }

    /**
     * Download cloudflared-linux-arm64 binary from GitHub releases.
     * Returns true on success.
     */
    private fun downloadCloudflared(targetFile: File, onStatusChange: (String) -> Unit): Boolean {
        return try {
            isDownloading = true
            status = "downloading"
            onStatusChange("Downloading cloudflared...")
            ZeroClawService.log("Cloudflare: downloading ARM64 binary...")

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

                FileOutputStream(targetFile).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                val pct = (downloadedBytes * 100 / totalBytes).toInt()
                                if (pct % 10 == 0) {
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
            isDownloading = false
            status = "downloaded"
            onStatusChange("Download complete")
            ZeroClawService.log("Cloudflare: binary downloaded (${targetFile.length() / 1024}KB)")
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
     * URL changes on every restart.
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
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()

            // Read stdout/stderr to find the trycloudflare.com URL
            val reader = tunnelProcess!!.inputStream.bufferedReader()
            withContext(Dispatchers.IO) {
                val urlRegex = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com")
                var urlFound = false
                var lineCount = 0

                while (!urlFound && lineCount < 200) {
                    val line = reader.readLine() ?: break
                    lineCount++

                    // Log important cloudflared output
                    if (line.contains("ERR") || line.contains("error")) {
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
                    ZeroClawService.log("Cloudflare: failed to get URL after $lineCount lines")
                    val ip = getLocalIp()
                    onUrlReady("http://$ip:$TARGET_PORT")
                }

                // Keep reading to prevent buffer deadlock (run in background)
                launch(Dispatchers.IO) {
                    try {
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.contains("ERR") || line.contains("Reconnecting")) {
                                ZeroClawService.log("Cloudflare: $line")
                            }
                        }
                    } catch (_: Exception) { /* process ended */ }
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
     * Named Tunnel — uses a pre-configured Cloudflare tunnel token.
     * Provides a persistent URL (doesn't change on restart).
     * User creates the tunnel at: https://one.dash.cloudflare.com → Networks → Tunnels
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
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()

            val reader = tunnelProcess!!.inputStream.bufferedReader()
            withContext(Dispatchers.IO) {
                // Named tunnels log their configured hostname
                val urlRegex = Regex("https://[a-z0-9.-]+\\.[a-z]{2,}")
                var urlFound = false
                var lineCount = 0

                while (!urlFound && lineCount < 200) {
                    val line = reader.readLine() ?: break
                    lineCount++

                    if (line.contains("ERR") || line.contains("error")) {
                        ZeroClawService.log("Cloudflare: $line")
                    }

                    // Named tunnels log "Registered tunnel connection" with the hostname
                    if (line.contains("Registered tunnel connection") || line.contains("Connection registered")) {
                        // The URL is the hostname configured in the Cloudflare dashboard
                        // We need to get it from the tunnel info
                        status = "connected"
                        onStatusChange("Named tunnel connected")
                        ZeroClawService.log("Cloudflare: named tunnel connected")
                        // For named tunnels, the user knows their URL — we signal connection is ready
                        // Try to extract URL from logs, otherwise the user provides it
                        urlFound = true
                    }

                    // Also check for any https URL in the output
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
                    // Named tunnel connected but we couldn't extract URL — this is normal
                    // The user already knows their hostname from Cloudflare dashboard
                    status = "connected_no_url"
                    ZeroClawService.log("Cloudflare: named tunnel started — check your Cloudflare dashboard for the URL")
                    onStatusChange("Connected — check Cloudflare dashboard for URL")
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
                    } catch (_: Exception) { /* process ended */ }
                }
            }
        } catch (e: Exception) {
            status = "failed"
            onStatusChange("Named tunnel error: ${e.message}")
            ZeroClawService.log("Cloudflare: named tunnel error — ${e.message}")
        }
    }

    /** ngrok fallback (binary must be manually placed in filesDir) */
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

    /** Check if cloudflared binary is already downloaded */
    fun isBinaryReady(): Boolean {
        val binary = File(context.filesDir, CLOUDFLARED_BINARY)
        return binary.exists() && binary.canExecute()
    }

    /** Delete the downloaded binary (to force re-download) */
    fun deleteBinary() {
        File(context.filesDir, CLOUDFLARED_BINARY).delete()
        status = "idle"
    }

    fun stop() {
        tunnelProcess?.destroyForcibly()
        tunnelProcess = null
        status = "idle"
    }
}
