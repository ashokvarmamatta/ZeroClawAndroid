package ai.zeroclaw.android.tools

import android.content.Context
import ai.zeroclaw.android.data.LlmKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * SpeechToTextTool — transcribe voice/audio files to text.
 *
 * Primary: OpenAI Whisper API (needs OpenAI key).
 * Fallback: Groq Whisper (free tier, fast, if OpenRouter/Groq key available).
 *
 * Accepts local file paths, content:// URIs, or audio URLs.
 * Inspired by OpenClaw's speech-to-text skill.
 */
class SpeechToTextTool(private val context: Context) : Tool {

    override val name = "speech_to_text"

    override val description = "Transcribe audio/voice files to text using Whisper. " +
            "Accepts file paths, content URIs, or audio URLs. " +
            "Supports mp3, mp4, wav, m4a, ogg, webm, flac formats. " +
            "Requires an OpenAI or Groq API key."

    override val parameters = listOf(
        ToolParam("source", "string", "Audio file path, content:// URI, or URL to transcribe"),
        ToolParam("language", "string", "Language code (e.g. 'en', 'es', 'hi'). Default: auto-detect.", required = false)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // transcription can be slow
        .build()

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val source = args["source"]?.trim()
            ?: return ToolResult(false, "", "Missing 'source' parameter (audio file path or URL)")

        if (source.isBlank()) return ToolResult(false, "", "Empty source")

        val language = args["language"]?.trim()

        return withContext(Dispatchers.IO) {
            try {
                // Resolve source to a local file
                val audioFile = resolveAudioFile(source)
                    ?: return@withContext ToolResult(false, "", "Could not load audio from: $source")

                // Find a Whisper-capable API key
                val keyManager = LlmKeyManager.getInstance(context)
                val keys = keyManager.loadKeys().filter { it.enabled }

                // Try OpenAI first, then Groq via OpenRouter
                val openaiKey = keys.firstOrNull { it.safeProvider == "openai" }
                val groqKey = keys.firstOrNull {
                    it.safeProvider == "openrouter" || it.safeBaseUrl.contains("groq", ignoreCase = true)
                }

                val result = when {
                    openaiKey != null -> transcribeWhisper(
                        audioFile, language,
                        openaiKey.safeApiKey,
                        openaiKey.safeBaseUrl.ifBlank { "https://api.openai.com/v1" }.trimEnd('/')
                    )
                    groqKey != null && groqKey.safeBaseUrl.contains("groq", ignoreCase = true) -> transcribeWhisper(
                        audioFile, language,
                        groqKey.safeApiKey,
                        groqKey.safeBaseUrl.trimEnd('/')
                    )
                    else -> null
                }

                // Clean up temp file
                if (audioFile.absolutePath.startsWith(context.cacheDir.absolutePath)) {
                    audioFile.delete()
                }

                result ?: ToolResult(false, "", "No Whisper-capable API key found. Add an OpenAI or Groq key.")
            } catch (e: Exception) {
                ToolResult(false, "", "Transcription failed: ${e.message}")
            }
        }
    }

    private fun transcribeWhisper(audioFile: File, language: String?, apiKey: String, baseUrl: String): ToolResult {
        val mediaType = when (audioFile.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "webm" -> "audio/webm"
            "flac" -> "audio/flac"
            "mp4" -> "audio/mp4"
            else -> "audio/mpeg"
        }

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("file", audioFile.name,
                audioFile.asRequestBody(mediaType.toMediaType()))

        if (!language.isNullOrBlank()) {
            bodyBuilder.addFormDataPart("language", language)
        }

        val request = Request.Builder()
            .url("$baseUrl/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(bodyBuilder.build())
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return ToolResult(false, "", "Empty response from Whisper API")

        if (!response.isSuccessful) {
            val error = try {
                JSONObject(body).optJSONObject("error")?.optString("message") ?: "HTTP ${response.code}"
            } catch (_: Exception) { "HTTP ${response.code}" }
            return ToolResult(false, "", "Whisper API error: $error")
        }

        val json = JSONObject(body)
        val text = json.optString("text", "")

        if (text.isBlank()) {
            return ToolResult(true, "Audio transcribed but no speech detected.")
        }

        val sb = StringBuilder("Transcription Result\n")
        sb.appendLine("═══════════════════════")
        sb.appendLine()
        sb.appendLine("Source: ${audioFile.name}")
        sb.appendLine("Size: ${audioFile.length() / 1024} KB")
        sb.appendLine()
        sb.appendLine("Text:")
        sb.appendLine(text)

        return ToolResult(true, sb.toString().take(4000))
    }

    /** Resolve audio source to a local file */
    private fun resolveAudioFile(source: String): File? {
        // Local file path
        if (source.startsWith("/") || source.startsWith("file://")) {
            val path = source.removePrefix("file://")
            val file = File(path)
            return if (file.exists()) file else null
        }

        // Content URI
        if (source.startsWith("content://")) {
            return try {
                val uri = android.net.Uri.parse(source)
                val tempFile = File(context.cacheDir, "stt_audio_${System.currentTimeMillis()}.tmp")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }
                if (tempFile.exists() && tempFile.length() > 0) tempFile else null
            } catch (_: Exception) { null }
        }

        // URL — download to cache
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return try {
                val ext = source.substringAfterLast(".").take(5).ifBlank { "mp3" }
                val tempFile = File(context.cacheDir, "stt_audio_${System.currentTimeMillis()}.$ext")
                URL(source).openStream().use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }
                if (tempFile.exists() && tempFile.length() > 0) tempFile else null
            } catch (_: Exception) { null }
        }

        // Bare file path
        val file = File(source)
        return if (file.exists()) file else null
    }
}
