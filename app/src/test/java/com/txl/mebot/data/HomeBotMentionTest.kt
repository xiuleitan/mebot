package com.txl.mebot.data

import com.txl.mebot.data.home.*
import com.txl.mebot.ui.home.HomeComposerState
import org.junit.Assert.*
import org.junit.Test

class HomeBotMentionTest {
    @Test fun everyRegisteredMentionPreservesItsBodyAndReplacesWithoutDuplication() {
        for (bot in HomeBot.entries) {
            val text = "@${bot.label} 第一行\n第二行含 @其他人"
            val mention = HomeBotMention.parse(text)
            assertEquals(bot, mention.bot)
            assertEquals("第一行\n第二行含 @其他人", mention.body)
            assertEquals("@档案 第一行\n第二行含 @其他人", mention.withBot(HomeBot.ARCHIVE))
            assertTrue(HomeComposerState(input = text, loaded = true).canSend)
        }
    }
    @Test fun noMentionUnknownNamesAndMentionOnlyCannotBeSent() {
        for (text in listOf("明天买菜", "@不存在 明天买菜", "@日 明天买菜", " @日程 明天买菜", "@日程", "@日程  \n", ""))
            assertFalse(text, HomeComposerState(input = text, loaded = true).canSend)
        assertFalse(HomeComposerState(input = "@日程 买菜", loaded = true, deciding = true).canSend)
        assertFalse(HomeComposerState(input = "@日程 买菜", loaded = true, busy = true).canSend)
    }
}
