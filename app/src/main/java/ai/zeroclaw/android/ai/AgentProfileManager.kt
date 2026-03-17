package ai.zeroclaw.android.ai

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import ai.zeroclaw.android.data.appDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * AgentProfileManager — Phase 113: Named AI personas with different prompts/tools/models.
 *
 * Agent profiles let users create named personas (e.g., "Coder", "Creative Writer",
 * "Customer Support") each with their own system prompt, preferred model, enabled tools,
 * and personality traits. Users can switch profiles per-channel or per-chat.
 *
 * Inspired by OpenClaw's IDENTITY.md + agent config system.
 */
class AgentProfileManager(private val context: Context) {

    data class AgentProfile(
        val id: String,
        val name: String,
        val emoji: String = "🤖",
        val systemPrompt: String = "",
        val preferredModel: String = "",     // empty = use default from key manager
        val preferredProvider: String = "",   // empty = use default failover
        val enabledTools: List<String> = emptyList(), // empty = all tools
        val maxTokens: Int = 1000,
        val temperature: Double = 0.7
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("emoji", emoji)
            put("systemPrompt", systemPrompt)
            put("preferredModel", preferredModel)
            put("preferredProvider", preferredProvider)
            put("enabledTools", JSONArray(enabledTools))
            put("maxTokens", maxTokens)
            put("temperature", temperature)
        }

