package ai.zeroclaw.android.signal

import android.content.Context
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * SignalBridgeManager — Signal messaging integration via signal-cli REST API.
 *
 * Requires signal-cli running in REST mode (JSON-RPC) on the local network
 * or via the tunnel. signal-cli must be registered with a phone number.
 *
 * Polls for new messages and sends replies via signal-cli's REST API.
 * API docs: https://github.com/AsamK/signal-cli/wiki/JSON-RPC-service
 */
class SignalBridgeManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private val llmRouter = LlmRouter.getInstance(context)
    private var running = false
    private var baseUrl = ""

    /**
     * Start polling signal-cli REST API for new messages.
     * @param signalApiUrl Base URL of signal-cli REST API (e.g. http://127.0.0.1:8899)
     */
    suspend fun start(signalApiUrl: String) = withContext(Dispatchers.IO) {
        baseUrl = signalApiUrl.trimEnd('/')
        running = true
        ZeroClawService.log("Signal: connecting to signal-cli at $baseUrl")

        // Verify connection
        val ok = checkHealth()
        if (!ok) {
            ZeroClawService.log("Signal: cannot reach signal-cli at $baseUrl")
            return@withContext
        }

        ZeroClawService.log("Signal: connected, polling for messages")

        while (running) {
            try {
                val messages = receiveMessages()
                for (msg in messages) {
                    handleMessage(msg)
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                ZeroClawService.log("Signal poll error: ${e.message}")
            }
            delay(3000) // Poll every 3 seconds
        }
    }

    fun stop() {
        running = false
    }

    private fun checkHealth(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/v1/about")
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful.also { response.close() }
        } catch (_: Exception) {
            false
        }
    }

    private fun receiveMessages(): List<SignalMessage> {
        val messages = mutableListOf<SignalMessage>()
        try {
            val request = Request.Builder()
                .url("$baseUrl/v1/receive")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return messages

            val jsonArray = JSONArray(body)
            for (i in 0 until jsonArray.length()) {
                val envelope = jsonArray.getJSONObject(i).optJSONObject("envelope") ?: continue
                val dataMessage = envelope.optJSONObject("dataMessage") ?: continue
                val text = dataMessage.optString("message", "").trim()
                val sender = envelope.optString("source", "").trim()

                if (text.isNotBlank() && sender.isNotBlank()) {
                    messages.add(SignalMessage(sender = sender, text = text))
                }
            }
        } catch (_: Exception) {}
        return messages
    }

    private suspend fun handleMessage(msg: SignalMessage) {
        ZeroClawService.log("Signal ${msg.sender}: ${msg.text}")

        try {
            val reply = llmRouter.call(msg.text, chatId = "signal_${msg.sender}")
            sendMessage(msg.sender, reply)
            ZeroClawService.log("Signal reply sent to ${msg.sender}")
        } catch (e: Exception) {
            ZeroClawService.log("Signal: reply error — ${e.message}")
            runCatching {
                sendMessage(msg.sender, "⚠️ Error: ${e.message?.take(200) ?: "Unknown error"}")
            }
        }
    }

    private fun sendMessage(recipient: String, text: String) {
        try {
            val json = JSONObject().apply {
                put("message", text)
                put("number", recipient) // signal-cli expects the sender's number
                put("recipients", JSONArray().apply { put(recipient) })
            }

            val request = Request.Builder()
                .url("$baseUrl/v2/send")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                ZeroClawService.log("Signal: send failed (${response.code})")
            }
            response.close()
        } catch (e: Exception) {
            ZeroClawService.log("Signal: send error — ${e.message}")
        }
    }

    private data class SignalMessage(val sender: String, val text: String)
}
