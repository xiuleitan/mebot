package com.txl.mebot.ui.subscriptions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.R
import com.txl.mebot.data.local.*
import com.txl.mebot.data.subscriptions.SubscriptionAnalysisFailure
import com.txl.mebot.ui.common.BotComposerWorkspace
import com.txl.mebot.ui.common.BotChatBubble
import com.txl.mebot.ui.common.BotChatHistoryButton
import com.txl.mebot.ui.common.BotChatMessage
import com.txl.mebot.ui.common.botChatContentPadding
import com.txl.mebot.ui.common.ComposerPendingMediaRow
import com.txl.mebot.ui.schedule.DeviceMediaPanel
import com.txl.mebot.ui.schedule.attachmentPickerActions
import com.txl.mebot.ui.schedule.scheduleChatTextSize
import com.txl.mebot.ui.schedule.scheduleChatBubbleTextSize
import com.txl.mebot.ui.schedule.scheduleColors

/** 输入、加号面板、键盘和开合动画沿用日程共用工作区；会话与原图以本地存储为准。 */
@Composable
internal fun SubscriptionComposerWorkspace(model: SubscriptionsViewModel?, expanded: Boolean, detailVisible: Boolean,
    onExpand: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier,
    onHomeRecord: ((String) -> Unit)? = null, content: @Composable (Dp) -> Unit) {
    val controller = model?.chat
    val state = controller?.state?.collectAsStateWithLifecycle()?.value ?: SubscriptionChatState(loading = false)
    val colors = scheduleColors()
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(detailVisible) { if (detailVisible) previewId = null }
    val actions = attachmentPickerActions(prepare = { controller?.preparePicker() == true },
        picked = { uris, _ -> controller?.picked(uris) }, prepareCamera = { controller?.prepareCamera() },
        cameraResult = { controller?.cameraResult(it) }, report = { controller?.reportPickerFailure() })
    val pending = state.pending
    val editable = model != null && state.sessionId != null && !state.loading && !state.loadError &&
        !state.confirming && !state.importing && (expanded || state.record == null && !state.analyzing)
    val selected = state.selectedSources.mapNotNull { (uri, asset) -> pending.indexOfFirst { it.asset.id == asset }.takeIf { it >= 0 }?.let { uri to it + 1 } }.toMap()
    BotComposerWorkspace(input = state.input, editable = editable, sending = state.sending,
        canSend = editable && !state.sending && !state.analyzing && (state.input.isNotBlank() || pending.isNotEmpty()),
        clearEnabled = !state.busy && !state.inputError, expanded = expanded, hasPendingMedia = pending.isNotEmpty(),
        hasConversation = state.messages.isNotEmpty() || state.analyzing || state.proposal != null, working = state.analyzing,
        onInputChanged = { controller?.changeInput(it) }, onSaveInput = { controller?.saveInput() }, onSend = { controller?.send() },
        onClear = { controller?.openCreation(forceNew = true) }, onExpand = onExpand, onDismiss = onDismiss,
        inputLabel = stringResource(R.string.subscriptions_input_label), placeholder = stringResource(R.string.subscriptions_input_placeholder), clearLabel = "新建订阅会话",
        showClearAction = onHomeRecord == null && state.sessionId != null && !state.loading && state.record == null,
        resetAfterCollapse = onHomeRecord == null && !detailVisible && state.record != null && !state.busy && !state.analyzing && !state.inputError,
        onCollapsed = { controller?.openCreation() }, modifier = modifier,
        notices = {
            if (state.importing) Text("正在保存截图…", color = colors.secondary, fontSize = 11.sp)
            if (state.inputError) TextButton({ controller?.saveInput() }) { Text("输入暂未保存 · 重试", color = MaterialTheme.colorScheme.error) }
            if (state.loadError) TextButton({ controller?.retry() }) { Text("会话读取失败 · 重试", color = MaterialTheme.colorScheme.error) }
        },
        pendingMedia = { add ->
            ComposerPendingMediaRow(pending, key = { it.asset.id }, isImage = { true },
                enabled = editable && !state.sending && !state.analyzing, onAdd = add,
                onRemove = { controller?.discard(it.asset.id) },
                thumbnail = { item, tileModifier ->
                    Surface(onClick = { previewId = item.asset.id }, shape = RoundedCornerShape(13.dp), modifier = tileModifier) {
                        SubscriptionPicture(item.asset.id, model, Modifier.fillMaxSize())
                    }
                })
        }, mediaPanel = { panel -> DeviceMediaPanel(actions, editable && !state.analyzing, selected, { controller?.pickLocal(it) }, panel) },
        toolbarActions = { BotChatHistoryButton(enabled = !state.busy && controller != null) { controller?.history() } },
        conversation = {
            SubscriptionChatContent(state, model, onPreview = { previewId = it }, onOpenRecord = {
                onDismiss(); state.record?.let { if (onHomeRecord != null) onHomeRecord(it.id) else model?.openDetail(it.id) }
            }, modifier = Modifier.weight(1f))
        }, content = content)
    if (state.historyOpen && controller != null) SubscriptionSheet(controller::dismissHistory) { close ->
        SubscriptionSheetTitle("订阅会话", { close(null) })
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { TextButton({ close { controller.openCreation(forceNew = true); onExpand() } }) { Text("新建订阅会话") } }
            items(state.chats, key = { it.conversation.id }) { row ->
                Surface(onClick = { close { controller.openSession(row.conversation.id); onExpand() } }, shape = RoundedCornerShape(14.dp), color = colors.past) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.subscriptionTitle ?: "未完成的订阅", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(row.draftText.ifBlank { row.lastText ?: "尚未发送消息" }, fontSize = 12.sp, color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (state.historyLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.historyMore) item { TextButton({ controller.history(true) }, enabled = !state.historyLoading) { Text("加载更早会话") } }
        }
    }
    previewId?.let { asset -> SubscriptionImagePreview(asset, model) { previewId = null } }
}

@Composable
internal fun SubscriptionChatContent(state: SubscriptionChatState, model: SubscriptionsViewModel?, onPreview: (String) -> Unit,
    onOpenRecord: () -> Unit, modifier: Modifier) {
    val colors = scheduleColors()
    val controller = model?.chat
    val list = rememberLazyListState()
    LaunchedEffect(state.sessionId, state.messages.lastOrNull()?.id, state.proposal?.operation?.id, state.analyzing) {
        withFrameNanos { }
        val last = list.layoutInfo.totalItemsCount - 1
        if (last >= 0) list.animateScrollToItem(last)
    }
    Column(modifier.fillMaxWidth()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = botChatContentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.hasOlder) item { TextButton({ controller?.older() }, enabled = !state.olderLoading) { Text(if (state.olderLoading) "读取中…" else "加载更早消息") } }
            if (state.messages.isEmpty() && !state.loading) item {
                BotChatMessage(stringResource(R.string.subscriptions_chat_hint), user = false)
            }
            items(state.messages, key = { it.id }) { message ->
                val user = message.role == MessageRole.USER
                BotChatMessage(message.content, user) {
                    val images = state.messageMedia[message.id].orEmpty()
                    if (images.isNotEmpty()) LazyRow(Modifier.height(104.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(images, key = { it.assetId }) { image -> Surface(onClick = { onPreview(image.assetId) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.size(100.dp, 104.dp)) {
                            SubscriptionPicture(image.assetId, model, Modifier.fillMaxSize())
                        } }
                    }
                }
            }
            if (state.loading || state.analyzing) item { Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                Text(if (state.loading) "正在读取本地会话…" else "正在整理订阅信息…", fontSize = scheduleChatTextSize(12), color = colors.secondary)
            } }
            state.proposal?.let { proposal -> item(key = "proposal") {
                val values = proposal.values
                val card = values.run { SubscriptionEntity("preview", title, amountMinor, currencyCode, cycleUnit, cycleInterval,
                    anchorDate, anchorAtMonthEnd, nextBillingDate, validUntil, autoRenew, status, account, paymentMethod, notes, coverAssetId, 0, 0, 0) }
                BotChatBubble(user = false) {
                    Text(values.title, fontSize = scheduleChatBubbleTextSize(16), fontWeight = FontWeight.SemiBold)
                    Text(card.price() + " " + card.priceSuffix(), fontSize = scheduleChatBubbleTextSize(16))
                    Text(if (values.autoRenew) "自动续费 · 下次预计扣费 ${values.nextBillingDate?.subscriptionFullDate()}" else "不自动续费 · " + (values.validUntil?.let { "有效期至 ${it.subscriptionFullDate()}" } ?: "长期有效"), fontSize = scheduleChatBubbleTextSize(14))
                    values.coverAssetId?.let { Surface(onClick = { onPreview(it) }, modifier = Modifier.size(84.dp), shape = RoundedCornerShape(12.dp)) { SubscriptionPicture(it, model, Modifier.fillMaxSize()) } }
                    values.account?.let { Text("账号：$it", fontSize = scheduleChatBubbleTextSize(14)) }
                    values.paymentMethod?.let { Text("支付方式：$it", fontSize = scheduleChatBubbleTextSize(14)) }
                    if (values.notes.isNotBlank()) Text(values.notes, fontSize = scheduleChatBubbleTextSize(14), lineHeight = scheduleChatBubbleTextSize(21))
                    Button({ controller?.confirm() }, enabled = !state.busy && !state.analyzing && !state.inputError && state.input.isBlank() && state.pending.isEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (state.confirming) "保存中…" else if (state.record == null) "确认创建" else "确认保存") }
                    if (state.input.isNotBlank() || state.pending.isNotEmpty()) Text("请先发送当前输入，再确认最新信息。",
                        fontSize = scheduleChatTextSize(11), lineHeight = scheduleChatTextSize(17), color = colors.secondary)
                }
            } }
            if (state.failure != null && !state.analyzing) item {
                Text(state.failure.message(), color = MaterialTheme.colorScheme.error, fontSize = scheduleChatTextSize(12), lineHeight = scheduleChatTextSize(19))
                TextButton({ controller?.retry() }, enabled = !state.busy) { Text("重新解析") }
            }
            state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, fontSize = scheduleChatTextSize(12), lineHeight = scheduleChatTextSize(19)) } }
            state.record?.let { record -> item("saved-record") { SubscriptionCard(record, model, onOpenRecord) } }
        }
    }
}
private fun SubscriptionAnalysisFailure.message() = when (this) {
    SubscriptionAnalysisFailure.CONFIGURATION -> "请检查设置中的语言模型、API Key，以及模型是否支持图片。原消息和截图已保留。"
    SubscriptionAnalysisFailure.CREDENTIAL -> "无法读取模型凭据，请在设置中重新保存 API Key。"
    SubscriptionAnalysisFailure.AUTHORIZATION -> "模型鉴权失败，请检查 API Key 和模型使用权限。"
    SubscriptionAnalysisFailure.BILLING -> "模型账户暂不可用，请检查服务余额或额度。"
    SubscriptionAnalysisFailure.NETWORK -> "网络连接失败，消息与截图已保存在本机，可稍后重试。"
    SubscriptionAnalysisFailure.TIMEOUT -> "解析超时，消息与截图已保留，请重试。"
    SubscriptionAnalysisFailure.RATE_LIMIT -> "模型请求较多，请稍后重试。"
    SubscriptionAnalysisFailure.CHANGED -> "订阅在解析期间发生了变化，请重新解析并核对最新内容。"
    SubscriptionAnalysisFailure.INTERRUPTED -> "上次解析已中断，原会话已恢复，点击重新解析继续。"
    SubscriptionAnalysisFailure.IMAGE -> "这次截图无法读取或过大，请换一张清晰截图，或直接描述订阅信息。"
    SubscriptionAnalysisFailure.LOCAL_SAVE -> "解析结果暂未保存，请重试。原消息和截图已保留。"
    else -> "本次解析未完成，请重试或继续补充文字。原消息与截图已保留。"
}
