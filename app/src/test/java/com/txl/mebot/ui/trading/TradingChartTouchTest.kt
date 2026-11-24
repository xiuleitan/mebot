package com.txl.mebot.ui.trading

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TradingChartTouchTest {
    @Test fun aParentDragCanCancelAnUnprotectedChartBeforeTheSecondFingerLands() {
        val fixture = PageFixture(withChartHost = false)
        fixture.touch(MotionEvent.ACTION_DOWN, 120f to 220f)
        fixture.touch(MotionEvent.ACTION_MOVE, 120f to 175f)
        assertTrue(fixture.chartActions.contains(MotionEvent.ACTION_CANCEL))
        fixture.touch(MotionEvent.ACTION_MOVE, 120f to 155f)
        assertEquals(1, fixture.page.moves)
    }

    @Test fun chartOwnsThePinchAndTheRemainingFingerUntilTheLastFingerLifts() {
        val fixture = PageFixture()
        fixture.touch(MotionEvent.ACTION_DOWN, 120f to 220f)
        fixture.touch(MotionEvent.ACTION_MOVE, 120f to 175f)
        fixture.touch(MotionEvent.ACTION_POINTER_DOWN or SecondPointer, 120f to 175f, 240f to 220f)
        fixture.touch(MotionEvent.ACTION_MOVE, 80f to 160f, 280f to 210f)
        fixture.touch(MotionEvent.ACTION_POINTER_UP or SecondPointer, 80f to 160f, 280f to 210f)
        fixture.touch(MotionEvent.ACTION_MOVE, 80f to 135f)

        assertTrue(fixture.page.blocksInterception)
        assertEquals(0, fixture.page.moves)
        assertEquals(
            listOf(
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_MOVE,
            ),
            fixture.chartActions,
        )
        fixture.touch(MotionEvent.ACTION_UP, 80f to 135f)
        assertFalse(fixture.page.blocksInterception)
        fixture.assertOutsideDragStaysWithPage()
    }

    @Test fun cancellationUnblocksPageScrollingForTheNextGesture() {
        val fixture = PageFixture()
        fixture.touch(MotionEvent.ACTION_DOWN, 120f to 200f)
        fixture.touch(MotionEvent.ACTION_MOVE, 120f to 150f)
        assertTrue(fixture.page.blocksInterception)
        fixture.touch(MotionEvent.ACTION_CANCEL, 120f to 150f)
        assertEquals(MotionEvent.ACTION_CANCEL, fixture.chartActions.last())
        assertFalse(fixture.page.blocksInterception)
        fixture.assertOutsideDragStaysWithPage()
    }

    private class PageFixture(withChartHost: Boolean = true) {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        val page = DraggingPage(context)
        val chartActions = mutableListOf<Int>()
        private var downTime = 0L
        private var step = 0

        init {
            val plot = View(context).apply {
                setOnTouchListener { view, event ->
                    chartActions += event.actionMasked
                    // A child can release its own lock when one finger goes up.
                    if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    true
                }
            }
            val chart = if (withChartHost) TradingChartHost(context, plot) else plot
            page.addView(chart, FrameLayout.LayoutParams(360, 300).apply { topMargin = 80 })
            page.measure(exactly(360), exactly(600))
            page.layout(0, 0, 360, 600)
        }

        fun assertOutsideDragStaysWithPage() {
            val eventCount = chartActions.size
            touch(MotionEvent.ACTION_DOWN, 120f to 440f)
            touch(MotionEvent.ACTION_MOVE, 120f to 315f) // Enters the chart after starting in the list.
            touch(MotionEvent.ACTION_UP, 120f to 290f)
            assertEquals(1, page.moves)
            assertEquals(eventCount, chartActions.size)
        }

        fun touch(action: Int, vararg points: Pair<Float, Float>) {
            if (action == MotionEvent.ACTION_DOWN) {
                downTime = SystemClock.uptimeMillis()
                step = 0
            }
            val properties = Array(points.size) { id ->
                MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER }
            }
            val coordinates = Array(points.size) { index ->
                MotionEvent.PointerCoords().apply {
                    x = points[index].first
                    y = points[index].second
                    pressure = 1f
                }
            }
            val event = MotionEvent.obtain(
                downTime, downTime + step++ * 16L, action, points.size, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            try { page.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    private class DraggingPage(context: Context) : FrameLayout(context) {
        var blocksInterception = false
            private set
        var moves = 0
            private set

        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            blocksInterception = disallowIntercept
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean = event.actionMasked == MotionEvent.ACTION_MOVE

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_MOVE) moves++
            return true
        }
    }

    private companion object {
        const val SecondPointer = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT

        fun exactly(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    }
}
