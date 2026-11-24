package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.*
import com.txl.mebot.data.media.LocalMediaStore
import com.txl.mebot.data.subscriptions.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SubscriptionChatCoordinatorTest {
    private lateinit var context: Context
    private lateinit var db: MebotDatabase
    private lateinit var storage: SubscriptionStorage
    private lateinit var records: SubscriptionRepository
    private lateinit var chats: SubscriptionConversationRepository
    private lateinit var coordinator: SubscriptionChatCoordinator
    private lateinit var media: LocalMediaStore
    private lateinit var attachments: SubscriptionAttachmentRepository
    private val clock = Clock.fixed(Instant.parse("2026-10-27T08:00:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.of(2026, 10, 31)
    private var output: SubscriptionInterpretation = SubscriptionInterpretation.Ask("每次多少钱、多久扣费一次，下次扣费是哪天，是否自动续费？")
    private var received: SubscriptionAnalysisContext? = null
    private var calls = 0
    private fun values(amount: Long = 2500) = SubscriptionValues("云盘会员", amount, "CNY", SubscriptionCycleUnit.MONTH, 1,
        anchorDate = date, nextBillingDate = date, autoRenew = true)
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(MebotDatabase.FILE_NAME)
        db = MebotDatabase.create(context)
        storage = SubscriptionStorage(db, db.subscriptions(), db.subscriptionConversations(), db.mediaDao())
        records = SubscriptionRepository(storage, clock); chats = SubscriptionConversationRepository(storage, clock)
        media = LocalMediaStore(context, db); attachments = SubscriptionAttachmentRepository(context, storage, media, clock)
        coordinator = make(SubscriptionInterpreter { received = it; calls++; output })
    }
    private fun make(interpreter: SubscriptionInterpreter, imageData: suspend (java.io.File) -> String = { "data:image/jpeg;base64,ZmFrZQ==" }) = SubscriptionChatCoordinator(storage, records, chats,
        SubscriptionLifecycle(storage, records, clock) { ZoneOffset.UTC }, interpreter, media::fileFor,
        imageData = imageData, clock = clock, zone = { ZoneOffset.UTC })
    @After fun teardown() { db.close(); context.deleteDatabase(MebotDatabase.FILE_NAME) }
    private suspend fun send(id: String, text: String, assets: List<String> = emptyList()): String {
        val old = storage.conversations.draft(id)!!
        val draft = chats.saveDraft(id, text, old.fieldsJson, old.revision)
        val messageId = UUID.randomUUID().toString()
        coordinator.send(id, messageId, text, assets, draft.revision)
        return messageId
    }
    private suspend fun meta(id: String) = SubscriptionChatMeta.read(storage.conversations.draft(id)!!.fieldsJson)
    private suspend fun proposal(id: String) = requireNotNull(storage.records.operation(requireNotNull(meta(id).proposalId)))
    private suspend fun image(id: String, color: Int = android.graphics.Color.BLUE): MediaAssetEntity {
        val bytes = ByteArrayOutputStream().use { stream ->
            Bitmap.createBitmap(12, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.let { bitmap ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); bitmap.recycle()
            }; stream.toByteArray()
        }
        return attachments.importImage(id, { ByteArrayInputStream(bytes) })
    }

    @Test fun missingFieldsAskInChatAndNeverCreateAPlaceholderCard() = runBlocking {
        val session = chats.newConversation()
        val message = send(session.id, "我买了好医保")
        coordinator.analyze(session.id, message)
        assertTrue(storage.records.all().isEmpty())
        assertTrue(chats.messages(session.id).last().content.contains("多少钱"))
        assertNull(meta(session.id).proposalId)
        assertNull(meta(session.id).analysisId)
        assertEquals(1, calls)
    }
    @Test fun completeTextProposesThenConfirmationCreatesExactlyOneBoundCard() = runBlocking {
        val session = chats.newConversation()
        output = SubscriptionInterpretation.Propose(values())
        val message = send(session.id, "云盘会员，每月25元，自动续费，10月31日扣费")
        coordinator.analyze(session.id, message)
        val pending = proposal(session.id)
        assertEquals(SubscriptionOperationState.PENDING, pending.state)
        assertTrue(storage.records.all().isEmpty())
        val results = listOf(async { coordinator.confirm(session.id, pending.id) }, async { coordinator.confirm(session.id, pending.id) }).awaitAll()
        assertEquals(results.first().id, results.last().id)
        val item = storage.records.all().single()
        assertEquals(2500L, item.amountMinor)
        assertEquals(item.id, storage.conversations.conversation(session.id)!!.subscriptionId)
        assertEquals(1, records.history(item.id).size)
        assertEquals(1, chats.messages(session.id).count { "已创建" in it.content })
        assertTrue(records.payments(item.id).isEmpty())
    }
    @Test fun confirmingByChatTextUsesLocalTransactionWithoutAnotherModelRequest() = runBlocking {
        val session = chats.newConversation(); output = SubscriptionInterpretation.Propose(values())
        coordinator.analyze(session.id, send(session.id, "完整订阅信息"))
        send(session.id, "确认")
        assertEquals(1, storage.records.all().size)
        assertEquals(1, calls)
        assertNull(meta(session.id).proposalId)
    }
    @Test fun continuingToTalkCancelsOldProposalAndOnlyNewestValuesCanBeSaved() = runBlocking {
        val session = chats.newConversation(); output = SubscriptionInterpretation.Propose(values())
        coordinator.analyze(session.id, send(session.id, "完整信息")); val old = proposal(session.id)
        output = SubscriptionInterpretation.Propose(values(3000))
        coordinator.analyze(session.id, send(session.id, "金额改成30元"))
        assertEquals(SubscriptionOperationState.CANCELLED, storage.records.operation(old.id)!!.state)
        assertTrue(runCatching { coordinator.confirm(session.id, old.id) }.exceptionOrNull() is SubscriptionConflict)
        coordinator.confirm(session.id, proposal(session.id).id)
        assertEquals(3000L, storage.records.all().single().amountMinor)
    }
    @Test fun screenshotIsAttachedToOnlyItsSentMessageAndBecomesPersistentCardImage() = runBlocking {
        val session = chats.newConversation(); val sent = image(session.id)
        output = SubscriptionInterpretation.Propose(values())
        val message = send(session.id, "", listOf(sent.id))
        val unsubmitted = image(session.id, android.graphics.Color.GREEN)
        coordinator.analyze(session.id, message)
        assertEquals(listOf(sent.id), received!!.images.map { it.assetId })
        assertEquals(message, received!!.images.single().messageId)
        val saved = coordinator.confirm(session.id, proposal(session.id).id)
        assertEquals(sent.id, saved.coverAssetId)
        assertTrue(media.fileFor(sent.id)!!.isFile)
        assertTrue(storage.conversations.mediaLinks(session.id).single { it.assetId == unsubmitted.id }.pendingSend)
        assertFalse(storage.conversations.mediaLinks(session.id).single { it.assetId == sent.id }.pendingSend)
    }
    @Test fun currentSessionContextCannotContainAnotherSubscriptionOrUnsentInput() = runBlocking {
        val other = chats.newConversation(); send(other.id, "OTHER_PERSON_SECRET")
        val session = chats.newConversation(); val first = send(session.id, "只有这个订阅")
        coordinator.analyze(session.id, first)
        val second = send(session.id, "每月25元，自动续费")
        val draft = storage.conversations.draft(session.id)!!
        chats.saveDraft(session.id, "UNSENT_PRIVATE_TEXT", draft.fieldsJson, draft.revision)
        coordinator.analyze(session.id, second)
        val request = subscriptionModelRequest("configured-model", received!!).toString()
        assertFalse(request.contains("OTHER_PERSON_SECRET")); assertFalse(request.contains("UNSENT_PRIVATE_TEXT"))
        assertTrue(received!!.messages.all { it.conversationId == session.id })
        assertTrue(received!!.messages.any { it.id == first })
    }
    @Test fun multipleSelectedImagesSurviveFollowUpQuestionsAndUseFirstSelectionAsCover() = runBlocking {
        val session = chats.newConversation()
        val first = image(session.id, android.graphics.Color.RED); val second = image(session.id, android.graphics.Color.GREEN)
        val question = send(session.id, "登记这些订阅截图", listOf(first.id, second.id))
        coordinator.analyze(session.id, question)
        output = SubscriptionInterpretation.Propose(values())
        val answer = send(session.id, "云盘会员，每月25元，自动续费，10月31日扣费")
        coordinator.analyze(session.id, answer)
        val saved = coordinator.confirm(session.id, proposal(session.id).id)
        assertEquals(first.id, saved.coverAssetId)
        assertEquals(listOf(first.id, second.id), records.imageIds(saved.id))
    }

    @Test fun laterChatAddsNewImagesWithoutRestoringPreviouslyDeletedGalleryImages() = runBlocking {
        val session = chats.newConversation(); val first = image(session.id); val second = image(session.id, android.graphics.Color.RED)
        output = SubscriptionInterpretation.Propose(values())
        val message = send(session.id, "登记订阅", listOf(first.id, second.id))
        coordinator.analyze(session.id, message)
        var item = coordinator.confirm(session.id, proposal(session.id).id)
        item = records.update("remove-gallery-image", item.id, item.revision,
            SubscriptionValues.from(item).copy(imageAssetIds = listOf(first.id)))
        val text = send(session.id, "备注改成家庭使用")
        coordinator.analyze(session.id, text)
        item = coordinator.confirm(session.id, proposal(session.id).id)
        assertEquals(listOf(first.id), records.imageIds(item.id))
        val third = image(session.id, android.graphics.Color.GREEN)
        val addition = send(session.id, "加这张截图", listOf(third.id))
        coordinator.analyze(session.id, addition)
        item = coordinator.confirm(session.id, proposal(session.id).id)
        assertEquals(first.id, item.coverAssetId)
        assertEquals(listOf(first.id, third.id), records.imageIds(item.id))
        assertNotNull(media.fileFor(second.id))
    }
    @Test fun laterChatEditsOnlyItsBoundCardAndCannotCreateAnotherSubscription() = runBlocking {
        val session = chats.newConversation(); output = SubscriptionInterpretation.Propose(values())
        coordinator.analyze(session.id, send(session.id, "完整信息")); val item = coordinator.confirm(session.id, proposal(session.id).id)
        output = SubscriptionInterpretation.Propose(values(3500))
        coordinator.analyze(session.id, send(session.id, "改成35元"))
        coordinator.confirm(session.id, proposal(session.id).id)
        assertEquals(1, storage.records.all().size)
        assertEquals(item.id, storage.records.all().single().id)
        assertEquals(3500L, records.get(item.id)!!.amountMinor)
        assertEquals(2, records.history(item.id).size)
    }
    @Test fun unrelatedServiceCannotSilentlyReplaceTheOriginalCard() = runBlocking {
        val session = chats.newConversation(); output = SubscriptionInterpretation.Propose(values())
        coordinator.analyze(session.id, send(session.id, "完整信息")); val item = coordinator.confirm(session.id, proposal(session.id).id)
        output = SubscriptionInterpretation.Propose(values().copy(title = "视频会员"))
        coordinator.analyze(session.id, send(session.id, "我还买了视频会员"))
        assertNull(meta(session.id).proposalId)
        assertEquals(item, records.get(item.id))
        assertTrue(chats.messages(session.id).last().content.contains("新建会话"))
    }
    @Test fun changedRecordDuringNetworkCallRejectsStaleResult() = runBlocking {
        val session = chats.newConversation(); val item = records.create("existing", values(), session.id)
        val message = send(session.id, "改为35元")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val delayed = make(SubscriptionInterpreter { entered.complete(Unit); release.await(); SubscriptionInterpretation.Propose(values(3500)) })
        val job = async { runCatching { delayed.analyze(session.id, message) }.exceptionOrNull() }
        entered.await()
        records.update("external", item.id, item.revision, values(5000))
        release.complete(Unit)
        assertEquals(SubscriptionAnalysisFailure.CHANGED, (job.await() as SubscriptionAnalysisException).failure)
        assertNull(meta(session.id).proposalId)
        assertEquals(5000L, records.get(item.id)!!.amountMinor)
    }
    @Test fun failedConfirmationRollsBackCardBindingHistoryAndSuccessReplyButKeepsProposal() = runBlocking {
        val session = chats.newConversation(); output = SubscriptionInterpretation.Propose(values())
        coordinator.analyze(session.id, send(session.id, "完整信息")); val operation = proposal(session.id)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_chat_history BEFORE INSERT ON subscription_changes BEGIN SELECT RAISE(ABORT, 'fixture failure'); END")
        assertTrue(runCatching { coordinator.confirm(session.id, operation.id) }.isFailure)
        assertTrue(storage.records.all().isEmpty())
        assertNull(storage.conversations.conversation(session.id)!!.subscriptionId)
        assertEquals(SubscriptionOperationState.PENDING, storage.records.operation(operation.id)!!.state)
        assertEquals(operation.id, meta(session.id).proposalId)
        assertFalse(chats.messages(session.id).any { "已创建" in it.content })
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_chat_history")
        coordinator.confirm(session.id, operation.id)
        assertEquals(1, storage.records.all().size)
    }
    @Test fun failureAndExplicitRetryReuseSentMessageAndKeepMediaAndHistory() = runBlocking {
        val session = chats.newConversation(); val photo = image(session.id)
        val message = send(session.id, "订阅信息", listOf(photo.id))
        coordinator.fail(session.id, message, SubscriptionAnalysisFailure.NETWORK)
        assertEquals(SubscriptionAnalysisFailure.NETWORK, meta(session.id).failure)
        assertEquals(message, coordinator.retry(session.id))
        output = SubscriptionInterpretation.Propose(values())
        coordinator.analyze(session.id, message)
        assertEquals(1, chats.messages(session.id).count { it.role == MessageRole.USER })
        assertTrue(media.fileFor(photo.id)!!.isFile)
        assertEquals(1, calls)
    }
    @Test fun newerMessagePreventsAnOldResultFromReopeningAnOutdatedProposal() = runBlocking {
        val session = chats.newConversation(); val old = send(session.id, "旧信息")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val delayed = make(SubscriptionInterpreter { entered.complete(Unit); release.await(); SubscriptionInterpretation.Propose(values()) })
        val job = async { delayed.analyze(session.id, old) }
        entered.await(); val new = send(session.id, "新信息"); release.complete(Unit); job.await()
        assertNull(meta(session.id).proposalId)
        assertEquals(new, meta(session.id).analysisId)
    }
    @Test fun inputMetadataPreservesOtherDraftsAndMarksSentBackupTokenAsConsumed() = runBlocking {
        val session = chats.newConversation()
        val draft = storage.conversations.draft(session.id)!!
        val fields = JSONObject().put("manualEditor", JSONObject().put("notes", "原编辑草稿")).put("paymentEditors", JSONObject().put("extra", "扣费草稿")).put("chatInputToken", "token")
        val saved = chats.saveDraft(session.id, "订阅信息", fields.toString(), draft.revision)
        coordinator.send(session.id, "message", "订阅信息", emptyList(), saved.revision, "token")
        val after = storage.conversations.draft(session.id)!!
        assertEquals("", after.inputText)
        val json = JSONObject(after.fieldsJson)
        assertEquals("原编辑草稿", json.getJSONObject("manualEditor").getString("notes"))
        assertEquals("扣费草稿", json.getJSONObject("paymentEditors").getString("extra"))
        assertEquals("token", json.getString("chatLastSentToken"))
        assertFalse(json.has("chatInputToken"))
    }
    @Test fun moreThanOneContextPageKeepsFirstAndLatestUserMessagesWithinBudget() = runBlocking {
        val session = chats.newConversation(); val first = send(session.id, "最初的订阅服务")
        repeat(55) { send(session.id, "补充内容 $it " + "内容".repeat(300)) }
        val latest = send(session.id, "最后的订阅要求")
        coordinator.analyze(session.id, latest)
        assertTrue(received!!.historyTrimmed)
        assertTrue(received!!.messages.any { it.id == first })
        assertEquals(latest, received!!.messages.last().id)
        assertTrue(received!!.messages.sumOf { it.content.length } <= 24_000)
    }
    @Test fun repricingEndedSubscriptionCannotRestoreItWithoutExplicitUserIntent() = runBlocking {
        val session = chats.newConversation(); val item = records.create("existing", values(), session.id)
        legacyArchived(records, "archive", item.id, item.revision)
        output = SubscriptionInterpretation.Propose(values(3000))
        coordinator.analyze(session.id, send(session.id, "金额改成30元"))
        assertNull(meta(session.id).proposalId)
        assertEquals(SubscriptionStatus.ARCHIVED, records.get(item.id)!!.status)
        coordinator.analyze(session.id, send(session.id, "恢复订阅，下次10月31日扣费"))
        assertEquals(SubscriptionOperationKind.RESTORE, proposal(session.id).kind)
        coordinator.confirm(session.id, proposal(session.id).id)
        assertEquals(SubscriptionStatus.ACTIVE, records.get(item.id)!!.status)
    }
    @Test fun unreadableOldImageCannotBlockLaterCompleteTextOrBeChosenAsNewCover() = runBlocking {
        val session = chats.newConversation(); val photo = image(session.id)
        val broken = make(SubscriptionInterpreter { received = it; SubscriptionInterpretation.Propose(values()) },
            imageData = { throw SubscriptionAnalysisException(SubscriptionAnalysisFailure.IMAGE) })
        val first = send(session.id, "", listOf(photo.id))
        assertEquals(SubscriptionAnalysisFailure.IMAGE, (runCatching { broken.analyze(session.id, first) }.exceptionOrNull() as SubscriptionAnalysisException).failure)
        val second = send(session.id, "云盘会员，每月25元，自动续费，下次10月31日扣费")
        broken.analyze(session.id, second)
        assertTrue(received!!.images.isEmpty())
        assertTrue(received!!.historyTrimmed)
        val result = broken.confirm(session.id, proposal(session.id).id)
        assertNull(result.coverAssetId)
        assertNotNull(media.fileFor(photo.id))
    }
    @Test fun explicitRenewalIsConfirmedAsANewTermWhileDateCorrectionRemainsAnEdit() = runBlocking {
        val session = chats.newConversation()
        val initial = values().copy(autoRenew = false, nextBillingDate = null, validUntil = date)
        val item = records.create("existing", initial, session.id)
        output = SubscriptionInterpretation.Propose(initial.copy(validUntil = LocalDate.of(2026, 11, 30)))
        coordinator.analyze(session.id, send(session.id, "没有续期，只是把日期更正为11月30日"))
        assertEquals(SubscriptionOperationKind.UPDATE, proposal(session.id).kind)
        coordinator.analyze(session.id, send(session.id, "我已续期，新一期有效期到11月30日"))
        assertEquals(SubscriptionOperationKind.RENEW_TERM, proposal(session.id).kind)
        coordinator.confirm(session.id, proposal(session.id).id)
        assertEquals(LocalDate.of(2026, 11, 30), records.get(item.id)!!.validUntil)
        assertTrue(records.payments(item.id).isEmpty())
    }
    @Test fun chattingAboutCancellationOfNextMonthOrNextYearUsesCancelledStateAndOneConfirmationReceipt() = runBlocking {
        for (unit in listOf(SubscriptionCycleUnit.MONTH, SubscriptionCycleUnit.YEAR)) {
            val session = chats.newConversation()
            val next = if (unit == SubscriptionCycleUnit.YEAR) date.plusYears(1) else date
            val original = values().copy(cycleUnit = unit, anchorDate = next, nextBillingDate = next)
            val item = records.create("cancel-$unit", original, session.id)
            output = SubscriptionInterpretation.Propose(original.copy(autoRenew = false, validUntil = next.minusDays(1)))
            coordinator.analyze(session.id, send(session.id, "我已取消${if (unit == SubscriptionCycleUnit.YEAR) "下一年" else "下个月"}的自动续费，有效期至${next.minusDays(1)}"))
            val operation = proposal(session.id)
            assertEquals(SubscriptionStatus.CANCEL_AT_PERIOD_END, SubscriptionValues.decode(JSONObject(operation.payloadJson)).status)
            coordinator.confirm(session.id, operation.id); coordinator.confirm(session.id, operation.id)
            val saved = records.get(item.id)!!
            assertEquals(SubscriptionStatus.CANCEL_AT_PERIOD_END, saved.status)
            assertEquals(next.minusDays(1), saved.validUntil)
            assertFalse(saved.autoRenew)
            assertTrue(records.payments(item.id).isEmpty())
            assertEquals(2, records.history(item.id).size)
        }
    }

}
