package ai.zeroclaw.android.memory

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import ai.zeroclaw.android.data.MemoryDatabase
import ai.zeroclaw.android.data.MemoryEntity
import ai.zeroclaw.android.data.LlmRouter
import ai.zeroclaw.android.data.appDataStore
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * SessionManager — Phase 122: Named sessions, session switching, session export.
 *
 * Allows users to create named sessions (e.g. "work", "personal", "project_x")
 * and switch between them. Each session has isolated conversation history and
 * memory. Sessions can be exported as JSON or summarized.
 *
 * Inspired by OpenClaw's context-engine session architecture.
 */
class SessionManager(private val context: Context) {

    data class Session(
        val id: String,
        val name: String,
        val description: String = "",
        val userId: String,
        val createdAt: Long = System.currentTimeMillis(),
        val lastActiveAt: Long = System.currentTimeMillis(),
        val messageCount: Int = 0,
        val memoryCount: Int = 0,
        val tags: List<String> = emptyList()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("description", description)
            put("userId", userId)
            put("createdAt", createdAt)
            put("lastActiveAt", lastActiveAt)
            put("messageCount", messageCount)
            put("memoryCount", memoryCount)
            put("tags", JSONArray(tags))
        }

        companion object {
            fun fromJson(json: JSONObject): Session {
                val tagsArr = json.optJSONArray("tags")
                val tags = if (tagsArr != null) {
                    (0 until tagsArr.length()).map { tagsArr.getString(it) }
                } else emptyList()
                return Session(
                    id = json.optString("id", ""),
                    name = json.optString("name", ""),
                    description = json.optString("description", ""),
                    userId = json.optString("userId", ""),
                    createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                    lastActiveAt = json.optLong("lastActiveAt", System.currentTimeMillis()),
                    messageCount = json.optInt("messageCount", 0),
                    memoryCount = json.optInt("memoryCount", 0),
                    tags = tags
                )
            }
        }
    }

    companion object {
        private val KEY_SESSIONS = stringPreferencesKey("named_sessions")
        private val KEY_ACTIVE_SESSIONS = stringPreferencesKey("active_user_sessions")

        const val DEFAULT_SESSION = "default"

        @Volatile private var INSTANCE: SessionManager? = null
        fun getInstance(context: Context): SessionManager {
            return INSTANCE ?: synchronized(this) {
                SessionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Create a new named session for a user.
     */
    suspend fun createSession(
        userId: String,
        name: String,
        description: String = "",
        tags: List<String> = emptyList()
    ): Session {
        val sessionId = "${name.lowercase().replace(Regex("[^a-z0-9]"), "_")}_${System.currentTimeMillis() % 10000}"
        val session = Session(
            id = sessionId,
            name = name,
            description = description,
            userId = userId,
            tags = tags
        )
        saveSession(session)
        ZeroClawService.log("SESSION: created '$name' ($sessionId) for $userId")
        return session
    }

    /**
     * Save/update a session.
     */
    private suspend fun saveSession(session: Session) {
        context.appDataStore.edit { prefs ->
            val existing = runCatching { JSONArray(prefs[KEY_SESSIONS] ?: "[]") }.getOrDefault(JSONArray())
            val updated = JSONArray()
            var replaced = false
            for (i in 0 until existing.length()) {
                val s = existing.getJSONObject(i)
                if (s.optString("id") == session.id) {
                    updated.put(session.toJson())
                    replaced = true
                } else {
                    updated.put(s)
                }
            }
            if (!replaced) updated.put(session.toJson())
            prefs[KEY_SESSIONS] = updated.toString()
        }
    }

    /**
     * Get all sessions for a user.
     */
    suspend fun getSessions(userId: String): List<Session> {
        val prefs = context.appDataStore.data.first()
        val arr = runCatching { JSONArray(prefs[KEY_SESSIONS] ?: "[]") }.getOrDefault(JSONArray())
        return (0 until arr.length())
            .map { Session.fromJson(arr.getJSONObject(it)) }
            .filter { it.userId == userId }
            .sortedByDescending { it.lastActiveAt }
    }

    /**
     * Get the active session ID for a user (defaults to "default").
     */
    suspend fun getActiveSessionId(userId: String): String {
        val prefs = context.appDataStore.data.first()
        val activeMap = runCatching { JSONObject(prefs[KEY_ACTIVE_SESSIONS] ?: "{}") }.getOrDefault(JSONObject())
        return activeMap.optString(userId, DEFAULT_SESSION)
    }

    /**
     * Switch the active session for a user.
     * Also updates the LlmRouter to use the session-scoped chat ID.
     */
    suspend fun switchSession(userId: String, sessionId: String): String {
        context.appDataStore.edit { prefs ->
            val map = runCatching { JSONObject(prefs[KEY_ACTIVE_SESSIONS] ?: "{}") }.getOrDefault(JSONObject())
            map.put(userId, sessionId)
            prefs[KEY_ACTIVE_SESSIONS] = map.toString()
        }
        ZeroClawService.log("SESSION: $userId switched to '$sessionId'")
        return sessionId
    }

    /**
     * Get the chat ID to use for LlmRouter given user + current session.
     * Format: "userId_sessionId" so each session has its own history.
     */
    suspend fun resolveChatId(baseChatId: String): String {
        val sessionId = getActiveSessionId(baseChatId)
        return if (sessionId == DEFAULT_SESSION) baseChatId else "${baseChatId}_session_$sessionId"
    }

    /**
     * Delete a session and optionally its memories.
     */
    suspend fun deleteSession(userId: String, sessionId: String, deleteMemories: Boolean = false) {
        context.appDataStore.edit { prefs ->
            val arr = runCatching { JSONArray(prefs[KEY_SESSIONS] ?: "[]") }.getOrDefault(JSONArray())
            val updated = JSONArray()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                if (!(s.optString("id") == sessionId && s.optString("userId") == userId)) {
                    updated.put(s)
                }
            }
            prefs[KEY_SESSIONS] = updated.toString()
        }
        ZeroClawService.log("SESSION: deleted '$sessionId' for $userId")
    }

    /**
     * Export a session as a JSON string (conversation + memories).
     */
    suspend fun exportSession(userId: String, sessionId: String): String {
        val llmRouter = LlmRouter.getInstance(context)
        val chatId = if (sessionId == DEFAULT_SESSION) userId else "${userId}_session_$sessionId"
        val history = llmRouter.getHistory(chatId)
        val memories = MemoryDatabase.getInstance(context).memoryDao().getAllForSession(userId, sessionId)

        val export = JSONObject().apply {
            put("sessionId", sessionId)
            put("userId", userId)
            put("exportedAt", System.currentTimeMillis())
            put("conversation", JSONArray().apply {
                history.forEach { msg ->
                    put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                    })
                }
            })
            put("memories", JSONArray().apply {
                memories.forEach { mem ->
                    put(JSONObject().apply {
                        put("key", mem.key)
                        put("value", mem.value)
                        put("tags", mem.tags)
                        put("createdAt", mem.createdAt)
                        put("updatedAt", mem.updatedAt)
                    })
                }
            })
        }
        return export.toString(2)
    }

    /**
     * List sessions in a readable format for the AI to report to users.
     */
    suspend fun formatSessionList(userId: String): String {
        val sessions = getSessions(userId)
        val activeId = getActiveSessionId(userId)

        if (sessions.isEmpty()) {
            return "No named sessions. Using default session.\n\nCreate one: 'create session Work' or 'new session Project X'"
        }

        return buildString {
            appendLine("📋 Sessions for $userId:")
            val defaultActive = if (activeId == DEFAULT_SESSION) " ← active" else ""
            appendLine("• default — Default session$defaultActive")
            sessions.forEach { s ->
                val active = if (s.id == activeId) " ← active" else ""
                val desc = if (s.description.isNotBlank()) " — ${s.description}" else ""
                appendLine("• ${s.name} (${s.id})$desc$active")
            }
            appendLine("\nSwitch: 'switch to session <name>'")
            appendLine("Export: 'export session <name>'")
        }
    }
}
