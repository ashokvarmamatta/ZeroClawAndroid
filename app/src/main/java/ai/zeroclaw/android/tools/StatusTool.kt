package ai.zeroclaw.android.tools

import android.content.Context
import ai.zeroclaw.android.data.LlmKeyManager
import ai.zeroclaw.android.service.ZeroClawService

/**
 * StatusTool — diagnostics and status reporting.
 *
 * Inspired by ZeroClaw upstream's runtime operation skill.
 * Lets the AI check service status, key health, connection state,
 * and recent logs — useful for self-diagnostics.
 */
class StatusTool(private val context: Context) : Tool {

    override val name = "status"

    override val description = "Check ZeroClaw service status, API key health, connections, and recent logs. " +
            "Actions: 'overview' (full status), 'keys' (API key health), 'logs' (recent log entries), 'connections' (Telegram/WhatsApp state)."

    override val parameters = listOf(
        ToolParam("action", "string", "One of: overview, keys, logs, connections. Default: overview.", required = false)
    )

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val action = args["action"]?.trim()?.lowercase() ?: "overview"

        return when (action) {
            "overview" -> overview()
            "keys" -> keyHealth()
            "logs" -> recentLogs()
            "connections" -> connections()
            else -> overview()
        }
    }

    private fun overview(): ToolResult {
        val sb = StringBuilder("ZeroClaw Status Report\n")
        sb.appendLine("═══════════════════════")

        // Service state
        sb.appendLine("\n🦀 Service: ${if (ZeroClawService.isRunning) "RUNNING" else "STOPPED"}")

        // Connections
        sb.appendLine("\n📡 Connections:")
        sb.appendLine("  Telegram: ${if (ZeroClawService.telegramConnected) "✓ Connected" else "✗ Disconnected"}")
        sb.appendLine("  WhatsApp: ${if (ZeroClawService.whatsappConnected) "✓ Connected" else "✗ Disconnected"}")
        sb.appendLine("  Discord:  ${if (ZeroClawService.discordConnected) "✓ Connected" else "✗ Disconnected"}")
        sb.appendLine("  Signal:   ${if (ZeroClawService.signalConnected) "✓ Connected" else "✗ Disconnected"}")
        val tunnel = ZeroClawService.tunnelUrl
        sb.appendLine("  Tunnel: ${tunnel ?: "Not active"}")

        // Keys summary
        val km = LlmKeyManager.getInstance(context)
        val keys = km.loadKeys()
        val enabledKeys = keys.filter { it.enabled }
        val failedCount = km.getFailedKeyIds().size
        sb.appendLine("\n🔑 API Keys: ${enabledKeys.size} enabled, $failedCount failed this session")
        for (key in enabledKeys) {
            val status = if (km.getFailedKeyIds().contains(key.id)) "✗ FAILED" else "✓ OK"
            val models = key.safeSelectedModels
            val modelInfo = if (models.isNotEmpty()) "${models.size} model(s) selected" else key.safePreferredModel.ifBlank { "default model" }
            sb.appendLine("  [${key.safeLabel}] ${key.safeProvider} — $status — $modelInfo")
        }

        // Recent logs (last 5)
        val logs = ZeroClawService.recentLogs.toList().takeLast(5)
        if (logs.isNotEmpty()) {
            sb.appendLine("\n📊 Recent Logs (last 5):")
            for (log in logs) {
                sb.appendLine("  $log")
            }
        }

        return ToolResult(true, sb.toString().take(4000))
    }

    private fun keyHealth(): ToolResult {
        val km = LlmKeyManager.getInstance(context)
        val keys = km.loadKeys()
        val failedIds = km.getFailedKeyIds()

        val sb = StringBuilder("API Key Health Report\n")
        sb.appendLine("═══════════════════════\n")

        if (keys.isEmpty()) {
            sb.appendLine("No API keys configured.")
            return ToolResult(true, sb.toString())
        }

        for ((i, key) in keys.withIndex()) {
            val enabled = if (key.enabled) "enabled" else "disabled"
            val failed = if (failedIds.contains(key.id)) " ⚠️ FAILED this session" else ""
            sb.appendLine("${i + 1}. [${key.safeLabel}]")
            sb.appendLine("   Provider: ${key.safeProvider}")
            sb.appendLine("   Status: $enabled$failed")
            sb.appendLine("   Base URL: ${key.safeBaseUrl.ifBlank { "(default)" }}")
            sb.appendLine("   Model: ${key.safePreferredModel.ifBlank { "(auto)" }}")

            val selected = key.safeSelectedModels
            val checked = key.safeCheckedModels
            if (checked.isNotEmpty()) {
                val passing = checked.count { it.value == null }
                val failing = checked.count { it.value != null }
                sb.appendLine("   Checked models: $passing passing, $failing failing")
                sb.appendLine("   Selected models: ${if (selected.isNotEmpty()) selected.joinToString(", ") else "(none)"}")
            }
            sb.appendLine("   Google Search: ${if (key.safeGoogleSearch) "ON" else "OFF"}")
            sb.appendLine()
        }

        return ToolResult(true, sb.toString().take(4000))
    }

    private fun recentLogs(): ToolResult {
        val logs = ZeroClawService.recentLogs.toList()

        if (logs.isEmpty()) {
            return ToolResult(true, "No recent logs. Service may not be running.")
        }

        val sb = StringBuilder("Recent Logs (${logs.size} entries):\n\n")
        for (log in logs) {
            sb.appendLine(log)
        }

        return ToolResult(true, sb.toString().take(4000))
    }

    private fun connections(): ToolResult {
        val sb = StringBuilder("Connection Status\n")
        sb.appendLine("═══════════════════\n")

        sb.appendLine("🦀 Service: ${if (ZeroClawService.isRunning) "RUNNING" else "STOPPED"}")
        sb.appendLine("✈️ Telegram: ${if (ZeroClawService.telegramConnected) "Connected (polling)" else "Disconnected"}")
        sb.appendLine("💬 WhatsApp: ${if (ZeroClawService.whatsappConnected) "Connected (webhook on :8080)" else "Disconnected"}")
        sb.appendLine("🎮 Discord:  ${if (ZeroClawService.discordConnected) "Connected (Gateway WebSocket)" else "Disconnected"}")
        sb.appendLine("📡 Signal:   ${if (ZeroClawService.signalConnected) "Connected (signal-cli REST)" else "Disconnected"}")

        val tunnel = ZeroClawService.tunnelUrl
        sb.appendLine("🌐 Tunnel: ${tunnel ?: "Not active"}")

        return ToolResult(true, sb.toString())
    }
}
