package ai.zeroclaw.android.tunnel

import android.content.Context
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

/**
 * TunnelManager — Cloudflare tunnel for Android
 *
 * Problem: Go's DNS reads /etc/resolv.conf (doesn't exist on Android).
 *          Port 53 needs root. HTTPS_PROXY ignored by cloudflared's custom transport.
 *
 * Solution: Make the tunnel API call from Java (DNS works here), get credentials,
 *           write them to disk, then run cloudflared with --credentials-file.
 *           With --edge-ip-version 4, cloudflared uses hardcoded edge IPs (no DNS needed).
 *
 * Flow:
 *   1. Java: POST https://api.trycloudflare.com/tunnel → get tunnel ID, secret, hostname
 *   2. Java: Write credentials.json + config.yml
 *   3. Run:  cloudflared tunnel --edge-ip-version 4 --credentials-file ... run <tunnel-id>
 *   4. Edge connection uses hardcoded IPs → no DNS needed!
 */
class TunnelManager(private val context: Context) {

    companion object {
        private const val BINARY_NAME = "libcloudflared.so"
        private const val TARGET_PORT = 8088
        private const val TUNNEL_API = "https://api.trycloudflare.com/tunnel"
        @Volatile var status: String = "idle"
    }

    private var tunnelProcess: Process? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun start(
        mode: String = "quick",
        token: String = "",
        hostname: String = "",
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
                ZeroClawService.log("Cloudflare: binary not found at ${binary.absolutePath}")
                onStatusChange("Binary not found — reinstall app")
                onUrlReady("http://${getLocalIp()}:$TARGET_PORT")
                return@withContext
            }

            if (!binary.canExecute()) binary.setExecutable(true)
            ZeroClawService.log("Cloudflare: binary ready (${binary.length() / 1024}KB)")

