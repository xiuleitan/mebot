package com.txl.mebot.ui.subscriptions

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import com.txl.mebot.ui.common.BotEditorSheet
import com.txl.mebot.ui.common.BotEditorSheetCloser
import com.txl.mebot.ui.common.BotEditorSheetTitle

internal typealias SubscriptionSheetCloser = BotEditorSheetCloser

@Composable
internal fun SubscriptionSheet(onDismiss: () -> Unit, dismissible: Boolean = true,
    beforeDismiss: suspend () -> Boolean = { true }, content: @Composable ColumnScope.(SubscriptionSheetCloser) -> Unit) =
    BotEditorSheet(onDismiss, dismissible, beforeDismiss, content)

@Composable
internal fun SubscriptionSheetTitle(title: String, onClose: () -> Unit, enabled: Boolean = true) =
    BotEditorSheetTitle(title, onClose, enabled)
