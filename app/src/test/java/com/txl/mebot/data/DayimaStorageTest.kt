package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.DayimaChatDraftEntity
import com.txl.mebot.data.local.DayimaChatMessageEntity
import com.txl.mebot.data.local.DayimaChatOperationEntity
import com.txl.mebot.data.local.DayimaChatSessionEntity
import com.txl.mebot.data.local.DayimaChatStatus
import com.txl.mebot.data.local.DayimaMessageRole
import com.txl.mebot.data.local.DayimaNoteEntity
import com.txl.mebot.data.local.DayimaOperationAction
import com.txl.mebot.data.local.DayimaOperationState
import com.txl.mebot.data.local.DayimaPeriodEntity
import com.txl.mebot.data.local.DayimaProfileEntity
import com.txl.mebot.data.local.DayimaReminderSettingsEntity
import com.txl.mebot.data.local.DayimaSpanMarkerEntity
import com.txl.mebot.data.local.DayimaSpanReason
import com.txl.mebot.data.local.DayimaWriteKind
import com.txl.mebot.data.local.DayimaWriteOutcome
import com.txl.mebot.data.local.DayimaWriteReceiptEntity
import com.txl.mebot.data.local.FlashnoteStatus
import com.txl.mebot.data.local.MebotDatabase
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
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

