package com.txl.mebot.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.txl.mebot.R
import com.txl.mebot.ui.schedule.scheduleChatBubbleTextSize
import com.txl.mebot.ui.schedule.scheduleChatColors
import com.txl.mebot.ui.schedule.scheduleColors

internal val botChatContentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)

/** 以日程为基准：用户右、助手左，90%宽度，正文14sp／行距21sp。 */
@Composable
internal fun BotChatBubble(user: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val palette = scheduleChatColors()
    Row(modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(Modifier.fillMaxWidth(0.9f),
            shape = if (user) RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 5.dp),
            color = if (user) palette.user else palette.assistant, contentColor = scheduleColors().ink) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
    }
}

@Composable
internal fun BotChatMessage(text: String, user: Boolean, modifier: Modifier = Modifier, media: @Composable () -> Unit = {}) {
    BotChatBubble(user, modifier) {
        media()
        if (text.isNotBlank()) SelectionContainer {
            Text(text, color = scheduleColors().ink, fontSize = scheduleChatBubbleTextSize(14), lineHeight = scheduleChatBubbleTextSize(21))
        }
    }
}

@Composable
internal fun BotChatHistoryButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(12.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent, contentColor = scheduleColors().ink)) {
        Icon(painterResource(R.drawable.ic_chat), stringResource(R.string.bot_chat_history), Modifier.size(21.dp))
    }
}
