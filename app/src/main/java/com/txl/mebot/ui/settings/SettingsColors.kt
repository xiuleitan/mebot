package com.txl.mebot.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.txl.mebot.ui.theme.LocalAppTheme

@Immutable
internal data class SettingsColors(
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val surface: Color,
    val ink: Color,
    val secondary: Color,
    val chevron: Color,
    val shadow: Color,
    val iconBackgroundAlpha: Float,
)

@Composable
internal fun settingsColors(): SettingsColors {
    val colors = MaterialTheme.colorScheme
    return SettingsColors(
        backgroundTop = colors.background, backgroundBottom = colors.surfaceContainerLow,
        surface = colors.surface, ink = colors.onSurface, secondary = colors.onSurfaceVariant,
        chevron = colors.onSurfaceVariant, shadow = colors.scrim.copy(alpha = 0.08f),
        iconBackgroundAlpha = if (LocalAppTheme.current.isDark) 0.17f else 0.09f,
    )
}
