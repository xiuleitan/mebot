package com.txl.mebot.ui.trading

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.txl.mebot.data.trading.TradingTrendDirection
import java.math.BigDecimal

internal val TradingBackground = Color(0xFF111722)
internal val TradingChartBackground = Color(0xFF161F2C)
internal val TradingUp = Color(0xFF53CFA7)
internal val TradingDown = Color(0xFFF17C91)

internal val TradingColors = darkColorScheme(
    primary = Color(0xFF90CBFA),
    onPrimary = Color(0xFF112839),
    primaryContainer = Color(0xFF223C54),
    onPrimaryContainer = Color(0xFFD3E9FC),
    secondaryContainer = Color(0xFF28394C),
    onSecondaryContainer = Color(0xFFE5EDF8),
    background = TradingBackground,
    onBackground = Color(0xFFE8EFF8),
    surface = TradingBackground,
    onSurface = Color(0xFFE8EFF8),
    surfaceContainer = Color(0xFF1D2836),
    surfaceContainerHigh = Color(0xFF243143),
    surfaceContainerHighest = Color(0xFF2A384B),
    surfaceVariant = Color(0xFF283749),
    onSurfaceVariant = Color(0xFFA6B6CA),
    outline = Color(0xFF798CA3),
    outlineVariant = Color(0xFF2B394B),
    error = Color(0xFFFFA4AC),
)

@Composable
internal fun TradingTrendDirection.accentColor(): Color = when (this) {
    TradingTrendDirection.BULLISH -> TradingUp
    TradingTrendDirection.BEARISH -> TradingDown
    TradingTrendDirection.SIDEWAYS -> MaterialTheme.colorScheme.primary
}

@Composable
internal fun pnlColor(value: BigDecimal?): Color = when (value?.signum()) {
    1 -> TradingUp
    -1 -> TradingDown
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal val TradingTypography = Typography().let { base ->
    base.copy(
        titleMedium = base.titleMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
        titleSmall = base.titleSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        bodySmall = base.bodySmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
        labelLarge = base.labelLarge.copy(fontSize = 11.sp, lineHeight = 15.sp),
        labelMedium = base.labelMedium.copy(fontSize = 10.sp, lineHeight = 14.sp),
        labelSmall = base.labelSmall.copy(fontSize = 9.sp, lineHeight = 13.sp),
    )
}
