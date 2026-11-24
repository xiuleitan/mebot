package com.txl.mebot.ui.trading

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.txl.mebot.R
import com.txl.mebot.data.trading.TREND_REASON_LIMIT
import com.txl.mebot.data.trading.TradingTrend
import com.txl.mebot.data.trading.TradingTrendDirection
import com.txl.mebot.ui.common.hapticAction
import kotlinx.coroutines.launch

@Composable
internal fun TradingTrendButton(
    state: Result<TradingTrend?>?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val direction = state?.getOrNull()?.direction
    val accent = direction?.accentColor()
    val label = when {
        state == null -> R.string.trading_trend_loading
        state.isFailure -> R.string.trading_trend_read_failed
        direction == null -> R.string.trading_trend_unset
        else -> direction.label
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            onClick = hapticAction(onClick),
            enabled = state?.isSuccess == true,
            modifier = modifier,
            shape = RoundedCornerShape(6.dp),
            color = accent?.let { lerp(TradingChartBackground, it, 0.28f) } ?: colors.surfaceVariant,
            contentColor = colors.onSurface,
        ) {
            Box(contentAlignment = Alignment.CenterStart) {
                Text(
                    stringResource(R.string.trading_trend_button, stringResource(label)),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
internal fun TradingTrendEditor(
    trend: TradingTrend?,
    onDismiss: () -> Unit,
    onSave: suspend (TradingTrendDirection, String, String) -> Boolean,
) {
    var direction by rememberSaveable { mutableStateOf(trend?.direction) }
    var bullishReason by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(trend?.bullishReason.orEmpty()))
    }
    var bearishReason by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(trend?.bearishReason.orEmpty()))
    }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val fieldShape = RoundedCornerShape(6.dp)
    Dialog(onDismissRequest = { if (!saving) onDismiss() }) {
        Surface(
            modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = TradingChartBackground,
            contentColor = colors.onSurface,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.trading_trend_settings),
                    modifier = Modifier.semantics { heading() },
                    style = typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(Modifier.fillMaxWidth().selectableGroup()) {
                    for (item in TradingTrendDirection.entries) {
                        val selected = direction == item
                        Box(
                            Modifier.weight(1f).heightIn(min = 36.dp)
                                .selectable(
                                    selected = selected,
                                    enabled = !saving,
                                    role = Role.RadioButton,
                                    onClick = hapticAction { direction = item; saveFailed = false },
                                )
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(item.label),
                                style = typography.labelLarge,
                                color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            if (selected) {
                                Box(
                                    Modifier.align(Alignment.BottomCenter).size(width = 16.dp, height = 2.dp)
                                        .background(colors.primary, fieldShape),
                                )
                            }
                        }
                    }
                }
                TrendReasonField(bullishReason, R.string.trading_trend_bullish_reason, enabled = !saving) {
                    bullishReason = it
                }
                TrendReasonField(bearishReason, R.string.trading_trend_bearish_reason, enabled = !saving) {
                    bearishReason = it
                }
                trend?.legacyReason?.takeIf { it.isNotEmpty() }?.let { previousReason ->
                    Text(
                        stringResource(R.string.trading_trend_legacy_reason),
                        color = colors.onSurfaceVariant,
                        style = typography.labelMedium,
                    )
                    SelectionContainer {
                        Text(previousReason, style = typography.bodySmall)
                    }
                }
                if (saveFailed) {
                    Text(
                        stringResource(R.string.trading_trend_save_failed),
                        color = colors.error,
                        style = typography.bodySmall,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !saving, onClick = onDismiss) {
                        Text(stringResource(R.string.trading_trend_cancel), style = typography.labelLarge)
                    }
                    TextButton(
                        enabled = direction != null && !saving,
                        onClick = {
                            direction?.let { selected ->
                                saving = true
                                saveFailed = false
                                val bullishText = bullishReason.text
                                val bearishText = bearishReason.text
                                scope.launch {
                                    val saved = onSave(selected, bullishText, bearishText)
                                    saving = false
                                    saveFailed = !saved
                                    if (saved) onDismiss()
                                }
                            }
                        },
                    ) {
                        Text(
                            stringResource(if (saving) R.string.trading_trend_saving else R.string.trading_trend_save),
                            style = typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendReasonField(
    value: TextFieldValue,
    label: Int,
    enabled: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.text.length <= TREND_REASON_LIMIT) onValueChange(it) },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        textStyle = typography.bodySmall,
        label = { Text(stringResource(label), style = typography.labelMedium) },
        shape = RoundedCornerShape(6.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            disabledContainerColor = colors.surface,
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.outlineVariant,
        ),
        minLines = 2,
        maxLines = 9,
        visualTransformation = TradingLineNumbers,
    )
}

internal val TradingTrendDirection.label: Int
    get() = when (this) {
        TradingTrendDirection.BULLISH -> R.string.trading_trend_bullish
        TradingTrendDirection.BEARISH -> R.string.trading_trend_bearish
        TradingTrendDirection.SIDEWAYS -> R.string.trading_trend_sideways
    }
