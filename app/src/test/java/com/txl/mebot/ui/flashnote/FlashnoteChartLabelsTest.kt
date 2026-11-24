package com.txl.mebot.ui.flashnote

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class FlashnoteChartLabelsTest {
    @Test fun sparseValuesStayImmediatelyAboveTheirBarsAndEmptyDaysStayEmpty() {
        val labels = listOf(Rect(4f, -16f, 12f, -4f), null, Rect(260f, 52f, 268f, 64f))
        assertEquals(labels, placeFlashnoteValueLabels(labels, 3f))
    }

    @Test fun aFullMonthOfDenseValuesKeepsEveryNumberWithoutOverlap() {
        val labels = List(31) { day -> Rect(day * 9f, -12f, day * 9f + 22f, 0f) }
        val placed = placeFlashnoteValueLabels(labels, 3f)
        assertPlacement(labels, placed, 3f)
        assertTrue(placed.filterNotNull().minOf { it.top } < -12f)
    }

    @Test fun largeFontAndUnevenBarHeightsKeepDayAssociationsAndNeverPushNumbersIntoBars() {
        val labels = List(31) { day ->
            if (day % 7 == 0) null else {
                val x = (day * 9f).coerceAtMost(256f)
                val y = ((day * 37) % 100).toFloat() - 24f
                Rect(x, y, x + 48f, y + 24f)
            }
        }
        assertPlacement(labels, placeFlashnoteValueLabels(labels, 6f), 6f)
    }

    private fun assertPlacement(original: List<Rect?>, placed: List<Rect?>, gap: Float) {
        assertEquals(original.size, placed.size)
        original.forEachIndexed { day, before ->
            val after = placed[day]
            if (before == null) assertNull(after) else {
                assertNotNull(after)
                after!!
                assertEquals(before.left, after.left, 0f)
                assertEquals(before.right, after.right, 0f)
                assertEquals(before.height, after.height, 0.001f)
                assertTrue(after.top <= before.top)
            }
        }
        val visible = placed.filterNotNull()
        for (a in visible.indices) for (b in a + 1 until visible.size) {
            assertFalse("Values $a and $b overlap", visible[a].inflate(gap / 2).overlaps(visible[b].inflate(gap / 2)))
        }
    }
}
