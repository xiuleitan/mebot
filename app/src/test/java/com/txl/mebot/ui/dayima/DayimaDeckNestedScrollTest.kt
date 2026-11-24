package com.txl.mebot.ui.dayima

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DayimaDeckNestedScrollTest {
    private class Gesture {
        var starts = 0
        var fraction = 0f
        val targets = mutableListOf<Float>()
        val connection = DayimaDeckNestedScroll(
            onStart = { starts++ },
            onDrag = { fraction += it / 300f },
            onStop = { targets += dayimaDeckTarget(fraction, it, 300f, 850f) },
        )
    }
    private val touch = NestedScrollSource.UserInput

    @Test fun scrollingWithinTheHistogramDoesNotMoveOrReleaseTheDeck() = runBlocking {
        val gesture = Gesture()
        assertEquals(Offset.Zero, gesture.connection.onPreScroll(Offset(-60f, 0f), touch))
        assertEquals(Offset.Zero, gesture.connection.onPostScroll(Offset(-60f, 0f), Offset.Zero, touch))
        assertEquals(Velocity.Zero, gesture.connection.onPreFling(Velocity(-1_000f, 0f)))
        assertEquals(Velocity.Zero, gesture.connection.onPostFling(Velocity(-1_000f, 0f), Velocity.Zero))
        assertEquals(0, gesture.starts)
        assertTrue(gesture.targets.isEmpty())
    }

    @Test fun bothHistogramEdgesHandTheRemainingDragToTheDeck() = runBlocking {
        for (sign in listOf(-1f, 1f)) {
            val gesture = Gesture()
            assertEquals(Offset(90f * sign, 0f), gesture.connection.onPostScroll(
                Offset.Zero, Offset(90f * sign, 12f), touch))
            assertEquals(Offset(30f * sign, 0f), gesture.connection.onPreScroll(Offset(30f * sign, 6f), touch))
            gesture.connection.onPreFling(Velocity.Zero)
            assertEquals(1, gesture.starts)
            assertEquals(listOf(sign), gesture.targets)
        }
    }

    @Test fun partialEdgeHandoffCountsOnlyTheUnconsumedDistanceAndAllowsReversal() = runBlocking {
        val gesture = Gesture()
        gesture.connection.onPostScroll(Offset(-55f, 0f), Offset(-5f, 0f), touch)
        assertEquals(-5f / 300f, gesture.fraction, 0.0001f)
        assertEquals(Offset(10f, 0f), gesture.connection.onPreScroll(Offset(10f, 0f), touch))
        gesture.connection.onPreFling(Velocity.Zero)
        assertEquals(listOf(0f), gesture.targets)
    }

    @Test fun anEdgeFlingUsesItsReleaseVelocityAndSettlesOnlyOnce() = runBlocking {
        val gesture = Gesture()
        gesture.connection.onPostScroll(Offset.Zero, Offset(5f, 0f), touch)
        assertEquals(Velocity(-1_200f, 0f), gesture.connection.onPreFling(Velocity(-1_200f, 600f)))
        assertEquals(Velocity.Zero, gesture.connection.onPostFling(Velocity.Zero, Velocity(-1_200f, 600f)))
        assertEquals(listOf(-1f), gesture.targets)
    }

    @Test fun listFlingAndProgrammaticScrollingNeverStartOrExtendACardDrag() = runBlocking {
        val gesture = Gesture()
        val effect = NestedScrollSource.SideEffect
        assertEquals(Offset.Zero, gesture.connection.onPostScroll(Offset.Zero, Offset(-150f, 0f), effect))
        gesture.connection.onPostScroll(Offset.Zero, Offset(12f, 0f), touch)
        val before = gesture.fraction
        assertEquals(Offset.Zero, gesture.connection.onPreScroll(Offset(-150f, 0f), effect))
        assertEquals(Offset.Zero, gesture.connection.onPostScroll(Offset.Zero, Offset(-150f, 0f), effect))
        assertEquals(before, gesture.fraction, 0f)
        gesture.connection.onPreFling(Velocity.Zero)
        assertEquals(listOf(0f), gesture.targets)
    }

    @Test fun verticalScrollingPassesThroughAndTheNextGestureCanStartAfresh() = runBlocking {
        val gesture = Gesture()
        assertEquals(Offset.Zero, gesture.connection.onPostScroll(Offset.Zero, Offset(0f, 150f), touch))
        assertEquals(0, gesture.starts)
        gesture.connection.onPostScroll(Offset.Zero, Offset(-10f, 0f), touch)
        assertEquals(Offset.Zero, gesture.connection.onPreScroll(Offset(0f, 150f), touch))
        assertEquals(Velocity.Zero, gesture.connection.onPreFling(Velocity(0f, 600f)))
        assertEquals(Offset.Zero, gesture.connection.onPreScroll(Offset(60f, 0f), touch))
        gesture.connection.onPostScroll(Offset.Zero, Offset(20f, 0f), touch)
        assertEquals(2, gesture.starts)
    }
}
