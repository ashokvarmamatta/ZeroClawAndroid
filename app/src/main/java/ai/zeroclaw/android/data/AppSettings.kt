package ai.zeroclaw.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "zeroclaw_settings")

data class SettingsData(
    val zeroClawUrl: String = "http://127.0.0.1:3000",
    val telegramToken: String = "",
    val twilioSid: String = "",
    val twilioToken: String = "",
    val twilioFrom: String = "whatsapp:+14155238886",
    val llmApiKey: String = "",
    val llmProvider: String = "openai",
    val autoStart: Boolean = true,
    val discordToken: String = "",
    val signalApiUrl: String = "",
    val slackToken: String = "",
    val matrixConfig: String = "",
    val ircConfig: String = "",
    val teamsConfig: String = "",
    val twitchConfig: String = "",
    val lineToken: String = "",
    val webChatEnabled: Boolean = false,
    val themeMode: String = "system",        // "system", "dark", "light"
    // NullClaw feature flags — only enable core features by default
    val composioEnabled: Boolean = false,    // needs API key
    val mcpEnabled: Boolean = false,         // needs server setup
    val a2aEnabled: Boolean = false,         // needs web chat enabled
    val delegateEnabled: Boolean = false,    // advanced — user enables manually
    val auditLogEnabled: Boolean = true,     // on by default (passive)
    val pushoverEnabled: Boolean = false,    // needs API key
    val composioApiKey: String = "",
    val mcpServerUrl: String = "",
    val pushoverToken: String = "",
    val pushoverUserKey: String = "",
    val mattermostConfig: String = "",
    val dingtalkConfig: String = "",
    val optimizePrompt: Boolean = false,
    val offlineWebSummarize: Boolean = true,   // fetch web data + ask model to summarize it
    val tunnelMode: String = "quick",          // "off", "quick" (trycloudflare.com), "token" (named tunnel)
    val tunnelToken: String = "",              // Cloudflare tunnel token (for named tunnels)
    val tunnelHostname: String = ""            // Your domain (e.g. "api.yourdomain.com") — shown in UI for named tunnels
)

class AppSettings(private val context: Context) {

    companion object {
        /** Expose DataStore for use by other components (e.g. ToolSystem). */
        fun dataStore(context: Context): DataStore<Preferences> = context.appDataStore

        val KEY_ZEROCLAW_URL = stringPreferencesKey("zeroclaw_url")
        val KEY_TELEGRAM_TOKEN = stringPreferencesKey("telegram_token")
        val KEY_TWILIO_SID = stringPreferencesKey("twilio_sid")
        val KEY_TWILIO_TOKEN = stringPreferencesKey("twilio_token")
        val KEY_TWILIO_FROM = stringPreferencesKey("twilio_from")
        val KEY_LLM_API_KEY = stringPreferencesKey("llm_api_key")
        val KEY_LLM_PROVIDER = stringPreferencesKey("llm_provider")
        val KEY_AUTO_START = booleanPreferencesKey("auto_start")
        val KEY_DISCORD_TOKEN = stringPreferencesKey("discord_token")
        val KEY_SIGNAL_API_URL = stringPreferencesKey("signal_api_url")
        val KEY_SLACK_TOKEN = stringPreferencesKey("slack_token")
        val KEY_MATRIX_CONFIG = stringPreferencesKey("matrix_config")
        val KEY_IRC_CONFIG = stringPreferencesKey("irc_config")
        val KEY_TEAMS_CONFIG = stringPreferencesKey("teams_config")
        val KEY_TWITCH_CONFIG = stringPreferencesKey("twitch_config")
        val KEY_LINE_TOKEN = stringPreferencesKey("line_token")
        val KEY_WEB_CHAT_ENABLED = booleanPreferencesKey("web_chat_enabled")
        val KEY_COMPOSIO_ENABLED = booleanPreferencesKey("composio_enabled")
        val KEY_MCP_ENABLED = booleanPreferencesKey("mcp_enabled")
        val KEY_A2A_ENABLED = booleanPreferencesKey("a2a_enabled")
        val KEY_DELEGATE_ENABLED = booleanPreferencesKey("delegate_enabled")
        val KEY_AUDIT_LOG_ENABLED = booleanPreferencesKey("audit_log_enabled")
        val KEY_PUSHOVER_ENABLED = booleanPreferencesKey("pushover_enabled")
        val KEY_COMPOSIO_API_KEY = stringPreferencesKey("composio_api_key")
        val KEY_MCP_SERVER_URL = stringPreferencesKey("mcp_server_url")
        val KEY_PUSHOVER_TOKEN = stringPreferencesKey("pushover_token")
        val KEY_PUSHOVER_USER_KEY = stringPreferencesKey("pushover_user_key")
        val KEY_MATTERMOST_CONFIG = stringPreferencesKey("mattermost_config")
        val KEY_DINGTALK_CONFIG = stringPreferencesKey("dingtalk_config")
        val KEY_BRAVE_API_KEY = stringPreferencesKey("brave_api_key")
        val KEY_OPTIMIZE_PROMPT = booleanPreferencesKey("optimize_prompt")
        val KEY_OFFLINE_WEB_SUMMARIZE = booleanPreferencesKey("offline_web_summarize")
        val KEY_TUNNEL_MODE = stringPreferencesKey("tunnel_mode")
        val KEY_TUNNEL_TOKEN = stringPreferencesKey("tunnel_token")
        val KEY_TUNNEL_HOSTNAME = stringPreferencesKey("tunnel_hostname")
    }

