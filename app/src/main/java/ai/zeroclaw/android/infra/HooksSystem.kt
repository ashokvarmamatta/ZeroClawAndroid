package ai.zeroclaw.android.infra

import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.runBlocking

/**
 * HooksSystem — Phase 123: Before/after tool call middleware.
 *
 * A lifecycle hook system allowing registered listeners to intercept and
 * transform requests/responses at key points in the agent pipeline.
 * Inspired by OpenClaw's plugins/hooks.ts (20+ hook points).
 *
 * Hook points:
 * - BEFORE_TOOL_CALL    — intercept tool call before execution (can modify args or cancel)
 * - AFTER_TOOL_CALL     — transform tool result after execution
 * - BEFORE_LLM_CALL     — modify prompt/system prompt before sending to LLM
 * - AFTER_LLM_CALL      — transform LLM reply before returning to user
 * - MESSAGE_RECEIVED    — intercept incoming message from any channel
 * - MESSAGE_SENDING     — intercept outgoing message before delivery
 * - SESSION_START       — new chat session started
 * - SESSION_END         — chat session cleared
 */
object HooksSystem {

    enum class HookPoint {
        BEFORE_TOOL_CALL,
        AFTER_TOOL_CALL,
        BEFORE_LLM_CALL,
        AFTER_LLM_CALL,
        MESSAGE_RECEIVED,
        MESSAGE_SENDING,
        SESSION_START,
        SESSION_END
    }

    data class HookContext(
        val hookPoint: HookPoint,
        val chatId: String,
        val toolName: String = "",
        val args: Map<String, String> = emptyMap(),
        var payload: String = "",      // message text / tool result / LLM reply
        var cancelled: Boolean = false,
        val metadata: MutableMap<String, Any> = mutableMapOf()
    )

    /** Return true to continue pipeline, false to cancel */
    fun interface Hook {
        suspend fun handle(ctx: HookContext): Boolean
    }

    private val hooks = mutableMapOf<HookPoint, MutableList<Pair<String, Hook>>>()

    /**
     * Register a hook for a specific lifecycle point.
     * @param id Unique identifier (used to unregister)
     * @param point The lifecycle point to hook into
     * @param hook The callback — return false to cancel the pipeline
     */
    fun register(id: String, point: HookPoint, hook: Hook) {
        hooks.getOrPut(point) { mutableListOf() }.add(id to hook)
        ZeroClawService.log("HOOKS: registered '$id' on ${point.name}")
    }

    /**
     * Unregister a hook by ID (removes from all hook points).
     */
    fun unregister(id: String) {
        for (list in hooks.values) {
            list.removeAll { it.first == id }
        }
    }

    /**
     * Fire all hooks for a given point. Returns false if any hook cancelled the pipeline.
     * Modifies ctx.payload in-place (hooks can transform the payload).
     */
    suspend fun fire(ctx: HookContext): Boolean {
        val list = hooks[ctx.hookPoint] ?: return true
        for ((id, hook) in list) {
            try {
                val cont = hook.handle(ctx)
                if (!cont || ctx.cancelled) {
                    ZeroClawService.log("HOOKS: pipeline cancelled by '$id' at ${ctx.hookPoint.name}")
                    return false
                }
            } catch (e: Exception) {
                ZeroClawService.log("HOOKS: '$id' threw at ${ctx.hookPoint.name} — ${e.message}")
            }
        }
        return true
    }

    /**
     * Convenience: fire a hook with a simple string payload.
     * Returns the (possibly modified) payload, or null if cancelled.
     */
    suspend fun fireWithPayload(
        point: HookPoint,
        chatId: String,
        payload: String,
        toolName: String = "",
        args: Map<String, String> = emptyMap()
    ): String? {
        val ctx = HookContext(point, chatId, toolName, args, payload)
        val cont = fire(ctx)
        return if (cont) ctx.payload else null
    }

    /**
     * List all registered hooks.
     */
    fun listHooks(): List<Triple<String, HookPoint, Int>> {
        return hooks.flatMap { (point, list) ->
            list.mapIndexed { idx, (id, _) -> Triple(id, point, idx) }
        }
    }

    /**
     * Clear all hooks (used for testing or plugin unload).
     */
    fun clearAll() {
        hooks.clear()
    }

    // ── Built-in hooks ────────────────────────────────────────────────────────

    /**
     * Built-in: log all tool calls.
     */
    val LOG_TOOL_CALLS = Hook { ctx ->
        if (ctx.hookPoint == HookPoint.BEFORE_TOOL_CALL) {
            ZeroClawService.log("HOOKS: tool=${ctx.toolName} args=${ctx.args}")
        }
        true
    }

    /**
     * Built-in: sanitize outgoing messages (strip internal markers).
     */
    val SANITIZE_OUTPUT = Hook { ctx ->
        if (ctx.hookPoint == HookPoint.MESSAGE_SENDING) {
            ctx.payload = ctx.payload
                .replace(Regex("<thinking>[\\s\\S]*?</thinking>"), "")
                .replace(Regex("<answer>|</answer>"), "")
                .trim()
        }
        true
    }

    init {
        // Register built-in hooks
        register("builtin_log_tools", HookPoint.BEFORE_TOOL_CALL, LOG_TOOL_CALLS)
        register("builtin_sanitize", HookPoint.MESSAGE_SENDING, SANITIZE_OUTPUT)
    }
}
