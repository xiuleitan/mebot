package com.txl.mebot.ui.home

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class HomeBotEditorTest {
    @Test fun enteringAtBeforeExistingBodyKeepsTheWholeBodySeparateFromTheBotName() {
        val before = TextFieldValue("明天去买菜", TextRange.Zero)
        val after = atomicHomeMentionEdit(before, TextFieldValue("@明天去买菜", TextRange(1)))
        assertEquals("@ 明天去买菜", after.text)
        assertEquals("@日程 明天去买菜", com.txl.mebot.data.home.HomeBotMention.parse(after.text).withBot(com.txl.mebot.data.home.HomeBot.SCHEDULE))
    }
    @Test fun backspaceInsideMentionRemovesItAsOneAtomAndLeavesBodyUntouched() {
        val before = TextFieldValue("@日程 明天买菜", TextRange(4))
        for (text in listOf("@日程明天买菜", "@日 明天买菜", "日程 明天买菜", "@日程", "")) {
            val actual = atomicHomeMentionEdit(before, TextFieldValue(text))
            assertEquals(if (text in listOf("@日程", "")) "" else "明天买菜", actual.text)
        }
        val deletionInBody = TextFieldValue("@日程 明天买", TextRange(7))
        assertEquals(deletionInBody, atomicHomeMentionEdit(before, deletionInBody))
        val selectedAcrossMention = atomicHomeMentionEdit(before, TextFieldValue("买菜"))
        assertEquals("买菜", selectedAcrossMention.text); assertEquals(TextRange.Zero, selectedAcrossMention.selection)
    }
    @Test fun automaticInsertionAndBotReplacementPreserveCursorSelectionAndImeComposition() {
        val before = TextFieldValue("明天买菜", TextRange(2), TextRange(0, 2))
        val inserted = homeMentionExternalValue(before, "@日程 明天买菜")
        assertEquals(TextRange(6), inserted.selection); assertEquals(TextRange(4, 6), inserted.composition)
        val replaced = homeMentionExternalValue(inserted, "@订阅管理 明天买菜")
        assertEquals(TextRange(8), replaced.selection); assertEquals(TextRange(6, 8), replaced.composition)
        assertEquals(before, homeMentionExternalValue(replaced, "明天买菜"))
        val selected = TextFieldValue("@日程 明天买菜", TextRange(4, 8))
        assertEquals(TextRange(6, 10), homeMentionExternalValue(selected, "@订阅管理 明天买菜").selection)
    }
}
