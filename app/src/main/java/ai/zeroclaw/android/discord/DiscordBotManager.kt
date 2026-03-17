package ai.zeroclaw.android.discord

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
 * DiscordBotManager — Discord bot integration via Gateway WebSocket + REST API.
 *
 * Connects to Discord Gateway for real-time message events.
 * Routes messages through LlmRouter and replies via REST API.
 * Supports per-channel conversation history.
 */
class DiscordBotManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val wsClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket needs no read timeout
        .build()

    private val llmRouter = LlmRouter.getInstance(context)
    private var webSocket: WebSocket? = null
    private var running = false
    private var heartbeatJob: Job? = null
    private var lastSequence: Int? = null
    private var sessionId: String? = null
    private var botToken: String = ""

    /**
     * Start the Discord bot. Connects to Gateway via WebSocket.
     */
    suspend fun start(token: String): Unit = withContext(Dispatchers.IO) {
        botToken = token
        running = true
        connectGateway()
    }

    private fun connectGateway() {
        ZeroClawService.log("Discord: connecting to Gateway...")

        val gatewayUrl = getGatewayUrl()
        if (gatewayUrl == null) {
            ZeroClawService.log("Discord: failed to get Gateway URL")
            return
        }

        val request = Request.Builder()
            .url("$gatewayUrl/?v=10&encoding=json")
            .build()

        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ZeroClawService.log("Discord: WebSocket connected")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleGatewayMessage(ws, text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                ZeroClawService.log("Discord: WebSocket error — ${t.message}")
                if (running) {
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(5000)
                        if (running) connectGateway()
                    }
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                ZeroClawService.log("Discord: WebSocket closed ($code: $reason)")
            }
        })
    }

    fun stop() {
        running = false
        heartbeatJob?.cancel()
        webSocket?.close(1000, "Shutting down")
        webSocket = null
    }

    private fun getGatewayUrl(): String? {
        return try {
            val request = Request.Builder()
                .url("https://discord.com/api/v10/gateway/bot")
                .header("Authorization", "Bot $botToken")
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return null
            JSONObject(body).optString("url", null)
        } catch (_: Exception) {
            null
        }
    }

    private fun handleGatewayMessage(ws: WebSocket, text: String) {
        try {
            val payload = JSONObject(text)
            val op = payload.getInt("op")
            val seq = payload.opt("s")
            if (seq is Int) lastSequence = seq

            when (op) {
                10 -> { // Hello — start heartbeat and identify
                    val interval = payload.getJSONObject("d").getLong("heartbeat_interval")
                    startHeartbeat(ws, interval)
                    sendIdentify(ws)
                }
                11 -> { /* Heartbeat ACK — all good */ }
                0 -> { // Dispatch
                    val eventName = payload.optString("t", "")
                    val data = payload.optJSONObject("d")

                    when (eventName) {
                        "READY" -> {
                            sessionId = data?.optString("session_id")
                            ZeroClawService.log("Discord: bot ready — session $sessionId")
                        }
                        "MESSAGE_CREATE" -> {
                            if (data != null) handleMessage(data)
                        }
                    }
                }
                7 -> { // Reconnect
                    ZeroClawService.log("Discord: server requested reconnect")
                    ws.close(4000, "Reconnecting")
                }
                9 -> { // Invalid Session
                    ZeroClawService.log("Discord: invalid session, re-identifying")
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(2000)
                        sendIdentify(ws)
                    }
                }
            }
        } catch (e: Exception) {
            ZeroClawService.log("Discord: gateway parse error — ${e.message}")
        }
    }

    private fun startHeartbeat(ws: WebSocket, intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && running) {
                val payload = JSONObject().apply {
                    put("op", 1)
                    put("d", lastSequence ?: JSONObject.NULL)
                }
                ws.send(payload.toString())
                delay(intervalMs)
            }
        }
    }

    private fun sendIdentify(ws: WebSocket) {
        val identify = JSONObject().apply {
            put("op", 2)
            put("d", JSONObject().apply {
                put("token", botToken)
                put("intents", 33281) // GUILDS | GUILD_MESSAGES | MESSAGE_CONTENT | DIRECT_MESSAGES
                put("properties", JSONObject().apply {
                    put("os", "android")
                    put("browser", "zeroclaw")
                    put("device", "zeroclaw")
                })
            })
        }
        ws.send(identify.toString())
    }

    private fun handleMessage(data: JSONObject) {
        // Ignore messages from bots (including self)
        val author = data.optJSONObject("author") ?: return
        if (author.optBoolean("bot", false)) return

        val content = data.optString("content", "").trim()
        if (content.isEmpty()) return

        val channelId = data.getString("channel_id")
        val username = author.optString("username", "user")
        val userId = author.optString("id", "unknown")

        ZeroClawService.log("Discord @$username: $content")

        // Process in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Send typing indicator
                sendTyping(channelId)

                // Route through LlmRouter
                val reply = llmRouter.call(content, chatId = "discord_$userId")

                sendChannelMessage(channelId, reply)
                ZeroClawService.log("Discord reply sent to #$channelId")
            } catch (e: Exception) {
                ZeroClawService.log("Discord: reply error — ${e.message}")
                runCatching {
                    sendChannelMessage(channelId, "⚠️ Error: ${e.message?.take(200) ?: "Unknown error"}")
                }
            }
        }
    }

    private fun sendTyping(channelId: String) {
        try {
            val request = Request.Builder()
                .url("https://discord.com/api/v10/channels/$channelId/typing")
                .header("Authorization", "Bot $botToken")
                .post("".toRequestBody(null))
                .build()
            httpClient.newCall(request).execute().close()
        } catch (_: Exception) {}
    }

    /** Public API for proactive messaging from MessageTool/agents/crons. */
    fun sendProactiveMessage(channelId: String, text: String) = sendChannelMessage(channelId, text)

    private fun sendChannelMessage(channelId: String, text: String) {
        // Discord has a 2000 char limit per message
        val chunks = text.chunked(1990)
        for (chunk in chunks) {
            try {
                val json = JSONObject().put("content", chunk)
                val request = Request.Builder()
                    .url("https://discord.com/api/v10/channels/$channelId/messages")
                    .header("Authorization", "Bot $botToken")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    ZeroClawService.log("Discord: send failed (${response.code})")
                }
                response.close()
            } catch (e: Exception) {
                ZeroClawService.log("Discord: send error — ${e.message}")
            }
        }
    }
}
