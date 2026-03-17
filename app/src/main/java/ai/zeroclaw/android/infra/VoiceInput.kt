package ai.zeroclaw.android.infra

import android.content.Context
import ai.zeroclaw.android.service.ZeroClawService
import ai.zeroclaw.android.tools.SpeechToTextTool
import java.io.File
import java.net.URL

/**
 * VoiceInput — Phase 139: Voice message transcription from Telegram/WhatsApp.
 *
 * When a user sends a voice message (OGG/OPUS from Telegram, OGG/AMR from WhatsApp),
 * this class:
 * 1. Downloads the audio file to the app cache
 * 2. Routes it through SpeechToTextTool (which calls OpenAI Whisper or on-device STT)
 * 3. Returns the transcription text
 *
 * The transcription is then treated as a regular text message in the AI pipeline.
 *
 * Supported formats:
 * - Telegram: OGG/OPUS voice messages
 * - WhatsApp: OGG voice messages (via Twilio media URL)
 * - Any audio format supported by SpeechToTextTool
 */
object VoiceInput {

    data class TranscriptionResult(
        val success: Boolean,
        val text: String = "",
        val language: String = "",
        val durationMs: Long = 0L,
        val error: String = ""
    )

    /**
     * Transcribe a voice file from a URL (Telegram file URL, Twilio media URL, etc.)
     *
     * @param audioUrl  Direct download URL for the audio file
     * @param fileName  Suggested filename (for format detection)
     * @param language  ISO 639-1 language hint (e.g. "en", "es") or null for auto-detect
     */
    suspend fun transcribeFromUrl(
        context: Context,
        audioUrl: String,
        fileName: String = "voice.ogg",
        language: String? = null
    ): TranscriptionResult {
        return try {
            val cacheFile = downloadToCache(context, audioUrl, fileName)
                ?: return TranscriptionResult(false, error = "Failed to download audio from $audioUrl")

            val result = transcribeFile(context, cacheFile.absolutePath, language)

            // Clean up cache file after transcription
            try { cacheFile.delete() } catch (_: Exception) {}

            result
        } catch (e: Exception) {
            ZeroClawService.log("VOICE: transcribeFromUrl failed — ${e.message}")
            TranscriptionResult(false, error = e.message ?: "Unknown error")
        }
    }

    /**
     * Transcribe a local audio file (already on device).
     */
    suspend fun transcribeFile(
        context: Context,
        filePath: String,
        language: String? = null
    ): TranscriptionResult {
        val stt = SpeechToTextTool(context)
        val args = mutableMapOf("path" to filePath)
        if (language != null) args["language"] = language

        val result = stt.execute(args)

        return if (result.success) {
            ZeroClawService.log("VOICE: transcribed ${File(filePath).name} → ${result.content.take(80)}")
            TranscriptionResult(
                success  = true,
                text     = result.content,
                language = language ?: "auto"
            )
        } else {
            ZeroClawService.log("VOICE: transcription failed — ${result.error}")
            TranscriptionResult(false, error = result.error ?: "STT failed")
        }
    }

    /**
     * Format the transcription for display in chat.
     * Prepends a microphone indicator so the AI knows this was a voice message.
     */
    fun formatForChat(transcription: TranscriptionResult): String {
        return if (transcription.success) {
            "🎤 [Voice message]: ${transcription.text}"
        } else {
            "🎤 [Voice message — transcription failed: ${transcription.error}]"
        }
    }

    /**
     * Detect if a Telegram message attachment is a voice message.
     * Telegram sends voice messages as type "voice" with MIME "audio/ogg".
     */
    fun isVoiceMessage(messageType: String?, mimeType: String?): Boolean {
        if (messageType == "voice") return true
        if (mimeType?.startsWith("audio/") == true) return true
        return false
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun downloadToCache(context: Context, url: String, fileName: String): File? {
        return try {
            val dir = File(context.cacheDir, "voice_input").also { it.mkdirs() }
            val file = File(dir, "${System.currentTimeMillis()}_$fileName")

            URL(url).openStream().use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            ZeroClawService.log("VOICE: downloaded ${file.name} (${file.length() / 1024}KB)")
            file
        } catch (e: Exception) {
            ZeroClawService.log("VOICE: download failed — ${e.message}")
            null
        }
    }
}
