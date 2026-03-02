package ai.zeroclaw.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = ZeroClawRed,
    onPrimary = ZeroClawOnSurface,
    primaryContainer = ZeroClawRedDark,
    onPrimaryContainer = ZeroClawOnSurface,
    secondary = ZeroClawOrange,
    background = ZeroClawSurface,
    surface = ZeroClawSurfaceVariant,
    onBackground = ZeroClawOnSurface,
    onSurface = ZeroClawOnSurface,
    surfaceVariant = ZeroClawSurfaceVariant,
    onSurfaceVariant = ZeroClawGray,
    error = ZeroClawRed
)

private val LightColorScheme = lightColorScheme(
    primary = ZeroClawRed,
    primaryContainer = Color(0xFFFFCDD2),
    secondary = ZeroClawOrange,
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A)
)

@Composable
fun ZeroClawTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primaryContainer.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