        companion object {
            fun fromJson(json: JSONObject): AgentProfile {
                val tools = mutableListOf<String>()
                val toolsArray = json.optJSONArray("enabledTools")
                if (toolsArray != null) {
                    for (i in 0 until toolsArray.length()) {
                        tools.add(toolsArray.getString(i))
                    }
                }
                return AgentProfile(
                    id = json.optString("id", ""),
                    name = json.optString("name", ""),
                    emoji = json.optString("emoji", "🤖"),
                    systemPrompt = json.optString("systemPrompt", ""),
                    preferredModel = json.optString("preferredModel", ""),
                    preferredProvider = json.optString("preferredProvider", ""),
                    enabledTools = tools,
                    maxTokens = json.optInt("maxTokens", 1000),
                    temperature = json.optDouble("temperature", 0.7)
                )
            }
        }
    }

    companion object {
        private val KEY_PROFILES = stringPreferencesKey("agent_profiles")
        private val KEY_ACTIVE_PROFILE = stringPreferencesKey("active_agent_profile")
        private val KEY_CHANNEL_PROFILES = stringPreferencesKey("channel_agent_profiles")

        // Built-in profiles
        val BUILTIN_PROFILES = listOf(
            AgentProfile(
                id = "default",
                name = "ZeroClaw",
                emoji = "🦀",
                systemPrompt = SystemPromptManager.DEFAULT_PROMPT
            ),
            AgentProfile(
                id = "coder",
                name = "Code Assistant",
                emoji = "💻",
                systemPrompt = "You are a senior software engineer assistant. " +
                    "Write clean, efficient, well-documented code. " +
                    "Explain your reasoning. Use best practices and design patterns. " +
                    "When debugging, identify root causes, not just symptoms.",
                maxTokens = 2000
            ),
            AgentProfile(
                id = "creative",
                name = "Creative Writer",
                emoji = "✨",
                systemPrompt = "You are a creative writing assistant with a vivid imagination. " +
                    "Write engaging, original content with strong narrative voice. " +
                    "Use literary techniques: metaphor, imagery, dialogue, pacing. " +
                    "Be bold and creative while maintaining quality.",
                temperature = 0.9,
                maxTokens = 1500
            ),
            AgentProfile(
                id = "analyst",
                name = "Data Analyst",
                emoji = "📊",
                systemPrompt = "You are a precise data analyst. " +
                    "Break down problems with data-driven reasoning. " +
                    "Use numbers, statistics, and structured analysis. " +
                    "Present findings in clear, organized formats with tables and bullet points.",
                temperature = 0.3,
                maxTokens = 1500
            ),
            AgentProfile(
                id = "tutor",
                name = "Patient Tutor",
                emoji = "📚",
                systemPrompt = "You are a patient, encouraging tutor. " +
                    "Explain concepts step by step, starting from fundamentals. " +
                    "Use analogies and examples. Check understanding before moving on. " +
                    "Celebrate progress and never make the student feel bad for not knowing something.",
                temperature = 0.6
            ),
            AgentProfile(
                id = "concise",
                name = "Brief Mode",
                emoji = "⚡",
                systemPrompt = "You are an ultra-concise assistant. " +
                    "Answer in the fewest words possible. No fluff, no filler. " +
                    "Use bullet points. Skip greetings and pleasantries. " +
                    "If you can answer in one word, do it.",
                maxTokens = 500,
                temperature = 0.3
            )
        )

        @Volatile private var INSTANCE: AgentProfileManager? = null
        fun getInstance(context: Context): AgentProfileManager {
            return INSTANCE ?: synchronized(this) {
                AgentProfileManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Get all profiles (built-in + user-created).
     */
    suspend fun getAllProfiles(): List<AgentProfile> {
        val prefs = context.appDataStore.data.first()
        val customJson = prefs[KEY_PROFILES] ?: "[]"
        val customProfiles = runCatching {
            val arr = JSONArray(customJson)
            (0 until arr.length()).map { AgentProfile.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        return BUILTIN_PROFILES + customProfiles
    }

    /**
     * Save a new custom profile or update existing.
     */
    suspend fun saveProfile(profile: AgentProfile) {
        context.appDataStore.edit { prefs ->
            val existing = runCatching { JSONArray(prefs[KEY_PROFILES] ?: "[]") }.getOrDefault(JSONArray())
            val updated = JSONArray()
            var replaced = false
            for (i in 0 until existing.length()) {
                val p = existing.getJSONObject(i)
                if (p.optString("id") == profile.id) {
                    updated.put(profile.toJson())
                    replaced = true
                } else {
                    updated.put(p)
                }
            }
            if (!replaced) updated.put(profile.toJson())
            prefs[KEY_PROFILES] = updated.toString()
        }
    }

    /**
     * Delete a custom profile by ID.
     */
    suspend fun deleteProfile(profileId: String) {
        context.appDataStore.edit { prefs ->
            val existing = runCatching { JSONArray(prefs[KEY_PROFILES] ?: "[]") }.getOrDefault(JSONArray())
            val updated = JSONArray()
            for (i in 0 until existing.length()) {
                val p = existing.getJSONObject(i)
                if (p.optString("id") != profileId) updated.put(p)
            }
            prefs[KEY_PROFILES] = updated.toString()
        }
    }

    /**
     * Get the currently active profile (global default).
     */
    suspend fun getActiveProfileId(): String {
        val prefs = context.appDataStore.data.first()
        return prefs[KEY_ACTIVE_PROFILE] ?: "default"
    }

    /**
     * Set the active profile (global default).
     */
    suspend fun setActiveProfile(profileId: String) {
        context.appDataStore.edit { prefs ->
            prefs[KEY_ACTIVE_PROFILE] = profileId
        }
    }

    /**
     * Set a profile for a specific channel.
     */
    suspend fun setChannelProfile(channel: String, profileId: String) {
        context.appDataStore.edit { prefs ->
            val json = runCatching { JSONObject(prefs[KEY_CHANNEL_PROFILES] ?: "{}") }.getOrDefault(JSONObject())
            if (profileId.isBlank() || profileId == "default") json.remove(channel) else json.put(channel, profileId)
            prefs[KEY_CHANNEL_PROFILES] = json.toString()
        }
    }

    /**
     * Resolve the effective profile for a chatId.
     * Priority: channel-specific profile > global active profile > default.
     */
    suspend fun resolveProfile(chatId: String): AgentProfile {
        val prefs = context.appDataStore.data.first()
        val allProfiles = getAllProfiles()

        // Check channel-specific profile
        val channelPrefix = chatId.substringBefore("_", "")
        if (channelPrefix.isNotBlank()) {
            val channelProfilesJson = prefs[KEY_CHANNEL_PROFILES] ?: "{}"
            val channelProfiles = runCatching { JSONObject(channelProfilesJson) }.getOrDefault(JSONObject())
            val channelProfileId = channelProfiles.optString(channelPrefix, "")
            if (channelProfileId.isNotBlank()) {
                allProfiles.find { it.id == channelProfileId }?.let { return it }
            }
        }

        // Check global active profile
        val activeId = prefs[KEY_ACTIVE_PROFILE] ?: "default"
        return allProfiles.find { it.id == activeId } ?: BUILTIN_PROFILES[0]
    }
}
