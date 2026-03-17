package ai.zeroclaw.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * ConversationLabels — Phase 137: Tag/label/pin conversations.
 *
 * Allows users to tag conversations with custom labels and pin important ones.
 * Labels are stored per-chatId in DataStore.
 *
 * In-chat commands (handled by LlmRouter before LLM call):
 * - /label <name>     — add a label to the current conversation
 * - /unlabel <name>   — remove a label
 * - /pin              — pin this conversation
 * - /unpin            — unpin this conversation
 * - /labels           — list all labels for this chat
 * - /labeled <name>   — list all chats with this label
 */
object ConversationLabels {

    data class ConversationMeta(
        val chatId: String,
        val labels: MutableSet<String> = mutableSetOf(),
        val pinned: Boolean = false,
        val note: String = "",
        val updatedAt: Long = System.currentTimeMillis()
    )

    private val KEY_LABELS = stringPreferencesKey("conversation_labels")
    private val cache = mutableMapOf<String, ConversationMeta>()

    // ── Label operations ───────────────────────────────────────────────────────

    suspend fun addLabel(context: Context, chatId: String, label: String) {
        val meta = getOrCreate(chatId)
        meta.labels.add(label.trim().lowercase())
        cache[chatId] = meta.copy(updatedAt = System.currentTimeMillis())
        persist(context)
        ZeroClawService.log("LABELS: [$chatId] added label '$label'")
    }

    suspend fun removeLabel(context: Context, chatId: String, label: String) {
        val meta = cache[chatId] ?: return
        meta.labels.remove(label.trim().lowercase())
        cache[chatId] = meta.copy(updatedAt = System.currentTimeMillis())
        persist(context)
        ZeroClawService.log("LABELS: [$chatId] removed label '$label'")
    }

    suspend fun setPin(context: Context, chatId: String, pinned: Boolean) {
        val meta = getOrCreate(chatId)
        cache[chatId] = meta.copy(pinned = pinned, updatedAt = System.currentTimeMillis())
        persist(context)
        ZeroClawService.log("LABELS: [$chatId] ${if (pinned) "pinned" else "unpinned"}")
    }

    suspend fun setNote(context: Context, chatId: String, note: String) {
        val meta = getOrCreate(chatId)
        cache[chatId] = meta.copy(note = note, updatedAt = System.currentTimeMillis())
        persist(context)
    }

    // ── Query ──────────────────────────────────────────────────────────────────

    fun getMeta(chatId: String): ConversationMeta? = cache[chatId]

    fun getLabels(chatId: String): Set<String> = cache[chatId]?.labels ?: emptySet()

    fun isPinned(chatId: String): Boolean = cache[chatId]?.pinned ?: false

    fun getChatsWithLabel(label: String): List<String> {
        val l = label.trim().lowercase()
        return cache.entries.filter { it.value.labels.contains(l) }.map { it.key }
    }

    fun getPinnedChats(): List<String> = cache.entries.filter { it.value.pinned }.map { it.key }

    fun allLabels(): Set<String> = cache.values.flatMap { it.labels }.toSet()

    // ── Chat command handler ───────────────────────────────────────────────────

    /**
     * Handle /label, /pin, /labels commands from chat.
     * Returns a reply string if handled, null if not a label command.
     */
    suspend fun handleCommand(context: Context, chatId: String, message: String): String? {
        val trimmed = message.trim()
        return when {
            trimmed.startsWith("/label ") -> {
                val label = trimmed.removePrefix("/label ").trim()
                addLabel(context, chatId, label)
                "Label added: #$label"
            }
            trimmed.startsWith("/unlabel ") -> {
                val label = trimmed.removePrefix("/unlabel ").trim()
                removeLabel(context, chatId, label)
                "Label removed: #$label"
            }
            trimmed == "/pin" -> {
                setPin(context, chatId, true)
                "Conversation pinned."
            }
            trimmed == "/unpin" -> {
                setPin(context, chatId, false)
                "Conversation unpinned."
            }
            trimmed == "/labels" -> {
                val meta = cache[chatId]
                if (meta == null) {
                    "No labels for this conversation."
                } else {
                    buildString {
                        if (meta.pinned) appendLine("📌 Pinned")
                        val labels = meta.labels
                        if (labels.isNotEmpty()) {
                            appendLine("Labels: ${labels.joinToString(", ") { "#$it" }}")
                        } else {
                            appendLine("No labels.")
                        }
                        if (meta.note.isNotBlank()) appendLine("Note: ${meta.note}")
                    }.trim()
                }
            }
            trimmed.startsWith("/labeled ") -> {
                val label = trimmed.removePrefix("/labeled ").trim()
                val chats = getChatsWithLabel(label)
                if (chats.isEmpty()) "No conversations labeled #$label."
                else "Chats labeled #$label:\n${chats.joinToString("\n") { "• $it" }}"
            }
            else -> null
        }
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    suspend fun load(context: Context) {
        val prefs = context.appDataStore.data.first()
        val json = prefs[KEY_LABELS] ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val chatId = obj.optString("chatId")
                val labels = mutableSetOf<String>()
                val labelsArr = obj.optJSONArray("labels") ?: JSONArray()
                for (j in 0 until labelsArr.length()) labels.add(labelsArr.getString(j))
                cache[chatId] = ConversationMeta(
                    chatId    = chatId,
                    labels    = labels,
                    pinned    = obj.optBoolean("pinned", false),
                    note      = obj.optString("note", ""),
                    updatedAt = obj.optLong("updatedAt", 0L)
                )
            }
        } catch (_: Exception) {}
    }

    private suspend fun persist(context: Context) {
        val arr = JSONArray()
        for (meta in cache.values) {
            arr.put(JSONObject().apply {
                put("chatId",    meta.chatId)
                put("labels",    JSONArray(meta.labels.toList()))
                put("pinned",    meta.pinned)
                put("note",      meta.note)
                put("updatedAt", meta.updatedAt)
            })
        }
        context.appDataStore.edit { prefs -> prefs[KEY_LABELS] = arr.toString() }
    }

    private fun getOrCreate(chatId: String): ConversationMeta {
        return cache.getOrPut(chatId) { ConversationMeta(chatId) }
    }
}
