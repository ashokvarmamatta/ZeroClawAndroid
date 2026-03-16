package ai.zeroclaw.android.tools

import android.content.Context
import ai.zeroclaw.android.data.MemoryDatabase
import ai.zeroclaw.android.data.MemoryEntity

/**
 * MemoryTool — persistent memory store/recall/forget per user.
 *
 * Actions:
 * - store: Save a key-value pair for the current user
 * - recall: Retrieve memories — by key, by search query, or list all
 * - forget: Delete a specific memory by key, or all memories for the user
 *
 * Memories persist across app restarts via Room/SQLite.
 */
class MemoryTool(private val context: Context) : Tool {

    override val name = "memory"

    override val description = "Store, recall, or forget persistent memories for the current user. " +
            "Use this to remember user preferences, facts, or context across conversations. " +
            "Actions: 'store' (save key+value), 'recall' (retrieve by key/search/all), 'forget' (delete by key or all)."

    override val parameters = listOf(
        ToolParam("action", "string", "One of: store, recall, forget"),
        ToolParam("key", "string", "Memory key/label (required for store and forget-by-key, optional for recall)", required = false),
        ToolParam("value", "string", "Memory content to store (required for store action)", required = false),
        ToolParam("query", "string", "Search query to find matching memories (optional for recall)", required = false),
        ToolParam("user_id", "string", "User/chat ID. If not provided, uses 'default'.", required = false)
    )

    private val db by lazy { MemoryDatabase.getInstance(context) }
    private val dao by lazy { db.memoryDao() }

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val action = args["action"]?.trim()?.lowercase()
            ?: return ToolResult(false, "", "Missing 'action' parameter. Use: store, recall, or forget.")

        val userId = args["user_id"]?.trim()?.ifBlank { "default" } ?: "default"

        return when (action) {
            "store" -> store(userId, args)
            "recall" -> recall(userId, args)
            "forget" -> forget(userId, args)
            else -> ToolResult(false, "", "Unknown action '$action'. Use: store, recall, or forget.")
        }
    }

    private suspend fun store(userId: String, args: Map<String, String>): ToolResult {
        val key = args["key"]?.trim()
            ?: return ToolResult(false, "", "Missing 'key' parameter for store action.")
        val value = args["value"]?.trim()
            ?: return ToolResult(false, "", "Missing 'value' parameter for store action.")

        if (key.isBlank()) return ToolResult(false, "", "Key cannot be blank.")
        if (value.isBlank()) return ToolResult(false, "", "Value cannot be blank.")

        val existing = dao.getByKey(userId, key)
        val now = System.currentTimeMillis()

        if (existing != null) {
            dao.upsert(existing.copy(value = value, updatedAt = now))
            return ToolResult(true, "Updated memory '$key' for user $userId.")
        } else {
            dao.upsert(MemoryEntity(userId = userId, key = key, value = value, createdAt = now, updatedAt = now))
            return ToolResult(true, "Stored new memory '$key' for user $userId.")
        }
    }

    private suspend fun recall(userId: String, args: Map<String, String>): ToolResult {
        val key = args["key"]?.trim()
        val query = args["query"]?.trim()

        // Recall by key
        if (!key.isNullOrBlank()) {
            val memory = dao.getByKey(userId, key)
                ?: return ToolResult(true, "No memory found with key '$key' for user $userId.")
            return ToolResult(true, formatMemory(memory))
        }

        // Search by query
        if (!query.isNullOrBlank()) {
            val results = dao.search(userId, query)
            if (results.isEmpty()) {
                return ToolResult(true, "No memories matching '$query' for user $userId.")
            }
            return ToolResult(true, formatMemories(results, "Memories matching '$query'"))
        }

        // List all
        val all = dao.getAllForUser(userId)
        if (all.isEmpty()) {
            return ToolResult(true, "No memories stored for user $userId.")
        }
        return ToolResult(true, formatMemories(all, "All memories for user $userId"))
    }

    private suspend fun forget(userId: String, args: Map<String, String>): ToolResult {
        val key = args["key"]?.trim()

        if (!key.isNullOrBlank()) {
            dao.getByKey(userId, key)
                ?: return ToolResult(true, "No memory with key '$key' found for user $userId.")
            dao.deleteByKey(userId, key)
            return ToolResult(true, "Forgotten memory '$key' for user $userId.")
        }

        // Forget all
        val count = dao.countForUser(userId)
        if (count == 0) {
            return ToolResult(true, "No memories to forget for user $userId.")
        }
        dao.deleteAllForUser(userId)
        return ToolResult(true, "Forgotten all $count memories for user $userId.")
    }

    private fun formatMemory(m: MemoryEntity): String {
        return "[${ m.key }]: ${m.value}"
    }

    private fun formatMemories(memories: List<MemoryEntity>, header: String): String {
        val sb = StringBuilder("$header (${memories.size}):\n\n")
        for (m in memories) {
            sb.appendLine("- [${m.key}]: ${m.value}")
        }
        return sb.toString().take(MAX_RESULT_LENGTH)
    }

    companion object {
        private const val MAX_RESULT_LENGTH = 4000
    }
}
