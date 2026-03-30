package ai.zeroclaw.android.line

import android.content.Context
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * LineBotManager — LINE Messaging API bot via webhook.
 *
 * Runs a local HTTP server that receives webhook events from LINE Platform.
 * Requires: Channel Access Token (long-lived).
 * Responds using LINE Messaging API reply endpoint.
 */
class LineBotManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val llmRouter = LlmRouter.getInstance(context)
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var channelToken = ""

    /**
     * Start LINE bot webhook server. Token: Channel Access Token.
     */
    suspend fun start(token: String, port: Int = 8443) = withContext(Dispatchers.IO) {
        channelToken = token.trim()
        running = true

        ZeroClawService.log("LINE: webhook listening on port $port...")
        serverSocket = ServerSocket(port)

        while (running) {
            try {
                val socket = serverSocket!!.accept()
                launch { handleRequest(socket) }
            } catch (e: Exception) {
                if (running) ZeroClawService.log("LINE: server error — ${e.message}")
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

            // Send 200 OK
            val response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
            out.write(response.toByteArray())
            out.flush()
            socket.close()

            // Process webhook events
            if (requestLine.startsWith("POST") && body.isNotBlank()) {
                processWebhook(JSONObject(body))
            }
        } catch (e: Exception) {
            ZeroClawService.log("LINE: request error — ${e.message}")
            runCatching { socket.close() }
        }
    }

    private suspend fun processWebhook(payload: JSONObject) {
        val events = payload.optJSONArray("events") ?: return

        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            val type = event.optString("type", "")

            if (type == "message") {
                val message = event.optJSONObject("message") ?: continue
                val msgType = message.optString("type", "")
                val text = message.optString("text", "").trim()
                val replyToken = event.optString("replyToken", "")
                val source = event.optJSONObject("source")
                val sourceId = source?.optString("groupId") 
                    ?: source?.optString("roomId") 
                    ?: source?.optString("userId") 
                    ?: "user"

                // Persist last-known source ID for proactive messaging
                context.getSharedPreferences("zeroclaw_prefs", Context.MODE_PRIVATE)
                    .edit().putString("line_last_source_id", sourceId).apply()

                if (msgType == "text" && text.isNotBlank() && replyToken.isNotBlank()) {
                    ZeroClawService.log("LINE @$sourceId: $text")

                    try {
                        val reply = llmRouter.call(text, chatId = "line_$sourceId")
                        replyMessage(replyToken, reply)
                        ZeroClawService.log("LINE: reply sent to $sourceId")
                    } catch (e: Exception) {
                        ZeroClawService.log("LINE: reply error — ${e.message}")
                        runCatching { replyMessage(replyToken, "⚠️ Error: ${e.message?.take(200)}") }
                    }
                }
            }
        }
    }

    private fun replyMessage(replyToken: String, text: String) {
        // LINE has a 5000 char limit per message bubble, max 5 bubbles per reply
        val chunks = text.chunked(5000).take(5)
        val messages = JSONArray()
        for (chunk in chunks) {
            messages.put(JSONObject().apply {
                put("type", "text")
                put("text", chunk)
            })
        }

        val json = JSONObject().apply {
            put("replyToken", replyToken)
            put("messages", messages)
        }

        val request = Request.Builder()
            .url("https://api.line.me/v2/bot/message/reply")
            .header("Authorization", "Bearer $channelToken")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().close()
    }

    /** Public API for proactive messaging from agents. */
    fun sendProactiveMessage(to: String, text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val chunks = text.chunked(5000).take(5)
                val messages = JSONArray()
                for (chunk in chunks) {
                    messages.put(JSONObject().apply {
                        put("type", "text")
                        put("text", chunk)
                    })
                }
                val json = JSONObject().apply {
                    put("to", to)
                    put("messages", messages)
                }
                val request = Request.Builder()
                    .url("https://api.line.me/v2/bot/message/push")
                    .header("Authorization", "Bearer $channelToken")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().close()
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}
