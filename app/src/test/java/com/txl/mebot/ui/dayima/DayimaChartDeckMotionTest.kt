package com.txl.mebot.ui.dayima

import org.junit.Assert.*
import org.junit.Test

class DayimaChartDeckMotionTest {
    @Test fun shortDragsReturnButBothLongDirectionsSwitch() {
        assertEquals(0f, dayimaDeckTarget(0.2f, 0f, 300f, 850f))
        assertEquals(0f, dayimaDeckTarget(-0.2f, 0f, 300f, 850f))
        assertEquals(1f, dayimaDeckTarget(0.4f, 0f, 300f, 850f))
        assertEquals(-1f, dayimaDeckTarget(-0.4f, 0f, 300f, 850f))
    }

    @Test fun quickReleaseUsesVelocityIncludingAReversedDrag() {
        assertEquals(1f, dayimaDeckTarget(0.02f, 1_000f, 300f, 850f))
        assertEquals(-1f, dayimaDeckTarget(0.02f, -1_000f, 300f, 850f))
        assertEquals(1f, dayimaDeckTarget(-0.3f, 1_000f, 300f, 850f))
    }

    @Test fun unmeasuredWidthCannotCommitASwitch() {
        assertEquals(0f, dayimaDeckTarget(0.9f, 2_000f, 0f, 850f))
        assertEquals(0f, dayimaDeckTarget(0.9f, 2_000f, Float.NaN, 850f))
    }

    @Test fun rearCardExposesExactlyTheReservedStripAtDifferentWidths() {
        for (width in listOf(220f, 340f, 680f)) {
            val front = dayimaDeckPose(true, 0f, width, 18f)
            val back = dayimaDeckPose(false, 0f, width, 18f)
            assertEquals(0f, front.x, 0.0001f)
            assertEquals(1f, front.scale, 0.0001f)
            assertEquals(width + 18f, back.x + width * back.scale, 0.0001f)
            assertTrue(back.depth > front.depth)
        }
    }

    @Test fun completedSwapMatchesTheRestingPosesWithoutASideJump() {
        for (direction in listOf(-1f, 1f)) {
            val oldFront = dayimaDeckPose(true, direction, 340f, 18f)
            val oldBack = dayimaDeckPose(false, direction, 340f, 18f)
            assertPose(dayimaDeckPose(false, 0f, 340f, 18f), oldFront)
            assertPose(dayimaDeckPose(true, 0f, 340f, 18f), oldBack)
        }
    }

    @Test fun depthReorderingHappensOnlyWhileTheOutgoingCardIsTransparent() {
        for (direction in listOf(-1f, 1f)) {
            assertTrue(dayimaDeckPose(true, direction * 0.49999f, 340f, 18f).alpha < 0.001f)
            assertTrue(dayimaDeckPose(true, direction * 0.50001f, 340f, 18f).alpha < 0.001f)
        }
    }

    @Test fun continuousDragAndReturnStayWithinVisibleLayerBounds() {
        for (step in -100..100) for (front in listOf(false, true)) {
            val pose = dayimaDeckPose(front, step / 100f, 340f, 18f)
            assertTrue(pose.x.isFinite())
            assertTrue(pose.depth in 0f..1f)
            assertTrue(pose.scale in 0.9599f..1.0001f)
            assertTrue(pose.alpha in 0f..1f)
        }
    }

    @Test fun outgoingCardReturnsBehindTheRightEdgeWithoutASecondLeftEdge() {
        for (direction in listOf(-1f, 1f)) for (step in 51..100) {
            val fraction = direction * step / 100f
            val outgoing = dayimaDeckPose(true, fraction, 340f, 18f)
            val incoming = dayimaDeckPose(false, fraction, 340f, 18f)
            assertTrue(outgoing.x >= incoming.x)
        }
    }

    private fun assertPose(expected: DayimaDeckPose, actual: DayimaDeckPose) {
        assertEquals(expected.x, actual.x, 0.0001f)
        assertEquals(expected.depth, actual.depth, 0.0001f)
        assertEquals(expected.scale, actual.scale, 0.0001f)
        assertEquals(expected.alpha, actual.alpha, 0.0001f)
    }
}
