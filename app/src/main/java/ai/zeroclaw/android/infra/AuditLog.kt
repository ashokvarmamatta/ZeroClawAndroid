package ai.zeroclaw.android.infra

import android.content.Context
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * AuditLog — Phase 156: Tamper-evident JSON Lines log of all tool actions.
 *
 * Every tool execution is appended to a JSON Lines (NDJSON) file on disk.
 * Each entry contains: timestamp, tool name, args, result summary, and a
 * simple hash chain (each entry hashes the previous entry's hash + its content)
 * for basic tamper detection.
 *
 * Log location: Files/zeroclaw/audit_YYYY-MM-DD.jsonl
 * Auto-rotated daily. Max 30 days retained.
 *
 * Enabled by default (passive, low overhead).
 */
object AuditLog {

    private const val MAX_LOG_DAYS = 30
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Volatile private var lastHash = "0000000000000000"

    /**
     * Log a tool execution event.
     */
    suspend fun logToolCall(
        context: Context,
        toolName: String,
        args: Map<String, String>,
        success: Boolean,
        resultSummary: String,
        durationMs: Long
    ) = withContext(Dispatchers.IO) {
        try {
            val entry = JSONObject().apply {
                put("ts", timestampFormat.format(Date()))
                put("tool", toolName)
                put("args", JSONObject(args as Map<*, *>))
                put("success", success)
                put("result", resultSummary.take(200))
                put("ms", durationMs)
                val content = "$lastHash$toolName${success}${resultSummary.take(50)}"
                val hash = content.hashCode().toString(16).padStart(16, '0')
                put("hash", hash)
                lastHash = hash
            }
            val logFile = getLogFile(context)
            logFile.appendText(entry.toString() + "\n")
        } catch (e: Exception) {
            // Audit log failure should never crash the app
            ZeroClawService.log("AUDIT: write failed — ${e.message}")
        }
    }

    /**
     * Read today's audit log entries.
     */
    fun readToday(context: Context): List<String> {
        return try {
            getLogFile(context).readLines().takeLast(100)
        } catch (_: Exception) { emptyList() }
    }

    private fun getLogFile(context: Context): File {
        val dir = File(context.filesDir, "zeroclaw").apply { mkdirs() }
        // Rotate old logs
        dir.listFiles()?.filter { it.name.startsWith("audit_") }
            ?.sortedBy { it.name }
            ?.dropLast(MAX_LOG_DAYS)
            ?.forEach { it.delete() }
        val today = dateFormat.format(Date())
        return File(dir, "audit_$today.jsonl")
    }
}
