package com.txl.mebot.ui.subscriptions

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.R
import com.txl.mebot.data.subscriptions.*
import com.txl.mebot.ui.schedule.scheduleColors

@Composable
internal fun SubscriptionSummary(state: SubscriptionsState, count: Int, retry: () -> Unit) {
    val colors = scheduleColors()
    val shape = RoundedCornerShape(20.dp)
    val spending = state.spending?.takeIf { it.asOf == state.today && state.spendingError == null }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    val current = spending?.current
    val previous = spending?.previous
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Surface(onClick = { opened = current?.month?.toString() }, enabled = current != null,
                modifier = Modifier.weight(1.4f).subscriptionCardShadow(shape).animateContentSize(),
                shape = shape, color = colors.surface, contentColor = colors.ink) {
                Column(Modifier.fillMaxWidth().heightIn(min = 128.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.subscriptions_month, state.today.year, state.today.monthValue), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(current?.let { SubscriptionMoney.format(it.cnyMinor, "CNY") } ?: "—", fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
                    Text("截至${state.today.monthValue}月${state.today.dayOfMonth}日 · 人民币", color = colors.secondary, fontSize = 11.sp, lineHeight = 17.sp)
                    Text(stringResource(R.string.subscriptions_count, count), color = colors.secondary, fontSize = 11.sp, lineHeight = 17.sp)
                    current?.unconverted?.forEach { Text("未折算 ${SubscriptionMoney.format(it.amountMinor, it.currencyCode)}", color = colors.secondary, fontSize = 11.sp) }
                }
            }
            Surface(onClick = { opened = previous?.month?.toString() }, enabled = previous != null,
                modifier = Modifier.weight(1f).subscriptionCardShadow(shape).animateContentSize(),
                shape = shape, color = colors.surface, contentColor = colors.ink) {
                Column(Modifier.fillMaxWidth().heightIn(min = 112.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.subscriptions_previous_month), color = colors.secondary, fontSize = 12.sp, lineHeight = 18.sp)
                    Text(previous?.let { SubscriptionMoney.format(it.cnyMinor, "CNY") } ?: "—", color = colors.secondary,
                        fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                    previous?.unconverted?.forEach { Text("未折算 ${SubscriptionMoney.format(it.amountMinor, it.currencyCode)}", color = colors.secondary, fontSize = 11.sp) }
                }
            }
        }
        if (state.spendingLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.spendingError?.let { TextButton(retry) { Text("$it · 重试", fontSize = 11.sp) } }
    }
    val selected = listOfNotNull(current, previous).firstOrNull { it.month.toString() == opened }
    if (selected != null) SubscriptionSpendingSheet(selected) { opened = null }
}

@Composable
private fun SubscriptionSpendingSheet(month: SubscriptionMonthSpending, dismiss: () -> Unit) {
    val colors = scheduleColors()
    SubscriptionSheet(dismiss) { close ->
        SubscriptionSheetTitle("${month.month.monthValue}月消费明细", { close(null) })
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text(SubscriptionMoney.format(month.cnyMinor, "CNY"), fontSize = 24.sp, fontWeight = FontWeight.Bold) }
            if (month.entries.isEmpty()) item { Text("本月尚无已到扣费日的消费", color = colors.secondary) }
            items(month.entries, key = { "${it.subscriptionId}:${it.eventId}" }) { entry ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(entry.date.subscriptionFullDate(), color = colors.secondary, fontSize = 11.sp)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(SubscriptionExchangeRates.cnyMinor(entry.amountMinor, entry.currencyCode)?.let { SubscriptionMoney.format(it, "CNY") }
                            ?: "未折算", fontSize = 14.sp)
                        if (entry.currencyCode != "CNY") Text(SubscriptionMoney.format(entry.amountMinor, entry.currencyCode), color = colors.secondary, fontSize = 11.sp)
                    }
                }
                HorizontalDivider(Modifier.padding(top = 12.dp), color = colors.divider)
            }
            item {
                Text("按扣费日累计，年费在扣费当月整笔计入。首次扣费日期可在订阅编辑页调整。", color = colors.secondary, fontSize = 12.sp, lineHeight = 18.sp)
                if (month.entries.any { it.currencyCode in setOf("USD", "HKD") }) Text(
                    "参考汇率 ${SubscriptionExchangeRates.date} · 中国银行\n1美元 = 6.7138元 · 1港元 = 0.8559元",
                    Modifier.padding(top = 10.dp), color = colors.secondary, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
    }
}
