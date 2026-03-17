package ai.zeroclaw.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * ThemeManager — Phase 132: Manual dark/light/system theme switch.
 *
 * Persists the user's theme preference in DataStore.
 * Observed as a Flow so the UI recomposes automatically when the theme changes.
 *
 * Modes:
 * - "system"  — follow Android system dark/light setting (default)
 * - "dark"    — always dark
 * - "light"   — always light
 */
object ThemeManager {

    enum class ThemeMode(val label: String, val emoji: String) {
        SYSTEM("System", "📱"),
        DARK("Dark", "🌙"),
        LIGHT("Light", "☀️")
    }

    private val KEY_THEME = stringPreferencesKey("theme_mode")

    /**
     * Observe the current theme as a Flow (recomposes UI on change).
     */
    fun themeFlow(context: Context): Flow<ThemeMode> {
        return context.appDataStore.data.map { prefs ->
            when (prefs[KEY_THEME]) {
                "dark"  -> ThemeMode.DARK
                "light" -> ThemeMode.LIGHT
                else    -> ThemeMode.SYSTEM
            }
        }
    }

    /**
     * Persist the selected theme mode.
     */
    suspend fun setTheme(context: Context, mode: ThemeMode) {
        context.appDataStore.edit { prefs ->
            prefs[KEY_THEME] = mode.name.lowercase()
        }
    }

    /**
     * Read current theme synchronously (for non-coroutine contexts).
     * Falls back to SYSTEM.
     */
    suspend fun getTheme(context: Context): ThemeMode {
        var result = ThemeMode.SYSTEM
        context.appDataStore.data.collect { prefs ->
            result = when (prefs[KEY_THEME]) {
                "dark"  -> ThemeMode.DARK
                "light" -> ThemeMode.LIGHT
                else    -> ThemeMode.SYSTEM
            }
            return@collect
        }
        return result
    }
}
