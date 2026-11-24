package com.txl.mebot.ui.common

import androidx.compose.runtime.*

/** 首页借用 Bot 的原详情流程，所有弹层与编辑都退出后再回到首页。 */
@Composable
internal fun BotDetailEntry(id: String, showing: Boolean, working: Boolean = false,
    open: () -> Unit, onClosed: () -> Unit) {
    var entered by remember(id) { mutableStateOf(false) }
    var requested by remember(id) { mutableStateOf(false) }
    val close by rememberUpdatedState(onClosed)
    LaunchedEffect(id, working) { if (!requested && !working) { requested = true; open() } }
    LaunchedEffect(showing, working, requested) {
        if (requested && showing) entered = true
        else if (entered && !working) { entered = false; close() }
    }
}
