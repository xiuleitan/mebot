package com.txl.mebot.ui.trading

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.R
import com.txl.mebot.data.local.TradingPriceAlert
import com.txl.mebot.data.trading.TradingContract
import com.txl.mebot.data.trading.alerts.ProbeAlertException
import com.txl.mebot.data.trading.alerts.TradingAlertDraft
import com.txl.mebot.data.trading.alerts.TradingAlertKind
import com.txl.mebot.data.trading.alerts.TradingAlertMode
import com.txl.mebot.data.trading.alerts.TradingAlertNotifications
import com.txl.mebot.data.trading.alerts.TradingAlertsRepository
import com.txl.mebot.data.trading.alerts.canCancel
import com.txl.mebot.data.trading.alerts.canRetry
import com.txl.mebot.data.trading.alerts.isOpeningOpportunity
import com.txl.mebot.ui.common.hapticAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val PriceAlertControlShape = RoundedCornerShape(6.dp)
private val PriceAlertButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)

@Composable
internal fun TradingPriceAlertButton(hasActiveAlerts: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            onClick = hapticAction(onClick),
            modifier = modifier.width(32.dp).semantics {
                if (hasActiveAlerts) stateDescription = "有正在监控的价格提醒"
            },
            shape = PriceAlertControlShape,
            color = colors.surfaceVariant,
            contentColor = colors.primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_alarm), "价格提醒", Modifier.size(19.dp))
                if (hasActiveAlerts) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp)
                            .size(6.dp).background(Color(0xFFFFC45C), CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
internal fun TradingPriceAlertsDialog(
    contract: TradingContract,
    repository: TradingAlertsRepository,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val endpoint by remember(repository) { repository.endpoint }.collectAsStateWithLifecycle(initialValue = null)
    val rules by remember(repository, contract.symbol) { repository.openRules(contract.symbol) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var mode by rememberSaveable { mutableStateOf(TradingAlertMode.TARGET) }
    var threshold by rememberSaveable(contract.symbol) { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("15") }
    var unit by rememberSaveable { mutableStateOf("percent") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var syncFailed by remember { mutableStateOf(false) }
    var canNotify by remember(context) { mutableStateOf(TradingAlertNotifications.canNotify(context)) }
    var notificationPermissionRequested by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(context, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) canNotify = TradingAlertNotifications.canNotify(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        canNotify = TradingAlertNotifications.canNotify(context)
    }
    LaunchedEffect(repository, endpoint) {
        syncFailed = false
        if (endpoint != null) {
            try {
                repository.syncOpenRules()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                syncFailed = true
            }
        }
    }

    fun perform(action: suspend () -> String?) {
        if (busy) return
        busy = true
        failed = false
        message = null
        scope.launch {
            try { message = action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: ProbeAlertException) { failed = true; message = problem.detail }
            catch (_: Exception) { failed = true; message = "暂时无法完成操作，请重试" }
            finally { busy = false }
        }
    }

    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp) {
            Surface(
                modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = TradingChartBackground,
                contentColor = colors.onSurface,
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "${contract.symbol} 价格提醒",
                        modifier = Modifier.semantics { heading() },
                        style = typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (endpoint == null) {
                        Text(
                            stringResource(R.string.trading_alert_connection_hint),
                            color = colors.onSurfaceVariant, style = typography.bodySmall,
                        )
                        return@Column
                    }
                    Column(
                        Modifier.weight(1f, fill = false).heightIn(max = 480.dp).fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (!canNotify) {
                            PriceAlertAction("开启交易提醒通知", enabled = !busy) {
                                if (Build.VERSION.SDK_INT >= 33 && !notificationPermissionRequested) {
                                    notificationPermissionRequested = true
                                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            TradingAlertMode.entries.forEach { item ->
                                PriceAlertChoice(item.label, selected = mode == item, enabled = !busy) {
                                    mode = item
                                    threshold = ""
                                }
                            }
                        }
                        val thresholdLabel = when (mode) {
                            TradingAlertMode.TARGET -> "目标标记价"
                            TradingAlertMode.WINDOW -> if (unit == "percent") "涨跌幅（%）" else "涨跌价差"
                            TradingAlertMode.OPENING_OPPORTUNITY -> "预期开单价（USDT）"
                        }
                        PriceAlertInput(threshold, { threshold = it.take(48) }, thresholdLabel, enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        if (mode == TradingAlertMode.WINDOW) {
                            PriceAlertInput(minutes, { minutes = it.filter(Char::isDigit).take(6) }, "窗口分钟数",
                                enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                PriceAlertChoice("百分比", selected = unit == "percent", enabled = !busy) { unit = "percent" }
                                PriceAlertChoice("价差", selected = unit == "absolute", enabled = !busy) { unit = "absolute" }
                            }
                        }
                        if (rules.isNotEmpty()) {
                            HorizontalDivider(color = colors.outlineVariant)
                            rules.forEach { rule ->
                                TradingAlertRuleRow(
                                    rule, enabled = !busy,
                                    onRetry = {
                                        perform {
                                            val retried = repository.retry(rule.requestId)
                                            alertResult(retried).takeUnless { retried.status == "active" }
                                        }
                                    },
                                    onCancel = {
                                        perform {
                                            repository.cancel(rule.requestId)
                                            null
                                        }
                                    },
                                )
                            }
                        }
                        if (syncFailed && message == null) {
                            Text(
                                stringResource(R.string.trading_alert_sync_failed), style = typography.bodySmall,
                                color = colors.error,
                            )
                        }
                        message?.let {
                            Text(it, color = if (failed) colors.error else colors.onSurfaceVariant,
                                style = typography.bodySmall)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        FilledTonalButton(
                            enabled = !busy && canNotify && threshold.isNotBlank(),
                            onClick = hapticAction {
                                perform {
                                    val rule = repository.create(
                                        TradingAlertDraft(contract.symbol, mode, threshold, minutes.toIntOrNull(), unit),
                                    )
                                    threshold = ""
                                    alertResult(rule).takeUnless { rule.status == "active" }
                                }
                            },
                            modifier = Modifier.heightIn(min = 32.dp),
                            shape = PriceAlertControlShape,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer,
                            ),
                            contentPadding = PriceAlertButtonPadding,
                        ) {
                            Text(if (busy) "处理中…" else "设置提醒", style = typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PriceAlertInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val interactions = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val labelInset = with(LocalDensity.current) { typography.labelMedium.lineHeight.toDp() / 2 }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colors.surface,
        unfocusedContainerColor = colors.surface,
        disabledContainerColor = colors.surface,
        unfocusedBorderColor = colors.outlineVariant,
        focusedBorderColor = colors.primary,
    )
    BasicTextField(
        value, onValueChange,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}
            .padding(top = labelInset).heightIn(min = 36.dp),
        enabled = enabled, singleLine = true, interactionSource = interactions,
        textStyle = typography.titleSmall.copy(color = if (enabled) colors.onSurface else colors.onSurfaceVariant),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = keyboardOptions,
        decorationBox = { input ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value, innerTextField = input, enabled = enabled, singleLine = true,
                visualTransformation = VisualTransformation.None, interactionSource = interactions,
                label = { Text(label, style = typography.labelMedium) },
                colors = fieldColors,
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = enabled, isError = false, interactionSource = interactions,
                        colors = fieldColors, shape = PriceAlertControlShape,
                    )
                },
            )
        },
    )
}

@Composable
private fun PriceAlertChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected = selected, enabled = enabled, onClick = hapticAction(onClick),
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        shape = PriceAlertControlShape,
        colors = FilterChipDefaults.filterChipColors(
            labelColor = colors.onSurfaceVariant, selectedContainerColor = colors.primaryContainer,
            selectedLabelColor = colors.onPrimaryContainer,
        ),
    )
}

@Composable
private fun PriceAlertAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        enabled = enabled, onClick = hapticAction(onClick),
        modifier = Modifier.heightIn(min = 32.dp), contentPadding = PriceAlertButtonPadding,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun TradingAlertRuleRow(rule: TradingPriceAlert, enabled: Boolean, onRetry: () -> Unit, onCancel: () -> Unit) {
    val kind = TradingAlertKind.entries.firstOrNull { it.value == rule.kind }
    val kindText = when {
        rule.isOpeningOpportunity -> TradingAlertMode.OPENING_OPPORTUNITY.label
        kind == TradingAlertKind.WINDOW -> "${rule.minutes} 分钟涨跌"
        else -> kind?.label.orEmpty()
    }
    val threshold = rule.threshold + if (rule.unit == "percent") "%" else ""
    val label = "$kindText $threshold"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        if (rule.canRetry) PriceAlertAction("重试", enabled = enabled, onClick = onRetry)
        if (rule.canCancel) {
            IconButton(
                onClick = hapticAction(onCancel), enabled = enabled, modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = TradingDown),
            ) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    stringResource(R.string.trading_alert_cancel_rule, label),
                    Modifier.size(15.dp),
                )
            }
        }
    }
}

internal fun alertResult(rule: TradingPriceAlert): String = when (rule.status) {
    "at_target" -> if (rule.isOpeningOpportunity) "当前标记价已到达预期开单价" else "当前标记价已等于目标价"
    "reached" -> "当前标记价已到达提醒价"
    "triggered" -> "提醒已触发"
    else -> "提醒已提交"
}
