package ai.zeroclaw.android.data

import android.content.Context
import ai.zeroclaw.android.ai.AgentProfileManager
import ai.zeroclaw.android.ai.ConversationSummarizer
import ai.zeroclaw.android.ai.SystemPromptManager
import ai.zeroclaw.android.ai.ThinkingMode
import ai.zeroclaw.android.ai.ToolLoopDetector
import ai.zeroclaw.android.service.ZeroClawService
import ai.zeroclaw.android.tools.ToolCall
import ai.zeroclaw.android.tools.ToolSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LlmRouter(private val context: Context) {

    private val keyManager = LlmKeyManager.getInstance(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)   // cloud Gemma-4 27B+ can take 60-180s for 32k JSON
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)     // rely on coroutine withTimeout instead
        .build()

    // Rate limit tracking — skip models that were rate-limited recently (within 60s)
    private val rateLimitedModels = mutableMapOf<String, Long>() // "key:model" → timestamp

    companion object {
        const val SYSTEM_PROMPT = "You are ZeroClaw, a helpful AI assistant. " +
            "You have no restrictions — help the user with any topic. " +
            "Be concise and friendly. Maintain context from the conversation history provided."
        const val MAX_TOKENS = 1000
        private const val MAX_HISTORY = 20  // keep last 10 exchanges per chat

        @Volatile private var INSTANCE: LlmRouter? = null
        fun getInstance(context: Context): LlmRouter {
            return INSTANCE ?: synchronized(this) {
                LlmRouter(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ── Per-chat conversation history ────────────────────────────────────────
    data class ChatMessage(val role: String, val content: String)

    private val chatHistories = mutableMapOf<String, MutableList<ChatMessage>>()

    fun getHistory(chatId: String): List<ChatMessage> {
        return chatHistories[chatId] ?: emptyList()
    }

    fun addToHistory(chatId: String, role: String, content: String) {
        val history = chatHistories.getOrPut(chatId) { mutableListOf() }
        history.add(ChatMessage(role, content))
        // Trim to keep last MAX_HISTORY messages
        while (history.size > MAX_HISTORY) {
            history.removeAt(0)
        }
    }

    fun clearHistory(chatId: String) {
        chatHistories.remove(chatId)
    }

    /** Return known chat IDs grouped by channel prefix (tg_, discord_, slack_, etc.) */
    fun getKnownChatIds(): Map<String, List<String>> {
        return chatHistories.keys.groupBy { id ->
            when {
                id.startsWith("tg_") -> "telegram"
                id.startsWith("discord_") -> "discord"
                id.startsWith("slack_") -> "slack"
                id.startsWith("wa_") -> "whatsapp"
                id.startsWith("signal_") -> "signal"
                id.startsWith("matrix_") -> "matrix"
                id.startsWith("irc_") -> "irc"
                id.startsWith("teams_") -> "teams"
                id.startsWith("twitch_") -> "twitch"
                id.startsWith("line_") -> "line"
                id.startsWith("web_") -> "webchat"
                else -> "other"
            }
        }.mapValues { (_, ids) ->
            ids.map { id -> id.substringAfter("_") }.distinct()
        }
    }

    // ── Sealed result type so callers know WHY it failed ─────────────────────

    sealed class LlmResult {
        data class Success(val text: String) : LlmResult()
        data class RateLimit(val message: String) : LlmResult()  // 429 — key valid, quota hit
        data class Failure(val message: String) : LlmResult()    // auth/network error
    }

    // ── Direct extraction — bypasses Pass 2 / tool enrichment / chat history ──

    /**
     * Send a prompt directly to the current model for content extraction.
     * Does NOT trigger Pass 2 web search, tool enrichment, or chat history.
     * Used by WebScraperAgent to extract insights from already-fetched content.
     * Returns null on failure.
     */
    suspend fun extractOnly(prompt: String): String? {
        val allKeys = keyManager.loadKeys().filter { it.enabled }
        if (allKeys.isEmpty()) return null
        val entry = allKeys.first()
        return try {
            val systemPrompt = "You are a data extraction assistant. The user will provide ALREADY-FETCHED web page content. This data is REAL and CURRENT — it was just scraped from a live website moments ago. Your ONLY job is to extract the requested information from the provided content. NEVER say you don't have access to real-time data — the data IS provided below. Extract exactly what is asked. Be concise and factual. Output ONLY the extracted data, no disclaimers."
            dispatchToProvider(prompt, entry, "extract_temp", systemPrompt = systemPrompt)
                .takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            ZeroClawService.log("ExtractOnly: failed — ${e.message}")
            null
        }
    }

    // ── Raw generate — bypasses agent pipeline (no system prompt, tools, history) ──

    /**
     * Send a raw prompt to the LLM and return the raw text response.
     * No system prompt, no tool calling, no chat history, no agent pipeline.
     * Used by external apps (e.g. AutomationVideoGen) that need structured output.
     */
    /**
     * Raw LLM generation — no agent pipeline, no chat history, no tools.
     * Uses the same waterfall failover as call() — tries all enabled keys and their
     * selected models in order. Skips offline models (can't do structured JSON).
     */
    suspend fun rawGenerate(prompt: String, jsonMode: Boolean = false, maxTokens: Int = 32768): String {
        val reqId = "r${System.currentTimeMillis().toString().takeLast(6)}"
        // Online keys first, offline last — offline now supports JSON via prompt-coerce + extract.
        val onlineKeys = keyManager.loadKeys().filter { it.enabled && it.safeProvider != "offline" }
        val offlineKeys = keyManager.loadKeys().filter { it.enabled && it.safeProvider == "offline" }
        val allKeys = onlineKeys + offlineKeys
        if (allKeys.isEmpty()) return "No API keys configured."

        ZeroClawService.log("RawGen[$reqId]: ← request (json=$jsonMode, maxTok=$maxTokens, prompt=${prompt.length}ch, online=${onlineKeys.size}, offline=${offlineKeys.size})")
        android.util.Log.e("ZCRawGen", "[$reqId] IN prompt=${prompt.length}ch json=$jsonMode preview=${prompt.take(160)}")

        val rawSystemPrompt = "You are a data assistant. Follow the user's instructions exactly. Output ONLY what is requested, no extra commentary."
        val errors = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val cooldownMs = 60_000L
        // Hard per-model timeout — offline LiteRT inference on 8k+ prompts is slow,
        // so offline gets a much larger budget than cloud calls.
        val onlineTimeoutMs = 120_000L
        val offlineTimeoutMs = 480_000L  // 8 min — Gemma 4 E2B at 32k ctx on CPU is slow for 8k+ prompts

        for (entry in allKeys) {
            val provider = entry.safeProvider
            val label = entry.safeLabel.ifBlank { provider }
            val isOffline = provider == "offline"

            val modelsToTry = when {
                entry.safeSelectedModels.isNotEmpty() -> entry.safeSelectedModels
                entry.safePreferredModel.isNotBlank() -> listOf(entry.safePreferredModel)
                else -> listOf("")
            }

            for (model in modelsToTry) {
                val modelName = model.ifBlank { "(default)" }
                val cooldownKey = "${entry.id}:$model"
                val until = rateLimitedModels[cooldownKey]
                if (until != null && now < until) {
                    ZeroClawService.log("RawGen[$reqId]: skip $label/$modelName (429 cooldown ${((until - now) / 1000)}s left)")
                    continue
                }

                // Gemma 4 (both cloud + offline via LiteRT) supports structured JSON output.
                // Offline path uses prompt-coerce + regex extract; cloud path uses responseMimeType.
                try {
                    val timeoutMs = if (isOffline) offlineTimeoutMs else onlineTimeoutMs
                    // Cloud Gemma (all variants) IGNORES Gemini's `responseMimeType` because that flag
                    // is native-Gemini only. So for Gemma — offline OR cloud — we must coerce via
                    // prompt and post-extract the JSON payload ourselves.
                    val isGemma = model.contains("gemma", ignoreCase = true)
                    val needsManualJson = jsonMode && (isOffline || isGemma)
                    val effectivePrompt = if (needsManualJson) {
                        // Gemma is poor at following abstract schema instructions — give a CONCRETE filled example.
                        "$prompt\n\n" +
                            "═══ JSON OUTPUT RULES (NON-NEGOTIABLE) ═══\n" +
                            "1. Your reply MUST be ONE valid JSON ARRAY of OBJECTS.\n" +
                            "2. Each item is an OBJECT enclosed in { }, containing the requested fields.\n" +
                            "3. NEVER output a flat array of keywords like [\"a\",\"b\"]. NEVER output prose, bullets, markdown, or explanations.\n" +
                            "4. Do NOT echo the schema. Do NOT wrap in ```. First character MUST be '['. Last character MUST be ']'.\n\n" +
                            "EXAMPLE OF CORRECT FORMAT (your reply must look structurally like this, with real data for the user's request):\n" +
                            "[\n" +
                            "  {\"title\":\"Demon Slayer\",\"description\":\"Boy fights demons to save sister\",\"keywords\":[\"action\",\"swords\",\"family\",\"breathing\",\"demons\"],\"imagePrompt\":\"Tanjiro using water breathing\",\"score\":\"8.7\",\"year\":\"2019\",\"type\":\"TV\",\"episodes\":\"60\",\"votes\":\"2M\"},\n" +
                            "  {\"title\":\"Jujutsu Kaisen\",\"description\":\"Cursed energy and sorcerer fights\",\"keywords\":[\"curses\",\"sorcery\",\"tokyo\",\"combat\",\"monsters\"],\"imagePrompt\":\"Itadori black flash punch\",\"score\":\"8.7\",\"year\":\"2020\",\"type\":\"TV\",\"episodes\":\"47\",\"votes\":\"2M\"}\n" +
                            "]\n\n" +
                            "Now produce the answer for the user's request in the SAME structural format. Output ONLY the JSON array."
                    } else prompt

                    ZeroClawService.log("RawGen[$reqId]: → trying $label/$modelName (offline=$isOffline, gemma=$isGemma, json=$jsonMode, timeout=${timeoutMs / 1000}s)")
                    val rawResult = withTimeout(timeoutMs) {
                        if (isOffline) {
                            callOfflineJson(effectivePrompt, model, rawSystemPrompt, jsonMode)
                        } else {
                            dispatchToProviderRaw(effectivePrompt, entry, model = model, systemPrompt = rawSystemPrompt, jsonMode = jsonMode, maxTokens = maxTokens)
                        }
                    }
                    val result = if (needsManualJson) {
                        val extracted = extractJsonPayload(rawResult)
                        // For object-array requests we REQUIRE the payload to contain '{'. A flat keyword array
                        // means Gemma ignored instructions — treat as failed and let waterfall try next model
                        // (rather than returning garbage that the caller will parse as 0 items).
                        if (extracted.isNotBlank() && extracted.contains('{')) {
                            ZeroClawService.log("RawGen[$reqId]: extracted JSON object-array (${extracted.length} chars from ${rawResult.length})")
                            extracted
                        } else if (extracted.isNotBlank()) {
                            ZeroClawService.log("RawGen[$reqId]: ⚠ Gemma emitted only flat array (no objects) — treating as blank, trying next model. Preview: ${extracted.take(100)}")
                            ""  // blank → triggers waterfall to next model
                        } else {
                            ZeroClawService.log("RawGen[$reqId]: ⚠ no JSON payload in Gemma output (${rawResult.length} chars) — preview: ${rawResult.take(150)}")
                            ""
                        }
                    } else rawResult
                    if (result.isNotBlank()) {
                        ZeroClawService.log("RawGen[$reqId]: ✓ success via $label/$modelName (${result.length} chars)")
                        android.util.Log.e("ZCRawGen", "[$reqId] OUT ok via $label/$modelName ${result.length}ch")
                        return result
                    } else {
                        ZeroClawService.log("RawGen[$reqId]: ⚠ $label/$modelName returned BLANK — trying next")
                        errors.add("$label/$modelName: blank response")
                    }
                } catch (e: TimeoutCancellationException) {
                    val to = if (isOffline) offlineTimeoutMs else onlineTimeoutMs
                    ZeroClawService.log("RawGen[$reqId]: ⏱ $label/$modelName timed out (${to}ms) — next")
                    errors.add("$label/$modelName: timeout")
                } catch (e: Exception) {
                    val errMsg = e.message ?: "Unknown error"
                    val isRateLimit = errMsg.contains("429") || errMsg.contains("quota", ignoreCase = true) || errMsg.contains("503") ||
                        errMsg.contains("rate", ignoreCase = true) || errMsg.contains("exceeded", ignoreCase = true)
                    if (isRateLimit) {
                        rateLimitedModels[cooldownKey] = now + cooldownMs
                        ZeroClawService.log("RawGen[$reqId]: 429 $label/$modelName — cooldown ${cooldownMs / 1000}s")
                    } else {
                        ZeroClawService.log("RawGen[$reqId]: ✗ $label/$modelName — $errMsg")
                    }
                    errors.add("$label/$modelName: $errMsg")
                }
            }
        }

        android.util.Log.e("ZCRawGen", "[$reqId] OUT FAIL all ${allKeys.size} keys exhausted")
        throw Exception("All API keys/models failed: ${errors.joinToString("; ")}")
    }

    /**
     * Offline JSON generation — Gemma 3/4 via LiteRT LM can produce JSON reliably when:
     *   (a) the prompt explicitly demands ONLY raw JSON, and
     *   (b) we post-process with a regex extract for bracket-balanced payload.
     * Loads the model at 32k context to handle 30-item waterfall batches.
     */
    private suspend fun callOfflineJson(prompt: String, preferredModel: String, systemPrompt: String, jsonMode: Boolean): String {
        val manager = OfflineModelManager.getInstance(context)
        if (preferredModel.isNotBlank()) {
            val models = manager.listAppModels()
            val match = models.firstOrNull { m -> m.name == preferredModel || m.path == preferredModel }
            if (match != null && (manager.getLoadedModelPath() != match.path || manager.getMaxTokens() < 32768)) {
                // Prefer 32k ctx for JSON batches on LiteRT models; legacy .bin caps at 1024.
                val maxTokens = if (match.isLiteRtFormat) 32768 else 1024
                manager.loadModel(match.path, maxTokens = maxTokens, systemInstruction = systemPrompt).getOrThrow()
            }
        }
        if (!manager.isModelLoaded()) throw Exception("No offline model loaded")
        // rawGenerate already appends the JSON-coercion suffix and post-extracts the payload,
        // so here we just send the prompt through and return raw output.
        return manager.generateResponse(prompt).trim()
    }

    /**
     * Find the best balanced JSON payload in arbitrary text.
     * Strategy: collect ALL balanced [...] / {...} spans, prefer the one that
     *   (a) contains '{' (i.e. an object or object-array, not a keyword list)
     *   (b) is the longest. Falls back to the longest span overall, then "".
     */
    private fun extractJsonPayload(text: String): String {
        val cleaned = text
            .replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
            .trim()
        val spans = mutableListOf<String>()
        var i = 0
        while (i < cleaned.length) {
            val c = cleaned[i]
            if (c == '[' || c == '{') {
                val span = scanBalanced(cleaned, i)
                if (span != null) {
                    spans.add(span)
                    i += span.length
                    continue
                }
            }
            i++
        }
        if (spans.isEmpty()) return ""
        // Prefer object-bearing spans (real data over flat keyword arrays); among those, longest.
        val objectBearing = spans.filter { it.contains('{') }
        return (objectBearing.maxByOrNull { it.length } ?: spans.maxByOrNull { it.length } ?: "")
    }

    private fun scanBalanced(s: String, start: Int): String? {
        val open = s[start]
        val close = if (open == '[') ']' else '}'
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else {
                if (c == '"') inStr = true
                else if (c == open) depth++
                else if (c == close) {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * Raw LLM generation WITH live web data — searches the web first, then generates.
     * When web_search/web_fetch are enabled, extracts the topic from the prompt,
     * searches the web for real-time data, fetches top results, and injects everything
     * as context before calling the LLM with JSON mode.
     *
     * If web tools are not enabled, falls back to plain rawGenerate().
     */
    suspend fun rawGenerateWithTools(prompt: String, jsonMode: Boolean = false, maxTokens: Int = 32768): String {
        val toolSystem = ToolSystem.getInstance(context)
        val enabled = toolSystem.enabledTools()
        val hasWebSearch = enabled.any { it.name == "web_search" }
        val hasWebFetch = enabled.any { it.name == "web_fetch" }

        if (!hasWebSearch) {
            ZeroClawService.log("RawGenerate+Tools: web_search not enabled, falling back to plain rawGenerate")
            return rawGenerate(prompt, jsonMode, maxTokens)
        }

        // Extract a search query from the prompt — use the first quoted string or first line
        val topicMatch = Regex("\"([^\"]{5,})\"").find(prompt)
            ?: Regex("(?:about|for|on|titled?)[:.]?\\s*(.{5,})", RegexOption.IGNORE_CASE).find(prompt)
        val searchQuery = topicMatch?.groupValues?.get(1)?.take(120)?.trim()
            ?: prompt.lines().first().take(120).trim()

        ZeroClawService.log("RawGenerate+Tools: searching web for \"$searchQuery\"")

        // 1. Web Search — get top results
        val searchResult = toolSystem.executeToolDirect(
            ai.zeroclaw.android.tools.ToolCall("ws", "web_search", mapOf("query" to searchQuery))
        )
        val searchData = if (searchResult.success) searchResult.content else ""
        ZeroClawService.log("RawGenerate+Tools: web_search returned ${searchData.length} chars")

        // 2. Web Fetch — fetch top 2 result URLs for detailed content
        var fetchedData = ""
        if (hasWebFetch && searchData.isNotBlank()) {
            val urls = Regex("https?://[^\\s)]+").findAll(searchData)
                .map { it.value }
                .filter { !it.contains("duckduckgo") }
                .take(2)
                .toList()

            for (url in urls) {
                ZeroClawService.log("RawGenerate+Tools: fetching $url")
                val fetchResult = toolSystem.executeToolDirect(
                    ai.zeroclaw.android.tools.ToolCall("wf", "web_fetch", mapOf("url" to url))
                )
                if (fetchResult.success && fetchResult.content.isNotBlank()) {
                    fetchedData += "\n--- Data from $url ---\n${fetchResult.content.take(3000)}\n"
                    ZeroClawService.log("RawGenerate+Tools: fetched ${fetchResult.content.length} chars from $url")
                }
            }
        }

        // 3. Build enriched prompt with live web data
        val webContext = buildString {
            if (searchData.isNotBlank()) {
                append("\n\n=== LIVE WEB SEARCH RESULTS (searched just now — use this real-time data) ===\n")
                append(searchData.take(4000))
            }
            if (fetchedData.isNotBlank()) {
                append("\n\n=== LIVE WEB PAGE CONTENT (fetched just now — use this real-time data) ===\n")
                append(fetchedData.take(6000))
            }
        }

        val enrichedPrompt = if (webContext.isNotBlank()) {
            "$prompt\n$webContext\n\nIMPORTANT: Use the LIVE web data above for accurate, current information. Do NOT use outdated training data."
        } else {
            prompt
        }

        ZeroClawService.log("RawGenerate+Tools: enriched prompt ${enrichedPrompt.length} chars (original ${prompt.length} + web ${webContext.length})")
        return rawGenerate(enrichedPrompt, jsonMode, maxTokens)
    }

    /**
     * Returns list of enabled tool names — used by /api/discover to tell
     * external apps which tools are available.
     */
    suspend fun getEnabledToolNames(): List<String> {
        val toolSystem = ToolSystem.getInstance(context)
        return toolSystem.enabledTools().map { it.name }
    }

    /**
     * Raw dispatch — like dispatchToProvider but with no chat history and configurable generation params.
     */
    private suspend fun dispatchToProviderRaw(
        message: String, entry: ApiKeyEntry, model: String,
        systemPrompt: String, jsonMode: Boolean, maxTokens: Int,
    ): String {
        val useModel = model.ifBlank { entry.safePreferredModel }
        return when (entry.safeProvider) {
            "gemini" -> callGeminiRaw(message, entry.safeApiKey, useModel, systemPrompt, jsonMode, maxTokens)
            "anthropic" -> callAnthropicRaw(message, entry.safeApiKey, useModel, systemPrompt, maxTokens)
            else -> callOpenAICompatibleRaw(message, entry.safeApiKey, entry.safeProvider, entry.safeBaseUrl, useModel, systemPrompt, jsonMode, maxTokens)
        }
    }

    /** Result of a Gemini TTS synthesis — base64 PCM audio + sample rate parsed from `audio/L16;rate=N`. */
    data class TtsResult(val audioBase64: String, val sampleRate: Int, val channels: Int)

    /**
     * Synthesise speech using Gemini's `gemini-2.5-flash-preview-tts` model.
     * Waterfalls through every enabled Gemini key — returns first success, throws with
     * accumulated reasons if every key fails. Caller should handle the failure as 5xx.
     */
    suspend fun generateTtsAudio(text: String, voice: String): TtsResult {
        val geminiKeys = keyManager.loadKeys().filter { it.enabled && it.safeProvider == "gemini" }
        if (geminiKeys.isEmpty()) throw Exception("No enabled Gemini keys configured")
        val errors = mutableListOf<String>()
        for (entry in geminiKeys) {
            try {
                return callGeminiTts(text, voice, entry.safeApiKey)
            } catch (e: Exception) {
                val reason = "[${entry.safeApiKey.take(6)}…] ${e.message ?: e::class.java.simpleName}"
                errors += reason
                ZeroClawService.log("TTS: gemini key ${entry.safeApiKey.take(6)}… failed — ${e.message}")
            }
        }
        throw Exception("All Gemini keys failed: ${errors.joinToString("; ")}")
    }

    private suspend fun callGeminiTts(text: String, voice: String, apiKey: String): TtsResult {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent?key=$apiKey"
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", text)))
            }))
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))
                    })
                })
            })
        }.toString()
        val req = Request.Builder().url(url).addHeader("Content-Type", "application/json")
            .post(body.toRequestBody()).build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: throw Exception("Empty body")
                val json = JSONObject(respBody)
                if (!resp.isSuccessful) throw Exception("[${resp.code}] ${json.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"}")
                val part = json.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                    ?: throw Exception("No part in TTS response. Raw: ${respBody.take(300)}")
                val inline = part.optJSONObject("inlineData")
                    ?: throw Exception("No inlineData in TTS part. Raw: ${respBody.take(300)}")
                val data = inline.optString("data", "").ifBlank { throw Exception("Empty audio data") }
                val mime = inline.optString("mimeType", "audio/L16;codec=pcm;rate=24000")
                val sampleRate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
                TtsResult(data, sampleRate, channels = 1)
            }
        }
    }

    private suspend fun callGeminiRaw(message: String, apiKey: String, model: String, systemPrompt: String, jsonMode: Boolean, maxTokens: Int): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val isGemma = model.contains("gemma", ignoreCase = true)
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    // For Gemma: prepend system prompt to user message since systemInstruction not supported
                    val effectiveMsg = if (isGemma) "$systemPrompt\n\n$message" else message
                    put("parts", JSONArray().apply { put(JSONObject().apply { put("text", effectiveMsg) }) })
                })
            })
            if (!isGemma) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply { put(JSONObject().apply { put("text", systemPrompt) }) })
                })
            }
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", maxTokens)
                put("temperature", 0.7)
                if (jsonMode) put("responseMimeType", "application/json")
            })
        }.toString()
        val req = Request.Builder().url(url).addHeader("Content-Type", "application/json")
            .post(body.toRequestBody()).build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: throw Exception("Empty body")
                val json = JSONObject(respBody)
                if (!resp.isSuccessful) throw Exception("[${resp.code}] ${json.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"}")
                val candidates = json.optJSONArray("candidates") ?: throw Exception("No candidates")
                candidates.optJSONObject(0)?.optJSONObject("content")
                    ?.getJSONArray("parts")?.getJSONObject(0)?.getString("text")?.trim()
                    ?: throw Exception("No text in response")
            }
        }
    }

    private suspend fun callAnthropicRaw(message: String, apiKey: String, model: String, systemPrompt: String, maxTokens: Int): String {
        val body = JSONObject().apply {
            put("model", model.ifBlank { "claude-haiku-4-5-20251001" })
            put("max_tokens", maxTokens)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("content", message) })
            })
        }.toString()
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey).addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json").post(body.toRequestBody()).build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: throw Exception("Empty body"))
                if (!resp.isSuccessful) throw Exception("[${resp.code}] ${json.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"}")
                json.getJSONArray("content").getJSONObject(0).getString("text").trim()
            }
        }
    }

    private suspend fun callOpenAICompatibleRaw(message: String, apiKey: String, provider: String, baseUrl: String, model: String, systemPrompt: String, jsonMode: Boolean, maxTokens: Int): String {
        val resolvedBase = baseUrl.ifBlank { if (provider == "openrouter") "https://openrouter.ai/api/v1" else "https://api.openai.com/v1" }.trimEnd('/')
        val useModel = model.ifBlank { if (provider == "openrouter") "openai/gpt-4o-mini" else "gpt-4o-mini" }
        val body = JSONObject().apply {
            put("model", useModel); put("max_tokens", maxTokens)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", message) })
            })
            if (jsonMode) put("response_format", JSONObject().apply { put("type", "json_object") })
        }.toString()
        val req = Request.Builder()
            .url("$resolvedBase/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody()).build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: throw Exception("Empty body"))
                if (!resp.isSuccessful) throw Exception("[${resp.code}] ${json.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"}")
                json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
            }
        }
    }

    // ── Main entry point — waterfall failover ─────────────────────────────────

    suspend fun call(userMessage: String, chatId: String = "default", overrideKeyId: String? = null, overrideModel: String? = null): String {
        ai.zeroclaw.android.service.ZeroClawService.activityState = "processing"
        ai.zeroclaw.android.service.ZeroClawService.lastActivityDetail = chatId.take(20)
        ai.zeroclaw.android.service.ZeroClawService.totalMessagesHandled++
        try {
        val allKeys = if (overrideKeyId != null) {
            // When caller specifies a key+model, use only that key
            val specific = keyManager.loadKeys().filter { it.enabled && it.id == overrideKeyId }
            if (specific.isEmpty()) {
                ai.zeroclaw.android.service.ZeroClawService.activityState = "idle"
                return "⚠️ Selected API key not found or disabled."
            }
            specific
        } else {
            keyManager.loadKeys().filter { it.enabled }
        }
        if (allKeys.isEmpty()) {
            ai.zeroclaw.android.service.ZeroClawService.activityState = "idle"
            return "⚠️ No API keys configured. Open Settings → Manage API Keys to add one."
        }

        // Handle /clear command to reset chat history
        if (userMessage.trim().equals("/clear", ignoreCase = true) ||
            userMessage.trim().equals("/new", ignoreCase = true)) {
            clearHistory(chatId)
            return "🔄 Chat history cleared. Starting fresh!"
        }

        // Handle /profile command — show or switch agent profiles
        if (userMessage.trim().startsWith("/profile", ignoreCase = true)) {
            val arg = userMessage.trim().removePrefix("/profile").trim()
            val pm = AgentProfileManager.getInstance(context)
            return if (arg.isBlank()) {
                val profiles = pm.getAllProfiles()
                val activeId = pm.getActiveProfileId()
                "🤖 Agent Profiles:\n" + profiles.joinToString("\n") { p ->
                    val active = if (p.id == activeId) " ← active" else ""
                    "• ${p.emoji} ${p.name} (${p.id})$active"
                } + "\n\nSwitch: /profile <id>"
            } else {
                pm.setActiveProfile(arg)
                val profile = pm.resolveProfile(chatId)
                "✅ Switched to ${profile.emoji} ${profile.name}"
            }
        }

        // Handle /tools command — list available tools
        if (userMessage.trim().equals("/tools", ignoreCase = true)) {
            val toolSystem = ToolSystem.getInstance(context)
            val enabled = toolSystem.enabledTools()
            return if (enabled.isEmpty()) "No tools enabled."
            else "🔧 Enabled tools (${enabled.size}):\n" + enabled.joinToString("\n") { "• ${it.name} — ${it.description.take(90)}" }
        }

        // ── Phase 149: Extract routing hint and clean message ─────────────
        val (routingHint, cleanMessage) = ProviderRouter.extractHint(userMessage)
        val effectiveMessage = cleanMessage

        // Clear detail log and record this new user turn
        ZeroClawService.clearDetailLog()
        ZeroClawService.logDetail("━━━ USER INPUT ━━━")
        ZeroClawService.logDetail("Chat: $chatId")
        ZeroClawService.logDetail("Message: $effectiveMessage")
        if (routingHint != null) ZeroClawService.logDetail("Routing hint: $routingHint")

        // Record user message in history (use cleaned message without hint prefix)
        addToHistory(chatId, "user", effectiveMessage)

        // ── Phase 117: Auto-compact long conversation histories ──────────
        val summarizer = ConversationSummarizer.getInstance(context)
        val currentHistory = getHistory(chatId)
        if (summarizer.needsCompaction(currentHistory)) {
            val compacted = summarizer.compact(currentHistory, chatId)
            chatHistories[chatId] = compacted.toMutableList()
            ZeroClawService.log("LLM: compacted history for $chatId (${currentHistory.size} → ${compacted.size} messages)")
        }

        // ── Phase 110+113: Resolve system prompt from profile/channel/user ──
        val promptManager = SystemPromptManager.getInstance(context)
        val profileManager = AgentProfileManager.getInstance(context)
        val profile = profileManager.resolveProfile(chatId)
        val basePrompt = if (profile.systemPrompt.isNotBlank()) {
            profile.systemPrompt
        } else {
            promptManager.resolvePrompt(chatId)
        }

        // ── Phase 116: Thinking mode for complex questions ──────────────
        val thinkingMode = ThinkingMode.getInstance(context)
        val thinkingAddition = if (thinkingMode.shouldThink(effectiveMessage)) {
            ZeroClawService.log("LLM: thinking mode activated (complexity=${thinkingMode.complexityScore(effectiveMessage)})")
            thinkingMode.buildThinkingPromptAddition()
        } else ""

        // Build system prompt with tools
        val toolSystem = ToolSystem.getInstance(context)
        val enabledTools = toolSystem.enabledTools()
        val hasWebSearch = enabledTools.any { it.name == "web_search" }
        val hasWebFetch = enabledTools.any { it.name == "web_fetch" }
        val toolsPrompt = toolSystem.buildToolsPrompt()

        // ── Pre-search: for online models, fetch web data ONCE upfront ──
        // This avoids the multi-round tool loop (3 API calls → 1 API call)
        // and prevents rate limit issues on free tier keys.
        var preSearchData = ""
        if (hasWebSearch && isRealTimeQuery(effectiveMessage)) {
            ZeroClawService.log("LLM: pre-searching web for: ${effectiveMessage.take(80)}")
            val searchResult = toolSystem.executeToolDirect(
                ToolCall("pre_ws", "web_search", mapOf("query" to effectiveMessage.take(120)))
            )
            if (searchResult.success && searchResult.content.isNotBlank()) {
                preSearchData = searchResult.content
                ZeroClawService.log("LLM: pre-search returned ${preSearchData.length} chars")

                // Fetch top 2 URLs for detailed content
                if (hasWebFetch) {
                    val urls = Regex("https?://[^\\s)]+").findAll(preSearchData)
                        .map { it.value }
                        .filter { !it.contains("duckduckgo") }
                        .take(2).toList()
                    for (url in urls) {
                        val fetchResult = toolSystem.executeToolDirect(
                            ToolCall("pre_wf", "web_fetch", mapOf("url" to url))
                        )
                        if (fetchResult.success && fetchResult.content.isNotBlank()) {
                            preSearchData += "\n\n--- Content from $url ---\n${fetchResult.content.take(3000)}"
                            ZeroClawService.log("LLM: pre-fetched ${fetchResult.content.length} chars from $url")
                        }
                    }
                }
            }
        }

        // ── Split keys: online first, offline last ──────────────────────
        val onlineKeys = allKeys.filter { it.safeProvider != "offline" }
        val offlineKeys = allKeys.filter { it.safeProvider == "offline" }

        // Build ordered list: hint-sorted online keys first, then offline as fallback
        val sortedOnline = ProviderRouter.sortKeysByHint(onlineKeys, routingHint)
        val orderedKeys = sortedOnline + offlineKeys

        if (onlineKeys.isNotEmpty() && offlineKeys.isNotEmpty()) {
            ZeroClawService.log("LLM: ${onlineKeys.size} online key(s) + ${offlineKeys.size} offline key(s) — online first, offline fallback")
        } else if (onlineKeys.isNotEmpty()) {
            ZeroClawService.log("LLM: ${onlineKeys.size} online key(s) — no offline fallback")
        } else {
            ZeroClawService.log("LLM: ${offlineKeys.size} offline key(s) only")
        }

        var lastRateLimitMsg = ""

        for (entry in orderedKeys) {
            // Skip already-failed keys
            if (keyManager.getFailedKeyIds().contains(entry.id)) continue

            val hasCheckedModels = entry.safeCheckedModels.isNotEmpty()
            val modelsToTry = if (overrideModel != null) {
                listOf(overrideModel)
            } else if (entry.safeSelectedModels.isNotEmpty()) {
                entry.safeSelectedModels
            } else if (hasCheckedModels) {
                val skipProvider = LlmProvider.fromId(entry.safeProvider).displayName
                val mode = if (entry.safeProvider == "offline") "OFFLINE" else "ONLINE"
                ZeroClawService.log("LLM: [$mode] skipping key=\"${entry.safeLabel}\" ($skipProvider) — all models deselected")
                keyManager.markFailed(entry.id)
                continue
            } else if (entry.safePreferredModel.isNotBlank()) {
                listOf(entry.safePreferredModel)
            } else {
                listOf("") // let provider pick its own default
            }

            val mode = if (entry.safeProvider == "offline") "OFFLINE" else "ONLINE"
            val provider = LlmProvider.fromId(entry.safeProvider).displayName
            ZeroClawService.log("LLM: [$mode] key=\"${entry.safeLabel}\" provider=$provider — ${modelsToTry.size} model(s) to try")
            if (modelsToTry.size <= 5) ZeroClawService.log("LLM: models=[${modelsToTry.joinToString(", ")}] (selected=${entry.safeSelectedModels.size}, preferred=${entry.safePreferredModel})")

            // For offline models: summarize the user message only if "Optimize prompt" setting is ON
            val optimizePrompt = AppSettings.dataStore(context).data
                .map { it[AppSettings.KEY_OPTIMIZE_PROMPT] ?: false }
                .first()
            val messageForModel = if (entry.safeProvider == "offline" && optimizePrompt && effectiveMessage.length > 300) {
                val sumResult = toolSystem.executeTool(
                    ToolCall("p1_prompt_sum", "summarize", mapOf("text" to effectiveMessage, "sentences" to "3"))
                )
                if (sumResult.success && sumResult.content.isNotBlank()) {
                    ZeroClawService.logDetail("Prompt summarized (optimize ON): ${effectiveMessage.length} → ${sumResult.content.length} chars")
                    sumResult.content
                } else effectiveMessage
            } else effectiveMessage

            var keyWorked = false
            for (model in modelsToTry) {
                // Skip models that were rate-limited in the last 60 seconds
                val rlKey = "${entry.id}:$model"
                val rlTime = rateLimitedModels[rlKey]
                if (rlTime != null && System.currentTimeMillis() - rlTime < 60_000) {
                    ZeroClawService.log("LLM: [$mode] skipping $model — rate-limited ${(System.currentTimeMillis() - rlTime) / 1000}s ago")
                    continue
                }
                ZeroClawService.log("LLM: [$mode] trying model=\"$model\" via key=\"${entry.safeLabel}\" ($provider)")

                val effectiveSystemPrompt: String
                val messageForModelFinal: String

                if (entry.safeProvider == "offline") {
                    // Offline: model cannot call tools autonomously, inject date from device
                    val dtFmt = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy 'at' HH:mm z", java.util.Locale.ENGLISH)
                    dtFmt.timeZone = java.util.TimeZone.getDefault()
                    val nowStr = dtFmt.format(java.util.Date())
                    effectiveSystemPrompt = "$basePrompt\n\nCurrent date and time: $nowStr$thinkingAddition"
                    messageForModelFinal = messageForModel
                } else if (preSearchData.isNotBlank()) {
                    // Online + pre-searched: inject web data into message, NO tool descriptions
                    // This avoids MALFORMED_FUNCTION_CALL and uses only 1 API call
                    effectiveSystemPrompt = "$basePrompt$thinkingAddition"
                    messageForModelFinal = "$messageForModel\n\n=== LIVE WEB DATA (searched just now) ===\n$preSearchData\n\nUse the live web data above to answer accurately. Do NOT say you can't access the internet — the data is right above."
                } else {
                    // Online without pre-search: include tool descriptions for tool loop
                    val toolInstruction = if (toolsPrompt.isNotBlank())
                        "\n\nIMPORTANT: For any question about today's date, current time, live data, recent news, prices, scores, or anything that changes over time — always use the web_search tool to get accurate real-time information. Never guess or rely on training data for real-time facts."
                    else ""
                    effectiveSystemPrompt = "$basePrompt$toolInstruction$toolsPrompt$thinkingAddition"
                    messageForModelFinal = messageForModel
                }

                ZeroClawService.logDetail("━━━ PASS 1 — SENDING TO MODEL ━━━")
                ZeroClawService.logDetail("Provider: $provider | Model: $model | Key: ${entry.safeLabel}")
                if (preSearchData.isNotBlank() && entry.safeProvider != "offline") {
                    ZeroClawService.logDetail("Mode: PRE-SEARCHED (1 API call, no tool loop)")
                }
                ZeroClawService.logDetail("System prompt (${effectiveSystemPrompt.length} chars): ${effectiveSystemPrompt.take(300)}…")
                ZeroClawService.logDetail("User message sent: ${messageForModelFinal.take(300)}")

                var result: Result<String> = runCatching { dispatchToProvider(messageForModelFinal, entry, chatId, model, effectiveSystemPrompt) }

                // If model tried native function calling due to tool descriptions in prompt,
                // retry without tool descriptions AND without chat history (history may contain tool context).
                val errMsg = result.exceptionOrNull()?.message ?: ""
                val isToolConfusion = errMsg.contains("UNEXPECTED_TOOL_CALL") || errMsg.contains("MALFORMED_FUNCTION_CALL")
                if (result.isFailure && isToolConfusion) {
                    ZeroClawService.log("LLM: $model confused by tool descriptions — retrying without tools and history")
                    val basePrompt = effectiveSystemPrompt.substringBefore("\n\nYou have access to the following tools").trim()
                        .substringBefore("\n\nIMPORTANT: For any question").trim()
                        .ifBlank { effectiveSystemPrompt }
                    // Use a temp chatId so dispatchToProvider gets empty history (no tool artifacts)
                    result = runCatching { dispatchToProvider(effectiveMessage, entry, "notool_${System.currentTimeMillis()}", model, basePrompt) }
                }

                when {
                    result.isSuccess -> {
                        var reply = result.getOrNull() ?: ""
                        if (reply.isNotBlank()) {
                            ZeroClawService.logDetail("━━━ PASS 1 — MODEL REPLY ━━━")
                            ZeroClawService.logDetail("Reply (${reply.length} chars): ${reply.take(500)}")

                            // ── Offline two-pass: real-time refusal OR known real-time query OR garbage reply → fetch → re-ask ──
                            // Only trigger if user has web tools enabled — respect user's tool settings
                            val enabledForPass2 = toolSystem.enabledTools().map { it.name }.toSet()
                            val hasAnyWebTool = "web_search" in enabledForPass2 || "web_fetch" in enabledForPass2 || "brave_search" in enabledForPass2
                            val needsWebData = entry.safeProvider == "offline" &&
                                hasAnyWebTool &&
                                (isRealTimeRefusal(reply) || isRealTimeQuery(effectiveMessage) || isGarbageOfflineReply(reply, effectiveMessage))
                            if (needsWebData) {
                                val reason = when {
                                    isRealTimeRefusal(reply) -> "refusal"
                                    isGarbageOfflineReply(reply, effectiveMessage) -> "garbage reply"
                                    else -> "real-time query"
                                }
                                ZeroClawService.log("OFFLINE: $reason — building direct reply from web data")
                                ZeroClawService.logDetail("━━━ PASS 2 TRIGGERED ($reason) ━━━")
                                ZeroClawService.logDetail("Pass 1 snippet: ${reply.take(200)}")

                                // Choose Pass 2 strategy based on user setting:
                                // ON  → fetch web data, ask model to SUMMARIZE it (natural replies)
                                // OFF → build formatted reply directly from web data (no model call)
                                val offlineWebSummarize = AppSettings.dataStore(context).data
                                    .map { it[AppSettings.KEY_OFFLINE_WEB_SUMMARIZE] ?: true }.first()

                                val finalReply = if (offlineWebSummarize) {
                                    ZeroClawService.log("OFFLINE: Pass 2 — summarizer mode")
                                    val summPrompt = buildPassTwoSummarizerPrompt(effectiveMessage, toolSystem)
                                    if (summPrompt.isNotBlank()) {
                                        ZeroClawService.logDetail("━━━ PASS 2 SUMMARIZER PROMPT ━━━")
                                        ZeroClawService.logDetail(summPrompt.take(600))
                                        val r = runCatching {
                                            callOffline(summPrompt, model, emptyList(), effectiveSystemPrompt)
                                        }.getOrNull()?.takeIf { it.isNotBlank() }
                                        if (r != null && !isSummarizerRefusal(r)) {
                                            ZeroClawService.log("OFFLINE: ✓ summarizer reply (${r.length} chars)")
                                            ZeroClawService.logDetail("━━━ PASS 2 SUMMARIZER REPLY ━━━\n$r")
                                            r
                                        } else {
                                            val fallbackReason = if (r != null) "summarizer gave refusal" else "summarizer call failed"
                                            ZeroClawService.log("OFFLINE: $fallbackReason — falling back to direct reply")
                                            buildPassTwoDirectReply(effectiveMessage, toolSystem)
                                        }
                                    } else {
                                        // No web data found — direct reply will show the error message
                                        buildPassTwoDirectReply(effectiveMessage, toolSystem)
                                    }
                                } else {
                                    ZeroClawService.log("OFFLINE: Pass 2 — direct reply mode")
                                    buildPassTwoDirectReply(effectiveMessage, toolSystem)
                                }

                                ZeroClawService.logDetail("━━━ PASS 2 FINAL REPLY ━━━\n${finalReply.take(400)}")
                                reply = finalReply
                            }

                            // ── Tool call loop ──────────────────────────────
                            reply = handleToolCalls(reply, entry, chatId, model, effectiveSystemPrompt, toolSystem)

                            // ── Phase 116: Extract answer from thinking mode ──
                            if (thinkingAddition.isNotBlank()) {
                                val thinking = thinkingMode.extractThinking(reply)
                                if (thinking != null) {
                                    ZeroClawService.log("LLM: thinking mode — ${thinking.length} chars of reasoning")
                                }
                                reply = thinkingMode.extractAnswer(reply)
                            }

                            ZeroClawService.log("LLM: ✓ [$mode] reply from key=\"${entry.safeLabel}\" model=\"$model\" ($provider)")
                            ZeroClawService.logDetail("━━━ FINAL REPLY TO USER ━━━")
                            ZeroClawService.logDetail(reply)
                            addToHistory(chatId, "assistant", reply)
                            return reply
                        }
                        ZeroClawService.log("LLM: ⚠ [$mode] blank reply from model=\"$model\" — trying next")
                    }
                    else -> {
                        val ex = result.exceptionOrNull()
                        val msg = ex?.message ?: "unknown error"

                        // Friendly log messages the user can understand
                        val friendlyMsg = when {
                            msg.contains("429") || msg.lowercase().contains("quota") || msg.lowercase().contains("rate") || msg.lowercase().contains("exceeded") -> {
                                lastRateLimitMsg = msg
                                rateLimitedModels["${entry.id}:$model"] = System.currentTimeMillis()
                                "⚠ Rate limit hit on $model — trying next model (will skip for 60s)"
                            }
                            msg.contains("MALFORMED_FUNCTION_CALL") || msg.contains("UNEXPECTED_TOOL_CALL") ->
                                "⚠ $model can't handle tool descriptions — trying next model"
                            msg.contains("Developer instruction is not enabled") ->
                                "⚠ $model doesn't support system prompts — trying next"
                            msg.contains("JSON mode is not enabled") ->
                                "⚠ $model doesn't support JSON mode — trying next"
                            msg.contains("Empty response") || msg.contains("no parts") || msg.contains("no text") ->
                                "⚠ $model returned empty response — trying next"
                            msg.contains("safety") || msg.contains("SAFETY") ->
                                "⚠ $model blocked response (safety filter) — trying next"
                            msg.contains("401") || msg.contains("403") ->
                                "✗ API key rejected by $model — check your key"
                            msg.contains("404") ->
                                "✗ Model $model not found — it may be deprecated or unavailable"
                            msg.contains("timeout") || msg.contains("timed out") ->
                                "⚠ $model timed out — trying next"
                            else ->
                                "✗ $model failed: ${msg.take(150)}"
                        }
                        ZeroClawService.log("LLM: $friendlyMsg")
                    }
                }
            }
            if (!keyWorked) {
                keyManager.markFailed(entry.id)
                // Log when transitioning from online to offline fallback
                if (entry.safeProvider != "offline" && onlineKeys.all { keyManager.getFailedKeyIds().contains(it.id) } && offlineKeys.isNotEmpty()) {
                    ZeroClawService.log("LLM: ⚠ All online keys exhausted — falling back to OFFLINE mode")
                }
            }
        }

        val failedCount = keyManager.failedCount()
        val totalCount = allKeys.size
        return if (lastRateLimitMsg.isNotBlank()) {
            "⚠️ All keys hit quota/rate-limits ($failedCount/$totalCount). " +
            "This means your keys ARE valid but you've exceeded the free tier. " +
            "Try again in a minute or add a paid-tier key."
        } else {
            "⚠️ All $failedCount/$totalCount API keys failed (online + offline). " +
            "Check your keys in Settings → Manage API Keys, then restart the service."
        }
        } finally {
            ai.zeroclaw.android.service.ZeroClawService.activityState = "idle"
            ai.zeroclaw.android.service.ZeroClawService.lastActivityDetail = ""
        }
    }

    /**
     * Handle tool calls in LLM response — execute tools and feed results back.
     * Loops up to MAX_TOOL_ROUNDS times for multi-step tool usage.
     */
    private suspend fun handleToolCalls(
        initialReply: String,
        entry: ApiKeyEntry,
        chatId: String,
        model: String,
        systemPrompt: String,
        toolSystem: ToolSystem
    ): String {
        var reply = initialReply
        var round = 0
        val loopDetector = ToolLoopDetector()  // Phase 115

        while (round < ToolSystem.MAX_TOOL_ROUNDS) {
            val toolCalls = toolSystem.parseToolCalls(reply)
            if (toolCalls.isEmpty()) break

            round++
            ZeroClawService.log("TOOL: round $round — ${toolCalls.size} tool call(s) detected")

            // Execute each tool call and collect results
            val results = StringBuilder()
            var loopDetected = false
            for (call in toolCalls) {
                // ── Phase 115: Check for tool loops ──────────────────────
                val argsHash = call.args.hashCode()
                val detection = loopDetector.recordCall(call.name, argsHash, round)
                if (detection != null) {
                    ZeroClawService.log("TOOL: ⚠ ${detection.message}")
                    results.appendLine("[Loop detected: ${detection.message}]")
                    loopDetected = true
                    break
                }

                // Auto-inject user_id for memory tool if not provided by LLM
                val enrichedCall = if (call.name == "memory" && call.args["user_id"].isNullOrBlank()) {
                    call.copy(args = call.args + ("user_id" to chatId))
                } else call
                val result = toolSystem.executeTool(enrichedCall)
                results.appendLine("Tool result for ${call.name}:")
                results.appendLine(if (result.success) result.content else "Error: ${result.error}")
                results.appendLine()

                // ── Phase 115: Check for stalls ──────────────────────────
                val resultHash = results.toString().hashCode()
                val stallDetection = loopDetector.recordResult(resultHash)
                if (stallDetection != null) {
                    ZeroClawService.log("TOOL: ⚠ ${stallDetection.message}")
                    loopDetected = true
                    break
                }
            }

            // ── Phase 115: Break out if loop detected ────────────────
            if (loopDetected) {
                ZeroClawService.log("TOOL: breaking tool loop at round $round (${loopDetector.summary()})")
                // Return the tool results directly — loop means model couldn't summarise them
                reply = "Here's what I found:\n\n${results.toString().take(2000)}"
                break
            }

            // Strip tool_call blocks from the reply to get the text parts
            val textParts = stripToolCallBlocks(reply)

            // Build a follow-up message with the tool results
            val dtFmt = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy 'at' HH:mm z", java.util.Locale.ENGLISH)
            dtFmt.timeZone = java.util.TimeZone.getDefault()
            val deviceDateNow = dtFmt.format(java.util.Date())
            val toolResultMessage = buildString {
                if (textParts.isNotBlank()) appendLine(textParts)
                appendLine("\n[Device clock: $deviceDateNow]")
                appendLine("[Tool Results]")
                append(results.toString().take(6000))
                appendLine("\nIMPORTANT: The device clock above is accurate. Use it as the authoritative date/time. Use the tool results to answer the user's question. Do not call any more tools unless absolutely necessary.")
            }

            // Add tool result as context and call LLM again
            addToHistory(chatId, "assistant", textParts.ifBlank { "(used tools)" })
            addToHistory(chatId, "user", toolResultMessage)

            ZeroClawService.logDetail("TOOL: round $round — calling model with tool results (${results.length} chars)")
            val nextResult = runCatching { dispatchToProvider(toolResultMessage, entry, chatId, model, systemPrompt) }
            if (nextResult.isFailure) {
                val errMsg = nextResult.exceptionOrNull()?.message ?: "unknown"
                ZeroClawService.log("TOOL: ✗ second model call failed — $errMsg")
                ZeroClawService.logDetail("TOOL: second call exception: ${nextResult.exceptionOrNull()?.javaClass?.simpleName}: $errMsg")
                // Model call failed — return the tool results directly so user still gets useful info
                reply = "Here's what I found:\n\n${results.toString().take(2000)}"
                break
            }
            val nextReply = nextResult.getOrNull()
            if (nextReply.isNullOrBlank()) {
                ZeroClawService.log("TOOL: ✗ second model call returned blank — returning tool results directly")
                reply = "Here's what I found:\n\n${results.toString().take(2000)}"
                break
            }
            ZeroClawService.logDetail("TOOL: round $round — model replied (${nextReply.length} chars): ${nextReply.take(200)}")
            reply = nextReply
        }

        // Final safety strip — ensure no tool_call blocks leak to user regardless of loop exit path
        return stripToolCallBlocks(reply)
    }

    /** Remove all tool_call code blocks from a reply string so they never reach the user. */
    private fun stripToolCallBlocks(text: String): String =
        text
            // Fenced: ```tool_call\n{...}\n``` (standard format)
            .replace(Regex("```tool_call[\\s\\S]*?```", RegexOption.DOT_MATCHES_ALL), "")
            // Bare: tool_call\n{...} spanning multiple lines until closing }
            .replace(Regex("tool_call\\s*\\{[^}]*(?:\\{[^}]*\\}[^}]*)*\\}", RegexOption.DOT_MATCHES_ALL), "")
            // Any remaining tool_call keyword on its own line
            .replace(Regex("^\\s*tool_call\\s*$", setOf(RegexOption.MULTILINE)), "")
            .trim()

    // ── Smart auto-tool: pre-execute tools for models that can't call them ────

    /**
     * Detect tool-worthy patterns in the user message and auto-execute them.
     * Returns enriched message with tool results injected so even dumb offline
     * models can answer properly. Covers ALL 11 tools.
     */
    /**
     * Fetch real-time context for offline models: current date/time + web search results.
     * Returns a context string to be embedded in the system prompt (NOT the user message).
     */
    /** Detect when an offline model refused because it thinks it has no real-time access. */
    private fun isRealTimeRefusal(reply: String): Boolean {
        val r = reply.lowercase()
        return r.contains("don't have access to real-time") ||
               r.contains("do not have access to real-time") ||
               r.contains("cannot access real-time") ||
               r.contains("can't access real-time") ||
               r.contains("no access to real-time") ||
               r.contains("don't have real-time") ||
               r.contains("do not have real-time") ||
               r.contains("cannot provide real-time") ||
               r.contains("can't provide real-time") ||
               r.contains("don't have access to the internet") ||
               r.contains("do not have access to the internet") ||
               r.contains("cannot access the internet") ||
               r.contains("no internet access") ||
               r.contains("i'm not able to browse") ||
               r.contains("i am not able to browse") ||
               r.contains("unable to browse") ||
               r.contains("cannot browse") ||
               r.contains("can't browse") ||
               r.contains("my knowledge cutoff") ||
               r.contains("my training data") ||
               r.contains("as of my last update") ||
               r.contains("as of my knowledge") ||
               r.contains("i don't have specific") ||
               r.contains("i do not have specific") ||
               r.contains("cannot provide a list") ||
               r.contains("can't provide a list") ||
               r.contains("don't have information about") ||
               r.contains("do not have information about") ||
               r.contains("no information available") ||
               r.contains("not aware of any") ||
               r.contains("my information may be") ||
               r.contains("information may be outdated") ||
               r.contains("i cannot confirm") ||
               r.contains("unable to confirm")
    }

    /**
     * Detect when the Pass 2 summarizer gives a useless "can't answer from context" reply
     * instead of actually summarizing the fetched data. Small models do this frequently.
     */
    private fun isSummarizerRefusal(reply: String): Boolean {
        val r = reply.lowercase()
        return r.contains("not possible to answer") ||
               r.contains("doesn't mention") || r.contains("does not mention") ||
               r.contains("not mentioned in") || r.contains("no mention of") ||
               r.contains("doesn't contain") || r.contains("does not contain") ||
               r.contains("not contain any") || r.contains("doesn't provide") ||
               r.contains("does not provide") || r.contains("no information") ||
               r.contains("not enough information") || r.contains("insufficient information") ||
               r.contains("cannot determine") || r.contains("can't determine") ||
               r.contains("cannot answer") || r.contains("can't answer") ||
               r.contains("unable to answer") || r.contains("unable to determine") ||
               r.contains("not able to answer") || r.contains("not able to determine") ||
               r.contains("provided context") || r.contains("provided text") ||
               r.contains("given text") || r.contains("given context") ||
               r.contains("above text") || r.contains("above data") ||
               r.contains("available in the text") || r.contains("information in the text") ||
               r.contains("has not occurred") || r.contains("not occurred yet") ||
               r.contains("has not happened") || r.contains("not happened yet") ||
               r.contains("no evidence") || r.contains("no indication") ||
               (r.contains("based on") && r.contains("the text") && (r.contains("not") || r.contains("no "))) ||
               (r.contains("i don't") && r.contains("information")) ||
               (r.contains("i do not") && r.contains("information"))
    }

    /**
     * Detect garbage/hallucinated offline model replies — random URLs, irrelevant content,
     * or replies that share zero keywords with the user's query. Small models like Gemma 2B
     * frequently hallucinate URL fragments or unrelated text from their training data.
     */
    private fun isGarbageOfflineReply(reply: String, userMessage: String): Boolean {
        val r = reply.trim()
        // Reply is predominantly a URL or URL path (hallucinated link)
        if (r.startsWith("/") || r.startsWith("http")) {
            val urlRatio = r.count { it == '/' || it == '-' || it == '.' || it == ':' }.toFloat() / r.length.coerceAtLeast(1)
            if (urlRatio > 0.15f) return true
        }
        // Reply contains mostly URL-like paths (model spat out a link mid-response)
        val lines = r.lines().filter { it.isNotBlank() }
        if (lines.size <= 2) {
            val urlLines = lines.count { line ->
                line.trim().let { it.startsWith("http") || it.startsWith("/") || it.startsWith("www.") }
            }
            if (urlLines > 0 && urlLines >= lines.size / 2 + 1) return true
        }
        // Very short reply with no overlap with query keywords → likely hallucination
        if (r.length < 200) {
            val queryWords = userMessage.lowercase().split(Regex("\\W+"))
                .filter { it.length > 3 }
                .toSet()
            val replyWords = r.lowercase().split(Regex("\\W+"))
                .filter { it.length > 3 }
                .toSet()
            val overlap = queryWords.intersect(replyWords)
            if (queryWords.size >= 3 && overlap.isEmpty()) return true
        }
        return false
    }

    /** Detect queries that need real-time web data regardless of Pass 1 answer. */
    private fun isRealTimeQuery(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("latest") || m.contains("current") ||
               m.contains("today") || m.contains("right now") ||
               m.contains("this week") || m.contains("this month") ||
               m.contains("streaming") || m.contains("realtime") ||
               m.contains("real-time") || m.contains("live ") ||
               m.contains("breaking") || m.contains("news") ||
               m.contains("price of") || m.contains("stock price") ||
               m.contains("weather") || m.contains("score") ||
               m.contains("happening") || m.contains("right now") ||
               m.contains("airing") || m.contains("episode") ||
               m.contains("release") || m.contains("trending")
    }

    /**
     * Pass 2: Build the reply DIRECTLY from web data — no model call.
     *
     * Small models (Gemma 2B INT4) are so strongly trained to refuse real-time queries
     * that they override injected context every time. Formatting the reply ourselves
     * from the actual web data gives 100% accurate, useful responses every single time.
     */
    private suspend fun buildPassTwoDirectReply(userMessage: String, toolSystem: ToolSystem): String {
        val now = java.util.Date()
        val fmt = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy 'at' HH:mm z", java.util.Locale.ENGLISH)
        fmt.timeZone = java.util.TimeZone.getDefault()
        val currentDateTime = fmt.format(now)

        // Pure date/time query — answer directly, no web search needed
        if (isDateTimeQuery(userMessage)) {
            ZeroClawService.logDetail("Pass 2: date/time query — direct answer")
            return "Today is $currentDateTime."
        }

        // Clean query for search engines
        val rawQuery = userMessage.take(200)
        val cleanQuery = rawQuery
            .replace(Regex("[?!]"), "")
            .replace(Regex("^(is|are|was|were|did|do|does|can|will|has|have|what|who|when|where|why|how)\\s+", RegexOption.IGNORE_CASE), "")
            .trim().ifBlank { rawQuery }

        ZeroClawService.log("OFFLINE-2P: searching \"${cleanQuery.take(60)}\"")

        // Web search with retry
        var searchResult = toolSystem.executeToolDirect(
            ToolCall("2p_search", "web_search", mapOf("query" to cleanQuery))
        )
        if (!searchResult.success || searchResult.content.startsWith("No results")) {
            val kw = cleanQuery.split(" ").take(5).joinToString(" ")
            ZeroClawService.logDetail("Retry with keywords: $kw")
            searchResult = toolSystem.executeToolDirect(
                ToolCall("2p_retry", "web_search", mapOf("query" to kw))
            )
        }

        // BraveSearch fallback
        if (!searchResult.success || searchResult.content.isBlank() || searchResult.content.startsWith("No results")) {
            val braveKey = AppSettings.dataStore(context).data
                .map { it[AppSettings.KEY_BRAVE_API_KEY] ?: "" }.first()
            if (braveKey.isNotBlank()) {
                ZeroClawService.log("OFFLINE-2P: DDG failed — trying BraveSearch")
                val braveResult = toolSystem.executeToolDirect(
                    ToolCall("2p_brave", "brave_search", mapOf("query" to cleanQuery, "api_key" to braveKey))
                )
                if (braveResult.success && braveResult.content.isNotBlank()) {
                    ZeroClawService.log("OFFLINE-2P: ✓ BraveSearch")
                    searchResult = braveResult
                }
            }
        }

        if (!searchResult.success || searchResult.content.isBlank()) {
            return "I searched for \"$userMessage\" but couldn't reach the web right now. Please check your internet connection and try again."
        }

        ZeroClawService.log("OFFLINE-2P: ✓ web_search ${searchResult.content.length} chars")
        ZeroClawService.logDetail("Search result:\n${searchResult.content.take(500)}")

        // Parse search results into structured items
        data class SearchItem(val num: Int, val title: String, val snippet: String, val url: String)
        val items = mutableListOf<SearchItem>()
        val lines = searchResult.content.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val numMatch = Regex("^(\\d+)\\.\\s+(.+)$").find(line)
            if (numMatch != null) {
                val num = numMatch.groupValues[1].toIntOrNull() ?: 0
                val title = numMatch.groupValues[2].trim()
                var snippet = ""
                var url = ""
                if (i + 1 < lines.size && !lines[i + 1].trim().startsWith("URL:") && lines[i + 1].trim().isNotBlank()) {
                    snippet = lines[i + 1].trim(); i++
                }
                if (i + 1 < lines.size && lines[i + 1].trim().startsWith("URL:")) {
                    url = lines[i + 1].trim().removePrefix("URL:").trim(); i++
                }
                if (title.isNotBlank()) items.add(SearchItem(num, title, snippet, url))
            }
            i++
        }

        // Fetch top URL for richer content
        var fetchContent = ""
        val topUrl = items.firstOrNull { it.url.isNotBlank() }?.url
        if (topUrl != null) {
            ZeroClawService.log("OFFLINE-2P: fetching $topUrl")
            val fetchResult = toolSystem.executeToolDirect(
                ToolCall("2p_fetch", "web_fetch", mapOf("url" to topUrl))
            )
            if (fetchResult.success && fetchResult.content.isNotBlank()) {
                ZeroClawService.log("OFFLINE-2P: ✓ web_fetch ${fetchResult.content.length} chars")
                fetchContent = fetchResult.content.lines()
                    .map { it.trim() }
                    .filter { it.length > 25 && !it.startsWith("http") && !it.startsWith("[") && !it.startsWith("|") }
                    .take(5)
                    .joinToString("\n")
                    .take(450)
                ZeroClawService.logDetail("Fetch excerpt: $fetchContent")
            } else {
                ZeroClawService.logDetail("web_fetch failed: ${fetchResult.error}")
            }
        }

        // Build the direct, formatted reply
        return buildString {
            appendLine("🔍 Here's what I found (${currentDateTime}):")
            appendLine()
            if (fetchContent.isNotBlank()) {
                appendLine(fetchContent)
                appendLine()
            }
            if (items.isNotEmpty()) {
                items.take(5).forEach { item ->
                    append("${item.num}. **${item.title}**")
                    if (item.snippet.isNotBlank()) append("\n   ${item.snippet.take(100)}")
                    if (item.url.isNotBlank()) append("\n   ${item.url}")
                    appendLine()
                }
            } else {
                appendLine(searchResult.content.take(800))
            }
        }.trim()
    }

    /**
     * Pass 2 summarizer mode: fetch web data and return a prompt that asks the model
     * to SUMMARIZE the fetched text — not to answer a real-time question.
     * Small models handle "summarize this text" far better than "answer from real-time data".
     * Returns empty string if web fetch completely failed (caller falls back to direct reply).
     */
    private suspend fun buildPassTwoSummarizerPrompt(userMessage: String, toolSystem: ToolSystem): String {
        val now = java.util.Date()
        val fmt = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy 'at' HH:mm z", java.util.Locale.ENGLISH)
        fmt.timeZone = java.util.TimeZone.getDefault()
        val currentDateTime = fmt.format(now)

        // Date-only query — no web fetch needed, frame as simple fact
        if (isDateTimeQuery(userMessage)) {
            return "The current date and time is: $currentDateTime\n\nPlease tell the user today's date in a friendly way."
        }

        // Clean query
        val rawQuery = userMessage.take(200)
        val cleanQuery = rawQuery
            .replace(Regex("[?!]"), "")
            .replace(Regex("^(is|are|was|were|did|do|does|can|will|has|have|what|who|when|where|why|how)\\s+", RegexOption.IGNORE_CASE), "")
            .trim().ifBlank { rawQuery }

        ZeroClawService.log("OFFLINE-SUM: searching \"${cleanQuery.take(60)}\"")

        // Web search with retry
        var searchResult = toolSystem.executeToolDirect(
            ToolCall("sum_search", "web_search", mapOf("query" to cleanQuery))
        )
        if (!searchResult.success || searchResult.content.startsWith("No results")) {
            val kw = cleanQuery.split(" ").take(5).joinToString(" ")
            searchResult = toolSystem.executeToolDirect(
                ToolCall("sum_retry", "web_search", mapOf("query" to kw))
            )
        }
        // BraveSearch fallback
        if (!searchResult.success || searchResult.content.isBlank() || searchResult.content.startsWith("No results")) {
            val braveKey = AppSettings.dataStore(context).data
                .map { it[AppSettings.KEY_BRAVE_API_KEY] ?: "" }.first()
            if (braveKey.isNotBlank()) {
                val br = toolSystem.executeToolDirect(
                    ToolCall("sum_brave", "brave_search", mapOf("query" to cleanQuery, "api_key" to braveKey))
                )
                if (br.success && br.content.isNotBlank()) searchResult = br
            }
        }

        if (!searchResult.success || searchResult.content.isBlank()) return ""

        ZeroClawService.log("OFFLINE-SUM: ✓ search ${searchResult.content.length} chars")

        // Try to fetch top URL for richer content
        val topUrl = Regex("""URL:\s*(https?://\S+)""").find(searchResult.content)?.groupValues?.get(1)
        var fetchText = ""
        if (topUrl != null) {
            val fr = toolSystem.executeToolDirect(
                ToolCall("sum_fetch", "web_fetch", mapOf("url" to topUrl))
            )
            if (fr.success && fr.content.isNotBlank()) {
                ZeroClawService.log("OFFLINE-SUM: ✓ fetch ${fr.content.length} chars")
                fetchText = fr.content.lines()
                    .map { it.trim() }
                    .filter { it.length > 20 }
                    .take(10)
                    .joinToString("\n")
                    .take(400)
            }
        }

        // Build the text to give the model for summarization
        // Total budget for offline: ~1290 chars. Prompt template ≈ 300 chars.
        // Leave ~900 chars for data (split between fetch + search).
        val dataForModel = buildString {
            appendLine("Date: $currentDateTime")
            appendLine()
            if (fetchText.isNotBlank()) {
                appendLine("Web page content:")
                appendLine(fetchText.take(400))
                appendLine()
            }
            appendLine("Search results:")
            appendLine(searchResult.content.take(400))
        }.trim()

        ZeroClawService.logDetail("OFFLINE-SUM: data for model (${dataForModel.length} chars):\n${dataForModel.take(400)}")

        // Frame as summarization task — model does NOT need real-time access, just needs to summarize given text.
        // Use simple, direct language that small models (Gemma 2B) can follow reliably.
        return buildString {
            appendLine("Below is real data I collected from the web. Read it carefully and answer the question using ONLY this data. Do NOT say the data is missing or insufficient — the answer IS in the data below.")
            appendLine()
            appendLine(dataForModel)
            appendLine()
            append("Question: $userMessage\n\nAnswer using the data above:")
        }
    }

    /** Returns true for short queries that just ask the current date or time. */
    private fun isDateTimeQuery(message: String): Boolean {
        val m = message.lowercase().trim()
        return m.length < 55 &&
            (m.contains("today") || m.contains("what day") || m.contains("what date") ||
             m.contains("today's date") || m.contains("current date") || m.contains("what time")) &&
            !m.contains("anime") && !m.contains("movie") && !m.contains("episode") &&
            !m.contains("news") && !m.contains("price") && !m.contains("war") &&
            !m.contains("score") && !m.contains("weather") && !m.contains("release") &&
            !m.contains("schedule") && !m.contains("event")
    }

    private suspend fun fetchOfflineContext(userMessage: String, toolSystem: ToolSystem): String {
        val now = java.util.Date()
        val fmt = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy 'at' HH:mm:ss z", java.util.Locale.ENGLISH)
        fmt.timeZone = java.util.TimeZone.getDefault()
        val currentDateTime = fmt.format(now)

        val query = userMessage.take(200)
        val webSearchEnabled = toolSystem.isEnabled("web_search")
        ZeroClawService.log("OFFLINE-CTX: date=$currentDateTime, web_search enabled=$webSearchEnabled")

        return buildString {
            appendLine("Today is $currentDateTime.")

            if (webSearchEnabled) {
                // Step 1: web search
                ZeroClawService.log("OFFLINE-CTX: searching \"${query.take(60)}\"")
                val searchResult = toolSystem.executeTool(
                    ToolCall("auto_search", "web_search", mapOf("query" to query))
                )

                if (searchResult.success && searchResult.content.isNotBlank()) {
                    ZeroClawService.log("OFFLINE-CTX: ✓ web_search ${searchResult.content.length} chars")
                    appendLine("Web search results:\n${searchResult.content.take(1500)}")

                    // Step 2: web_fetch the first URL from search results for deeper content
                    val firstUrl = Regex("""URL:\s*(https?://\S+)""").find(searchResult.content)?.groupValues?.get(1)
                    if (firstUrl != null && toolSystem.isEnabled("web_fetch")) {
                        ZeroClawService.log("OFFLINE-CTX: fetching top result: $firstUrl")
                        val fetchResult = toolSystem.executeTool(
                            ToolCall("auto_fetch", "web_fetch", mapOf("url" to firstUrl))
                        )
                        if (fetchResult.success && fetchResult.content.isNotBlank()) {
                            ZeroClawService.log("OFFLINE-CTX: ✓ web_fetch ${fetchResult.content.length} chars")
                            appendLine("\nTop result full content:\n${fetchResult.content.take(1500)}")
                        }
                    }
                } else {
                    ZeroClawService.log("OFFLINE-CTX: web_search failed — ${searchResult.error}")
                }
            } else {
                ZeroClawService.log("OFFLINE-CTX: web_search disabled — date only")
            }
        }.trim()
    }

    /**
     * Build system prompt for offline models. Real-time context is embedded here
     * so the model treats it as ground truth, not as conversation history.
     */
    private fun buildOfflineSystemPrompt(base: String, context: String?, thinking: String): String {
        val sb = StringBuilder(base)
        if (!context.isNullOrBlank()) {
            sb.append("\n\n--- Real-time context (use this to answer the user) ---\n")
            sb.append(context)
            sb.append("\n--- End context ---")
        }
        sb.append("\n\nAnswer the user's question directly and concisely using the context above. Do NOT say you lack real-time access or internet — all needed data is provided above.")
        if (thinking.isNotBlank()) sb.append(thinking)
        return sb.toString()
    }

    private suspend fun autoToolEnrich(
        userMessage: String,
        chatId: String,
        toolSystem: ToolSystem
    ): String {
        val msg = userMessage.lowercase().trim()
        val toolCalls = mutableListOf<ToolCall>()

        // ── 1. Weather ──────────────────────────────────────────────────────
        val weatherKeywords = listOf("weather", "temperature", "forecast", "rain", "raining",
            "snow", "snowing", "cold", "hot", "warm", "humid", "humidity", "wind", "windy",
            "sunny", "cloudy", "storm", "climate", "degrees", "celsius", "fahrenheit")
        val hasWeatherKeyword = weatherKeywords.any { msg.contains(it) }
        val isCurrentQuery = msg.startsWith("current ") && msg.length > 10

        if (hasWeatherKeyword || isCurrentQuery) {
            val weatherPatterns = listOf(
                Regex("^current\\s+(?:weather\\s+(?:in|at|for|of)\\s+)?(.+)", RegexOption.IGNORE_CASE),
                Regex("(?:what(?:'s| is) the )?weather\\s+(?:in|at|for|of|)\\s*(.+)", RegexOption.IGNORE_CASE),
                Regex("(?:temperature|forecast|weather report)\\s+(?:in|at|for|of|)\\s*(.+)", RegexOption.IGNORE_CASE),
                Regex("(?:how(?:'s| is) the )?weather\\s+(?:in|at|for|of)\\s+(.+)", RegexOption.IGNORE_CASE),
                Regex("is it (?:raining|cold|hot|warm|snowing|sunny|cloudy|windy)\\s+(?:in|at)\\s+(.+)", RegexOption.IGNORE_CASE),
                Regex("(?:rain|snow|storm|sun|clouds?)\\s+(?:in|at|for)\\s+(.+)", RegexOption.IGNORE_CASE),
                Regex("(?:weather|temperature|forecast|current)\\s+(.{2,})", RegexOption.IGNORE_CASE)
            )
            for (pattern in weatherPatterns) {
                val match = pattern.find(userMessage)
                if (match != null) {
                    var location = match.groupValues[1].trim()
                        .removeSuffix("?").removeSuffix(".").removeSuffix("!").trim()
                    location = location.replace(Regex("\\s+(weather|temperature|forecast|today|now|right now|currently)$", RegexOption.IGNORE_CASE), "").trim()
                    if (location.isNotBlank() && location.length >= 2) {
                        val action = if (msg.contains("forecast") || msg.contains("3 day") || msg.contains("week")) "forecast" else "current"
                        toolCalls.add(ToolCall("auto_weather", "weather", mapOf("location" to location, "action" to action)))
                        break
                    }
                }
            }
        }

        // ── 2. Summarize (text, URL, or conversation history) ─────────────
        val wantsSummary = msg.contains("summarize") || msg.contains("summary") || msg.contains("summarise") ||
                msg.contains("key points") || msg.contains("tldr") || msg.contains("tl;dr") ||
                msg.contains("condense") || msg.contains("shorten")
        // Check if user wants to summarize the chat/conversation itself
        val wantsChatSummary = wantsSummary && (
                msg.contains("chat") || msg.contains("conversation") || msg.contains("above") ||
                msg.contains("last message") || msg.contains("previous") || msg.contains("this") ||
                msg.contains("it") || msg.matches(Regex("^(summarize|summarise|summary|tldr|tl;dr|key points)\\s*$")))
        if (toolCalls.isEmpty() && wantsSummary) {
            val urlInMsg = Regex("(https?://[^\\s]+)", RegexOption.IGNORE_CASE).find(userMessage)
            if (urlInMsg != null) {
                val url = urlInMsg.groupValues[1].removeSuffix(",").removeSuffix(")").removeSuffix(".")
                toolCalls.add(ToolCall("auto_summarize", "summarize", mapOf("text" to url)))
            } else if (wantsChatSummary) {
                // Summarize conversation history — grab recent messages for this chat
                val history = getHistory(chatId)
                if (history.size >= 2) {
                    // Collect last few messages (skip the current "summarize" message)
                    val recentChat = history.dropLast(1).takeLast(20).joinToString("\n") { m ->
                        "${m.role.replaceFirstChar { it.uppercase() }}: ${m.content}"
                    }
                    if (toolSystem.isEnabled("summarize")) {
                        toolCalls.add(ToolCall("auto_summarize", "summarize",
                            mapOf("text" to recentChat, "sentences" to "5")))
                    } else {
                        // No summarize tool — inject history so model can summarize directly
                        return "$userMessage\n\n[Conversation history to summarize:]\n${recentChat.take(4000)}"
                    }
                }
            } else {
                // Summarize the raw text after the keyword
                val textMatch = Regex("(?:summarize|summarise|summary of|key points of|tldr|tl;dr|condense|shorten)\\s*:?\\s*(.{20,})", RegexOption.IGNORE_CASE).find(userMessage)
                if (textMatch != null) {
                    toolCalls.add(ToolCall("auto_summarize", "summarize", mapOf("text" to textMatch.groupValues[1].trim())))
                }
            }
        }

        // ── 3. Translate ────────────────────────────────────────────────────
        if (toolCalls.isEmpty()) {
            val translatePatterns = listOf(
                // "translate X to Y"
                Regex("translate\\s+['\"]?(.+?)['\"]?\\s+(?:to|into|in)\\s+(\\w+)", RegexOption.IGNORE_CASE),
                // "X in spanish", "hello in hindi"
                Regex("['\"]?(.+?)['\"]?\\s+in\\s+(spanish|french|german|japanese|chinese|korean|hindi|telugu|tamil|arabic|russian|portuguese|italian|dutch|swedish|turkish|thai|vietnamese|indonesian|kannada|malayalam|marathi|gujarati|bengali|punjabi|urdu|persian|hebrew|greek|polish|romanian|hungarian|finnish|danish|norwegian|filipino|swahili|czech|ukrainian|serbian|croatian|slovak|slovenian|estonian|latvian|lithuanian|georgian|albanian|catalan|afrikaans|bulgarian|malay)", RegexOption.IGNORE_CASE),
                // "how do you say X in Y"
                Regex("how (?:do you |to )?say\\s+['\"]?(.+?)['\"]?\\s+in\\s+(\\w+)", RegexOption.IGNORE_CASE),
                // "what is X in Y"
                Regex("what(?:'s| is)\\s+['\"]?(.+?)['\"]?\\s+in\\s+(\\w+)\\s*\\??", RegexOption.IGNORE_CASE)
            )
            val needsTranslate = msg.contains("translate") || msg.contains("translation") ||
                    msg.contains("how do you say") || msg.contains("how to say") ||
                    msg.contains("in spanish") || msg.contains("in french") || msg.contains("in german") ||
                    msg.contains("in hindi") || msg.contains("in telugu") || msg.contains("in japanese") ||
                    msg.contains("in chinese") || msg.contains("in korean") || msg.contains("in arabic") ||
                    msg.contains("in tamil") || msg.contains("in russian") || msg.contains("in portuguese")
            if (needsTranslate) {
                for (pattern in translatePatterns) {
                    val match = pattern.find(userMessage)
                    if (match != null) {
                        val text = match.groupValues[1].trim().removeSuffix("?").removeSuffix(".").trim()
                        val targetLang = match.groupValues[2].trim()
                        if (text.isNotBlank() && text.length >= 1 && targetLang.isNotBlank()) {
                            toolCalls.add(ToolCall("auto_translate", "translate", mapOf(
                                "text" to text,
                                "to" to targetLang
                            )))
                            break
                        }
                    }
                }
            }
        }

        // ── 4. Image Generation ─────────────────────────────────────────────
        if (toolCalls.isEmpty()) {
            val imageGenKeywords = msg.contains("generate") || msg.contains("create") || msg.contains("draw") ||
                    msg.contains("make") || msg.contains("design") || msg.contains("paint")
            val imageNouns = msg.contains("image") || msg.contains("picture") || msg.contains("photo") ||
                    msg.contains("illustration") || msg.contains("art") || msg.contains("artwork") ||
                    msg.contains("wallpaper") || msg.contains("poster") || msg.contains("logo")
            if (imageGenKeywords && imageNouns) {
                val prompt = userMessage
                    .replace(Regex("(?:generate|create|draw|make|design|paint)\\s+(?:an?|the|me)?\\s*(?:image|picture|photo|illustration|art|artwork|wallpaper|poster|logo)\\s*(?:of|about|showing|with|for)?\\s*", RegexOption.IGNORE_CASE), "")
                    .trim().ifBlank { userMessage }
                val size = when {
                    msg.contains("wide") || msg.contains("landscape") || msg.contains("horizontal") -> "wide"
                    msg.contains("tall") || msg.contains("portrait") || msg.contains("vertical") -> "tall"
                    else -> "square"
                }
                toolCalls.add(ToolCall("auto_imagegen", "image_gen", mapOf("prompt" to prompt, "size" to size)))
            }
        }

        // ── 5. Speech-to-Text ────────────────────────────────────────────────
        if (toolCalls.isEmpty() && (msg.contains("transcribe") || msg.contains("transcription") ||
                    msg.contains("speech to text") || msg.contains("voice to text") ||
                    msg.contains("what does this audio say") || msg.contains("convert audio"))) {
            val audioMatch = Regex("((?:https?://)?\\S+\\.(?:mp3|wav|m4a|ogg|webm|flac|mp4))", RegexOption.IGNORE_CASE).find(userMessage)
            if (audioMatch != null) {
                toolCalls.add(ToolCall("auto_stt", "speech_to_text", mapOf("source" to audioMatch.groupValues[1])))
            }
        }

        // ── 6. Text-to-Speech ────────────────────────────────────────────────
        if (toolCalls.isEmpty() && (msg.contains("read aloud") || msg.contains("speak this") ||
                    msg.contains("say this") || msg.contains("read this out") ||
                    msg.contains("text to speech") || msg.contains("tts"))) {
            val textMatch = Regex("(?:read aloud|speak this|say this|read this out|tts|text to speech)\\s*:?\\s*(.{3,})", RegexOption.IGNORE_CASE).find(userMessage)
            if (textMatch != null) {
                val ttsText = textMatch.groupValues[1].trim()
                val lang = when {
                    msg.contains("in spanish") || msg.contains("in es") -> "es"
                    msg.contains("in french") || msg.contains("in fr") -> "fr"
                    msg.contains("in hindi") || msg.contains("in hi") -> "hi"
                    msg.contains("in japanese") || msg.contains("in ja") -> "ja"
                    msg.contains("in german") || msg.contains("in de") -> "de"
                    msg.contains("in telugu") || msg.contains("in te") -> "te"
                    else -> "en"
                }
                toolCalls.add(ToolCall("auto_tts", "text_to_speech", mapOf("text" to ttsText, "language" to lang)))
            } else if (msg == "tts" || msg == "text to speech" || msg.contains("available voices") || msg.contains("what voices")) {
                toolCalls.add(ToolCall("auto_tts", "text_to_speech", mapOf("text" to "", "action" to "voices")))
            }
        }

        // ── 7. Calendar ─────────────────────────────────────────────────────
        if (toolCalls.isEmpty()) {
            val calendarKeywords = msg.contains("calendar") || msg.contains("schedule") ||
                    msg.contains("appointment") || msg.contains("meeting") ||
                    msg.contains("event") || msg.contains("agenda") ||
                    msg.contains("what's on") || msg.contains("what is on") ||
                    msg.contains("my day") || msg.contains("free time")
            if (calendarKeywords) {
                val calendarPatterns = listOf(
                    // "create/add/schedule event/meeting/appointment ..."
                    Regex("(?:create|add|schedule|set up|book|make)\\s+(?:an? )?(?:event|meeting|appointment|reminder)\\s+(?:called |named |titled )?(.+)", RegexOption.IGNORE_CASE),
                    // "what's on my calendar/schedule"
                    Regex("(?:what(?:'s| is) on|show|check)\\s+(?:my )?(?:calendar|schedule|agenda)(?:\\s+(?:for|on|this)\\s+(.+))?", RegexOption.IGNORE_CASE),
                    // "my schedule/calendar today/this week/tomorrow"
                    Regex("(?:my )(?:schedule|calendar|agenda|events?)(?:\\s+(?:for|on))?\\s+(today|tomorrow|this week|next week)", RegexOption.IGNORE_CASE),
                    // "search calendar for X"
                    Regex("(?:search|find|look for)\\s+(?:in )?(?:my )?(?:calendar|events?)\\s+(?:for )?(.+)", RegexOption.IGNORE_CASE),
                    // "do I have any meetings/events ..."
                    Regex("do I have (?:any )?(?:meetings?|events?|appointments?)\\s*(.+)?", RegexOption.IGNORE_CASE)
                )

                for (pattern in calendarPatterns) {
                    val match = pattern.find(userMessage)
                    if (match != null) {
                        val detail = match.groupValues[1].trim().removeSuffix("?").removeSuffix(".").trim()
                        // Determine action
                        val isCreate = msg.contains("create") || msg.contains("add") || msg.contains("schedule") ||
                                msg.contains("set up") || msg.contains("book")
                        val isSearch = msg.contains("search") || msg.contains("find") || msg.contains("look for")

                        when {
                            isCreate && detail.isNotBlank() -> {
                                toolCalls.add(ToolCall("auto_calendar", "calendar", mapOf("action" to "create", "title" to detail)))
                            }
                            isSearch && detail.isNotBlank() -> {
                                toolCalls.add(ToolCall("auto_calendar", "calendar", mapOf("action" to "search", "query" to detail)))
                            }
                            detail.contains("week", ignoreCase = true) -> {
                                toolCalls.add(ToolCall("auto_calendar", "calendar", mapOf("action" to "week")))
                            }
                            else -> {
                                toolCalls.add(ToolCall("auto_calendar", "calendar", mapOf("action" to "today")))
                            }
                        }
                        break
                    }
                }

                // Fallback: if keywords matched but no pattern, just show today
                if (toolCalls.isEmpty() && calendarKeywords) {
                    toolCalls.add(ToolCall("auto_calendar", "calendar", mapOf("action" to "today")))
                }
            }
        }

        // ── 8. Contacts ─────────────────────────────────────────────────────
        if (toolCalls.isEmpty()) {
            val contactsKeywords = msg.contains("contact") || msg.contains("phone number") ||
                    msg.contains("phone book") || msg.contains("address book") ||
                    msg.contains("who is") && (msg.contains("number") || msg.contains("email") || msg.contains("phone"))
            if (contactsKeywords) {
                val contactPatterns = listOf(
                    Regex("(?:find|search|look up|get)\\s+(?:contact|phone|number|email)\\s+(?:for |of )?(.+)", RegexOption.IGNORE_CASE),
                    Regex("(?:what(?:'s| is))\\s+(.+?)(?:'s)?\\s+(?:phone|number|email|address)", RegexOption.IGNORE_CASE),
                    Regex("(?:contact|phone|number|email)\\s+(?:for|of)\\s+(.+)", RegexOption.IGNORE_CASE),
                    Regex("(?:find|search|look up)\\s+(.+?)\\s+(?:in )?(?:my )?contacts?", RegexOption.IGNORE_CASE)
                )
                for (pattern in contactPatterns) {
                    val match = pattern.find(userMessage)
                    if (match != null) {
                        val query = match.groupValues[1].trim().removeSuffix("?").removeSuffix(".").trim()
                        if (query.isNotBlank() && query.length >= 2) {
                            toolCalls.add(ToolCall("auto_contacts", "contacts", mapOf("action" to "search", "query" to query)))
                            break
                        }
                    }
                }
                if (toolCalls.isEmpty() && (msg.contains("list") || msg.contains("show") || msg.contains("all")) && msg.contains("contact")) {
                    toolCalls.add(ToolCall("auto_contacts", "contacts", mapOf("action" to "list")))
                }
            }
        }

        // ── 9. Location ─────────────────────────────────────────────────────
        if (toolCalls.isEmpty()) {
            val locationKeywords = msg.contains("where am i") || msg.contains("my location") ||
                    msg.contains("gps") || msg.contains("current location") ||
                    msg.contains("geocode") || msg.contains("coordinates") ||
                    (msg.contains("near me") || msg.contains("nearby") || msg.contains("close to me")) ||
                    (msg.contains("find") && (msg.contains("restaurant") || msg.contains("hospital") ||
                            msg.contains("pharmacy") || msg.contains("gas station") || msg.contains("atm") ||
                            msg.contains("hotel") || msg.contains("cafe") || msg.contains("store")))
            if (locationKeywords) {
                when {
                    msg.contains("where am i") || msg.contains("my location") || msg.contains("gps") || msg.contains("current location") -> {
                        toolCalls.add(ToolCall("auto_location", "location", mapOf("action" to "current")))
                    }
                    msg.contains("geocode") || (msg.contains("coordinates") && msg.contains("of")) -> {
                        val addrMatch = Regex("(?:geocode|coordinates of|coordinates for)\\s+(.+)", RegexOption.IGNORE_CASE).find(userMessage)
                        if (addrMatch != null) {
                            toolCalls.add(ToolCall("auto_location", "location", mapOf("action" to "geocode", "address" to addrMatch.groupValues[1].trim().removeSuffix("?"))))
                        }
                    }
                    msg.contains("near me") || msg.contains("nearby") || msg.contains("close to me") -> {
                        val nearbyMatch = Regex("(?:find|search|show|list|any)\\s+(.+?)\\s+(?:near|close|around|nearby)", RegexOption.IGNORE_CASE).find(userMessage)
                            ?: Regex("(?:near(?:by)?|close to me)\\s+(.+)", RegexOption.IGNORE_CASE).find(userMessage)
                        val query = nearbyMatch?.groupValues?.get(1)?.trim()?.removeSuffix("?")?.removeSuffix(".")
                            ?: Regex("(restaurants?|hospitals?|pharmacies|gas stations?|atms?|hotels?|cafes?|stores?|banks?|parks?|schools?)").find(msg)?.groupValues?.get(1)
                            ?: "places"
                        toolCalls.add(ToolCall("auto_location", "location", mapOf("action" to "nearby", "query" to query)))
                    }
                    else -> {
                        // Generic "find X" for places
                        val placeMatch = Regex("find\\s+(?:a |the |an )?(.+?)(?:\\s+near| close| around|$)", RegexOption.IGNORE_CASE).find(userMessage)
                        if (placeMatch != null) {
                            toolCalls.add(ToolCall("auto_location", "location", mapOf("action" to "nearby", "query" to placeMatch.groupValues[1].trim())))
                        }
                    }
                }
            }
        }

        // ── 10. Calculator ──────────────────────────────────────────────────
        if (toolCalls.isEmpty()) {
            val calcKeywords = msg.contains("calculate") || msg.contains("compute") || msg.contains("evaluate") ||
                    msg.contains("what is") && msg.contains(Regex("[+\\-*/^%]|\\d+\\s*[×÷]")) ||
                    msg.contains("convert") && (msg.contains(" to ") && Regex("\\d").containsMatchIn(msg)) ||
                    msg.contains("how many") && msg.contains(" in ") && Regex("\\d").containsMatchIn(msg) ||
                    msg.contains("sqrt") || msg.contains("factorial") || msg.contains("sin(") || msg.contains("cos(") || msg.contains("log(")
            if (calcKeywords) {
                // Unit conversion
                val convertMatch = Regex("(?:convert|how many \\w+ in)\\s+([\\d.]+)\\s+(\\w+)\\s+(?:to|in|into)\\s+(\\w+)", RegexOption.IGNORE_CASE).find(userMessage)
                if (convertMatch != null) {
                    toolCalls.add(ToolCall("auto_calc", "calculator", mapOf(
                        "action" to "convert",
                        "value" to convertMatch.groupValues[1],
                        "from" to convertMatch.groupValues[2],
                        "to" to convertMatch.groupValues[3]
                    )))
                } else {
                    // Math expression
                    val exprMatch = Regex("(?:calculate|compute|evaluate|what is|what's)\\s+(.+?)\\s*\\??$", RegexOption.IGNORE_CASE).find(userMessage)
                    if (exprMatch != null) {
                        val expr = exprMatch.groupValues[1].trim()
                        if (expr.isNotBlank() && Regex("[\\d+\\-*/^%().]").containsMatchIn(expr)) {
                            toolCalls.add(ToolCall("auto_calc", "calculator", mapOf("action" to "eval", "expression" to expr)))
                        }
                    }
                }
            }
        }

        // ── 11. RSS Feed ────────────────────────────────────────────────────
        if (toolCalls.isEmpty()) {
            val rssKeywords = msg.contains("rss") || msg.contains("feed") || msg.contains("atom feed") ||
                    msg.contains("blog feed") || msg.contains("news feed") || msg.contains("podcast feed")
            if (rssKeywords) {
                val urlMatch = Regex("(https?://[^\\s]+)", RegexOption.IGNORE_CASE).find(userMessage)
                if (urlMatch != null) {
                    val url = urlMatch.groupValues[1].removeSuffix(",").removeSuffix(")").removeSuffix(".")
                    val action = if (msg.contains("headline") || msg.contains("titles only")) "headlines" else "read"
                    toolCalls.add(ToolCall("auto_rss", "rss_feed", mapOf("url" to url, "action" to action)))
                }
            }
        }

        // ── 12. QR Code ─────────────────────────────────────────────────────
        if (toolCalls.isEmpty()) {
            val qrKeywords = msg.contains("qr code") || msg.contains("qr") && (msg.contains("generate") || msg.contains("create") || msg.contains("make"))
            if (qrKeywords) {
                val qrPatterns = listOf(
                    Regex("(?:generate|create|make)\\s+(?:a )?qr\\s*code\\s+(?:for |with |saying |containing )?(.+)", RegexOption.IGNORE_CASE),
                    Regex("qr\\s*code\\s+(?:for|of|with)\\s+(.+)", RegexOption.IGNORE_CASE)
                )
                for (pattern in qrPatterns) {
                    val match = pattern.find(userMessage)
                    if (match != null) {
                        val text = match.groupValues[1].trim().removeSuffix("?").removeSuffix(".").trim()
                        if (text.isNotBlank()) {
                            toolCalls.add(ToolCall("auto_qr", "qr_code", mapOf("text" to text)))
                            break
                        }
                    }
                }
            }
        }

        // ── 13. File Manager ────────────────────────────────────────────────
        if (toolCalls.isEmpty()) {
            val fileKeywords = msg.contains("list files") || msg.contains("show files") ||
                    msg.contains("read file") || msg.contains("write file") || msg.contains("create file") ||
                    msg.contains("file manager") || msg.contains("search files") || msg.contains("find files") ||
                    (msg.contains("files in") && (msg.contains("download") || msg.contains("document") || msg.contains("internal")))
            if (fileKeywords) {
                val isRead = msg.contains("read")
                val isWrite = msg.contains("write") || msg.contains("create") || msg.contains("save")
                val isSearch = msg.contains("search") || msg.contains("find")
                val isList = msg.contains("list") || msg.contains("show") || msg.contains("what")

                when {
                    isRead -> {
                        val pathMatch = Regex("read\\s+(?:file |the file )?(.+)", RegexOption.IGNORE_CASE).find(userMessage)
                        val path = pathMatch?.groupValues?.get(1)?.trim()?.removeSuffix("?") ?: ""
                        if (path.isNotBlank()) toolCalls.add(ToolCall("auto_file", "file_manager", mapOf("action" to "read", "path" to path)))
                    }
                    isSearch -> {
                        val searchMatch = Regex("(?:search|find)\\s+(?:files? )?(?:for |named |called )?(.+?)(?:\\s+in\\s+(.+))?$", RegexOption.IGNORE_CASE).find(userMessage)
                        if (searchMatch != null) {
                            val query = searchMatch.groupValues[1].trim()
                            val path = searchMatch.groupValues[2].trim().ifBlank { "downloads" }
                            toolCalls.add(ToolCall("auto_file", "file_manager", mapOf("action" to "search", "query" to query, "path" to path)))
                        }
                    }
                    isList -> {
                        val dirMatch = Regex("(?:list|show|what(?:'s| is) in)\\s+(?:files? (?:in )?)?(.+?)\\s*$", RegexOption.IGNORE_CASE).find(userMessage)
                        val path = dirMatch?.groupValues?.get(1)?.trim()?.removeSuffix("?") ?: "internal"
                        toolCalls.add(ToolCall("auto_file", "file_manager", mapOf("action" to "list", "path" to path)))
                    }
                }
            }
        }

        // ── 14. Clipboard ───────────────────────────────────────────────────
        if (toolCalls.isEmpty()) {
            val clipKeywords = msg.contains("clipboard") || msg.contains("copy to clipboard") ||
                    msg.contains("paste") && msg.contains("clipboard") || msg.contains("what's copied") ||
                    msg.contains("clear clipboard")
            if (clipKeywords) {
                when {
                    msg.contains("copy") || msg.contains("write") || msg.contains("set") -> {
                        val textMatch = Regex("(?:copy|write|set)\\s+(?:this )?(?:to )?clipboard\\s*:?\\s*(.+)", RegexOption.IGNORE_CASE).find(userMessage)
                            ?: Regex("(?:copy|clipboard)\\s*:?\\s*(.{3,})", RegexOption.IGNORE_CASE).find(userMessage)
                        if (textMatch != null) {
                            toolCalls.add(ToolCall("auto_clip", "clipboard", mapOf("action" to "write", "text" to textMatch.groupValues[1].trim())))
                        }
                    }
                    msg.contains("clear") -> toolCalls.add(ToolCall("auto_clip", "clipboard", mapOf("action" to "clear")))
                    else -> toolCalls.add(ToolCall("auto_clip", "clipboard", mapOf("action" to "read")))
                }
            }
        }

        // ── 15. Spotify ─────────────────────────────────────────────────────
        if (toolCalls.isEmpty()) {
            val spotifyKeywords = msg.contains("spotify") || (msg.contains("play") && msg.contains("music")) ||
                    msg.contains("now playing") || msg.contains("what's playing") ||
                    msg.contains("skip track") || msg.contains("next track") || msg.contains("pause music")
            if (spotifyKeywords) {
                when {
                    msg.contains("search") -> {
                        val queryMatch = Regex("(?:search|find)\\s+(?:on )?spotify\\s+(?:for )?(.+)", RegexOption.IGNORE_CASE).find(userMessage)
                        if (queryMatch != null) {
                            toolCalls.add(ToolCall("auto_spotify", "spotify", mapOf("action" to "search", "query" to queryMatch.groupValues[1].trim())))
                        }
                    }
                    msg.contains("play") -> {
                        val playMatch = Regex("play\\s+(.+?)(?:\\s+on spotify)?\\s*$", RegexOption.IGNORE_CASE).find(userMessage)
                        if (playMatch != null) {
                            toolCalls.add(ToolCall("auto_spotify", "spotify", mapOf("action" to "play", "query" to playMatch.groupValues[1].trim())))
                        } else {
                            toolCalls.add(ToolCall("auto_spotify", "spotify", mapOf("action" to "play")))
                        }
                    }
                    msg.contains("pause") || msg.contains("stop") -> toolCalls.add(ToolCall("auto_spotify", "spotify", mapOf("action" to "pause")))
                    msg.contains("next") || msg.contains("skip") -> toolCalls.add(ToolCall("auto_spotify", "spotify", mapOf("action" to "next")))
                    msg.contains("previous") || msg.contains("back") -> toolCalls.add(ToolCall("auto_spotify", "spotify", mapOf("action" to "previous")))
                    msg.contains("queue") -> {
                        val queueMatch = Regex("(?:add|queue)\\s+(.+?)(?:\\s+(?:to|on) (?:queue|spotify))?\\s*$", RegexOption.IGNORE_CASE).find(userMessage)
                        if (queueMatch != null) {
                            toolCalls.add(ToolCall("auto_spotify", "spotify", mapOf("action" to "queue", "query" to queueMatch.groupValues[1].trim())))
                        }
                    }
                    else -> toolCalls.add(ToolCall("auto_spotify", "spotify", mapOf("action" to "now_playing")))
                }
            }
        }

        // ── 16. Smart Home ──────────────────────────────────────────────────
        if (toolCalls.isEmpty()) {
            val homeKeywords = msg.contains("hue") || msg.contains("smart light") || msg.contains("smart home") ||
                    (msg.contains("light") && (msg.contains("turn on") || msg.contains("turn off") ||
                            msg.contains("brightness") || msg.contains("color") || msg.contains("dim")))
            if (homeKeywords) {
                when {
                    msg.contains("list") || msg.contains("all lights") || msg.contains("my lights") ->
                        toolCalls.add(ToolCall("auto_home", "smart_home", mapOf("action" to "lights")))
                    msg.contains("turn on") || msg.contains("switch on") -> {
                        val lightMatch = Regex("(?:turn|switch)\\s+on\\s+(?:the )?(.+?)(?:\\s+light)?\\s*$", RegexOption.IGNORE_CASE).find(userMessage)
                        val light = lightMatch?.groupValues?.get(1)?.trim() ?: "1"
                        toolCalls.add(ToolCall("auto_home", "smart_home", mapOf("action" to "on", "light" to light)))
                    }
                    msg.contains("turn off") || msg.contains("switch off") -> {
                        val lightMatch = Regex("(?:turn|switch)\\s+off\\s+(?:the )?(.+?)(?:\\s+light)?\\s*$", RegexOption.IGNORE_CASE).find(userMessage)
                        val light = lightMatch?.groupValues?.get(1)?.trim() ?: "1"
                        toolCalls.add(ToolCall("auto_home", "smart_home", mapOf("action" to "off", "light" to light)))
                    }
                    msg.contains("brightness") || msg.contains("dim") -> {
                        val briMatch = Regex("(?:set |change )?(?:the )?(.+?)\\s+(?:brightness |to )?(\\d+)\\s*%?", RegexOption.IGNORE_CASE).find(userMessage)
                        if (briMatch != null) {
                            toolCalls.add(ToolCall("auto_home", "smart_home", mapOf("action" to "brightness", "light" to briMatch.groupValues[1].trim(), "value" to briMatch.groupValues[2])))
                        }
                    }
                    msg.contains("color") -> {
                        val colorMatch = Regex("(?:set |change )?(?:the )?(.+?)\\s+(?:color |to )(\\w+)", RegexOption.IGNORE_CASE).find(userMessage)
                        if (colorMatch != null) {
                            toolCalls.add(ToolCall("auto_home", "smart_home", mapOf("action" to "color", "light" to colorMatch.groupValues[1].trim(), "value" to colorMatch.groupValues[2])))
                        }
                    }
                    msg.contains("scene") -> {
                        val sceneMatch = Regex("(?:activate|set|use)\\s+(?:the )?(.+?)\\s+scene", RegexOption.IGNORE_CASE).find(userMessage)
                        if (sceneMatch != null) {
                            toolCalls.add(ToolCall("auto_home", "smart_home", mapOf("action" to "scenes", "scene" to sceneMatch.groupValues[1].trim())))
                        } else {
                            toolCalls.add(ToolCall("auto_home", "smart_home", mapOf("action" to "scenes")))
                        }
                    }
                }
            }
        }

        // ── 17. Brave Search ─────────────────────────────────────────────────
        if (toolCalls.isEmpty()) {
            val braveKeywords = msg.contains("brave search") || msg.contains("search brave") ||
                    msg.contains("brave") && (msg.contains("search") || msg.contains("look up") || msg.contains("find"))
            if (braveKeywords) {
                val braveMatch = Regex("(?:brave search|search brave)\\s+(?:for )?(.+)", RegexOption.IGNORE_CASE).find(userMessage)
                    ?: Regex("brave\\s+(?:search|look up|find)\\s+(?:for )?(.+)", RegexOption.IGNORE_CASE).find(userMessage)
                if (braveMatch != null) {
                    val query = braveMatch.groupValues[1].trim().removeSuffix("?").removeSuffix(".")
                    val action = if (msg.contains("news")) "news" else "web"
                    if (query.isNotBlank()) {
                        toolCalls.add(ToolCall("auto_brave", "brave_search", mapOf("query" to query, "action" to action)))
                    }
                }
            }
        }

        // ── 18. Bookmarks ───────────────────────────────────────────────────
        if (toolCalls.isEmpty()) {
            val bookmarkKeywords = msg.contains("bookmark") || msg.contains("bookmarks") ||
                    msg.contains("save this url") || msg.contains("save this link")
            if (bookmarkKeywords) {
                when {
                    msg.contains("save") || msg.contains("add") -> {
                        val urlMatch = Regex("(https?://[^\\s]+)", RegexOption.IGNORE_CASE).find(userMessage)
                        val titleMatch = Regex("(?:as|titled|called|named)\\s+(.+?)(?:\\s+tagged|$)", RegexOption.IGNORE_CASE).find(userMessage)
                        val tagsMatch = Regex("tagged?\\s+(.+)", RegexOption.IGNORE_CASE).find(userMessage)
                        val args = mutableMapOf("action" to "save")
                        if (urlMatch != null) args["url"] = urlMatch.groupValues[1].removeSuffix(",").removeSuffix(")")
                        if (titleMatch != null) args["title"] = titleMatch.groupValues[1].trim()
                        if (tagsMatch != null) args["tags"] = tagsMatch.groupValues[1].trim()
                        if (args.containsKey("url") || args.containsKey("title")) {
                            toolCalls.add(ToolCall("auto_bookmark", "bookmark", args))
                        }
                    }
                    msg.contains("search") || msg.contains("find") -> {
                        val queryMatch = Regex("(?:search|find)\\s+bookmarks?\\s+(?:for )?(.+)", RegexOption.IGNORE_CASE).find(userMessage)
                        if (queryMatch != null) {
                            toolCalls.add(ToolCall("auto_bookmark", "bookmark", mapOf("action" to "search", "query" to queryMatch.groupValues[1].trim().removeSuffix("?"))))
                        }
                    }
                    msg.contains("delete") || msg.contains("remove") -> {
                        val idMatch = Regex("(?:delete|remove)\\s+bookmark\\s+(?:#)?(\\d+)", RegexOption.IGNORE_CASE).find(userMessage)
                        if (idMatch != null) {
                            toolCalls.add(ToolCall("auto_bookmark", "bookmark", mapOf("action" to "delete", "id" to idMatch.groupValues[1])))
                        }
                    }
                    else -> toolCalls.add(ToolCall("auto_bookmark", "bookmark", mapOf("action" to "list")))
                }
            }
        }

        // ── 19. Web Fetch (URL without summary keyword) ──────────────────────
        if (toolCalls.isEmpty()) {
            val urlPattern = Regex("(https?://[^\\s]+)", RegexOption.IGNORE_CASE)
            val urlMatch = urlPattern.find(userMessage)
            if (urlMatch != null) {
                val url = urlMatch.groupValues[1].removeSuffix(",").removeSuffix(")").removeSuffix(".")
                if (url.lowercase().endsWith(".pdf")) {
                    toolCalls.add(ToolCall("auto_pdf", "pdf_read", mapOf("source" to url)))
                } else if (url.matches(Regex(".*\\.(png|jpg|jpeg|gif|webp|bmp)(\\?.*)?$", RegexOption.IGNORE_CASE))) {
                    // Image URL → skip for offline (needs vision model)
                } else {
                    toolCalls.add(ToolCall("auto_fetch", "web_fetch", mapOf("url" to url)))
                }
            }
        }

        // ── 3. Web Search ───────────────────────────────────────────────────
        if (toolCalls.isEmpty()) {
            val searchPatterns = listOf(
                Regex("(?:search|google|look up|find out|search for)\\s+(?:for |about |up )?(.{3,})", RegexOption.IGNORE_CASE),
                Regex("(?:who is|who was|what is|what are|when did|when was|where is|how many|how much|how old)\\s+(.{3,})", RegexOption.IGNORE_CASE),
                Regex("(?:latest|recent|current|today(?:'s)?)\\s+(?:news|updates?|info|results?|scores?)\\s+(?:on |about |for )?(.{3,})", RegexOption.IGNORE_CASE),
                Regex("(?:tell me about|what happened|what's happening|explain)\\s+(?:with |in |at |to )?(.{3,})", RegexOption.IGNORE_CASE)
            )
            val needsSearch = msg.contains("latest") || msg.contains("current") || msg.contains("recent") ||
                    msg.contains("today") || msg.contains("news") || msg.contains("score") ||
                    msg.contains("price") || msg.contains("search") || msg.contains("google") ||
                    msg.contains("look up") || msg.contains("find out") || msg.contains("who is") ||
                    msg.contains("what is") || msg.contains("who was") || msg.contains("when did") ||
                    msg.contains("where is") || msg.contains("how many") || msg.contains("how much") ||
                    msg.contains("tell me about") || msg.contains("what happened") || msg.contains("explain")
            if (needsSearch) {
                for (pattern in searchPatterns) {
                    val match = pattern.find(userMessage)
                    if (match != null) {
                        val query = match.groupValues[1].trim().removeSuffix("?").removeSuffix(".").trim()
                        if (query.length >= 3) {
                            toolCalls.add(ToolCall("auto_search", "web_search", mapOf("query" to query)))
                            break
                        }
                    }
                }
            }
        }

        // ── 4. Memory ───────────────────────────────────────────────────────
        if (toolCalls.isEmpty()) {
            // Store memory
            val storePatterns = listOf(
                Regex("(?:remember|save|store|note)\\s+(?:that\\s+)?(?:my\\s+)?(.{3,})", RegexOption.IGNORE_CASE),
                Regex("(?:my )?(name|birthday|age|email|phone|address|favorite|fav|job|work|hobby)\\s+(?:is|=)\\s+(.+)", RegexOption.IGNORE_CASE)
            )
            if (msg.contains("remember") || msg.contains("save") || msg.contains("store") || msg.contains("note that")) {
                for (pattern in storePatterns) {
                    val match = pattern.find(userMessage)
                    if (match != null) {
                        val content = match.groupValues[match.groupValues.size - 1].trim().removeSuffix("?").removeSuffix(".").trim()
                        if (content.length >= 3) {
                            // Try to extract key=value
                            val kvMatch = Regex("(.+?)\\s+(?:is|=|:)\\s+(.+)", RegexOption.IGNORE_CASE).find(content)
                            if (kvMatch != null) {
                                toolCalls.add(ToolCall("auto_memory", "memory", mapOf(
                                    "action" to "store",
                                    "key" to kvMatch.groupValues[1].trim(),
                                    "value" to kvMatch.groupValues[2].trim()
                                )))
                            } else {
                                toolCalls.add(ToolCall("auto_memory", "memory", mapOf(
                                    "action" to "store",
                                    "key" to "note_${System.currentTimeMillis() / 1000}",
                                    "value" to content
                                )))
                            }
                            break
                        }
                    }
                }
            }
            // Recall memory
            if (toolCalls.isEmpty() && (msg.contains("what do you remember") || msg.contains("what do you know about me") ||
                        msg.contains("recall") || msg.contains("my memories") || msg.contains("what did i tell you"))) {
                toolCalls.add(ToolCall("auto_memory", "memory", mapOf("action" to "recall", "key" to "all")))
            }
            // Forget memory
            if (toolCalls.isEmpty() && (msg.contains("forget") || msg.contains("delete memory") || msg.contains("erase"))) {
                val forgetMatch = Regex("(?:forget|delete|erase)\\s+(?:my\\s+)?(?:memory\\s+(?:of|about)\\s+)?(.+)", RegexOption.IGNORE_CASE).find(userMessage)
                if (forgetMatch != null) {
                    val key = forgetMatch.groupValues[1].trim().removeSuffix("?").removeSuffix(".").trim()
                    if (key.isNotBlank() && key != "me" && key != "everything") {
                        toolCalls.add(ToolCall("auto_memory", "memory", mapOf("action" to "forget", "key" to key)))
                    } else if (key == "everything" || key == "all") {
                        toolCalls.add(ToolCall("auto_memory", "memory", mapOf("action" to "forget", "key" to "all")))
                    }
                }
            }
        }

        // ── 5. PDF Read ─────────────────────────────────────────────────────
        if (toolCalls.isEmpty() && (msg.contains("pdf") || msg.contains(".pdf"))) {
            val pdfMatch = Regex("(?:read|open|extract|summarize|what(?:'s| is) in)\\s+(?:this |the )?(?:pdf\\s+)?(.+\\.pdf)", RegexOption.IGNORE_CASE).find(userMessage)
            if (pdfMatch != null) {
                toolCalls.add(ToolCall("auto_pdf", "pdf_read", mapOf("source" to pdfMatch.groupValues[1].trim())))
            }
        }

        // ── 6. Cron / Scheduled Tasks ───────────────────────────────────────
        if (toolCalls.isEmpty()) {
            // Schedule a task
            if (msg.contains("schedule") || msg.contains("remind me every") || msg.contains("repeat every") ||
                msg.contains("run every") || msg.contains("set alarm") || msg.contains("set reminder")) {
                val intervalMatch = Regex("(?:every|each)\\s+(\\d+)\\s*(min|minute|hour|hr|day|h|m|d)", RegexOption.IGNORE_CASE).find(userMessage)
                if (intervalMatch != null) {
                    val num = intervalMatch.groupValues[1].toIntOrNull() ?: 60
                    val unit = intervalMatch.groupValues[2].lowercase()
                    val minutes = when {
                        unit.startsWith("h") -> num * 60
                        unit.startsWith("d") -> num * 1440
                        else -> num
                    }
                    val prompt = userMessage.replace(Regex("(?:schedule|remind me|repeat|run|set)\\s+", RegexOption.IGNORE_CASE), "")
                        .replace(intervalMatch.value, "").trim()
                    val name = prompt.take(30).replace(Regex("[^a-zA-Z0-9 ]"), "").trim().replace(" ", "_").ifBlank { "task" }
                    toolCalls.add(ToolCall("auto_cron", "cron", mapOf(
                        "action" to "schedule",
                        "name" to name,
                        "interval_minutes" to minutes.toString(),
                        "prompt" to prompt.ifBlank { userMessage }
                    )))
                }
            }
            // List tasks
            if (toolCalls.isEmpty() && (msg.contains("scheduled task") || msg.contains("list task") ||
                        msg.contains("my task") || msg.contains("active task") || msg.contains("what tasks") ||
                        msg.contains("show tasks") || msg.contains("cron list"))) {
                toolCalls.add(ToolCall("auto_cron", "cron", mapOf("action" to "list")))
            }
            // Cancel task
            if (toolCalls.isEmpty() && (msg.contains("cancel task") || msg.contains("stop task") ||
                        msg.contains("remove task") || msg.contains("delete task") || msg.contains("cancel reminder"))) {
                val cancelMatch = Regex("(?:cancel|stop|remove|delete)\\s+(?:the\\s+)?(?:task|reminder)\\s+(.+)", RegexOption.IGNORE_CASE).find(userMessage)
                if (cancelMatch != null) {
                    toolCalls.add(ToolCall("auto_cron", "cron", mapOf("action" to "cancel", "name" to cancelMatch.groupValues[1].trim())))
                }
            }
        }

        // ── 7. Status / Diagnostics ─────────────────────────────────────────
        if (toolCalls.isEmpty() && (msg.contains("status") || msg.contains("diagnostics") ||
                    msg.contains("health check") || msg.contains("service running") || msg.contains("is zeroclaw"))) {
            if (msg.contains("key") || msg.contains("api")) {
                toolCalls.add(ToolCall("auto_status", "status", mapOf("action" to "keys")))
            } else if (msg.contains("log")) {
                toolCalls.add(ToolCall("auto_status", "status", mapOf("action" to "logs")))
            } else if (msg.contains("connect") || msg.contains("telegram") || msg.contains("whatsapp") || msg.contains("discord")) {
                toolCalls.add(ToolCall("auto_status", "status", mapOf("action" to "connections")))
            } else {
                toolCalls.add(ToolCall("auto_status", "status", mapOf("action" to "overview")))
            }
        }

        // ── 8. GitHub ───────────────────────────────────────────────────────
        if (toolCalls.isEmpty() && (msg.contains("github") || msg.contains("repo") || msg.contains("repository"))) {
            // Search repos
            val searchMatch = Regex("(?:search|find|look for)\\s+(?:github\\s+)?(?:repos?|repositories?)\\s+(?:for |about |named )?(.+)", RegexOption.IGNORE_CASE).find(userMessage)
            if (searchMatch != null) {
                toolCalls.add(ToolCall("auto_github", "github", mapOf("action" to "search", "query" to searchMatch.groupValues[1].trim())))
            }
            // Read README
            if (toolCalls.isEmpty()) {
                val readmeMatch = Regex("(?:readme|read me|about)\\s+(?:of |for |from )?(?:(?:https?://)?github\\.com/)?([\\w-]+/[\\w.-]+)", RegexOption.IGNORE_CASE).find(userMessage)
                    ?: Regex("(?:https?://)?github\\.com/([\\w-]+/[\\w.-]+)", RegexOption.IGNORE_CASE).find(userMessage)
                if (readmeMatch != null) {
                    toolCalls.add(ToolCall("auto_github", "github", mapOf("action" to "readme", "repo" to readmeMatch.groupValues[1].trim())))
                }
            }
            // List issues
            if (toolCalls.isEmpty() && msg.contains("issue")) {
                val issueMatch = Regex("issues?\\s+(?:of |for |from |on )?(?:(?:https?://)?github\\.com/)?([\\w-]+/[\\w.-]+)", RegexOption.IGNORE_CASE).find(userMessage)
                if (issueMatch != null) {
                    toolCalls.add(ToolCall("auto_github", "github", mapOf("action" to "issues", "repo" to issueMatch.groupValues[1].trim())))
                }
            }
        }

        // ── 9. Notion ───────────────────────────────────────────────────────
        if (toolCalls.isEmpty() && msg.contains("notion")) {
            if (msg.contains("search") || msg.contains("find")) {
                val notionSearch = Regex("(?:search|find)\\s+(?:in\\s+)?notion\\s+(?:for\\s+)?(.+)", RegexOption.IGNORE_CASE).find(userMessage)
                    ?: Regex("notion\\s+(?:search|find)\\s+(?:for\\s+)?(.+)", RegexOption.IGNORE_CASE).find(userMessage)
                if (notionSearch != null) {
                    toolCalls.add(ToolCall("auto_notion", "notion", mapOf("action" to "search", "query" to notionSearch.groupValues[1].trim())))
                }
            } else if (msg.contains("read") || msg.contains("open") || msg.contains("show")) {
                val pageMatch = Regex("(?:read|open|show)\\s+(?:the\\s+)?notion\\s+(?:page\\s+)?(.+)", RegexOption.IGNORE_CASE).find(userMessage)
                if (pageMatch != null) {
                    toolCalls.add(ToolCall("auto_notion", "notion", mapOf("action" to "search", "query" to pageMatch.groupValues[1].trim())))
                }
            }
        }

        // ── 10. Email ───────────────────────────────────────────────────────
        if (toolCalls.isEmpty() && (msg.contains("send email") || msg.contains("send an email") ||
                    msg.contains("email to") || msg.contains("mail to") || msg.contains("draft email"))) {
            val emailMatch = Regex("(?:to|recipient:?)\\s+([\\w.+-]+@[\\w.-]+)", RegexOption.IGNORE_CASE).find(userMessage)
            val subjectMatch = Regex("(?:subject|about|regarding):?\\s+(.+?)(?:\\.|,|\\n|$)", RegexOption.IGNORE_CASE).find(userMessage)
            if (emailMatch != null) {
                val action = if (msg.contains("draft")) "draft" else "send"
                val args = mutableMapOf(
                    "action" to action,
                    "to" to emailMatch.groupValues[1].trim()
                )
                if (subjectMatch != null) args["subject"] = subjectMatch.groupValues[1].trim()
                args["body"] = userMessage // Let the LLM compose the body from the full message
                toolCalls.add(ToolCall("auto_email", "email", args))
            }
        }

        // ── Agents — list, status, enable, disable, run, results ────────
        if (toolCalls.isEmpty()) {
            val agentKeywords = msg.contains("agent") || msg.contains("agents") ||
                msg.contains("scraper") || msg.contains("scrapers") ||
                msg.contains("my bots") || msg.contains("background task")
            if (agentKeywords) {
                val action = when {
                    msg.contains("create") || msg.contains("add agent") || msg.contains("new agent") ||
                        msg.contains("set up") || msg.contains("setup") || msg.contains("make an agent") ||
                        msg.contains("track") || msg.contains("monitor") || msg.contains("watch") -> "create"
                    msg.contains("list") || msg.contains("show") || msg.contains("all agent") ||
                        msg.contains("what agent") || msg.contains("how many agent") -> "list"
                    msg.contains("enable") || msg.contains("activate") || msg.contains("turn on") || msg.contains("start agent") -> "enable"
                    msg.contains("disable") || msg.contains("deactivate") || msg.contains("turn off") || msg.contains("stop agent") || msg.contains("pause") -> "disable"
                    msg.contains("run") || msg.contains("execute") || msg.contains("trigger") || msg.contains("fetch now") -> "run"
                    msg.contains("delete") || msg.contains("remove") -> "delete"
                    msg.contains("change") || msg.contains("modify") || msg.contains("update agent") || msg.contains("adjust") ||
                        msg.contains("instead") || msg.contains("only remote") || msg.contains("only show") -> "modify"
                    msg.contains("result") || msg.contains("history") || msg.contains("output") || msg.contains("fetched") || msg.contains("data from") -> "results"
                    msg.contains("status") || msg.contains("detail") || msg.contains("info") || msg.contains("about") -> "status"
                    else -> "list"
                }
                // Try to extract agent name from the message
                val namePatterns = listOf(
                    Regex("(?:agent|scraper)\\s+['\"]?([\\w\\s-]+?)['\"]?\\s*(?:agent|status|result|history|detail|info|\\?|\$)", RegexOption.IGNORE_CASE),
                    Regex("(?:enable|disable|run|delete|start|stop|pause|activate|deactivate|trigger)\\s+(?:agent\\s+)?['\"]?([\\w\\s-]+?)['\"]?\\s*(?:\\?|\$)", RegexOption.IGNORE_CASE),
                    Regex("(?:results?|history|output|data)\\s+(?:from|of|for)\\s+(?:agent\\s+)?['\"]?([\\w\\s-]+?)['\"]?\\s*(?:\\?|\$)", RegexOption.IGNORE_CASE),
                    Regex("(?:about|status of|info on)\\s+(?:agent\\s+)?['\"]?([\\w\\s-]+?)['\"]?\\s*(?:\\?|\$)", RegexOption.IGNORE_CASE)
                )
                var agentTarget = ""
                for (p in namePatterns) {
                    val m = p.find(userMessage)
                    if (m != null) { agentTarget = m.groupValues[1].trim(); break }
                }
                val agentArgs = mutableMapOf("action" to action)
                if (agentTarget.isNotBlank()) agentArgs["target"] = agentTarget
                // For create, try to extract URL, interval, channel from the message
                if (action == "create") {
                    val urlMatch = Regex("(https?://[^\\s]+)", RegexOption.IGNORE_CASE).find(userMessage)
                    if (urlMatch != null) agentArgs["url"] = urlMatch.groupValues[1].removeSuffix(",").removeSuffix(")")
                    val intervalMatch = Regex("every\\s+(\\d+)\\s*(?:min|minute|m\\b|hour|h\\b)", RegexOption.IGNORE_CASE).find(userMessage)
                    if (intervalMatch != null) {
                        val num = intervalMatch.groupValues[1].toIntOrNull() ?: 60
                        val isHour = intervalMatch.value.contains(Regex("hour|h\\b", RegexOption.IGNORE_CASE))
                        agentArgs["interval"] = if (isHour) (num * 60).toString() else num.toString()
                    }
                    val channelMatch = Regex("(?:send|deliver|push)\\s+(?:to|via|on)\\s+(telegram|discord|slack|whatsapp|signal|matrix|email|teams|twitch|line|irc)", RegexOption.IGNORE_CASE).find(userMessage)
                    if (channelMatch != null) agentArgs["channel"] = channelMatch.groupValues[1].lowercase()
                    // Pass the full message as extract_prompt context for the LLM to use
                    agentArgs["_user_request"] = userMessage
                }
                toolCalls.add(ToolCall("auto_agents", "agents", agentArgs))
            }
        }

        // No auto-tool matched — return original
        if (toolCalls.isEmpty()) return userMessage

        // Execute matched tools and build enriched message
        val results = StringBuilder()
        for (call in toolCalls) {
            val enrichedCall = if (call.name == "memory" && call.args["user_id"].isNullOrBlank()) {
                call.copy(args = call.args + ("user_id" to chatId))
            } else call

            ZeroClawService.log("AUTO-TOOL: pre-executing ${call.name}(${call.args})")
            val result = toolSystem.executeTool(enrichedCall)
            if (result.success) {
                ZeroClawService.log("AUTO-TOOL: ✓ ${call.name} returned ${result.content.length} chars")
                results.appendLine("[${call.name} result]:")
                results.appendLine(result.content)
            } else {
                ZeroClawService.log("AUTO-TOOL: ✗ ${call.name} failed — ${result.error}")
            }
        }

        return if (results.isNotBlank()) {
            "$userMessage\n\n[Real-time data retrieved for you — use this to answer the user naturally:]\n${results.toString().take(4000)}"
        } else {
            userMessage
        }
    }

    // ── Validate a single key (used when user saves in dialog) ───────────────

    suspend fun validateKey(entry: ApiKeyEntry): ValidationResult {
        return try {
            when (entry.safeProvider) {
                "gemini"    -> validateGemini(entry.safeApiKey, entry.safePreferredModel)
                "anthropic" -> validateAnthropic(entry.safeApiKey, entry.safePreferredModel)
                "ollama"    -> validateOllama(entry.safePreferredModel)
                "offline"   -> validateOffline(entry.safePreferredModel)
                else        -> validateOpenAICompatible(entry.safeApiKey, entry.safeProvider, entry.safeBaseUrl, entry.safePreferredModel)
            }
        } catch (e: Exception) {
            ValidationResult(false, "Network error: ${e.message}")
        }
    }

    // ── Model listing helpers ─────────────────────────────────────────────────

    /** Fetch model list from any OpenAI-compatible /v1/models endpoint */
    private suspend fun listOpenAIModels(apiKey: String, provider: String, baseUrl: String): List<String> {
        val defaultBase = if (provider == "openrouter") "https://openrouter.ai/api/v1" else "https://api.openai.com/v1"
        val resolvedBase = baseUrl.ifBlank { defaultBase }.trimEnd('/')
        val req = Request.Builder()
            .url("$resolvedBase/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .get().build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val json = runCatching { JSONObject(resp.body?.string() ?: "") }.getOrNull()
                        ?: return@withContext emptyList()
                    val arr = json.optJSONArray("data") ?: return@withContext emptyList()
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val id = arr.getJSONObject(i).optString("id", "")
                        if (id.isNotBlank()) list.add(id)
                    }
                    // Sort: newest/best first — GPT-4 family first, then gpt-3.5, then others
                    list.sortedWith(compareBy(
                        { !it.contains("gpt-4o") },
                        { !it.contains("gpt-4") },
                        { !it.contains("gpt-3.5") },
                        { it }
                    ))
                }
            } catch (e: Exception) { emptyList() }
        }
    }

    /** Curated Anthropic model list — Anthropic has no public /models endpoint */
    private fun listAnthropicModels(): List<String> = listOf(
        "claude-opus-4-6",
        "claude-sonnet-4-6",
        "claude-haiku-4-5-20251001",
        "claude-3-5-sonnet-20241022",
        "claude-3-5-haiku-20241022",
        "claude-3-opus-20240229",
        "claude-3-haiku-20240307"
    )

    // ── OpenRouter rich model catalog ─────────────────────────────────────────

    data class OpenRouterModel(
        val id: String,
        val name: String,
        val contextLength: Int,
        val promptPricePer1M: Double,   // USD per 1M prompt tokens
        val completionPricePer1M: Double // USD per 1M completion tokens
    ) {
        val isFree: Boolean get() = promptPricePer1M == 0.0 && completionPricePer1M == 0.0
        val priceLabel: String get() = when {
            isFree -> "FREE"
            promptPricePer1M < 1.0 -> "<\$1/M"
            else -> "\$${String.format("%.0f", promptPricePer1M)}/M"
        }
        val contextLabel: String get() = when {
            contextLength >= 1_000_000 -> "${contextLength / 1_000_000}M ctx"
            contextLength >= 1_000     -> "${contextLength / 1_000}K ctx"
            else                       -> "$contextLength ctx"
        }
    }

    /** Fetch full OpenRouter model catalog with pricing + context info */
    suspend fun listOpenRouterModels(apiKey: String): List<OpenRouterModel> {
        val req = Request.Builder()
            .url("https://openrouter.ai/api/v1/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .get().build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val json = runCatching { JSONObject(resp.body?.string() ?: "") }.getOrNull()
                        ?: return@withContext emptyList()
                    val arr = json.optJSONArray("data") ?: return@withContext emptyList()
                    val list = mutableListOf<OpenRouterModel>()
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        val id = m.optString("id", "")
                        if (id.isBlank()) continue
                        val name = m.optString("name", id)
                        val ctx = m.optInt("context_length", 0)
                        val pricing = m.optJSONObject("pricing")
                        val prompt = pricing?.optString("prompt", "0")?.toDoubleOrNull() ?: 0.0
                        val completion = pricing?.optString("completion", "0")?.toDoubleOrNull() ?: 0.0
                        // OpenRouter prices are per-token; convert to per-1M
                        list.add(OpenRouterModel(
                            id = id,
                            name = name,
                            contextLength = ctx,
                            promptPricePer1M = prompt * 1_000_000,
                            completionPricePer1M = completion * 1_000_000
                        ))
                    }
                    // Sort: free first, then by name
                    list.sortedWith(compareBy({ !it.isFree }, { it.name.lowercase() }))
                }
            } catch (e: Exception) { emptyList() }
        }
    }

    /** Fetch local Ollama models from /api/tags */
    private suspend fun listOllamaModels(): List<String> {
        val req = Request.Builder().url("http://127.0.0.1:11434/api/tags").get().build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val json = runCatching { JSONObject(resp.body?.string() ?: "") }.getOrNull()
                        ?: return@withContext emptyList()
                    val arr = json.optJSONArray("models") ?: return@withContext emptyList()
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val name = arr.getJSONObject(i).optString("name", "")
                        if (name.isNotBlank()) list.add(name)
                    }
                    list.sorted()
                }
            } catch (e: Exception) { emptyList() }
        }
    }

    /** Test using a raw cURL-style call to a custom endpoint */
    suspend fun validateKeyWithCurl(bearerToken: String, baseUrl: String, model: String): ValidationResult {
        return try {
            val cleanBase = baseUrl.trimEnd('/')
            val url = "$cleanBase/chat/completions"
            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 5)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", "hi") })
                })
            }.toString()
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $bearerToken")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody())
                .build()
            withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { resp ->
                    val respBody = resp.body?.string() ?: ""
                    if (resp.isSuccessful) {
                        val json = runCatching { JSONObject(respBody) }.getOrNull()
                        val modelUsed = json?.optJSONArray("choices")
                            ?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")
                        ValidationResult(true, "✅ cURL test passed — endpoint responded successfully")
                    } else {
                        val json = runCatching { JSONObject(respBody) }.getOrNull()
                        val msg = json?.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                        ValidationResult(false, "❌ $msg")
                    }
                }
            }
        } catch (e: Exception) {
            ValidationResult(false, "Network error: ${e.message}")
        }
    }

    data class ValidationResult(
        val isValid: Boolean,
        val message: String,
        val availableModels: List<String> = emptyList(),
        val responseText: String = ""   // actual AI reply from the test prompt
    )

    // ── Fetch models only (no test prompt) ───────────────────────────────────

    /**
     * Fetch the list of available models for a given API key without sending any test prompt.
     * Returns a list of model IDs/names.
     */
    suspend fun fetchModelsOnly(entry: ApiKeyEntry): List<String> {
        return when (entry.safeProvider) {
            "gemini" -> {
                val url = "https://generativelanguage.googleapis.com/v1beta/models?key=${entry.safeApiKey}"
                val req = Request.Builder().url(url).get().build()
                withContext(Dispatchers.IO) {
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: return@withContext emptyList()
                        val json = JSONObject(body)
                        if (!resp.isSuccessful) {
                            val errMsg = json.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                            throw Exception("[${ resp.code}] $errMsg")
                        }
                        val arr = json.optJSONArray("models") ?: return@withContext emptyList()
                        val list = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            val m = arr.getJSONObject(i)
                            val methods = m.optJSONArray("supportedGenerationMethods")
                            var supportsGenerate = false
                            if (methods != null) {
                                for (j in 0 until methods.length()) {
                                    if (methods.getString(j) == "generateContent") { supportsGenerate = true; break }
                                }
                            }
                            if (supportsGenerate) {
                                val name = m.optString("name", "").removePrefix("models/")
                                if (name.isNotBlank()) list.add(name)
                            }
                        }
                        list
                    }
                }
            }
            "anthropic" -> listAnthropicModels()
            "ollama" -> listOllamaModels()
            "openrouter" -> listOpenAIModels(entry.safeApiKey, "openrouter", entry.safeBaseUrl)
            else -> listOpenAIModels(entry.safeApiKey, entry.safeProvider, entry.safeBaseUrl)
        }
    }

    // ── Public per-model test — used by "Check All Models" UI ────────────────

    /**
     * Test a single model for a given API key entry.
     * Returns Result.success(aiReply) or Result.failure(exception).
     */
    suspend fun testSingleModel(entry: ApiKeyEntry, modelId: String): Result<String> {
        return runCatching {
            sendTestPrompt(entry.safeApiKey, modelId, entry.safeProvider, entry.safeBaseUrl)
        }
    }

    // ── Send a real test prompt to verify key + model + quota ─────────────────

    private val TEST_PROMPT = "Say hello in one word."

    private suspend fun sendTestPrompt(
        apiKey: String,
        model: String,
        provider: String,
        baseUrl: String = ""
    ): String = withContext(Dispatchers.IO) {
        when (provider) {
            "gemini" -> {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val body = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", TEST_PROMPT) })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("maxOutputTokens", 20); put("temperature", 0.1)
                    })
                }.toString()
                val req = Request.Builder().url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody()).build()
                client.newCall(req).execute().use { resp ->
                    val respBody = resp.body?.string() ?: throw Exception("Empty response")
                    val json = JSONObject(respBody)
                    if (!resp.isSuccessful) {
                        val errMsg = json.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                        throw Exception("[${resp.code}] $errMsg")
                    }
                    val candidates = json.optJSONArray("candidates")
                        ?: throw Exception("No candidates in response")
                    val content = candidates.optJSONObject(0)?.optJSONObject("content")
                        ?: throw Exception("No content in response")
                    val parts = content.optJSONArray("parts")
                        ?: throw Exception("No parts in response — model may not support this prompt")
                    val text = parts.optJSONObject(0)?.optString("text", "")?.trim() ?: ""
                    if (text.isBlank()) throw Exception("Empty response from model")
                    text
                }
            }
            "anthropic" -> {
                val body = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 20)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", TEST_PROMPT) })
                    })
                }.toString()
                val req = Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody()).build()
                client.newCall(req).execute().use { resp ->
                    val respBody = resp.body?.string() ?: throw Exception("Empty response")
                    val json = JSONObject(respBody)
                    if (!resp.isSuccessful) {
                        val errMsg = json.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                        throw Exception("[${resp.code}] $errMsg")
                    }
                    json.getJSONArray("content").getJSONObject(0).getString("text").trim()
                }
            }
            "ollama" -> {
                val body = JSONObject().apply {
                    put("model", model); put("stream", false)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", TEST_PROMPT) })
                    })
                }.toString()
                val req = Request.Builder().url("http://127.0.0.1:11434/api/chat")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody()).build()
                client.newCall(req).execute().use { resp ->
                    val json = JSONObject(resp.body?.string() ?: throw Exception("Empty response"))
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                    json.getJSONObject("message").getString("content").trim()
                }
            }
            else -> {
                // OpenAI-compatible (OpenAI, OpenRouter, custom)
                val defaultBase = if (provider == "openrouter") "https://openrouter.ai/api/v1" else "https://api.openai.com/v1"
                val resolvedBase = baseUrl.ifBlank { defaultBase }.trimEnd('/')
                val body = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 20)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", TEST_PROMPT) })
                    })
                }.toString()
                val req = Request.Builder()
                    .url("$resolvedBase/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .apply { if (provider == "openrouter") addHeader("HTTP-Referer", "https://zeroclaw.ai") }
                    .post(body.toRequestBody()).build()
                client.newCall(req).execute().use { resp ->
                    val respBody = resp.body?.string() ?: throw Exception("Empty response")
                    val json = JSONObject(respBody)
                    if (!resp.isSuccessful) {
                        val errMsg = json.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                        throw Exception("[${resp.code}] $errMsg")
                    }
                    json.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()
                }
            }
        }
    }

    private suspend fun validateGemini(apiKey: String, preferredModel: String = ""): ValidationResult {
        // Step 1: List models (also proves key is valid)
        val modelsUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
        val modelsReq = Request.Builder().url(modelsUrl).get().build()

        val models = withContext(Dispatchers.IO) {
            client.newCall(modelsReq).execute().use { resp ->
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                if (!resp.isSuccessful) {
                    val errMsg = json.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                    val code = json.optJSONObject("error")?.optInt("code") ?: resp.code
                    throw Exception("[$code] $errMsg")
                }
                val arr = json.optJSONArray("models") ?: return@withContext emptyList<String>()
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    var supportsGenerate = false
                    if (methods != null) {
                        for (j in 0 until methods.length()) {
                            if (methods.getString(j) == "generateContent") { supportsGenerate = true; break }
                        }
                    }
                    if (supportsGenerate) {
                        val name = m.optString("name", "").removePrefix("models/")
                        val display = m.optString("displayName", name)
                        if (name.isNotBlank()) list.add("$name ($display)")
                    }
                }
                list
            }
        }

        if (models == null) {
            return ValidationResult(false, "Failed to connect to Gemini API")
        }

        // Step 2: Send a real test prompt to verify quota/billing
        val testModel = when {
            preferredModel.isNotBlank() -> preferredModel
            else -> models.firstOrNull { it.contains("2.5-flash") }?.substringBefore(" (")
                ?: models.firstOrNull { it.contains("2.0-flash") }?.substringBefore(" (")
                ?: models.firstOrNull { it.contains("1.5-flash") }?.substringBefore(" (")
                ?: models.firstOrNull()?.substringBefore(" (")
                ?: "gemini-2.5-flash-preview-04-17"
        }
        val testResponse = try {
            sendTestPrompt(apiKey, testModel, "gemini")
        } catch (e: Exception) {
            val msg = e.message ?: "unknown error"
            if (msg.contains("429") || msg.lowercase().contains("quota") ||
                msg.lowercase().contains("rate") || msg.lowercase().contains("exceeded")) {
                return ValidationResult(false,
                    "⚠️ Key valid but quota/rate-limit reached for $testModel. " +
                    "Try a different model or wait and retry.",
                    models, "")
            }
            return ValidationResult(false, "❌ Key valid but test prompt failed: $msg", models, "")
        }

        val msg = "✅ Valid — ${models.size} models, tested $testModel"
        return ValidationResult(true, msg, models, testResponse)
    }

    private suspend fun validateOpenAICompatible(apiKey: String, provider: String, baseUrl: String = "", preferredModel: String = ""): ValidationResult {
        val defaultBase = if (provider == "openrouter") "https://openrouter.ai/api/v1" else "https://api.openai.com/v1"
        val resolvedBase = baseUrl.ifBlank { defaultBase }.trimEnd('/')
        val testModel = when {
            preferredModel.isNotBlank() -> preferredModel
            provider == "openrouter"    -> "openai/gpt-4o-mini"
            else                        -> "gpt-4o-mini"
        }
        val label = when {
            baseUrl.isNotBlank() -> "custom endpoint ($resolvedBase)"
            provider == "openrouter" -> "OpenRouter"
            else -> "OpenAI"
        }

        // Send a real test prompt to verify key + model + quota
        val testResponse = try {
            sendTestPrompt(apiKey, testModel, provider, resolvedBase)
        } catch (e: Exception) {
            val msg = e.message ?: "unknown error"
            if (msg.contains("429") || msg.lowercase().contains("quota") ||
                msg.lowercase().contains("rate") || msg.lowercase().contains("exceeded")) {
                // Key is valid but quota hit — fetch models but warn
                val models = try { listOpenAIModels(apiKey, provider, baseUrl) } catch (_: Exception) { emptyList() }
                return ValidationResult(false,
                    "⚠️ Key valid but quota/rate-limit reached for $testModel. Try a different model or wait.",
                    models, "")
            }
            return ValidationResult(false, "❌ $msg")
        }

        // Test passed — fetch full model list
        val models = try { listOpenAIModels(apiKey, provider, baseUrl) } catch (_: Exception) { emptyList() }
        val msg = if (models.isNotEmpty())
            "✅ Valid $label key — ${models.size} models, tested $testModel"
        else
            "✅ Valid $label key — tested $testModel"
        return ValidationResult(true, msg, models, testResponse)
    }

    private suspend fun validateAnthropic(apiKey: String, preferredModel: String = ""): ValidationResult {
        val testModel = preferredModel.ifBlank { "claude-haiku-4-5-20251001" }
        val models = listAnthropicModels()

        // Send a real test prompt to verify key + model + quota
        val testResponse = try {
            sendTestPrompt(apiKey, testModel, "anthropic")
        } catch (e: Exception) {
            val msg = e.message ?: "unknown error"
            if (msg.contains("429") || msg.lowercase().contains("quota") ||
                msg.lowercase().contains("rate") || msg.lowercase().contains("exceeded")) {
                return ValidationResult(false,
                    "⚠️ Key valid but quota/rate-limit reached for $testModel. Try a different model or wait.",
                    models, "")
            }
            return ValidationResult(false, "❌ $msg", models, "")
        }

        return ValidationResult(true,
            "✅ Valid Anthropic key — ${models.size} models, tested $testModel",
            models, testResponse)
    }

    private suspend fun validateOllama(preferredModel: String = ""): ValidationResult {
        val req = Request.Builder().url("http://127.0.0.1:11434/api/tags").get().build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext ValidationResult(false, "❌ Ollama not responding (HTTP ${resp.code})")
                    }
                    val models = listOllamaModels()
                    if (models.isEmpty()) {
                        return@withContext ValidationResult(true,
                            "✅ Ollama running locally (no models installed yet)", models)
                    }
                    // Send test prompt
                    val testModel = preferredModel.ifBlank { models.first() }
                    val testResponse = try {
                        sendTestPrompt("local", testModel, "ollama")
                    } catch (e: Exception) {
                        return@withContext ValidationResult(false,
                            "⚠️ Ollama running but test prompt failed on $testModel: ${e.message}",
                            models, "")
                    }
                    ValidationResult(true,
                        "✅ Ollama — ${models.size} models, tested $testModel",
                        models, testResponse)
                }
            } catch (e: Exception) {
                ValidationResult(false, "❌ Ollama not found — is it running on this device?")
            }
        }
    }

    // ── Provider dispatch (actual message sending) ────────────────────────────

    private suspend fun dispatchToProvider(message: String, entry: ApiKeyEntry, chatId: String, model: String = "", systemPrompt: String = SYSTEM_PROMPT): String {
        val history = getHistory(chatId).dropLast(1) // drop the user msg we just added (it's in `message`)
        val useModel = model.ifBlank { entry.safePreferredModel }
        if (useModel != model) {
            ZeroClawService.log("LLM: model resolved: \"$model\" → \"$useModel\" (fallback to preferredModel)")
        }
        return when (entry.safeProvider) {
            "anthropic" -> callAnthropic(message, entry.safeApiKey, useModel, history, systemPrompt)
            "gemini"    -> callGemini(message, entry.safeApiKey, useModel, history, entry.safeGoogleSearch, systemPrompt)
            "ollama"    -> callOllama(message, useModel, history, systemPrompt)
            "offline"   -> callOffline(message, useModel, history, systemPrompt)
            else        -> callOpenAICompatible(message, entry.safeApiKey, entry.safeProvider, entry.safeBaseUrl, useModel, history, systemPrompt)
        }
    }

    private suspend fun callOpenAICompatible(message: String, apiKey: String, provider: String, baseUrl: String = "", preferredModel: String = "", history: List<ChatMessage> = emptyList(), systemPrompt: String = SYSTEM_PROMPT): String {
        val defaultBase = if (provider == "openrouter") "https://openrouter.ai/api/v1" else "https://api.openai.com/v1"
        val resolvedBase = baseUrl.ifBlank { defaultBase }.trimEnd('/')
        val model = when {
            preferredModel.isNotBlank() -> preferredModel
            provider == "openrouter"    -> "openai/gpt-4o-mini"
            else                        -> "gpt-4o-mini"
        }
        val body = JSONObject().apply {
            put("model", model); put("max_tokens", MAX_TOKENS)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role","system"); put("content", systemPrompt) })
                history.forEach { msg ->
                    put(JSONObject().apply { put("role", msg.role); put("content", msg.content) })
                }
                put(JSONObject().apply { put("role","user");   put("content", message) })
            })
        }.toString()
        val req = Request.Builder()
            .url("$resolvedBase/chat/completions")
            .addHeader("Authorization","Bearer $apiKey")
            .addHeader("Content-Type","application/json")
            .apply { if (provider == "openrouter") addHeader("HTTP-Referer","https://zeroclaw.ai") }
            .post(body.toRequestBody()).build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: throw Exception("Empty body")
                val json = JSONObject(respBody)
                if (!resp.isSuccessful) throw Exception("[${resp.code}] ${json.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"}")
                val choice = json.optJSONArray("choices")?.optJSONObject(0)
                    ?: throw Exception("MALFORMED_FUNCTION_CALL: No choices in response. Raw: ${respBody.take(300)}")
                val message = choice.optJSONObject("message")
                    ?: throw Exception("MALFORMED_FUNCTION_CALL: No message in choice. Raw: ${respBody.take(300)}")
                // OpenAI/OpenRouter may return tool_calls instead of content when confused by tool descriptions
                val content = message.optString("content", "").trim()
                if (content.isBlank()) {
                    val hasToolCalls = message.has("tool_calls")
                    if (hasToolCalls) {
                        throw Exception("MALFORMED_FUNCTION_CALL: Model tried to call tools instead of responding. Retry without tool descriptions.")
                    }
                    throw Exception("Empty response from model (finish_reason=${choice.optString("finish_reason", "unknown")})")
                }
                content
            }
        }
    }

    private suspend fun callAnthropic(message: String, apiKey: String, preferredModel: String = "", history: List<ChatMessage> = emptyList(), systemPrompt: String = SYSTEM_PROMPT): String {
        val model = preferredModel.ifBlank { "claude-haiku-4-5-20251001" }
        val body = JSONObject().apply {
            put("model", model); put("max_tokens", MAX_TOKENS)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                history.forEach { msg ->
                    put(JSONObject().apply { put("role", msg.role); put("content", msg.content) })
                }
                put(JSONObject().apply { put("role","user"); put("content", message) })
            })
        }.toString()
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey).addHeader("anthropic-version","2023-06-01")
            .addHeader("Content-Type","application/json").post(body.toRequestBody()).build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: throw Exception("Empty body")
                val json = JSONObject(respBody)
                if (!resp.isSuccessful) throw Exception("[${resp.code}] ${json.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"}")
                val contentArr = json.optJSONArray("content")
                    ?: throw Exception("Empty response from Claude (no content array)")
                // Find first text block — Claude may return tool_use blocks if confused by tool descriptions
                var textResult = ""
                for (i in 0 until contentArr.length()) {
                    val block = contentArr.getJSONObject(i)
                    if (block.optString("type") == "text") {
                        textResult = block.optString("text", "")
                        if (textResult.isNotBlank()) break
                    }
                    if (block.optString("type") == "tool_use") {
                        throw Exception("MALFORMED_FUNCTION_CALL: Claude tried to call tools instead of responding. Retry without tool descriptions.")
                    }
                }
                if (textResult.isBlank()) throw Exception("Empty response from Claude (no text blocks)")
                textResult.trim()
            }
        }
    }

    private suspend fun callGemini(message: String, apiKey: String, preferredModel: String = "", history: List<ChatMessage> = emptyList(), googleSearch: Boolean = false, systemPrompt: String = SYSTEM_PROMPT): String {
        val model = preferredModel.ifBlank { "gemini-2.5-flash-preview-04-17" }
            .let { if (it.isBlank()) "gemini-1.5-flash" else it }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                // Add conversation history
                history.forEach { msg ->
                    put(JSONObject().apply {
                        put("role", if (msg.role == "assistant") "model" else "user")
                        put("parts", JSONArray().apply { put(JSONObject().apply { put("text", msg.content) }) })
                    })
                }
                // Current message
                put(JSONObject().apply {
                    put("role","user")
                    put("parts", JSONArray().apply { put(JSONObject().apply { put("text", message) }) })
                })
            })
            // Gemma models don't support systemInstruction — skip it to avoid 400 error
            val isGemma = model.contains("gemma", ignoreCase = true)
            if (!isGemma) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply { put(JSONObject().apply { put("text", systemPrompt) }) })
                })
            }
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", MAX_TOKENS); put("temperature", 0.7)
            })
            // Enable Grounding with Google Search for real-time info (if user enabled it)
            if (googleSearch) {
                put("tools", JSONArray().apply {
                    put(JSONObject().apply {
                        put("googleSearch", JSONObject())
                    })
                })
            }
        }.toString()
        val req = Request.Builder().url(url).addHeader("Content-Type","application/json")
            .post(body.toRequestBody()).build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: throw Exception("Empty body")
                val json = JSONObject(respBody)
                if (!resp.isSuccessful) {
                    val code = resp.code
                    val errMsg = json.optJSONObject("error")?.optString("message") ?: "HTTP $code"
                    throw Exception("[$code] $errMsg")
                }
                val candidates = json.optJSONArray("candidates")
                    ?: throw Exception("No candidates in response — possible safety block. Raw: ${respBody.take(300)}")
                val candidate = candidates.optJSONObject(0)
                    ?: throw Exception("Empty candidates array")
                val finishReason = candidate.optString("finishReason", "")
                if (finishReason == "UNEXPECTED_TOOL_CALL" || finishReason == "MALFORMED_FUNCTION_CALL") {
                    // Model tried native function calling because it saw tool descriptions in prompt.
                    // Retry handled by caller — throw specific error so call() can retry without tools.
                    throw Exception("$finishReason: Model attempted native function calling. Retry without tool descriptions.")
                }
                val content = candidate.optJSONObject("content")
                    ?: throw Exception("No content in candidate (finishReason=$finishReason). Raw: ${respBody.take(300)}")
                val parts = content.optJSONArray("parts")
                if (parts == null || parts.length() == 0) {
                    throw Exception("Empty response from model (finishReason=$finishReason, no parts). This may be a safety filter or the model had nothing to say. Raw: ${respBody.take(300)}")
                }
                // Try to find a text part (skip function call parts, thought parts, etc.)
                var textResult = ""
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    val text = part.optString("text", "")
                    if (text.isNotBlank()) { textResult = text; break }
                }
                if (textResult.isBlank()) {
                    throw Exception("No text in response parts (finishReason=$finishReason). Parts may contain non-text data. Raw: ${respBody.take(300)}")
                }
                textResult.trim()
            }
        }
    }

    private suspend fun callOllama(message: String, preferredModel: String = "", history: List<ChatMessage> = emptyList(), systemPrompt: String = SYSTEM_PROMPT): String {
        val model = preferredModel.ifBlank { "llama3.2" }
        val body = JSONObject().apply {
            put("model", model); put("stream", false)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role","system"); put("content", systemPrompt) })
                history.forEach { msg ->
                    put(JSONObject().apply { put("role", msg.role); put("content", msg.content) })
                }
                put(JSONObject().apply { put("role","user");   put("content", message) })
            })
        }.toString()
        val req = Request.Builder().url("http://127.0.0.1:11434/api/chat")
            .addHeader("Content-Type","application/json").post(body.toRequestBody()).build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: throw Exception("Empty body"))
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                json.getJSONObject("message").getString("content").trim()
            }
        }
    }

    // ── Offline model (LiteRT LM Engine+Conversation) ──────────────────────

    private suspend fun callOffline(message: String, preferredModel: String, history: List<ChatMessage> = emptyList(), systemPrompt: String = SYSTEM_PROMPT, prefill: String = ""): String {
        val manager = OfflineModelManager.getInstance(context)
        if (preferredModel.isNotBlank()) {
            val models = manager.listAppModels()
            val match = models.firstOrNull { m -> m.name == preferredModel || m.path == preferredModel }
            if (match != null && manager.getLoadedModelPath() != match.path) {
                // LiteRT LM models get larger context; legacy .bin models stay at 1024
                val maxTokens = if (match.isLiteRtFormat) 4096 else 1024
                manager.loadModel(match.path, maxTokens = maxTokens, systemInstruction = systemPrompt).getOrThrow()
            }
        }
        if (!manager.isModelLoaded()) {
            throw Exception("No offline model loaded — import one in Settings → API Keys")
        }

        // LiteRT LM Conversation maintains history automatically via sendMessageAsync().
        // For the first call or when conversation context doesn't include history yet,
        // we send just the user message — the Conversation object handles multi-turn.
        // For legacy compatibility with the Pass 2 pipeline that sends synthetic prompts
        // with history baked in, we detect if history is provided and format accordingly.
        val prompt = if (history.isNotEmpty() && history.size > 1) {
            // Pass 2 / summarizer calls: send the full context as a single message
            // since these are one-shot calls, not multi-turn conversations
            val historyText = history.joinToString("\n") { msg ->
                if (msg.role == "user") "User: ${msg.content}" else "Assistant: ${msg.content}"
            }
            "$historyText\nUser: $message"
        } else {
            // Normal chat: just send the user message, Conversation handles history
            if (prefill.isNotBlank()) "$message\n$prefill" else message
        }

        val raw = manager.generateResponse(prompt)

        // Strip leaked user-turn continuations (model keeps generating past its response)
        val trimmed = raw
            .split(Regex("\n+(?:User|Human)\\s*:", RegexOption.IGNORE_CASE)).first()
            .trim()
        return if (prefill.isNotBlank() && !trimmed.startsWith(prefill)) "$prefill$trimmed" else trimmed
    }

    private suspend fun validateOffline(preferredModel: String): ValidationResult {
        val manager = OfflineModelManager.getInstance(context)
        val models = manager.listAppModels()

        if (models.isEmpty()) {
            return ValidationResult(false,
                "❌ No models imported. Use the file picker in Settings → API Keys to import a .bin or .litertlm model.")
        }

        val modelNames = models.map { m -> m.name }

        // Try to load the preferred model
        if (preferredModel.isNotBlank()) {
            val match = models.firstOrNull { m -> m.name == preferredModel || m.path == preferredModel }
            if (match != null) {
                val maxTokens = if (match.isLiteRtFormat) 4096 else 1024
                val loadResult = manager.loadModel(match.path, maxTokens = maxTokens)
                if (loadResult.isFailure) {
                    return ValidationResult(false,
                        "❌ Failed to load ${match.name}: ${loadResult.exceptionOrNull()?.message}",
                        modelNames)
                }
                // Send test prompt
                val testResponse = try {
                    manager.generateResponse("Say hello in one word.")
                } catch (e: Exception) {
                    return ValidationResult(false,
                        "⚠️ Model loaded but test prompt failed: ${e.message}",
                        modelNames, "")
                }
                val engineLabel = if (match.isLiteRtFormat) "LiteRT LM" else "legacy"
                return ValidationResult(true,
                    "✅ Offline model loaded: ${match.name} (${match.sizeMB}, $engineLabel)",
                    modelNames, testResponse)
            }
        }

        // No preferred model — just list available
        val displayNames = models.map { m -> "${m.name} (${m.sizeMB})" }
        return ValidationResult(true,
            "✅ ${models.size} model file${if (models.size != 1) "s" else ""} imported",
            displayNames)
    }

    private fun String.toRequestBody(): RequestBody =
        toRequestBody("application/json; charset=utf-8".toMediaType())
}
