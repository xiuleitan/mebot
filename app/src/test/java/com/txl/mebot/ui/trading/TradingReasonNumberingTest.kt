package com.txl.mebot.ui.trading

import android.app.Application
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TradingReasonNumberingTest {
    @Test
    fun emptyFieldAndNewLineStartANumberedItem() {
        assertEquals("1. ", rendered(""))
        assertEquals("1. 放量\n2. ", rendered("放量\n"))
        assertEquals("1. 放量\n2. 回踩\n3. ", rendered("放量\n回踩\n"))
    }

    @Test
    fun theNoteDraftUsesGeneratedNumbersAndKeepsDecimalValuesAfterEnter() {
        val draft = tradingNoteEditorBody("7、观察原油\r\n1.25% 时再看")
        assertEquals("观察原油\n1.25% 时再看", draft)
        assertEquals("1. 观察原油\n2. 1.25% 时再看\n3. ", rendered(draft + "\n"))
    }

    @Test
    fun removingAnItemRenumbersTheRestAndSupportsDoubleDigits() {
        assertEquals("1. 首项\n2. 末项", rendered("首项\n末项"))
        val list = List(11) { "同一内容" }.joinToString("\n")
        assertEquals("10. 同一内容", rendered(list).lines()[9])
    }

    @Test
    fun caretStartsAfterTheNumberInEmptyFieldsAndAfterEnter() {
        val empty = TradingLineNumbers.filter(AnnotatedString(""))
        assertEquals("1. ".length, empty.offsetMapping.originalToTransformed(0))

        val original = "放量\n回踩\n"
        val numbered = TradingLineNumbers.filter(AnnotatedString(original))
        assertEquals(numbered.text.length, numbered.offsetMapping.originalToTransformed(original.length))
        assertEquals(
            numbered.text.text.indexOf("回踩"),
            numbered.offsetMapping.originalToTransformed(original.indexOf("回踩")),
        )
    }

    @Test
    fun positionsInsideTheNumberMapToTheStartOfItsText() {
        val original = List(9) { "理由" }.joinToString("\n") + "\n"
        val numbered = TradingLineNumbers.filter(AnnotatedString(original))
        val mapping = numbered.offsetMapping
        val tenthPrefix = numbered.text.text.indexOf("10. ")
        for (offset in tenthPrefix..numbered.text.length) {
            assertEquals(original.length, mapping.transformedToOriginal(offset))
        }
        assertEquals(numbered.text.length, mapping.originalToTransformed(original.length))
        for (offset in 0..original.length) {
            assertEquals(offset, mapping.transformedToOriginal(mapping.originalToTransformed(offset)))
        }
    }

    private fun rendered(original: String): String =
        TradingLineNumbers.filter(AnnotatedString(original)).text.text
}
