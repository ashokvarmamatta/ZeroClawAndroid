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

        // Send typing indicator
        sendChatAction(token, chatId)

        // Route through LlmRouter — waterfall failover handled inside
        val reply = llmRouter.call(text)

        sendMessage(token, chatId, reply)
        ZeroClawService.log("Telegram reply sent to $chatId")
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
        val body = FormBody.Builder()
            .add("chat_id", chatId.toString())
            .add("text", text)
            .add("parse_mode", "Markdown")
            .build()
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(url).post(body).build()).execute().close()
            }
        }
    }

    fun stop() { running = false }
}
