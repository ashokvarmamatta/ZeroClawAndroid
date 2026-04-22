package ai.zeroclaw.android.agents

import android.content.Context
import ai.zeroclaw.android.agents.api.FreeApiRegistry
import ai.zeroclaw.android.data.AgentResultDatabase
import ai.zeroclaw.android.data.AgentResultEntity
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.service.HomeWidget
import ai.zeroclaw.android.service.ZeroClawService
import ai.zeroclaw.android.tools.ToolSystem
import ai.zeroclaw.android.telegram.TelegramBotManager
import ai.zeroclaw.android.tools.WebFetchTool
import ai.zeroclaw.android.ui.ChannelTarget
import ai.zeroclaw.android.ui.parseChannelTargets
import java.util.UUID

/**
 * WebScraperAgent — executes a single web scraper agent run.
 *
 * Pipeline:
 *  1. Fetch URL via WebFetchTool
 *  2. If extractPrompt set → use SummarizeTool or LlmRouter to extract insight
 *  3. Change detection — if onlyOnChange and content hash unchanged, skip
 *  4. Deliver to all selected bots + channel targets via ZeroClawService.sendProactive()
 */
class WebScraperAgent(private val context: Context) {

    private val agentManager = AgentManager.getInstance(context)
    private val resultDb = AgentResultDatabase.getInstance(context).agentResultDao()

