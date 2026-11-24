package com.txl.mebot.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.dayima.*
import com.txl.mebot.data.local.*
import java.time.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
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
class DayimaChatCoordinatorTest {
    private lateinit var db: MebotDatabase
    private lateinit var records: DayimaRepository
    private lateinit var chats: DayimaConversationRepository
    private lateinit var engine: DayimaChatCoordinator
    private val clock = ChatClock()
    private val today = LocalDate.of(2026, 10, 25)
    private val first = LocalDate.of(2026, 8, 1)
    private val calls = AtomicInteger()
    private var handler: suspend (DayimaAiContext) -> DayimaAiDecision = { DayimaAiDecision.Answer("你好") }
    private val contexts = mutableListOf<DayimaAiContext>()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), MebotDatabase::class.java).build()
        records = DayimaRepository(db, clock) { ZoneOffset.UTC }
        chats = DayimaConversationRepository(db, clock) { ZoneOffset.UTC }
        engine = DayimaChatCoordinator(db, records, chats, DayimaTextInterpreter { context ->
            calls.incrementAndGet(); contexts += context; handler(context)
        }, clock) { ZoneOffset.UTC }
    }
    @After fun cleanup() { db.close() }

    @Test fun recordAndFollowupEditUseTheSamePersistedTargetAndDoNotDuplicateOnRetry() = runBlocking {
        val session = chats.open(new = true)
        handler = { DayimaAiDecision.Write(listOf(DayimaChatMutation.Record(it.now.toLocalDate(), "轻微腰酸"))) }
        val request = send(session.session.id, "今天来了，也记一下腰酸")
        engine.process(session.session.id, request.id); engine.process(session.session.id, request.id)
        assertEquals(1, calls.get())
        val original = records.periods().single()
        assertEquals(1, records.notesForPeriod(original.id).size)
        handler = { context -> assertEquals(original.id, context.lastTargets.single().id)
            DayimaAiDecision.Write(listOf(DayimaChatMutation.Edit(DayimaTargetRef(last = true), today.minusDays(1)))) }
        val edit = send(session.session.id, "刚才记错了，改成昨天")
        engine.process(session.session.id, edit.id)
        assertEquals(original.id, records.periods().single().id)
        assertEquals(today.minusDays(1), records.periods().single().startDate)
        assertEquals("轻微腰酸", records.notesForPeriod(original.id).single().content)
        assertEquals(4, chats.snapshot(session.session.id)!!.messages.size)
    }

    @Test fun queryNumbersComeFromTheSameLocalStatisticsAndQueryDoesNotWriteFacts() = runBlocking {
        record(first); record(first.plusDays(28)); record(first.plusDays(56))
        val version = records.recordsSnapshot().dataVersion
        val session = chats.open(new = true)
        handler = { DayimaAiDecision.Query(DayimaChatQuery(DayimaQueryKind.STATISTICS, DayimaDateRange(today.withDayOfYear(1), today))) }
        val request = send(session.session.id, "今年有多少次刚好28天，占多少？")
        engine.process(session.session.id, request.id)
        val reply = chats.snapshot(session.session.id)!!.messages.last().content
        assertTrue(reply.contains("2 个有效完整周期"))
        assertTrue(reply.contains("恰好 2 次（100.0%）"))
        assertTrue(reply.contains("28 天 2 次"))
        assertEquals(version, records.recordsSnapshot().dataVersion)
    }

    @Test fun newSessionHasNoOldMessagesOrLastTargetAndLocalQueryBodyIsMasked() = runBlocking {
        val old = chats.open(new = true)
        handler = { DayimaAiDecision.Write(listOf(DayimaChatMutation.Record(first, null))) }
        val a = send(old.session.id, "旧聊天私密标记：8月1日开始")
        engine.process(old.session.id, a.id)
        val fresh = chats.open(new = true)
        handler = { context ->
            assertFalse(context.messages.any { it.content.contains("旧聊天私密标记") })
            assertTrue(context.lastTargets.isEmpty())
            DayimaAiDecision.Query(DayimaChatQuery(DayimaQueryKind.HISTORY))
        }
        engine.process(fresh.session.id, send(fresh.session.id, "查看开始历史").id)
        handler = { context ->
            val localReply = context.messages.first { it.id.startsWith("dayima:result:") }
            assertFalse(localReply.content.contains(first.toString()))
            assertTrue(localReply.content.contains("按需查询"))
            DayimaAiDecision.Answer("不客气")
        }
        engine.process(fresh.session.id, send(fresh.session.id, "谢谢").id)
        assertEquals(1, records.periods().size)
    }

    @Test fun failingAssistantInsertRollsBackBusinessAndReceiptAndRetryCommitsOnce() = runBlocking {
        val session = chats.open(new = true)
        val request = send(session.session.id, "今天来了，备注只出现一次")
        handler = { DayimaAiDecision.Write(listOf(DayimaChatMutation.Record(today, "只出现一次"))) }
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_dayima_reply BEFORE INSERT ON dayima_chat_messages
            WHEN NEW.role = 'ASSISTANT' BEGIN SELECT RAISE(ABORT, 'test failure'); END""")
        fails(DayimaChatFailure.LOCAL_SAVE) { engine.process(session.session.id, request.id) }
        assertTrue(records.periods().isEmpty())
        assertNull(db.dayimaWriteDao().receipt("${request.id}:0"))
        assertEquals(DayimaOperationState.FAILED, chats.snapshot(session.session.id)!!.operation!!.state)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_dayima_reply")
        engine.process(session.session.id, request.id)
        assertEquals(1, records.periods().size)
        assertEquals(1, records.notesForPeriod(records.periods().single().id).size)
        assertEquals(2, chats.snapshot(session.session.id)!!.messages.size)
    }

    @Test fun futureDateInBatchAsksBeforeAnyOperationIsCommitted() = runBlocking {
        val session = chats.open(new = true)
        handler = { DayimaAiDecision.Write(listOf(DayimaChatMutation.Record(first, null), DayimaChatMutation.Record(today.plusDays(1), null))) }
        engine.process(session.session.id, send(session.session.id, "一起补记两个日期").id)
        assertTrue(records.periods().isEmpty())
        assertEquals(DayimaOperationState.WAITING_FOR_INPUT, chats.snapshot(session.session.id)!!.operation!!.state)
        assertTrue(chats.snapshot(session.session.id)!!.messages.last().content.contains("不能在未来"))
    }

    @Test fun switchingChatInvalidatesLateLookupAndDoesNotSendAnotherModelRequest() = runBlocking {
        val old = chats.open(new = true)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        handler = { entered.complete(Unit); release.await(); DayimaAiDecision.Lookup(DayimaChatQuery(DayimaQueryKind.STATISTICS)) }
        val request = send(old.session.id, "想了解周期情况")
        val pending = async { runCatching { engine.process(old.session.id, request.id) }.exceptionOrNull() }
        withTimeout(5_000) { entered.await() }
        val fresh = chats.open(new = true)
        release.complete(Unit)
        assertEquals(DayimaChatFailure.CHANGED, (pending.await() as DayimaChatException).failure)
        assertEquals(1, calls.get())
        assertTrue(chats.snapshot(fresh.session.id)!!.messages.isEmpty())
        assertTrue(records.periods().isEmpty())
    }

    @Test fun deletingChatWhileModelWaitsCannotResurrectChatOrWriteBusiness() = runBlocking {
        val session = chats.open(new = true)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        handler = { entered.complete(Unit); release.await(); DayimaAiDecision.Write(listOf(DayimaChatMutation.Record(today, null))) }
        val request = send(session.session.id, "今天来了")
        val pending = async { runCatching { engine.process(session.session.id, request.id) }.exceptionOrNull() }
        withTimeout(5_000) { entered.await() }
        chats.delete(session.session.id); release.complete(Unit)
        assertEquals(DayimaChatFailure.CHANGED, (pending.await() as DayimaChatException).failure)
        assertNull(chats.snapshot(session.session.id))
        assertTrue(records.periods().isEmpty())
    }

    @Test fun manualChangeDuringModelRequestBlocksStaleWrite() = runBlocking {
        val session = chats.open(new = true)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        handler = { entered.complete(Unit); release.await(); DayimaAiDecision.Write(listOf(DayimaChatMutation.Record(today, null))) }
        val request = send(session.session.id, "今天开始")
        val pending = async { runCatching { engine.process(session.session.id, request.id) }.exceptionOrNull() }
        withTimeout(5_000) { entered.await() }
        record(first); release.complete(Unit)
        assertEquals(DayimaChatFailure.CHANGED, (pending.await() as DayimaChatException).failure)
        assertEquals(listOf(first), records.periods().map { it.startDate })
    }

    @Test fun concurrentProcessingClaimsOneAttemptAndOnlyCallsModelOnce() = runBlocking {
        val session = chats.open(new = true)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        handler = { entered.complete(Unit); release.await(); DayimaAiDecision.Write(listOf(DayimaChatMutation.Record(today, null))) }
        val request = send(session.session.id, "今天开始")
        val a = async { engine.process(session.session.id, request.id) }
        withTimeout(5_000) { entered.await() }
        engine.process(session.session.id, request.id)
        assertEquals(1, calls.get())
        release.complete(Unit); a.await()
        assertEquals(1, records.periods().size)
    }

    @Test fun newChatCannotUseLastAsAnImplicitGlobalLatestTarget() = runBlocking {
        record(first)
        val session = chats.open(new = true)
        handler = { DayimaAiDecision.Write(listOf(DayimaChatMutation.Edit(DayimaTargetRef(last = true), today))) }
        engine.process(session.session.id, send(session.session.id, "改成今天").id)
        assertEquals(first, records.periods().single().startDate)
        assertEquals(DayimaOperationState.WAITING_FOR_INPUT, chats.snapshot(session.session.id)!!.operation!!.state)
    }

    @Test fun reminderPatchIsDurableIdempotentAndDoesNotClaimNotificationsWereScheduled() = runBlocking {
        val session = chats.open(new = true)
        handler = { DayimaAiDecision.Write(listOf(DayimaChatMutation.Reminders(null, false, LocalTime.of(20, 0), 2))) }
        val request = send(session.session.id, "提前两天晚上八点提醒，关闭缺失记录提醒")
        engine.process(session.session.id, request.id); engine.process(session.session.id, request.id)
        val settings = db.dayimaDao().reminderSettings()!!
        assertEquals(1L, settings.revision)
        assertTrue(settings.prepareEnabled); assertFalse(settings.missingRecordEnabled)
        assertEquals(LocalTime.of(20, 0), settings.reminderTime)
        assertEquals(2, settings.prepareDaysBefore)
        val reply = chats.snapshot(session.session.id)!!.messages.last().content
        assertTrue(reply.contains("有可用的周期预测时"))
        assertTrue(reply.contains("显示在首页"))
        chats.delete(session.session.id)
        assertEquals(settings, db.dayimaDao().reminderSettings())
    }

    @Test fun newNegativeDeleteReplyOverridesEarlierDeleteClarification() = runBlocking {
        record(first)
        val session = chats.open(new = true)
        handler = { DayimaAiDecision.Ask("要删除哪一天？", DayimaOperationAction.DELETE) }
        engine.process(session.session.id, send(session.session.id, "删除一条记录").id)
        handler = { DayimaAiDecision.Write(listOf(DayimaChatMutation.Delete(DayimaTargetRef(date = first)))) }
        engine.process(session.session.id, send(session.session.id, "不要删了").id)
        assertEquals(1, records.periods().size)
        assertTrue(chats.snapshot(session.session.id)!!.messages.last().content.contains("未删除"))
    }

    @Test fun consultationLookupOnlySendsRequestedAggregatesWithoutBirthOrPrivateNotes() = runBlocking {
        record(first); record(first.plusDays(28)); record(first.plusDays(56))
        records.execute(DayimaChange.SaveBirthDate(LocalDate.of(1998, 3, 16)))
        records.execute(DayimaChange.AddNote(first, "PRIVATE_NOTE_NOT_REQUESTED"))
        val session = chats.open(new = true)
        handler = { context ->
            if (context.reads.isEmpty()) DayimaAiDecision.Lookup(DayimaChatQuery(DayimaQueryKind.STATISTICS, includeAge = true))
            else {
                val text = context.reads.single().toString()
                assertFalse(text.contains("1998-03-16")); assertFalse(text.contains("PRIVATE_NOTE_NOT_REQUESTED"))
                assertEquals(2, context.reads.single().getInt("eligible"))
                DayimaAiDecision.Answer("这些是历史周期的描述，不能仅凭数字确定变化原因。")
            }
        }
        engine.process(session.session.id, send(session.session.id, "结合年龄看看周期有没有变化").id)
        assertEquals(2, calls.get())
        assertTrue(chats.snapshot(session.session.id)!!.messages.last().id.startsWith("dayima:consult:"))
    }

    @Test fun replayingSentMessageNeverClearsAYoungerUnsentDraft() = runBlocking {
        val session = chats.open(new = true)
        val request = send(session.session.id, "第一条")
        val draft = chats.snapshot(session.session.id)!!.draft
        val newer = chats.saveInput(session.session.id, "下一条尚未发送", draft.revision)
        val replay = chats.send(session.session.id, request.content, request.id, draft.revision)
        assertEquals(request, replay)
        assertEquals(newer, chats.snapshot(session.session.id)!!.draft)
    }

    @Test fun retryKeepsOriginalSentDateAndRecoveryDoesNotAutomaticallyCallModel() = runBlocking {
        val session = chats.open(new = true)
        handler = { throw DayimaChatException(DayimaChatFailure.NETWORK) }
        val request = send(session.session.id, "今天来了")
        fails(DayimaChatFailure.NETWORK) { engine.process(session.session.id, request.id) }
        clock.time = clock.time.plus(Duration.ofDays(1))
        val reopened = chats.open(session.session.id)
        assertEquals(1, calls.get())
        assertEquals(DayimaOperationState.FAILED, reopened.operation!!.state)
        handler = { context -> assertEquals(today, context.now.toLocalDate()); DayimaAiDecision.Write(listOf(DayimaChatMutation.Record(context.now.toLocalDate(), null))) }
        engine.process(session.session.id, request.id)
        assertEquals(today, records.periods().single().startDate)
    }

    @Test fun deletingCompletedChatPreservesPeriodNotesProfileAndTransactionReceipt() = runBlocking {
        val session = chats.open(new = true)
        handler = { DayimaAiDecision.Write(listOf(DayimaChatMutation.Record(today, "保留备注"), DayimaChatMutation.Birth(LocalDate.of(2000, 1, 1)))) }
        val request = send(session.session.id, "今天来了，出生日期2000年1月1日请保存")
        engine.process(session.session.id, request.id)
        chats.delete(session.session.id)
        assertNull(chats.snapshot(session.session.id))
        assertEquals(1, records.periods().size)
        assertEquals(1, records.notesForPeriod(records.periods().single().id).size)
        assertNotNull(records.profile()?.birthDate)
        assertNotNull(db.dayimaWriteDao().receipt("${request.id}:0"))
    }

    private suspend fun record(date: LocalDate) { records.execute(DayimaChange.RecordPeriod(date)) }
    private suspend fun send(id: String, text: String): DayimaChatMessageEntity {
        val draft = chats.snapshot(id)!!.draft
        val saved = chats.saveInput(id, text, draft.revision)
        return chats.send(id, text, UUID.randomUUID().toString(), saved.revision)
    }
    private suspend fun fails(failure: DayimaChatFailure, block: suspend () -> Unit) {
        try { block(); fail("expected $failure") } catch (error: DayimaChatException) { assertEquals(failure, error.failure) }
    }
    private class ChatClock : Clock() {
        var time: Instant = Instant.parse("2026-10-25T12:00:00Z")
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(time, zone)
        override fun instant(): Instant = time
    }
}
