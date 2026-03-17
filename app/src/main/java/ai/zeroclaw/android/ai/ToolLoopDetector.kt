package ai.zeroclaw.android.ai

import ai.zeroclaw.android.service.ZeroClawService

/**
 * ToolLoopDetector — Phase 115: Prevent infinite tool call chains.
 *
 * Detects patterns where the AI gets stuck in loops:
 * - Same tool called repeatedly with same args (repeat detection)
 * - Ping-pong between two tools (oscillation detection)
 * - Too many tool calls in a single conversation turn (circuit breaker)
 * - No progress between consecutive tool rounds (stall detection)
 *
 * Inspired by OpenClaw's tool-loop-detection.ts.
 */
class ToolLoopDetector {

    data class ToolCallRecord(
        val toolName: String,
        val argsHash: Int,
        val round: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    companion object {
        const val MAX_SAME_TOOL_REPEATS = 3   // same tool+args called 3 times → loop
        const val MAX_TOTAL_CALLS = 15        // circuit breaker: 15 tool calls per turn
        const val MAX_PING_PONG = 3           // A→B→A→B→A→B pattern 3 times → loop
        const val STALL_THRESHOLD = 3         // 3 rounds with no meaningful result change
    }

    private val callHistory = mutableListOf<ToolCallRecord>()
    private val resultHashes = mutableListOf<Int>()

    /**
     * Record a tool call. Returns a LoopDetection if a loop is detected, null otherwise.
     */
    fun recordCall(toolName: String, argsHash: Int, round: Int): LoopDetection? {
        val record = ToolCallRecord(toolName, argsHash, round)
        callHistory.add(record)

        // 1. Circuit breaker: too many total calls
        if (callHistory.size > MAX_TOTAL_CALLS) {
            ZeroClawService.log("LOOP: circuit breaker — ${callHistory.size} tool calls exceeded limit of $MAX_TOTAL_CALLS")
            return LoopDetection.CircuitBreaker(callHistory.size)
        }

        // 2. Same tool+args repeat detection
        val sameCallCount = callHistory.count { it.toolName == toolName && it.argsHash == argsHash }
        if (sameCallCount >= MAX_SAME_TOOL_REPEATS) {
            ZeroClawService.log("LOOP: repeat detected — $toolName called $sameCallCount times with same args")
            return LoopDetection.RepeatDetected(toolName, sameCallCount)
        }

        // 3. Ping-pong detection (A→B→A→B pattern)
        if (callHistory.size >= MAX_PING_PONG * 2) {
            val recent = callHistory.takeLast(MAX_PING_PONG * 2)
            val toolNames = recent.map { it.toolName }
            val pairs = toolNames.zipWithNext()
            if (pairs.size >= MAX_PING_PONG * 2 - 1) {
                val uniquePairs = pairs.toSet()
                if (uniquePairs.size <= 2 && toolNames.toSet().size == 2) {
                    val tools = toolNames.toSet().toList()
                    ZeroClawService.log("LOOP: ping-pong detected between ${tools[0]} and ${tools[1]}")
                    return LoopDetection.PingPong(tools[0], tools[1])
                }
            }
        }

        return null
    }

    /**
     * Record a tool result hash to detect stalls (same results across rounds).
     */
    fun recordResult(resultHash: Int): LoopDetection? {
        resultHashes.add(resultHash)

        if (resultHashes.size >= STALL_THRESHOLD) {
            val recent = resultHashes.takeLast(STALL_THRESHOLD)
            if (recent.toSet().size == 1) {
                ZeroClawService.log("LOOP: stall detected — $STALL_THRESHOLD rounds with identical results")
                return LoopDetection.Stall(STALL_THRESHOLD)
            }
        }
        return null
    }

    /**
     * Reset the detector for a new conversation turn.
     */
    fun reset() {
        callHistory.clear()
        resultHashes.clear()
    }

    /**
     * Get a summary of the current detection state for logging.
     */
    fun summary(): String {
        val toolCounts = callHistory.groupBy { it.toolName }.mapValues { it.value.size }
        return "calls=${callHistory.size}, tools=${toolCounts}"
    }

    sealed class LoopDetection {
        abstract val message: String

        data class RepeatDetected(val tool: String, val count: Int) : LoopDetection() {
            override val message = "Tool '$tool' called $count times with identical args — breaking loop."
        }
        data class CircuitBreaker(val totalCalls: Int) : LoopDetection() {
            override val message = "Circuit breaker: $totalCalls tool calls in one turn — stopping."
        }
        data class PingPong(val toolA: String, val toolB: String) : LoopDetection() {
            override val message = "Ping-pong loop between '$toolA' and '$toolB' — breaking."
        }
        data class Stall(val rounds: Int) : LoopDetection() {
            override val message = "Stall: $rounds rounds with identical results — stopping."
        }
    }
}
