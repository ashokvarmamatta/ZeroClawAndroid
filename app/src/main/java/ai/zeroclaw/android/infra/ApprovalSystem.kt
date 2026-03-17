package ai.zeroclaw.android.infra

import ai.zeroclaw.android.service.ZeroClawService
import ai.zeroclaw.android.tools.ToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ApprovalSystem — Phase 136: Require user approval for dangerous tool actions.
 *
 * Before certain high-risk tool calls are executed, a pending approval is created.
 * The request is shown to the user (via notification or chat message), and the
 * tool execution waits until the user approves or denies it.
 *
 * Dangerous tools requiring approval:
 * - file_manager: delete, write, move operations
 * - web_fetch: POST/PUT/DELETE requests
 * - email: send action
 * - smart_home: any action
 * - Any tool matching custom dangerous patterns
 *
 * Approval timeout: 5 minutes (after which the call is auto-denied).
 */
object ApprovalSystem {

    private const val APPROVAL_TIMEOUT_MS = 5 * 60 * 1000L

    enum class ApprovalDecision { PENDING, APPROVED, DENIED }

    data class PendingApproval(
        val id: String,
        val chatId: String,
        val toolName: String,
        val args: Map<String, String>,
        val reason: String,
        val createdAt: Long = System.currentTimeMillis(),
        @Transient val deferred: CompletableDeferred<Boolean> = CompletableDeferred()
    )

    private val pending = mutableMapOf<String, PendingApproval>()
    private var idCounter = 1000

    // ── Dangerous tool definitions ─────────────────────────────────────────────

    data class DangerousAction(
        val toolName: String,
        val argKey: String? = null,     // if set, only dangerous for this arg key
        val argValues: List<String> = emptyList(), // if set, only for these arg values
        val reason: String
    )

    val DANGEROUS_ACTIONS = listOf(
        DangerousAction("file_manager", "action", listOf("delete", "write", "move"),
            "This will modify or delete files on your device"),
        DangerousAction("email", "action", listOf("send"),
            "This will send an email on your behalf"),
        DangerousAction("smart_home", null, emptyList(),
            "This will control smart home devices"),
        DangerousAction("web_fetch", "method", listOf("POST", "PUT", "DELETE", "PATCH"),
            "This will make a write request to an external URL"),
        DangerousAction("calendar", "action", listOf("create", "delete", "update"),
            "This will modify your calendar")
    )

    private var approvalEnabled = true

    fun setEnabled(enabled: Boolean) { approvalEnabled = enabled }
    fun isEnabled() = approvalEnabled

    // ── Check if a tool call needs approval ────────────────────────────────────

    fun needsApproval(call: ToolCall): String? {
        if (!approvalEnabled) return null

        for (danger in DANGEROUS_ACTIONS) {
            if (danger.toolName != call.name) continue

            if (danger.argKey == null) {
                // Any call to this tool is dangerous
                return danger.reason
            }

            val argValue = call.args[danger.argKey]?.lowercase() ?: continue
            if (danger.argValues.isEmpty() || danger.argValues.any { it.lowercase() == argValue }) {
                return danger.reason
            }
        }
        return null
    }

    // ── Request & wait for approval ────────────────────────────────────────────

    /**
     * Request user approval for a tool call. Suspends until approved/denied or timed out.
     * Returns true if approved, false if denied or timed out.
     *
     * Callers should send the approval prompt to the user's chat before calling this.
     */
    suspend fun requestApproval(
        chatId: String,
        call: ToolCall,
        reason: String,
        onPendingCreated: suspend (PendingApproval) -> Unit = {}
    ): Boolean {
        val id = "approval_${idCounter++}"
        val approval = PendingApproval(
            id = id,
            chatId = chatId,
            toolName = call.name,
            args = call.args,
            reason = reason
        )

        pending[id] = approval
        ZeroClawService.log("APPROVAL: pending '$id' for ${call.name}(${call.args}) reason='$reason'")

        // Notify the caller so they can message the user
        onPendingCreated(approval)

        val result = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
            approval.deferred.await()
        } ?: false

        pending.remove(id)

        if (!result) {
            ZeroClawService.log("APPROVAL: '$id' denied/timed-out")
        } else {
            ZeroClawService.log("APPROVAL: '$id' approved")
        }

        return result
    }

    /**
     * Approve a pending request by ID (called when user sends "yes"/"approve").
     */
    fun approve(approvalId: String): Boolean {
        val a = pending[approvalId] ?: return false
        a.deferred.complete(true)
        return true
    }

    /**
     * Deny a pending request by ID (called when user sends "no"/"deny").
     */
    fun deny(approvalId: String): Boolean {
        val a = pending[approvalId] ?: return false
        a.deferred.complete(false)
        return true
    }

    /**
     * Try to resolve "yes" or "no" from a chat message for the most recent pending approval
     * in that chatId. Returns true if a pending approval was resolved.
     */
    fun tryResolveFromMessage(chatId: String, message: String): Boolean {
        val approval = pending.values
            .filter { it.chatId == chatId }
            .maxByOrNull { it.createdAt } ?: return false

        val lower = message.trim().lowercase()
        return when {
            lower in listOf("yes", "y", "approve", "ok", "allow", "confirm") -> approve(approval.id)
            lower in listOf("no", "n", "deny", "cancel", "block", "reject")  -> deny(approval.id)
            else -> false
        }
    }

    /**
     * Get all pending approvals for a chat.
     */
    fun getPending(chatId: String): List<PendingApproval> =
        pending.values.filter { it.chatId == chatId }.toList()

    /**
     * Format an approval request message to send to the user.
     */
    fun formatRequest(approval: PendingApproval): String = buildString {
        appendLine("🔐 **Approval Required**")
        appendLine()
        appendLine("The AI wants to call: `${approval.toolName}`")
        if (approval.args.isNotEmpty()) {
            appendLine("With args: `${approval.args.entries.joinToString { "${it.key}=${it.value}" }}`")
        }
        appendLine()
        appendLine("⚠️ Reason: ${approval.reason}")
        appendLine()
        appendLine("Reply **yes** to allow or **no** to deny. (Timeout: 5 minutes)")
        appendLine("ID: `${approval.id}`")
    }
}
