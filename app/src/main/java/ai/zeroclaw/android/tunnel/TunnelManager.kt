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
 * Creates a public HTTPS URL tunneling to localhost so that:
 * - Telegram webhooks can reach this device
 * - Twilio webhooks for WhatsApp can reach this device
 *
 * Strategy:
 * 1. Try ngrok (if binary is available in app files dir)
 * 2. Fall back to Cloudflare tunnel
 * 3. Fall back to a simple self-reported local IP (for LAN use)
 */
class TunnelManager(private val context: Context) {

    private var tunnelProcess: Process? = null
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()

    suspend fun start(onUrlReady: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            // Try to use ngrok if available
            val ngrokBinary = File(context.filesDir, "ngrok")
            if (ngrokBinary.exists() && ngrokBinary.canExecute()) {
                startNgrok(ngrokBinary, onUrlReady)
            } else {
                // Try Cloudflare tunnel
                val cloudflareBinary = File(context.filesDir, "cloudflared")
                if (cloudflareBinary.exists() && cloudflareBinary.canExecute()) {
                    startCloudflare(cloudflareBinary, onUrlReady)
                } else {
                    // Fallback: report local IP
                    val ip = getLocalIp()
                    val url = "http://$ip:3000"
                    ZeroClawService.log("No tunnel binary found. Local URL: $url")
                    ZeroClawService.log("For webhooks: download ngrok ARM64 binary to ${context.filesDir}/ngrok")
                    onUrlReady(url)
                    pollNgrokApi(onUrlReady) // Keep polling in case ngrok starts later
                }
            }
        }
    }

    private suspend fun startNgrok(binary: File, onUrlReady: (String) -> Unit) {
        try {
            ZeroClawService.log("Starting ngrok tunnel...")
            tunnelProcess = ProcessBuilder(binary.absolutePath, "http", "3000", "--log=stdout")
                .redirectErrorStream(true)
                .start()
            // Poll ngrok local API for the public URL
            delay(3000)
            pollNgrokApi(onUrlReady)
        } catch (e: Exception) {
            ZeroClawService.log("ngrok error: ${e.message}")
        }
    }

    private suspend fun startCloudflare(binary: File, onUrlReady: (String) -> Unit) {
        try {
            ZeroClawService.log("Starting Cloudflare tunnel...")
            tunnelProcess = ProcessBuilder(binary.absolutePath, "tunnel", "--url", "http://localhost:3000")
                .redirectErrorStream(true)
                .start()
            // Read output to find the URL
            val reader = tunnelProcess!!.inputStream.bufferedReader()
            var urlFound = false
            withContext(Dispatchers.IO) {
                while (!urlFound) {
                    val line = reader.readLine() ?: break
                    if (line.contains("trycloudflare.com") || line.contains("https://")) {
                        val urlRegex = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com")
                        val match = urlRegex.find(line)
                        if (match != null) {
                            onUrlReady(match.value)
                            urlFound = true
                            ZeroClawService.log("Cloudflare URL: ${match.value}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ZeroClawService.log("Cloudflare error: ${e.message}")
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

    private fun getLocalIp(): String {
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

    fun stop() {
        tunnelProcess?.destroy()
        tunnelProcess = null
    }
}
