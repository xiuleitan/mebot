package com.txl.mebot.ui.trading

import android.app.Application
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TradingOverviewCaptureTest {
    private val header = Rect(0, 24, 360, 80)

    @Test
    fun startsAtTheQuoteHeaderAndStopsBeforeThePositionArea() {
        val frame = TradingOverviewBounds(
            header, overview = Rect(0, 80, 360, 570), viewport = Rect(0, 80, 360, 720),
        )
        val source = checkNotNull(frame.sourceRect(copied = 0))
        assertEquals(Rect(0, 24, 360, 570), source)
        assertEquals(frame.height, source.height())
    }

    @Test
    fun completesTheOverviewOnAShortScreenWithoutRepeatingTheHeader() {
        val viewport = Rect(0, 80, 360, 360)
        val initial = TradingOverviewBounds(header, overview = Rect(0, 80, 360, 570), viewport)
        val first = checkNotNull(initial.sourceRect(copied = 0))
        assertEquals(Rect(0, 24, 360, 360), first)
        val scrolled = initial.copy(overview = Rect(0, -200, 360, 290))
        val second = checkNotNull(scrolled.sourceRect(copied = first.height()))
        assertEquals(Rect(0, 80, 360, 290), second)
        assertEquals(initial.height, first.height() + second.height())
    }

    @Test
    fun rejectsAClippedStartOrAGapBetweenScrollSlices() {
        val frame = TradingOverviewBounds(
            header, overview = Rect(0, 70, 360, 560), viewport = Rect(0, 80, 360, 360),
        )
        assertNull(frame.sourceRect(copied = 0))
        val scrolledTooFar = frame.copy(overview = Rect(0, -220, 360, 270))
        assertNull(scrolledTooFar.sourceRect(copied = 336))
    }
}
