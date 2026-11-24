package com.txl.mebot.data

import com.txl.mebot.data.local.SubscriptionCycleUnit.*
import com.txl.mebot.data.subscriptions.SubscriptionDates
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SubscriptionDatesTest {
    private fun d(value: String) = LocalDate.parse(value)
    @Test fun month31ClampsInFebruaryAndRestoresTheOriginalDayInMarch() {
        val anchor = d("2026-01-31")
        assertEquals(d("2026-02-28"), SubscriptionDates.onOrAfter(anchor, MONTH, 1, false, d("2026-02-01")))
        assertEquals(d("2026-03-31"), SubscriptionDates.after(anchor, MONTH, 1, false, d("2026-02-28")))
        assertEquals(d("2026-04-30"), SubscriptionDates.after(anchor, MONTH, 1, false, d("2026-03-31")))
        assertEquals(d("2026-05-31"), SubscriptionDates.after(anchor, MONTH, 1, false, d("2026-04-30")))
    }
    @Test fun explicitMonthEndDiffersFromDay30AndStaysAtTheEndOfEveryMonth() {
        val anchor = d("2026-04-30")
        assertEquals(d("2026-05-31"), SubscriptionDates.onOrAfter(anchor, MONTH, 1, true, d("2026-05-01")))
        assertEquals(d("2026-05-30"), SubscriptionDates.onOrAfter(anchor, MONTH, 1, false, d("2026-05-01")))
    }
    @Test fun quarterAndHalfYearKeepTheirOriginalPhaseAcrossClampedMonths() {
        val anchor = d("2025-11-30")
        assertEquals(d("2026-02-28"), SubscriptionDates.onOrAfter(anchor, MONTH, 3, false, d("2026-01-01")))
        assertEquals(d("2026-05-30"), SubscriptionDates.after(anchor, MONTH, 3, false, d("2026-02-28")))
        assertEquals(d("2026-05-31"), SubscriptionDates.onOrAfter(anchor, MONTH, 6, true, d("2026-01-01")))
    }
    @Test fun leapDayReturnsInTheNextLeapYearInsteadOfDriftingToFebruary28() {
        val anchor = d("2024-02-29")
        for (year in 2025..2027) assertEquals(d("$year-02-28"), SubscriptionDates.onOrAfter(anchor, YEAR, 1, false, d("$year-01-01")))
        assertEquals(d("2028-02-29"), SubscriptionDates.onOrAfter(anchor, YEAR, 1, false, d("2028-01-01")))
        assertEquals(d("2028-02-29"), SubscriptionDates.onOrAfter(anchor, YEAR, 4, false, d("2025-01-01")))
    }
    @Test fun weeklyAndDailyIntervalsCrossYearsAndRespectInclusiveAndExclusiveBounds() {
        val anchor = d("2025-12-29")
        assertEquals(d("2026-01-12"), SubscriptionDates.onOrAfter(anchor, WEEK, 2, false, d("2026-01-01")))
        assertEquals(d("2026-01-12"), SubscriptionDates.onOrAfter(anchor, WEEK, 2, false, d("2026-01-12")))
        assertEquals(d("2026-01-26"), SubscriptionDates.after(anchor, WEEK, 2, false, d("2026-01-12")))
        assertEquals(d("2026-01-01"), SubscriptionDates.onOrAfter(anchor, DAY, 3, false, d("2025-12-31")))
    }
    @Test fun yearsOfMissedPeriodsJumpToCurrentDateWithoutChangingTheAnchor() {
        val anchor = d("2000-01-31")
        assertEquals(d("2035-10-31"), SubscriptionDates.onOrAfter(anchor, MONTH, 1, false, d("2035-10-01")))
        assertEquals(d("2035-11-30"), SubscriptionDates.onOrAfter(anchor, MONTH, 1, false, d("2035-11-01")))
        assertEquals(anchor, SubscriptionDates.onOrAfter(anchor, MONTH, 1, false, d("1999-01-01")))
    }
    @Test fun nonRecurringHasNoNextDateAndInvalidIntervalsAreRejected() {
        assertNull(SubscriptionDates.onOrAfter(d("2026-01-01"), NONE, 0, false, d("2030-01-01")))
        assertTrue(runCatching { SubscriptionDates.onOrAfter(d("2026-01-01"), MONTH, 0, false, d("2030-01-01")) }.isFailure)
        assertTrue(runCatching { SubscriptionDates.onOrAfter(d("2026-01-01"), DAY, 1, true, d("2030-01-01")) }.isFailure)
    }
}
