package ai.zeroclaw.android.agents

import android.content.Context
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

        val svc = ZeroClawService.instance
        if (svc == null) {
            val status = "Service not running — delivery skipped"
            ZeroClawService.log("AGENT[${agent.name}]: $status")
            agentManager.markRun(agent.id, newHash, status)
            return
        }

        try {
            svc.sendProactive(agent.channel, agent.chatId, message)
            val status = "Delivered ${content.length} chars → ${agent.channel}/${agent.chatId}"
            ZeroClawService.log("AGENT[${agent.name}]: ✓ $status")
            agentManager.markRun(agent.id, newHash, status)
        } catch (e: Exception) {
            val status = "Delivery error: ${e.message}"
            ZeroClawService.log("AGENT[${agent.name}]: ✗ $status")
            agentManager.markRun(agent.id, newHash, status)
        }
    }

    /**
     * Use LlmRouter to apply the user's extraction prompt against the fetched content.
     * Returns null on failure so caller can fall back to raw content.
     */
    private suspend fun extractWithLlm(agent: AgentConfig, rawContent: String): String? {
        return try {
            val router = ai.zeroclaw.android.data.LlmRouter.getInstance(context)
            // Keep content short — offline models cap at 1024 tokens (~3000 chars total).
            // 600 chars of content leaves enough room for the prompt template + system prompt.
            val contentSnippet = rawContent.take(600)
            val prompt = """Extract from this web page content: ${agent.extractPrompt}

Content:
$contentSnippet

Be concise."""
            val reply = router.call(prompt, chatId = "agent_${agent.id}")
            if (reply.isNotBlank()) reply else null
        } catch (_: Exception) {
            null
        }
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
