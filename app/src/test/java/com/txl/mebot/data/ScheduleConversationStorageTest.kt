package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.MediaAssetEntity
import com.txl.mebot.data.local.MediaSource
import com.txl.mebot.data.local.MessageRole
import com.txl.mebot.data.schedule.ConversationDraftConflict
import com.txl.mebot.data.schedule.RecurrencePattern
import com.txl.mebot.data.schedule.RecurrenceRule
import com.txl.mebot.data.schedule.ScheduleConversationRepository
import com.txl.mebot.data.schedule.ScheduleDraft
import com.txl.mebot.data.schedule.ScheduleRepository
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
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
class ScheduleConversationStorageTest {
    private lateinit var context: Context
    private lateinit var database: MebotDatabase
    private lateinit var schedules: ScheduleRepository
    private lateinit var conversations: ScheduleConversationRepository
    private val clock = Clock.fixed(Instant.parse("2026-10-22T02:00:00Z"), ZoneOffset.UTC)
    private val day = LocalDate.of(2026, 10, 22)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, MebotDatabase::class.java).build()
        connectRepositories()
    }

    private fun connectRepositories() {
        schedules = ScheduleRepository(database, clock) { ZoneId.of("Asia/Shanghai") }
        conversations = ScheduleConversationRepository(database, schedules, clock)
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
    }

    @Test fun reopenDatabaseRestoresMessagesUnsentTextAndPendingQuestion() = runBlocking {
        database.close()
        database = MebotDatabase.create(context)
        connectRepositories()
        val session = conversations.openCreation()
        val draft = conversations.saveInput(session.sessionId, "明天开会", session.revision)
        conversations.sendUserMessage(session.sessionId, "user-1", draft.inputText, draft.revision)
        conversations.recordQuestion(session.sessionId, "question-1", "明天几点开会？")
        val empty = conversations.openConversation(session.sessionId)
        conversations.saveInput(session.sessionId, "下午四点，还要讨论", empty.revision)

        database.close()
        database = MebotDatabase.create(context)
        connectRepositories()
        val restored = conversations.openCreation()
        assertEquals(session.sessionId, restored.sessionId)
        assertEquals("下午四点，还要讨论", restored.inputText)
        assertEquals(listOf("明天开会", "明天几点开会？"), conversations.observeMessages(session.sessionId, 40).first().map { it.content })
        assertEquals("明天几点开会？", conversations.observePendingQuestions(session.sessionId).first().single().question)
        assertNull(schedules.taskForSession(session.sessionId))
    }

    @Test fun repeatedNewScheduleEntryRestoresOnlyOneCreationConversation() = runBlocking {
        val first = conversations.openCreation()
        val draft = conversations.saveInput(first.sessionId, "今天买土豆和猕猴桃", first.revision)
        repeat(5) { assertEquals(draft, conversations.openCreation()) }
        assertEquals(1, sessionCount())
        conversations.sendUserMessage(first.sessionId, "message", draft.inputText, draft.revision)
        val afterSend = conversations.openCreation()
        assertEquals(first.sessionId, afterSend.sessionId)
        assertEquals("", afterSend.inputText)
        assertEquals(1, conversations.observeMessages(first.sessionId, 40).first().size)
    }

    @Test fun staleDraftWritesAndSendsCannotOverwriteNewerInput() = runBlocking {
        val initial = conversations.openCreation()
        val latest = conversations.saveInput(initial.sessionId, "四点开会", initial.revision)
        assertTrue(runCatching { conversations.saveInput(initial.sessionId, "三点开会", initial.revision) }.exceptionOrNull() is ConversationDraftConflict)
        assertTrue(runCatching { conversations.sendUserMessage(initial.sessionId, "stale-send", "三点开会", initial.revision) }.exceptionOrNull() is ConversationDraftConflict)
        assertTrue(runCatching { conversations.sendUserMessage(initial.sessionId, "wrong-content", "另一个描述", latest.revision) }.isFailure)
        assertEquals(latest, conversations.openConversation(initial.sessionId))
        assertTrue(conversations.observeMessages(initial.sessionId, 40).first().isEmpty())
    }

    @Test fun clearCreatesNewSessionAndRemovesOnlyTheUnfinishedConversation() = runBlocking {
        database.close()
        database = MebotDatabase.create(context)
        connectRepositories()
        val original = conversations.openCreation()
        database.mediaDao().insert(MediaAssetEntity(
            id = "local-image", source = MediaSource.BUILT_IN, builtinKey = "meeting",
            mimeType = "image/webp", byteCount = 0, width = 768, height = 768, createdAt = 100,
        ))
        schedules.appendMessage(original.sessionId, "with-attachment", MessageRole.USER, "明天开会", listOf("local-image"))
        conversations.recordQuestion(original.sessionId, "pending-question", "几点开会？")
        conversations.saveInput(original.sessionId, "下午四点，还未发送", original.revision)
        val fresh = conversations.clearCreation()
        assertNotEquals(original.sessionId, fresh.sessionId)
        assertEquals("", fresh.inputText)
        assertNull(database.scheduleDao().session(original.sessionId))
        assertNull(database.scheduleDao().message("with-attachment"))
        assertNull(database.conversationDao().draft(original.sessionId))
        assertNull(database.conversationDao().question("pending-question"))
        assertTrue(schedules.attachments(original.sessionId).isEmpty())
        assertNull(database.mediaDao().asset("local-image")) // 清空草稿也清理其无人引用的附件元数据。
        assertTrue(runCatching { conversations.saveInput(original.sessionId, "迟到的保存", 1) }.isFailure)
        database.close()
        database = MebotDatabase.create(context)
        connectRepositories()
        assertEquals(fresh, conversations.openCreation())
        assertEquals(1, sessionCount())
    }

    @Test fun entryKeepsNewestLegacyDraftAndDeletesOlderUnfinishedHistories() = runBlocking {
        val older = schedules.openSession("older")
        conversations.openConversation(older.id)
        conversations.recordQuestion(older.id, "old-question", "老草稿的问题")
        database.scheduleDao().touchSession(older.id, 100)
        val latest = schedules.openSession("latest")
        val draft = conversations.openConversation(latest.id)
        conversations.saveInput(latest.id, "最近编辑的草稿", draft.revision)
        database.scheduleDao().touchSession(latest.id, 200)
        val empty = schedules.openSession("last-empty-entry")
        conversations.openConversation(empty.id)
        database.scheduleDao().touchSession(empty.id, 300)
        val restored = conversations.openCreation()
        assertEquals(latest.id, restored.sessionId)
        assertEquals("最近编辑的草稿", restored.inputText)
        assertNull(database.scheduleDao().session(older.id))
        assertNull(database.scheduleDao().session(empty.id))
        assertNull(database.conversationDao().question("old-question"))
        assertEquals(1, sessionCount())
    }

    @Test fun clearFailureRollsBackDeletionAndDoesNotLeaveExtraNewSessions() = runBlocking {
        val original = conversations.openCreation()
        val input = conversations.saveInput(original.sessionId, "不能丢失的草稿", original.revision)
        schedules.appendMessage(original.sessionId, "message", MessageRole.USER, "之前的描述")
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_session_delete BEFORE DELETE ON schedule_sessions
            BEGIN SELECT RAISE(ABORT, 'simulated clear failure'); END
        """.trimIndent())
        assertTrue(runCatching { conversations.clearCreation() }.isFailure)
        assertEquals(input, conversations.openCreation())
        assertEquals(1, conversations.observeMessages(original.sessionId, 40).first().size)
        assertEquals(1, sessionCount())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_session_delete")
        val fresh = conversations.clearCreation()
        assertNotEquals(original.sessionId, fresh.sessionId)
        assertEquals(1, sessionCount())
    }

    @Test fun messageCommitFailureKeepsDraftAndRetryOnlySendsOnce() = runBlocking {
        val initial = conversations.openCreation()
        val draft = conversations.saveInput(initial.sessionId, "  今天买菜  ", initial.revision)
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_clear BEFORE UPDATE ON schedule_conversation_drafts
            WHEN NEW.inputText = '' BEGIN SELECT RAISE(ABORT, 'simulated disk write failure'); END
        """.trimIndent())
        assertTrue(runCatching { conversations.sendUserMessage(draft.sessionId, "send", draft.inputText, draft.revision) }.isFailure)
        assertEquals(draft, conversations.openConversation(draft.sessionId))
        assertTrue(conversations.observeMessages(draft.sessionId, 40).first().isEmpty())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_clear")
        val sent = conversations.sendUserMessage(draft.sessionId, "send", draft.inputText, draft.revision)
        assertEquals("", sent.inputText)
        val next = conversations.saveInput(draft.sessionId, "再加茄子", sent.revision)
        val replay = conversations.sendUserMessage(draft.sessionId, "send", draft.inputText, draft.revision)
        assertEquals(next, replay) // 旧消息重试不能清掉后来补充的草稿。
        assertEquals(listOf("今天买菜"), conversations.observeMessages(draft.sessionId, 40).first().map { it.content })
        assertTrue(runCatching { conversations.sendUserMessage(draft.sessionId, "send", "另一个内容", next.revision) }.isFailure)
        assertEquals(next, conversations.openConversation(draft.sessionId))
    }

    @Test fun pendingQuestionRequiresAnExplicitLaterAnswerInTheSameConversation() = runBlocking {
        val first = conversations.openCreation()
        conversations.sendUserMessage(first.sessionId, "before", "每天喂猫", first.revision)
        conversations.recordQuestion(first.sessionId, "question", "几点提醒？")
        conversations.recordQuestion(first.sessionId, "question", "几点提醒？")
        schedules.createTask(first.sessionId, "create-first-task", ScheduleDraft("喂猫"))
        val second = conversations.openCreation()
        conversations.sendUserMessage(second.sessionId, "foreign", "上午九点", second.revision)
        assertTrue(runCatching { conversations.resolveQuestion(first.sessionId, "question", "before") }.isFailure)
        assertTrue(runCatching { conversations.resolveQuestion(first.sessionId, "question", "foreign") }.isFailure)
        val draft = conversations.openConversation(first.sessionId)
        conversations.sendUserMessage(first.sessionId, "answer", "每天上午九点", draft.revision)
        assertEquals(1, conversations.observePendingQuestions(first.sessionId).first().size)
        conversations.resolveQuestion(first.sessionId, "question", "answer")
        conversations.resolveQuestion(first.sessionId, "question", "answer")
        assertTrue(conversations.observePendingQuestions(first.sessionId).first().isEmpty())
        assertEquals("answer", database.conversationDao().question("question")?.answerMessageId)
        assertEquals(3, conversations.observeMessages(first.sessionId, 40).first().size)
    }

    @Test fun recurringOccurrenceCardsAndTaskAlwaysOpenTheirOriginalConversation() = runBlocking {
        val recurring = conversations.openCreation()
        val task = schedules.createTask(recurring.sessionId, "create-rule", ScheduleDraft("喂猫", recurrence = RecurrenceRule(RecurrencePattern.DAILY)))
        schedules.materializeRange(day, day.plusDays(2))
        val occurrences = database.scheduleDao().occurrencesForTask(task.id)
        assertEquals(3, occurrences.size)
        assertEquals(recurring.sessionId, conversations.openForTask(task.id).sessionId)
        for (occurrence in occurrences) assertEquals(recurring.sessionId, conversations.openForOccurrence(occurrence.id).sessionId)
        val draft = conversations.saveInput(recurring.sessionId, "以后改为早上喂", recurring.revision)
        assertEquals(draft, conversations.openForOccurrence(occurrences.last().id))

        val ordinary = conversations.openCreation()
        val oneOff = schedules.createTask(ordinary.sessionId, "create-one", ScheduleDraft("买猫粮"))
        val card = database.scheduleDao().occurrencesForTask(oneOff.id).single()
        assertEquals(ordinary.sessionId, conversations.openForOccurrence(card.id).sessionId)
        assertNotEquals(recurring.sessionId, ordinary.sessionId)
        schedules.deleteTask(ordinary.sessionId, "delete", oneOff.revision)
        assertTrue(runCatching { conversations.openForOccurrence(card.id) }.isFailure)
        val pending = conversations.clearCreation()
        assertNotEquals(ordinary.sessionId, pending.sessionId)
        assertEquals(draft, conversations.openForOccurrence(occurrences.last().id))
        assertTrue(runCatching { conversations.openForOccurrence(card.id) }.isFailure)
        assertNull(schedules.taskForSession(ordinary.sessionId, includeDeleted = true))
    }

    @Test fun messageWindowDoesNotDiscardOlderHistory() = runBlocking {
        val session = conversations.openCreation()
        repeat(85) { index -> schedules.appendMessage(session.sessionId, "message-$index", MessageRole.USER, "补充 $index") }
        val recent = conversations.observeMessages(session.sessionId, 40).first()
        assertEquals(40, recent.size)
        assertEquals(46L, recent.first().sequence)
        assertEquals(85L, recent.last().sequence)
        assertEquals(85, conversations.observeMessages(session.sessionId, 120).first().size)
        assertEquals(session.sessionId, conversations.openCreation().sessionId)
    }

    @Test fun versionOneMigrationPreservesEveryExistingTableAndAddsRecoverableDrafts() = runBlocking {
        database.close()
        val schemaPath = "schemas/com.txl.mebot.data.local.MebotDatabase/1.json"
        val schemaFile = File(schemaPath).takeIf { it.exists() } ?: File("app/$schemaPath")
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        val path = context.getDatabasePath(MebotDatabase.FILE_NAME)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            val epoch = day.toEpochDay()
            old.execSQL("INSERT INTO schedule_sessions VALUES ('legacy-session', 100, 200)")
            old.execSQL("INSERT INTO schedule_messages VALUES ('legacy-message', 'legacy-session', 1, 'USER', '每天喂猫', 100)")
            old.execSQL("INSERT INTO media_assets (id,source,builtinKey,mimeType,byteCount,width,height,createdAt) VALUES ('legacy-image','BUILT_IN','cat','image/webp',0,768,768,100)")
            old.execSQL("INSERT INTO schedule_tasks VALUES ('legacy-task','legacy-session','喂猫','准备猫粮',$epoch,540,'legacy-image',1,100,200,NULL)")
            old.execSQL("INSERT INTO schedule_task_versions VALUES ('legacy-task',1,$epoch,'喂猫','准备猫粮',$epoch,540,'legacy-image',100)")
            old.execSQL("INSERT INTO schedule_recurrences VALUES ('legacy-task',1,'DAILY',1,0,NULL,NULL,1)")
            old.execSQL("INSERT INTO schedule_reminder_leads VALUES ('legacy-task',1,10)")
            old.execSQL("INSERT INTO schedule_occurrences VALUES ('legacy-card','legacy-task','2026-10-22','喂猫','准备猫粮',$epoch,540,'legacy-image',1,1,NULL,0,100,200)")
            old.execSQL("INSERT INTO schedule_reminders VALUES ('legacy-alarm','legacy-card',1,10,999999,'Asia/Shanghai','PENDING',NULL,100)")
            old.execSQL("INSERT INTO schedule_attachments VALUES ('legacy-session','legacy-image','legacy-message',100)")
            old.execSQL("INSERT INTO schedule_operations VALUES ('legacy-operation','legacy-session','legacy-task',1,'CREATE','fingerprint',NULL,'{}',100)")
            old.version = 1
        }
        database = MebotDatabase.create(context)
        connectRepositories()
        assertEquals("", conversations.openConversation("legacy-session").inputText) // 强制 Room 实际迁移并校验 schema。
        assertEquals(200L, database.scheduleDao().session("legacy-session")?.updatedAt)
        assertEquals("每天喂猫", conversations.observeMessages("legacy-session", 40).first().single().content)
        assertEquals("喂猫", schedules.taskForSession("legacy-session")?.title)
        assertEquals("legacy-image", database.scheduleDao().occurrence("legacy-card")?.coverAssetId)
        assertEquals(RecurrencePattern.DAILY, schedules.recurrenceForSession("legacy-session")?.pattern)
        assertEquals(listOf(10), schedules.reminderMinutesForSession("legacy-session"))
        assertEquals(1, schedules.attachments("legacy-session").size)
        assertEquals(1, schedules.operationHistory("legacy-session").size)
        assertEquals(1, database.scheduleDao().reminders("legacy-card").size)
        assertNotNull(database.mediaDao().asset("legacy-image"))
        val draft = conversations.openConversation("legacy-session")
        conversations.saveInput("legacy-session", "再加一碗水", draft.revision)
        conversations.recordQuestion("legacy-session", "new-question", "要提前几分钟？")
        assertEquals(1, conversations.observePendingQuestions("legacy-session").first().size)
    }

    private fun sessionCount(): Int = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM schedule_sessions").use {
        it.moveToFirst()
        it.getInt(0)
    }

}
