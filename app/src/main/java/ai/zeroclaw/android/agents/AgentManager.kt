package ai.zeroclaw.android.agents

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * AgentManager — singleton that persists and manages all user agents.
 * Follows the same SharedPreferences pattern as CronTool.
 */
class AgentManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("zeroclaw_agents", Context.MODE_PRIVATE)
    private val gson = GsonBuilder().serializeNulls().create()

    // ── CRUD ────────────────────────────────────────────────────────────────

    fun loadAll(): List<AgentConfig> {
        val json = prefs.getString(KEY_AGENTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<AgentConfig>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(agent: AgentConfig) {
        val list = loadAll().toMutableList()
        list.removeAll { it.id == agent.id }
        list.add(agent)
        persist(list)
    }

    fun delete(id: String) {
        val list = loadAll().toMutableList()
        list.removeAll { it.id == id }
        persist(list)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val list = loadAll().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(enabled = enabled)
            persist(list)
        }
    }

    fun createWebScraper(
        name: String,
        url: String,
        intervalMinutes: Int,
        channel: String,
        chatId: String,
        extractPrompt: String,
        onlyOnChange: Boolean,
        templateId: String? = null
    ): AgentConfig {
        val agent = AgentConfig(
            id = UUID.randomUUID().toString(),
            name = name,
            type = TYPE_WEB_SCRAPER,
            url = url,
            intervalMinutes = intervalMinutes.coerceAtLeast(5),
            channel = channel,
            chatId = chatId,
            extractPrompt = extractPrompt,
            onlyOnChange = onlyOnChange,
            enabled = true,
            createdAt = System.currentTimeMillis(),
            lastRunAt = 0L,
            lastContentHash = 0,
            lastStatus = "Not run yet",
            templateId = templateId
        )
        save(agent)
        return agent
    }

    /** Reset an agent to its template defaults (keeps channel/chatId). */
    fun resetToTemplate(id: String): Boolean {
        val list = loadAll().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val agent = list[idx]
        val tplId = agent.templateId ?: return false
        val template = AGENT_TEMPLATES.firstOrNull { it.id == tplId } ?: return false
        list[idx] = agent.copy(
            name = template.name,
            url = template.url,
            extractPrompt = template.extractPrompt,
            intervalMinutes = template.intervalMinutes,
            onlyOnChange = template.onlyOnChange,
            lastStatus = "Reset to defaults"
        )
        persist(list)
        return true
    }

    // ── Scheduling ──────────────────────────────────────────────────────────

    /** Returns all enabled agents whose interval has elapsed. */
    fun getDueAgents(): List<AgentConfig> {
        val now = System.currentTimeMillis()
        return loadAll().filter { agent ->
            agent.enabled &&
            (now - agent.lastRunAt) >= agent.intervalMinutes * 60_000L
        }
    }

    /** Update lastRunAt, lastContentHash, and lastStatus after a run. */
    fun markRun(id: String, contentHash: Int, status: String) {
        val list = loadAll().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(
                lastRunAt = System.currentTimeMillis(),
                lastContentHash = contentHash,
                lastStatus = status
            )
            persist(list)
        }
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    private fun persist(list: List<AgentConfig>) {
        prefs.edit().putString(KEY_AGENTS, gson.toJson(list)).apply()
    }

    companion object {
        const val TYPE_WEB_SCRAPER = "web_scraper"

        private const val KEY_AGENTS = "agents_v1"

        @Volatile private var INSTANCE: AgentManager? = null

        fun getInstance(context: Context): AgentManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgentManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
