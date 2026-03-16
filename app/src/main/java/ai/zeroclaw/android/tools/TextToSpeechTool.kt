package ai.zeroclaw.android.tools

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * TextToSpeechTool — read text aloud using Android's built-in TTS engine.
 *
 * 100% free, works offline, no API key needed.
 * Can also save speech to an audio file.
 *
 * Inspired by OpenClaw's TTS skill.
 */
class TextToSpeechTool(private val context: Context) : Tool {

    override val name = "text_to_speech"

    override val description = "Read text aloud or save as audio file using Android's built-in TTS engine. " +
            "Free, works offline. Actions: 'speak' (read aloud), 'save' (save to audio file), 'voices' (list available voices)."

    override val parameters = listOf(
        ToolParam("text", "string", "The text to speak or save as audio"),
        ToolParam("action", "string", "One of: speak (default), save, voices.", required = false),
        ToolParam("language", "string", "Language code (e.g. 'en', 'es', 'hi', 'ja'). Default: en.", required = false),
        ToolParam("speed", "string", "Speech speed: 'slow' (0.7), 'normal' (1.0), 'fast' (1.3). Default: normal.", required = false)
    )

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val text = args["text"]?.trim()
        val action = args["action"]?.trim()?.lowercase() ?: "speak"
        val language = args["language"]?.trim()?.lowercase() ?: "en"
        val speed = args["speed"]?.trim()?.lowercase() ?: "normal"

        return when (action) {
            "voices" -> listVoices()
            "save" -> {
                if (text.isNullOrBlank()) return ToolResult(false, "", "Missing 'text' parameter")
                saveToFile(text, language, speed)
            }
            else -> {
                if (text.isNullOrBlank()) return ToolResult(false, "", "Missing 'text' parameter")
                speakAloud(text, language, speed)
            }
        }
    }

    /** Speak text aloud via Android TTS */
    private suspend fun speakAloud(text: String, language: String, speed: String): ToolResult {
        return withContext(Dispatchers.Main) {
            val result = withTimeoutOrNull(15000L) {
                suspendCancellableCoroutine { cont ->
                    var tts: TextToSpeech? = null
                    tts = TextToSpeech(context) { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            val locale = resolveLocale(language)
                            tts?.language = locale
                            tts?.setSpeechRate(resolveSpeed(speed))

                            val utteranceId = "zeroclaw_tts_${System.currentTimeMillis()}"
                            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(id: String?) {}
                                override fun onDone(id: String?) {
                                    tts?.shutdown()
                                    if (cont.isActive) cont.resume(true)
                                }
                                @Deprecated("Deprecated in Java")
                                override fun onError(id: String?) {
                                    tts?.shutdown()
                                    if (cont.isActive) cont.resume(false)
                                }
                            })

                            val params = Bundle().apply {
                                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                            }
                            tts?.speak(text.take(MAX_SPEAK_LENGTH), TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                        } else {
                            if (cont.isActive) cont.resume(false)
                        }
                    }

                    cont.invokeOnCancellation { tts?.shutdown() }
                }
            }

            if (result == true) {
                val wordCount = text.split(Regex("\\s+")).size
                ToolResult(true, "Spoke $wordCount words aloud (language: $language, speed: $speed).")
            } else {
                ToolResult(false, "", "TTS failed or timed out. Check if a TTS engine is installed on the device.")
            }
        }
    }

    /** Save speech to audio file */
    private suspend fun saveToFile(text: String, language: String, speed: String): ToolResult {
        return withContext(Dispatchers.Main) {
            val outputFile = File(context.cacheDir, "tts_output_${System.currentTimeMillis()}.wav")

            val result = withTimeoutOrNull(30000L) {
                suspendCancellableCoroutine { cont ->
                    var tts: TextToSpeech? = null
                    tts = TextToSpeech(context) { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            val locale = resolveLocale(language)
                            tts?.language = locale
                            tts?.setSpeechRate(resolveSpeed(speed))

                            val utteranceId = "zeroclaw_save_${System.currentTimeMillis()}"
                            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(id: String?) {}
                                override fun onDone(id: String?) {
                                    tts?.shutdown()
                                    if (cont.isActive) cont.resume(true)
                                }
                                @Deprecated("Deprecated in Java")
                                override fun onError(id: String?) {
                                    tts?.shutdown()
                                    if (cont.isActive) cont.resume(false)
                                }
                            })

                            val params = Bundle().apply {
                                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                            }
                            tts?.synthesizeToFile(text.take(MAX_SPEAK_LENGTH), params, outputFile, utteranceId)
                        } else {
                            if (cont.isActive) cont.resume(false)
                        }
                    }

                    cont.invokeOnCancellation { tts?.shutdown() }
                }
            }

            if (result == true && outputFile.exists() && outputFile.length() > 0) {
                val sizeKb = outputFile.length() / 1024
                val sb = StringBuilder("Audio Saved\n")
                sb.appendLine("═══════════════")
                sb.appendLine()
                sb.appendLine("File: ${outputFile.absolutePath}")
                sb.appendLine("Size: ${sizeKb} KB")
                sb.appendLine("Language: $language")
                sb.appendLine("Speed: $speed")
                sb.appendLine("Words: ${text.split(Regex("\\s+")).size}")
                ToolResult(true, sb.toString())
            } else {
                ToolResult(false, "", "Failed to save audio file.")
            }
        }
    }

    /** List available TTS voices */
    private suspend fun listVoices(): ToolResult {
        return withContext(Dispatchers.Main) {
            val result = withTimeoutOrNull(5000L) {
                suspendCancellableCoroutine { cont ->
                    var tts: TextToSpeech? = null
                    tts = TextToSpeech(context) { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            val voices = tts?.voices
                            tts?.shutdown()
                            if (cont.isActive) cont.resume(voices)
                        } else {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    cont.invokeOnCancellation { tts?.shutdown() }
                }
            }

            if (result != null && result.isNotEmpty()) {
                val sb = StringBuilder("Available TTS Voices (${result.size})\n")
                sb.appendLine("═══════════════════════════════")
                sb.appendLine()

                // Group by language, show top voices
                val byLang = result.groupBy { it.locale.language }
                    .toSortedMap()
                    .entries.take(30)

                for ((lang, voices) in byLang) {
                    val langName = Locale(lang).displayLanguage
                    val voiceNames = voices.take(3).joinToString(", ") { it.name }
                    sb.appendLine("$langName ($lang): $voiceNames")
                }

                sb.appendLine()
                sb.appendLine("Use the language code (e.g. 'en', 'es', 'hi') with the 'language' parameter.")
                ToolResult(true, sb.toString().take(4000))
            } else {
                ToolResult(false, "", "Could not retrieve TTS voices. Check if a TTS engine is installed.")
            }
        }
    }

    private fun resolveLocale(lang: String): Locale {
        return when (lang) {
            "en" -> Locale.US
            "zh" -> Locale.CHINESE
            "ja" -> Locale.JAPANESE
            "ko" -> Locale.KOREAN
            "fr" -> Locale.FRENCH
            "de" -> Locale.GERMAN
            "it" -> Locale.ITALIAN
            else -> Locale(lang)
        }
    }

    private fun resolveSpeed(speed: String): Float = when (speed) {
        "slow" -> 0.7f
        "fast" -> 1.3f
        else -> 1.0f
    }

    companion object {
        private const val MAX_SPEAK_LENGTH = 4000
    }
}