/** 在开发机真实 SQLite 上验证持久化、关联边界与升级；不操作用户手机。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DayimaStorageTest {
    private lateinit var context: Context
    private lateinit var database: MebotDatabase
    private val firstDate = LocalDate.of(2024, 2, 29)
    private val nextDate = LocalDate.of(2024, 3, 28)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        database = MebotDatabase.create(context)
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        FixtureFiles.forEach { File(context.filesDir, it).delete() }
    }

    @Test fun freshStorageHasNoInventedRecordsProfileOrEnabledReminderTask() = runBlocking {
        val dao = database.dayimaDao()
        assertNull(dao.profile())
        assertNull(dao.reminderSettings())
        assertNull(dao.latestPeriod())
        assertNull(dao.latestActiveSession())
        assertTrue(dao.periods().isEmpty())
        assertTrue(dao.spanMarkers().isEmpty())
        assertTrue(dao.sessions(50, 0).isEmpty())
        assertTrue(dao.notesBetween(firstDate, nextDate).isEmpty())
        assertEquals(MebotDatabase.VERSION, database.openHelper.readableDatabase.version)
    }

    @Test fun profileDatesNotesSpanAndIndependentChatStateAllSurviveReopen() = runBlocking {
        val dao = database.dayimaDao()
        val profile = DayimaProfileEntity(birthDate = LocalDate.of(1988, 2, 29), updatedAt = 100)
        val settings = DayimaReminderSettingsEntity(prepareEnabled = false, reminderTime = LocalTime.of(20, 45), prepareDaysBefore = 2, updatedAt = 200)
        val first = period("first", firstDate)
        val next = period("next", nextDate)
        val note = DayimaNoteEntity("note", firstDate, "保留文字\n第二行", first.id, 100, 200)
        val marker = marker(first, next)
        val a = session("a", 100)
        val b = session("b", 300).copy(status = DayimaChatStatus.CLOSED)
        val aMessage = message("request-a", a.id, 1)
        val bMessage = message("request-b", b.id, 1)
        val aDraft = DayimaChatDraftEntity(a.id, "尚未发送的补充", 3, 400)
        val bDraft = DayimaChatDraftEntity(b.id, "另一段聊天的输入", 2, 500)
        val aOperation = operation(aMessage).copy(state = DayimaOperationState.WAITING_FOR_INPUT,
            action = DayimaOperationAction.RECORD, question = "具体是哪一天？", attemptId = "attempt-a")
        val bOperation = operation(bMessage).copy(state = DayimaOperationState.SUCCEEDED,
            action = DayimaOperationAction.QUERY, resultJson = "{\"count\":1}")
        database.withTransaction {
            dao.insertProfile(profile)
            dao.insertReminderSettings(settings)
            dao.insertPeriod(next)
            dao.insertPeriod(first)
            dao.insertNote(note)
            dao.insertSpanMarker(marker)
            dao.insertSession(a)
            dao.insertSession(b)
            dao.insertMessage(aMessage)
            dao.insertMessage(bMessage)
            dao.insertDraft(aDraft)
            dao.insertDraft(bDraft)
            dao.insertOperation(aOperation)
            dao.insertOperation(bOperation)
        }
        reopen()
        val saved = database.dayimaDao()
        assertEquals(profile, saved.observeProfile().first())
        assertEquals(settings, saved.observeReminderSettings().first())
        assertEquals(listOf(first, next), saved.observePeriods().first())
        assertEquals(note, saved.note(note.id))
        assertEquals(listOf(note), saved.notesForPeriod(first.id))
        assertEquals(marker, saved.spanMarker(marker.id))
        assertEquals(a, saved.observeSession(a.id).first())
        assertEquals(a, saved.latestActiveSession())
        assertEquals(listOf(b, a), saved.sessions(10, 0))
        assertEquals(listOf(aMessage), saved.observeMessages(a.id, 10).first())
        assertEquals(listOf(bMessage), saved.messages(b.id, 10))
        assertEquals(aDraft, saved.observeDraft(a.id).first())
        assertEquals(bDraft, saved.draft(b.id))
        assertEquals(listOf(aOperation), saved.pendingQuestions(a.id))
        assertTrue(saved.pendingQuestions(b.id).isEmpty())
        assertEquals(bOperation, saved.operation(bMessage.id))
        database.openHelper.readableDatabase.query("SELECT startDate FROM dayima_periods WHERE id = 'first'").use {
            assertTrue(it.moveToFirst())
            assertEquals(firstDate.toEpochDay(), it.getLong(0))
        }
    }

    @Test fun duplicateDateOrIdAbortsWithoutReplacingOriginalOrItsNotes() = runBlocking {
        val dao = database.dayimaDao()
        val original = period("original", firstDate)
        val note = DayimaNoteEntity("note", firstDate, "需要保留", original.id, 100, 100)
        dao.insertPeriod(original)
        dao.insertNote(note)
        assertTrue(runCatching { dao.insertPeriod(period("duplicate-date", firstDate)) }.isFailure)
        assertTrue(runCatching { dao.insertPeriod(original.copy(startDate = nextDate)) }.isFailure)
        assertEquals(original, dao.periodOn(firstDate))
        assertEquals(listOf(original), dao.periods())
        assertEquals(note, dao.note(note.id))
    }

    @Test fun historyOrdersByOccurrenceDateAndPagesWithoutLosingBoundaryRecords() = runBlocking {
        val dao = database.dayimaDao()
        val older = period("older", firstDate.minusYears(3)).copy(updatedAt = 900)
        val first = period("first", firstDate)
        val next = period("next", nextDate)
        dao.insertPeriod(next)
        dao.insertPeriod(older)
        dao.insertPeriod(first)
        assertEquals(listOf(older, first, next), dao.periods())
        assertEquals(listOf(first, next), dao.periodsBetween(firstDate, nextDate))
        assertEquals(listOf(first, older), dao.periodsBefore(nextDate, 2))
        assertEquals(next, dao.latestPeriod())
    }

    @Test fun messageSequenceAndPendingQuestionsAreScopedToTheirOwnChat() = runBlocking {
        val dao = database.dayimaDao()
        dao.insertSession(session("a", 100))
        dao.insertSession(session("b", 200))
        val first = message("a-first", "a", 1)
        val second = message("a-second", "a", 2).copy(role = DayimaMessageRole.ASSISTANT)
        val third = message("a-third", "a", 3)
        val other = message("b-first", "b", 1)
        listOf(third, first, other, second).forEach { dao.insertMessage(it) }
        dao.insertOperation(operation(first).copy(state = DayimaOperationState.WAITING_FOR_INPUT, question = "哪天？"))
        assertEquals(listOf(second, third), dao.messages("a", 2))
        assertEquals(listOf(first, second), dao.messages("a", 10, 3))
        assertEquals(listOf(other), dao.messages("b", 10))
        assertEquals(3L, dao.lastMessageSequence("a"))
        assertTrue(dao.pendingQuestions("b").isEmpty())
        assertTrue(runCatching { dao.insertMessage(message("same-sequence", "a", 1)) }.isFailure)
        assertTrue(runCatching { dao.insertOperation(operation(first).copy(sessionId = "b")) }.isFailure)
        // 用没有现存操作的请求验证复合外键，而非仅命中请求 ID 唯一约束。
        assertTrue(runCatching { dao.insertOperation(operation(third).copy(sessionId = "b")) }.isFailure)
        assertEquals("哪天？", dao.operation(first.id)?.question)
    }

    @Test fun missingParentsAreRejectedAndRelatedWritesRollBackTogether() = runBlocking {
        val dao = database.dayimaDao()
        assertTrue(runCatching { dao.insertMessage(message("orphan", "missing", 1)) }.isFailure)
        assertTrue(runCatching { dao.insertDraft(DayimaChatDraftEntity("missing", updatedAt = 100)) }.isFailure)
        assertTrue(runCatching { dao.insertNote(DayimaNoteEntity("orphan-note", firstDate, "文字", "missing", 100, 100)) }.isFailure)
        assertTrue(runCatching {
            database.withTransaction {
                dao.insertPeriod(period("rollback", firstDate))
                dao.insertSession(session("rollback-chat", 100))
                dao.insertOperation(DayimaChatOperationEntity("missing-request", "rollback-chat", createdAt = 100, updatedAt = 100))
            }
        }.isFailure)
        assertNull(dao.period("rollback"))
        assertNull(dao.session("rollback-chat"))
        assertNull(dao.operation("missing-request"))
    }

    @Test fun deletingChatCascadesOnlyThatChatAndCannotResurrectItsDraft() = runBlocking {
        val dao = database.dayimaDao()
        val first = period("first", firstDate)
        dao.insertPeriod(first)
        val note = DayimaNoteEntity("note", firstDate, "独立业务事实", first.id, 100, 100)
        dao.insertNote(note)
        val profile = DayimaProfileEntity(birthDate = LocalDate.of(1990, 1, 1), updatedAt = 100)
        val settings = DayimaReminderSettingsEntity(updatedAt = 100)
        dao.insertProfile(profile)
        dao.insertReminderSettings(settings)
        for (id in listOf("a", "b")) {
            dao.insertSession(session(id, 100))
            dao.insertDraft(DayimaChatDraftEntity(id, "输入 $id", updatedAt = 100))
            val request = message("request-$id", id, 1)
            dao.insertMessage(request)
            dao.insertOperation(operation(request))
        }
        assertEquals(1, dao.deleteSession("a", 1))
        assertNull(dao.session("a"))
        assertNull(dao.draft("a"))
        assertNull(dao.message("request-a"))
        assertNull(dao.operation("request-a"))
        assertEquals(0, dao.updateDraft("a", "迟到的自动保存", 200, 0))
        assertTrue(runCatching { dao.insertDraft(DayimaChatDraftEntity("a", updatedAt = 200)) }.isFailure)
        assertEquals("输入 b", dao.draft("b")?.inputText)
        assertNotNull(dao.operation("request-b"))
        assertEquals(first, dao.period(first.id))
        assertEquals(note, dao.note(note.id))
        assertEquals(profile, dao.profile())
        assertEquals(settings, dao.reminderSettings())
    }

    @Test fun dateChangesAndDeletionKeepNotesAndOriginalSpanSnapshotsForReview() = runBlocking {
        val dao = database.dayimaDao()
        val first = period("first", firstDate)
        val next = period("next", nextDate)
        dao.insertPeriod(first)
        dao.insertPeriod(next)
        val marked = marker(first, next)
        dao.insertSpanMarker(marked)
        val note = DayimaNoteEntity("note", firstDate, "独立保留的情况", first.id, 100, 100)
        dao.insertNote(note)
        assertEquals(1, dao.updatePeriodDate(first.id, firstDate.minusDays(1), 200, 1))
        assertEquals(marked, dao.spanMarker(marked.id))
        assertEquals(2L, dao.period(first.id)?.revision)
        assertEquals(0, dao.deletePeriod(first.id, 1))
        assertEquals(1, dao.deletePeriod(first.id, 2))
        assertEquals(note.copy(periodId = null), dao.note(note.id))
        assertEquals(marked.copy(startPeriodId = null), dao.spanMarker(marked.id))
        assertEquals(next, dao.period(next.id))
    }

    @Test fun revisionUpdatesRejectStaleProfileDraftSettingsAndOperationWrites() = runBlocking {
        val dao = database.dayimaDao()
        dao.insertProfile(DayimaProfileEntity(updatedAt = 100))
        dao.insertReminderSettings(DayimaReminderSettingsEntity(updatedAt = 100))
        dao.insertSession(session("a", 100))
        dao.insertDraft(DayimaChatDraftEntity("a", updatedAt = 100))
        val request = message("request", "a", 1)
        dao.insertMessage(request)
        dao.insertOperation(operation(request))
        val birth = LocalDate.of(1990, 2, 3)
        assertEquals(1, dao.updateBirthDate(birth, 200, 1))
        assertEquals(0, dao.updateBirthDate(null, 300, 1))
        assertEquals(birth, dao.profile()?.birthDate)
        assertEquals(1, dao.updateDraft("a", "较新内容", 200, 0))
        assertEquals(0, dao.updateDraft("a", "旧保存", 300, 0))
        assertEquals("较新内容", dao.draft("a")?.inputText)
        assertEquals(1, dao.updateReminderSettings(false, true, LocalTime.of(18, 30), 3, 200, 1))
        assertEquals(0, dao.updateReminderSettings(true, false, LocalTime.NOON, 1, 300, 1))
        assertEquals(LocalTime.of(18, 30), dao.reminderSettings()?.reminderTime)
        assertEquals(1, dao.updateOperation(request.id, DayimaOperationState.SUCCEEDED, DayimaOperationAction.QUERY,
            null, "{\"count\":0}", null, "attempt", 200, 1))
        assertEquals(0, dao.updateOperation(request.id, DayimaOperationState.FAILED, null, null, null, "OLD", null, 300, 1))
        assertEquals(DayimaOperationState.SUCCEEDED, dao.operation(request.id)?.state)
        assertTrue(runCatching { dao.insertOperation(operation(request)) }.isFailure)
    }

    @Test fun versionElevenMigrationPreservesAllExistingRowsDefinitionsAndFiles() = runBlocking {
        database.close()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        val schemaPath = "schemas/com.txl.mebot.data.local.MebotDatabase/11.json"
        val schema = JSONObject((File(schemaPath).takeIf { it.exists() } ?: File("app/$schemaPath")).readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val names = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
        val files = FixtureFiles.associateWith { relative ->
            File(context.filesDir, relative).apply { parentFile!!.mkdirs(); writeText("fixture original: $relative") }.readBytes()
        }
        lateinit var oldRows: Map<String, List<List<String?>>>
        lateinit var oldDefinitions: List<List<String?>>
        val path = context.getDatabasePath(MebotDatabase.FILE_NAME).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.setForeignKeyConstraintsEnabled(true)
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices ->
                    for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            schema.getJSONArray("setupQueries").let { queries -> for (i in 0 until queries.length()) old.execSQL(queries.getString(i)) }
            seedVersionEleven(old)
            old.version = 11
            oldRows = names.associateWith { table -> old.rawQuery("SELECT * FROM `$table` ORDER BY rowid", null).use(::rows) }
            oldDefinitions = definitions(names) { old.rawQuery(it, null) }
            old.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
        database = MebotDatabase.create(context)
        val dao = database.dayimaDao()
        assertTrue(dao.periods().isEmpty()) // 触发真实迁移及 Room 完整 schema 校验。
        assertNull(dao.profile())
        assertNull(dao.reminderSettings())
        assertTrue(dao.sessions(50, 0).isEmpty())
        val upgraded = database.openHelper.readableDatabase
        assertEquals(MebotDatabase.VERSION, upgraded.version)
        val actualRows = names.associateWith { table -> upgraded.query("SELECT * FROM `$table` ORDER BY rowid").use(::rows) }
        assertEquals(oldRows, actualRows)
        assertEquals(oldDefinitions, definitions(names) { upgraded.query(it) })
        files.forEach { (relative, content) -> assertArrayEquals(content, File(context.filesDir, relative).readBytes()) }
        upgraded.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        dao.insertPeriod(period("after-upgrade", firstDate))
        dao.insertSession(session("after-upgrade-chat", 100))
        dao.insertDraft(DayimaChatDraftEntity("after-upgrade-chat", "升级后草稿", updatedAt = 100))
        reopen()
        assertEquals(firstDate, database.dayimaDao().period("after-upgrade")?.startDate)
        assertEquals("升级后草稿", database.dayimaDao().draft("after-upgrade-chat")?.inputText)
    }

    @Test fun versionTwelveMigrationPreservesAllExistingBusinessDataAndAddsEmptyReceipts() = runBlocking {
        migrateVersionTwelve(staleSpan = false)
    }

    @Test fun versionTwelveMigrationMarksAnAlreadyNonAdjacentSpanForReview() = runBlocking {
        migrateVersionTwelve(staleSpan = true)
    }

    private suspend fun migrateVersionTwelve(staleSpan: Boolean) {
        database.close()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        val schemaPath = "schemas/com.txl.mebot.data.local.MebotDatabase/12.json"
        val schema = JSONObject((File(schemaPath).takeIf { it.exists() } ?: File("app/$schemaPath")).readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val columns = (0 until entities.length()).associate { index ->
            val entity = entities.getJSONObject(index)
            val fields = entity.getJSONArray("fields")
            entity.getString("tableName") to (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
        }
        val names = columns.keys.toList()
        fun select(table: String) = "SELECT ${columns.getValue(table).joinToString(",") { "`$it`" }} FROM `$table` ORDER BY rowid"
        lateinit var oldRows: Map<String, List<List<String?>>>
        lateinit var oldDefinitions: List<List<String?>>
        val path = context.getDatabasePath(MebotDatabase.FILE_NAME).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.setForeignKeyConstraintsEnabled(true)
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices ->
                    for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            schema.getJSONArray("setupQueries").let { queries -> for (i in 0 until queries.length()) old.execSQL(queries.getString(i)) }
            seedVersionEleven(old)
            val start = firstDate.toEpochDay()
            val end = nextDate.toEpochDay()
            listOf(
                "INSERT INTO dayima_profile VALUES (1,${LocalDate.of(1988, 2, 29).toEpochDay()},100,1)",
                "INSERT INTO dayima_periods VALUES ('first',$start,100,100,1),('next',$end,100,100,1)",
                "INSERT INTO dayima_notes VALUES ('note',$start,'已有备注','first',100,100,1)",
                "INSERT INTO dayima_span_markers VALUES ('marker','first','next',$start,$end,1,1,'MISSING_RECORDS','原标记',100,100,1)",
                "INSERT INTO dayima_chat_sessions VALUES ('chat','原有独立聊天','ACTIVE',100,200,1)",
                "INSERT INTO dayima_chat_messages VALUES ('dayima-request','chat',1,'USER','原对话',100)",
                "INSERT INTO dayima_chat_drafts VALUES ('chat','未发送内容',3,200)",
                "INSERT INTO dayima_chat_operations VALUES ('dayima-request','chat','WAITING_FOR_INPUT','RECORD','哪一天？',NULL,NULL,'attempt',100,200,1)",
                "INSERT INTO dayima_reminder_settings VALUES (1,1,0,1245,2,100,1)",
            ).forEach { old.execSQL(it) }
            if (staleSpan) old.execSQL("INSERT INTO dayima_periods VALUES ('middle',${start + 14},100,100,1)")
            old.version = 12
            oldRows = names.associateWith { old.rawQuery(select(it), null).use(::rows) }
            oldDefinitions = definitions(names) { old.rawQuery(it, null) }
                .filterNot { it[0] == "table" && it[2] == "dayima_span_markers" }
        }
        database = MebotDatabase.create(context)
        val writes = database.dayimaWriteDao()
        assertEquals(0L, writes.dataVersion()) // 触发 12→13 迁移及完整 schema 校验。
        assertEquals(MebotDatabase.VERSION, database.openHelper.readableDatabase.version)
        val expected = oldRows.toMutableMap()
        if (staleSpan) {
            val revisionIndex = columns.getValue("dayima_span_markers").indexOf("revision")
            expected["dayima_span_markers"] = oldRows.getValue("dayima_span_markers").map { row ->
                row.toMutableList().also { it[revisionIndex] = "2" }
            }
        }
        val upgraded = database.openHelper.readableDatabase
        assertEquals(expected, names.associateWith { upgraded.query(select(it)).use(::rows) })
        assertEquals(oldDefinitions, definitions(names) { upgraded.query(it) }
            .filterNot { it[0] == "table" && it[2] == "dayima_span_markers" })
        assertEquals(staleSpan, database.dayimaDao().spanMarker("marker")?.needsReview)
        upgraded.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        assertEquals(1L, writes.insert(DayimaWriteReceiptEntity(requestId = "after-upgrade", fingerprint = "fixture",
            kind = DayimaWriteKind.BIRTH_DATE, targetId = "1", targetRevision = 1,
            outcome = DayimaWriteOutcome.UNCHANGED, committedAt = 200)))
        reopen()
        assertEquals(1L, database.dayimaWriteDao().dataVersion())
        assertEquals("未发送内容", database.dayimaDao().draft("chat")?.inputText)
    }

    @Test fun incompleteVersionThirteenRepairsReviewStateAndPreservesEveryBotsRecords() = runBlocking {
        migrateVersionThirteen(missingReviewState = true)
    }

    @Test fun completeVersionThirteenPreservesExistingReviewDecisionsAndAllRecords() = runBlocking {
        migrateVersionThirteen(missingReviewState = false)
    }

    @Test fun failedRepairRollsBackAddedColumnWithoutResettingAnyRecordsOrIdentity() = runBlocking {
        val original = createVersionThirteenFixture(missingReviewState = true)
        val path = context.getDatabasePath(MebotDatabase.FILE_NAME)
        SQLiteDatabase.openOrCreateDatabase(path, null).use {
            it.execSQL("DROP INDEX index_flashnotes_status_createdAt_id")
        }
        database = MebotDatabase.create(context)
        assertTrue(runCatching { database.flashnoteDao().observeCounts().first() }.isFailure)
        database.close()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            assertEquals(13, old.version)
            old.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42", null).use {
                assertTrue(it.moveToFirst()); assertEquals(IncompleteVersionThirteenHash, it.getString(0))
            }
            old.rawQuery("PRAGMA table_info(dayima_span_markers)", null).use {
                val name = it.getColumnIndexOrThrow("name")
                while (it.moveToNext()) assertNotEquals("needsReview", it.getString(name))
            }
            assertEquals(original.rows, original.columns.keys.associateWith {
                old.rawQuery(original.select(it), null).use(::rows)
            })
        }
    }

    private suspend fun migrateVersionThirteen(missingReviewState: Boolean) {
        val original = createVersionThirteenFixture(missingReviewState)
        val files = FixtureFiles.associateWith { relative ->
            File(context.filesDir, relative).apply {
                parentFile!!.mkdirs(); writeText("original local file: $relative")
            }.readBytes()
        }
        database = MebotDatabase.create(context)
        val upgraded = database.openHelper.readableDatabase // 完整结构验证通过后才更新版本与标识。
        assertEquals(MebotDatabase.VERSION, upgraded.version)
        val expected = original.rows.toMutableMap()
        if (missingReviewState) {
            val revision = original.columns.getValue("dayima_span_markers").indexOf("revision")
            expected["dayima_span_markers"] = expected.getValue("dayima_span_markers").map { row ->
                row.toMutableList().also {
                    if (row[0] != "valid") it[revision] = (it[revision]!!.toLong() + 1).toString()
                }
            }
        }
        assertEquals(expected, original.columns.keys.associateWith { upgraded.query(original.select(it)).use(::rows) })
        assertEquals(original.definitions.filterNot { missingReviewState && it[2] == "dayima_span_markers" },
            definitions(original.columns.keys.toList()) { upgraded.query(it) }
                .filterNot { missingReviewState && it[2] == "dayima_span_markers" })
        files.forEach { (relative, bytes) -> assertArrayEquals(bytes, File(context.filesDir, relative).readBytes()) }
        val dao = database.dayimaDao()
        assertEquals(!missingReviewState, dao.spanMarker("valid")?.needsReview)
        assertEquals(missingReviewState, dao.spanMarker("stale")?.needsReview)
        assertEquals(true, dao.spanMarker("missing")?.needsReview)
        assertEquals("未发送内容", dao.draft("chat")?.inputText)
        val day = LocalDate.of(2026, 10, 24)
        assertEquals("task", database.scheduleDao().observeDisplayDate(day).first().single().occurrence.taskId)
        assertEquals(1, database.scheduleDao().observeDateCounts(day, day).first().size)
        assertEquals("flash", database.flashnoteDao().observeNotes(FlashnoteStatus.COMPLETED).first().single().id)
        assertEquals(1, database.flashnoteDao().observeCounts().first().single().count)
        assertEquals("未提交内容", database.flashnoteDao().draft("draft")?.content)
        upgraded.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        assertEquals(42L, database.dayimaWriteDao().insert(DayimaWriteReceiptEntity(
            requestId = "after-repair", fingerprint = "fixture", kind = DayimaWriteKind.BIRTH_DATE,
            targetId = "1", targetRevision = 1, outcome = DayimaWriteOutcome.UNCHANGED, committedAt = 300,
        )))
        reopen()
        assertEquals(42L, database.dayimaWriteDao().dataVersion())
        assertEquals(MebotDatabase.VERSION, database.openHelper.readableDatabase.version)
        assertEquals(!missingReviewState, database.dayimaDao().spanMarker("valid")?.needsReview)
    }

    private data class LegacySnapshot(
        val columns: Map<String, List<String>>,
        val rows: Map<String, List<List<String?>>>,
        val definitions: List<List<String?>>,
    ) {
        fun select(table: String) = "SELECT " + columns.getValue(table).joinToString(",") { "`$it`" } +
            " FROM `$table` ORDER BY rowid"
    }

    private fun createVersionThirteenFixture(missingReviewState: Boolean): LegacySnapshot {
        database.close()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        val schemaPath = "schemas/com.txl.mebot.data.local.MebotDatabase/13.json"
        val schema = JSONObject((File(schemaPath).takeIf { it.exists() } ?: File("app/$schemaPath")).readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val path = context.getDatabasePath(MebotDatabase.FILE_NAME).apply { parentFile!!.mkdirs() }
        return SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.setForeignKeyConstraintsEnabled(true)
            val columns = linkedMapOf<String, List<String>>()
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                var sql = entity.getString("createSql").replace("\${TABLE_NAME}", table)
                if (missingReviewState && table == "dayima_span_markers") {
                    val completeSql = sql
                    sql = sql.replace(", `needsReview` INTEGER NOT NULL DEFAULT 0", "")
                    assertNotEquals(completeSql, sql)
                }
                old.execSQL(sql)
                entity.optJSONArray("indices")?.let { indices ->
                    for (j in 0 until indices.length()) old.execSQL(
                        indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                columns[table] = old.rawQuery("SELECT * FROM `$table` LIMIT 0", null).use { it.columnNames.toList() }
            }
            schema.getJSONArray("setupQueries").let { queries ->
                for (i in 0 until queries.length()) old.execSQL(queries.getString(i))
            }
            if (missingReviewState) old.execSQL(
                "UPDATE room_master_table SET identity_hash = ? WHERE id = 42", arrayOf(IncompleteVersionThirteenHash))
            seedVersionEleven(old)
            val start = firstDate.toEpochDay()
            val end = nextDate.toEpochDay()
            val later = end + 28
            listOf(
                "INSERT INTO dayima_profile VALUES (1,$start,100,1)",
                "INSERT INTO dayima_periods VALUES ('first',$start,100,100,1),('next',$end,100,100,1),('later',$later,100,100,1)",
                "INSERT INTO dayima_notes VALUES ('note',$start,'已有备注','first',100,100,1)",
                "INSERT INTO dayima_chat_sessions VALUES ('chat','原聊天','ACTIVE',100,200,1)",
                "INSERT INTO dayima_chat_messages VALUES ('dayima-request','chat',1,'USER','原对话',100)",
                "INSERT INTO dayima_chat_drafts VALUES ('chat','未发送内容',3,200)",
                "INSERT INTO dayima_chat_operations VALUES ('dayima-request','chat','WAITING_FOR_INPUT','RECORD','哪一天？',NULL,NULL,'attempt',100,200,1)",
                "INSERT INTO dayima_reminder_settings VALUES (1,1,0,1245,2,100,1)",
                "INSERT INTO dayima_write_receipts VALUES (41,'old-request','fixture','BIRTH_DATE','1',1,'UNCHANGED',NULL,NULL,200)",
            ).forEach { old.execSQL(it) }
            val reviewColumn = if (missingReviewState) "" else ",needsReview"
            for ((id, endpoint, date, review) in listOf(
                listOf("valid", "'next'", "$end", "1"),
                listOf("stale", "'later'", "$later", "0"),
                listOf("missing", "NULL", "$end", "1"),
            )) {
                val reviewValue = if (missingReviewState) "" else ",$review"
                old.execSQL("""INSERT INTO dayima_span_markers
                    (id,startPeriodId,endPeriodId,startDate,endDate,startPeriodRevision,endPeriodRevision,
                    reason,detail,createdAt,updatedAt,revision$reviewColumn)
                    VALUES ('$id','first',$endpoint,$start,$date,1,1,'MISSING_RECORDS','保留标记',100,100,7$reviewValue)""")
            }
            old.version = 13
            val empty = LegacySnapshot(columns, emptyMap(), emptyList())
            empty.copy(
                rows = columns.keys.associateWith { old.rawQuery(empty.select(it), null).use(::rows) },
                definitions = definitions(columns.keys.toList()) { old.rawQuery(it, null) },
            )
        }
    }

    private fun period(id: String, date: LocalDate) = DayimaPeriodEntity(id, date, 100, 100)
    private fun session(id: String, updatedAt: Long) = DayimaChatSessionEntity(id, createdAt = 100, updatedAt = updatedAt)
    private fun message(id: String, sessionId: String, sequence: Long) =
        DayimaChatMessageEntity(id, sessionId, sequence, DayimaMessageRole.USER, "消息 $id", 100)
    private fun operation(request: DayimaChatMessageEntity) =
        DayimaChatOperationEntity(request.id, request.sessionId, createdAt = 100, updatedAt = 100)
    private fun marker(first: DayimaPeriodEntity, next: DayimaPeriodEntity) = DayimaSpanMarkerEntity(
        "marker", first.id, next.id, first.startDate, next.startDate, first.revision, next.revision,
        DayimaSpanReason.MISSING_RECORDS, "当时的说明", 100, 100,
    )
    private fun reopen() { database.close(); database = MebotDatabase.create(context) }

    private fun definitions(names: List<String>, query: (String) -> Cursor) = query(
        "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE type IN ('table','index') AND name NOT LIKE 'sqlite_%' ORDER BY type,name",
    ).use(::rows).filter { it[2] in names }

    private fun rows(cursor: Cursor): List<List<String?>> = buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { i ->
            when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_BLOB -> "blob:" + cursor.getBlob(i).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                else -> cursor.getString(i)
            }
        })
    }

    private fun seedVersionEleven(old: SQLiteDatabase) {
        val day = LocalDate.of(2026, 10, 24).toEpochDay()
        listOf(
            "INSERT INTO media_assets (id,source,relativePath,originalName,mimeType,byteCount,width,height,createdAt) VALUES ('photo','IMPORTED','${FixtureFiles[0]}','原图.png','image/png',4,1,1,100),('file','IMPORTED','${FixtureFiles[1]}','附件.txt','text/plain',40,0,0,100)",
            "INSERT INTO schedule_sessions VALUES ('session',100,200),('draft-session',100,250)",
            "INSERT INTO schedule_messages VALUES ('message','session',1,'USER','原日程会话',100,NULL),('question','draft-session',1,'ASSISTANT','几点？',150,NULL)",
            "INSERT INTO schedule_tasks VALUES ('task','session','已有日程','完整内容',$day,540,'photo',1,100,200,NULL)",
            "INSERT INTO schedule_task_versions VALUES ('task',1,$day,'已有日程','完整内容',$day,540,'photo',100)",
            "INSERT INTO schedule_recurrences VALUES ('task',1,'DAILY',1,0,NULL,NULL,1)",
            "INSERT INTO schedule_reminder_leads VALUES ('task',1,10)",
            "INSERT INTO schedule_occurrences VALUES ('occurrence','task','date:$day','已有日程','完整内容',$day,540,'photo',1,1,NULL,0,100,200,0)",
            "INSERT INTO schedule_reminders VALUES ('reminder','occurrence',1,10,200000,'Asia/Shanghai','PENDING',NULL,100)",
            "INSERT INTO schedule_attachments VALUES ('session','photo','message',100,0,'原图.png'),('session','file','message',100,1,'附件.txt')",
            "INSERT INTO schedule_operations VALUES ('request','session','task',1,'CREATE','fingerprint',NULL,'{}',100)",
            "INSERT INTO schedule_conversation_drafts VALUES ('session','输入',2,200),('draft-session','未发送草稿',3,250)",
            "INSERT INTO schedule_pending_questions VALUES ('question','draft-session','几点？',150,NULL,NULL)",
            "INSERT INTO model_settings VALUES (1,'qwen3.8-flash','https://example.com/v1','z-image-turbo','https://example.com/images',768,X'010203',X'040506',2,100)",
            "INSERT INTO schedule_exclusions VALUES ('task','excluded')",
            "INSERT INTO schedule_occurrence_leads VALUES ('occurrence',5)",
            "INSERT INTO media_cleanup VALUES ('media/originals/unused.png')",
            "INSERT INTO schedule_local_attachments VALUES ('session','photo','原图.png',1,100,1,0,0),('session','file','附件.txt',2,100,0,0,0),('draft-session','photo','草稿图.png',1,100,1,1,1)",
            "INSERT INTO schedule_cover_jobs VALUES ('task','session','attempt','封面提示','FAILED',NULL,NULL,NULL,'NETWORK',200)",
            "INSERT INTO schedule_notification_settings VALUES (1,'${FixtureFiles[2]}','铃声.wav',512,1000,200)",
            "INSERT INTO flashnotes VALUES ('flash','已有闪念','闪念全文','COMPLETED',100,300,200,2)",
            "INSERT INTO flashnote_drafts VALUES ('draft','','未提交内容',NULL,NULL,3,400,''),('editing','编辑','未提交编辑','flash',2,4,500,'')",
            "INSERT INTO flashnote_attachments VALUES ('flash','photo','原图.png',1)",
            "INSERT INTO flashnote_draft_attachments VALUES ('draft','file','附件.txt',1)",
            "INSERT INTO flashnote_completions VALUES ('flash',$day)",
            "INSERT INTO flashnote_completion_archive VALUES (${day - 1},4)",
        ).forEach { old.execSQL(it) }
    }

    companion object {
        // 来自已安装开发版本的结构标识；该版本只有回执表，没有 needsReview 列。
        private const val IncompleteVersionThirteenHash = "58a93f89d99ef3e8e95e78c96b83e082"
        private val FixtureFiles = listOf("media/originals/dayima-migration-photo.png", "media/originals/dayima-migration-file.txt", "ringtones/dayima-migration.wav")
    }
}
