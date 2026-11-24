package com.txl.mebot.ui.home

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.txl.mebot.data.home.HomeBot
import com.txl.mebot.ui.schedule.scheduleColors

@Composable
internal fun homeBotColor(bot: HomeBot): Color {
    val dark = scheduleColors().backgroundTop.luminance() < .5f
    return when (bot) {
        HomeBot.SCHEDULE -> Color(if (dark) 0xff90caf9 else 0xff1967d2)
        HomeBot.FLASHNOTE -> Color(if (dark) 0xffffd180 else 0xff805600)
        HomeBot.DAYIMA -> Color(if (dark) 0xfff5a5bc else 0xffad3267)
        HomeBot.SUBSCRIPTIONS -> Color(if (dark) 0xffceafff else 0xff7040b2)
        HomeBot.ARCHIVE -> Color(if (dark) 0xff9cdbc2 else 0xff246b4b)
    }
}
