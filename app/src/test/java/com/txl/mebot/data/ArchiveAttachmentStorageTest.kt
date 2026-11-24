package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.archive.*
import com.txl.mebot.data.local.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
class ArchiveAttachmentStorageTest {
    private lateinit var context: Context
    private lateinit var database: MebotDatabase
    private lateinit var records: ArchiveRepository
    private lateinit var files: ArchiveAttachmentRepository
    private val bytes = "fixture private image bytes with sensitive caption".toByteArray()
    private val cipher = ArchiveCipher { SecretKeySpec(ByteArray(32) { 2 }, "AES") }
    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(MebotDatabase.FILE_NAME)
        File(context.filesDir, "archive").deleteRecursively(); File(context.cacheDir, "archive_previews").deleteRecursively(); open()
    }
    private fun open() {
        database = MebotDatabase.create(context)
        val storage = ArchiveStorage(database, database.archives(), cipher)
        records = ArchiveRepository(storage); files = ArchiveAttachmentRepository(context, storage)
    }
    @After fun tearDown() {
        database.close(); context.deleteDatabase(MebotDatabase.FILE_NAME)
        File(context.filesDir, "archive").deleteRecursively(); File(context.cacheDir, "archive_previews").deleteRecursively()
        File(context.filesDir, "media/originals/archive-fixture-other-bot.png").delete()
    }
    private suspend fun import(session: String = "a", id: String = "photo") = files.import(session, "我的身份证.png", "image/png", id) { ByteArrayInputStream(bytes) }
    private fun original(id: String = "photo") = File(context.filesDir, "archive/originals/$id.bin")

    @Test fun importedFileAndEncryptedMetadataSurviveSourceRemovalCloseReopenAndCacheCleanup() = runBlocking {
        records.createSession("a"); val asset = import()
        assertTrue(original().isFile); assertFalse(original().readBytes().contentEquals(bytes))
        assertFalse(original().readText(Charsets.ISO_8859_1).contains("sensitive caption"))
        val row = requireNotNull(database.archives().asset(asset))
        assertFalse(row.payload.toString(Charsets.UTF_8).contains("身份证"))
        database.close(); open()
        assertEquals("我的身份证.png", files.metadata(asset).name)
        var preview: File? = null
        files.withDecryptedFile(asset) { file -> assertArrayEquals(bytes, file.readBytes()); preview = file }
        assertFalse(requireNotNull(preview).exists())
        files.clearPreviewCache(); files.recoverFiles()
        assertTrue(original().exists()); assertNotNull(database.archives().asset(asset))
        assertEquals(1, database.archives().sessionAssets("a").size)
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM media_assets WHERE relativePath LIKE 'archive/%'").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    @Test fun retrySameImportIsIdempotentButContentOrScopeChangesNeverOverwriteOriginal() = runBlocking {
        records.createSession("a"); records.createSession("b")
        import(); val original = original().readBytes()
        assertEquals("photo", import()); assertEquals(1, database.archives().sessionAssets("a").size)
        assertTrue(runCatching { files.import("a", "我的身份证.png", "image/png", "photo") { ByteArrayInputStream("changed".toByteArray()) } }.isFailure)
        assertTrue(runCatching { import("b") }.isFailure)
        assertArrayEquals(original, original().readBytes()); assertTrue(database.archives().sessionAssets("b").isEmpty())
    }

    @Test fun failedSourceAndSizeLimitDoNotPublishAssetOrLeavePlaintextStaging() = runBlocking {
        records.createSession("a")
        val broken = object : InputStream() {
            private var reads = 0
            override fun read(): Int { if (reads++ < 8) return 65; throw IOException("fixture") }
        }
        assertTrue(runCatching { files.import("a", "bad", "application/octet-stream", "failed") { broken } }.isFailure)
        assertTrue(runCatching { files.import("a", "large", "image/png", "large", maxBytes = 3) { ByteArrayInputStream(bytes) } }.isFailure)
        assertTrue(runCatching { files.import("a", "empty", "image/png", "empty") { ByteArrayInputStream(byteArrayOf()) } }.isFailure)
        assertTrue(database.archives().sessionAssets("a").isEmpty())
        assertTrue(File(context.filesDir, "archive/staging").listFiles().orEmpty().isEmpty())
        assertTrue(File(context.filesDir, "archive/originals").listFiles().orEmpty().isEmpty())
    }

    @Test fun failedDatabaseRegistrationAndInterruptedPublicationAreRecoveredWithoutTouchingOtherBot() = runBlocking {
        records.createSession("a")
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER fail_asset BEFORE INSERT ON archive_session_assets BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertTrue(runCatching { import() }.isFailure)
        assertNull(database.archives().asset("photo")); assertTrue(original().exists())
        sql.execSQL("DROP TRIGGER fail_asset")
        original("crash-orphan").writeBytes(byteArrayOf(1, 2, 3))
        val other = File(context.filesDir, "media/originals/archive-fixture-other-bot.png").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        files.recoverFiles()
        assertFalse(original().exists()); assertFalse(original("crash-orphan").exists()); assertArrayEquals(bytes, other.readBytes())
        import(); files.recoverFiles(); assertTrue(original().exists())
    }

    @Test fun onlyUnsentUnreferencedAssetCanBeDiscardedAndFormalOrMessageReferencesKeepOriginal() = runBlocking {
        records.createSession("a"); import(id = "pending")
        files.discardPending("a", "pending"); assertFalse(original("pending").exists()); assertNull(database.archives().asset("pending"))
        import()
        val draft = records.saveDraft("a", 0, ArchiveDraftContent(candidate = ArchiveContent("证件", assetIds = listOf("photo"), coverAssetId = "photo")))
        val saved = records.confirm("a", "save", draft.revision)
        assertTrue(runCatching { files.discardPending("a", "photo") }.isFailure)
        records.createSession("edit", saved.recordId)
        assertEquals(listOf("photo"), database.archives().sessionAssets("edit").map { it.assetId })
        assertTrue(runCatching { files.discardPending("edit", "photo") }.isFailure)
        import("edit", "sent")
        val input = records.saveDraft("edit", 0, ArchiveDraftContent("补充图片"))
        records.sendMessage("edit", "message", input.revision, "补充图片", listOf("sent"))
        assertTrue(runCatching { files.discardPending("edit", "sent") }.isFailure)
        files.recoverFiles(); assertTrue(original().isFile); assertTrue(original("sent").isFile)
    }

    @Test fun authenticationFailureAndCancelledConsumerNeverExposeOrRetainPlaintext() = runBlocking {
        records.createSession("a"); import()
        assertTrue(runCatching { files.withDecryptedFile("photo") { throw CancellationException("fixture") } }.isFailure)
        assertTrue(File(context.cacheDir, "archive_previews").listFiles().orEmpty().isEmpty())
        original().writeBytes(original().readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        var exposed = false
        assertTrue(runCatching { files.withDecryptedFile("photo") { exposed = true } }.isFailure)
        assertFalse(exposed); assertTrue(File(context.cacheDir, "archive_previews").listFiles().orEmpty().isEmpty())
        assertNotNull(database.archives().asset("photo")); assertTrue(original().exists())
    }

    @Test fun rejectsPathTraversalAndMissingSessionBeforeOpeningSource() = runBlocking {
        records.createSession("a")
        var opened = false
        assertTrue(runCatching { files.import("a", "name", "image/png", "../../outside") { opened = true; ByteArrayInputStream(bytes) } }.isFailure)
        assertTrue(runCatching { files.import("missing", "name", "image/png", "photo") { opened = true; ByteArrayInputStream(bytes) } }.isFailure)
        assertFalse(opened)
    }
}
