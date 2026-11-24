package com.txl.mebot.data

import com.txl.mebot.ui.schedule.ScheduleDateNavigationState
import com.txl.mebot.ui.schedule.ScheduleEntry
import com.txl.mebot.ui.schedule.scheduleCardGridIndex
import kotlinx.coroutines.Job
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ScheduleDateNavigationTest {
    @Test fun createdCardTargetMapsToItsGridPositionAcrossBothSections() {
        val day = LocalDate.of(2026, 10, 28)
        val pending = (1..5).map { ScheduleEntry("p$it", "未完成", day, detail = "") }
        val completed = (1..3).map { ScheduleEntry("c$it", "已完成", day, detail = "", completed = true) }
        assertEquals(4, scheduleCardGridIndex(pending, completed, "p5"))
        assertEquals(6, scheduleCardGridIndex(pending, completed, "c1")) // 分割标题独占一个索引。
        assertEquals(8, scheduleCardGridIndex(pending, completed, "c3"))
        assertEquals(0, scheduleCardGridIndex(pending, emptyList(), "p1"))
        assertEquals(1, scheduleCardGridIndex(emptyList(), completed, "c1"))
        assertEquals(-1, scheduleCardGridIndex(pending, completed, "deleted"))
    }

    @Test fun replacingATapCancelsItsJobWithoutReleasingTheNewNavigation() {
        val navigation = ScheduleDateNavigationState()
        val today = LocalDate.of(2026, 10, 25)
        val old = navigation.begin(today)
        val oldJob = Job()
        navigation.attach(old, oldJob)
        val latest = navigation.begin(today.plusDays(1))
        assertTrue(oldJob.isCancelled)
        navigation.finish(old)
        assertTrue(navigation.isInProgress)
        assertTrue(navigation.owns(latest))
        assertEquals(today.plusDays(1), navigation.targetDate)
        val staleJob = Job()
        navigation.attach(old, staleJob)
        assertTrue(staleJob.isCancelled)
        navigation.finish(latest)
        assertFalse(navigation.isInProgress)
        assertNull(navigation.targetDate)
    }

    @Test fun externalNavigationCancelsATapAndLateAttachmentCannotRestartIt() {
        val navigation = ScheduleDateNavigationState()
        val request = navigation.begin(LocalDate.of(2026, 10, 25))
        val job = Job()
        navigation.attach(request, job)
        navigation.cancel()
        assertTrue(job.isCancelled)
        assertFalse(navigation.isInProgress)
        assertFalse(navigation.owns(request))
        assertNull(navigation.targetDate)
        val lateJob = Job()
        navigation.attach(request, lateJob)
        assertTrue(lateJob.isCancelled)
    }
}
