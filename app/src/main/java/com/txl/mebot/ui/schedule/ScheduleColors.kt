package com.txl.mebot.ui.schedule

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.txl.mebot.ui.theme.LocalAppTheme

@Immutable
internal data class ScheduleColors(
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val surface: Color,
    val ink: Color,
    val secondary: Color,
    val accent: Color,
    val onAccent: Color,
    val selected: Color,
    val past: Color,
    val pastInk: Color,
    val cardBottom: Color,
    val completedBottom: Color,
    val divider: Color,
)

@Composable
internal fun scheduleColors(): ScheduleColors {
    val colors = MaterialTheme.colorScheme
    val isDark = LocalAppTheme.current.isDark
    return ScheduleColors(
        backgroundTop = colors.background, backgroundBottom = colors.surfaceContainerLow,
        surface = colors.surface, ink = colors.onSurface, secondary = colors.onSurfaceVariant,
        accent = colors.primary, onAccent = colors.onPrimary, selected = colors.secondaryContainer,
        past = colors.surfaceContainer, pastInk = colors.onSurfaceVariant,
        cardBottom = if (isDark) colors.background else colors.surface,
        completedBottom = if (isDark) colors.background else colors.surfaceContainerHighest,
        divider = colors.outlineVariant,
    )
}

@Immutable
internal data class ScheduleChatColors(
    val panel: Color,
    val input: Color,
    val assistant: Color,
    val user: Color,
)

@Composable
internal fun scheduleChatColors(): ScheduleChatColors {
    val colors = scheduleColors()
    val dark = LocalAppTheme.current.isDark
    val panel = lerp(colors.backgroundTop, colors.surface, 0.38f)
    return ScheduleChatColors(
        panel = panel,
        input = colors.surface,
        assistant = lerp(panel, colors.accent, if (dark) 0.12f else 0.06f),
        user = lerp(panel, colors.accent, if (dark) 0.27f else 0.16f),
    )
}