    suspend fun run(agent: AgentConfig) {
        val runId = UUID.randomUUID().toString()
        ZeroClawService.log("AGENT[${agent.name}]: starting run → ${if (agent.url.isBlank()) "(no url)" else agent.url}")
        ZeroClawService.activityState = "agent_run"
        ZeroClawService.lastActivityDetail = agent.name

        // ── BUG-43: search_only agents skip URL fetching and run web_search directly ──
        if (agent.type == AgentManager.TYPE_SEARCH_ONLY) {
            runSearchOnly(agent, runId)
            return
        }

        // ── Step 1: Try direct free API first (Phase 166) ────────────────────
        val apiParams = buildApiParams(agent)
        val templateId = agent.templateId ?: agent.apiSource
        val apiResult = try {
            if (agent.apiSource != null) {
                // Agent has explicit apiSource
                val source = FreeApiRegistry.getSource(agent.apiSource)
                if (source != null) {
                    ZeroClawService.log("AGENT[${agent.name}]: trying direct API → ${source.displayName}")
                    source.fetch(apiParams)
                } else null
            } else {
                // Try by template ID
                FreeApiRegistry.tryFetch(templateId, apiParams)
            }
        } catch (e: Exception) {
            ZeroClawService.log("AGENT[${agent.name}]: API error, falling back to web scrape — ${e.message}")
            null
        }

        var content: String
        var usedApi = false

        if (apiResult != null && apiResult.success) {
            // Direct API succeeded — skip web scraping and LLM extraction
            content = apiResult.content
            usedApi = true
            ZeroClawService.log("AGENT[${agent.name}]: direct API OK (${content.length} chars)")
        } else {
            // Fallback: web scrape + LLM extraction (original path)
            if (apiResult != null && !apiResult.success) {
                ZeroClawService.log("AGENT[${agent.name}]: API failed (${apiResult.error}), falling back to web scrape")
            }

            // Fetch from all URLs (primary + extras) and combine
            val allUrls = agent.allUrls
            val fetchMethod = agent.safeFetchType
            val allContent = StringBuilder()
            var anySuccess = false

            for ((idx, fetchUrl) in allUrls.withIndex()) {
                if (fetchUrl.isBlank()) continue
                ZeroClawService.log("AGENT[${agent.name}]: fetching [${idx + 1}/${allUrls.size}] via ${fetchMethod.uppercase()} → $fetchUrl")
                val fetchResult = when (fetchMethod) {
                    "rss" -> try {
                        ai.zeroclaw.android.tools.RssFeedTool().execute(mapOf("url" to fetchUrl, "limit" to "10"))
                    } catch (_: Exception) { WebFetchTool().execute(mapOf("url" to fetchUrl)) }
                    "webview" -> try {
                        ai.zeroclaw.android.tools.WebViewTool(context).execute(mapOf("action" to "fetch", "url" to fetchUrl, "wait_ms" to "3000"))
                    } catch (_: Exception) { WebFetchTool().execute(mapOf("url" to fetchUrl)) }
                    else -> WebFetchTool().execute(mapOf("url" to fetchUrl))
                }
                if (fetchResult.success && fetchResult.content.isNotBlank()) {
                    anySuccess = true
                    if (allUrls.size > 1) allContent.appendLine("\n--- Source: $fetchUrl ---")
                    allContent.appendLine(fetchResult.content)
                } else {
                    ZeroClawService.log("AGENT[${agent.name}]: fetch failed for $fetchUrl — ${fetchResult.error}")
                }
            }

            if (!anySuccess) {
                val status = "All ${allUrls.size} URLs failed to fetch ($fetchMethod)"
                ZeroClawService.log("AGENT[${agent.name}]: $status")
                agentManager.markRun(agent.id, agent.lastContentHash, status)
                saveResult(agent, runId, "failed", "", "", emptyList(), status, false, agent.lastContentHash)
                return
            }

            content = allContent.toString()

            // ── Extract / Summarize via LLM ──────────────────────────────────
            if (agent.extractPrompt.isNotBlank()) {
                content = extractWithLlm(agent, content) ?: content
            }
        }

        // ── Step 3: Change detection ─────────────────────────────────────────
        val newHash = content.hashCode()
        if (agent.onlyOnChange && newHash == agent.lastContentHash) {
            val status = "No change detected — skipped push"
            ZeroClawService.log("AGENT[${agent.name}]: $status")
            agentManager.markRun(agent.id, newHash, status)
            saveResult(agent, runId, "skipped", content, content, emptyList(), status, usedApi, newHash)
            return
        }

        // ── Step 4: Deliver to all targets ───────────────────────────────────
        val header = buildHeader(agent, usedApi)
        val message = "$header\n\n${content.take(MAX_MESSAGE_CHARS)}"

        val svc = ZeroClawService.instance
        if (svc == null) {
            val status = "Service not running — delivery skipped"
            ZeroClawService.log("AGENT[${agent.name}]: $status")
            agentManager.markRun(agent.id, newHash, status)
            saveResult(agent, runId, "failed", content, content, emptyList(), status, usedApi, newHash)
            return
        }

        // Parse delivery targets:
        // agent.channel = comma-separated bot names (e.g., "telegram,discord")
        // agent.chatId = channel:id pairs for specific targets (e.g., "discord:123,slack:C456")
        val botChannels = agent.channel.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val channelTargets = parseChannelTargets(agent.chatId)

        if (botChannels.isEmpty() && channelTargets.isEmpty()) {
            // Legacy fallback: treat channel as single bot, chatId as target
            val chatId = resolveSingleChatId(agent.channel, agent.chatId, agent, newHash)
            if (chatId != null) {
                deliverToChannel(svc, agent, agent.channel, chatId, message, content.length, newHash, runId, content, usedApi)
            } else {
                saveResult(agent, runId, "failed", content, message, emptyList(), "No chat available", usedApi, newHash)
            }
            return
        }

        val deliveredTo = mutableListOf<String>()
        val errors = mutableListOf<String>()

        // Deliver to each selected bot (auto-resolve chatId)
        for (ch in botChannels) {
            val chatId = resolveBotChatId(ch, agent)
            if (chatId != null) {
                try {
                    svc.sendProactive(ch, chatId, message)
                    deliveredTo.add("${channelEmoji(ch)}$ch")
                } catch (e: Exception) {
                    errors.add("$ch: ${e.message}")
                }
            } else {
                errors.add("$ch: no chat available")
            }
        }

        // Deliver to each specific channel target
        for (target in channelTargets) {
            try {
                svc.sendProactive(target.channel, target.chatId, message)
                deliveredTo.add("${channelEmoji(target.channel)}${target.channel}/${target.chatId.take(10)}")
            } catch (e: Exception) {
                errors.add("${target.channel}/${target.chatId}: ${e.message}")
            }
        }

        // Build final status
        val status = buildString {
            if (deliveredTo.isNotEmpty()) {
                append("Delivered ${content.length} chars → ${deliveredTo.joinToString(", ")}")
            }
            if (errors.isNotEmpty()) {
                if (deliveredTo.isNotEmpty()) append(" | ")
                append("Failed: ${errors.joinToString("; ")}")
            }
            if (deliveredTo.isEmpty() && errors.isEmpty()) {
                append("No delivery targets available")
            }
        }

        val resultStatus = if (deliveredTo.isNotEmpty() && errors.isEmpty()) "success"
            else if (deliveredTo.isNotEmpty()) "partial"
            else "failed"

        if (deliveredTo.isNotEmpty()) {
            ZeroClawService.log("AGENT[${agent.name}]: ✓ $status")
        } else {
            ZeroClawService.log("AGENT[${agent.name}]: ✗ $status")
        }
        agentManager.markRun(agent.id, newHash, status)
        saveResult(agent, runId, resultStatus, content, message, deliveredTo, errors.joinToString("; "), usedApi, newHash)
    }

