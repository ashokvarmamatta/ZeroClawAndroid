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
    val autoStart: Boolean = true
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
            autoStart = prefs[KEY_AUTO_START] ?: true
        )
    }

    suspend fun save(
        zeroClawUrl: String, telegramToken: String, twilioSid: String,
        twilioToken: String, twilioFrom: String, llmApiKey: String,
        llmProvider: String, autoStart: Boolean
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
        }
        // Also save auto_start to legacy SharedPreferences for BootReceiver
        context.getSharedPreferences("zeroclaw_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("auto_start", autoStart).apply()
    }
}
