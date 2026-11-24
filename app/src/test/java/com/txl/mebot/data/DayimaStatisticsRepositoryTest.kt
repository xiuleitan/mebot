package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.dayima.DayimaChange
import com.txl.mebot.data.dayima.DayimaCycleStatus
import com.txl.mebot.data.dayima.DayimaDateRange
import com.txl.mebot.data.dayima.DayimaPredictionStatus
import com.txl.mebot.data.dayima.DayimaRepository
import com.txl.mebot.data.dayima.DayimaStatistics
import com.txl.mebot.data.dayima.DayimaWriteResult
import com.txl.mebot.data.local.DayimaSpanReason
import com.txl.mebot.data.local.MebotDatabase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DayimaStatisticsRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: MebotDatabase
    private lateinit var clock: StatisticsClock
    private lateinit var repository: DayimaRepository
    private val start = LocalDate.of(2026, 7, 1)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        clock = StatisticsClock()
        connect()
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
    }

    @Test fun savedEditsAndDeletionRecomputeFactsDistributionAndPredictionAfterReopen() = runBlocking {
        val a = record(start)
        val b = record(start.plusDays(28))
        val first = repository.statistics()
        assertEquals(listOf(28L), first.period.frequency.map { it.days })
        assertEquals(28L, first.prediction.prediction?.weightedCycleDays)
        val edited = (repository.execute(DayimaChange.EditPeriod(b.id, b.revision, start.plusDays(30))) as DayimaWriteResult.PeriodSaved).period
        val changed = repository.statistics()
        assertTrue(changed.dataVersion > first.dataVersion)
        assertEquals(listOf(30L), changed.period.frequency.map { it.days })
        assertEquals(30L, changed.prediction.prediction?.weightedCycleDays)
        database.close(); connect()
        assertEquals(changed, repository.statistics())
        repository.execute(DayimaChange.DeletePeriod(edited.id, edited.revision))
        val deleted = repository.statistics()
        assertTrue(deleted.period.cycles.isEmpty())
        assertEquals(a.id, deleted.currentCycle?.startPeriodId)
        assertEquals(DayimaPredictionStatus.INSUFFICIENT_CONTINUOUS_HISTORY, deleted.prediction.status)
    }

    @Test fun birthDateChangesOnlyAgeClassificationNotCycleLengthsOrPrediction() = runBlocking {
        record(start); record(start.plusDays(28)); record(start.plusDays(56))
        val missing = repository.statistics()
        assertTrue(missing.period.ages.isEmpty())
        val profile = (repository.execute(DayimaChange.SaveBirthDate(LocalDate.of(2000, 7, 15))) as DayimaWriteResult.BirthDateSaved).profile
        val known = repository.statistics()
        assertEquals(listOf(25, 26), known.period.cycles.map { it.ageYears })
        assertEquals(missing.period.frequency, known.period.frequency)
        assertEquals(missing.prediction, known.prediction)
        repository.execute(DayimaChange.SaveBirthDate(LocalDate.of(1999, 7, 15), profile.revision))
        val corrected = repository.statistics()
        assertEquals(listOf(26, 27), corrected.period.cycles.map { it.ageYears })
        assertEquals(known.period.distribution, corrected.period.distribution)
    }

    @Test fun markedSpansReviewAndRemovalChangeEligibilityWithoutApplyingStaleFlagsToNewCycles() = runBlocking {
        val a = record(start)
        val b = record(start.plusDays(28))
        val marked = (repository.execute(DayimaChange.SaveSpan(a.id, b.id, 1, 1, DayimaSpanReason.MISSING_RECORDS)) as DayimaWriteResult.SpanSaved).marker
        assertEquals(1, repository.statistics().period.excludedCycleCount)
        val middle = record(start.plusDays(14))
        val subdivided = repository.statistics()
        assertEquals(2, subdivided.period.eligibleCycleCount)
        assertEquals(1, subdivided.period.pendingReviewMarkerCount)
        repository.execute(DayimaChange.DeletePeriod(middle.id, 1))
        val pending = repository.statistics()
        assertEquals(DayimaCycleStatus.PENDING_REVIEW, pending.period.cycles.single().status)
        assertNull(pending.prediction.prediction)
        val current = repository.spanMarkers().single().marker
        assertEquals(marked.id, current.id)
        repository.execute(DayimaChange.SaveSpan(a.id, b.id, 1, 1, DayimaSpanReason.MISSING_RECORDS,
            id = current.id, expectedRevision = current.revision))
        assertEquals(1, repository.statistics().period.excludedCycleCount)
        val reviewed = repository.spanMarkers().single().marker
        repository.execute(DayimaChange.DeleteSpan(reviewed.id, reviewed.revision))
        val unmarked = repository.statistics()
        assertEquals(1, unmarked.period.eligibleCycleCount)
        assertNotNull(unmarked.prediction.prediction)
    }

    @Test fun subscriptionEmitsNewCommittedVersionsAndNeverReturnsADeletedCycle() = runBlocking {
        val updates = Channel<DayimaStatistics>(Channel.UNLIMITED)
        val collector = launch { repository.observeStatistics().collect { updates.send(it) } }
        try {
            assertEquals(0L, receive(updates).dataVersion)
            record(start)
            assertEquals(0, receiveVersion(updates, 1).period.completedCycleCount)
            val b = record(start.plusDays(28))
            assertEquals(1, receiveVersion(updates, 2).period.completedCycleCount)
            repository.execute(DayimaChange.DeletePeriod(b.id, 1))
            val deleted = receiveVersion(updates, 3)
            assertTrue(deleted.period.frequency.isEmpty())
            assertTrue(deleted.period.cycles.isEmpty())
        } finally { collector.cancelAndJoin(); updates.close() }
    }

    @Test fun aVisibleSubscriptionRefreshesAtLocalMidnightWithoutAnotherDatabaseWrite() = runBlocking {
        clock.time = Instant.parse("2026-10-25T15:59:59.990Z").toEpochMilli()
        record(LocalDate.of(2026, 10, 20))
        val updates = Channel<DayimaStatistics>(Channel.UNLIMITED)
        val collector = launch { repository.observeStatistics().collect { updates.send(it) } }
        try {
            val before = receive(updates)
            assertEquals(LocalDate.of(2026, 10, 25), before.asOfDate)
            assertEquals(5L, before.currentCycle?.daysSinceStart)
            clock.time += 20
            val after = withTimeout(10_000) {
                var next = updates.receive()
                while (next.asOfDate == before.asOfDate) next = updates.receive()
                next
            }
            assertEquals(LocalDate.of(2026, 10, 26), after.asOfDate)
            assertEquals(6L, after.currentCycle?.daysSinceStart)
            assertEquals(before.dataVersion, after.dataVersion)
            assertEquals(0, after.period.completedCycleCount)
        } finally { collector.cancelAndJoin(); updates.close() }
    }

    @Test fun twoRangesAreComparedFromOneSnapshot() = runBlocking {
        clock.time = Instant.parse("2027-01-01T04:00:00Z").toEpochMilli()
        var date = start
        val saved = mutableListOf(record(date))
        for (days in listOf(26, 27, 28, 29, 30, 31)) {
            date = date.plusDays(days.toLong())
            saved += record(date)
        }
        val firstRange = DayimaDateRange(saved[0].startDate, saved[2].startDate)
        val secondRange = DayimaDateRange(saved[3].startDate, saved[5].startDate)
        val result = repository.compareStatistics(firstRange, secondRange)
        assertEquals(repository.recordsSnapshot().dataVersion, result.dataVersion)
        assertEquals(3, result.first.eligibleCycleCount)
        assertEquals(3, result.second.eligibleCycleCount)
        assertEquals(3.0, result.medianDifferenceDays!!, 0.0)
    }

    private suspend fun record(date: LocalDate) = (repository.execute(DayimaChange.RecordPeriod(date)) as DayimaWriteResult.PeriodSaved).period
    private suspend fun receive(channel: Channel<DayimaStatistics>) = withTimeout(10_000) { channel.receive() }
    private suspend fun receiveVersion(channel: Channel<DayimaStatistics>, version: Long): DayimaStatistics = withTimeout(10_000) {
        var next = channel.receive()
        while (next.dataVersion < version) next = channel.receive()
        next
    }

    private fun connect() {
        database = MebotDatabase.create(context)
        repository = DayimaRepository(database, clock) { ZoneId.of("Asia/Shanghai") }
    }

    private class StatisticsClock : Clock() {
        @Volatile var time = Instant.parse("2026-10-25T04:00:00Z").toEpochMilli()
        override fun instant(): Instant = Instant.ofEpochMilli(time)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    }
}
