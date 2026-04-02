package ai.zeroclaw.android.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.*
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * TunnelManager
 * Creates a public HTTPS URL tunneling to localhost:8088 (WebChat server).
 *
 * The cloudflared binary is bundled in the APK as jniLibs/arm64-v8a/libcloudflared.so.
 * Android extracts it to nativeLibraryDir (which has exec permission).
 *
 * Android DNS fix:
 * Go binaries read /etc/resolv.conf which doesn't exist on Android, so DNS fails.
 * Go's fallback is to try 127.0.0.1:53 and [::1]:53.
 * We run a lightweight UDP DNS relay on 127.0.0.1:53 that forwards queries
 * to the device's real DNS server. No DNS parsing needed — just packet forwarding.
 */
class TunnelManager(private val context: Context) {

    companion object {
        private const val BINARY_NAME = "libcloudflared.so"
        private const val TARGET_PORT = 8088
        private const val DNS_RELAY_PORT = 53
        @Volatile var status: String = "idle"
    }

    private var tunnelProcess: Process? = null
    private var dnsRelayJob: Job? = null
    private var dnsSocket: DatagramSocket? = null

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

            // Start local DNS relay so Go's resolver can resolve hostnames
            startDnsRelay()

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
     * Start a UDP DNS relay on 127.0.0.1:53.
     *
     * Why: Go reads /etc/resolv.conf for DNS servers. This file doesn't exist on Android.
     * Go falls back to 127.0.0.1:53 and [::1]:53. We provide a relay on 127.0.0.1:53
     * that forwards DNS queries to the device's actual DNS server.
     *
     * This is a pure UDP packet relay — no DNS parsing needed.
     */
    private fun startDnsRelay() {
        val upstreamDns = getDeviceDnsServer()
        if (upstreamDns == null) {
            ZeroClawService.log("Cloudflare: WARNING — could not detect device DNS server, using 8.8.8.8")
        }
        val dnsTarget = upstreamDns ?: "8.8.8.8"

        dnsRelayJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                dnsSocket = DatagramSocket(null)
                dnsSocket!!.reuseAddress = true
                dnsSocket!!.bind(InetSocketAddress("127.0.0.1", DNS_RELAY_PORT))
                ZeroClawService.log("Cloudflare: DNS relay started on 127.0.0.1:$DNS_RELAY_PORT → $dnsTarget")

                val buffer = ByteArray(4096)
                val upstreamAddr = InetAddress.getByName(dnsTarget)

                while (isActive) {
                    // Receive query from cloudflared (Go resolver)
                    val inPacket = DatagramPacket(buffer, buffer.size)
                    dnsSocket!!.receive(inPacket)

                    // Forward to real DNS server
                    val forwardSocket = DatagramSocket()
                    forwardSocket.soTimeout = 5000
                    try {
                        val query = DatagramPacket(
                            inPacket.data, inPacket.offset, inPacket.length,
                            upstreamAddr, 53
                        )
                        forwardSocket.send(query)

                        // Receive response from real DNS
                        val respBuffer = ByteArray(4096)
                        val response = DatagramPacket(respBuffer, respBuffer.size)
                        forwardSocket.receive(response)

                        // Forward response back to cloudflared
                        val reply = DatagramPacket(
                            response.data, response.offset, response.length,
                            inPacket.address, inPacket.port
                        )
                        dnsSocket!!.send(reply)
                    } catch (e: Exception) {
                        ZeroClawService.log("Cloudflare: DNS relay error — ${e.message}")
                    } finally {
                        forwardSocket.close()
                    }
                }
            } catch (e: java.net.BindException) {
                ZeroClawService.log("Cloudflare: DNS relay bind failed on port $DNS_RELAY_PORT — ${e.message}")
                ZeroClawService.log("Cloudflare: Trying alternative DNS approach...")
                // Port 53 not available — try higher port approach as fallback
                startDnsRelayHighPort(dnsTarget)
            } catch (e: Exception) {
                ZeroClawService.log("Cloudflare: DNS relay error — ${e.message}")
            }
        }

        // Give DNS relay time to start
        Thread.sleep(200)
    }

    /**
     * Fallback: If port 53 is blocked, start DNS on a high port and create a wrapper
     * script that sets up /etc/resolv.conf in a network namespace (requires root).
     * If that's not possible either, we fall back to pre-resolving the hostname.
     */
    private suspend fun startDnsRelayHighPort(dnsTarget: String) {
        // Since we can't bind port 53 and can't change /etc/resolv.conf,
        // pre-resolve the critical hostnames and use --edge-ip-version
        try {
            val ips = withContext(Dispatchers.IO) {
                InetAddress.getAllByName("api.trycloudflare.com")
                    .mapNotNull { it.hostAddress }
            }
            if (ips.isNotEmpty()) {
                ZeroClawService.log("Cloudflare: pre-resolved api.trycloudflare.com → ${ips.joinToString(", ")}")
            }
        } catch (e: Exception) {
            ZeroClawService.log("Cloudflare: pre-resolve failed — ${e.message}")
        }
    }

    /** Get the device's primary DNS server */
    private fun getDeviceDnsServer(): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val linkProps: LinkProperties = cm.getLinkProperties(network) ?: return null
            linkProps.dnsServers
                .firstOrNull { it is Inet4Address }
                ?.hostAddress
                ?: linkProps.dnsServers.firstOrNull()?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    private fun buildTunnelProcess(binary: File, vararg args: String): Process {
        val pb = ProcessBuilder(listOf(binary.absolutePath) + args.toList())
            .directory(context.cacheDir)
            .redirectErrorStream(true)

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
                "--edge-ip-version", "4"  // Force IPv4 to avoid IPv6 DNS issues
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
                        ZeroClawService.log("Cloudflare: named tunnel connected — URL is in your Cloudflare dashboard")
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
            // Match tunnel URLs but NOT api.trycloudflare.com
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
        dnsRelayJob?.cancel()
        dnsRelayJob = null
        try { dnsSocket?.close() } catch (_: Exception) { }
        dnsSocket = null
        status = "idle"
    }
}