            when (mode) {
                "token" -> {
                    if (token.isNotBlank()) {
                        startNamedTunnel(binary, token, hostname, onUrlReady, onStatusChange)
                    } else {
                        ZeroClawService.log("Cloudflare: no token — using quick tunnel")
                        startQuickTunnel(binary, onUrlReady, onStatusChange)
                    }
                }
                else -> startQuickTunnel(binary, onUrlReady, onStatusChange)
            }
        }
    }

    /**
     * Quick Tunnel — register via API from Java, then run cloudflared with credentials.
     */
    private suspend fun startQuickTunnel(
        binary: File,
        onUrlReady: (String) -> Unit,
        onStatusChange: (String) -> Unit
    ) {
        try {
            status = "starting"
            onStatusChange("Registering tunnel...")
            ZeroClawService.log("Cloudflare: requesting quick tunnel from API...")

            // Step 1: Register tunnel via API (Java handles DNS!)
            val tunnelInfo = registerQuickTunnel()
            if (tunnelInfo == null) {
                status = "failed"
                onStatusChange("Tunnel registration failed")
                onUrlReady("http://${getLocalIp()}:$TARGET_PORT")
                return
            }

            val tunnelId = tunnelInfo.getString("id")
            val tunnelHost = tunnelInfo.getString("hostname")
            val accountTag = tunnelInfo.getString("account_tag")
            val tunnelSecret = tunnelInfo.getString("secret")

            ZeroClawService.log("Cloudflare: tunnel registered → https://$tunnelHost")
            onStatusChange("Starting tunnel connection...")

            // Step 2: Write credentials file
            val credsFile = File(context.cacheDir, "tunnel_creds.json")
            FileWriter(credsFile).use { w ->
                val creds = JSONObject()
                creds.put("AccountTag", accountTag)
                creds.put("TunnelID", tunnelId)
                creds.put("TunnelSecret", tunnelSecret)
                w.write(creds.toString())
            }

            // Step 3: Write config file
            val configFile = File(context.cacheDir, "tunnel_config.yml")
            FileWriter(configFile).use { w ->
                w.write("tunnel: $tunnelId\n")
                w.write("credentials-file: ${credsFile.absolutePath}\n")
                w.write("protocol: http2\n")
                w.write("ingress:\n")
                w.write("  - hostname: $tunnelHost\n")
                w.write("    service: http://localhost:$TARGET_PORT\n")
                w.write("  - service: http_status:404\n")
            }

            // Step 4: Resolve edge server IPs from Java (Go's DNS is broken on Android)
            val edgeIps = resolveEdgeIps()
            ZeroClawService.log("Cloudflare: edge IPs resolved → ${edgeIps.joinToString(", ")}")

            // Step 5: Run cloudflared with credentials + pre-resolved edge IPs
            // --edge flag takes one address per flag instance
            ZeroClawService.log("Cloudflare: connecting to edge with tunnel $tunnelId")
            val cmd = mutableListOf(
                binary.absolutePath,
                "tunnel",
                "--config", configFile.absolutePath,
                "--edge-ip-version", "4",
                "--no-autoupdate"
            )
            for (ip in edgeIps.take(4)) {
                cmd.addAll(listOf("--edge", ip))
            }
            cmd.addAll(listOf("run", tunnelId))

            tunnelProcess = ProcessBuilder(cmd)
                .directory(context.cacheDir)
                .redirectErrorStream(true)
                .start()

            val tunnelUrl = "https://$tunnelHost"
            onStatusChange("Waiting for edge connection...")
            ZeroClawService.log("Cloudflare: process started, waiting for edge connection…")

            // Monitor cloudflared output — only announce URL once edge is connected
            val reader = tunnelProcess!!.inputStream.bufferedReader()
            var urlAnnounced = false

            withContext(Dispatchers.IO) {
                // Fallback: announce URL after 15s even if no edge confirmation
                val fallbackJob = launch(Dispatchers.IO) {
                    delay(15_000)
                    if (!urlAnnounced) {
                        urlAnnounced = true
                        ZeroClawService.log("Cloudflare: edge timeout — announcing URL anyway")
                        onUrlReady(tunnelUrl)
                        status = "connected"
                        onStatusChange("Connected: $tunnelUrl")
                    }
                }

                launch(Dispatchers.IO) {
                    try {
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.contains("ERR") || line.contains("error")
                                || line.contains("Registered") || line.contains("connected")) {
                                ZeroClawService.log("Cloudflare: $line")
                            }
                            // Detect successful edge connection — NOW announce the URL
                            if (line.contains("Registered tunnel connection") && !urlAnnounced) {
                                urlAnnounced = true
                                fallbackJob.cancel()
                                ZeroClawService.log("Cloudflare: tunnel live → $tunnelUrl")
                                onUrlReady(tunnelUrl)
                                status = "connected"
                                onStatusChange("Connected: $tunnelUrl")
                            }
                        }
                        // Process ended
                        ZeroClawService.log("Cloudflare: tunnel process exited")
                        status = "disconnected"
                    } catch (_: Exception) { }
                }
            }

        } catch (e: Exception) {
            status = "failed"
            onStatusChange("Tunnel error: ${e.message}")
            ZeroClawService.log("Cloudflare: quick tunnel error — ${e.message}")
            onUrlReady("http://${getLocalIp()}:$TARGET_PORT")
        }
    }

    /**
     * Register a quick tunnel via the Cloudflare API.
     * This is done from Java where DNS resolution works on Android.
     * Returns the tunnel info JSON object, or null on failure.
     */
    private fun registerQuickTunnel(): JSONObject? {
        return try {
            val request = Request.Builder()
                .url(TUNNEL_API)
                .post("".toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "ZeroClaw-Android/1.1")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    ZeroClawService.log("Cloudflare: API error HTTP ${response.code}: $body")
                    return null
                }

                val json = JSONObject(body)
                if (!json.optBoolean("success", false)) {
                    ZeroClawService.log("Cloudflare: API returned success=false: $body")
                    return null
                }

                json.getJSONObject("result")
            }
        } catch (e: Exception) {
            ZeroClawService.log("Cloudflare: API request failed — ${e.message}")
            null
        }
    }

    /**
     * Resolve Cloudflare edge server IPs from Java (Android DNS works here).
     * cloudflared normally looks up SRV record _v2-origintunneld._tcp.argotunnel.com
     * which fails on Android because Go can't do DNS. We resolve the edge hostnames
     * directly and pass them via --edge flag.
     *
     * Returns list of "ip:port" strings for --edge flags.
     */
    private fun resolveEdgeIps(): List<String> {
        val edgeHostnames = listOf(
            "region1.v2.argotunnel.com",
            "region2.v2.argotunnel.com"
        )
        val edgePort = 7844
        val resolvedAddrs = mutableListOf<String>()

        for (hostname in edgeHostnames) {
            try {
                val addresses = java.net.InetAddress.getAllByName(hostname)
                for (addr in addresses) {
                    if (addr is Inet4Address) {
                        resolvedAddrs.add("${addr.hostAddress}:$edgePort")
                    }
                }
            } catch (e: Exception) {
                ZeroClawService.log("Cloudflare: failed to resolve $hostname — ${e.message}")
            }
        }

        // Fallback: known Cloudflare edge IPs (may change, but rarely)
        if (resolvedAddrs.isEmpty()) {
            ZeroClawService.log("Cloudflare: using fallback edge IPs")
            resolvedAddrs.addAll(listOf(
                "198.41.192.167:$edgePort",
                "198.41.192.67:$edgePort",
                "198.41.200.13:$edgePort",
                "198.41.200.193:$edgePort"
            ))
        }

        return resolvedAddrs
    }

    /**
     * Named Tunnel — persistent URL via Cloudflare token.
     * User creates tunnel at: https://one.dash.cloudflare.com → Networks → Tunnels
     */
    private suspend fun startNamedTunnel(
        binary: File,
        token: String,
        hostname: String,
        onUrlReady: (String) -> Unit,
        onStatusChange: (String) -> Unit
    ) {
        try {
            status = "starting"
            onStatusChange("Starting Cloudflare Named Tunnel...")
            ZeroClawService.log("Cloudflare: starting named tunnel with token")

            // Resolve edge IPs from Java (same DNS fix as quick tunnel)
            val edgeIps = resolveEdgeIps()
            ZeroClawService.log("Cloudflare: edge IPs for named tunnel → ${edgeIps.joinToString(", ")}")

            val cmd = mutableListOf(
                binary.absolutePath,
                "tunnel",
                "--edge-ip-version", "4",
                "--no-autoupdate",
                "--protocol", "http2"
            )
            for (ip in edgeIps.take(4)) {
                cmd.addAll(listOf("--edge", ip))
            }
            cmd.addAll(listOf("run", "--token", token))

            tunnelProcess = ProcessBuilder(cmd)
                .directory(context.cacheDir)
                .redirectErrorStream(true)
                .start()

            // If user provided their domain, report it immediately
            if (hostname.isNotBlank()) {
                val url = if (hostname.startsWith("http")) hostname else "https://$hostname"
                onUrlReady(url)
                ZeroClawService.log("Cloudflare: named tunnel URL → $url")
            }

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
                        onStatusChange("Connected: $hostname")
                        ZeroClawService.log("Cloudflare: named tunnel connected! Edge link established.")
                    }
                }

                if (!connected) {
                    status = "failed"
                    ZeroClawService.log("Cloudflare: named tunnel failed after $lineCount lines")
                    onStatusChange("Named tunnel failed to connect")
                }

                // Drain output
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

    fun getLocalIp(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
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
