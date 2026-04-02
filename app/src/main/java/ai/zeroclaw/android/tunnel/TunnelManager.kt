package ai.zeroclaw.android.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.net.Inet4Address

/**
 * TunnelManager
 * Creates a public HTTPS URL tunneling to localhost:8088 (WebChat server).
 *
 * The cloudflared binary is bundled in the APK as jniLibs/arm64-v8a/libcloudflared.so.
 * Android extracts it to nativeLibraryDir (which has exec permission).
 *
 * Android DNS fix: Go binaries read /etc/resolv.conf which doesn't exist on Android.
 * We create a resolv.conf in cacheDir with the device's real DNS servers and point
 * Go's resolver to it via the GODEBUG and RES_OPTIONS environment variables.
 */
class TunnelManager(private val context: Context) {

    companion object {
        private const val BINARY_NAME = "libcloudflared.so"
        private const val TARGET_PORT = 8088
        @Volatile var status: String = "idle"
    }

    private var tunnelProcess: Process? = null

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

            // Create resolv.conf for Go's DNS resolver (Android doesn't have /etc/resolv.conf)
            val resolvConf = createResolvConf()

            when (mode) {
                "token" -> {
                    if (token.isNotBlank()) {
                        startNamedTunnel(binary, token, resolvConf, onUrlReady, onStatusChange)
                    } else {
                        ZeroClawService.log("Cloudflare: no token — using quick tunnel")
                        startQuickTunnel(binary, resolvConf, onUrlReady, onStatusChange)
                    }
                }
                else -> startQuickTunnel(binary, resolvConf, onUrlReady, onStatusChange)
            }
        }
    }

    /**
     * Create a resolv.conf file with the device's actual DNS servers.
     * Go's pure-Go DNS resolver reads /etc/resolv.conf which doesn't exist on Android.
     * We create one in cacheDir and point to it via environment variable.
     */
    private fun createResolvConf(): File {
        val resolvFile = File(context.cacheDir, "resolv.conf")
        try {
            val dnsServers = getDeviceDnsServers()
            FileWriter(resolvFile).use { writer ->
                if (dnsServers.isNotEmpty()) {
                    dnsServers.forEach { dns -> writer.write("nameserver $dns\n") }
                    ZeroClawService.log("Cloudflare: DNS servers → ${dnsServers.joinToString(", ")}")
                } else {
                    // Fallback to public DNS
                    writer.write("nameserver 8.8.8.8\n")
                    writer.write("nameserver 1.1.1.1\n")
                    ZeroClawService.log("Cloudflare: using fallback DNS (8.8.8.8, 1.1.1.1)")
                }
            }
        } catch (e: Exception) {
            // If we can't create it, write fallback
            FileWriter(resolvFile).use { writer ->
                writer.write("nameserver 8.8.8.8\n")
                writer.write("nameserver 1.1.1.1\n")
            }
            ZeroClawService.log("Cloudflare: DNS config error, using fallback — ${e.message}")
        }
        return resolvFile
    }

    /** Get the device's actual DNS servers from ConnectivityManager */
    private fun getDeviceDnsServers(): List<String> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return emptyList()
            val linkProps: LinkProperties = cm.getLinkProperties(network) ?: return emptyList()
            linkProps.dnsServers
                .mapNotNull { it.hostAddress }
                .filter { !it.contains(":") } // Prefer IPv4 DNS
                .ifEmpty {
                    // Include IPv6 DNS if no IPv4 available
                    linkProps.dnsServers.mapNotNull { it.hostAddress }
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Build a ProcessBuilder with proper environment for Android.
     * Sets up DNS resolution so Go can find DNS servers.
     */
    private fun buildTunnelProcess(binary: File, resolvConf: File, vararg args: String): Process {
        val pb = ProcessBuilder(listOf(binary.absolutePath) + args.toList())
            .directory(context.cacheDir)
            .redirectErrorStream(true)

        // Point Go's DNS resolver to our custom resolv.conf
        pb.environment()["GODEBUG"] = "netdns=go"
        pb.environment()["RES_CONF"] = resolvConf.absolutePath
        // Also set HOME so cloudflared can write temp files
        pb.environment()["HOME"] = context.cacheDir.absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath

        return pb.start()
    }

    /**
     * Quick Tunnel — free, no account, random trycloudflare.com URL.
     */
    private suspend fun startQuickTunnel(
        binary: File,
        resolvConf: File,
        onUrlReady: (String) -> Unit,
        onStatusChange: (String) -> Unit
    ) {
        try {
            status = "starting"
            onStatusChange("Starting Cloudflare Quick Tunnel...")
            ZeroClawService.log("Cloudflare: starting quick tunnel → localhost:$TARGET_PORT")

            tunnelProcess = buildTunnelProcess(
                binary, resolvConf,
                "tunnel", "--url", "http://localhost:$TARGET_PORT"
            )

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
     */
    private suspend fun startNamedTunnel(
        binary: File,
        token: String,
        resolvConf: File,
        onUrlReady: (String) -> Unit,
        onStatusChange: (String) -> Unit
    ) {
        try {
            status = "starting"
            onStatusChange("Starting Cloudflare Named Tunnel...")
            ZeroClawService.log("Cloudflare: starting named tunnel with token")

            tunnelProcess = buildTunnelProcess(
                binary, resolvConf,
                "tunnel", "run", "--token", token
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

                    // Try to extract the configured hostname
                    if (!connected) {
                        val urlMatch = Regex("https://[a-z0-9.-]+\\.[a-z]{2,}").find(line)
                        if (urlMatch != null
                            && !urlMatch.value.contains("api.cloudflare")
                            && !urlMatch.value.contains("update.")
                            && !urlMatch.value.contains("trycloudflare.com")
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

    /**
     * Parse cloudflared output looking for the trycloudflare.com URL.
     * Only matches actual tunnel URLs (random-word.trycloudflare.com),
     * NOT api.trycloudflare.com from error messages.
     */
    private suspend fun parseOutputForUrl(
        process: Process,
        onUrlReady: (String) -> Unit,
        onStatusChange: (String) -> Unit
    ) {
        val reader = process.inputStream.bufferedReader()
        withContext(Dispatchers.IO) {
            // Match random-word-style tunnel URLs, NOT api.trycloudflare.com
            val urlRegex = Regex("https://(?!api\\.)[a-z0-9][a-z0-9-]*[a-z0-9]\\.trycloudflare\\.com")
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
        status = "idle"
    }
}
