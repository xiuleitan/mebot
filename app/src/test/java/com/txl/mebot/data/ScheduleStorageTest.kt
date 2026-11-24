package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.MessageRole
import com.txl.mebot.data.local.ReminderStatus
import com.txl.mebot.data.media.BuiltinCover
import com.txl.mebot.data.media.LocalMediaStore
import com.txl.mebot.data.schedule.RecurrencePattern
import com.txl.mebot.data.schedule.RecurrenceRule
import com.txl.mebot.data.schedule.ScheduleDraft
import com.txl.mebot.data.schedule.SchedulePatch
import com.txl.mebot.data.schedule.ScheduleRepository
import com.txl.mebot.ui.schedule.asEntry
import com.txl.mebot.ui.schedule.sortedForDisplay
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** 在开发机上使用真实 SQLite 验证事务与数据保留，不操作用户手机。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ScheduleStorageTest {
    private lateinit var context: Context
    private lateinit var database: MebotDatabase
    private lateinit var repository: ScheduleRepository
    private lateinit var media: LocalMediaStore
    private val day = LocalDate.of(2026, 10, 21)
    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var clock: MutableClock

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clock = MutableClock(Instant.parse("2026-10-20T16:05:00Z")) // 当地已跨到 21 日。
        database = Room.inMemoryDatabaseBuilder(context, MebotDatabase::class.java).build()
        repository = ScheduleRepository(database, clock) { zone }
        media = LocalMediaStore(context, database, clock)
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        File(context.filesDir, "media").deleteRecursively()
        File(context.cacheDir, "media_previews").deleteRecursively()
    }

    @Test fun dateOnlyDefaultsToLocalTodayAndHasNoAlarm() = runBlocking {
        val session = repository.openSession()
        val task = repository.createTask(session.id, "create", ScheduleDraft("整理笔记"))
        assertEquals(day, task.date)
        assertNull(task.time)
        val occurrence = repository.observeDate(day).first().single()
        assertTrue(database.scheduleDao().reminders(occurrence.id).isEmpty())
        assertTrue(repository.reminderMinutesForSession(session.id).isEmpty())
    }

    @Test fun explicitDateAndMultipleRemindersAreStoredAsIndependentTriggers() = runBlocking {
        val session = repository.openSession()
        repository.createTask(session.id, "create", ScheduleDraft(
            "团队会议", date = day.plusDays(1), time = LocalTime.of(16, 0), reminderMinutes = listOf(0, 30, 10, 10),
        ))
        assertTrue(repository.observeDate(day).first().isEmpty())
        val occurrence = repository.observeDate(day.plusDays(1)).first().single()
        val alarms = database.scheduleDao().reminders(occurrence.id)
        assertEquals(listOf(30, 10, 0), alarms.map { it.minutesBefore })
        assertEquals(listOf("15:30", "15:50", "16:00"), alarms.map {
            Instant.ofEpochMilli(it.triggerAt).atZone(zone).toLocalTime().toString()
        })
        assertTrue(alarms.all { it.status == ReminderStatus.PENDING && it.zoneId == zone.id })
    }

    @Test fun titleLimitUsesUnicodeCharactersAndInvalidWritesLeaveNoTask() = runBlocking {
        val session = repository.openSession()
        assertTrue(runCatching {
            repository.createTask(session.id, "invalid", ScheduleDraft("一".repeat(31)))
        }.isFailure)
        assertNull(repository.taskForSession(session.id))
        assertTrue(repository.operationHistory(session.id).isEmpty())
        val valid = "😀".repeat(30)
        assertEquals(valid, repository.createTask(session.id, "valid", ScheduleDraft(valid)).title)
        assertEquals(valid, repository.taskForSession(session.id)?.title)
    }

    @Test fun storedLegacyTitleSurvivesRescheduleOccurrenceEditAndStoppingRecurrence() = runBlocking {
        val session = repository.openSession()
        val task = repository.createTask(session.id, "create", ScheduleDraft("原来标题", date = day,
            time = LocalTime.of(16, 0), recurrence = RecurrenceRule(RecurrencePattern.DAILY)))
        repository.materializeRange(day, day.plusDays(1))
        val legacy = "一二三四五六七八九十"
        // 模拟升级前已存储的十字标题，不通过新建接口绕过当前规则。
        val sql = database.openHelper.writableDatabase
        sql.execSQL("UPDATE schedule_tasks SET title = ? WHERE id = ?", arrayOf(legacy, task.id))
        sql.execSQL("UPDATE schedule_task_versions SET title = ? WHERE taskId = ?", arrayOf(legacy, task.id))
        sql.execSQL("UPDATE schedule_occurrences SET title = ? WHERE taskId = ?", arrayOf(legacy, task.id))
        val draft = repository.draftForDate(session.id, day)
        val changed = repository.updateTask(session.id, "time-only", draft.copy(time = LocalTime.of(17, 0)),
            task.revision, setOf("time"))
        assertEquals(legacy, changed.title)
        val occurrence = repository.observeDate(day.plusDays(1)).first().single()
        val once = repository.editOccurrence(session.id, occurrence.id,
            SchedulePatch(draft.copy(time = LocalTime.of(18, 0)), setOf("time")), "edit-once", occurrence.revision)
        assertEquals(legacy, once.title)
        assertEquals(LocalTime.of(18, 0), once.time)
        assertTrue(runCatching { repository.updateTask(session.id, "invalid-new-name",
            draft.copy(title = "甲".repeat(31)), changed.revision, setOf("title")) }.isFailure)
        assertEquals(changed.revision, repository.taskForSession(session.id)?.revision)
        val stopped = repository.stopRecurrence(session.id, "stop", changed.revision)
        assertEquals(legacy, stopped.title)
        assertEquals(false, repository.recurrenceForSession(session.id)?.enabled)
    }

    @Test fun dateOnlyRejectsExplicitAlarmAndMinutePrecisionIsValidated() = runBlocking {
        val session = repository.openSession()
        for (draft in listOf(
            ScheduleDraft("复盘", reminderMinutes = listOf(10)),
            ScheduleDraft("复盘", time = LocalTime.of(16, 0, 1)),
            ScheduleDraft("复盘", time = LocalTime.of(16, 0), reminderMinutes = listOf(-1)),
            ScheduleDraft("   "),
        )) {
            assertTrue(runCatching { repository.createTask(session.id, "invalid", draft) }.isFailure)
        }
        assertNull(repository.taskForSession(session.id))
    }

    @Test fun oneConversationCannotCreateAnotherTaskEvenAfterDeletion() = runBlocking {
        val session = repository.openSession()
        val first = repository.createTask(session.id, "first", ScheduleDraft("开会"))
        assertTrue(runCatching { repository.createTask(session.id, "second", ScheduleDraft("健身")) }.isFailure)
        repository.deleteTask(session.id, "delete", first.revision)
        assertTrue(runCatching { repository.createTask(session.id, "third", ScheduleDraft("采购")) }.isFailure)
        assertNull(repository.taskForSession(session.id, includeDeleted = true))
        assertNull(database.scheduleDao().session(session.id))
    }

    @Test fun retryIsIdempotentAcrossMidnightAndRejectsChangedPayload() = runBlocking {
        val session = repository.openSession()
        val draft = ScheduleDraft("开会", time = LocalTime.of(16, 0))
        val first = repository.createTask(session.id, "request", draft)
        clock.current = clock.current.plusSeconds(86_400)
        val retry = repository.createTask(session.id, "request", draft)
        assertEquals(first.id, retry.id)
        assertEquals(day, retry.date)
        assertEquals(1, repository.operationHistory(session.id).size)
        assertEquals(1, repository.observeDate(day).first().size)
        assertTrue(runCatching { repository.createTask(session.id, "request", draft.copy(title = "采购")) }.isFailure)
    }

    @Test fun concurrentCreateRequestsCommitOnlyOneLogicalTask() = runBlocking {
        val session = repository.openSession()
        val results = (1..2).map { number ->
            async { runCatching { repository.createTask(session.id, "request-$number", ScheduleDraft("开会")) } }
        }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, repository.operationHistory(session.id).size)
        assertEquals(1, repository.observeDate(day).first().size)
    }

    @Test fun failedCoverReferenceRollsBackTaskVersionAndOperation() = runBlocking {
        val session = repository.openSession()
        assertTrue(runCatching {
            repository.createTask(session.id, "bad", ScheduleDraft("开会", coverAssetId = "missing"))
        }.isFailure)
        assertNull(repository.taskForSession(session.id))
        assertTrue(repository.operationHistory(session.id).isEmpty())
        assertTrue(repository.observeDate(day).first().isEmpty())
    }

    @Test fun failedAttachmentRollsBackMessageAndRetriedMessagesKeepSequenceOrder() = runBlocking {
        val session = repository.openSession()
        assertTrue(runCatching {
            repository.appendMessage(session.id, "bad", MessageRole.USER, "附件", listOf("missing"))
        }.isFailure)
        assertNull(database.scheduleDao().message("bad"))
        repository.appendMessage(session.id, "z-user", MessageRole.USER, "明天下午四点开会")
        repository.appendMessage(session.id, "a-assistant", MessageRole.ASSISTANT, "已保存")
        repository.appendMessage(session.id, "z-user", MessageRole.USER, "明天下午四点开会")
        assertEquals(listOf("z-user", "a-assistant"), repository.recentMessages(session.id).map { it.id })
    }

    @Test fun sameAssetCanBeReferencedByMultipleMessagesWithoutDuplicatingTaskAttachment() = runBlocking {
        val session = repository.openSession()
        val asset = media.builtin(BuiltinCover.MEETING)
        for (messageId in listOf("first", "second", "second")) {
            repository.appendMessage(session.id, messageId, MessageRole.USER, "参考图", listOf(asset.id))
        }
        assertEquals(2, repository.recentMessages(session.id).size)
        assertEquals(1, repository.attachments(session.id).size)
    }

    @Test fun editingReschedulesSameCardAndCancelsOldReminderGeneration() = runBlocking {
        val session = repository.openSession()
        val task = repository.createTask(session.id, "create", ScheduleDraft("会议", time = LocalTime.of(16, 0)))
        val before = repository.observeDate(day).first().single()
        repository.updateTask(session.id, "edit", ScheduleDraft("会议", time = LocalTime.of(17, 0)), task.revision)
        val after = repository.observeDate(day).first().single()
        assertEquals(before.id, after.id)
        val alarms = database.scheduleDao().reminders(after.id)
        assertEquals(2, alarms.count { it.status == ReminderStatus.CANCELLED })
        val pending = repository.pendingReminders(0, Long.MAX_VALUE)
        assertEquals(listOf(10, 0), pending.map { it.minutesBefore })
        assertTrue(pending.all { it.occurrenceRevision == after.revision })
        assertTrue(runCatching {
            repository.updateTask(session.id, "stale", ScheduleDraft("过时修改"), task.revision)
        }.isFailure)
        assertEquals(LocalTime.of(17, 0), repository.observeDate(day).first().single().time)
        assertEquals(2, repository.operationHistory(session.id).size)
    }

    @Test fun completionCancelsAlarmsAndUndoOnlyRestoresFutureTriggers() = runBlocking {
        val session = repository.openSession()
        repository.createTask(session.id, "create", ScheduleDraft("会议", time = LocalTime.of(16, 0)))
        val before = repository.observeDate(day).first().single()
        val done = repository.setCompleted(session.id, before.id, true, "done", before.revision)
        assertTrue(repository.pendingReminders(0, Long.MAX_VALUE).isEmpty())
        clock.current = Instant.parse("2026-10-21T09:00:00Z") // 当地 17:00，原提醒已过时。
        val restored = repository.setCompleted(session.id, done.id, false, "undo", done.revision)
        assertTrue(repository.pendingReminders(0, Long.MAX_VALUE).isEmpty())
        val restoredReminders = database.scheduleDao().reminders(done.id).filter { it.occurrenceRevision == restored.revision }
        assertEquals(listOf(10, 0), restoredReminders.map { it.minutesBefore })
        assertTrue(restoredReminders.all { it.status == ReminderStatus.MISSED })
    }

    @Test fun anotherConversationCannotChangeAnOccurrence() = runBlocking {
        val first = repository.openSession()
        val second = repository.openSession()
        repository.createTask(first.id, "first", ScheduleDraft("开会"))
        repository.createTask(second.id, "second", ScheduleDraft("采购"))
        val item = repository.observeDate(day).first().first { it.title == "开会" }
        assertTrue(runCatching { repository.setCompleted(second.id, item.id, true, "cross", item.revision) }.isFailure)
        assertNull(database.scheduleDao().occurrence(item.id)?.completedAt)
    }

    @Test fun repeatedMaterializationDoesNotDuplicateOccurrencesAndCompletionIsPerDay() = runBlocking {
        val session = repository.openSession()
        repository.createTask(session.id, "create", ScheduleDraft("健身", recurrence = RecurrenceRule(RecurrencePattern.DAILY)))
        repeat(2) { repository.materializeRange(day, day.plusDays(2)) }
        val first = repository.observeDate(day).first().single()
        repository.setCompleted(session.id, first.id, true, "done", first.revision)
        assertNull(repository.observeDate(day.plusDays(1)).first().single().completedAt)
        assertEquals(listOf(1, 1, 1), repository.observeDateCounts(day, day.plusDays(2)).first().map { it.count })
    }

    @Test fun recurrenceEditsPreserveStoredAndUnmaterializedHistory() = runBlocking {
        val session = repository.openSession()
        val original = ScheduleDraft("原来标题", time = LocalTime.of(16, 0), recurrence = RecurrenceRule(RecurrencePattern.DAILY))
        val task = repository.createTask(session.id, "create", original)
        val done = repository.observeDate(day).first().single()
        repository.setCompleted(session.id, done.id, true, "done", done.revision)
        clock.current = clock.current.plusSeconds(2 * 86_400)
        repository.updateTask(session.id, "edit", original.copy(title = "新的标题", time = LocalTime.of(17, 0)), task.revision)
        repository.materializeRange(day, day.plusDays(3))
        assertEquals("原来标题", repository.observeDate(day).first().single().title)
        assertEquals("原来标题", repository.observeDate(day.plusDays(1)).first().single().title)
        assertEquals("新的标题", repository.observeDate(day.plusDays(2)).first().single().title)
        assertEquals(LocalTime.of(16, 0), repository.observeDate(day.plusDays(1)).first().single().time)
    }

    @Test fun weeklyMonthlyAndIntervalRulesRespectBoundaries() = runBlocking {
        val weekly = repository.openSession()
        repository.createTask(weekly.id, "weekly", ScheduleDraft(
            "周五开会", recurrence = RecurrenceRule(RecurrencePattern.WEEKLY, weekDays = setOf(DayOfWeek.FRIDAY)),
        ))
        repository.materializeRange(day, day.plusDays(7))
        assertEquals(DayOfWeek.FRIDAY, database.scheduleDao().occurrencesForTask(
            requireNotNull(repository.taskForSession(weekly.id)).id,
        ).single().date.dayOfWeek)
        val monthly = repository.openSession()
        val january = LocalDate.of(2027, 1, 31)
        repository.createTask(monthly.id, "monthly", ScheduleDraft(
            "月末复盘", date = january,
            recurrence = RecurrenceRule(RecurrencePattern.MONTHLY, monthDay = 31, endDate = LocalDate.of(2027, 3, 31)),
        ))
        repository.materializeRange(january, LocalDate.of(2027, 4, 1))
        val monthlyDates = database.scheduleDao().occurrencesForTask(requireNotNull(repository.taskForSession(monthly.id)).id).map { it.date }
        assertEquals(listOf(january, LocalDate.of(2027, 3, 31)), monthlyDates)
        val interval = RecurrenceRule(RecurrencePattern.INTERVAL, intervalDays = 100)
        assertEquals(day.plusDays(100), interval.nextOnOrAfter(day, day.plusDays(1)))
    }

    @Test fun disablingRuleSuppressesFutureCardsButKeepsPastCards() = runBlocking {
        val session = repository.openSession()
        val draft = ScheduleDraft("健身", recurrence = RecurrenceRule(RecurrencePattern.DAILY))
        val task = repository.createTask(session.id, "create", draft)
        repository.materializeRange(day, day.plusDays(3))
        clock.current = clock.current.plusSeconds(86_400)
        repository.updateTask(session.id, "disable", draft.copy(recurrence = draft.recurrence?.copy(enabled = false)), task.revision)
        assertEquals(1, repository.observeDate(day).first().size)
        assertTrue(repository.observeDate(day.plusDays(1)).first().isEmpty())
        assertEquals(4, database.scheduleDao().occurrencesForTask(task.id).size)
    }

    @Test fun convertingBetweenSingleAndRecurringKeepsOneVisibleCardPerDate() = runBlocking {
        val session = repository.openSession()
        val task = repository.createTask(session.id, "create", ScheduleDraft("健身"))
        val daily = repository.updateTask(session.id, "daily", ScheduleDraft(
            "健身", recurrence = RecurrenceRule(RecurrencePattern.DAILY),
        ), task.revision)
        repository.materializeRange(day, day.plusDays(2))
        assertEquals(listOf(1, 1, 1), repository.observeDateCounts(day, day.plusDays(2)).first().map { it.count })
        clock.current = clock.current.plusSeconds(86_400)
        repository.updateTask(session.id, "once", ScheduleDraft("单次健身", date = day.plusDays(2)), daily.revision)
        assertEquals(1, repository.observeDate(day).first().size)
        assertTrue(repository.observeDate(day.plusDays(1)).first().isEmpty())
        assertEquals("单次健身", repository.observeDate(day.plusDays(2)).first().single().title)
    }

    @Test fun deleteRemovesMessagesMediaOccurrencesAndAuditTrail() = runBlocking {
        val session = repository.openSession()
        val cover = media.builtin(BuiltinCover.MEETING)
        repository.appendMessage(session.id, "message", MessageRole.USER, "明天开会", listOf(cover.id))
        val task = repository.createTask(session.id, "create", ScheduleDraft("会议", time = LocalTime.of(16, 0), coverAssetId = cover.id))
        repository.deleteTask(session.id, "delete", task.revision)
        assertTrue(repository.observeDate(day).first().isEmpty())
        assertTrue(repository.pendingReminders(0, Long.MAX_VALUE).isEmpty())
        assertNull(repository.taskForSession(session.id))
        assertNull(repository.taskForSession(session.id, includeDeleted = true))
        assertTrue(database.scheduleDao().occurrencesForTask(task.id).isEmpty())
        assertTrue(repository.recentMessages(session.id).isEmpty())
        assertTrue(repository.operationHistory(session.id).isEmpty())
        assertNull(database.mediaDao().asset(cover.id))
    }

    @Test fun dateQueryKeepsAlarmOrderAndCompletedCardsLast() = runBlocking {
        for ((title, time) in listOf("仅日期" to null, "晚上" to LocalTime.of(20, 0), "上午" to LocalTime.of(9, 0))) {
            val session = repository.openSession()
            repository.createTask(session.id, title, ScheduleDraft(title, time = time))
        }
        assertEquals(listOf("上午", "晚上", "仅日期"), repository.observeDate(day).first().map { it.title })
        val morning = repository.observeDate(day).first().first()
        val task = requireNotNull(database.scheduleDao().task(morning.taskId))
        repository.setCompleted(task.sessionId, morning.id, true, "done", morning.revision)
        assertEquals(listOf("晚上", "仅日期", "上午"), repository.observeDate(day).first().map { it.title })
    }

    @Test fun latestCompletionLeadsTheCompletedGroupAfterRestoreRecompleteAndDatabaseReopen() = runBlocking {
        database.close()
        database = MebotDatabase.create(context)
        repository = ScheduleRepository(database, clock) { zone }
        for ((title, time) in listOf("上午" to LocalTime.of(9, 0), "晚上" to LocalTime.of(20, 0), "采购" to null, "清单" to null)) {
            val session = repository.openSession()
            repository.createTask(session.id, title, ScheduleDraft(title, time = time))
        }
        suspend fun complete(title: String, done: Boolean) {
            val occurrence = repository.observeDate(day).first().single { it.title == title }
            val task = requireNotNull(database.scheduleDao().task(occurrence.taskId))
            repository.setCompleted(task.sessionId, occurrence.id, done, java.util.UUID.randomUUID().toString(), occurrence.revision)
            clock.current = clock.current.plusSeconds(10)
        }
        suspend fun assertOrder(vararg titles: String) {
            assertEquals(titles.toList(), repository.observeDate(day).first().map { it.title })
            val cards = repository.observeDisplayDate(day).first().map { it.asEntry() }
            assertEquals(titles.toList(), cards.map { it.title })
            assertEquals(titles.toList(), cards.reversed().sortedForDisplay().map { it.title })
        }
        complete("上午", true)
        complete("晚上", true)
        complete("采购", true) // 最新完成的仅日期任务优先于更早完成的准点任务。
        assertOrder("清单", "采购", "晚上", "上午")
        complete("上午", false)
        assertOrder("上午", "清单", "采购", "晚上")
        complete("上午", true)
        assertOrder("清单", "上午", "采购", "晚上")
        val completedAt = repository.observeDisplayDate(day).first().associate { it.occurrence.id to it.occurrence.completedAt }
        database.close()
        database = MebotDatabase.create(context)
        repository = ScheduleRepository(database, clock) { zone }
        assertOrder("清单", "上午", "采购", "晚上")
        assertEquals(completedAt, repository.observeDisplayDate(day).first().map { it.asEntry() }.associate { it.id to it.completedAt })
    }

    @Test fun databaseCloseAndReopenRetainsSessionTaskRulesAndMessages() = runBlocking {
        database.close()
        database = MebotDatabase.create(context)
        repository = ScheduleRepository(database, clock) { zone }
        val session = repository.openSession()
        repository.appendMessage(session.id, "message", MessageRole.USER, "每天健身")
        val task = repository.createTask(session.id, "create", ScheduleDraft(
            "健身", time = LocalTime.of(18, 0), recurrence = RecurrenceRule(RecurrencePattern.DAILY), reminderMinutes = listOf(15, 0),
        ))
        database.close()
        database = MebotDatabase.create(context)
        repository = ScheduleRepository(database, clock) { zone }
        assertEquals(task.id, repository.taskForSession(session.id)?.id)
        assertEquals(RecurrencePattern.DAILY, repository.recurrenceForSession(session.id)?.pattern)
        assertEquals(listOf(15, 0), repository.reminderMinutesForSession(session.id))
        assertEquals("每天健身", repository.recentMessages(session.id).single().content)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun imageImportDeduplicatesAndCacheClearKeepsOriginalAndReferences() = runBlocking {
        val first = media.importImage({ ByteArrayInputStream(PNG) }, originalName = "first.png")
        val second = media.importImage({ ByteArrayInputStream(PNG) }, originalName = "second.png")
        assertEquals(first.id, second.id)
        val file = requireNotNull(media.fileFor(first.id))
        assertTrue(file.readBytes().contentEquals(PNG))
        val session = repository.openSession()
        repository.createTask(session.id, "create", ScheduleDraft("图片任务", coverAssetId = first.id))
        val cache = File(context.cacheDir, "media_previews/thumbnail.webp")
        cache.parentFile?.mkdirs()
        cache.writeText("derived")
        media.clearDerivedCache()
        assertFalse(cache.exists())
        assertTrue(file.exists())
        assertEquals(first.id, repository.observeDate(day).first().single().coverAssetId)
    }

    @Test fun interruptedImportDoesNotRegisterPartialImage() = runBlocking {
        assertTrue(runCatching {
            media.importImage({ object : InputStream() {
                override fun read(): Int = throw IOException("interrupted")
            } })
        }.isFailure)
        assertTrue(File(context.filesDir, "media/staging").listFiles().orEmpty().isEmpty())
        assertTrue(File(context.filesDir, "media/originals").walkTopDown().none { it.isFile })
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun completedFileCanBeRecoveredAfterDatabaseWriteFailure() = runBlocking {
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_media BEFORE INSERT ON media_assets BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertTrue(runCatching { media.importImage({ ByteArrayInputStream(PNG) }) }.isFailure)
        val originals = File(context.filesDir, "media/originals")
        assertEquals(1, originals.walkTopDown().count { it.isFile })
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_media")
        val recovered = media.importImage({ ByteArrayInputStream(PNG) })
        media.cleanPendingDeletions() // 失败后排队的文件已重新登记，不能被旧清理任务删除。
        assertTrue(requireNotNull(media.fileFor(recovered.id)).readBytes().contentEquals(PNG))
        assertEquals(1, originals.walkTopDown().count { it.isFile })
    }

    private class MutableClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current
    }

    companion object {
        private val PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jD1cAAAAASUVORK5CYII=",
        )
    }
}
