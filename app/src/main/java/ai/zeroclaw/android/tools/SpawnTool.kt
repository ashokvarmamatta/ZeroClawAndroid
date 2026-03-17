package ai.zeroclaw.android.tools

import android.content.Context
import ai.zeroclaw.android.ai.MultiAgentManager
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SpawnTool — Phase 142: Spawn a background sub-agent and return immediately.
 *
 * Unlike DelegateTool (which waits for completion), SpawnTool fires off the agent
 * and returns a task_id immediately. The agent runs in the background and results
 * can be collected later via the task_id.
 *
 * This allows the main AI to kick off multiple parallel tasks without blocking.
 *
 * Disabled by default — enable in Settings for parallel agent workflows.
 */
class SpawnTool(private val context: Context) : Tool {
    override val name = "spawn"
    override val description = "Spawn a background AI agent for a task. Returns task_id immediately — agent runs in background. Use for fire-and-forget parallel tasks. Check status with 'status' action."
    override val parameters = listOf(
        ToolParam("action", "string", "What to do: 'run' (spawn new agent), 'status' (check task), 'list' (all tasks), 'collect' (get result of completed task)"),
        ToolParam("task", "string", "Task description for the agent to work on (for 'run' action)", required = false),
        ToolParam("label", "string", "Human-readable label for this task (for 'run' action)", required = false),
        ToolParam("task_id", "string", "Agent task ID to check/collect (for 'status' and 'collect' actions)", required = false),
        ToolParam("chat_id", "string", "Current chat ID for scoping (optional)", required = false)
    )

    override suspend fun execute(args: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val agentManager = MultiAgentManager.getInstance(context)

        when (val action = args["action"] ?: "run") {
            "run" -> {
                val task = args["task"] ?: return@withContext ToolResult(false, "", "Missing 'task' parameter")
                val label = args["label"] ?: "spawned-agent"
                val chatId = args["chat_id"] ?: "spawn_${System.currentTimeMillis()}"

                val agentId = agentManager.spawn(
                    parentChatId = chatId,
                    task = task,
                    label = label,
                    depth = 1  // spawned agents cannot spawn further agents
                )

                if (agentId == null) {
                    return@withContext ToolResult(false, "",
                        "Cannot spawn agent — max concurrent agents (${MultiAgentManager.MAX_CONCURRENT_AGENTS}) reached.")
                }

                ZeroClawService.log("SPAWN: spawned $agentId ($label) async")
                ToolResult(true, "Agent spawned. Task ID: $agentId\nLabel: $label\nStatus: RUNNING\n\nUse spawn action='status' task_id='$agentId' to check progress.")
            }

            "status" -> {
                val taskId = args["task_id"] ?: return@withContext ToolResult(false, "", "Missing 'task_id'")
                val agent = agentManager.getAgent(taskId)
                    ?: return@withContext ToolResult(false, "", "Task '$taskId' not found.")
                val elapsed = (System.currentTimeMillis() - agent.startedAt) / 1000
                ToolResult(true, "Task $taskId (${agent.label})\nStatus: ${agent.status}\nElapsed: ${elapsed}s\n" +
                    if (agent.status == MultiAgentManager.AgentStatus.COMPLETED ||
                        agent.status == MultiAgentManager.AgentStatus.FAILED)
                        "\nResult preview: ${agent.result.take(200)}…"
                    else "")
            }

            "collect" -> {
                val taskId = args["task_id"] ?: return@withContext ToolResult(false, "", "Missing 'task_id'")
                val agent = agentManager.getAgent(taskId)
                    ?: return@withContext ToolResult(false, "", "Task '$taskId' not found.")
                if (agent.status == MultiAgentManager.AgentStatus.RUNNING ||
                    agent.status == MultiAgentManager.AgentStatus.PENDING) {
                    return@withContext ToolResult(false, "", "Task $taskId is still ${agent.status}. Wait for it to complete.")
                }
                ToolResult(true, "Task $taskId (${agent.label}) — ${agent.status}\n\n${agent.result}")
            }

            "list" -> {
                val chatId = args["chat_id"]
                val agents = agentManager.listAgents(chatId)
                if (agents.isEmpty()) {
                    return@withContext ToolResult(true, "No active or recent agent tasks.")
                }
                val list = agents.joinToString("\n") { a ->
                    val elapsed = (System.currentTimeMillis() - a.startedAt) / 1000
                    "• ${a.id} (${a.label}) — ${a.status} — ${elapsed}s ago"
                }
                ToolResult(true, "Agent tasks:\n$list")
            }

            else -> ToolResult(false, "", "Unknown action '$action'. Use: run, status, collect, list.")
        }
    }
}
