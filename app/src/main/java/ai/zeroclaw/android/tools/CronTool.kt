package ai.zeroclaw.android.tools

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CronTool — schedule recurring AI tasks per user.
 *
 * Actions:
 * - schedule: Create a new scheduled task (name, interval in minutes, prompt)
 * - list: List all scheduled tasks for the user
 * - cancel: Cancel a scheduled task by name
 * - run_due: Execute all tasks whose interval has elapsed (called by the service loop)
 *
 * Tasks are persisted in SharedPreferences. The service daemon checks
 * and executes due tasks each polling cycle.
 */
class CronTool(private val context: Context) : Tool {

    override val name = "cron"

    override val description = "Schedule recurring AI tasks. " +
            "Actions: 'schedule' (create a task with name, interval_minutes, prompt), " +
            "'list' (show all tasks), 'cancel' (remove a task by name)."

    override val parameters = listOf(
        ToolParam("action", "string", "One of: schedule, list, cancel"),
        ToolParam("task_name", "string", "Name/label for the task (required for schedule and cancel)", required = false),
        ToolParam("interval_minutes", "string", "How often to run in minutes (required for schedule)", required = false),
        ToolParam("prompt", "string", "The AI prompt to run on each interval (required for schedule)", required = false),
        ToolParam("user_id", "string", "User/chat ID. If not provided, uses 'default'.", required = false)
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("zeroclaw_cron", Context.MODE_PRIVATE)
    private val gson = GsonBuilder().serializeNulls().create()

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val action = args["action"]?.trim()?.lowercase()
            ?: return ToolResult(false, "", "Missing 'action' parameter. Use: schedule, list, or cancel.")
        val userId = args["user_id"]?.trim()?.ifBlank { "default" } ?: "default"

        return when (action) {
            "schedule" -> schedule(userId, args)
            "list" -> list(userId)
            "cancel" -> cancel(userId, args)
            else -> ToolResult(false, "", "Unknown action '$action'. Use: schedule, list, or cancel.")
        }
    }

    private fun schedule(userId: String, args: Map<String, String>): ToolResult {
        val name = args["task_name"]?.trim()
            ?: return ToolResult(false, "", "Missing 'task_name' parameter.")
        val intervalStr = args["interval_minutes"]?.trim()
            ?: return ToolResult(false, "", "Missing 'interval_minutes' parameter.")
        val prompt = args["prompt"]?.trim()
            ?: return ToolResult(false, "", "Missing 'prompt' parameter.")

        val interval = intervalStr.toIntOrNull()
            ?: return ToolResult(false, "", "'interval_minutes' must be a number.")

        if (interval < 1) return ToolResult(false, "", "Interval must be at least 1 minute.")
        if (interval > 10080) return ToolResult(false, "", "Interval cannot exceed 10080 minutes (7 days).")

        val tasks = loadTasks(userId).toMutableList()
        // Replace existing task with same name
        tasks.removeAll { it.name.equals(name, ignoreCase = true) }

        tasks.add(CronTask(
            name = name,
            userId = userId,
            prompt = prompt,
            intervalMinutes = interval,
            createdAt = System.currentTimeMillis(),
            lastRunAt = 0L
        ))

        saveTasks(userId, tasks)
        return ToolResult(true, "Scheduled task '$name' to run every $interval minute(s).\nPrompt: $prompt")
    }

    private fun list(userId: String): ToolResult {
        val tasks = loadTasks(userId)
        if (tasks.isEmpty()) {
            return ToolResult(true, "No scheduled tasks for user $userId.")
        }

        val sb = StringBuilder("Scheduled tasks for user $userId (${tasks.size}):\n\n")
        for (t in tasks) {
            val lastRun = if (t.lastRunAt > 0) {
                val ago = (System.currentTimeMillis() - t.lastRunAt) / 60000
                "${ago}m ago"
            } else "never"
            sb.appendLine("- [${t.name}] every ${t.intervalMinutes}m (last run: $lastRun)")
            sb.appendLine("  Prompt: ${t.prompt.take(100)}")
        }
        return ToolResult(true, sb.toString())
    }

    private fun cancel(userId: String, args: Map<String, String>): ToolResult {
        val name = args["task_name"]?.trim()
            ?: return ToolResult(false, "", "Missing 'task_name' parameter.")

        val tasks = loadTasks(userId).toMutableList()
        val removed = tasks.removeAll { it.name.equals(name, ignoreCase = true) }

        if (!removed) {
            return ToolResult(true, "No task named '$name' found for user $userId.")
        }

        saveTasks(userId, tasks)
        return ToolResult(true, "Cancelled task '$name'.")
    }

    // ── Public methods for the service daemon ──────────────────────────

    /**
     * Get all due tasks across all users. Called by the service polling loop.
     */
    fun getDueTasks(): List<CronTask> {
        val due = mutableListOf<CronTask>()
        val allKeys = prefs.all.keys.filter { it.startsWith("tasks_") }

        for (key in allKeys) {
            val userId = key.removePrefix("tasks_")
            val tasks = loadTasks(userId)
            val now = System.currentTimeMillis()

            for (task in tasks) {
                val elapsed = now - task.lastRunAt
                if (elapsed >= task.intervalMinutes * 60_000L) {
                    due.add(task)
                }
            }
        }
        return due
    }

    /**
     * Mark a task as just executed.
     */
    fun markRun(task: CronTask) {
        val tasks = loadTasks(task.userId).toMutableList()
        val idx = tasks.indexOfFirst { it.name.equals(task.name, ignoreCase = true) }
        if (idx >= 0) {
            tasks[idx] = tasks[idx].copy(lastRunAt = System.currentTimeMillis())
            saveTasks(task.userId, tasks)
        }
    }

    // ── Persistence ────────────────────────────────────────────────────

    private fun loadTasks(userId: String): List<CronTask> {
        val json = prefs.getString("tasks_$userId", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<CronTask>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveTasks(userId: String, tasks: List<CronTask>) {
        prefs.edit().putString("tasks_$userId", gson.toJson(tasks)).apply()
    }

    data class CronTask(
        val name: String,
        val userId: String,
        val prompt: String,
        val intervalMinutes: Int,
        val createdAt: Long,
        val lastRunAt: Long
    )
}
