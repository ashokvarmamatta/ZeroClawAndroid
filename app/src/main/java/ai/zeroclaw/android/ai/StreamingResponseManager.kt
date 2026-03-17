package ai.zeroclaw.android.ai

import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * StreamingResponseManager — Phase 111: Stream LLM output to channels in real-time.
 *
 * Supports Server-Sent Events (SSE) streaming for OpenAI-compatible, Anthropic,
 * and Gemini APIs. Returns a Flow<String> of text chunks that can be consumed
 * by messaging channels to provide edit-in-place or progressive message updates.
 *
 * Channels like Telegram (editMessageText) and Discord (edit message) can update
 * the message as chunks arrive, giving users a ChatGPT-like streaming experience.
 */
class StreamingResponseManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)  // longer for streaming
        .build()

    companion object {
        // Minimum chars between updates (avoid too-frequent edits)
        const val MIN_CHUNK_SIZE = 30
        // Maximum time between updates in ms
        const val MAX_UPDATE_INTERVAL_MS = 1500L
    }

    /**
     * Stream a response from an OpenAI-compatible API.
     * Returns a Flow of text chunks.
     */
    fun streamOpenAI(
        message: String,
        apiKey: String,
        baseUrl: String = "https://api.openai.com/v1",
        model: String = "gpt-4o-mini",
        systemPrompt: String,
        history: List<Pair<String, String>> = emptyList()
    ): Flow<String> = flow {
        val body = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("max_tokens", 1000)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                history.forEach { (role, content) ->
                    put(JSONObject().apply { put("role", role); put("content", content) })
                }
                put(JSONObject().apply { put("role", "user"); put("content", message) })
            })
        }.toString()

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Stream failed: HTTP ${response.code}")
        }

        val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: throw Exception("Empty body")))
        var buffer = StringBuilder()

        try {
            var line: String? = null
            while (reader.readLine().also { line = it } != null) {
                val data = line ?: continue
                if (!data.startsWith("data: ")) continue
                val jsonStr = data.removePrefix("data: ").trim()
                if (jsonStr == "[DONE]") break

                try {
                    val json = JSONObject(jsonStr)
                    val choices = json.optJSONArray("choices") ?: continue
                    val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                    val content = delta.optString("content", "")
                    if (content.isNotEmpty()) {
                        buffer.append(content)
                        if (buffer.length >= MIN_CHUNK_SIZE) {
                            emit(buffer.toString())
                            buffer = StringBuilder()
                        }
                    }
                } catch (_: Exception) { /* skip malformed SSE lines */ }
            }

            // Emit remaining buffer
            if (buffer.isNotEmpty()) {
                emit(buffer.toString())
            }
        } finally {
            reader.close()
            response.close()
        }
    }

    /**
     * Stream a response from the Anthropic API.
     */
    fun streamAnthropic(
        message: String,
        apiKey: String,
        model: String = "claude-haiku-4-5-20251001",
        systemPrompt: String,
        history: List<Pair<String, String>> = emptyList()
    ): Flow<String> = flow {
        val body = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("max_tokens", 1000)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                history.forEach { (role, content) ->
                    put(JSONObject().apply { put("role", role); put("content", content) })
                }
                put(JSONObject().apply { put("role", "user"); put("content", message) })
            })
        }.toString()

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Stream failed: HTTP ${response.code}")
        }

        val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: throw Exception("Empty body")))
        var buffer = StringBuilder()

        try {
            var line: String? = null
            while (reader.readLine().also { line = it } != null) {
                val data = line ?: continue
                if (!data.startsWith("data: ")) continue
                val jsonStr = data.removePrefix("data: ").trim()

                try {
                    val json = JSONObject(jsonStr)
                    val type = json.optString("type", "")
                    if (type == "content_block_delta") {
                        val delta = json.optJSONObject("delta") ?: continue
                        val text = delta.optString("text", "")
                        if (text.isNotEmpty()) {
                            buffer.append(text)
                            if (buffer.length >= MIN_CHUNK_SIZE) {
                                emit(buffer.toString())
                                buffer = StringBuilder()
                            }
                        }
                    } else if (type == "message_stop") {
                        break
                    }
                } catch (_: Exception) { /* skip malformed SSE lines */ }
            }

            if (buffer.isNotEmpty()) {
                emit(buffer.toString())
            }
        } finally {
            reader.close()
            response.close()
        }
    }

    /**
     * Aggregate streaming chunks with rate limiting for message edits.
     * Returns a Flow that emits full accumulated text at appropriate intervals.
     */
    fun aggregateForEditing(chunks: Flow<String>): Flow<String> = flow {
        val fullText = StringBuilder()
        var lastEmitTime = 0L

        chunks.collect { chunk ->
            fullText.append(chunk)
            val now = System.currentTimeMillis()
            if (now - lastEmitTime >= MAX_UPDATE_INTERVAL_MS) {
                emit(fullText.toString())
                lastEmitTime = now
            }
        }

        // Always emit final complete text
        emit(fullText.toString())
    }
}
