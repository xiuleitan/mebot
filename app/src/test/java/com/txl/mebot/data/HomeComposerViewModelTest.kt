package com.txl.mebot.data

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.*
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.*
import com.txl.mebot.ui.home.HomeComposerViewModel
import com.txl.mebot.ui.schedule.AttachmentFailure
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeComposerViewModelTest {
    private lateinit var app: Application
    private lateinit var container: AppContainer
    private lateinit var model: HomeComposerViewModel
    private lateinit var handle: SavedStateHandle
    private var activity: ActivityController<ComponentActivity>? = null
    @Before fun setup() {
        ReflectionHelpers.getStaticField<MutableMap<String, Any>>(FileProvider::class.java, "sCache").clear()
        app = ApplicationProvider.getApplicationContext(); app.deleteDatabase(MebotDatabase.FILE_NAME)
        container = AppContainer(app); create()
    }
    private fun create(saved: SavedStateHandle = SavedStateHandle()) {
        handle = saved
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        model = ViewModelProvider(activity!!.get(), object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) =
                HomeComposerViewModel(app, saved, container.homeComposer) as T
        })[HomeComposerViewModel::class.java]
        await { model.state.value.loaded }
    }
    private fun destroy() { activity?.pause()?.stop()?.destroy(); activity = null; shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)) }
    @After fun close() {
        destroy(); container.stopMaintenance(); container.database.close(); app.deleteDatabase(MebotDatabase.FILE_NAME)
        File(app.filesDir, "media").deleteRecursively()
    }
    private fun image() = ByteArrayOutputStream().use { out ->
        Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { compress(Bitmap.CompressFormat.PNG, 100, out); recycle() }
        out.toByteArray()
    }
    private fun source(name: String, bytes: ByteArray): Uri {
        val file = File(app.filesDir, "media/originals/home-test-source/$name").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        return FileProvider.getUriForFile(app, "${app.packageName}.attachments", file, name)
    }
    private fun pick(uris: List<Uri>, images: Boolean = true) {
        assertTrue(model.preparePicker()); model.picked(uris, images)
        await { !model.state.value.busy }
    }
    @Test fun mixedDraftPersistsAcrossRecreationWithoutCreatingBotRecords() = runBlocking {
        assertFalse(model.state.value.canSend)
        val bytes = "附件内容".toByteArray()
        pick(listOf(source("说明.txt", bytes), source("照片.png", image())), false)
        await { model.state.value.items.size == 2 }
        assertFalse(model.state.value.canSend)
        model.changeInput("下周把这些材料整理好")
        val saving = model.savePendingInput(); await { saving.isCompleted }
        assertEquals("下周把这些材料整理好", container.homeComposer.read().inputText)
        assertEquals(2, model.state.value.items.size)
        val item = model.state.value.items.first()
        val intent = requireNotNull(model.fileIntent(item))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertArrayEquals(bytes, app.contentResolver.openInputStream(intent.data!!)!!.use { it.readBytes() })
        for (table in listOf("schedule_sessions", "flashnotes", "dayima_periods", "subscriptions", "archive_records"))
            container.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); assertEquals(table, 0, it.getInt(0)) }
        destroy(); create()
        assertEquals("下周把这些材料整理好", model.state.value.input)
        assertEquals(2, model.state.value.items.size)
        assertTrue(model.state.value.items.last().isImage)
    }
    @Test fun recentPhotoSelectionTogglesAndSharedMediaIsProtectedUntilAllOwnersReleaseIt() = runBlocking {
        val uri = source("共享图.png", image())
        model.pickLocal(uri); await { !model.state.value.busy && model.state.value.items.size == 1 }
        val first = model.state.value.items.single()
        assertEquals(0, container.database.mediaDao().deleteIfUnused(first.asset.id))
        pick(listOf(uri, uri)); assertEquals(1, model.state.value.items.size)
        container.database.flashnoteDao().insertNote(FlashnoteEntity("other", content = "其他 Bot", createdAt = 1, updatedAt = 1))
        container.database.flashnoteMediaDao().insertNote(FlashnoteAttachmentEntity("other", first.asset.id, "共享", 0))
        model.pickLocal(uri); await { !model.state.value.busy && model.state.value.items.isEmpty() }
        assertNotNull(container.database.mediaDao().asset(first.asset.id))
        assertTrue(container.media.fileFor(first.asset.id)!!.isFile)
        model.pickLocal(uri); await { !model.state.value.busy && model.state.value.items.size == 1 }
        container.database.flashnoteMediaDao().clearNote("other")
        model.remove(first.asset.id); await { !model.state.value.busy && model.state.value.items.isEmpty() }
        assertNull(container.database.mediaDao().asset(first.asset.id))
    }
    @Test fun databaseFailurePreservesUnsavedTextInSavedStateAndRetryCommitsItOnce() = runBlocking {
        container.database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_home_input BEFORE UPDATE ON home_composer_drafts
            BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        repeat(50) { model.changeInput("尚未发送 $it") }
        val job = model.savePendingInput(); await { job.isCompleted }
        assertTrue(model.state.value.saveFailed)
        assertEquals("", container.homeComposer.read().inputText)
        val saved = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })
        destroy(); create(saved); await { model.state.value.saveFailed }
        assertEquals("尚未发送 49", model.state.value.input)
        container.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_home_input")
        model.retry(); await { !model.state.value.saveFailed }
        assertEquals("尚未发送 49", container.homeComposer.read().inputText)
        assertEquals(1L, container.homeComposer.read().revision)
    }
    @Test fun failedAttachmentAssociationRollsBackItsMediaAndPartialBatchKeepsSuccessfulFiles() = runBlocking {
        container.database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_home_attach BEFORE INSERT ON home_composer_attachments
            BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        val uri = source("图.png", image())
        pick(listOf(uri)); assertEquals(AttachmentFailure.IMPORT, model.state.value.failure)
        assertTrue(model.state.value.items.isEmpty())
        container.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM media_assets").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        container.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_home_attach")
        pick(listOf(uri, Uri.parse("content://missing-provider/missing")))
        await { model.state.value.items.size == 1 }
        assertEquals(AttachmentFailure.IMPORT, model.state.value.failure)
        assertTrue(model.state.value.items.single().isImage)
    }
    @Test fun olderActivitySnapshotCannotOverwriteTheLatestCommittedDraft() = runBlocking {
        model.changeInput("系统快照中的旧文字")
        val oldSnapshot = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })
        var job = model.savePendingInput(); await { job.isCompleted }
        model.changeInput("之后已保存的新文字")
        job = model.savePendingInput(); await { job.isCompleted }
        assertEquals(2L, container.homeComposer.read().revision)
        destroy(); create(oldSnapshot)
        assertEquals("之后已保存的新文字", model.state.value.input)
        job = model.savePendingInput(); await { job.isCompleted }
        assertEquals("之后已保存的新文字", container.homeComposer.read().inputText)
        assertEquals(2L, container.homeComposer.read().revision)
    }
    @Test fun cameraCancellationAndRestoredPickerKeepExistingDraftAndImportOnlyConfirmedCapture() = runBlocking {
        model.changeInput("拍照附件")
        val cancelled = requireNotNull(model.prepareCamera())
        model.cameraResult(false)
        await { !app.contentResolver.runCatching { openInputStream(cancelled)?.close() }.isSuccess }
        assertTrue(model.state.value.items.isEmpty())
        val uri = source("恢复.png", image())
        assertTrue(model.preparePicker())
        val saved = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })
        destroy(); create(saved)
        model.picked(listOf(uri), true); await { !model.state.value.busy && model.state.value.items.size == 1 }
        val capture = requireNotNull(model.prepareCamera())
        app.contentResolver.openOutputStream(capture)!!.use { it.write(image()) }
        model.cameraResult(true); await { !model.state.value.busy }
        // 相同原图在本地去重，不因拍照入口再生成一条附件。
        assertEquals(1, model.state.value.items.size)
        assertEquals("拍照附件", model.state.value.input)
    }
    private fun await(condition: () -> Boolean) {
        repeat(400) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); if (condition()) return; Thread.sleep(5) }
        assertTrue("Expected state within timeout: ${model.state.value}", condition())
    }
}
