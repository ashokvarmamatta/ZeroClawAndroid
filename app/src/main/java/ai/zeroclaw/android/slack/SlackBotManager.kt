package ai.zeroclaw.android.slack

import android.content.Context
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * SlackBotManager — Slack workspace bot via Socket Mode (WebSocket) + Web API.
 *
 * Uses Slack Socket Mode for receiving events (no public URL needed)
 * and Web API for sending messages.
 * Requires: Bot Token (xoxb-...) and App-Level Token (xapp-...).
 */
class SlackBotManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No timeout for WebSocket
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val llmRouter = LlmRouter.getInstance(context)
    private var running = false
    private var webSocket: WebSocket? = null
    private var botToken = ""
    private var appToken = ""
    private var botUserId = ""

    /**
     * Start the Slack bot. Token format: "xoxb-BOT_TOKEN|xapp-APP_TOKEN"
     * The pipe separator splits bot token from app-level token.
     */
    suspend fun start(tokenPair: String) = withContext(Dispatchers.IO) {
        val parts = tokenPair.split("|")
        if (parts.size < 2) {
            ZeroClawService.log("Slack: invalid token format. Use 'xoxb-...|xapp-...'")
            return@withContext
        }
        botToken = parts[0].trim()
        appToken = parts[1].trim()
        running = true

        // Get bot user ID
        botUserId = getBotUserId() ?: ""
        ZeroClawService.log("Slack: bot user ID = $botUserId")

        // Connect via Socket Mode
        connectSocketMode()
    }

    private suspend fun connectSocketMode() {
        if (!running) return

        // Get WebSocket URL from Socket Mode
        val request = Request.Builder()
            .url("https://slack.com/api/apps.connections.open")
            .header("Authorization", "Bearer $appToken")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post("".toRequestBody(null))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return
        val json = JSONObject(body)

        if (!json.optBoolean("ok", false)) {
            ZeroClawService.log("Slack: Socket Mode connection failed — ${json.optString("error", "unknown")}")
            delay(10_000)
            if (running) connectSocketMode()
            return
        }

        val wsUrl = json.optString("url", "")
        if (wsUrl.isBlank()) {
            ZeroClawService.log("Slack: no WebSocket URL returned")
            return
        }

        ZeroClawService.log("Slack: connecting to Socket Mode...")

        val wsRequest = Request.Builder().url(wsUrl).build()
        webSocket = wsClient.newWebSocket(wsRequest, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, resp: Response) {
                ZeroClawService.log("Slack: Socket Mode connected")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleSocketMessage(ws, text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) {
                ZeroClawService.log("Slack: WebSocket error — ${t.message}")
                if (running) {
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(5000)
                        if (running) connectSocketMode()
                    }
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
                if (running) {
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(2000)
                        if (running) connectSocketMode()
                    }
                }
            }
        })
    }

    private fun handleSocketMessage(ws: WebSocket, text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")
            val envelopeId = json.optString("envelope_id", "")

            // Acknowledge the envelope
            if (envelopeId.isNotBlank()) {
                ws.send(JSONObject().put("envelope_id", envelopeId).toString())
            }

            when (type) {
                "events_api" -> {
                    val payload = json.optJSONObject("payload") ?: return
                    val event = payload.optJSONObject("event") ?: return
                    val eventType = event.optString("type", "")

                    if (eventType == "message" && !event.has("subtype")) {
                        val userId = event.optString("user", "")
                        val msgText = event.optString("text", "").trim()
                        val channel = event.optString("channel", "")

                        // Persist last-known channel ID for proactive messaging
                        context.getSharedPreferences("zeroclaw_prefs", Context.MODE_PRIVATE)
                            .edit().putString("slack_last_channel_id", channel).apply()

                        // Ignore bot's own messages
                        if (userId == botUserId || msgText.isBlank()) return

                        ZeroClawService.log("Slack @$userId: $msgText")

                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val reply = llmRouter.call(msgText, chatId = "slack_$userId")
                                sendMessage(channel, reply)
                                ZeroClawService.log("Slack: reply sent to #$channel")
                            } catch (e: Exception) {
                                ZeroClawService.log("Slack: reply error — ${e.message}")
                                runCatching { sendMessage(channel, "⚠️ Error: ${e.message?.take(200)}") }
                            }
                        }
                    }
                }
                "hello" -> ZeroClawService.log("Slack: Socket Mode handshake OK")
                "disconnect" -> {
                    ZeroClawService.log("Slack: server requested disconnect, reconnecting...")
                    if (running) {
                        CoroutineScope(Dispatchers.IO).launch {
                            delay(1000)
                            connectSocketMode()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ZeroClawService.log("Slack: parse error — ${e.message}")
        }
    }

    /** Public API for proactive messaging from MessageTool/agents/crons. */
    fun sendProactiveMessage(channel: String, text: String) = sendMessage(channel, text)

    private fun sendMessage(channel: String, text: String) {
        // Slack message limit is ~40000 chars, but chunk at 4000 for readability
        val chunks = text.chunked(4000)
        for (chunk in chunks) {
            val json = JSONObject().apply {
                put("channel", channel)
                put("text", chunk)
            }
            val request = Request.Builder()
                .url("https://slack.com/api/chat.postMessage")
                .header("Authorization", "Bearer $botToken")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().close()
        }
    }

    private fun getBotUserId(): String? {
        return try {
            val request = Request.Builder()
                .url("https://slack.com/api/auth.test")
                .header("Authorization", "Bearer $botToken")
                .post("".toRequestBody(null))
                .build()
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "")
            json.optString("user_id", "")
        } catch (_: Exception) { null }
    }

    fun stop() {
        running = false
        webSocket?.close(1000, "Shutting down")
        webSocket = null
    }
}
