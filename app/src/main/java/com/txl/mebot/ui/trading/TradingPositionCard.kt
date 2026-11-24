package com.txl.mebot.ui.trading

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.txl.mebot.R
import com.txl.mebot.data.local.TradingPriceAlert
import com.txl.mebot.data.trading.PositionAlertChoice
import com.txl.mebot.data.trading.TradingContract
import com.txl.mebot.data.trading.TradingPosition
import com.txl.mebot.data.trading.TradingPositionDetails
import com.txl.mebot.data.trading.TradingPositionPlan
import com.txl.mebot.data.trading.TradingTrendDirection
import com.txl.mebot.data.trading.alertTarget
import com.txl.mebot.data.trading.alerts.ProbeAlertException
import com.txl.mebot.data.trading.alerts.TradingAlertNotifications
import com.txl.mebot.data.trading.alerts.TradingPositionAlert
import com.txl.mebot.data.trading.parsePositionPlanPrice
import com.txl.mebot.data.trading.tradingPositionMetrics
import com.txl.mebot.ui.common.hapticAction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun TradingPositionCard(
    contract: TradingContract,
    details: TradingPositionDetails,
    plan: TradingPositionPlan,
    markPrice: BigDecimal?,
    onClick: () -> Unit,
) {
    val position = details.position
    val metrics = tradingPositionMetrics(position, plan, markPrice)
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val isLong = position.side == "LONG"
    val trend = details.trendAtOpen
    val opposesTrend = when (trend) {
        TradingTrendDirection.BULLISH -> !isLong
        TradingTrendDirection.BEARISH -> isLong
        else -> false
    }
    val trendText = trend?.let { stringResource(it.label) } ?: stringResource(R.string.trading_position_unrecorded)
    val openingDay = details.openingFill?.let { Instant.ofEpochMilli(it.time).atZone(ZoneId.systemDefault()).dayOfWeek }
    val weekday = openingDay?.getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE)
        ?: stringResource(R.string.trading_position_day_unrecorded)
    val weekend = openingDay == DayOfWeek.SATURDAY || openingDay == DayOfWeek.SUNDAY
    val pnl = metrics.floatingPnl
    val risk = metrics.riskMultiple?.let { (if (it.signum() > 0) "+" else "") + it.toPlainString() + "R" } ?: "—"

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        onClick = hapticAction(onClick),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF23272F),
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlowRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        PositionBadge(
                            stringResource(if (isLong) R.string.trading_position_long else R.string.trading_position_short),
                            if (isLong) TradingUp else TradingDown,
                        )
                        if (opposesTrend) {
                            Icon(
                                painterResource(R.drawable.ic_trading_position_warning),
                                contentDescription = stringResource(R.string.trading_position_direction_mismatch),
                                modifier = Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(12.dp),
                                tint = Color(0xFFFFC45C),
                            )
                        }
                    }
                    PositionBadge(stringResource(R.string.trading_trend_button, trendText), trend?.accentColor())
                    PositionBadge(weekday, if (weekend) TradingDown else null)
                }
                Text(
                    stringResource(R.string.trading_position_current_risk, risk),
                    modifier = Modifier.widthIn(max = 120.dp).padding(vertical = 3.dp),
                    style = typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    textAlign = TextAlign.End,
                    color = pnlColor(metrics.riskMultiple),
                )
            }
            PositionValueRow(
                stringResource(R.string.trading_position_entry_price, formatPrice(position.entryPrice, contract.priceDecimals)),
                position.quantity.trimDecimal() + " " + contract.asset,
                colors.onSurfaceVariant,
            )
            PositionValueRow(
                stringResource(R.string.trading_position_floating_pnl, formatTradingPnl(pnl)),
                stringResource(R.string.trading_position_entry_value, formatPrice(metrics.entryValue.toPlainString())),
                pnlColor(pnl),
            )
        }
    }
}

