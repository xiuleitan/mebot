package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.schedule.ScheduleDraft
import com.txl.mebot.data.schedule.ScheduleRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = Application::class)
class ScheduleAndroid8CompatibilityTest {
    @Test fun localDateConversionAndTransactionalStorageWorkAtMinimumSdk() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, MebotDatabase::class.java).build()
        try {
            val repository = ScheduleRepository(
                database, Clock.fixed(Instant.parse("2026-10-20T16:05:00Z"), ZoneOffset.UTC),
            ) { ZoneId.of("Asia/Shanghai") }
            val session = repository.openSession()
            repository.createTask(session.id, "create", ScheduleDraft("会议", time = LocalTime.of(16, 0)))
            val occurrence = repository.observeDate(LocalDate.of(2026, 10, 21)).first().single()
            assertEquals("会议", occurrence.title)
            assertEquals(LocalTime.of(16, 0), occurrence.time)
            assertEquals(listOf(10, 0), repository.pendingReminders(0, Long.MAX_VALUE).map { it.minutesBefore })
        } finally {
            database.close()
        }
    }
}
