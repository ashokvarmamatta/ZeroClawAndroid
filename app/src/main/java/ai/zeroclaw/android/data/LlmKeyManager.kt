package ai.zeroclaw.android.data

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * LlmKeyManager
 *
 * Stores the ordered list of ApiKeyEntry objects in SharedPreferences (as JSON).
 * Tracks in-memory which key is active and which have failed this session.
 * Active key ID is persisted so it survives app restarts.
 * Order of keys = failover priority order (index 0 = first tried).
 */
class LlmKeyManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("llm_keys", Context.MODE_PRIVATE)
    private val gson = GsonBuilder().serializeNulls().create()

    // ── In-memory failure tracking (resets on service restart) ───────────────
    private val failedKeyIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    // ── Observable state for UI ───────────────────────────────────────────────
    private val _keysFlow = MutableStateFlow<List<ApiKeyEntry>>(emptyList())
    val keysFlow: StateFlow<List<ApiKeyEntry>> = _keysFlow.asStateFlow()

    private val _activeIndexFlow = MutableStateFlow(0)
    val activeIndexFlow: StateFlow<Int> = _activeIndexFlow.asStateFlow()

    init {
        _keysFlow.value = loadKeys()
        _activeIndexFlow.value = resolveActiveIndex()
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    fun loadKeys(): List<ApiKeyEntry> {
        val json = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ApiKeyEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveKeys(keys: List<ApiKeyEntry>) {
        prefs.edit().putString(KEY_LIST, gson.toJson(keys)).apply()
        _keysFlow.value = keys
        _activeIndexFlow.value = resolveActiveIndex()
    }

    fun addKey(entry: ApiKeyEntry) {
        val updated = loadKeys().toMutableList().apply { add(entry) }
        saveKeys(updated)
    }

    fun updateKey(entry: ApiKeyEntry) {
        val updated = loadKeys().map { if (it.id == entry.id) entry else it }
        saveKeys(updated)
    }

    fun deleteKey(id: String) {
        // If deleting the active key, clear active preference
        if (prefs.getString(ACTIVE_KEY_ID, null) == id) {
            prefs.edit().remove(ACTIVE_KEY_ID).apply()
        }
        val updated = loadKeys().filter { it.id != id }
        saveKeys(updated)
        failedKeyIds.remove(id)
    }

    fun reorderKeys(keys: List<ApiKeyEntry>) = saveKeys(keys)

    // ── Priority reordering ───────────────────────────────────────────────────

    /** Move a key up (lower index = higher priority) or down in the failover chain. */
    fun moveKey(id: String, direction: Int /* -1 = up, +1 = down */) {
        val keys = loadKeys().toMutableList()
        val idx = keys.indexOfFirst { it.id == id }
        if (idx < 0) return
        val targetIdx = idx + direction
        if (targetIdx < 0 || targetIdx >= keys.size) return
        val temp = keys[idx]
        keys[idx] = keys[targetIdx]
        keys[targetIdx] = temp
        saveKeys(keys)
    }

    // ── Active key selection ──────────────────────────────────────────────────

    /**
     * Pin a specific key as the active/default starting point for failover.
     * This moves that key to index 0 so it's always tried first.
     * The choice is persisted across restarts.
     */
    fun setActiveKey(id: String) {
        prefs.edit().putString(ACTIVE_KEY_ID, id).apply()
        // Move this key to the front of the list
        val keys = loadKeys().toMutableList()
        val idx = keys.indexOfFirst { it.id == id }
        if (idx > 0) {
            val entry = keys.removeAt(idx)
            keys.add(0, entry)
            saveKeys(keys)
        } else {
            _activeIndexFlow.value = resolveActiveIndex()
        }
        // Reset failures so the newly active key gets a fresh try
        failedKeyIds.clear()
    }

    /** Returns the current active key (first enabled, non-failed). */
    fun getActiveKey(): ApiKeyEntry? {
        val keys = loadKeys().filter { it.enabled }
        val pinnedId = prefs.getString(ACTIVE_KEY_ID, null)
        return if (pinnedId != null) {
            keys.firstOrNull { it.id == pinnedId && it.id !in failedKeyIds }
                ?: keys.firstOrNull { it.id !in failedKeyIds }
        } else {
            keys.firstOrNull { it.id !in failedKeyIds }
        }
    }

    private fun resolveActiveIndex(): Int {
        val keys = loadKeys().filter { it.enabled }
        if (keys.isEmpty()) return 0
        val pinnedId = prefs.getString(ACTIVE_KEY_ID, null)
        return if (pinnedId != null) {
            keys.indexOfFirst { it.id == pinnedId }.takeIf { it >= 0 } ?: 0
        } else 0
    }

    // ── Failover logic ────────────────────────────────────────────────────────

    /** Returns the next usable (enabled, not-failed) key, or null if all exhausted. */
    fun nextUsableKey(): ApiKeyEntry? {
        val pinnedId = prefs.getString(ACTIVE_KEY_ID, null)
        val keys = loadKeys().filter { it.enabled }
        // Start from pinned key if set and not failed
        if (pinnedId != null) {
            val pinned = keys.firstOrNull { it.id == pinnedId && it.id !in failedKeyIds }
            if (pinned != null) return pinned
        }
        return keys.firstOrNull { it.id !in failedKeyIds }
    }

    /** Mark a key as failed this session — won't be tried again until restart. */
    fun markFailed(id: String) {
        failedKeyIds.add(id)
        val keys = loadKeys().filter { it.enabled }
        val nextIndex = keys.indexOfFirst { it.id !in failedKeyIds }
        _activeIndexFlow.value = if (nextIndex >= 0) nextIndex else keys.size
    }

    /** Clear failure for a single key (e.g. when user changes model selection). */
    fun unmarkFailed(id: String) {
        failedKeyIds.remove(id)
        _activeIndexFlow.value = resolveActiveIndex()
    }

    /** Reset all failure state (called on service start). */
    fun resetFailures() {
        failedKeyIds.clear()
        _activeIndexFlow.value = resolveActiveIndex()
    }

    /** How many keys are currently failed this session. */
    fun failedCount() = failedKeyIds.size

    /** Get set of currently failed key IDs (for diagnostics). */
    fun getFailedKeyIds(): Set<String> = failedKeyIds.toSet()

    /** True if ALL enabled keys have failed. */
    fun allExhausted(): Boolean {
        val keys = loadKeys().filter { it.enabled }
        return keys.isNotEmpty() && keys.all { it.id in failedKeyIds }
    }

    companion object {
        private const val KEY_LIST = "api_key_list"
        private const val ACTIVE_KEY_ID = "active_key_id"

        @Volatile private var INSTANCE: LlmKeyManager? = null
        fun getInstance(context: Context): LlmKeyManager {
            return INSTANCE ?: synchronized(this) {
                LlmKeyManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
