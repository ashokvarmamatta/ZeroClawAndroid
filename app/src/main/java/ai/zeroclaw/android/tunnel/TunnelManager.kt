package ai.zeroclaw.android.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.*
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * TunnelManager
 * Creates a public HTTPS URL tunneling to localhost:8088 (WebChat server).
 *
 * The cloudflared binary is bundled in the APK as jniLibs/arm64-v8a/libcloudflared.so.
 * Android extracts it to nativeLibraryDir (which has exec permission).
 *
 * Android DNS fix:
 * Go reads /etc/resolv.conf (hardcoded, can't override). Doesn't exist on Android.
 * Port 53 is blocked for non-root apps. So we use an HTTP CONNECT proxy instead.
 * Go's net/http respects HTTPS_PROXY env var. Our proxy resolves DNS using Android's
 * resolver (InetAddress.getByName), then tunnels the TCP connection transparently.
 */
class TunnelManager(private val context: Context) {

    companion object {
        private const val BINARY_NAME = "libcloudflared.so"
        private const val TARGET_PORT = 8088
        private const val PROXY_PORT = 18053
        @Volatile var status: String = "idle"
    }

    private var tunnelProcess: Process? = null
    private var proxyJob: Job? = null
    private var proxySocket: ServerSocket? = null

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
                ZeroClawService.log("Cloudflare: binary not found at ${binary.absolutePath}")
                onStatusChange("Binary not found — reinstall app")
                onUrlReady("http://${getLocalIp()}:$TARGET_PORT")
                return@withContext
            }

            if (!binary.canExecute()) binary.setExecutable(true)
            ZeroClawService.log("Cloudflare: binary ready (${binary.length() / 1024}KB)")

            // Start HTTPS CONNECT proxy for DNS resolution
            startConnectProxy()

            when (mode) {
                "token" -> {
                    if (token.isNotBlank()) {
                        startNamedTunnel(binary, token, onUrlReady, onStatusChange)
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
     * HTTP CONNECT proxy on 127.0.0.1:PROXY_PORT.
     *
     * When cloudflared makes HTTPS requests, Go checks HTTPS_PROXY env var.
     * Our proxy:
     * 1. Receives CONNECT hostname:port request
     * 2. Resolves hostname using Android's InetAddress.getByName() (working DNS)
     * 3. Connects to resolved IP:port
     * 4. Sends "200 Connection Established" back
     * 5. Relays data bidirectionally (transparent tunnel)
     */
    private fun startConnectProxy() {
        proxyJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                proxySocket = ServerSocket()
                proxySocket!!.reuseAddress = true
                proxySocket!!.bind(InetSocketAddress("127.0.0.1", PROXY_PORT))
                ZeroClawService.log("Cloudflare: CONNECT proxy started on 127.0.0.1:$PROXY_PORT")

                while (isActive) {
                    val clientSocket = proxySocket!!.accept()
                    launch { handleConnectRequest(clientSocket) }
                }
            } catch (e: Exception) {
                ZeroClawService.log("Cloudflare: proxy error — ${e.message}")
            }
        }

        // Give proxy time to start
        Thread.sleep(100)
    }

    private fun handleConnectRequest(client: Socket) {
        try {
            client.soTimeout = 30000
            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()

            // Read the HTTP CONNECT request line
            val requestLine = readLine(clientIn)
            // e.g. "CONNECT api.trycloudflare.com:443 HTTP/1.1"

            if (!requestLine.startsWith("CONNECT ")) {
                // Not a CONNECT request — send error and close
                clientOut.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".toByteArray())
                client.close()
                return
            }

            // Parse host:port
            val target = requestLine.substringAfter("CONNECT ").substringBefore(" HTTP")
            val host = target.substringBefore(":")
            val port = target.substringAfter(":").toIntOrNull() ?: 443

            // Read and discard remaining headers (until empty line)
            while (true) {
                val line = readLine(clientIn)
                if (line.isEmpty()) break
            }

            // Resolve hostname using Android's DNS (this works!)
            val resolved = InetAddress.getByName(host)
            ZeroClawService.log("Cloudflare: proxy CONNECT $host → ${resolved.hostAddress}:$port")

            // Connect to the resolved address
            val upstream = Socket()
            upstream.connect(InetSocketAddress(resolved, port), 10000)
            upstream.soTimeout = 60000

            // Send success response
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            clientOut.flush()

            // Relay data bidirectionally
            val job1 = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = clientIn.read(buf)
                        if (n <= 0) break
                        upstream.getOutputStream().write(buf, 0, n)
                        upstream.getOutputStream().flush()
                    }
                } catch (_: Exception) { }
                try { upstream.shutdownOutput() } catch (_: Exception) { }
            }

            val job2 = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = upstream.getInputStream().read(buf)
                        if (n <= 0) break
                        clientOut.write(buf, 0, n)
                        clientOut.flush()
                    }
                } catch (_: Exception) { }
                try { client.shutdownOutput() } catch (_: Exception) { }
            }

            // Wait for both directions to finish
            runBlocking {
                job1.join()
                job2.join()
            }

            upstream.close()
            client.close()

        } catch (e: Exception) {
            try {
                client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n${e.message}".toByteArray())
            } catch (_: Exception) { }
            client.close()
        }
    }

    /** Read a line from InputStream (until \r\n or \n) */
    private fun readLine(input: java.io.InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun buildTunnelProcess(binary: File, vararg args: String): Process {
        val pb = ProcessBuilder(listOf(binary.absolutePath) + args.toList())
            .directory(context.cacheDir)
            .redirectErrorStream(true)

        // Route all HTTPS traffic through our CONNECT proxy (fixes DNS)
        pb.environment()["HTTPS_PROXY"] = "http://127.0.0.1:$PROXY_PORT"
        pb.environment()["HTTP_PROXY"] = "http://127.0.0.1:$PROXY_PORT"
        pb.environment()["HOME"] = context.cacheDir.absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath

        return pb.start()
    }

    private suspend fun startQuickTunnel(
        binary: File,
        onUrlReady: (String) -> Unit,
        onStatusChange: (String) -> Unit
    ) {
        try {
            status = "starting"
            onStatusChange("Starting Cloudflare Quick Tunnel...")
            ZeroClawService.log("Cloudflare: starting quick tunnel → localhost:$TARGET_PORT")

            tunnelProcess = buildTunnelProcess(
                binary, "tunnel", "--url", "http://localhost:$TARGET_PORT",
                "--edge-ip-version", "4"
            )

            parseOutputForUrl(tunnelProcess!!, onUrlReady, onStatusChange)
        } catch (e: Exception) {
            status = "failed"
            onStatusChange("Tunnel error: ${e.message}")
            ZeroClawService.log("Cloudflare: quick tunnel error — ${e.message}")
            onUrlReady("http://${getLocalIp()}:$TARGET_PORT")
        }
    }

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

            tunnelProcess = buildTunnelProcess(
                binary, "tunnel", "run", "--token", token,
                "--edge-ip-version", "4"
            )

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
                        ZeroClawService.log("Cloudflare: named tunnel connected — check Cloudflare dashboard for URL")
                    }

                    if (!connected) {
                        val urlMatch = Regex("https://[a-z0-9.-]+\\.[a-z]{2,}").find(line)
                        if (urlMatch != null
                            && !urlMatch.value.contains("api.cloudflare")
                            && !urlMatch.value.contains("api.trycloudflare")
                            && !urlMatch.value.contains("update.")
                        ) {
                            connected = true
                            status = "connected"
                            val url = urlMatch.value
                            onStatusChange("Connected: $url")
                            ZeroClawService.log("Cloudflare: named tunnel live → $url")
                            onUrlReady(url)
                        }
                    }
                }

                if (!connected) {
                    status = "failed"
                    ZeroClawService.log("Cloudflare: named tunnel failed after $lineCount lines")
                    onStatusChange("Named tunnel failed to connect")
                }

                drainOutput(reader)
            }
        } catch (e: Exception) {
            status = "failed"
            onStatusChange("Named tunnel error: ${e.message}")
            ZeroClawService.log("Cloudflare: named tunnel error — ${e.message}")
        }
    }

    private suspend fun parseOutputForUrl(
        process: Process,
        onUrlReady: (String) -> Unit,
        onStatusChange: (String) -> Unit
    ) {
        val reader = process.inputStream.bufferedReader()
        withContext(Dispatchers.IO) {
            val urlRegex = Regex("https://(?!api\\.)[a-z0-9][a-z0-9-]*[a-z0-9]\\.trycloudflare\\.com")
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
                ZeroClawService.log("Cloudflare: no tunnel URL found after $lineCount lines")
                onUrlReady("http://${getLocalIp()}:$TARGET_PORT")
            }

            drainOutput(reader)
        }
    }

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
        proxyJob?.cancel()
        proxyJob = null
        try { proxySocket?.close() } catch (_: Exception) { }
        proxySocket = null
        status = "idle"
    }
}
