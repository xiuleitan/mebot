package com.txl.mebot.ui.dayima

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.txl.mebot.R
import com.txl.mebot.data.dayima.DayimaCyclePhase
import com.txl.mebot.ui.theme.LocalAppTheme

internal fun DayimaCyclePhase.label(): Int = when (this) {
    DayimaCyclePhase.MENSTRUAL -> R.string.dayima_phase_menstrual
    DayimaCyclePhase.FOLLICULAR -> R.string.dayima_phase_follicular
    DayimaCyclePhase.OVULATION -> R.string.dayima_phase_ovulation
    DayimaCyclePhase.LUTEAL -> R.string.dayima_phase_luteal
}
internal fun DayimaCyclePhase.currentLabel(): Int = when (this) {
    DayimaCyclePhase.MENSTRUAL -> R.string.dayima_phase_menstrual_current
    DayimaCyclePhase.FOLLICULAR -> R.string.dayima_phase_follicular_current
    DayimaCyclePhase.OVULATION -> R.string.dayima_phase_ovulation_current
    DayimaCyclePhase.LUTEAL -> R.string.dayima_phase_luteal_current
}
internal fun DayimaCyclePhase.features(): Int = when (this) {
    DayimaCyclePhase.MENSTRUAL -> R.string.dayima_phase_menstrual_features
    DayimaCyclePhase.FOLLICULAR -> R.string.dayima_phase_follicular_features
    DayimaCyclePhase.OVULATION -> R.string.dayima_phase_ovulation_features
    DayimaCyclePhase.LUTEAL -> R.string.dayima_phase_luteal_features
}

internal data class DayimaPhaseColors(val background: Color, val foreground: Color)
@Composable internal fun DayimaCyclePhase.colors(): DayimaPhaseColors {
    val dark = LocalAppTheme.current.isDark
    return when (this) {
        DayimaCyclePhase.MENSTRUAL -> if (dark) DayimaPhaseColors(Color(0xFF542C3A), Color(0xFFFFC5D5))
            else DayimaPhaseColors(Color(0xFFFBE1E8), Color(0xFF8E294A))
        DayimaCyclePhase.FOLLICULAR -> if (dark) DayimaPhaseColors(Color(0xFF254640), Color(0xFFB0E2D5))
            else DayimaPhaseColors(Color(0xFFDAF0EC), Color(0xFF235C54))
        DayimaCyclePhase.OVULATION -> if (dark) DayimaPhaseColors(Color(0xFF4F4027), Color(0xFFFFD79C))
            else DayimaPhaseColors(Color(0xFFFFE6BD), Color(0xFF795009))
        DayimaCyclePhase.LUTEAL -> if (dark) DayimaPhaseColors(Color(0xFF40334F), Color(0xFFDDC9F5))
            else DayimaPhaseColors(Color(0xFFE8E0F6), Color(0xFF5E4181))
    }
}
