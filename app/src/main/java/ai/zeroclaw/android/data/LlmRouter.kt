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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

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

    // ── Sealed result type so callers know WHY it failed ─────────────────────

    sealed class LlmResult {
        data class Success(val text: String) : LlmResult()
        data class RateLimit(val message: String) : LlmResult()  // 429 — key valid, quota hit
        data class Failure(val message: String) : LlmResult()    // auth/network error
    }

    // ── Main entry point — waterfall failover ─────────────────────────────────

    suspend fun call(userMessage: String, chatId: String = "default"): String {
        val allKeys = keyManager.loadKeys().filter { it.enabled }
        if (allKeys.isEmpty()) {
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
                val profiles = kotlinx.coroutines.runBlocking { pm.getAllProfiles() }
                val activeId = kotlinx.coroutines.runBlocking { pm.getActiveProfileId() }
                "🤖 Agent Profiles:\n" + profiles.joinToString("\n") { p ->
                    val active = if (p.id == activeId) " ← active" else ""
                    "• ${p.emoji} ${p.name} (${p.id})$active"
                } + "\n\nSwitch: /profile <id>"
            } else {
                kotlinx.coroutines.runBlocking { pm.setActiveProfile(arg) }
                val profile = kotlinx.coroutines.runBlocking { pm.resolveProfile(chatId) }
                "✅ Switched to ${profile.emoji} ${profile.name}"
            }
        }

        // Handle /tools command — list available tools
        if (userMessage.trim().equals("/tools", ignoreCase = true)) {
            val toolSystem = ToolSystem.getInstance(context)
            val enabled = toolSystem.enabledTools()
            return if (enabled.isEmpty()) "No tools enabled."
            else "🔧 Enabled tools:\n" + enabled.joinToString("\n") { "• ${it.name} — ${it.description.take(60)}" }
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
        val toolsPrompt = toolSystem.buildToolsPrompt()

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
            val modelsToTry = if (entry.safeSelectedModels.isNotEmpty()) {
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

            // For offline models: summarize the user message if it's long to avoid token overflow
            val messageForModel = if (entry.safeProvider == "offline" && effectiveMessage.length > 300) {
                val sumResult = toolSystem.executeTool(
                    ToolCall("p1_prompt_sum", "summarize", mapOf("text" to effectiveMessage, "sentences" to "3"))
                )
                if (sumResult.success && sumResult.content.isNotBlank()) {
                    ZeroClawService.logDetail("Prompt summarized: ${effectiveMessage.length} → ${sumResult.content.length} chars")
                    sumResult.content
                } else effectiveMessage
            } else effectiveMessage

            var keyWorked = false
            for (model in modelsToTry) {
                ZeroClawService.log("LLM: [$mode] trying model=\"$model\" via key=\"${entry.safeLabel}\" ($provider)")

                val effectiveSystemPrompt = if (entry.safeProvider == "offline") {
                    basePrompt + thinkingAddition
                } else {
                    basePrompt + toolsPrompt + thinkingAddition
                }

                ZeroClawService.logDetail("━━━ PASS 1 — SENDING TO MODEL ━━━")
                ZeroClawService.logDetail("Provider: $provider | Model: $model | Key: ${entry.safeLabel}")
                ZeroClawService.logDetail("System prompt (${effectiveSystemPrompt.length} chars): ${effectiveSystemPrompt.take(300)}…")
                ZeroClawService.logDetail("User message sent: $messageForModel")

                val result = runCatching { dispatchToProvider(messageForModel, entry, chatId, model, effectiveSystemPrompt) }

                when {
                    result.isSuccess -> {
                        var reply = result.getOrNull() ?: ""
                        if (reply.isNotBlank()) {
                            ZeroClawService.logDetail("━━━ PASS 1 — MODEL REPLY ━━━")
                            ZeroClawService.logDetail("Reply (${reply.length} chars): ${reply.take(500)}")

                            // ── Offline two-pass: detect real-time refusal → fetch → re-ask ──
                            if (entry.safeProvider == "offline" && isRealTimeRefusal(reply)) {
                                ZeroClawService.log("OFFLINE: real-time refusal detected — fetching web data for second pass")
                                ZeroClawService.logDetail("━━━ REFUSAL DETECTED — STARTING PASS 2 ━━━")
                                ZeroClawService.logDetail("Refusal snippet: ${reply.take(200)}")

                                val enriched = buildOfflineSecondPassMessage(effectiveMessage, toolSystem)

                                ZeroClawService.logDetail("━━━ PASS 2 — SENDING ENRICHED MESSAGE ━━━")
                                ZeroClawService.logDetail("Enriched message (${enriched.length} chars): ${enriched.take(600)}")

                                val secondResult = runCatching {
                                    dispatchToProvider(enriched, entry, chatId, model, effectiveSystemPrompt)
                                }
                                val secondReply = secondResult.getOrNull()
                                if (!secondReply.isNullOrBlank()) {
                                    ZeroClawService.log("OFFLINE: ✓ second pass reply (${secondReply.length} chars)")
                                    ZeroClawService.logDetail("━━━ PASS 2 — MODEL REPLY ━━━")
                                    ZeroClawService.logDetail("Reply (${secondReply.length} chars): ${secondReply.take(500)}")
                                    reply = secondReply
                                } else {
                                    ZeroClawService.log("OFFLINE: second pass failed — using first reply")
                                    ZeroClawService.logDetail("Pass 2 failed — falling back to pass 1 reply")
                                }
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

                        if (msg.contains("429") || msg.lowercase().contains("quota") ||
                            msg.lowercase().contains("rate") || msg.lowercase().contains("exceeded")) {
                            ZeroClawService.log("LLM: ⚠ [$mode] ${entry.safeLabel}/\"$model\" rate-limited — trying next")
                            lastRateLimitMsg = msg
                        } else {
                            ZeroClawService.log("LLM: ✗ [$mode] ${entry.safeLabel}/\"$model\" failed — $msg")
                        }
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
                break
            }

            // Strip tool_call blocks from the reply to get the text parts
            val textParts = reply.replace(
                Regex("```tool_call\\s*\\n\\{[^`]+\\}\\s*\\n?```", RegexOption.DOT_MATCHES_ALL),
                ""
            ).trim()

            // Build a follow-up message with the tool results
            val toolResultMessage = buildString {
                if (textParts.isNotBlank()) appendLine(textParts)
                appendLine("\n[Tool Results]")
                append(results.toString().take(6000))
                appendLine("\nUse the above tool results to provide your final answer to the user. Do not call any more tools unless absolutely necessary.")
            }

            // Add tool result as context and call LLM again
            addToHistory(chatId, "assistant", textParts.ifBlank { "(used tools)" })
            addToHistory(chatId, "user", toolResultMessage)

            val nextResult = runCatching { dispatchToProvider(toolResultMessage, entry, chatId, model, systemPrompt) }
            reply = nextResult.getOrNull() ?: break
            if (reply.isBlank()) break
        }

        return reply
    }

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
     * Second-pass message for offline: fetch web search + web_fetch + summarize,
     * then ask the model to answer using that data.
     */
    private suspend fun buildOfflineSecondPassMessage(userMessage: String, toolSystem: ToolSystem): String {
        val now = java.util.Date()
        val fmt = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy 'at' HH:mm z", java.util.Locale.ENGLISH)
        fmt.timeZone = java.util.TimeZone.getDefault()
        val currentDateTime = fmt.format(now)

        val sb = StringBuilder()
        sb.appendLine("Current date and time: $currentDateTime")
        sb.appendLine()

        // web_search
        if (toolSystem.isEnabled("web_search")) {
            // Clean query: remove ?, !, leading question words that confuse search engines
            val rawQuery = userMessage.take(200)
            val cleanQuery = rawQuery
                .replace(Regex("[?!]"), "")
                .replace(Regex("^(is|are|was|were|did|do|does|can|will|has|have|what|who|when|where|why|how)\\s+", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { rawQuery }
            ZeroClawService.log("OFFLINE-2P: web_search query: \"${cleanQuery.take(60)}\"")
            ZeroClawService.logDetail("Tool: web_search | Query: $cleanQuery")

            var searchResult = toolSystem.executeTool(
                ToolCall("2p_search", "web_search", mapOf("query" to cleanQuery))
            )
            // Retry with keyword-only query if first attempt returns no results
            val noResults = !searchResult.success || searchResult.content.startsWith("No results")
            if (noResults) {
                val keywordQuery = cleanQuery.split(" ").take(5).joinToString(" ")
                ZeroClawService.logDetail("web_search no results — retrying with keywords: $keywordQuery")
                searchResult = toolSystem.executeTool(
                    ToolCall("2p_search_retry", "web_search", mapOf("query" to keywordQuery))
                )
            }

            // BraveSearch fallback if DDG failed
            val ddgFailed = !searchResult.success || searchResult.content.startsWith("No results") || searchResult.content.isBlank()
            if (ddgFailed && toolSystem.isEnabled("brave_search")) {
                val braveKey = AppSettings.dataStore(context).data
                    .map { it[AppSettings.KEY_BRAVE_API_KEY] ?: "" }
                    .first()
                if (braveKey.isNotBlank()) {
                    ZeroClawService.log("OFFLINE-2P: DDG failed — trying BraveSearch fallback")
                    ZeroClawService.logDetail("DDG failed — trying BraveSearch | key=${braveKey.take(8)}…")
                    val braveResult = toolSystem.executeTool(
                        ToolCall("2p_brave", "brave_search", mapOf("query" to cleanQuery, "api_key" to braveKey))
                    )
                    if (braveResult.success && braveResult.content.isNotBlank()) {
                        ZeroClawService.log("OFFLINE-2P: ✓ BraveSearch ${braveResult.content.length} chars")
                        ZeroClawService.logDetail("BraveSearch result (${braveResult.content.length} chars): ${braveResult.content.take(400)}")
                        searchResult = braveResult
                    } else {
                        ZeroClawService.logDetail("BraveSearch also failed: ${braveResult.error}")
                    }
                } else {
                    ZeroClawService.logDetail("BraveSearch skipped: no API key configured (add in Settings)")
                }
            }

            if (searchResult.success && searchResult.content.isNotBlank() && !searchResult.content.startsWith("No results")) {
                ZeroClawService.log("OFFLINE-2P: ✓ web_search ${searchResult.content.length} chars")
                ZeroClawService.logDetail("web_search result (${searchResult.content.length} chars): ${searchResult.content.take(400)}")
                sb.appendLine("Web search results:")
                sb.appendLine(searchResult.content.take(1500))

                // web_fetch the first URL
                if (toolSystem.isEnabled("web_fetch")) {
                    val firstUrl = Regex("""URL:\s*(https?://\S+)""").find(searchResult.content)?.groupValues?.get(1)
                    if (firstUrl != null) {
                        ZeroClawService.log("OFFLINE-2P: fetching $firstUrl")
                        ZeroClawService.logDetail("Tool: web_fetch | URL: $firstUrl")
                        val fetchResult = toolSystem.executeTool(
                            ToolCall("2p_fetch", "web_fetch", mapOf("url" to firstUrl))
                        )
                        if (fetchResult.success && fetchResult.content.isNotBlank()) {
                            ZeroClawService.log("OFFLINE-2P: ✓ web_fetch ${fetchResult.content.length} chars")
                            ZeroClawService.logDetail("web_fetch result (${fetchResult.content.length} chars): ${fetchResult.content.take(400)}")
                            sb.appendLine()
                            sb.appendLine("Detailed content from top result:")
                            sb.appendLine(fetchResult.content.take(1500))
                        } else {
                            ZeroClawService.logDetail("web_fetch failed: ${fetchResult.error}")
                        }
                    } else {
                        ZeroClawService.logDetail("web_fetch skipped: no URL found in search results")
                    }
                } else {
                    ZeroClawService.logDetail("web_fetch skipped: not enabled")
                }
            } else {
                ZeroClawService.log("OFFLINE-2P: web_search failed — ${searchResult.error}")
                ZeroClawService.logDetail("web_search failed: ${searchResult.error}")
            }
        } else {
            ZeroClawService.logDetail("web_search skipped: not enabled")
        }

        // Always summarize — extract the most relevant sentences for the user's question.
        // More sentences for list-type queries (top 10, list movies, etc.)
        val rawContext = sb.toString()
        val isListQuery = Regex("(list|top\\s*\\d+|give me \\d+|show \\d+|\\d+ movies|\\d+ songs|\\d+ results)",
            RegexOption.IGNORE_CASE).containsMatchIn(userMessage)
        val sentenceCount = if (isListQuery) "12" else "6"

        val finalContext = if (rawContext.isNotBlank() && toolSystem.isEnabled("summarize")) {
            ZeroClawService.log("OFFLINE-2P: summarizing ${rawContext.length} chars → $sentenceCount sentences")
            ZeroClawService.logDetail("Tool: summarize | sentences=$sentenceCount | listQuery=$isListQuery")
            val textForSummary = "Topic: $userMessage\n\n$rawContext"
            val sumResult = toolSystem.executeTool(
                ToolCall("2p_sum", "summarize", mapOf("text" to textForSummary, "sentences" to sentenceCount))
            )
            if (sumResult.success && sumResult.content.isNotBlank()) {
                ZeroClawService.log("OFFLINE-2P: ✓ summarized to ${sumResult.content.length} chars")
                ZeroClawService.logDetail("Summarized context: ${sumResult.content}")
                sumResult.content
            } else {
                ZeroClawService.logDetail("summarize failed — using raw context trimmed")
                rawContext.take(1500)
            }
        } else {
            rawContext.take(1500)
        }

        // Natural RAG-style prompt — no robotic "Based on facts given" forcing
        return buildString {
            appendLine("Here is current information retrieved from the web (as of $currentDateTime):")
            appendLine()
            appendLine(finalContext)
            appendLine()
            append("$userMessage")
        }
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

        // ── 2. Summarize (text or URL) ──────────────────────────────────────
        val wantsSummary = msg.contains("summarize") || msg.contains("summary") || msg.contains("summarise") ||
                msg.contains("key points") || msg.contains("tldr") || msg.contains("tl;dr") ||
                msg.contains("condense") || msg.contains("shorten") || msg.contains("brief")
        if (toolCalls.isEmpty() && wantsSummary) {
            val urlInMsg = Regex("(https?://[^\\s]+)", RegexOption.IGNORE_CASE).find(userMessage)
            if (urlInMsg != null) {
                val url = urlInMsg.groupValues[1].removeSuffix(",").removeSuffix(")").removeSuffix(".")
                toolCalls.add(ToolCall("auto_summarize", "summarize", mapOf("text" to url)))
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
                val json = JSONObject(resp.body?.string() ?: throw Exception("Empty body"))
                if (!resp.isSuccessful) throw Exception("[${resp.code}] ${json.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"}")
                json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
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
                val json = JSONObject(resp.body?.string() ?: throw Exception("Empty body"))
                if (!resp.isSuccessful) throw Exception("[${resp.code}] ${json.optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"}")
                json.getJSONArray("content").getJSONObject(0).getString("text").trim()
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
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply { put(JSONObject().apply { put("text", systemPrompt) }) })
            })
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
                json.getJSONArray("candidates")
                    .getJSONObject(0).getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0).getString("text").trim()
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

    // ── Offline model (MediaPipe LlmInference) ──────────────────────────────

    private suspend fun callOffline(message: String, preferredModel: String, history: List<ChatMessage> = emptyList(), systemPrompt: String = SYSTEM_PROMPT): String {
        val manager = OfflineModelManager.getInstance(context)
        if (preferredModel.isNotBlank()) {
            val models = manager.listAppModels()
            val match = models.firstOrNull { m -> m.name == preferredModel || m.path == preferredModel }
            if (match != null && manager.getLoadedModelPath() != match.path) {
                manager.loadModel(match.path).getOrThrow()
            }
        }
        if (!manager.isModelLoaded()) {
            throw Exception("No offline model loaded — import one in Settings → API Keys")
        }
        // Build prompt with conversation history
        val historyText = if (history.isNotEmpty()) {
            history.joinToString("\n") { msg ->
                if (msg.role == "user") "User: ${msg.content}" else "Assistant: ${msg.content}"
            } + "\n"
        } else ""
        val fullPrompt = "$systemPrompt\n\n${historyText}User: $message\nAssistant:"
        return manager.generateResponse(fullPrompt)
    }

    private suspend fun validateOffline(preferredModel: String): ValidationResult {
        val manager = OfflineModelManager.getInstance(context)
        val models = manager.listAppModels()

        if (models.isEmpty()) {
            return ValidationResult(false,
                "❌ No models imported. Use the file picker in Settings → API Keys to import a .bin model.")
        }

        val modelNames = models.map { m -> m.name }

        // Try to load the preferred model
        if (preferredModel.isNotBlank()) {
            val match = models.firstOrNull { m -> m.name == preferredModel || m.path == preferredModel }
            if (match != null) {
                val loadResult = manager.loadModel(match.path)
                if (loadResult.isFailure) {
                    return ValidationResult(false,
                        "❌ Failed to load ${match.name}: ${loadResult.exceptionOrNull()?.message}",
                        modelNames)
                }
                // Send test prompt
                val testResponse = try {
                    val prompt = "Say hello in one word.\nAssistant:"
                    manager.generateResponse(prompt)
                } catch (e: Exception) {
                    return ValidationResult(false,
                        "⚠️ Model loaded but test prompt failed: ${e.message}",
                        modelNames, "")
                }
                return ValidationResult(true,
                    "✅ Offline model loaded: ${match.name} (${match.sizeMB})",
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
