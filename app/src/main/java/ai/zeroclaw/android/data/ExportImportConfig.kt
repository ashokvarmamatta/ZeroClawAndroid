package ai.zeroclaw.android.data

import android.content.Context
import android.os.Environment
import ai.zeroclaw.android.service.ZeroClawService
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ExportImportConfig — Phase 131: Backup/restore all settings & API keys as JSON.
 *
 * Export: collects AppSettings + all LlmKeyManager entries → single JSON file.
 * Import: reads JSON → writes back to DataStore and LlmKeyManager.
 *
 * Export format:
 * {
 *   "version": 1,
 *   "exported_at": "2026-03-17T12:00:00",
 *   "settings": { ... all SettingsData fields ... },
 *   "api_keys": [ { "label", "provider", "apiKey", "baseUrl", "model", "priority" }, ... ]
 * }
 *
 * Files saved to: Downloads/ZeroClaw/zeroclaw_backup_<timestamp>.json
 */
object ExportImportConfig {

    private const val EXPORT_VERSION = 1
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    data class ExportResult(
        val success: Boolean,
        val path: String = "",
        val message: String = ""
    )

    // ── Export ─────────────────────────────────────────────────────────────────

    suspend fun export(context: Context): ExportResult {
        return try {
            val settings = AppSettings(context).getAll()
            val keys = LlmKeyManager.getInstance(context).loadKeys()

            val json = JSONObject().apply {
                put("version", EXPORT_VERSION)
                put("exported_at", dateFormat.format(Date()))
                put("settings", settingsToJson(settings))
                put("api_keys", keysToJson(keys))
            }

            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "ZeroClaw"
            ).also { it.mkdirs() }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "zeroclaw_backup_$timestamp.json")
            file.writeText(json.toString(2))

            ZeroClawService.log("EXPORT: saved ${keys.size} keys + settings → ${file.absolutePath}")
            ExportResult(true, file.absolutePath, "Backup saved to ${file.name}")
        } catch (e: Exception) {
            ZeroClawService.log("EXPORT: failed — ${e.message}")
            ExportResult(false, message = "Export failed: ${e.message}")
        }
    }

    // ── Import ─────────────────────────────────────────────────────────────────

    suspend fun import(context: Context, filePath: String): ExportResult {
        return try {
            val file = File(filePath)
            if (!file.exists()) return ExportResult(false, message = "File not found: $filePath")

            val json = JSONObject(file.readText())
            val version = json.optInt("version", 1)

            // Import settings
            val settingsObj = json.optJSONObject("settings")
            if (settingsObj != null) {
                importSettings(context, settingsObj)
            }

            // Import API keys
            val keysArr = json.optJSONArray("api_keys")
            var keyCount = 0
            if (keysArr != null) {
                keyCount = importKeys(context, keysArr)
            }

            val msg = "Imported settings + $keyCount API keys"
            ZeroClawService.log("IMPORT: $msg from ${file.name} (v$version)")
            ExportResult(true, filePath, msg)
        } catch (e: Exception) {
            ZeroClawService.log("IMPORT: failed — ${e.message}")
            ExportResult(false, message = "Import failed: ${e.message}")
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun settingsToJson(s: SettingsData): JSONObject = JSONObject().apply {
        put("zeroClawUrl", s.zeroClawUrl)
        put("telegramToken", s.telegramToken)
        put("twilioSid", s.twilioSid)
        put("twilioToken", s.twilioToken)
        put("twilioFrom", s.twilioFrom)
        put("llmProvider", s.llmProvider)
        put("autoStart", s.autoStart)
        put("discordToken", s.discordToken)
        put("signalApiUrl", s.signalApiUrl)
        put("slackToken", s.slackToken)
        put("matrixConfig", s.matrixConfig)
        put("ircConfig", s.ircConfig)
        put("teamsConfig", s.teamsConfig)
        put("twitchConfig", s.twitchConfig)
        put("lineToken", s.lineToken)
        put("webChatEnabled", s.webChatEnabled)
    }

    private fun keysToJson(keys: List<ApiKeyEntry>): JSONArray = JSONArray().apply {
        keys.forEach { k ->
            put(JSONObject().apply {
                put("id",             k.id)
                put("label",          k.safeLabel)
                put("provider",       k.safeProvider)
                put("apiKey",         k.safeApiKey)
                put("baseUrl",        k.safeBaseUrl)
                put("preferredModel", k.safePreferredModel)
                put("enabled",        k.enabled)
            })
        }
    }

    private suspend fun importSettings(context: Context, obj: JSONObject) {
        AppSettings(context).save(
            zeroClawUrl  = obj.optString("zeroClawUrl", "http://127.0.0.1:3000"),
            telegramToken = obj.optString("telegramToken", ""),
            twilioSid    = obj.optString("twilioSid", ""),
            twilioToken  = obj.optString("twilioToken", ""),
            twilioFrom   = obj.optString("twilioFrom", "whatsapp:+14155238886"),
            llmApiKey    = "",  // legacy field — keys now in LlmKeyManager
            llmProvider  = obj.optString("llmProvider", "openai"),
            autoStart    = obj.optBoolean("autoStart", true),
            discordToken = obj.optString("discordToken", ""),
            signalApiUrl = obj.optString("signalApiUrl", ""),
            slackToken   = obj.optString("slackToken", ""),
            matrixConfig = obj.optString("matrixConfig", ""),
            ircConfig    = obj.optString("ircConfig", ""),
            teamsConfig  = obj.optString("teamsConfig", ""),
            twitchConfig = obj.optString("twitchConfig", ""),
            lineToken    = obj.optString("lineToken", ""),
            webChatEnabled = obj.optBoolean("webChatEnabled", false)
        )
    }

    private fun importKeys(context: Context, arr: JSONArray): Int {
        val manager = LlmKeyManager.getInstance(context)
        val existing = manager.loadKeys().toMutableList()
        var added = 0

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val entry = ApiKeyEntry(
                label          = obj.optString("label", "Imported Key ${i + 1}"),
                provider       = obj.optString("provider", "openai"),
                apiKey         = obj.optString("apiKey", ""),
                baseUrl        = obj.optString("baseUrl", "").ifBlank { null },
                preferredModel = obj.optString("preferredModel", "").ifBlank { null },
                enabled        = obj.optBoolean("enabled", true)
            )
            // Skip duplicates by apiKey
            if (existing.none { it.safeApiKey == entry.apiKey }) {
                existing.add(entry)
                added++
            }
        }

        manager.saveKeys(existing)
        return added
    }

    /**
     * List available backup files in Downloads/ZeroClaw/.
     */
    fun listBackups(): List<File> {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "ZeroClaw"
        )
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.name.startsWith("zeroclaw_backup_") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
