package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.*
import com.txl.mebot.data.media.LocalMediaStore
import com.txl.mebot.data.models.*
import com.txl.mebot.data.schedule.*
import com.txl.mebot.ui.schedule.asEntry
import com.txl.mebot.ui.schedule.loadSchedulePhoto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** 真实 SQLite / 图片复制验证，不调用真实模型，也不操作手机。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScheduleCoverStorageTest {
    private lateinit var context: Context
    private lateinit var db: MebotDatabase
    private lateinit var schedules: ScheduleRepository
    private lateinit var conversations: ScheduleConversationRepository
    private lateinit var attachments: ScheduleAttachmentRepository
    private lateinit var media: LocalMediaStore
    private lateinit var covers: ScheduleCoverRepository
    private lateinit var session: String
    private val clock = Clock.fixed(Instant.parse("2026-10-23T08:00:00Z"), ZoneOffset.UTC)
    private val day = LocalDate.of(2026, 10, 23)
    private val queued = mutableListOf<ScheduleCoverJobEntity>()
    private var generations = 0
    private var downloads = 0
    private var lastPrompt = ""
    private var generate: suspend (String) -> GeneratedCoverLink = { GeneratedCoverLink(URL, "z-image-turbo") }
    private var download: suspend (String) -> ByteArray = { image(Color.BLUE) }

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        db = MebotDatabase.create(context)
        connect()
        session = conversations.openCreation().sessionId
    }
    private fun connect() {
        media = LocalMediaStore(context, db, clock)
        schedules = ScheduleRepository(db, clock) { ZoneOffset.UTC }
        conversations = ScheduleConversationRepository(db, schedules, clock)
        attachments = ScheduleAttachmentRepository(context, db, media, clock)
        covers = ScheduleCoverRepository(db, media, CoverImageGenerator { prompt -> generations++; lastPrompt = prompt; generate(prompt) },
            CoverImageDownloader { url -> downloads++; download(url) }, clock, enqueue = { queued += it })
    }
    private fun reopen() { db.close(); db = MebotDatabase.create(context); connect() }
    @After fun tearDown() { db.close(); context.deleteDatabase(MebotDatabase.FILE_NAME); File(context.filesDir, "media").deleteRecursively() }
    private fun image(color: Int, size: Int = 768) = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color); compress(Bitmap.CompressFormat.PNG, 100, output); recycle()
        }
        output.toByteArray()
    }
    private suspend fun attachImage() = media.importDocument({ ByteArrayInputStream(image(Color.RED, 32)) }, "用户私有图片.png", "image/png", true) {
        attachments.attach(session, it)
    }
    private suspend fun save(draft: ScheduleDraft = ScheduleDraft("采购蔬菜", date = day.plusDays(1))): ScheduleTaskEntity {
        val task = schedules.createTask(session, "create:$session", draft)
        covers.queueForTask(task)
        return task
    }
    private suspend fun job(task: ScheduleTaskEntity) = requireNotNull(db.scheduleCoverDao().job(task.id))

    @Test fun naturalLanguageSaveCommitsTaskAndReplyBeforeSchedulingOrGeneratingTheCover() = runBlocking {
        val draft = conversations.openConversation(session)
        conversations.sendUserMessage(session, "user", "明天买土豆", draft.revision)
        val coordinator = ScheduleCreationCoordinator(db, schedules, conversations,
            ScheduleTextInterpreter { ScheduleInterpretation.Save(ScheduleDraft("采购蔬菜", "土豆", day.plusDays(1))) }, clock,
            zone = { ZoneOffset.UTC }, covers = covers)
        coordinator.process(session, "user")
        val task = requireNotNull(schedules.taskForSession(session))
        assertEquals(ScheduleCoverState.QUEUED, job(task).state)
        assertEquals(1, queued.size)
        assertEquals(0, generations)
        assertEquals(0, downloads)
        assertNull(task.coverAssetId)
        assertEquals(MessageRole.ASSISTANT, schedules.recentMessages(session).last().role)
        assertEquals(1, schedules.observeDate(day.plusDays(1)).first().size)
        coordinator.process(session, "user")
        assertEquals(1, queued.size)
        assertFalse(covers.execute(task.id, job(task).attemptId))
        val saved = requireNotNull(schedules.taskForSession(session))
        val asset = requireNotNull(db.mediaDao().asset(requireNotNull(saved.coverAssetId)))
        assertEquals(MediaSource.AI_GENERATED, asset.source)
        assertEquals(768, asset.width); assertEquals(768, asset.height)
        assertEquals("z-image-turbo", asset.generator)
        assertEquals(lastPrompt, asset.generationPrompt)
        assertTrue(requireNotNull(media.fileFor(asset.id)).isFile)
        assertEquals(ScheduleCoverState.READY, job(task).state)
        assertNull(job(task).remoteUrl)
        assertEquals(task.revision, saved.revision)
        val card = schedules.observeDisplayDate(day.plusDays(1)).first().single().asEntry()
        assertEquals(0, card.attachmentCount)
        assertNotNull(loadSchedulePhoto(context, card))
        assertTrue(attachments.observe(session).first().isEmpty())
        reopen()
        assertEquals(asset.id, schedules.taskForSession(session)?.coverAssetId)
        assertNotNull(loadSchedulePhoto(context, schedules.observeDisplayDate(day.plusDays(1)).first().single().asEntry()))
    }

    @Test fun userImageSkipsGenerationWhileFilesAloneDoNotAndNeitherContentNorNamesEnterThePrompt() = runBlocking {
        attachImage()
        val withImage = save()
        assertNull(db.scheduleCoverDao().job(withImage.id))
        assertEquals(0, generations)
        session = conversations.openCreation().sessionId
        media.importDocument({ ByteArrayInputStream("PRIVATE_FILE_CONTENT".toByteArray()) }, "秘密文件.txt", "text/plain") {
            attachments.attach(session, it)
        }
        val withFile = save(ScheduleDraft("整理资料", "PRIVATE_DETAIL", day))
        covers.execute(withFile.id, job(withFile).attemptId)
        assertEquals(1, generations)
        assertTrue(lastPrompt.contains("整理资料"))
        assertFalse(lastPrompt.contains("PRIVATE")); assertFalse(lastPrompt.contains("秘密文件")); assertFalse(lastPrompt.contains("用户私有"))
        assertEquals(1, attachments.observe(session).first().size)
    }

    @Test fun legacyQueuedPromptIsRefreshedToAContinuousFullImageBeforeGeneration() = runBlocking {
        val task = save()
        val queuedJob = job(task)
        db.scheduleCoverDao().update(queuedJob.copy(prompt = "主体位于画面上半部，下半部留出柔和背景供界面叠字"))
        reopen()
        covers.execute(task.id, queuedJob.attemptId)
        assertTrue(lastPrompt.contains("单一连续"))
        assertTrue(lastPrompt.contains("全画面清晰"))
        assertTrue(lastPrompt.contains("无上下分区"))
        assertFalse(lastPrompt.contains("主体位于画面上半部"))
        assertFalse(lastPrompt.contains("供界面叠字"))
        val asset = requireNotNull(db.mediaDao().asset(requireNotNull(schedules.taskForSession(session)?.coverAssetId)))
        assertEquals(lastPrompt, asset.generationPrompt)
        assertEquals(lastPrompt, job(task).prompt)
    }

    @Test fun explicitRegenerationPreservesOldCoverUntilSuccessAndUpdatesEveryRecurringCardOnce() = runBlocking {
        val task = save(ScheduleDraft("每天喂猫", date = day, recurrence = RecurrenceRule(RecurrencePattern.DAILY)))
        schedules.materializeRange(day, day.plusDays(2))
        covers.execute(task.id, job(task).attemptId)
        val original = requireNotNull(schedules.taskForSession(session))
        val oldId = requireNotNull(original.coverAssetId)
        val oldFile = requireNotNull(media.fileFor(oldId))
        val firstAttempt = job(task).attemptId
        covers.regenerate(session)
        val next = job(task)
        assertNotEquals(firstAttempt, next.attemptId)
        assertEquals(ScheduleCoverState.QUEUED, next.state)
        assertEquals(oldId, schedules.taskForSession(session)?.coverAssetId)
        assertTrue(oldFile.isFile)
        assertEquals(1, generations)
        val queuedCount = queued.size
        covers.regenerate(session)
        assertEquals(next.attemptId, job(task).attemptId)
        assertEquals(queuedCount, queued.size)
        download = { image(Color.GREEN) }
        covers.execute(task.id, next.attemptId)
        val updated = requireNotNull(schedules.taskForSession(session))
        assertNotEquals(oldId, updated.coverAssetId)
        assertEquals(original.title, updated.title)
        assertEquals(original.revision, updated.revision)
        val asset = requireNotNull(db.mediaDao().asset(requireNotNull(updated.coverAssetId)))
        for (date in listOf(day, day.plusDays(1), day.plusDays(2), day.plusDays(3))) {
            schedules.materializeRange(date, date)
            val card = schedules.observeDisplayDate(date).first().single().asEntry()
            assertEquals(asset.relativePath, card.coverRelativePath)
            assertEquals(MediaSource.AI_GENERATED, card.coverSource)
        }
        assertFalse(oldFile.exists())
        assertEquals(2, generations)
        covers.execute(task.id, next.attemptId)
        assertEquals(2, generations)
    }

    @Test fun regenerationDoesNotRemoveAnOldCoverStillUsedByAnotherTask() = runBlocking {
        val task = save()
        covers.execute(task.id, job(task).attemptId)
        val oldId = requireNotNull(schedules.taskForSession(session)?.coverAssetId)
        val oldFile = requireNotNull(media.fileFor(oldId))
        val other = schedules.openSession()
        schedules.createTask(other.id, "shared-cover", ScheduleDraft("共用图片", date = day, coverAssetId = oldId))
        covers.regenerate(session)
        download = { image(Color.GREEN) }
        covers.execute(task.id, job(task).attemptId)
        assertNotEquals(oldId, schedules.taskForSession(session)?.coverAssetId)
        assertEquals(oldId, schedules.taskForSession(other.id)?.coverAssetId)
        assertTrue(oldFile.isFile)
        assertNotNull(db.mediaDao().asset(oldId))
    }

    @Test fun failedReplacementKeepsOldCoverAndRetriesOnlyTheDownloadWhenItsUrlIsValid() = runBlocking {
        val task = save()
        covers.execute(task.id, job(task).attemptId)
        val oldId = requireNotNull(schedules.taskForSession(session)?.coverAssetId)
        val oldFile = requireNotNull(media.fileFor(oldId))
        covers.regenerate(session)
        download = { throw IOException("offline") }
        covers.execute(task.id, job(task).attemptId, 2)
        assertEquals(ScheduleCoverState.FAILED, job(task).state)
        assertEquals(oldId, schedules.taskForSession(session)?.coverAssetId)
        assertTrue(oldFile.isFile)
        covers.retry(session)
        assertEquals(ScheduleCoverState.DOWNLOADING, job(task).state)
        generate = { error("已有图片 URL，不应再生成") }
        download = { image(Color.GREEN) }
        covers.execute(task.id, job(task).attemptId)
        assertEquals(2, generations)
        assertNotEquals(oldId, schedules.taskForSession(session)?.coverAssetId)
    }

    @Test fun userImageUploadedDuringRegenerationStillWinsOverTheLateReplacement() = runBlocking {
        val task = save()
        covers.execute(task.id, job(task).attemptId)
        covers.regenerate(session)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        download = { started.complete(Unit); release.await(); image(Color.GREEN) }
        val attempt = job(task).attemptId
        val work = async { covers.execute(task.id, attempt) }
        started.await()
        val user = attachImage()
        release.complete(Unit); work.await()
        assertEquals(user.id, schedules.taskForSession(session)?.coverAssetId)
        assertEquals(ScheduleCoverState.SKIPPED, job(task).state)
        assertTrue(requireNotNull(media.fileFor(user.id)).isFile)
        covers.regenerate(session)
        assertEquals(ScheduleCoverState.SKIPPED, job(task).state)
        assertEquals(2, generations)
    }

    @Test fun deletingATaskDuringRegenerationDoesNotRestoreItsOldOrNewCover() = runBlocking {
        val task = save()
        covers.execute(task.id, job(task).attemptId)
        covers.regenerate(session)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        download = { started.complete(Unit); release.await(); image(Color.GREEN) }
        val attempt = job(task).attemptId
        val work = async { covers.execute(task.id, attempt) }
        started.await()
        schedules.deleteTask(session, "delete-replacement", requireNotNull(schedules.taskForSession(session)).revision)
        release.complete(Unit); work.await()
        assertNull(schedules.taskForSession(session))
        assertNull(db.scheduleCoverDao().job(task.id))
        assertTrue(File(context.filesDir, "media/originals").walkTopDown().none { it.isFile })
    }

    @Test fun failedTaskTransactionCannotLeaveOrScheduleACoverJob() = runBlocking {
        val draft = conversations.openConversation(session)
        conversations.sendUserMessage(session, "user", "明天买土豆", draft.revision)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_reply BEFORE INSERT ON schedule_messages
            WHEN NEW.role = 'ASSISTANT' BEGIN SELECT RAISE(ABORT, 'test write failure'); END""")
        val coordinator = ScheduleCreationCoordinator(db, schedules, conversations,
            ScheduleTextInterpreter { ScheduleInterpretation.Save(ScheduleDraft("采购蔬菜", date = day.plusDays(1))) }, clock,
            covers = covers, zone = { ZoneOffset.UTC })
        assertTrue(runCatching { coordinator.process(session, "user") }.isFailure)
        assertNull(schedules.taskForSession(session))
        assertTrue(db.scheduleCoverDao().unfinished().isEmpty())
        assertTrue(queued.isEmpty()); assertEquals(0, generations)
    }

    @Test fun failedCoverAssignmentRollsBackAllReferencesAndCleansTheDownloadedOriginal() = runBlocking {
        val task = save()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_cover BEFORE UPDATE OF coverAssetId ON schedule_occurrences
            BEGIN SELECT RAISE(ABORT, 'test write failure'); END""")
        assertFalse(covers.execute(task.id, job(task).attemptId))
        assertNull(schedules.taskForSession(session)?.coverAssetId)
        assertNull(schedules.observeDate(day.plusDays(1)).first().single().coverAssetId)
        assertEquals(ScheduleCoverState.FAILED, job(task).state)
        assertEquals(ScheduleCoverFailure.LOCAL_SAVE, job(task).failure)
        assertTrue(File(context.filesDir, "media/originals").walkTopDown().none { it.isFile })
    }

    @Test fun userImageAddedDuringGenerationWinsBeforeAnyGeneratedImageDownload() = runBlocking {
        val task = save()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        generate = { started.complete(Unit); release.await(); GeneratedCoverLink(URL, "z-image-turbo") }
        val work = async { covers.execute(task.id, job(task).attemptId) }
        started.await()
        val userImage = attachImage()
        release.complete(Unit); work.await()
        assertEquals(userImage.id, schedules.taskForSession(session)?.coverAssetId)
        assertEquals(ScheduleCoverState.SKIPPED, job(task).state)
        assertEquals(0, downloads)
    }

    @Test fun lateDownloadCannotOverwriteAUserCoverEvenIfThatUserImageWasRemovedAgain() = runBlocking {
        val task = save()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        download = { started.complete(Unit); release.await(); image(Color.BLUE) }
        val work = async { covers.execute(task.id, job(task).attemptId) }
        started.await()
        val userImage = attachImage()
        attachments.remove(session, userImage.id)
        release.complete(Unit); work.await()
        assertNull(schedules.taskForSession(session)?.coverAssetId)
        assertEquals(ScheduleCoverState.SKIPPED, job(task).state)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM media_assets").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        assertTrue(File(context.filesDir, "media/originals").walkTopDown().none { it.isFile })
    }

    @Test fun deletingTaskDuringDownloadRemovesItsJobAndDiscardsTheLateLocalImage() = runBlocking {
        val task = save()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        download = { started.complete(Unit); release.await(); image(Color.BLUE) }
        val attempt = job(task).attemptId
        val work = async { covers.execute(task.id, attempt) }
        started.await()
        schedules.deleteTask(session, "delete", task.revision)
        assertNull(db.scheduleCoverDao().job(task.id))
        release.complete(Unit); work.await()
        reopen()
        assertNull(schedules.taskForSession(session)); assertNull(db.scheduleCoverDao().job(task.id))
        assertFalse(covers.execute(task.id, attempt))
        assertTrue(File(context.filesDir, "media/originals").walkTopDown().none { it.isFile })
    }

    @Test fun allRecurringDatesShareOneLocalCoverAndTimeChangesDoNotRegenerateIt() = runBlocking {
        val task = save(ScheduleDraft("喂猫", "猫粮", day, LocalTime.of(18, 0), RecurrenceRule(RecurrencePattern.DAILY), listOf(10, 0)))
        schedules.materializeRange(day, day.plusDays(2))
        val occurrence = schedules.observeDate(day.plusDays(1)).first().single()
        schedules.setCompleted(session, occurrence.id, true, "complete", occurrence.revision)
        val before = schedules.history(day, day.plusDays(2)).map { it.copy(coverAssetId = null) }
        val attempt = job(task).attemptId
        covers.execute(task.id, attempt)
        val assetId = requireNotNull(schedules.taskForSession(session)?.coverAssetId)
        assertEquals(before, schedules.history(day, day.plusDays(2)).map { it.copy(coverAssetId = null) })
        assertTrue(schedules.history(day, day.plusDays(2)).all { it.coverAssetId == assetId })
        val base = schedules.draftForDate(session, day)
        schedules.updateTask(session, "time-only", base.copy(time = LocalTime.of(19, 0)), task.revision, setOf("time"))
        schedules.materializeRange(day.plusDays(20), day.plusDays(20))
        assertEquals(assetId, schedules.observeDate(day.plusDays(20)).first().single().coverAssetId)
        assertEquals(listOf(10, 0), schedules.reminderMinutesForSession(session))
        covers.queueForTask(requireNotNull(schedules.taskForSession(session)))
        assertFalse(covers.execute(task.id, attempt))
        assertEquals(1, generations); assertEquals(1, downloads)
        val localFile = requireNotNull(media.fileFor(assetId))
        schedules.deleteTask(session, "delete", requireNotNull(schedules.taskForSession(session)).revision)
        media.cleanPendingDeletions()
        assertFalse(localFile.exists()); assertNull(db.scheduleCoverDao().job(task.id))
    }

    @Test fun failedGenerationKeepsTaskAndOnlyExplicitRetryCreatesAnotherPaidAttempt() = runBlocking {
        val task = save()
        generate = { throw ModelImageException(ConnectionResultKind.INVALID_KEY) }
        val first = job(task).attemptId
        assertFalse(covers.execute(task.id, first))
        assertEquals(ScheduleCoverState.FAILED, job(task).state)
        assertEquals(ScheduleCoverFailure.AUTH, job(task).failure)
        assertEquals(1, schedules.observeDate(day.plusDays(1)).first().size)
        covers.resumePending()
        assertTrue(queued.isEmpty())
        assertFalse(covers.execute(task.id, first)); assertEquals(1, generations)
        generate = { GeneratedCoverLink(URL, "z-image-turbo") }
        covers.retry(session)
        val retry = job(task).attemptId
        assertNotEquals(first, retry)
        assertEquals(1, queued.size)
        assertFalse(covers.execute(task.id, first))
        assertFalse(covers.execute(task.id, retry))
        assertEquals(2, generations); assertNotNull(schedules.taskForSession(session)?.coverAssetId)
    }

    @Test fun interruptedGenerationAcrossProcessRestartNeverAutomaticallyPostsAgain() = runBlocking {
        val task = save()
        val initial = job(task)
        db.scheduleCoverDao().update(initial.copy(state = ScheduleCoverState.GENERATING))
        reopen()
        covers.resumePending()
        assertEquals(1, queued.size)
        assertFalse(covers.execute(task.id, initial.attemptId))
        assertEquals(0, generations)
        assertEquals(ScheduleCoverFailure.INTERRUPTED, job(task).failure)
        covers.retry(session)
        assertFalse(covers.execute(task.id, job(task).attemptId))
        assertEquals(1, generations)
    }

    @Test fun cancellationDuringGenerationIsPersistedButDownloadCancellationKeepsTheReusableUrl() = runBlocking {
        val task = save()
        val started = CompletableDeferred<Unit>()
        generate = { started.complete(Unit); awaitCancellation() }
        val work = async { covers.execute(task.id, job(task).attemptId) }
        started.await(); work.cancelAndJoin()
        assertEquals(ScheduleCoverFailure.INTERRUPTED, job(task).failure)
        generate = { GeneratedCoverLink(URL, "z-image-turbo") }
        covers.retry(session)
        val downloadStarted = CompletableDeferred<Unit>()
        download = { downloadStarted.complete(Unit); awaitCancellation() }
        val next = async { covers.execute(task.id, job(task).attemptId) }
        downloadStarted.await(); next.cancelAndJoin()
        assertEquals(ScheduleCoverState.DOWNLOADING, job(task).state)
        assertEquals(URL, job(task).remoteUrl)
        download = { image(Color.BLUE) }
        covers.execute(task.id, job(task).attemptId)
        assertEquals(2, generations)
        assertEquals(ScheduleCoverState.READY, job(task).state)
    }

    @Test fun downloadFailureAndDatabaseReopenReuseTheExistingUrlWithoutRegeneration() = runBlocking {
        val task = save()
        download = { throw IOException("offline") }
        val attempt = job(task).attemptId
        assertTrue(covers.execute(task.id, attempt))
        assertEquals(ScheduleCoverState.DOWNLOADING, job(task).state)
        assertEquals(URL, job(task).remoteUrl)
        reopen()
        generate = { error("不应重复调用生图") }
        download = { image(Color.BLUE) }
        assertFalse(covers.execute(task.id, attempt, 1))
        assertEquals(1, generations)
        assertEquals(2, downloads)
        assertEquals(ScheduleCoverState.READY, job(task).state)
    }

    @Test fun exhaustedDownloadRetriesAreManualAndExpiredUrlsRequireAnExplicitNewGeneration() = runBlocking {
        val task = save()
        download = { throw IOException("offline") }
        assertFalse(covers.execute(task.id, job(task).attemptId, 2))
        assertEquals(ScheduleCoverState.FAILED, job(task).state)
        covers.retry(session)
        assertEquals(ScheduleCoverState.DOWNLOADING, job(task).state)
        assertFalse(covers.execute(task.id, job(task).attemptId, 2))
        assertEquals(1, generations)
        val old = job(task)
        db.scheduleCoverDao().update(old.copy(generatedAt = clock.millis() - 24L * 60 * 60 * 1000))
        covers.retry(session)
        assertEquals(ScheduleCoverState.QUEUED, job(task).state)
        assertNull(job(task).remoteUrl)
        download = { image(Color.BLUE) }
        covers.execute(task.id, job(task).attemptId)
        assertEquals(2, generations)
    }

    @Test fun invalidDownloadedImageCannotBecomeACoverOrLeaveAnOrphanOriginal() = runBlocking {
        for (bytes in listOf("<html>invalid</html>".toByteArray(), image(Color.GREEN, 32))) {
            val task = save()
            download = { bytes }
            covers.execute(task.id, job(task).attemptId)
            assertEquals(ScheduleCoverFailure.INVALID_RESPONSE, job(task).failure)
            assertNull(job(task).remoteUrl)
            assertNull(schedules.taskForSession(session)?.coverAssetId)
            assertTrue(File(context.filesDir, "media/originals").walkTopDown().none { it.isFile })
            session = conversations.openCreation().sessionId
        }
    }

    @Test fun actualSchemaFiveMigrationPreservesTasksDraftsAttachmentsAndEncryptedCredentials() = runBlocking {
        db.close(); context.deleteDatabase(MebotDatabase.FILE_NAME)
        val path = "schemas/com.txl.mebot.data.local.MebotDatabase/5.json"
        val schema = JSONObject((File(path).takeIf { it.exists() } ?: File("app/$path")).readText()).getJSONObject("database")
        val file = context.getDatabasePath(MebotDatabase.FILE_NAME).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val tables = schema.getJSONArray("entities")
            for (index in 0 until tables.length()) {
                val table = tables.getJSONObject(index); val name = table.getString("tableName")
                old.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", name))
                table.optJSONArray("indices")?.let { indices -> for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", name)) }
            }
            schema.getJSONArray("setupQueries").let { queries -> for (i in 0 until queries.length()) old.execSQL(queries.getString(i)) }
            old.execSQL("INSERT INTO schedule_sessions VALUES ('legacy',100,200)")
            old.execSQL("INSERT INTO schedule_messages VALUES ('msg','legacy',1,'USER','开会',100,NULL)")
            old.execSQL("INSERT INTO media_assets (id,source,relativePath,originalName,mimeType,byteCount,width,height,createdAt) VALUES ('photo','IMPORTED','media/originals/photo.png','旧图.png','image/png',50,10,10,100)")
            old.execSQL("INSERT INTO schedule_local_attachments VALUES ('legacy','photo','旧图.png',1,100,1)")
            old.execSQL("INSERT INTO schedule_tasks VALUES ('task','legacy','开会','备注',${day.toEpochDay()},NULL,'photo',1,100,200,NULL)")
            old.execSQL("INSERT INTO schedule_task_versions VALUES ('task',1,${day.toEpochDay()},'开会','备注',${day.toEpochDay()},NULL,'photo',100)")
            old.execSQL("INSERT INTO schedule_conversation_drafts VALUES ('legacy','未发草稿',7,200)")
            old.execSQL("INSERT INTO model_settings VALUES (1,'qwen3.8-flash','https://example.com','z-image-turbo','https://example.com',768,X'010203',X'040506',2,100)")
            old.version = 5
        }
        db = MebotDatabase.create(context); connect()
        assertEquals("旧图.png", attachments.observe("legacy").first().single().link.displayName)
        assertEquals("photo", schedules.taskForSession("legacy")?.coverAssetId)
        assertEquals("未发草稿", conversations.openConversation("legacy").inputText)
        assertEquals("开会", schedules.recentMessages("legacy").single().content)
        assertTrue(db.scheduleCoverDao().unfinished().isEmpty())
        db.openHelper.readableDatabase.query("SELECT keyCiphertext,keyIv FROM model_settings WHERE id=1").use {
            assertTrue(it.moveToFirst()); assertTrue(it.getBlob(0).contentEquals(byteArrayOf(1, 2, 3))); assertTrue(it.getBlob(1).contentEquals(byteArrayOf(4, 5, 6)))
        }
    }

    private companion object { const val URL = "https://images.example.com/cover.png?temporary-secret=signature" }
}