    /**
     * BUG-43: search_only agents skip URL fetching and run web_search directly.
     * The extractPrompt is treated as the search query + extraction instructions.
     */
    private suspend fun runSearchOnly(agent: AgentConfig, runId: String) {
        val content: String = try {
            val router = LlmRouter.getInstance(context)
            ZeroClawService.log("AGENT[${agent.name}]: search_only — calling rawGenerateWithTools")
            val reply = router.rawGenerateWithTools(agent.extractPrompt).trim()
            if (reply.isBlank()) {
                val status = "search_only: empty LLM reply"
                ZeroClawService.log("AGENT[${agent.name}]: $status")
                agentManager.markRun(agent.id, agent.lastContentHash, status)
                saveResult(agent, runId, "failed", "", "", emptyList(), status, false, agent.lastContentHash)
                return
            }
            reply
        } catch (e: Throwable) {
            val status = "search_only failed: ${e.message}"
            ZeroClawService.log("AGENT[${agent.name}]: $status")
            agentManager.markRun(agent.id, agent.lastContentHash, status)
            saveResult(agent, runId, "failed", "", "", emptyList(), status, false, agent.lastContentHash)
            return
        }

        val newHash = content.hashCode()
        if (agent.onlyOnChange && newHash == agent.lastContentHash) {
            val status = "No change detected — skipped push"
            ZeroClawService.log("AGENT[${agent.name}]: $status")
            agentManager.markRun(agent.id, newHash, status)
            saveResult(agent, runId, "skipped", content, content, emptyList(), status, false, newHash)
            return
        }

        val header = buildHeader(agent, usedApi = false)
        val message = "$header\n\n${content.take(MAX_MESSAGE_CHARS)}"

        val svc = ZeroClawService.instance
        if (svc == null) {
            val status = "Service not running — delivery skipped"
            ZeroClawService.log("AGENT[${agent.name}]: $status")
            agentManager.markRun(agent.id, newHash, status)
            saveResult(agent, runId, "failed", content, content, emptyList(), status, false, newHash)
            return
        }

        val botChannels = agent.channel.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val channelTargets = parseChannelTargets(agent.chatId)

        if (botChannels.isEmpty() && channelTargets.isEmpty()) {
            val chatId = resolveSingleChatId(agent.channel, agent.chatId, agent, newHash)
            if (chatId != null) {
                deliverToChannel(svc, agent, agent.channel, chatId, message, content.length, newHash, runId, content, usedApi = false)
            } else {
                saveResult(agent, runId, "failed", content, message, emptyList(), "No chat available", false, newHash)
            }
            return
        }

        val deliveredTo = mutableListOf<String>()
        val errors = mutableListOf<String>()
        for (ch in botChannels) {
            val chatId = resolveBotChatId(ch, agent)
            if (chatId != null) {
                try {
                    svc.sendProactive(ch, chatId, message)
                    deliveredTo.add("${channelEmoji(ch)}$ch")
                } catch (e: Exception) {
                    errors.add("$ch: ${e.message}")
                }
            } else {
                errors.add("$ch: no chat available")
            }
        }
        for (target in channelTargets) {
            try {
                svc.sendProactive(target.channel, target.chatId, message)
                deliveredTo.add("${channelEmoji(target.channel)}${target.channel}/${target.chatId.take(10)}")
            } catch (e: Exception) {
                errors.add("${target.channel}/${target.chatId}: ${e.message}")
            }
        }

        val status = buildString {
            if (deliveredTo.isNotEmpty()) append("Delivered ${content.length} chars → ${deliveredTo.joinToString(", ")}")
            if (errors.isNotEmpty()) {
                if (deliveredTo.isNotEmpty()) append(" | ")
                append("Failed: ${errors.joinToString("; ")}")
            }
            if (deliveredTo.isEmpty() && errors.isEmpty()) append("No delivery targets available")
        }
        val resultStatus = if (deliveredTo.isNotEmpty() && errors.isEmpty()) "success"
            else if (deliveredTo.isNotEmpty()) "partial"
            else "failed"
        ZeroClawService.log("AGENT[${agent.name}]: ${if (deliveredTo.isNotEmpty()) "✓" else "✗"} $status")
        agentManager.markRun(agent.id, newHash, status)
        saveResult(agent, runId, resultStatus, content, message, deliveredTo, errors.joinToString("; "), false, newHash)
    }

