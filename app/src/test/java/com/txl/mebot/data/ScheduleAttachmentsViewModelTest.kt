package com.txl.mebot.data

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.schedule.ScheduleDraft
import com.txl.mebot.ui.schedule.AttachmentFailure
import com.txl.mebot.ui.schedule.ScheduleAttachmentsViewModel
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MebotApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ScheduleAttachmentsViewModelTest {
    private lateinit var app: MebotApplication
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var model: ScheduleAttachmentsViewModel
    private lateinit var session: String

    @Before fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        // Robolectric 每个用例更换 filesDir，不能沿用 FileProvider 上一用例的静态路径缓存。
        ReflectionHelpers.getStaticField<MutableMap<String, Any>>(FileProvider::class.java, "sCache").clear()
        session = app.container.conversations.openCreation().sessionId
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        attach(session)
    }
    @After fun tearDown() {
        activity.pause().stop().destroy()
        app.container.database.close(); app.deleteDatabase(MebotDatabase.FILE_NAME)
        File(app.filesDir, "media").deleteRecursively()
    }
    private fun attach(id: String) {
        model = ViewModelProvider(activity.get())[ScheduleAttachmentsViewModel::class.java]
        model.bind(id)
        await { !model.state.value.loading }
    }
    private fun source(name: String, bytes: ByteArray): Uri {
        val file = File(app.filesDir, "media/originals/picker-source/$name").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        return FileProvider.getUriForFile(app, "${app.packageName}.attachments", file, name)
    }
    private fun pick(uri: Uri, image: Boolean = false) {
        assertTrue(model.preparePicker()); model.picked(listOf(uri), image)
        await { !model.state.value.isImporting }
    }

    private fun captureBytes(uri: Uri) {
        val image = android.graphics.Bitmap.createBitmap(24, 16, android.graphics.Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.GREEN)
        app.contentResolver.openOutputStream(uri)!!.use { image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        image.recycle()
    }

    @Test fun completedCameraPhotoIsCopiedToPersistentMediaAndStagingIsRemoved() = runBlocking {
        val uri = requireNotNull(model.prepareCamera(true))
        captureBytes(uri)
        model.cameraResult(true)
        await { !model.state.value.isImporting && model.state.value.items.size == 1 }
        val item = model.state.value.items.single()
        assertTrue(item.isImage)
        assertTrue(item.link.pendingSend)
        assertTrue(requireNotNull(app.container.media.fileFor(item.asset.id)).exists())
        assertTrue(File(app.filesDir, "media/originals/capture-staging").listFiles().orEmpty().isEmpty())
    }

    @Test fun cancelledCameraDoesNotCreateAnAttachmentOrLeaveItsStagingFile() = runBlocking {
        requireNotNull(model.prepareCamera(true))
        model.cameraResult(false)
        await { File(app.filesDir, "media/originals/capture-staging").listFiles().orEmpty().isEmpty() }
        assertTrue(model.state.value.items.isEmpty())
        assertTrue(app.container.schedules.recentMessages(session).isEmpty())
        assertTrue(model.preparePicker())
        model.picked(emptyList(), true)
    }

    @Test fun cameraResultAfterClearingDraftCannotAttachToTheReplacementConversation() = runBlocking {
        val uri = requireNotNull(model.prepareCamera(true))
        captureBytes(uri)
        val fresh = app.container.conversations.clearCreation().sessionId
        model.bind(fresh)
        await { !model.state.value.loading }
        model.cameraResult(true)
        await { !model.state.value.isImporting }
        assertTrue(app.container.scheduleAttachments.observe(fresh).first().isEmpty())
        assertNull(app.container.database.scheduleDao().session(session))
        assertTrue(File(app.filesDir, "media/originals/capture-staging").listFiles().orEmpty().isEmpty())
    }

    @Test fun sentFileCanStillOpenAfterTaskAttachmentRemovalButCannotOpenFromAnotherSession() = runBlocking {
        pick(source("历史资料.txt", "original contents".toByteArray()))
        await { model.state.value.items.size == 1 }
        val item = model.state.value.items.single()
        val draft = app.container.conversations.openConversation(session)
        app.container.conversations.sendUserMessage(session, "history-message", "明天使用资料", draft.revision)
        model.remove(item.asset.id)
        await { model.state.value.items.isEmpty() && model.state.value.busyAssetId == null }
        val intent = requireNotNull(model.messageFileIntent("history-message", item.asset.id))
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        assertEquals("original contents", app.contentResolver.openInputStream(requireNotNull(intent.data))!!.bufferedReader().use { it.readText() })
        app.contentResolver.query(requireNotNull(intent.data), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals("历史资料.txt", it.getString(0))
        }
        val other = app.container.schedules.openSession().id
        model.bind(other); await { !model.state.value.loading }
        assertNull(model.messageFileIntent("history-message", item.asset.id))
    }

    @Test fun pickerTargetSurvivesRecreationAndCannotBeRedirectedToAnotherTask() = runBlocking {
        val schedules = app.container.schedules
        schedules.createTask(session, "a", ScheduleDraft("原任务", date = LocalDate.now()))
        val other = schedules.openSession().id
        schedules.createTask(other, "b", ScheduleDraft("另一任务", date = LocalDate.now()))
        val uri = source("原任务.txt", "local bytes".toByteArray())
        assertTrue(model.preparePicker())
        val saved = Bundle(); activity.pause().saveInstanceState(saved).stop().destroy()
        activity = Robolectric.buildActivity(ComponentActivity::class.java).create(saved).start().resume().visible()
        attach(other)
        model.picked(listOf(uri), false)
        await { !model.state.value.isImporting }
        assertEquals(1, app.container.scheduleAttachments.observe(session).first().size)
        assertTrue(app.container.scheduleAttachments.observe(other).first().isEmpty())
        assertTrue(schedules.recentMessages(session).isEmpty())
        assertTrue(schedules.recentMessages(other).isEmpty())
    }

    @Test fun cancelledOrRepeatedSelectionDoesNotCreateMessagesOrDuplicateAttachments() = runBlocking {
        assertTrue(model.preparePicker()); model.picked(emptyList(), false)
        assertFalse(model.state.value.isImporting); assertTrue(model.state.value.items.isEmpty())
        val uri = source("资料.txt", "同一个文件".toByteArray())
        assertTrue(model.preparePicker()); model.picked(listOf(uri, uri), false)
        await { !model.state.value.isImporting && model.state.value.items.size == 1 }
        pick(uri)
        assertEquals(1, model.state.value.items.size)
        assertTrue(app.container.schedules.recentMessages(session).isEmpty())
    }

    @Test fun fileOpeningUsesCopiedBytesNamedContentUriAndReadOnlyPermission() = runBlocking {
        val bytes = "本地文档".toByteArray()
        pick(source("文档.txt", bytes))
        await { model.state.value.items.size == 1 }
        val item = model.state.value.items.single()
        val intent = requireNotNull(model.fileIntent(item))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content", intent.data?.scheme)
        assertEquals("${app.packageName}.attachments", intent.data?.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertTrue(requireNotNull(app.contentResolver.openInputStream(requireNotNull(intent.data))).use { it.readBytes() }.contentEquals(bytes))
        app.contentResolver.query(requireNotNull(intent.data), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals("文档.txt", it.getString(0))
        }
        val privateFile = File(app.filesDir, "private-data.txt").apply { writeText("test") }
        assertTrue(runCatching { FileProvider.getUriForFile(app, "${app.packageName}.attachments", privateFile) }.isFailure)
        privateFile.delete()
        Unit
    }

    @Test fun pictureBecomesCoverAndRemovalClearsBothTheCoverAndItsLocalCopy() = runBlocking {
        val bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jD1cAAAAASUVORK5CYII=")
        val task = app.container.schedules.createTask(session, "create", ScheduleDraft("有图片", date = LocalDate.now()))
        pick(source("图片.png", bytes), true)
        await { model.state.value.items.size == 1 && model.state.value.items.single().link.isCover }
        val asset = model.state.value.items.single().asset
        assertEquals(asset.id, app.container.schedules.taskForSession(session)?.coverAssetId)
        val file = requireNotNull(app.container.media.fileFor(asset.id))
        model.remove(asset.id)
        await { model.state.value.busyAssetId == null && model.state.value.items.isEmpty() }
        assertFalse(file.exists())
        assertEquals(task.id, app.container.schedules.taskForSession(session)?.id)
        assertNull(app.container.schedules.taskForSession(session)?.coverAssetId)
    }

    @Test fun missingFileReportsFailureWithoutDeletingTheTaskOrOtherAttachments() = runBlocking {
        app.container.schedules.createTask(session, "create", ScheduleDraft("日程", date = LocalDate.now()))
        pick(source("资料.txt", "text".toByteArray()))
        await { model.state.value.items.size == 1 }
        val item = model.state.value.items.single()
        requireNotNull(app.container.media.fileFor(item.asset.id)).delete()
        assertNull(model.fileIntent(item))
        assertEquals(AttachmentFailure.MISSING, model.state.value.failure)
        assertNotNull(app.container.schedules.taskForSession(session))
        assertEquals(1, model.state.value.items.size)
    }

    @Test fun previewMutationsTargetTheOriginalSessionAndCoverFallsBackAfterRemoval() = runBlocking {
        val whiteBytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jD1cAAAAASUVORK5CYII=")
        val white = source("white.png", whiteBytes)
        val green = source("green.jpg", byteArrayOf())
        captureBytes(green)
        app.container.schedules.createTask(session, "original", ScheduleDraft("原任务"))
        pick(white, true); pick(green, true)
        await { model.state.value.items.size == 2 }
        val first = model.state.value.items.first().asset.id
        val second = model.state.value.items.last().asset.id
        val other = app.container.schedules.openSession().id
        app.container.schedules.createTask(other, "other", ScheduleDraft("另一任务"))
        model.bind(other); await { !model.state.value.loading }
        pick(white, true); pick(green, true)
        await { model.state.value.items.size == 2 }
        assertEquals(first, model.state.value.items.first().asset.id)
        assertEquals(second, model.state.value.items.last().asset.id)
        // 旧预览的按钮结果不能误改另一项也引用相同图片的任务。
        model.chooseCover(second, session)
        model.remove(first, session)
        model.discardPending(second, session)
        assertNull(model.state.value.busyAssetId)
        assertEquals(2, app.container.scheduleAttachments.observe(other).first().size)
        assertEquals(first, app.container.schedules.taskForSession(other)?.coverAssetId)
        model.chooseCover(second, other)
        await { model.state.value.busyAssetId == null && model.state.value.items.last().link.isCover }
        model.remove(second, other)
        await { model.state.value.busyAssetId == null && model.state.value.items.size == 1 }
        assertEquals(first, app.container.schedules.taskForSession(other)?.coverAssetId)
        model.remove(first, other)
        await { model.state.value.busyAssetId == null && model.state.value.items.isEmpty() }
        assertNull(app.container.schedules.taskForSession(other)?.coverAssetId)
        assertEquals(2, app.container.scheduleAttachments.observe(session).first().size)
        assertTrue(requireNotNull(app.container.media.fileFor(first)).isFile)
        assertTrue(requireNotNull(app.container.media.fileFor(second)).isFile)
    }

    @Test fun latePickerResultForDeletedTaskCannotRecreateTheOldSessionOrAttachToNewTask() = runBlocking {
        val schedules = app.container.schedules
        val task = schedules.createTask(session, "a", ScheduleDraft("原任务", date = LocalDate.now()))
        val uri = source("迟到.txt", "old session".toByteArray())
        assertTrue(model.preparePicker())
        schedules.deleteTask(session, "delete", task.revision)
        val other = app.container.conversations.openCreation().sessionId
        model.bind(other); await { !model.state.value.loading }
        model.picked(listOf(uri), false)
        await { !model.state.value.isImporting }
        assertNull(app.container.database.scheduleDao().session(session))
        assertTrue(app.container.scheduleAttachments.observe(other).first().isEmpty())
        assertTrue(model.state.value.items.isEmpty())
        app.container.media.cleanPendingDeletions()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "等待附件状态超时：${model.state.value}" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5)
        }
    }
}
