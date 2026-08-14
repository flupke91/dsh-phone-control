package com.flupke.dsh.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Official DSH design tokens (packages/client/ui-theme/src/styles/design-platform.css)
// deepseek scale
val DshPrimary = Color(0xFF4176E6)            // deepseek-500 rgb(65,118,230)
val DshPrimaryContainer = Color(0xFFE4EDFD)   // deepseek-100
val DshPrimaryDim = Color(0xFFD3E2FF)         // deepseek-200
val DshPrimaryStrong = Color(0xFF2F4C8F)      // deepseek-700
val DshOnPrimary = Color(0xFFFFFFFF)
// neutral-bluish scale
val DshBg = Color(0xFFF5F6F7)                 // neutral-bluish-60
val DshSurface = Color(0xFFFFFFFF)            // neutral-bluish-00
val DshSurface2 = Color(0xFFEBEEF2)           // neutral-bluish-100
val DshTextPrimary = Color(0xFF0F1115)        // neutral-bluish-1000
val DshTextSecondary = Color(0xFF61666B)      // neutral-bluish-700
val DshTextTertiary = Color(0xFF979DA6)       // neutral-bluish-500
val DshBorder = Color(0x1A000000)             // rgba(0,0,0,0.10)
// semantic
val DshSuccess = Color(0xFF22C55E)            // green-500
val DshWarning = Color(0xFFF59E0B)            // amber-500
val DshDanger = Color(0xFFE5484D)

private val LightColors = lightColorScheme(
    primary = DshPrimary,
    onPrimary = DshOnPrimary,
    primaryContainer = DshPrimaryContainer,
    onPrimaryContainer = DshPrimaryStrong,
    secondary = DshSuccess,
    onSecondary = DshOnPrimary,
    background = DshBg,
    onBackground = DshTextPrimary,
    surface = DshSurface,
    onSurface = DshTextPrimary,
    onSurfaceVariant = DshTextSecondary,
    surfaceVariant = DshSurface2,
    outline = DshBorder,
    outlineVariant = Color(0x14000000),
    error = DshDanger,
    onError = DshOnPrimary,
    errorContainer = Color(0xFFFDE7E8),
    onErrorContainer = Color(0xFF5C1A1C),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8BDF5),
    onPrimary = Color(0xFF0F1B3A),
    primaryContainer = Color(0xFF25375F),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFF4ADE80),
    onSecondary = Color(0xFF052E14),
    secondaryContainer = Color(0xFF1D3A26),
    onSecondaryContainer = Color(0xFFB8F5CD),
    tertiary = Color(0xFF9AA7FF),
    onTertiary = Color(0xFF141A3A),
    tertiaryContainer = Color(0xFF2A3260),
    onTertiaryContainer = Color(0xFFDDE1FF),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF16181D),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF23262C),
    onSurfaceVariant = Color(0xFFFFFFFF),
    outline = Color(0xFF3A4048),
    outlineVariant = Color(0xFF2A2E35),
    error = Color(0xFFFF6B70),
    onError = Color(0xFF3B080A),
    errorContainer = Color(0xFF4A1A1D),
    onErrorContainer = Color(0xFFFFDADB),
    surfaceContainerLowest = Color(0xFF0C0E12),
    surfaceContainerLow = Color(0xFF131519),
    surfaceContainer = Color(0xFF16181D),
    surfaceContainerHigh = Color(0xFF1C1F24),
    surfaceContainerHighest = Color(0xFF23262C),
)

@Composable
fun DshTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

/** 内存态主题偏好（默认深色；v1 不持久化到磁盘）。 */
object ThemePrefs {
    enum class Mode { SYSTEM, LIGHT, DARK }

    private val _mode = kotlinx.coroutines.flow.MutableStateFlow(Mode.DARK)
    val mode: kotlinx.coroutines.flow.StateFlow<Mode> = _mode

    fun set(mode: Mode) {
        _mode.value = mode
    }
}
