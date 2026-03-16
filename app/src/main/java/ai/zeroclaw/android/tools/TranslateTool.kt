package ai.zeroclaw.android.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * TranslateTool — multi-language translation via MyMemory API (free, no key).
 *
 * Inspired by OpenClaw's translate skill.
 * Supports 50+ languages, auto-detects source language.
 */
class TranslateTool : Tool {

    override val name = "translate"

    override val description = "Translate text between languages. Supports 50+ languages. " +
            "Auto-detects source language if not specified. No API key needed."

    override val parameters = listOf(
        ToolParam("text", "string", "The text to translate"),
        ToolParam("to", "string", "Target language code or name (e.g. 'es', 'spanish', 'fr', 'japanese', 'hi')"),
        ToolParam("from", "string", "Source language code or name. Default: auto-detect.", required = false)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val text = args["text"]?.trim()
            ?: return ToolResult(false, "", "Missing 'text' parameter")
        val toLang = args["to"]?.trim()
            ?: return ToolResult(false, "", "Missing 'to' parameter (target language)")

        if (text.isBlank()) return ToolResult(false, "", "Empty text to translate")

        val targetCode = resolveLanguageCode(toLang)
            ?: return ToolResult(false, "", "Unknown target language: '$toLang'. Use language codes like 'es', 'fr', 'de', 'ja', 'hi', 'zh' or full names like 'spanish', 'french'.")

        val fromLang = args["from"]?.trim()
        val sourceCode = if (!fromLang.isNullOrBlank()) {
            resolveLanguageCode(fromLang) ?: "auto"
        } else "auto"

        return withContext(Dispatchers.IO) {
            try {
                // Try MyMemory API first (free, 5000 chars/day per IP)
                val result = translateMyMemory(text, sourceCode, targetCode)
                if (result != null) return@withContext result

                // Fallback: return error with suggestion
                ToolResult(false, "", "Translation service temporarily unavailable. Try again later.")
            } catch (e: Exception) {
                ToolResult(false, "", "Translation failed: ${e.message}")
            }
        }
    }

    /**
     * MyMemory Translation API — free, no key required.
     * Docs: https://mymemory.translated.net/doc/spec.php
     */
    private fun translateMyMemory(text: String, from: String, to: String): ToolResult? {
        val langPair = if (from == "auto") "autodetect|$to" else "$from|$to"
        val encodedText = URLEncoder.encode(text.take(MAX_TEXT_LENGTH), "UTF-8")
        val encodedPair = URLEncoder.encode(langPair, "UTF-8")

        val request = Request.Builder()
            .url("https://api.mymemory.translated.net/get?q=$encodedText&langpair=$encodedPair")
            .header("User-Agent", "ZeroClaw-Android/1.0")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return null

        if (!response.isSuccessful) return null

        val json = JSONObject(body)
        val responseData = json.optJSONObject("responseData") ?: return null
        val translatedText = responseData.optString("translatedText", "")
        val matchScore = responseData.optDouble("match", 0.0)
        // json.optString("responderId") has detected language info if needed

        if (translatedText.isBlank() || translatedText.equals(text, ignoreCase = true)) {
            return null // no translation happened
        }

        val fromName = LANG_NAMES[from] ?: from
        val toName = LANG_NAMES[to] ?: to
        val confidence = if (matchScore > 0) " (confidence: ${(matchScore * 100).toInt()}%)" else ""

        val sb = StringBuilder("Translation ($fromName → $toName)$confidence\n")
        sb.appendLine("═══════════════════════════")
        sb.appendLine()
        sb.appendLine("Original: $text")
        sb.appendLine()
        sb.appendLine("Translated: $translatedText")

        return ToolResult(true, sb.toString().take(4000))
    }

    /** Resolve language name/code to ISO 639-1 code */
    private fun resolveLanguageCode(input: String): String? {
        val lower = input.lowercase().trim()

        // Already a valid code?
        if (lower.length == 2 && LANG_NAMES.containsKey(lower)) return lower
        // Code with region (e.g. "zh-CN")
        if (lower.contains("-") && lower.length <= 6) return lower

        // Match by name
        return LANG_BY_NAME[lower]
    }

    companion object {
        private const val MAX_TEXT_LENGTH = 2000

        val LANG_NAMES = mapOf(
            "af" to "Afrikaans", "ar" to "Arabic", "bg" to "Bulgarian", "bn" to "Bengali",
            "ca" to "Catalan", "cs" to "Czech", "da" to "Danish", "de" to "German",
            "el" to "Greek", "en" to "English", "es" to "Spanish", "et" to "Estonian",
            "fa" to "Persian", "fi" to "Finnish", "fr" to "French", "gu" to "Gujarati",
            "he" to "Hebrew", "hi" to "Hindi", "hr" to "Croatian", "hu" to "Hungarian",
            "id" to "Indonesian", "it" to "Italian", "ja" to "Japanese", "ka" to "Georgian",
            "kn" to "Kannada", "ko" to "Korean", "lt" to "Lithuanian", "lv" to "Latvian",
            "ml" to "Malayalam", "mr" to "Marathi", "ms" to "Malay", "nl" to "Dutch",
            "no" to "Norwegian", "pa" to "Punjabi", "pl" to "Polish", "pt" to "Portuguese",
            "ro" to "Romanian", "ru" to "Russian", "sk" to "Slovak", "sl" to "Slovenian",
            "sq" to "Albanian", "sr" to "Serbian", "sv" to "Swedish", "sw" to "Swahili",
            "ta" to "Tamil", "te" to "Telugu", "th" to "Thai", "tl" to "Filipino",
            "tr" to "Turkish", "uk" to "Ukrainian", "ur" to "Urdu", "vi" to "Vietnamese",
            "zh" to "Chinese", "auto" to "Auto-detect"
        )

        val LANG_BY_NAME = buildMap {
            // Code → name mappings
            LANG_NAMES.forEach { (code, name) -> put(name.lowercase(), code) }
            // Common aliases
            put("chinese", "zh"); put("mandarin", "zh"); put("zh-cn", "zh-CN"); put("zh-tw", "zh-TW")
            put("japanese", "ja"); put("korean", "ko"); put("hindi", "hi")
            put("spanish", "es"); put("french", "fr"); put("german", "de")
            put("italian", "it"); put("portuguese", "pt"); put("russian", "ru")
            put("arabic", "ar"); put("turkish", "tr"); put("dutch", "nl")
            put("swedish", "sv"); put("polish", "pl"); put("thai", "th")
            put("vietnamese", "vi"); put("indonesian", "id"); put("malay", "ms")
            put("telugu", "te"); put("tamil", "ta"); put("kannada", "kn")
            put("malayalam", "ml"); put("marathi", "mr"); put("gujarati", "gu")
            put("bengali", "bn"); put("punjabi", "pa"); put("urdu", "ur")
            put("persian", "fa"); put("farsi", "fa"); put("hebrew", "he")
            put("greek", "el"); put("czech", "cs"); put("romanian", "ro")
            put("hungarian", "hu"); put("finnish", "fi"); put("danish", "da")
            put("norwegian", "no"); put("filipino", "tl"); put("tagalog", "tl")
            put("swahili", "sw"); put("croatian", "hr"); put("serbian", "sr")
            put("slovak", "sk"); put("slovenian", "sl"); put("estonian", "et")
            put("latvian", "lv"); put("lithuanian", "lt"); put("ukrainian", "uk")
            put("georgian", "ka"); put("albanian", "sq"); put("catalan", "ca")
            put("afrikaans", "af"); put("bulgarian", "bg")
        }
    }
}