    /**
     * Resolve chat ID for a bot channel (no user-specified ID).
     * Uses LlmRouter history → TelegramBotManager last known chat → empty string for other channels.
     */
    private fun resolveBotChatId(channel: String, agent: AgentConfig): String? {
        // 1. Try known chat IDs from LlmRouter conversation history
        val known = try { LlmRouter.getInstance(context).getKnownChatIds() } catch (_: Exception) { emptyMap() }
        val ids = known[channel]
        if (!ids.isNullOrEmpty()) return ids.first()

        // 2. For Telegram, use persisted last known chat from bot polling
        if (channel == "telegram") {
            val lastTgChat = TelegramBotManager.lastKnownChatId
            if (lastTgChat != null) return lastTgChat.toString()
            return null
        }

        // 3. Other channels — pass empty string, let channel manager handle
        return ""
    }

    /**
     * Legacy single-channel resolution for old agents that haven't been re-saved.
     */
    private fun resolveSingleChatId(channel: String, chatId: String, agent: AgentConfig, contentHash: Int): String? {
        val ch = channel.lowercase()
        if (chatId.isNotBlank()) {
            if (ch == "telegram" && chatId.trim().toLongOrNull() == null) {
                ZeroClawService.log("AGENT[${agent.name}]: chatId '${chatId}' not numeric — auto-resolving")
            } else {
                return chatId
            }
        }
        return resolveBotChatId(ch, agent) ?: run {
            ZeroClawService.log("AGENT[${agent.name}]: No chat available for $ch — send a message to the bot first")
            agentManager.markRun(agent.id, contentHash, "No chat available for $ch")
            null
        }
    }

    private suspend fun deliverToChannel(
        svc: ZeroClawService, agent: AgentConfig,
        channel: String, chatId: String, message: String,
        contentLength: Int, newHash: Int,
        runId: String = "", rawContent: String = "", usedApi: Boolean = false
    ) {
        try {
            svc.sendProactive(channel, chatId, message)
            val status = "Delivered $contentLength chars → $channel/$chatId"
            ZeroClawService.log("AGENT[${agent.name}]: ✓ $status")
            agentManager.markRun(agent.id, newHash, status)
            saveResult(agent, runId, "success", rawContent, message, listOf(channel), "", usedApi, newHash)
        } catch (e: Exception) {
            val status = "Delivery error: ${e.message}"
            ZeroClawService.log("AGENT[${agent.name}]: ✗ $status")
            agentManager.markRun(agent.id, newHash, status)
            saveResult(agent, runId, "failed", rawContent, message, emptyList(), e.message ?: status, usedApi, newHash)
        }
    }

