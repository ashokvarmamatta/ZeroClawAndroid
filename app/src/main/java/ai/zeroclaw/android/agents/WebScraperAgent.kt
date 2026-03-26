package ai.zeroclaw.android.agents

import android.content.Context
import ai.zeroclaw.android.agents.api.FreeApiRegistry
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.service.ZeroClawService
import ai.zeroclaw.android.tools.ToolSystem
import ai.zeroclaw.android.telegram.TelegramBotManager
import ai.zeroclaw.android.tools.WebFetchTool
import ai.zeroclaw.android.ui.ChannelTarget
import ai.zeroclaw.android.ui.parseChannelTargets

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

    suspend fun run(agent: AgentConfig) {
        ZeroClawService.log("AGENT[${agent.name}]: starting run → ${agent.url}")

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

            val fetchTool = WebFetchTool()
            val fetchResult = fetchTool.execute(mapOf("url" to agent.url))

            if (!fetchResult.success) {
                val status = "Fetch failed: ${fetchResult.error}"
                ZeroClawService.log("AGENT[${agent.name}]: $status")
                agentManager.markRun(agent.id, agent.lastContentHash, status)
                return
            }

            content = fetchResult.content

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
                deliverToChannel(svc, agent, agent.channel, chatId, message, content.length, newHash)
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

        if (deliveredTo.isNotEmpty()) {
            ZeroClawService.log("AGENT[${agent.name}]: ✓ $status")
        } else {
            ZeroClawService.log("AGENT[${agent.name}]: ✗ $status")
        }
        agentManager.markRun(agent.id, newHash, status)
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
        contentLength: Int, newHash: Int
    ) {
        try {
            svc.sendProactive(channel, chatId, message)
            val status = "Delivered $contentLength chars → $channel/$chatId"
            ZeroClawService.log("AGENT[${agent.name}]: ✓ $status")
            agentManager.markRun(agent.id, newHash, status)
        } catch (e: Exception) {
            val status = "Delivery error: ${e.message}"
            ZeroClawService.log("AGENT[${agent.name}]: ✗ $status")
            agentManager.markRun(agent.id, newHash, status)
        }
    }

    /**
     * Use LlmRouter.extractOnly() to apply the user's extraction prompt.
     * Calls the model DIRECTLY — no Pass 2 web search, no tool enrichment,
     * no chat history. This prevents the agent from getting irrelevant
     * web search results instead of extracting from the actual fetched content.
     * Returns null on failure so caller can fall back to raw content.
     */
    private suspend fun extractWithLlm(agent: AgentConfig, rawContent: String): String? {
        return try {
            val router = LlmRouter.getInstance(context)
            val cleaned = stripBoilerplate(rawContent)
            val contentSnippet = cleaned.take(2500)
            val prompt = """INSTRUCTION: ${agent.extractPrompt}

IMPORTANT: The following content was JUST FETCHED from ${agent.url} — it is real, live data. Extract the requested information from it. Do NOT say you lack real-time access.

--- BEGIN FETCHED CONTENT ---
$contentSnippet
--- END FETCHED CONTENT ---"""
            ZeroClawService.log("AGENT[${agent.name}]: extracting with LLM (${contentSnippet.length} chars content)")
            val reply = router.extractOnly(prompt)
            if (reply != null) {
                ZeroClawService.log("AGENT[${agent.name}]: extraction OK (${reply.length} chars)")
            }
            reply
        } catch (e: Throwable) {
            ZeroClawService.log("AGENT[${agent.name}]: LLM extraction failed — ${e.message}")
            null
        }
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
        val sourceLabel = if (usedApi) "Free API" else "Web Scraper"
        return "🤖 *${agent.name}* — $sourceLabel\n🔗 ${agent.url}\n🕐 ${dtFmt.format(java.util.Date())}"
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
        "email"     -> "📧"
        else        -> "📤"
    }

    companion object {
        private const val MAX_MESSAGE_CHARS = 3500
    }
}
