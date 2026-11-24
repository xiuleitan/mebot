package com.txl.mebot.ui.subscriptions

import android.content.res.Configuration
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.data.local.SubscriptionEntity
import com.txl.mebot.ui.schedule.scheduleColors
import com.txl.mebot.ui.theme.MebotTheme
import java.time.Duration
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun SubscriptionsRoute(onOpenDrawer: () -> Unit, onReturnHome: () -> Unit, model: SubscriptionsViewModel? = null,
    detailId: String? = null, reminderEventId: String? = null, onDetailClosed: () -> Unit = {}) {
    val state = model?.state?.collectAsStateWithLifecycle()?.value ?: SubscriptionsState(loading = false)
    val colors = scheduleColors()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val owner = LocalLifecycleOwner.current
    var chatExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.detailId) { if (state.detailId != null) chatExpanded = false }
    var enteredDetail by remember(detailId) { mutableStateOf(detailId == null) }
    LaunchedEffect(state.detailId, detailId) { if (detailId != null && state.detailId == detailId) enteredDetail = true }
    if (detailId != null) com.txl.mebot.ui.common.BotDetailEntry(detailId,
        showing = state.detailId == detailId || enteredDetail && (state.editor != null || state.deletion != null || chatExpanded), working = state.busy,
        open = { model?.openFromReminder(detailId) }, onClosed = onDetailClosed)
    val snackbar = remember { SnackbarHostState() }
    val ongoing = remember(state.records, state.today) { state.ongoing }
    val ended = remember(state.records, state.today) { state.ended }
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> model?.refresh()
                Lifecycle.Event.ON_STOP -> model?.chat?.saveInput()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(model) {
        if (model != null) while (isActive) {
            val now = ZonedDateTime.now()
            delay(Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone)).toMillis().coerceAtLeast(1000))
            model.refresh()
        }
    }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it); model?.consumeNotice() } }
    Box(Modifier.fillMaxSize().then(if (detailId == null || chatExpanded)
        Modifier.background(Brush.verticalGradient(listOf(colors.backgroundTop, colors.backgroundBottom))) else Modifier)
        .windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (detailId == null || chatExpanded) SubscriptionComposerWorkspace(model = model,
            expanded = chatExpanded, onExpand = { chatExpanded = true }, onDismiss = { chatExpanded = false },
            detailVisible = state.detailId != null,
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxSize()) { bottomPadding ->
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    NavigationButton(R.drawable.ic_menu, R.string.open_sidebar, onOpenDrawer)
                    Text(stringResource(R.string.subscriptions_title), Modifier.weight(1f), color = colors.ink,
                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    NavigationButton(R.drawable.ic_home, R.string.return_home, onReturnHome)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, bottomPadding + 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "summary") { SubscriptionSummary(state, ongoing.size, { model?.refreshStatistics() }) }
                    item(key = "actions") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("持续中的订阅", color = colors.secondary, fontSize = 12.sp)
                        }
                    }
                    if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    state.loadError?.let { message -> item { Column { Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = { model?.refresh() }) { Text("重试") } } } }
                    if (!state.loading && state.loadError == null && ongoing.isEmpty()) item(key = "empty") {
                        Text("暂无持续中的订阅\n在下方说说订阅信息，或发送一张截图", Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            color = colors.secondary, fontSize = 14.sp, lineHeight = 22.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    items(ongoing, key = { it.id }) { card ->
                        SubscriptionCard(card, model, onClick = { focus.clearFocus(); keyboard?.hide(); model?.openDetail(card.id) }, modifier = Modifier.animateItem())
                    }
                    if (ended.isNotEmpty()) item(key = "ended") {
                        TextButton(onClick = { model?.showEnded(true) }, modifier = Modifier.fillMaxWidth()) { Text("已结束的订阅（${ended.size}）") }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).padding(top = 72.dp, start = 16.dp, end = 16.dp))
    }
    if (model != null && ((detailId != null && !enteredDetail) || (state.detailId != null && state.detail == null))) {
        SubscriptionSheet({ model.dismissDetail(); if (detailId != null) onDetailClosed() }, dismissible = !state.busy) { close ->
            SubscriptionSheetTitle("订阅详情", { close(null) }, enabled = !state.busy)
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.notice == null && state.loadError == null) CircularProgressIndicator(Modifier.size(24.dp))
                Text(state.notice ?: state.loadError ?: "正在读取订阅…")
                if (detailId != null && (state.notice != null || state.loadError != null)) TextButton({ model.openFromReminder(detailId) }) { Text("重试") }
            }
        }
    }
    if (model != null && enteredDetail) {
        state.detail?.let { item -> key(item.id) {
            SubscriptionDetailSheet(item, state, model, onDismiss = model::dismissDetail,
                onChat = { model.chat.openSubscription(item.id); chatExpanded = true }, onEdit = { model.openEdit(item) },
                reminderEventId = reminderEventId)
        } }
        state.editor?.let { editor -> key(editor.conversationId) { SubscriptionEditorSheet(editor, state, model) } }
        if (state.endedOpen) SubscriptionEndedSheet(ended, model)
        state.deletion?.let { SubscriptionDeleteDialog(it, state, model) }
    }
}

@Composable
internal fun SubscriptionCard(card: SubscriptionEntity, model: SubscriptionsViewModel?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = scheduleColors()
    val shape = RoundedCornerShape(18.dp)
    val price = buildAnnotatedString {
        append(card.price())
        withStyle(SpanStyle(color = colors.secondary, fontSize = 10.sp, fontWeight = FontWeight.Normal)) { append(" ${card.priceSuffix()}") }
    }
    Surface(modifier.fillMaxWidth().subscriptionCardShadow(shape).animateContentSize(),
        shape = shape, color = colors.surface, contentColor = colors.ink) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 76.dp)
            .clickable(onClickLabel = "查看${card.title}详情", onClick = hapticAction(onClick)).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // 由文字行高确定正方形边长，向右扩展缩略图，同时避免原图撑高卡片。
            Box(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true)) {
                SubscriptionPicture(card.coverAssetId, model, Modifier.matchParentSize())
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(card.title, Modifier.fillMaxWidth(), fontSize = 14.sp, lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    Text(card.dateSummary().replaceFirst(" ", "\n"), Modifier.weight(1f), color = colors.secondary,
                        fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(price, Modifier.widthIn(max = 150.dp), fontSize = 14.sp, lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun SubscriptionDetailField(label: Int, value: String) = SubscriptionDetailField(stringResource(label), value)
@Composable
internal fun SubscriptionDetailField(label: String, value: String) {
    val colors = scheduleColors()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(0.9f), color = colors.secondary, fontSize = 12.sp, lineHeight = 18.sp)
        Text(value, Modifier.weight(1.6f), color = colors.ink, fontSize = 12.sp, lineHeight = 18.sp)
    }
}
@Composable
private fun NavigationButton(icon: Int, label: Int, onClick: () -> Unit) {
    val colors = scheduleColors()
    IconButton(hapticAction(onClick), Modifier.size(48.dp), shape = CircleShape,
        colors = IconButtonDefaults.iconButtonColors(containerColor = colors.surface, contentColor = colors.ink)) {
        Icon(painterResource(icon), stringResource(label), Modifier.size(24.dp))
    }
}
@Preview(name = "订阅管理", showBackground = true)
@Preview(name = "深色订阅管理", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun SubscriptionsPreview() { MebotTheme { SubscriptionsRoute({}, {}) } }