    /**
     * Use LlmRouter.extractOnly() to apply the user's extraction prompt.
     * Calls the model DIRECTLY — no Pass 2 web search, no tool enrichment,
     * no chat history. This prevents the agent from getting irrelevant
     * web search results instead of extracting from the actual fetched content.
     *
     * The prompt now explicitly asks for Telegram Markdown formatting so
     * the delivered message looks structured and readable in chat apps.
     * Returns null on failure so caller can fall back to raw content.
     */
    private suspend fun extractWithLlm(agent: AgentConfig, rawContent: String): String? {
        return try {
            val router = LlmRouter.getInstance(context)
            val cleaned = stripBoilerplate(rawContent)
            val contentSnippet = cleaned.take(2500)

            // Build format guide from saved formatPreview — tells LLM what fields to find
            val formatGuide = if (agent.safeFormatPreview.isNotBlank()) {
                """
REFERENCE FORMAT (use this EXACT structure — replace values with CURRENT data from the fetched content):
${agent.safeFormatPreview}

HOW TO USE THIS FORMAT:
- The format above shows the EXACT output structure you must follow
- Each line/bullet represents a data field — find the MATCHING value in the fetched content below
- Match fields by meaning: if the format shows an anime name, find anime names in the content; if it shows episode numbers, find episode numbers
- Do NOT copy the example values from the format — find the REAL current values from the fetched content
- Keep the same Markdown formatting (*bold*, bullets, numbering) as shown in the format
"""
            } else ""

            val prompt = """You are a data extraction bot. Your ONLY job is to extract EXACTLY what the user asked for from the fetched content below.

USER ASKED FOR: ${agent.extractPrompt}
$formatGuide
STRICT RULES:
- Output ONLY the requested data — no explanation, no preamble, no commentary
- Do NOT add "Here is...", "Based on...", "I found..." or any filler text
- Do NOT add source attribution, disclaimers, or footer text
- Do NOT include raw HTML, URLs, or page metadata unless specifically asked
- Do NOT invent or hallucinate values — every value MUST come from the fetched content below
- If a value cannot be found in the content, skip that item entirely rather than guessing
- Use Telegram Markdown: *bold* for headers/labels, • for bullet points, numbered lists for ordered items
- If the content doesn't have what was asked for, output only: "No matching data found"

FETCHED CONTENT FROM ${agent.url}:
$contentSnippet

OUTPUT (start directly with the extracted data, following the reference format above):"""
            ZeroClawService.log("AGENT[${agent.name}]: extracting with LLM (${contentSnippet.length} chars content)")
            var reply = router.extractOnly(prompt)
            if (reply != null) {
                // Strip any preamble the LLM might have added despite instructions
                reply = cleanExtractedReply(reply)
                ZeroClawService.log("AGENT[${agent.name}]: extraction OK (${reply.length} chars)")
            }
            reply
        } catch (e: Throwable) {
            ZeroClawService.log("AGENT[${agent.name}]: LLM extraction failed — ${e.message}")
            null
        }
    }