@Composable
private fun PositionBadge(label: String, accent: Color?) {
    val colors = MaterialTheme.colorScheme
    Text(
        label,
        modifier = Modifier
            .background((accent ?: colors.onSurfaceVariant).copy(alpha = 0.22f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = accent ?: colors.onSurfaceVariant,
    )
}

@Composable
private fun PositionValueRow(left: String, right: String, leftColor: Color) {
    val style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(left, modifier = Modifier.weight(1f), style = style, color = leftColor)
        Text(right, modifier = Modifier.widthIn(max = 132.dp), style = style, textAlign = TextAlign.End)
    }
}

@Composable
internal fun TradingPositionPlanEditor(
    position: TradingPosition,
    plan: TradingPositionPlan,
    alerts: List<TradingPositionAlert>?,
    onDismiss: () -> Unit,
    onSave: suspend (String, String) -> Boolean,
    onSetAlert: suspend (PositionAlertChoice, String) -> TradingPriceAlert,
    onCancelAlert: suspend (String) -> Unit,
) {
    var stopLoss by rememberSaveable { mutableStateOf(plan.stopLoss.orEmpty()) }
    var takeProfit by rememberSaveable { mutableStateOf(plan.takeProfit.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var updatingAlert by remember { mutableStateOf(false) }
    var alertMessage by remember { mutableStateOf<String?>(null) }
    var alertFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val stop = parsePositionPlanPrice(stopLoss)
    val invalidStop = stopLoss.isNotBlank() && stop == null
    val invalidTarget = takeProfit.isNotBlank() && parsePositionPlanPrice(takeProfit) == null
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val busy = saving || updatingAlert

    fun updateAlert(action: suspend () -> String?) {
        if (saving || updatingAlert) return
        updatingAlert = true
        alertMessage = null
        alertFailed = false
        scope.launch {
            try { alertMessage = action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: ProbeAlertException) { alertMessage = error.detail; alertFailed = true }
            catch (_: Exception) { alertMessage = "暂时无法更新提醒，请重试"; alertFailed = true }
            finally { updatingAlert = false }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
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
                    stringResource(R.string.trading_position_plan_title),
                    style = typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                PositionPriceField(stopLoss, R.string.trading_position_stop_loss, !busy, invalidStop) {
                    stopLoss = it
                    saveFailed = false
                    alertMessage = null
                }
                PositionPriceField(takeProfit, R.string.trading_position_take_profit, !busy, invalidTarget) {
                    takeProfit = it
                    saveFailed = false
                }
                if (invalidStop || invalidTarget || saveFailed) {
                    Text(
                        stringResource(if (saveFailed) R.string.trading_position_save_failed else R.string.trading_position_invalid_price),
                        color = colors.error,
                        style = typography.bodySmall,
                    )
                }
                HorizontalDivider(color = colors.outlineVariant)
                PositionPlanAlerts(
                    position = position, stop = stop, alerts = alerts.orEmpty(), enabled = !busy && alerts != null,
                    onSelect = { choice ->
                        val enteredStop = stopLoss
                        updateAlert {
                            if (!TradingAlertNotifications.canNotify(context)) {
                                throw ProbeAlertException("请先从 K 线图的闹钟开启交易提醒通知")
                            }
                            val rule = onSetAlert(choice, enteredStop)
                            alertResult(rule).takeUnless { rule.status == "active" }
                        }
                    },
                    onCancel = { id ->
                        updateAlert {
                            onCancelAlert(id)
                            null
                        }
                    },
                )
                val message = if (updatingAlert) "正在更新提醒…" else alertMessage
                message?.let {
                    Text(it, color = if (alertFailed) colors.error else colors.onSurfaceVariant, style = typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !busy, onClick = onDismiss) {
                        Text("关闭", style = typography.labelLarge)
                    }
                    TextButton(
                        enabled = !busy && !invalidStop && !invalidTarget,
                        onClick = {
                            saving = true
                            saveFailed = false
                            val stop = stopLoss
                            val target = takeProfit
                            scope.launch {
                                val saved = onSave(stop, target)
                                saving = false
                                saveFailed = !saved
                                if (saved) onDismiss()
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
private fun PositionPlanAlerts(
    position: TradingPosition,
    stop: BigDecimal?,
    alerts: List<TradingPositionAlert>,
    enabled: Boolean,
    onSelect: (PositionAlertChoice) -> Unit,
    onCancel: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val riskAlerts = alerts.filter { it.choice.multiple != null }.sortedBy { it.choice.ordinal }
    val stopAlert = alerts.firstOrNull { it.choice == PositionAlertChoice.STOP }?.rule
    val profitChoices = PositionAlertChoice.entries.filter { it.multiple != null }
    val highlight = Color(0xFFFFC45C)
    val buttonColors = SegmentedButtonDefaults.colors(
        activeContainerColor = highlight,
        activeContentColor = TradingBackground,
        activeBorderColor = highlight,
        disabledActiveContainerColor = highlight,
        disabledActiveContentColor = TradingBackground,
        disabledActiveBorderColor = highlight,
    )
    Text("盈亏比提醒", style = typography.labelLarge, fontWeight = FontWeight.SemiBold)
    MultiChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        profitChoices.forEachIndexed { index, choice ->
            val target = position.alertTarget(stop, choice)
            val label = "${choice.multiple}R"
            val rule = riskAlerts.firstOrNull { it.choice == choice }?.rule
            val active = rule?.status == "active"
            val cancelOnTap = rule?.takeIf {
                it.status == "cancelling" || (active && (target == null || it.threshold == target.price))
            }
            val toggle = hapticAction {
                if (cancelOnTap != null) onCancel(cancelOnTap.requestId)
                else onSelect(choice)
            }
            SegmentedButton(
                checked = active,
                onCheckedChange = { toggle() },
                enabled = enabled && (target != null || cancelOnTap != null),
                shape = SegmentedButtonDefaults.itemShape(index, profitChoices.size, RoundedCornerShape(6.dp)),
                modifier = Modifier.semantics {
                    val price = rule?.threshold ?: target?.price
                    contentDescription = if (price != null) "$label，$price USDT" else label
                },
                colors = buttonColors,
                icon = {},
            ) {
                Text(label, style = typography.labelMedium, fontWeight = if (active) FontWeight.Bold else null)
            }
        }
    }
    if (riskAlerts.isNotEmpty()) {
        riskAlerts.forEach { alert ->
            PositionAlertStatus(alert.rule, enabled, onCancel, label = "${alert.choice.multiple}R")
        }
    } else if (stop == null) {
        Text("填入止损价后可设置提醒", style = typography.bodySmall, color = colors.onSurfaceVariant)
    } else if (position.alertTarget(stop, PositionAlertChoice.R1) == null) {
        Text("请检查止损价与开仓均价，确认可计算有效的提醒价", style = typography.bodySmall, color = colors.onSurfaceVariant)
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val stopTarget = position.alertTarget(stop, PositionAlertChoice.STOP)
        val stopActive = stopAlert?.status == "active"
        FilterChip(
            selected = stopActive,
            onClick = hapticAction {
                if (stopAlert?.status == "cancelling" || stopAlert?.status == "active" &&
                    (stopTarget == null || stopTarget.price == stopAlert.threshold)
                ) onCancel(stopAlert.requestId)
                else onSelect(PositionAlertChoice.STOP)
            },
            enabled = enabled && (stop != null || stopAlert != null && stopAlert.status != "pending"),
            shape = RoundedCornerShape(6.dp),
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = highlight,
                selectedLabelColor = TradingBackground,
                disabledSelectedContainerColor = highlight,
                disabledLabelColor = if (stopActive) TradingBackground else colors.onSurfaceVariant.copy(alpha = 0.38f),
            ),
            label = {
                Text("止损提醒", style = typography.labelMedium, fontWeight = if (stopActive) FontWeight.Bold else null)
            },
        )
        if (stopAlert != null) PositionAlertStatus(stopAlert, enabled, onCancel)
    }
}

@Composable
private fun PositionAlertStatus(
    rule: TradingPriceAlert,
    enabled: Boolean,
    onCancel: (String) -> Unit,
    label: String? = null,
) {
    val status = when (rule.status) {
        "pending" -> "待确认"
        "cancelling" -> "撤销待确认"
        else -> "监控中"
    }
    val prefix = label?.let { "$it · " }.orEmpty()
    FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
        Text(
            "$prefix${rule.threshold} USDT · $status",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            modifier = Modifier.semantics {
                if (label != null) contentDescription = "取消 $label 提醒"
            },
            enabled = enabled,
            onClick = hapticAction { onCancel(rule.requestId) },
        ) {
            Text("取消提醒", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PositionPriceField(value: String, label: Int, enabled: Boolean, invalid: Boolean, onChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(32)) },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        isError = invalid,
        singleLine = true,
        label = { Text(stringResource(label), style = MaterialTheme.typography.labelMedium) },
        textStyle = MaterialTheme.typography.bodySmall,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = RoundedCornerShape(6.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.outlineVariant,
        ),
    )
}

internal fun formatTradingPnl(value: BigDecimal, showPositiveSign: Boolean = false): String {
    val rounded = value.setScale(2, RoundingMode.HALF_UP)
    val sign = if (showPositiveSign && rounded.signum() > 0) "+" else ""
    return sign + rounded.toPlainString()
}
