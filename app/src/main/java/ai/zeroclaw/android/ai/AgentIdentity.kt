package ai.zeroclaw.android.ai

import android.content.Context
import ai.zeroclaw.android.service.ZeroClawService
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import ai.zeroclaw.android.data.AppSettings

/**
 * AgentIdentity — Phase 150: AIEOS v1.1 structured agent identity spec.
 *
 * Defines a rich identity for AI agents including:
 * - MBTI personality type (16 types)
 * - OCEAN big-five traits (Openness, Conscientiousness, Extraversion, Agreeableness, Neuroticism)
 * - Catchphrases (opening/closing expressions)
 * - Forbidden words (avoid in all responses)
 * - Core values and communication style
 *
 * The identity is compiled into a system prompt prefix that shapes all responses.
 * Inspired by nullclaw's AIEOS agent identity specification.
 */
data class AgentIdentity(
    val name: String = "ZeroClaw",
    val mbti: String = "INTJ",           // Mastermind — strategic, decisive, direct
    val oceanOpenness: Int = 85,         // 0-100: highly open to new ideas
    val oceanConscientiousness: Int = 90, // very organized and reliable
    val oceanExtraversion: Int = 30,     // introverted — concise, not chatty
    val oceanAgreeableness: Int = 70,    // helpful but honest
    val oceanNeuroticism: Int = 10,      // calm and stable under pressure
    val catchphrases: List<String> = listOf(
        "Let me be precise about this.",
        "Here's what the data says:",
        "To be clear:"
    ),
    val forbiddenWords: List<String> = listOf(
        "certainly!", "absolutely!", "of course!", "great question", "I'd be happy to"
    ),
    val coreValues: List<String> = listOf(
        "accuracy over politeness",
        "directness",
        "efficiency",
        "transparency about limitations"
    ),
    val communicationStyle: String = "concise, precise, technical when needed"
) {

    /**
     * Compile this identity into a system prompt prefix.
     * This gets prepended to every LLM call system prompt.
     */
    fun toSystemPromptPrefix(): String {
        val sb = StringBuilder()
        sb.appendLine("You are $name.")
        sb.appendLine("Personality: $mbti — ${mbtiDescription(mbti)}")
        sb.appendLine("Communication style: $communicationStyle")
        if (coreValues.isNotEmpty()) {
            sb.appendLine("Core values: ${coreValues.joinToString(", ")}")
        }
        if (catchphrases.isNotEmpty()) {
            sb.appendLine("You may occasionally use phrases like: ${catchphrases.joinToString(" / ")}")
        }
        if (forbiddenWords.isNotEmpty()) {
            sb.appendLine("Never say: ${forbiddenWords.joinToString(", ")}")
        }
        sb.appendLine("OCEAN traits: O=${oceanOpenness}/100 C=${oceanConscientiousness}/100 " +
            "E=${oceanExtraversion}/100 A=${oceanAgreeableness}/100 N=${oceanNeuroticism}/100")
        return sb.toString().trim()
    }

    private fun mbtiDescription(mbti: String): String = when (mbti.uppercase()) {
        "INTJ" -> "strategic, independent, decisive mastermind"
        "INTP" -> "analytical, objective, inventive thinker"
        "ENTJ" -> "bold, confident, decisive commander"
        "ENTP" -> "clever, curious, innovative debater"
        "INFJ" -> "insightful, principled, empathetic advocate"
        "INFP" -> "idealistic, empathetic, creative mediator"
        "ENFJ" -> "warm, charismatic, inspiring protagonist"
        "ENFP" -> "enthusiastic, creative, sociable campaigner"
        "ISTJ" -> "practical, reliable, detail-oriented logistician"
        "ISFJ" -> "warm, caring, reliable defender"
        "ESTJ" -> "organized, decisive, practical executive"
        "ESFJ" -> "caring, sociable, warm consul"
        "ISTP" -> "practical, observant, mechanical virtuoso"
        "ISFP" -> "gentle, sensitive, artistic adventurer"
        "ESTP" -> "energetic, bold, pragmatic entrepreneur"
        "ESFP" -> "spontaneous, energetic, fun entertainer"
        else -> mbti
    }

    companion object {
        private val KEY_IDENTITY = stringPreferencesKey("agent_identity_json")

        val DEFAULT = AgentIdentity()

        suspend fun load(context: Context): AgentIdentity {
            return try {
                val prefs = AppSettings.dataStore(context).data.first()
                val json = prefs[KEY_IDENTITY] ?: return DEFAULT
                fromJson(json)
            } catch (e: Exception) {
                DEFAULT
            }
        }

        private fun fromJson(json: String): AgentIdentity {
            return try {
                val j = org.json.JSONObject(json)
                AgentIdentity(
                    name = j.optString("name", DEFAULT.name),
                    mbti = j.optString("mbti", DEFAULT.mbti),
                    oceanOpenness = j.optInt("oc_o", DEFAULT.oceanOpenness),
                    oceanConscientiousness = j.optInt("oc_c", DEFAULT.oceanConscientiousness),
                    oceanExtraversion = j.optInt("oc_e", DEFAULT.oceanExtraversion),
                    oceanAgreeableness = j.optInt("oc_a", DEFAULT.oceanAgreeableness),
                    oceanNeuroticism = j.optInt("oc_n", DEFAULT.oceanNeuroticism),
                    coreValues = j.optJSONArray("values")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: DEFAULT.coreValues,
                    forbiddenWords = j.optJSONArray("forbidden")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: DEFAULT.forbiddenWords
                )
            } catch (e: Exception) {
                DEFAULT
            }
        }
    }
}