    /** Remove common LLM preamble/filler that leaks through despite prompt instructions */
    private fun cleanExtractedReply(reply: String): String {
        var text = reply.trim()
        // Strip common preamble patterns
        val preamblePatterns = listOf(
            Regex("^(?:Here(?:'s| is| are) (?:the |your |)(?:extracted |requested |)(?:data|information|content|results?))[:\\s]*", RegexOption.IGNORE_CASE),
            Regex("^(?:Based on (?:the |)(?:fetched |)(?:content|data|page)[,:\\s]+)", RegexOption.IGNORE_CASE),
            Regex("^(?:I (?:found|extracted|got) (?:the following|this)[:\\s]*)", RegexOption.IGNORE_CASE),
            Regex("^(?:The (?:fetched |extracted |)(?:content|data|page) (?:shows|contains|includes)[:\\s]*)", RegexOption.IGNORE_CASE),
            Regex("^(?:From (?:the |)(?:fetched |)(?:content|URL|page|data)[,:\\s]+)", RegexOption.IGNORE_CASE),
            Regex("^(?:Sure[!,.]?\\s*(?:Here(?:'s| is| are)[:\\s]*)?)"),
            Regex("^(?:OUTPUT[:\\s]*)", RegexOption.IGNORE_CASE)
        )
        for (pattern in preamblePatterns) {
            text = text.replace(pattern, "").trim()
        }
        // Strip trailing disclaimers/source lines
        val trailingPatterns = listOf(
            Regex("\\n+(?:Source|Data source|Note|Disclaimer|\\*Note)[:\\s].*$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            Regex("\\n+(?:This data|The above|All data|Data (?:is |was )).*$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        )
        for (pattern in trailingPatterns) {
            text = text.replace(pattern, "").trim()
        }
        return text
    }

    /** Strip RSS/XML boilerplate, copyright notices, and metadata that waste token budget */
    private fun stripBoilerplate(content: String): String {
        var text = content
        val boilerplatePhrases = listOf(
            "This XML feed is made available solely",
            "Any other use of the feed is expressly prohibited",
            "By accessing this feed or using these results",
            "you agree to be bound by the foregoing",
            "Copyright ©",
            "All rights reserved"
        )
        for (phrase in boilerplatePhrases) {
            text = text.replace(Regex("[^.]*${Regex.escape(phrase)}[^.]*\\.?\\s*"), " ")
        }
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun buildHeader(agent: AgentConfig, usedApi: Boolean = false): String {
        val dtFmt = java.text.SimpleDateFormat("MMM d, yyyy HH:mm", java.util.Locale.ENGLISH)
        dtFmt.timeZone = java.util.TimeZone.getDefault()
        val sourceLabel = when {
            agent.type == AgentManager.TYPE_SEARCH_ONLY -> "Tool Search"
            usedApi -> "Free API"
            else -> "Web Scraper"
        }
        val sourceLine = if (agent.type == AgentManager.TYPE_SEARCH_ONLY)
            "🔗 using web_search + web_fetch"
        else "🔗 ${agent.url}"
        return "🤖 *${agent.name}* — $sourceLabel\n$sourceLine\n🕐 ${dtFmt.format(java.util.Date())}"
    }

    /** Build params map for API data sources from agent config */
    private fun buildApiParams(agent: AgentConfig): Map<String, String> {
        val params = mutableMapOf<String, String>()
        // Extract query from the URL or extractPrompt
        val url = agent.url
        val query = extractQueryFromUrl(url) ?: extractQueryFromPrompt(agent.extractPrompt) ?: agent.name
        params["query"] = query
        return params
    }

    private fun extractQueryFromUrl(url: String): String? {
        // Try to get q= parameter from Google search URLs
        val match = Regex("[?&]q=([^&]+)").find(url)
        if (match != null) {
            return java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
                .replace("+", " ")
                .replace(Regex("\\s+(price|today|latest|live|scores|results).*", RegexOption.IGNORE_CASE), "")
                .trim()
        }
        return null
    }

    private fun extractQueryFromPrompt(prompt: String): String? {
        // Look for specific keywords in extraction prompts
        val match = Regex("\\{query\\}").find(prompt)
        return if (match != null) null else null // {query} should already be replaced
    }

    private fun channelEmoji(channel: String) = when (channel.lowercase()) {
        "telegram"  -> "✈️"
        "discord"   -> "🎮"
        "slack"     -> "💼"
        "whatsapp"  -> "💬"
        "signal"    -> "🔒"
        "matrix"    -> "🟢"
        "irc"       -> "📡"
        "teams"     -> "🟦"
        "twitch"    -> "🟣"
        "line"      -> "🟩"
        "email"     -> "📧"
        else        -> "📤"
    }

    /** Persist agent run result to Room database for API access */
    private suspend fun saveResult(
        agent: AgentConfig, runId: String, status: String,
        rawContent: String, extractedContent: String,
        deliveredTo: List<String>, errorMessage: String,
        usedApi: Boolean, contentHash: Int
    ) {
        // Update widget activity state
        ZeroClawService.lastAgentRun = "${agent.name} — $status"
        ZeroClawService.activityState = "idle"
        ZeroClawService.lastActivityDetail = ""
        HomeWidget.broadcastUpdate(context)
        try {
            val deliveredJson = org.json.JSONArray(deliveredTo).toString()
            resultDb.insert(AgentResultEntity(
                agentId = agent.id,
                agentName = agent.name,
                runId = runId,
                status = status,
                url = agent.url,
                usedApi = usedApi,
                rawContent = rawContent.take(MAX_RAW_CONTENT_CHARS),
                extractedContent = extractedContent.take(MAX_MESSAGE_CHARS),
                deliveredTo = deliveredJson,
                errorMessage = errorMessage.take(500),
                contentHash = contentHash
            ))
        } catch (e: Exception) {
            ZeroClawService.log("AGENT[${agent.name}]: failed to save result to DB — ${e.message}")
        }
    }

    companion object {
        private const val MAX_MESSAGE_CHARS = 3500
        private const val MAX_RAW_CONTENT_CHARS = 5000
    }
}
