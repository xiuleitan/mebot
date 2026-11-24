package com.txl.mebot.ui.trading

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.txl.mebot.data.local.TradingFill
import com.txl.mebot.data.local.TradingLine
import com.txl.mebot.data.trading.TradingContract
import com.txl.mebot.data.trading.TradingProblem
import com.txl.mebot.data.trading.actionLabel
import kotlinx.coroutines.launch

@Composable
internal fun TradingConnectionDialog(connected: Boolean, onDismiss: () -> Unit, model: TradingViewModel) {
    var key by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<TradingProblem?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("币安只读连接") },
        text = {
            Column(
                Modifier.heightIn(max = 370.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    key, { key = it.take(128); problem = null }, Modifier.fillMaxWidth(),
                    label = { Text("API Key") }, enabled = !busy, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    secret, { secret = it.take(128); problem = null }, Modifier.fillMaxWidth(),
                    label = { Text("Secret Key") }, enabled = !busy, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                )
                problem?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && key.isNotBlank() && secret.isNotBlank(),
                onClick = {
                    busy = true
                    model.connect(key, secret) { error ->
                        busy = false
                        problem = error
                        if (error == null) {
                            key = ""
                            secret = ""
                            onDismiss()
                        }
                    }
                },
            ) { Text(if (busy) "正在验证…" else "验证并保存") }
        },
        dismissButton = {
            Row {
                if (connected) {
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        model.disconnect { error ->
                            busy = false
                            problem = error
                            if (error == null) onDismiss()
                        }
                    }) { Text("断开连接") }
                }
                TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
internal fun TradingFillDialog(
    contract: TradingContract,
    fills: List<TradingFill>,
    onDismiss: () -> Unit,
    onSave: suspend (TradingFill, String) -> Boolean,
) {
    var picked by remember(fills.map { it.tradeId }) { mutableStateOf(fills.first().tradeId) }
    val fill = fills.firstOrNull { it.tradeId == picked } ?: fills.first()
    var reason by remember(fill.tradeId, fill.reasonUpdatedAt) { mutableStateOf(fill.reason.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(fill.actionLabel()) },
        text = {
            Column(
                Modifier.heightIn(max = 385.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (fills.size > 1) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        fills.forEachIndexed { index, item ->
                            FilterChip(selected = item.tradeId == picked, onClick = { picked = item.tradeId },
                                label = { Text("成交 " + (index + 1)) }, enabled = !saving)
                        }
                    }
                }
                Text(formatTradeTime(fill.time) + " · " + formatPrice(fill.price, contract.priceDecimals) + " USDT")
                Text(
                    "数量 " + fill.quantity.trimDecimal() + " ${contract.asset} · 已实现盈亏 " + fill.realizedPnl.trimDecimal() + " USDT",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    reason, { reason = it.take(1_000); failed = false }, Modifier.fillMaxWidth(),
                    enabled = !saving, label = { Text("开单理由") }, minLines = 2, maxLines = 5,
                )
                fill.reasonUpdatedAt?.let { Text("理由编辑于 " + formatTradeTime(it), style = MaterialTheme.typography.bodySmall) }
                if (failed) Text("保存失败，请重试。", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    val saved = onSave(fill, reason)
                    saving = false
                    if (saved) onDismiss() else failed = true
                }
            }) { Text(if (saving) "正在保存…" else "保存") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
internal fun TradingLinesDialog(
    contract: TradingContract,
    lines: List<TradingLine>,
    onDismiss: () -> Unit,
    onDelete: suspend (String) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("已保存的线") },
        text = {
            Column(Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState())) {
                if (lines.isEmpty()) Text("还没有保存的线。")
                lines.forEach { line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val price = formatPrice(line.price, contract.priceDecimals)
                        val label = if (line.endPrice == null) {
                            "水平线 " + price
                        } else {
                            "趋势线 " + price + " → " + formatPrice(line.endPrice, contract.priceDecimals)
                        }
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(enabled = !removing, onClick = {
                            removing = true
                            scope.launch {
                                failed = !onDelete(line.id)
                                removing = false
                            }
                        }) { Text("删除") }
                    }
                }
                if (failed) Text("删除失败，请重试。", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}
