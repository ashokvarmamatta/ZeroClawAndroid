package ai.zeroclaw.android.matrix

import android.content.Context
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * MatrixBotManager — Matrix/Element protocol integration via Client-Server API.
 *
 * Uses long-polling /sync endpoint for receiving events.
 * Requires: Homeserver URL and access token.
 * Token format: "https://matrix.org|syt_TOKEN"
 */
class MatrixBotManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // Long poll timeout
        .build()

    private val llmRouter = LlmRouter.getInstance(context)
    private var running = false
    private var homeserver = ""
    private var accessToken = ""
    private var botUserId = ""
    private var nextBatch = ""

    /**
     * Start the Matrix bot. Config format: "https://homeserver.org|access_token"
     */
    suspend fun start(config: String) = withContext(Dispatchers.IO) {
        val parts = config.split("|")
        if (parts.size < 2) {
            ZeroClawService.log("Matrix: invalid config. Use 'https://homeserver|access_token'")
            return@withContext
        }
        homeserver = parts[0].trim().trimEnd('/')
        accessToken = parts[1].trim()
        running = true

        // Get bot user ID
        botUserId = whoAmI() ?: ""
        ZeroClawService.log("Matrix: bot user = $botUserId")

        // Initial sync to get since token (no message processing)
        initialSync()

        // Start long-polling
        ZeroClawService.log("Matrix: starting sync loop...")
        while (running) {
            try {
                syncAndProcess()
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                ZeroClawService.log("Matrix: sync error — ${e.message}")
                delay(5000)
            }
        }
    }

    private fun whoAmI(): String? {
        return try {
            val request = Request.Builder()
                .url("$homeserver/_matrix/client/v3/account/whoami")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "")
            json.optString("user_id", "")
        } catch (_: Exception) { null }
    }

    private fun initialSync() {
        try {
            val request = Request.Builder()
                .url("$homeserver/_matrix/client/v3/sync?filter={\"room\":{\"timeline\":{\"limit\":0}}}&timeout=0")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "")
            nextBatch = json.optString("next_batch", "")
            ZeroClawService.log("Matrix: initial sync done, batch = ${nextBatch.take(20)}...")
        } catch (e: Exception) {
            ZeroClawService.log("Matrix: initial sync failed — ${e.message}")
        }
    }

    private suspend fun syncAndProcess() {
        val sinceParam = if (nextBatch.isNotBlank()) "&since=$nextBatch" else ""
        val request = Request.Builder()
            .url("$homeserver/_matrix/client/v3/sync?timeout=30000$sinceParam")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
        val body = response.body?.string() ?: return
        val json = JSONObject(body)

        nextBatch = json.optString("next_batch", nextBatch)

        // Process room events
        val rooms = json.optJSONObject("rooms")?.optJSONObject("join") ?: return
        for (roomId in rooms.keys()) {
            val room = rooms.getJSONObject(roomId)
            val timeline = room.optJSONObject("timeline")?.optJSONArray("events") ?: continue

            for (i in 0 until timeline.length()) {
                val event = timeline.getJSONObject(i)
                val eventType = event.optString("type", "")
                val sender = event.optString("sender", "")

                // Only process text messages not from ourselves
                if (eventType == "m.room.message" && sender != botUserId) {
                    val content = event.optJSONObject("content") ?: continue
                    val msgType = content.optString("msgtype", "")
                    val msgBody = content.optString("body", "").trim()

                    if (msgType == "m.text" && msgBody.isNotBlank()) {
                        ZeroClawService.log("Matrix @$sender in $roomId: $msgBody")

                        try {
                            val reply = llmRouter.call(msgBody, chatId = "matrix_$sender")
                            sendMessage(roomId, reply)
                            ZeroClawService.log("Matrix: reply sent to $roomId")
                        } catch (e: Exception) {
                            ZeroClawService.log("Matrix: reply error — ${e.message}")
                            runCatching { sendMessage(roomId, "⚠️ Error: ${e.message?.take(200)}") }
                        }
                    }
                }
            }
        }
    }

    private fun sendMessage(roomId: String, text: String) {
        val txnId = "zclaw_${System.currentTimeMillis()}"
        val json = JSONObject().apply {
            put("msgtype", "m.text")
            put("body", text)
        }
        val encodedRoom = java.net.URLEncoder.encode(roomId, "UTF-8")
        val request = Request.Builder()
            .url("$homeserver/_matrix/client/v3/rooms/$encodedRoom/send/m.room.message/$txnId")
            .header("Authorization", "Bearer $accessToken")
            .put(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().close()
    }

    fun stop() {
        running = false
    }
}
