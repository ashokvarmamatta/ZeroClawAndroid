package ai.zeroclaw.android.teams

import android.content.Context
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * TeamsBotManager — Microsoft Teams bot via Bot Framework webhook.
 *
 * Runs a local HTTP server that receives activity POSTs from the
 * Bot Framework Service (via tunnel). Responds using Bot Framework REST API.
 * Requires: Bot ID and Bot Secret (from Azure Bot registration).
 * Config format: "botId|botSecret"
 */
class TeamsBotManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val llmRouter = LlmRouter.getInstance(context)
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var botId = ""
    private var botSecret = ""
    private var accessToken = ""
    private var tokenExpiry = 0L
    private var lastServiceUrl = ""
    private var lastConversationId = ""

    /**
     * Start Teams bot webhook server. Config: "botId|botSecret"
     */
    suspend fun start(config: String, port: Int = 3978) = withContext(Dispatchers.IO) {
        val parts = config.split("|")
        if (parts.size < 2) {
            ZeroClawService.log("Teams: invalid config. Use 'botId|botSecret'")
            return@withContext
        }
        botId = parts[0].trim()
        botSecret = parts[1].trim()
        running = true

        // Get initial access token
        refreshToken()

        ZeroClawService.log("Teams: webhook listening on port $port...")
        serverSocket = ServerSocket(port)

        while (running) {
            try {
                val socket = serverSocket!!.accept()
                launch { handleRequest(socket) }
            } catch (e: Exception) {
                if (running) ZeroClawService.log("Teams: server error — ${e.message}")
            }
        }
    }

    private suspend fun handleRequest(socket: java.net.Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()

            // Read HTTP request
            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            var line: String?
            var contentLength = 0
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
                val colonIdx = line!!.indexOf(':')
                if (colonIdx > 0) {
                    val key = line!!.substring(0, colonIdx).trim().lowercase()
                    val value = line!!.substring(colonIdx + 1).trim()
                    headers[key] = value
                    if (key == "content-length") contentLength = value.toIntOrNull() ?: 0
                }
            }

            // Read body
            val bodyChars = CharArray(contentLength)
            if (contentLength > 0) reader.read(bodyChars, 0, contentLength)
            val body = String(bodyChars)

            // Send 200 OK immediately
            val response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
            out.write(response.toByteArray())
            out.flush()
            socket.close()

            // Process activity
            if (requestLine.startsWith("POST") && body.isNotBlank()) {
                processActivity(JSONObject(body))
            }
        } catch (e: Exception) {
            ZeroClawService.log("Teams: request error — ${e.message}")
            runCatching { socket.close() }
        }
    }

    private suspend fun processActivity(activity: JSONObject) {
        val type = activity.optString("type", "")
        if (type != "message") return

        val text = activity.optString("text", "").trim()
        if (text.isBlank()) return

        val from = activity.optJSONObject("from")
        val userId = from?.optString("id", "user") ?: "user"
        val userName = from?.optString("name", "User") ?: "User"
        val conversationId = activity.optJSONObject("conversation")?.optString("id", "") ?: ""
        val serviceUrl = activity.optString("serviceUrl", "")
        val activityId = activity.optString("id", "")

        // Persist last known conversation for proactive messaging
        if (serviceUrl.isNotBlank()) lastServiceUrl = serviceUrl
        if (conversationId.isNotBlank()) lastConversationId = conversationId

        ZeroClawService.log("Teams @$userName: $text")

        try {
            val reply = llmRouter.call(text, chatId = "teams_$userId")
            sendReply(serviceUrl, conversationId, activityId, reply)
            ZeroClawService.log("Teams: reply sent to $userName")
        } catch (e: Exception) {
            ZeroClawService.log("Teams: reply error — ${e.message}")
            runCatching { sendReply(serviceUrl, conversationId, activityId, "⚠️ Error: ${e.message?.take(200)}") }
        }
    }

    /**
     * Public API for proactive messaging from agents/crons.
     * chatId can be "serviceUrl|conversationId" or just conversationId (uses last known serviceUrl).
     */
    fun sendProactiveMessage(chatId: String, text: String) {
        val parts = chatId.split("|", limit = 2)
        val svcUrl = if (parts.size == 2) parts[0] else lastServiceUrl
        val convId = if (parts.size == 2) parts[1] else chatId.ifBlank { lastConversationId }
        if (svcUrl.isBlank() || convId.isBlank()) {
            ZeroClawService.log("Teams: no conversation available — someone must message the bot first")
            return
        }
        sendReply(svcUrl, convId, "", text)
    }

    private fun sendReply(serviceUrl: String, conversationId: String, replyToId: String, text: String) {
        ensureToken()
        val url = "${serviceUrl.trimEnd('/')}/v3/conversations/$conversationId/activities"
        val json = JSONObject().apply {
            put("type", "message")
            put("text", text)
            if (replyToId.isNotBlank()) put("replyToId", replyToId)
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().close()
    }

    private fun refreshToken() {
        try {
            val body = "grant_type=client_credentials&client_id=$botId&client_secret=$botSecret&scope=https%3A%2F%2Fapi.botframework.com%2F.default"
            val request = Request.Builder()
                .url("https://login.microsoftonline.com/botframework.com/oauth2/v2.0/token")
                .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "")
            accessToken = json.optString("access_token", "")
            val expiresIn = json.optLong("expires_in", 3600)
            tokenExpiry = System.currentTimeMillis() + (expiresIn - 60) * 1000
            ZeroClawService.log("Teams: token refreshed (expires in ${expiresIn}s)")
        } catch (e: Exception) {
            ZeroClawService.log("Teams: token refresh failed — ${e.message}")
        }
    }

    private fun ensureToken() {
        if (System.currentTimeMillis() > tokenExpiry) refreshToken()
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}
