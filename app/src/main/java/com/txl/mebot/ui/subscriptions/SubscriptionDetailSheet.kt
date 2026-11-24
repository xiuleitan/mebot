package com.txl.mebot.ui.subscriptions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.data.local.*
import com.txl.mebot.ui.common.DetailDeleteButton
import com.txl.mebot.ui.common.DetailDeleteConfirmationDialog
import com.txl.mebot.ui.schedule.scheduleColors

@Composable
internal fun SubscriptionDetailSheet(card: SubscriptionEntity, page: SubscriptionsState, model: SubscriptionsViewModel,
    onDismiss: () -> Unit, onChat: () -> Unit, onEdit: () -> Unit, reminderEventId: String? = null) {
    val colors = scheduleColors()
    val reminder = com.txl.mebot.ui.common.rememberReminderDetail(com.txl.mebot.data.home.HomeSource.SUBSCRIPTION, card.id, card.revision, reminderEventId)
    val busy = page.busy || reminder.busy
    val imageFlow = remember(card.id, model) { model.observeImages(card.id) }
    val images by imageFlow.collectAsStateWithLifecycle(initialValue = listOfNotNull(card.coverAssetId))
    var fullImageId by rememberSaveable(card.id) { mutableStateOf<String?>(null) }
    SubscriptionSheet(onDismiss, dismissible = !busy) { close ->
        SubscriptionSheetTitle(card.title, { close(null) })
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)) {
            SelectionContainer { Text(card.price() + " " + card.priceSuffix(), fontSize = 20.sp) }
            SubscriptionDetailGallery(images, card.coverAssetId, model, enabled = !busy, onOpenImage = { fullImageId = it })
            page.imageError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))
            SubscriptionDetailField(R.string.subscriptions_billing_date, card.billingDateText(page.today))
            SubscriptionDetailField(R.string.subscriptions_end_date, card.validUntil?.subscriptionFullDate() ?: if (card.cycleUnit == SubscriptionCycleUnit.NONE) "长期有效" else "未填写")
            SubscriptionDetailField(R.string.subscriptions_cycle, card.cycleText() + if (card.anchorAtMonthEnd) " · 按月末日期" else "")
            SubscriptionDetailField(R.string.subscriptions_payment, card.paymentMethod ?: "未填写")
            SubscriptionDetailField(R.string.subscriptions_account, card.account ?: "未填写")
            SelectionContainer { SubscriptionDetailField(R.string.subscriptions_notes, card.notes.ifBlank { "未填写" }) }
            com.txl.mebot.ui.common.ReminderDetailAction(reminder, !busy, onSaved = { close(null) })
        }
        HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailDeleteButton(onClick = { model.requestDelete(card) }, modifier = Modifier.weight(1f),
                enabled = !busy && card.status != SubscriptionStatus.DELETED,
                shape = ButtonDefaults.shape, contentPadding = ButtonDefaults.ContentPadding)
            Button(hapticAction { close(onEdit) }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDCEBFF), contentColor = Color(0xFF1C4D7D))) { Text("编辑") }
            Button(hapticAction { close(onChat) }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF202124))) { Text("聊天") }
        }
    }
    fullImageId?.let { initialImageId -> SubscriptionImagePreview(
        initialImageId = initialImageId, images = images, model = model, coverAssetId = card.coverAssetId,
        enabled = !busy && card.status != SubscriptionStatus.DELETED, busy = page.busy, error = page.imageError,
        onSetCover = { model.changeImage(card, it, remove = false) }, onRemove = { model.changeImage(card, it, remove = true) },
        onDismiss = { fullImageId = null }) }
}

@Composable
internal fun SubscriptionEndedSheet(items: List<SubscriptionEntity>, model: SubscriptionsViewModel) {
    SubscriptionSheet({ model.showEnded(false) }) { close ->
        SubscriptionSheetTitle("已结束的订阅", { close(null) })
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { card -> SubscriptionCard(card, model, { close { model.openDetail(card.id) } }) }
        }
    }
}

@Composable
internal fun SubscriptionDeleteDialog(intent: SubscriptionDeleteIntent, state: SubscriptionsState, model: SubscriptionsViewModel) {
    DetailDeleteConfirmationDialog(
        title = stringResource(R.string.subscription_delete_confirmation_title),
        onDismiss = model::dismissDelete, onConfirm = model::performDelete, enabled = !state.busy,
        confirmLabel = stringResource(if (state.busy) R.string.detail_delete_pending else R.string.detail_delete_confirm),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.subscription_delete_confirmation_body, intent.record.title))
                state.deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
    )
}
