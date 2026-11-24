package com.txl.mebot.data

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.flashnote.FlashnoteAttachmentRepository
import com.txl.mebot.data.flashnote.FlashnoteRepository
import com.txl.mebot.data.local.FlashnoteDraftEntity
import com.txl.mebot.data.local.FlashnoteEntity
import com.txl.mebot.data.local.FlashnoteStatus
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.media.LocalMediaStore
import com.txl.mebot.ui.flashnote.FlashnoteInputSaveState
import com.txl.mebot.ui.flashnote.FlashnoteViewModel
import com.txl.mebot.ui.schedule.AttachmentFailure
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Duration
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
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class FlashnoteAttachmentsViewModelTest {
    private lateinit var app: Application
    private lateinit var database: MebotDatabase
    private lateinit var repository: FlashnoteRepository
    private lateinit var media: LocalMediaStore
    private lateinit var importer: FlashnoteAttachmentRepository
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var model: FlashnoteViewModel

    @Before fun setUp() {
        ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
        ReflectionHelpers.getStaticField<MutableMap<String, Any>>(FileProvider::class.java, "sCache").clear()
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(MebotDatabase.FILE_NAME)
        database = MebotDatabase.create(app)
        connect()
        createModel()
    }
    private fun connect() {
        repository = FlashnoteRepository(database)
        media = LocalMediaStore(app, database)
        importer = FlashnoteAttachmentRepository(app, repository, media)
    }
    private fun createModel(commit: suspend (FlashnoteDraftEntity) -> FlashnoteEntity = repository::save) {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FlashnoteViewModel(app, SavedStateHandle(), repository, commit, importer) as T
        }
        model = ViewModelProvider(activity.get(), factory)[FlashnoteViewModel::class.java]
        model.setVisible(true)
        await { model.state.value.draftLoaded && !model.state.value.loading }
    }
    private fun destroyModel() {
        model.setVisible(false)
        await { !model.state.value.isSaving && !model.state.value.media.busy &&
            model.state.value.inputSaveState != FlashnoteInputSaveState.SAVING && model.state.value.editor?.busy != true }
        activity.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }
    @After fun tearDown() {
        destroyModel()
        database.close()
        app.deleteDatabase(MebotDatabase.FILE_NAME)
        File(app.filesDir, "media").deleteRecursively()
        ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
    }
    private fun image(color: Int = Color.RED) = ByteArrayOutputStream().use {
        Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color); compress(Bitmap.CompressFormat.PNG, 100, it); recycle() }
        it.toByteArray()
    }
    private fun source(name: String, bytes: ByteArray): Uri {
        val file = File(app.filesDir, "media/originals/picker-source/$name").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        return FileProvider.getUriForFile(app, "${app.packageName}.attachments", file, name)
    }
    private fun pick(uris: List<Uri>, imagesOnly: Boolean = true) {
        assertTrue(model.preparePicker())
        model.picked(uris, imagesOnly)
        await { !model.state.value.media.isImporting }
    }
    private fun save() {
        model.save()
        await { !model.state.value.isSaving && model.state.value.draftLoaded && !model.state.value.saveFailed && model.state.value.savedEvent != null }
    }

    @Test fun selectedImagesAndFilesBecomeCardCoverAndOpenUsingCopiedReadOnlyContentUris() = runBlocking {
        val photo = source("照片.png", image())
        val bytes = "需要保留的文件正文".toByteArray()
        val document = source("资料.txt", bytes)
        pick(listOf(document, photo), false)
        assertEquals(2, model.state.value.media.items.size)
        assertTrue(model.state.value.canSave)
        val fileItem = model.state.value.media.items.first()
        val intent = requireNotNull(model.fileIntent(fileItem))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content", intent.data?.scheme)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertArrayEquals(bytes, app.contentResolver.openInputStream(requireNotNull(intent.data))!!.use { it.readBytes() })
        model.changeInput("带图闪念\n还要处理这些附件")
        save()
        await { model.state.value.entries.singleOrNull()?.attachmentCount == 2 }
        val entry = model.state.value.entries.single()
        assertNotNull(entry.coverRelativePath)
        assertTrue(model.state.value.media.items.isEmpty())
        model.openDetail(entry.id)
        await { !model.state.value.detailLoading && model.state.value.detailAttachments.size == 2 }
        assertEquals("带图闪念\n还要处理这些附件", model.state.value.detail?.detail)
        assertEquals(listOf("资料.txt", "照片.png"), model.state.value.detailAttachments.map { it.displayName })
    }

    @Test fun unsentImageAndTextRestoreAfterClosingViewModelAndReopeningDatabase() = runBlocking {
        pick(listOf(source("未提交.png", image())))
        val id = model.state.value.media.items.single().asset.id
        model.changeInput("还没保存的记录\n继续补充")
        destroyModel()
        database.close(); database = MebotDatabase.create(app); connect(); createModel()
        assertEquals("还没保存的记录\n继续补充", model.state.value.input)
        assertEquals(id, model.state.value.media.items.single().asset.id)
        assertTrue(requireNotNull(media.fileFor(id)).isFile)
        assertTrue(repository.notes(FlashnoteStatus.PENDING).isEmpty())
    }

    @Test fun galleryTogglesSelectionAndRemovingTheLastImageDisablesEmptySubmit() = runBlocking {
        val uri = source("重复.png", image())
        model.pickLocal(uri)
        assertFalse(model.state.value.canSave)
        await { !model.state.value.media.busy && model.state.value.media.items.size == 1 }
        assertTrue(model.state.value.canSave)
        val id = model.state.value.media.items.single().asset.id
        val stored = requireNotNull(media.fileFor(id))
        model.pickLocal(uri)
        await { !model.state.value.media.busy && model.state.value.media.items.isEmpty() }
        assertFalse(model.state.value.canSave)
        assertFalse(stored.exists())
        assertTrue(repository.notes(FlashnoteStatus.PENDING).isEmpty())
    }

    @Test fun partialImportFailureKeepsSuccessfulItemsAndInputAndAllowsAnotherSelection() = runBlocking {
        model.changeInput("导入失败也不能丢失输入")
        pick(listOf(source("非图片.txt", "not an image".toByteArray()), source("有效.png", image())), true)
        assertEquals("导入失败也不能丢失输入", model.state.value.input)
        assertEquals(AttachmentFailure.IMPORT, model.state.value.media.failure)
        assertEquals("有效.png", model.state.value.media.items.single().displayName)
        assertTrue(model.state.value.canSave)
        pick(listOf(source("补充.png", image(Color.BLUE))))
        assertNull(model.state.value.media.failure)
        assertEquals(2, model.state.value.media.items.size)
    }

    @Test fun cancelledAndSuccessfulCameraCleanTheirStagingAndPersistTheOriginal() = runBlocking {
        requireNotNull(model.prepareCamera())
        model.cameraResult(false)
        await { File(app.filesDir, "media/originals/flashnote-capture-staging").listFiles().orEmpty().isEmpty() }
        assertTrue(model.state.value.media.items.isEmpty())
        val uri = requireNotNull(model.prepareCamera())
        app.contentResolver.openOutputStream(uri)!!.use { output ->
            Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.GREEN); compress(Bitmap.CompressFormat.JPEG, 90, output); recycle()
            }
        }
        model.cameraResult(true)
        await { !model.state.value.media.isImporting && model.state.value.media.items.size == 1 }
        val item = model.state.value.media.items.single()
        assertTrue(item.isImage)
        assertNotNull(media.fileFor(item.asset.id))
        assertTrue(File(app.filesDir, "media/originals/flashnote-capture-staging").listFiles().orEmpty().isEmpty())
        save()
        await { model.state.value.entries.singleOrNull()?.title == "图片闪念" }
    }

    @Test fun latePickerResultCannotAttachToTheNextDraftOrTheAlreadySavedNote() = runBlocking {
        assertTrue(model.preparePicker())
        model.changeInput("已经保存的上一条")
        save()
        val oldId = requireNotNull(model.state.value.savedEvent).id
        model.picked(listOf(source("迟到.png", image())), true)
        await { !model.state.value.media.isImporting }
        assertEquals(AttachmentFailure.IMPORT, model.state.value.media.failure)
        assertTrue(model.state.value.media.items.isEmpty())
        assertTrue(repository.noteAttachments(oldId).isEmpty())
        assertTrue(repository.draftAttachments(repository.openCreation().key).isEmpty())
    }

    @Test fun failedSaveKeepsImportedMediaAndRetryDoesNotDuplicateTheRecord() = runBlocking {
        pick(listOf(source("重试.png", image())))
        val id = model.state.value.media.items.single().asset.id
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_flash_media_save BEFORE DELETE ON flashnote_drafts BEGIN SELECT RAISE(ABORT, 'test'); END")
        model.changeInput("保存失败要保留图片")
        model.save()
        await { !model.state.value.isSaving && model.state.value.saveFailed }
        assertEquals(id, model.state.value.media.items.single().asset.id)
        assertFalse(model.preparePicker())
        assertTrue(repository.notes(FlashnoteStatus.PENDING).isEmpty())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_flash_media_save")
        save()
        assertEquals(1, repository.notes(FlashnoteStatus.PENDING).size)
        assertEquals(id, repository.noteAttachments(requireNotNull(model.state.value.savedEvent).id).single().asset.id)
    }

    @Test fun editingRemovalCanCancelOrSaveWithoutChangingTheOriginalCreationTime() = runBlocking {
        pick(listOf(source("编辑红.png", image()), source("编辑蓝.png", image(Color.BLUE))))
        model.changeInput("有图片的原记录")
        save()
        val id = requireNotNull(model.state.value.savedEvent).id
        val original = requireNotNull(repository.note(id))
        model.openDetail(id)
        await { !model.state.value.detailLoading && model.state.value.detailAttachments.size == 2 }
        val firstId = model.state.value.detailAttachments.first().asset.id
        model.beginEditing()
        await { model.state.value.editor?.loading == false }
        model.removeEditAttachment(firstId)
        await { model.state.value.editor?.let { !it.busy && it.attachments.size == 1 } == true }
        assertEquals(2, repository.noteAttachments(id).size)
        model.cancelEdit()
        await { model.state.value.editor == null }
        assertEquals(original, repository.note(id))
        model.beginEditing()
        await { model.state.value.editor?.loading == false }
        model.removeEditAttachment(firstId)
        await { model.state.value.editor?.let { !it.busy && it.attachments.size == 1 } == true }
        model.saveEdit()
        await { model.state.value.editor == null && model.state.value.detailAttachments.size == 1 }
        assertEquals(original.createdAt, repository.note(id)?.createdAt)
        assertEquals(original.revision + 1, repository.note(id)?.revision)
    }

    @Test fun importContinuesAfterPageAndViewModelCloseAndCanBeResumed() = runBlocking {
        assertTrue(model.preparePicker())
        model.picked(listOf(source("后台一.png", image()), source("后台二.png", image(Color.BLUE))), true)
        val closing = model
        model.setVisible(false)
        activity.pause().stop().destroy()
        await { !closing.state.value.media.isImporting }
        assertEquals(2, repository.draftAttachments(repository.openCreation().key).size)
        createModel()
        assertEquals(2, model.state.value.media.items.size)
        assertTrue(model.state.value.canSave)
    }

    @Test fun lostAcknowledgementRetriesWithTheSameAttachmentSnapshot() = runBlocking {
        destroyModel()
        var failOnce = true
        createModel(commit = { draft ->
            repository.save(draft).also { if (failOnce) { failOnce = false; throw IOException("lost acknowledgement") } }
        })
        pick(listOf(source("确认丢失.png", image())))
        val assetId = model.state.value.media.items.single().asset.id
        model.save()
        await { !model.state.value.isSaving && model.state.value.saveFailed }
        val first = repository.notes(FlashnoteStatus.PENDING).single()
        assertEquals("图片闪念", first.title)
        save()
        assertEquals(listOf(first), repository.notes(FlashnoteStatus.PENDING))
        assertEquals(listOf(assetId), repository.noteAttachments(first.id).map { it.asset.id })
    }

    @Test fun attachmentVersionConflictPreservesTextAndExplicitRetryReloadsAllDraftMedia() = runBlocking {
        val original = repository.openCreation()
        importer.importUri(original, source("先由其他上下文添加.png", image()), true)
        pick(listOf(source("当前选择.png", image(Color.BLUE))))
        assertEquals(FlashnoteInputSaveState.CONFLICT, model.state.value.inputSaveState)
        model.changeInput("需要保留的当前文字")
        model.retryInputSave()
        await { model.state.value.inputSaveState == FlashnoteInputSaveState.SAVED && model.state.value.media.items.size == 1 }
        assertEquals("需要保留的当前文字", model.state.value.input)
        assertNull(model.state.value.media.failure)
        pick(listOf(source("当前选择.png", image(Color.BLUE))))
        assertEquals(2, model.state.value.media.items.size)
        save()
        assertEquals(2, repository.noteAttachments(requireNotNull(model.state.value.savedEvent).id).size)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "等待闪念附件状态超时" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
    }
}
