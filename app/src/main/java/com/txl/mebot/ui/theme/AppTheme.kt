package com.txl.mebot.ui.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.txl.mebot.R

enum class AppTheme(
    val id: String,
    @param:StringRes val title: Int,
    val isDark: Boolean,
    internal val background: Color,
    internal val surface: Color,
    internal val neutral: Color,
    internal val ink: Color,
    internal val surfaceHigh: Color = lerp(surface, neutral, 0.06f),
    internal val surfaceHighest: Color = lerp(surface, neutral, 0.12f),
    internal val accent: Color = ink,
) {
    BasicLight(
        id = "basic_light", title = R.string.theme_basic_light, isDark = false,
        background = Color(0xFFF2F2F2), surface = Color(0xFFFFFFFF),
        neutral = Color(0xFFCCCCCC), ink = Color(0xFF1A1A1A),
    ),
    BasicDark(
        id = "basic_dark", title = R.string.theme_basic_dark, isDark = true,
        background = Color(0xFF2C2C2C), surface = Color(0xFF3B3B3B),
        neutral = Color(0xFF595959), ink = Color(0xFFF2F2F2),
        surfaceHigh = Color(0xFF4A4A4A), surfaceHighest = Color(0xFF595959),
    ),
    SoftPink(
        id = "soft_pink", title = R.string.theme_soft_pink, isDark = false,
        background = Color(0xFFFFDDDD), surface = Color(0xFFFCF8F8),
        neutral = Color(0xFFFFCED8), ink = Color(0xFF36232B),
        surfaceHigh = Color(0xFFFBEFEF), surfaceHighest = Color(0xFFFFDDDD),
        accent = Color(0xFF943650),
    ),
    ;

    val swatches: List<Color> get() = when (this) {
        BasicLight -> listOf(background, surface, neutral, ink)
        BasicDark -> listOf(background, surface, surfaceHigh, surfaceHighest, neutral, ink).distinct()
        SoftPink -> listOf(neutral, background, surface, surfaceHigh)
    }

    val isMonochrome: Boolean get() = this == BasicLight || this == BasicDark

    companion object {
        // 旧配色按明暗恢复为基础主题，保留用户明确选择的明暗偏好。
        fun fromId(id: String?): AppTheme? = when (id) {
            BasicLight.id, "mist_rose", "clear_blue", "frost_blue", "warm_sand" -> BasicLight
            BasicDark.id, "deep_ocean", "pine_green", "starry_purple", "stone_teal" -> BasicDark
            SoftPink.id -> SoftPink
            else -> null
        }

        fun defaultFor(isDark: Boolean): AppTheme = if (isDark) BasicDark else BasicLight
    }
}
