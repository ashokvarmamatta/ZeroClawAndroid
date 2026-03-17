package ai.zeroclaw.android.infra

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import ai.zeroclaw.android.service.ZeroClawService
import ai.zeroclaw.android.tools.QrEncoder
import org.json.JSONObject
import java.security.SecureRandom

/**
 * DevicePairing — Phase 129: Pair with desktop/other devices via QR code.
 *
 * Generates a pairing QR code that encodes the local WebChat server address
 * and a one-time session token. Another device scans the code to connect
 * directly to this device's WebChat server for AI chat access.
 *
 * Pairing payload (JSON in QR):
 * {
 *   "host": "192.168.1.x:8080",
 *   "token": "<32-byte random hex>",
 *   "name": "ZeroClaw on Pixel 8",
 *   "version": 1
 * }
 *
 * The token is stored and checked by the WebChat server for the session.
 * Paired tokens expire after PAIR_TOKEN_TTL_MS (24 hours).
 */
object DevicePairing {

    private const val PAIR_TOKEN_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
    private const val QR_SIZE_PX = 512

    data class PairingSession(
        val token: String,
        val host: String,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        fun isExpired() = System.currentTimeMillis() - createdAt > PAIR_TOKEN_TTL_MS
        fun qrPayload(deviceName: String) = JSONObject().apply {
            put("host", host)
            put("token", token)
            put("name", deviceName)
            put("version", 1)
        }.toString()
    }

    private val activeSessions = mutableMapOf<String, PairingSession>()

    /**
     * Create a new pairing session and return its QR code bitmap.
     *
     * @param localIp  The device's LAN IP address (e.g. "192.168.1.42")
     * @param port     The WebChat server port (default 8080)
     * @param deviceName  Human-readable device name shown to the connecting client
     */
    fun createPairingQr(
        context: Context,
        localIp: String,
        port: Int = 8080,
        deviceName: String = "ZeroClaw"
    ): Bitmap {
        val token = generateToken()
        val host = "$localIp:$port"
        val session = PairingSession(token, host)

        // Expire old sessions before adding new one
        pruneExpired()
        activeSessions[token] = session

        val payload = session.qrPayload(deviceName)
        ZeroClawService.log("PAIRING: generated QR for $host, token=${token.take(8)}...")

        return generateQrBitmap(payload)
    }

    /**
     * Validate a pairing token sent by a connecting client.
     * Returns the session if valid, null otherwise.
     */
    fun validateToken(token: String): PairingSession? {
        val session = activeSessions[token] ?: return null
        if (session.isExpired()) {
            activeSessions.remove(token)
            ZeroClawService.log("PAIRING: token expired ${token.take(8)}...")
            return null
        }
        ZeroClawService.log("PAIRING: token validated ${token.take(8)}...")
        return session
    }

    /**
     * Revoke a pairing token (called on disconnect or manual revoke).
     */
    fun revokeToken(token: String) {
        activeSessions.remove(token)
        ZeroClawService.log("PAIRING: token revoked ${token.take(8)}...")
    }

    /**
     * List all currently active (non-expired) pairing sessions.
     */
    fun listActiveSessions(): List<PairingSession> {
        pruneExpired()
        return activeSessions.values.toList()
    }

    /**
     * Revoke all sessions (called on service stop or security reset).
     */
    fun revokeAll() {
        activeSessions.clear()
        ZeroClawService.log("PAIRING: all sessions revoked")
    }

    /**
     * Get the device's local WiFi IP address.
     */
    fun getLocalIp(context: Context): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces.asSequence()) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses.asSequence()) {
                    if (addr.isLoopbackAddress) continue
                    if (addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            ZeroClawService.log("PAIRING: failed to get local IP — ${e.message}")
            null
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun pruneExpired() {
        val expired = activeSessions.entries.filter { it.value.isExpired() }.map { it.key }
        expired.forEach { activeSessions.remove(it) }
        if (expired.isNotEmpty()) {
            ZeroClawService.log("PAIRING: pruned ${expired.size} expired session(s)")
        }
    }

    private fun generateQrBitmap(content: String): Bitmap {
        val matrix = QrEncoder.encode(content)
            ?: throw IllegalStateException("Failed to encode QR code for pairing payload")
        val moduleCount = matrix.size
        val pixelSize = QR_SIZE_PX / moduleCount
        val actualSize = pixelSize * moduleCount
        val bitmap = Bitmap.createBitmap(actualSize, actualSize, Bitmap.Config.RGB_565)
        for (y in 0 until moduleCount) {
            for (x in 0 until moduleCount) {
                val color = if (matrix[y][x]) Color.BLACK else Color.WHITE
                for (py in 0 until pixelSize) {
                    for (px in 0 until pixelSize) {
                        bitmap.setPixel(x * pixelSize + px, y * pixelSize + py, color)
                    }
                }
            }
        }
        return bitmap
    }
}
