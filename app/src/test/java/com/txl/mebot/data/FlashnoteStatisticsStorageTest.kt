package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.flashnote.FlashnoteRepository
import com.txl.mebot.data.local.FlashnoteEntity
import com.txl.mebot.data.local.FlashnoteStatus
import com.txl.mebot.data.local.MebotDatabase
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
class FlashnoteStatisticsStorageTest {
    private lateinit var context: Context
    private lateinit var database: MebotDatabase
    private lateinit var clock: StatisticsClock
    private lateinit var repository: FlashnoteRepository
    private var zone = ZoneId.of("Asia/Shanghai")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        clock = StatisticsClock()
        database = MebotDatabase.create(context)
        connect()
    }
    private fun connect() { repository = FlashnoteRepository(database, clock, dayZone = { zone }) }
    @After fun tearDown() { database.close(); context.deleteDatabase(MebotDatabase.FILE_NAME); File(context.filesDir, OldPhoto).delete() }
    private fun time(value: String) { clock.value = Instant.parse(value) }
    private suspend fun create(body: String = "想做的一件事"): FlashnoteEntity {
        val draft = repository.openCreation()
        return repository.save(repository.saveInput(draft.key, "", body, draft.revision))
    }
    private suspend fun counts(): Map<LocalDate, Long> = database.flashnoteStatisticsDao().days(
        LocalDate.of(1900, 1, 1).toEpochDay(), LocalDate.of(2200, 1, 1).toEpochDay(),
    ).associate { LocalDate.ofEpochDay(it.day) to it.count }

    @Test fun historyUsesCompletionDayInPhoneZoneAndGroupsTasksRegardlessOfCreationTime() = runBlocking {
        time("2026-10-01T00:00:00Z")
        val first = create("第一项"); val second = create("第二项")
        time("2026-10-24T17:30:00Z") // 手机当地已是 25 日。
        repository.complete(first.id, first.revision)
        repository.complete(second.id, second.revision)
        assertEquals(mapOf(LocalDate.of(2026, 10, 25) to 2L), counts())
        assertEquals(first.createdAt, repository.note(first.id)?.createdAt)
    }

    @Test fun concurrentRepeatedCompletionsCountOnlyOnceAndKeepTheFirstTimestamp() = runBlocking {
        val note = create()
        val results = coroutineScope { List(12) { async(Dispatchers.Default) { repository.complete(note.id, note.revision) } }.awaitAll() }
        assertTrue(results.all { it == results.first() })
        assertEquals(1L, counts().values.single())
        time("2026-10-28T12:00:00Z")
        assertEquals(results.first(), repository.complete(note.id, note.revision))
        assertEquals(1L, counts().values.single())
    }

    @Test fun restorationWithdrawsItsOldDayAndRecompletionCountsOnlyOnTheNewDay() = runBlocking {
        val note = create()
        time("2026-10-25T00:00:00Z")
        val done = repository.complete(note.id, note.revision)
        time("2026-10-26T00:00:00Z")
        val pending = repository.restore(note.id, done.revision)
        assertTrue(counts().isEmpty())
        assertTrue(runCatching { repository.complete(note.id, note.revision) }.isFailure)
        assertTrue(counts().isEmpty())
        time("2026-10-27T00:00:00Z")
        repository.complete(note.id, pending.revision)
        assertEquals(mapOf(LocalDate.of(2026, 10, 27) to 1L), counts())
    }

    @Test fun deletingCompletedTasksArchivesOnlyDayAndCountAndRepeatedDeletionDoesNotAddAgain() = runBlocking {
        val first = create("删除后不得保留的正文一"); val second = create("删除后不得保留的正文二")
        val one = repository.complete(first.id, first.revision)
        val two = repository.complete(second.id, second.revision)
        repository.delete(one.id, one.revision)
        repository.delete(two.id, two.revision)
        assertFalse(repository.delete(one.id, one.revision))
        assertTrue(repository.notes(FlashnoteStatus.COMPLETED).isEmpty())
        assertEquals(2L, counts().values.single())
        val db = database.openHelper.readableDatabase
        db.query("SELECT * FROM flashnote_completion_archive").use { cursor ->
            assertEquals(listOf("day", "count"), cursor.columnNames.toList())
            assertTrue(cursor.moveToFirst()); assertEquals(2L, cursor.getLong(1)); assertFalse(cursor.moveToNext())
        }
        db.query("SELECT COUNT(*) FROM flashnote_completions").use { it.moveToFirst(); assertEquals(0L, it.getLong(0)) }
    }

    @Test fun deletingPendingOrRestoredTasksCannotManufactureHistoryAndStaleDeletePreservesIt() = runBlocking {
        val pending = create()
        repository.delete(pending.id, pending.revision)
        assertTrue(counts().isEmpty())
        val original = create()
        val done = repository.complete(original.id, original.revision)
        assertTrue(runCatching { repository.delete(original.id, original.revision) }.isFailure)
        assertEquals(1L, counts().values.single())
        val restored = repository.restore(done.id, done.revision)
        repository.delete(restored.id, restored.revision)
        assertTrue(counts().isEmpty())
    }

    @Test fun editingCompletedContentKeepsTheOriginalCompletionDay() = runBlocking {
        val note = create()
        val done = repository.complete(note.id, note.revision)
        val before = counts()
        time("2026-11-03T00:00:00Z")
        val edit = repository.openEditing(note.id)
        val saved = repository.save(repository.saveInput(edit.key, "新标题", "新的完整正文", edit.revision))
        assertEquals(before, counts())
        assertEquals(done.completedAt, saved.completedAt)
        assertEquals(done.createdAt, saved.createdAt)
    }

    @Test fun completionAndRestorationFailuresRollBackStatusAndHistoryTogether() = runBlocking {
        val note = create()
        val db = database.openHelper.writableDatabase
        db.execSQL("CREATE TRIGGER fail_completion BEFORE INSERT ON flashnote_completions BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertTrue(runCatching { repository.complete(note.id, note.revision) }.isFailure)
        assertEquals(note, repository.note(note.id)); assertTrue(counts().isEmpty())
        db.execSQL("DROP TRIGGER fail_completion")
        val completed = repository.complete(note.id, note.revision)
        db.execSQL("CREATE TRIGGER fail_restore BEFORE DELETE ON flashnote_completions BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertTrue(runCatching { repository.restore(completed.id, completed.revision) }.isFailure)
        assertEquals(completed, repository.note(note.id)); assertEquals(1L, counts().values.single())
        db.execSQL("DROP TRIGGER fail_restore")
        repository.restore(completed.id, completed.revision)
        assertTrue(counts().isEmpty())
    }

    @Test fun failedDeletionRollsBackArchiveIncrementAndRetryKeepsExactlyOneHistoricalCompletion() = runBlocking {
        val original = create(); val completed = repository.complete(original.id, original.revision)
        val db = database.openHelper.writableDatabase
        db.execSQL("CREATE TRIGGER fail_stats_delete BEFORE DELETE ON flashnotes BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertTrue(runCatching { repository.delete(completed.id, completed.revision) }.isFailure)
        assertEquals(completed, repository.note(completed.id))
        assertEquals(1L, counts().values.single())
        db.query("SELECT COUNT(*) FROM flashnote_completion_archive").use { it.moveToFirst(); assertEquals(0L, it.getLong(0)) }
        db.execSQL("DROP TRIGGER fail_stats_delete")
        repository.delete(completed.id, completed.revision)
        assertEquals(1L, counts().values.single())
    }

    @Test fun storedCompletionDayDoesNotMoveWhenTimeZoneChangesBeforeDeletion() = runBlocking {
        time("2026-10-24T17:30:00Z")
        val note = create(); val completed = repository.complete(note.id, note.revision)
        zone = ZoneId.of("America/Los_Angeles")
        repository.delete(completed.id, completed.revision)
        assertEquals(mapOf(LocalDate.of(2026, 10, 25) to 1L), counts())
        database.close(); database = MebotDatabase.create(context); connect()
        assertEquals(mapOf(LocalDate.of(2026, 10, 25) to 1L), counts())
    }

    @Test fun monthQueriesUseHalfOpenLocalDayBoundsIncludingLeapMonthAndYearBoundaries() = runBlocking {
        for (instant in listOf("2024-02-28T16:00:00Z", "2024-02-29T15:59:59Z", "2024-02-29T16:00:00Z")) {
            time(instant); val note = create(); repository.complete(note.id, note.revision)
        }
        val feb = repository.observeCompletionDays(LocalDate.of(2024, 2, 1).toEpochDay(), LocalDate.of(2024, 3, 1).toEpochDay()).first()
        assertEquals(2L, feb.single().count)
        assertEquals(LocalDate.of(2024, 2, 29).toEpochDay(), feb.single().day)
        assertEquals(1L, repository.observeCompletionDays(LocalDate.of(2024, 3, 1).toEpochDay(), LocalDate.of(2024, 4, 1).toEpochDay()).first().single().count)
    }

    @Test fun actualSchemaTenUpgradeBackfillsKnownCompletionTimesAndPreservesAllExistingTablesAndFiles() = runBlocking {
        database.close(); context.deleteDatabase(MebotDatabase.FILE_NAME)
        val schemaPath = "schemas/com.txl.mebot.data.local.MebotDatabase/10.json"
        val schema = JSONObject((File(schemaPath).takeIf { it.exists() } ?: File("app/$schemaPath")).readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val names = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
        val created = Instant.parse("2026-10-20T02:00:00Z").toEpochMilli()
        val completed = Instant.parse("2026-10-24T17:00:00Z").toEpochMilli()
        val photo = File(context.filesDir, OldPhoto).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val path = context.getDatabasePath(MebotDatabase.FILE_NAME).apply { parentFile!!.mkdirs() }
        lateinit var oldRows: Map<String, List<List<String?>>>
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.setForeignKeyConstraintsEnabled(true)
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices -> for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            schema.getJSONArray("setupQueries").let { queries -> for (i in 0 until queries.length()) old.execSQL(queries.getString(i)) }
            old.execSQL("INSERT INTO flashnotes VALUES ('done','已有标题','原文','COMPLETED',$created,$completed,$completed,2),('unknown','无完成日期','原文','COMPLETED',$created,$created,NULL,1),('pending','未完成','原文','PENDING',$created,$created,NULL,1)")
            old.execSQL("INSERT INTO flashnote_drafts VALUES ('draft','','未提交的输入',NULL,NULL,3,$created,'')")
            old.execSQL("INSERT INTO media_assets (id,source,relativePath,originalName,mimeType,byteCount,width,height,createdAt) VALUES ('photo','IMPORTED','$OldPhoto','原图.png','image/png',3,1,1,$created)")
            old.execSQL("INSERT INTO flashnote_attachments VALUES ('done','photo','原图.png',1)")
            old.execSQL("INSERT INTO schedule_sessions VALUES ('schedule',10,20)")
            old.execSQL("INSERT INTO schedule_messages VALUES ('message','schedule',1,'USER','日程历史不得丢失',10,NULL)")
            old.version = 10
            oldRows = names.associateWith { name -> old.rawQuery("SELECT * FROM `$name` ORDER BY rowid", null).use { c -> buildList { while(c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) } } }
        }
        database = MebotDatabase.create(context); connect()
        val expectedDay = Instant.ofEpochMilli(completed).atZone(ZoneId.systemDefault()).toLocalDate()
        assertEquals(mapOf(expectedDay to 1L), counts())
        assertEquals(MebotDatabase.VERSION, database.openHelper.readableDatabase.version)
        for (name in names) database.openHelper.readableDatabase.query("SELECT * FROM `$name` ORDER BY rowid").use { c ->
            val actual = buildList { while(c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) }
            assertEquals(name, oldRows[name], actual)
        }
        assertEquals("未提交的输入", repository.openCreation().content)
        assertArrayEquals(byteArrayOf(1, 2, 3), photo.readBytes())
        database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        val known = requireNotNull(repository.note("done")); repository.delete(known.id, known.revision)
        assertEquals(mapOf(expectedDay to 1L), counts())
    }

    private class StatisticsClock : Clock() {
        var value: Instant = Instant.parse("2026-10-25T02:00:00Z")
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(value, zone)
        override fun instant(): Instant = value
    }
    private companion object { const val OldPhoto = "media/originals/flashnote-statistics-old.png" }
}
