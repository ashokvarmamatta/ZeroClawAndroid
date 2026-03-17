package ai.zeroclaw.android.infra

import android.content.Context
import ai.zeroclaw.android.data.AppSettings
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * A2AServer — Phase 148: Agent-to-Agent (A2A) protocol server.
 *
 * Google's A2A spec (https://google.github.io/A2A/) defines how agents communicate:
 * - GET /.well-known/agent-card.json — describes this agent's capabilities
 * - POST /a2a — receives task requests as JSON-RPC 2.0
 *
 * A2A tasks are long-running and go through states: submitted → working → completed.
 * Disabled by default — requires web chat server enabled first.
 *
 * Integration: A2AServer hooks into WebChatServer's HTTP handler.
 */
class A2AServer(private val context: Context) {

    enum class TaskState { SUBMITTED, WORKING, COMPLETED, FAILED, CANCELLED }

    data class A2ATask(
        val id: String,
        var state: TaskState = TaskState.SUBMITTED,
        val query: String,
        var result: String = "",
        val createdAt: Long = System.currentTimeMillis()
    )

    private val tasks = ConcurrentHashMap<String, A2ATask>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var agentName = "ZeroClaw"
    private var agentVersion = "1.0"

    companion object {
        @Volatile private var INSTANCE: A2AServer? = null
        fun getInstance(context: Context): A2AServer =
            INSTANCE ?: synchronized(this) {
                A2AServer(context.applicationContext).also { INSTANCE = it }
            }
    }

    /** Build the agent card JSON (describes this agent to other agents). */
    fun buildAgentCard(): JSONObject {
        return JSONObject().apply {
            put("name", agentName)
            put("version", agentVersion)
            put("description", "ZeroClaw AI agent — 30+ tools, multi-channel, multi-provider")
            put("url", ZeroClawService.tunnelUrl ?: "http://localhost:8088")
            put("a2a_endpoint", "${ZeroClawService.tunnelUrl ?: "http://localhost:8088"}/a2a")
            put("capabilities", org.json.JSONArray().apply {
                put("task_submission")
                put("task_status")
                put("tool_calling")
            })
            put("input_modes", org.json.JSONArray().apply { put("text") })
            put("output_modes", org.json.JSONArray().apply { put("text") })
        }
    }

    /**
     * Handle an incoming A2A JSON-RPC 2.0 request.
     * Returns the JSON-RPC response as a string.
     */
    fun handleRequest(requestBody: String): String {
        return try {
            val req = JSONObject(requestBody)
            val method = req.optString("method", "")
            val id = req.opt("id")
            val params = req.optJSONObject("params") ?: JSONObject()

            when (method) {
                "task/submit" -> handleTaskSubmit(id, params)
                "task/status" -> handleTaskStatus(id, params)
                "task/cancel" -> handleTaskCancel(id, params)
                else -> errorResponse(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            errorResponse(null, -32700, "Parse error: ${e.message}")
        }
    }

    private fun handleTaskSubmit(reqId: Any?, params: JSONObject): String {
        val query = params.optString("query", "").ifBlank {
            return errorResponse(reqId, -32602, "Missing 'query' parameter")
        }
        val taskId = "a2a_${System.currentTimeMillis()}"
        val task = A2ATask(id = taskId, query = query)
        tasks[taskId] = task

        // Process the task asynchronously
        scope.launch {
            task.state = TaskState.WORKING
            try {
                val llmRouter = LlmRouter.getInstance(context)
                val result = llmRouter.call(
                    userMessage = "[A2A task] $query",
                    chatId = "a2a_$taskId"
                )
                tasks[taskId] = task.copy(state = TaskState.COMPLETED, result = result)
                ZeroClawService.log("A2A: task $taskId completed — ${result.length} chars")
            } catch (e: Exception) {
                tasks[taskId] = task.copy(state = TaskState.FAILED, result = "Error: ${e.message}")
                ZeroClawService.log("A2A: task $taskId failed — ${e.message}")
            }
        }

        ZeroClawService.log("A2A: task $taskId submitted — \"${query.take(60)}\"")
        return successResponse(reqId, JSONObject().apply {
            put("task_id", taskId)
            put("state", TaskState.SUBMITTED.name)
        })
    }

    private fun handleTaskStatus(reqId: Any?, params: JSONObject): String {
        val taskId = params.optString("task_id", "")
        val task = tasks[taskId] ?: return errorResponse(reqId, -32602, "Task not found: $taskId")
        return successResponse(reqId, JSONObject().apply {
            put("task_id", taskId)
            put("state", task.state.name)
            if (task.state == TaskState.COMPLETED || task.state == TaskState.FAILED) {
                put("result", task.result)
            }
        })
    }

    private fun handleTaskCancel(reqId: Any?, params: JSONObject): String {
        val taskId = params.optString("task_id", "")
        val task = tasks[taskId] ?: return errorResponse(reqId, -32602, "Task not found: $taskId")
        tasks[taskId] = task.copy(state = TaskState.CANCELLED)
        return successResponse(reqId, JSONObject().apply { put("cancelled", true) })
    }

    private fun successResponse(id: Any?, result: JSONObject): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString()
    }

    private fun errorResponse(id: Any?, code: Int, message: String): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
        }.toString()
    }

    fun shutdown() {
        scope.cancel()
        tasks.clear()
    }
}