    suspend fun getAll(): SettingsData {
        val prefs = context.appDataStore.data.first()
        return SettingsData(
            zeroClawUrl = prefs[KEY_ZEROCLAW_URL] ?: "http://127.0.0.1:3000",
            telegramToken = prefs[KEY_TELEGRAM_TOKEN] ?: "",
            twilioSid = prefs[KEY_TWILIO_SID] ?: "",
            twilioToken = prefs[KEY_TWILIO_TOKEN] ?: "",
            twilioFrom = prefs[KEY_TWILIO_FROM] ?: "whatsapp:+14155238886",
            llmApiKey = prefs[KEY_LLM_API_KEY] ?: "",
            llmProvider = prefs[KEY_LLM_PROVIDER] ?: "openai",
            autoStart = prefs[KEY_AUTO_START] ?: true,
            discordToken = prefs[KEY_DISCORD_TOKEN] ?: "",
            signalApiUrl = prefs[KEY_SIGNAL_API_URL] ?: "",
            slackToken = prefs[KEY_SLACK_TOKEN] ?: "",
            matrixConfig = prefs[KEY_MATRIX_CONFIG] ?: "",
            ircConfig = prefs[KEY_IRC_CONFIG] ?: "",
            teamsConfig = prefs[KEY_TEAMS_CONFIG] ?: "",
            twitchConfig = prefs[KEY_TWITCH_CONFIG] ?: "",
            lineToken = prefs[KEY_LINE_TOKEN] ?: "",
            webChatEnabled = prefs[KEY_WEB_CHAT_ENABLED] ?: false,
            composioEnabled = prefs[KEY_COMPOSIO_ENABLED] ?: false,
            mcpEnabled = prefs[KEY_MCP_ENABLED] ?: false,
            a2aEnabled = prefs[KEY_A2A_ENABLED] ?: false,
            delegateEnabled = prefs[KEY_DELEGATE_ENABLED] ?: false,
            auditLogEnabled = prefs[KEY_AUDIT_LOG_ENABLED] ?: true,
            pushoverEnabled = prefs[KEY_PUSHOVER_ENABLED] ?: false,
            composioApiKey = prefs[KEY_COMPOSIO_API_KEY] ?: "",
            mcpServerUrl = prefs[KEY_MCP_SERVER_URL] ?: "",
            pushoverToken = prefs[KEY_PUSHOVER_TOKEN] ?: "",
            pushoverUserKey = prefs[KEY_PUSHOVER_USER_KEY] ?: "",
            mattermostConfig = prefs[KEY_MATTERMOST_CONFIG] ?: "",
            dingtalkConfig = prefs[KEY_DINGTALK_CONFIG] ?: "",
            optimizePrompt = prefs[KEY_OPTIMIZE_PROMPT] ?: false,
            offlineWebSummarize = prefs[KEY_OFFLINE_WEB_SUMMARIZE] ?: true,
            tunnelMode = prefs[KEY_TUNNEL_MODE] ?: "quick",
            tunnelToken = prefs[KEY_TUNNEL_TOKEN] ?: "",
            tunnelHostname = prefs[KEY_TUNNEL_HOSTNAME] ?: ""
        )
    }

    suspend fun save(
        zeroClawUrl: String, telegramToken: String, twilioSid: String,
        twilioToken: String, twilioFrom: String, llmApiKey: String,
        llmProvider: String, autoStart: Boolean, discordToken: String = "",
        signalApiUrl: String = "", slackToken: String = "",
        matrixConfig: String = "", ircConfig: String = "",
        teamsConfig: String = "", twitchConfig: String = "",
        lineToken: String = "", webChatEnabled: Boolean = false,
        optimizePrompt: Boolean = false, offlineWebSummarize: Boolean = true,
        tunnelMode: String = "quick", tunnelToken: String = "",
        tunnelHostname: String = ""
    ) {
        context.appDataStore.edit { prefs ->
            prefs[KEY_ZEROCLAW_URL] = zeroClawUrl
            prefs[KEY_TELEGRAM_TOKEN] = telegramToken
            prefs[KEY_TWILIO_SID] = twilioSid
            prefs[KEY_TWILIO_TOKEN] = twilioToken
            prefs[KEY_TWILIO_FROM] = twilioFrom
            prefs[KEY_LLM_API_KEY] = llmApiKey
            prefs[KEY_LLM_PROVIDER] = llmProvider
            prefs[KEY_AUTO_START] = autoStart
            prefs[KEY_DISCORD_TOKEN] = discordToken
            prefs[KEY_SIGNAL_API_URL] = signalApiUrl
            prefs[KEY_SLACK_TOKEN] = slackToken
            prefs[KEY_MATRIX_CONFIG] = matrixConfig
            prefs[KEY_IRC_CONFIG] = ircConfig
            prefs[KEY_TEAMS_CONFIG] = teamsConfig
            prefs[KEY_TWITCH_CONFIG] = twitchConfig
            prefs[KEY_LINE_TOKEN] = lineToken
            prefs[KEY_WEB_CHAT_ENABLED] = webChatEnabled
            prefs[KEY_OPTIMIZE_PROMPT] = optimizePrompt
            prefs[KEY_OFFLINE_WEB_SUMMARIZE] = offlineWebSummarize
            prefs[KEY_TUNNEL_MODE] = tunnelMode
            prefs[KEY_TUNNEL_TOKEN] = tunnelToken
            prefs[KEY_TUNNEL_HOSTNAME] = tunnelHostname
        }
        // Also save auto_start to legacy SharedPreferences for BootReceiver
        context.getSharedPreferences("zeroclaw_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("auto_start", autoStart).apply()
    }
}
