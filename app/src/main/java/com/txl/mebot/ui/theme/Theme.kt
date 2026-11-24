package com.txl.mebot.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

val LocalAppTheme = staticCompositionLocalOf { AppTheme.BasicLight }

private fun buildColorScheme(theme: AppTheme): ColorScheme {
    val background = theme.background
    val surface = theme.surface
    val neutral = theme.neutral
    val ink = theme.ink
    val secondaryInk = when (theme) {
        AppTheme.BasicLight -> Color(0xFF555555)
        AppTheme.BasicDark -> Color(0xFFD0D0D0)
        AppTheme.SoftPink -> Color(0xFF6E4956)
    }
    val accent = theme.accent
    val onAccent = if (theme.isDark) background else Color.White
    val selected = if (theme.isDark) theme.surfaceHigh else neutral
    val base = if (theme.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = onAccent,
        primaryContainer = selected, onPrimaryContainer = ink,
        secondary = accent, onSecondary = onAccent,
        secondaryContainer = selected, onSecondaryContainer = ink,
        tertiary = neutral,
        onTertiary = ink,
        tertiaryContainer = selected, onTertiaryContainer = ink,
        background = background, onBackground = ink,
        surface = surface, onSurface = ink, onSurfaceVariant = secondaryInk,
        surfaceVariant = selected,
        surfaceContainerLowest = surface,
        surfaceContainerLow = lerp(background, surface, 0.35f),
        surfaceContainer = lerp(background, surface, 0.65f),
        surfaceContainerHigh = theme.surfaceHigh,
        surfaceContainerHighest = theme.surfaceHighest,
        surfaceBright = theme.surfaceHighest, surfaceDim = background,
        outline = when (theme) {
            AppTheme.BasicLight -> Color(0xFF7F7F7F)
            AppTheme.BasicDark -> Color(0xFFA5A5A5)
            AppTheme.SoftPink -> Color(0xFFA06376)
        },
        outlineVariant = when (theme) {
            AppTheme.BasicLight -> Color(0xFFCCCCCC)
            AppTheme.BasicDark -> Color(0xFF595959)
            AppTheme.SoftPink -> Color(0xFFDDA6B2)
        },
        inverseSurface = ink, inverseOnSurface = background, inversePrimary = onAccent,
        surfaceTint = Color.Transparent, scrim = Color.Black,
    )
}

private val ThemeColorSchemes = AppTheme.entries.associateWith(::buildColorScheme)

internal val AppTheme.colorScheme: ColorScheme get() = ThemeColorSchemes.getValue(this)

@Composable
private fun animateThemeColor(color: Color, label: String): Color =
    animateColorAsState(color, animationSpec = tween(240), label = label).value

@Composable
private fun animatedColorScheme(target: ColorScheme): ColorScheme = target.copy(
    primary = animateThemeColor(target.primary, "theme_primary"),
    onPrimary = animateThemeColor(target.onPrimary, "theme_on_primary"),
    primaryContainer = animateThemeColor(target.primaryContainer, "theme_primary_container"),
    onPrimaryContainer = animateThemeColor(target.onPrimaryContainer, "theme_on_primary_container"),
    secondary = animateThemeColor(target.secondary, "theme_secondary"),
    onSecondary = animateThemeColor(target.onSecondary, "theme_on_secondary"),
    secondaryContainer = animateThemeColor(target.secondaryContainer, "theme_secondary_container"),
    onSecondaryContainer = animateThemeColor(target.onSecondaryContainer, "theme_on_secondary_container"),
    tertiary = animateThemeColor(target.tertiary, "theme_tertiary"),
    onTertiary = animateThemeColor(target.onTertiary, "theme_on_tertiary"),
    tertiaryContainer = animateThemeColor(target.tertiaryContainer, "theme_tertiary_container"),
    onTertiaryContainer = animateThemeColor(target.onTertiaryContainer, "theme_on_tertiary_container"),
    background = animateThemeColor(target.background, "theme_background"),
    onBackground = animateThemeColor(target.onBackground, "theme_on_background"),
    surface = animateThemeColor(target.surface, "theme_surface"),
    onSurface = animateThemeColor(target.onSurface, "theme_on_surface"),
    onSurfaceVariant = animateThemeColor(target.onSurfaceVariant, "theme_on_surface_variant"),
    surfaceVariant = animateThemeColor(target.surfaceVariant, "theme_surface_variant"),
    surfaceContainerLowest = animateThemeColor(target.surfaceContainerLowest, "theme_surface_lowest"),
    surfaceContainerLow = animateThemeColor(target.surfaceContainerLow, "theme_surface_low"),
    surfaceContainer = animateThemeColor(target.surfaceContainer, "theme_surface_container"),
    surfaceContainerHigh = animateThemeColor(target.surfaceContainerHigh, "theme_surface_high"),
    surfaceContainerHighest = animateThemeColor(target.surfaceContainerHighest, "theme_surface_highest"),
    surfaceBright = animateThemeColor(target.surfaceBright, "theme_surface_bright"),
    surfaceDim = animateThemeColor(target.surfaceDim, "theme_surface_dim"),
    outline = animateThemeColor(target.outline, "theme_outline"),
    outlineVariant = animateThemeColor(target.outlineVariant, "theme_outline_variant"),
    inverseSurface = animateThemeColor(target.inverseSurface, "theme_inverse_surface"),
    inverseOnSurface = animateThemeColor(target.inverseOnSurface, "theme_inverse_on_surface"),
    inversePrimary = animateThemeColor(target.inversePrimary, "theme_inverse_primary"),
)

@Composable
fun MebotTheme(
    theme: AppTheme = AppTheme.defaultFor(isSystemInDarkTheme()),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAppTheme provides theme) {
        MaterialTheme(colorScheme = animatedColorScheme(theme.colorScheme), content = content)
    }
}
