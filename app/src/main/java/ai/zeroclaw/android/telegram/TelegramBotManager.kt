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

    private val prefs = context.getSharedPreferences("zeroclaw_telegram", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_CHAT_ID = "last_known_chat_id"

        /** Last chat ID seen from polling — used as default target for proactive messages. */
        @Volatile var lastKnownChatId: Long? = null
            private set

        /** Load persisted chat ID (call once from service init). */
        fun restoreLastChatId(context: Context) {
            val prefs = context.getSharedPreferences("zeroclaw_telegram", Context.MODE_PRIVATE)
            val saved = prefs.getLong(KEY_LAST_CHAT_ID, 0L)
            if (saved != 0L) lastKnownChatId = saved
        }
    }

    /** Persist + update the last known chat ID. */
    private fun trackChatId(chatId: Long) {
        lastKnownChatId = chatId
        prefs.edit().putLong(KEY_LAST_CHAT_ID, chatId).apply()
    }

    suspend fun startPolling(token: String) {
        // Restore persisted chat ID on polling start
        val saved = prefs.getLong(KEY_LAST_CHAT_ID, 0L)
        if (saved != 0L) lastKnownChatId = saved

        // Seed: if no chat ID known yet, peek at the most recent update to find one
        if (lastKnownChatId == null) {
            seedChatIdFromRecentUpdates(token)
        }

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

    /**
     * One-time seed: fetch the most recent Telegram update (offset=-1, no timeout)
     * to discover the last chat ID. This lets agents deliver messages immediately
     * on first start without waiting for the user to send a new message.
     */
    private suspend fun seedChatIdFromRecentUpdates(token: String) {
        try {
            val url = "https://api.telegram.org/bot$token/getUpdates?offset=-1&timeout=0"
            val request = Request.Builder().url(url).build()
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext
                    val body = response.body?.string() ?: return@withContext
                    val json = JSONObject(body)
                    if (!json.getBoolean("ok")) return@withContext
                    val result = json.getJSONArray("result")
                    if (result.length() == 0) return@withContext
                    // Get the most recent update
                    val lastUpdate = result.getJSONObject(result.length() - 1)
                    offset = lastUpdate.getLong("update_id") + 1
                    val message = lastUpdate.optJSONObject("message") ?: return@withContext
                    val chatId = message.optJSONObject("chat")?.optLong("id", 0L) ?: 0L
                    if (chatId != 0L) {
                        trackChatId(chatId)
                        ZeroClawService.log("Telegram: seeded bot chat ID $chatId from recent history")
                    }
                }
            }
        } catch (e: Exception) {
            // Non-fatal — polling will pick up chat IDs eventually
            ZeroClawService.log("Telegram: seed fetch failed — ${e.message}")
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

        // Persist chat ID so agents can deliver here even after app restart
        trackChatId(chatId)

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
        // Telegram has a 4096 char limit — split long messages
        val chunks = if (text.length > 4000) {
            text.chunked(4000)
        } else {
            listOf(text)
        }
        withContext(Dispatchers.IO) {
            for (chunk in chunks) {
                // Try Markdown first — if AI response has unescaped chars, Telegram rejects it
                val mdBody = FormBody.Builder()
                    .add("chat_id", chatId.toString())
                    .add("text", chunk)
                    .add("parse_mode", "Markdown")
                    .build()
                val mdResp = runCatching {
                    client.newCall(Request.Builder().url(url).post(mdBody).build()).execute()
                }
                val resp = mdResp.getOrNull()
                if (resp != null && resp.isSuccessful) {
                    resp.close()
                    continue
                }
                resp?.close()
                // Markdown failed — strip markdown formatting and send as plain text
                ZeroClawService.log("Telegram: Markdown send failed, retrying as plain text")
                val plainText = chunk
                    .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")  // **bold** → bold
                    .replace(Regex("\\*(.+?)\\*"), "$1")        // *italic* → italic
                    .replace(Regex("`(.+?)`"), "$1")            // `code` → code
                val plainBody = FormBody.Builder()
                    .add("chat_id", chatId.toString())
                    .add("text", plainText)
                    .build()
                val plainResp = runCatching {
                    client.newCall(Request.Builder().url(url).post(plainBody).build()).execute()
                }
                val pr = plainResp.getOrNull()
                if (pr != null && !pr.isSuccessful) {
                    val errBody = runCatching { pr.body?.string() }.getOrNull() ?: ""
                    ZeroClawService.log("Telegram: plain text send also failed — HTTP ${pr.code}: ${errBody.take(200)}")
                }
                pr?.close()
            }
        }
    }

    fun stop() { running = false }

    /**
     * Public API for proactive messaging from MessageTool/agents/crons.
     * If chatId is blank or invalid, automatically sends to the bot's last known chat.
     */
    suspend fun sendProactiveMessage(token: String, chatId: String, text: String) {
        // Resolve target: explicit chatId → last known bot chat
        val chatIdLong = chatId.toLongOrNull() ?: lastKnownChatId ?: run {
            ZeroClawService.log("Telegram: no chat available — send a message to the bot first")
            return
        }
        if (chatId.toLongOrNull() == null && chatId.isNotBlank()) {
            ZeroClawService.log("Telegram: chatId '$chatId' not valid — using bot chat $chatIdLong")
        }
        sendMessage(token, chatIdLong, text)
    }
}
