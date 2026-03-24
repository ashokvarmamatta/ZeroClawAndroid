package ai.zeroclaw.android.agents

import android.content.Context
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.service.ZeroClawService
import ai.zeroclaw.android.tools.ToolSystem
import ai.zeroclaw.android.tools.WebFetchTool

/**
 * WebScraperAgent — executes a single web scraper agent run.
 *
 * Pipeline:
 *  1. Fetch URL via WebFetchTool
 *  2. If extractPrompt set → use SummarizeTool or LlmRouter to extract insight
 *  3. Change detection — if onlyOnChange and content hash unchanged, skip
 *  4. Send result to configured channel via ZeroClawService.sendProactive()
 */
class WebScraperAgent(private val context: Context) {

    private val agentManager = AgentManager.getInstance(context)

    suspend fun run(agent: AgentConfig) {
        ZeroClawService.log("AGENT[${agent.name}]: starting web scrape → ${agent.url}")

        // ── Step 1: Fetch ────────────────────────────────────────────────────
        // Always use a fresh WebFetchTool instance so it picks up the latest client config
        val fetchTool = WebFetchTool()

        val fetchResult = fetchTool.execute(mapOf("url" to agent.url))

        if (!fetchResult.success) {
            val status = "Fetch failed: ${fetchResult.error}"
            ZeroClawService.log("AGENT[${agent.name}]: $status")
            agentManager.markRun(agent.id, agent.lastContentHash, status)
            return
        }

        var content = fetchResult.content

        // ── Step 2: Extract / Summarize ──────────────────────────────────────
        if (agent.extractPrompt.isNotBlank()) {
            content = extractWithLlm(agent, content) ?: content
        }

        // ── Step 3: Change detection ─────────────────────────────────────────
        val newHash = content.hashCode()
        if (agent.onlyOnChange && newHash == agent.lastContentHash) {
            val status = "No change detected — skipped push"
            ZeroClawService.log("AGENT[${agent.name}]: $status")
            agentManager.markRun(agent.id, newHash, status)
            return
        }

        // ── Step 4: Deliver ──────────────────────────────────────────────────
        val header = buildHeader(agent)
        val message = "$header\n\n${content.take(MAX_MESSAGE_CHARS)}"

        // Resolve chatId — if blank, use the most recent known chat for this channel
        val effectiveChatId = if (agent.chatId.isNotBlank()) agent.chatId else {
            val known = try { LlmRouter.getInstance(context).getKnownChatIds() } catch (_: Exception) { emptyMap() }
            val ids = known[agent.channel]
            if (ids.isNullOrEmpty()) {
                val status = "No chat ID configured and no recent chats found for ${agent.channel}"
                ZeroClawService.log("AGENT[${agent.name}]: $status")
                agentManager.markRun(agent.id, newHash, status)
                return
            }
            ids.first().also {
                ZeroClawService.log("AGENT[${agent.name}]: using last known chat ID: $it")
            }
        }

        val svc = ZeroClawService.instance
        if (svc == null) {
            val status = "Service not running — delivery skipped"
            ZeroClawService.log("AGENT[${agent.name}]: $status")
            agentManager.markRun(agent.id, newHash, status)
            return
        }

        try {
            svc.sendProactive(agent.channel, effectiveChatId, message)
            val status = "Delivered ${content.length} chars → ${agent.channel}/${effectiveChatId}"
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
            // Strip common boilerplate that wastes token budget
            val cleaned = stripBoilerplate(rawContent)
            // Use 2000 chars — extractOnly() will truncate further if needed for offline models
            val contentSnippet = cleaned.take(2000)
            val prompt = """${agent.extractPrompt}

Content:
$contentSnippet"""
            ZeroClawService.log("AGENT[${agent.name}]: extracting with LLM (${contentSnippet.length} chars content)")
            val reply = router.extractOnly(prompt)
            if (reply != null) {
                ZeroClawService.log("AGENT[${agent.name}]: extraction OK (${reply.length} chars)")
            }
            reply
        } catch (e: Throwable) {
            // Catch Throwable (not just Exception) — MediaPipe JNI errors can throw Error
            ZeroClawService.log("AGENT[${agent.name}]: LLM extraction failed — ${e.message}")
            null
        }
    }

    /** Strip RSS/XML boilerplate, copyright notices, and metadata that waste token budget */
    private fun stripBoilerplate(content: String): String {
        var text = content
        // Remove common RSS boilerplate lines
        val boilerplatePhrases = listOf(
            "This XML feed is made available solely",
            "Any other use of the feed is expressly prohibited",
            "By accessing this feed or using these results",
            "you agree to be bound by the foregoing",
            "Copyright ©",
            "All rights reserved"
        )
        for (phrase in boilerplatePhrases) {
            // Remove the sentence containing the phrase
            text = text.replace(Regex("[^.]*${Regex.escape(phrase)}[^.]*\\.?\\s*"), " ")
        }
        // Collapse whitespace
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun buildHeader(agent: AgentConfig): String {
        val dtFmt = java.text.SimpleDateFormat("MMM d, yyyy HH:mm", java.util.Locale.ENGLISH)
        dtFmt.timeZone = java.util.TimeZone.getDefault()
        return "🤖 *${agent.name}* — Web Scraper\n🔗 ${agent.url}\n🕐 ${dtFmt.format(java.util.Date())}"
    }

    companion object {
        private const val MAX_MESSAGE_CHARS = 3500
    }
}
