package ai.zeroclaw.android.tools

import android.content.Context
import ai.zeroclaw.android.ai.AgentProfileManager
import ai.zeroclaw.android.ai.MultiAgentManager
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DelegateTool — Phase 142: Delegate a task to a named agent profile.
 *
 * Routes work to a specific named persona (e.g. "coder", "analyst", "creative")
 * and waits for the result. Uses MultiAgentManager.spawn() + AgentProfileManager
 * for per-profile system prompts and model preferences.
 *
 * Sub-agents are restricted from calling delegate/spawn themselves (depth limit).
 *
 * Disabled by default — enable in Settings when you want agent-to-agent delegation.
 */
class DelegateTool(private val context: Context) : Tool {
    override val name = "delegate"
    override val description = "Delegate a task to a named AI agent (e.g. coder, analyst, creative). Waits for result. Use when you need a different expert persona for a subtask."
    override val parameters = listOf(
        ToolParam("agent", "string", "Agent name to delegate to: 'coder', 'analyst', 'creative', 'tutor', 'brief', or a custom profile ID"),
        ToolParam("task", "string", "The task to delegate — describe exactly what you need the agent to do"),
        ToolParam("chat_id", "string", "Current chat ID for context isolation", required = false)
    )

    override suspend fun execute(args: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val agentName = args["agent"] ?: return@withContext ToolResult(false, "", "Missing 'agent' parameter")
        val task = args["task"] ?: return@withContext ToolResult(false, "", "Missing 'task' parameter")
        val chatId = args["chat_id"] ?: "delegate_${System.currentTimeMillis()}"

        val profileManager = AgentProfileManager.getInstance(context)
        val agentManager = MultiAgentManager.getInstance(context)

        // Find profile by ID or name (case-insensitive)
        val allProfiles = profileManager.getAllProfiles()
        val profile = allProfiles.firstOrNull {
            it.id.equals(agentName, ignoreCase = true) ||
            it.name.equals(agentName, ignoreCase = true)
        }
        val label = profile?.name ?: agentName

        ZeroClawService.log("DELEGATE: dispatching to $label — \"${task.take(60)}\"")

        // Build the delegated prompt with agent context
        val delegatedPrompt = if (profile != null) {
            "[Delegated task for $label]\n\n${profile.systemPrompt}\n\nTask: $task"
        } else {
            "[Delegated task for $agentName]\n\nTask: $task"
        }

        // Spawn with depth=1 to prevent sub-agents from spawning further agents
        val agentId = agentManager.spawn(
            parentChatId = chatId,
            task = delegatedPrompt,
            label = label,
            depth = 1  // prevents further delegation from this sub-agent
        )

        if (agentId == null) {
            return@withContext ToolResult(false, "",
                "Could not spawn delegate agent — max concurrent agents reached or depth limit hit.")
        }

        // Wait for the agent to complete (poll with short-circuit)
        val deadline = System.currentTimeMillis() + MultiAgentManager.AGENT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val agent = agentManager.getAgent(agentId)
            if (agent?.status == MultiAgentManager.AgentStatus.COMPLETED) {
                ZeroClawService.log("DELEGATE: $label completed — ${agent.result.length} chars")
                return@withContext ToolResult(true, "[$label result]\n${agent.result}")
            }
            if (agent?.status == MultiAgentManager.AgentStatus.FAILED ||
                agent?.status == MultiAgentManager.AgentStatus.CANCELLED) {
                return@withContext ToolResult(false, "", "$label failed: ${agent.result}")
            }
            kotlinx.coroutines.delay(500)
        }

        ToolResult(false, "", "Delegate agent $label timed out after ${MultiAgentManager.AGENT_TIMEOUT_MS / 1000}s")
    }
}
