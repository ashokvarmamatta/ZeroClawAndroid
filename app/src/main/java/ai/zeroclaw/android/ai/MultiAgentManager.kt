package ai.zeroclaw.android.ai

import android.content.Context
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * MultiAgentManager — Phase 112: Spawn sub-agents for parallel tasks.
 *
 * Allows the main AI to spawn lightweight sub-agents for parallel task execution.
 * Each sub-agent gets its own chat context, runs concurrently, and reports results
 * back to the parent. Inspired by OpenClaw's subagent-spawn/registry system.
 *
 * Example: "Research 3 topics simultaneously" → spawns 3 sub-agents, merges results.
 */
class MultiAgentManager(private val context: Context) {

    data class SubAgent(
        val id: String,
        val parentChatId: String,
        val task: String,
        val label: String,
        val status: AgentStatus = AgentStatus.PENDING,
        val result: String = "",
        val startedAt: Long = System.currentTimeMillis(),
        val completedAt: Long = 0
    )

    enum class AgentStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

    companion object {
        const val MAX_CONCURRENT_AGENTS = 5
        const val MAX_DEPTH = 3            // prevent infinite agent spawning
        const val AGENT_TIMEOUT_MS = 120_000L  // 2 minutes per sub-agent

        @Volatile private var INSTANCE: MultiAgentManager? = null
        fun getInstance(context: Context): MultiAgentManager {
            return INSTANCE ?: synchronized(this) {
                MultiAgentManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val agents = ConcurrentHashMap<String, SubAgent>()
    private val idCounter = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Spawn a sub-agent to perform a task. Returns the agent ID.
     */
    fun spawn(
        parentChatId: String,
        task: String,
        label: String = "sub-agent",
        depth: Int = 0
    ): String? {
        if (depth >= MAX_DEPTH) {
            ZeroClawService.log("AGENT: max depth ($MAX_DEPTH) reached — refusing to spawn")
            return null
        }

        val activeCount = agents.values.count { it.status == AgentStatus.RUNNING }
        if (activeCount >= MAX_CONCURRENT_AGENTS) {
            ZeroClawService.log("AGENT: max concurrent ($MAX_CONCURRENT_AGENTS) reached — queueing")
            return null
        }

        val agentId = "agent_${idCounter.incrementAndGet()}"
        val agent = SubAgent(
            id = agentId,
            parentChatId = parentChatId,
            task = task,
            label = label,
            status = AgentStatus.RUNNING
        )
        agents[agentId] = agent
        ZeroClawService.log("AGENT: spawned $agentId ($label) — \"${task.take(60)}...\"")

        scope.launch {
            try {
                withTimeout(AGENT_TIMEOUT_MS) {
                    val llmRouter = LlmRouter.getInstance(context)
                    // Sub-agent uses its own chat context (prefixed to avoid collision)
                    val subChatId = "subagent_${agentId}_${parentChatId}"
                    val result = llmRouter.call(task, chatId = subChatId)

                    agents[agentId] = agent.copy(
                        status = AgentStatus.COMPLETED,
                        result = result,
                        completedAt = System.currentTimeMillis()
                    )
                    ZeroClawService.log("AGENT: $agentId ($label) completed — ${result.length} chars")
                }
            } catch (e: TimeoutCancellationException) {
                agents[agentId] = agent.copy(
                    status = AgentStatus.FAILED,
                    result = "Timed out after ${AGENT_TIMEOUT_MS / 1000}s",
                    completedAt = System.currentTimeMillis()
                )
                ZeroClawService.log("AGENT: $agentId ($label) timed out")
            } catch (e: Exception) {
                agents[agentId] = agent.copy(
                    status = AgentStatus.FAILED,
                    result = "Error: ${e.message}",
                    completedAt = System.currentTimeMillis()
                )
                ZeroClawService.log("AGENT: $agentId ($label) failed — ${e.message}")
            }
        }

        return agentId
    }

    /**
     * Spawn multiple sub-agents for parallel tasks and wait for all to complete.
     * Returns a list of results in the same order as the tasks.
     */
    suspend fun spawnAndWait(
        parentChatId: String,
        tasks: List<Pair<String, String>>,  // label to task
        timeoutMs: Long = AGENT_TIMEOUT_MS
    ): List<Pair<String, String>> {
        val agentIds = tasks.mapNotNull { (label, task) ->
            spawn(parentChatId, task, label)?.let { label to it }
        }

        // Poll for completion
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val allDone = agentIds.all { (_, id) ->
                val a = agents[id]
                a?.status == AgentStatus.COMPLETED || a?.status == AgentStatus.FAILED
            }
            if (allDone) break
            delay(500)
        }

        return agentIds.map { (label, id) ->
            val agent = agents[id]
            label to (agent?.result ?: "No result")
        }
    }

    /**
     * Get status of a specific agent.
     */
    fun getAgent(agentId: String): SubAgent? = agents[agentId]

    /**
     * List all agents (optionally for a parent chat).
     */
    fun listAgents(parentChatId: String? = null): List<SubAgent> {
        return if (parentChatId != null) {
            agents.values.filter { it.parentChatId == parentChatId }
        } else {
            agents.values.toList()
        }.sortedByDescending { it.startedAt }
    }

    /**
     * Cancel a running agent.
     */
    fun cancel(agentId: String): Boolean {
        val agent = agents[agentId] ?: return false
        if (agent.status != AgentStatus.RUNNING) return false
        agents[agentId] = agent.copy(
            status = AgentStatus.CANCELLED,
            result = "Cancelled by user",
            completedAt = System.currentTimeMillis()
        )
        ZeroClawService.log("AGENT: $agentId cancelled")
        return true
    }

    /**
     * Clean up completed/failed agents older than the given age.
     */
    fun sweep(maxAgeMs: Long = 300_000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        agents.entries.removeIf { (_, agent) ->
            agent.status != AgentStatus.RUNNING && agent.completedAt < cutoff
        }
    }

    fun shutdown() {
        scope.cancel()
        agents.clear()
    }
}
