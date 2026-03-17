package ai.zeroclaw.android.telegram

import android.content.Context
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TelegramBotManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private val llmRouter = LlmRouter.getInstance(context)
    private var running = false
    private var offset = 0L

    suspend fun startPolling(token: String) {
        running = true
        ZeroClawService.log("Telegram polling started")
        while (running) {
            try {
                val updates = getUpdates(token)
                for (update in updates) {
                    handleUpdate(update, token)
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                ZeroClawService.log("Telegram poll error: ${e.message}")
                delay(5000)
            }
        }
    }

    private suspend fun getUpdates(token: String): List<JSONObject> {
        val url = "https://api.telegram.org/bot$token/getUpdates" +
                  "?offset=$offset&timeout=30&allowed_updates=%5B%22message%22%5D"
        val request = Request.Builder().url(url).build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                if (!json.getBoolean("ok")) return@withContext emptyList()
                val result = json.getJSONArray("result")
                val updates = mutableListOf<JSONObject>()
                for (i in 0 until result.length()) {
                    val u = result.getJSONObject(i)
                    offset = u.getLong("update_id") + 1
                    updates.add(u)
                }
                updates
            }
        }
    }

    private suspend fun handleUpdate(update: JSONObject, token: String) {
        val message = update.optJSONObject("message") ?: return
        val chatId = message.getJSONObject("chat").getLong("id")
        val text = message.optString("text", "").trim()
        if (text.isEmpty()) return

        val username = message.optJSONObject("from")?.optString("username", "user") ?: "user"
        ZeroClawService.log("Telegram @$username: $text")

        try {
            // Send typing indicator
            sendChatAction(token, chatId)

            // Route through LlmRouter with per-chat history for context
            val reply = llmRouter.call(text, chatId = "tg_$chatId")

            sendMessage(token, chatId, reply)
            ZeroClawService.log("Telegram reply sent to $chatId")
        } catch (e: Exception) {
            ZeroClawService.log("Telegram: failed to handle message — ${e.message}")
            // Try to notify user of the error
            runCatching {
                sendMessage(token, chatId, "⚠️ Error: ${e.message?.take(200) ?: "Unknown error"}")
            }
        }
    }

    private suspend fun sendChatAction(token: String, chatId: Long) {
        val url = "https://api.telegram.org/bot$token/sendChatAction"
        val body = FormBody.Builder()
            .add("chat_id", chatId.toString())
            .add("action", "typing")
            .build()
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(url).post(body).build()).execute().close()
            }
        }
    }

    private suspend fun sendMessage(token: String, chatId: Long, text: String) {
        val url = "https://api.telegram.org/bot$token/sendMessage"
        withContext(Dispatchers.IO) {
            // Try Markdown first — if AI response has unescaped chars, Telegram rejects it
            val mdBody = FormBody.Builder()
                .add("chat_id", chatId.toString())
                .add("text", text)
                .add("parse_mode", "Markdown")
                .build()
            val mdResp = runCatching {
                client.newCall(Request.Builder().url(url).post(mdBody).build()).execute()
            }
            val resp = mdResp.getOrNull()
            if (resp != null && resp.isSuccessful) {
                resp.close()
                return@withContext
            }
            resp?.close()
            // Markdown failed — retry as plain text
            ZeroClawService.log("Telegram: Markdown send failed, retrying as plain text")
            val plainBody = FormBody.Builder()
                .add("chat_id", chatId.toString())
                .add("text", text)
                .build()
            val plainResp = runCatching {
                client.newCall(Request.Builder().url(url).post(plainBody).build()).execute()
            }
            val pr = plainResp.getOrNull()
            if (pr != null && !pr.isSuccessful) {
                ZeroClawService.log("Telegram: plain text send also failed — HTTP ${pr.code}")
            }
            pr?.close()
        }
    }

    fun stop() { running = false }

    /** Public API for proactive messaging from MessageTool/agents/crons. */
    suspend fun sendProactiveMessage(token: String, chatId: String, text: String) {
        val chatIdLong = chatId.toLongOrNull() ?: run {
            ZeroClawService.log("Telegram: invalid chatId '$chatId' for proactive message")
            return
        }
        sendMessage(token, chatIdLong, text)
    }
}
