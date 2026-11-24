package com.txl.mebot.data

import android.app.Application
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.OneTimeWorkRequest
import com.txl.mebot.data.archive.*
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.media.AttachmentFileProvider
import com.txl.mebot.ui.archive.ArchiveExportCache
import com.txl.mebot.ui.archive.ArchiveMediaFiles
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
class ArchiveExportFilesTest {
    private lateinit var app: Application
    private lateinit var database: MebotDatabase
    private lateinit var files: ArchiveAttachmentRepository
    private lateinit var media: ArchiveMediaFiles
    private lateinit var provider: AttachmentFileProvider
    private val scheduled = mutableListOf<OneTimeWorkRequest>()
    private val bytes = "private fixture document\nsecond line".toByteArray()
    private val authority get() = app.packageName + ".attachments"
    private var keyAvailable = true

    @Before fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext(); app.deleteDatabase(MebotDatabase.FILE_NAME)
        listOf("archive_exports", "archive_export_staging", "archive_previews").forEach { File(app.cacheDir, it).deleteRecursively() }
        File(app.filesDir, "archive").deleteRecursively()
        database = MebotDatabase.create(app)
        val cipher = ArchiveCipher { if (!keyAvailable) throw ArchiveKeyUnavailable() else SecretKeySpec(ByteArray(32) { 6 }, "AES") }
        val storage = ArchiveStorage(database, database.archives(), cipher)
        ArchiveRepository(storage).createSession("session")
        files = ArchiveAttachmentRepository(app, storage)
        files.import("session", "私有文件.txt", "text/plain", "document") { ByteArrayInputStream(bytes) }
        media = ArchiveMediaFiles(app, files) { scheduled += it }
        provider = AttachmentFileProvider().apply { attachInfo(app, app.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!) }
    }
    @After fun tearDown() {
        database.close(); app.deleteDatabase(MebotDatabase.FILE_NAME)
        File(app.filesDir, "archive").deleteRecursively()
        listOf("archive_exports", "archive_export_staging", "archive_previews").forEach { File(app.cacheDir, it).deleteRecursively() }
        File(app.filesDir, "media/originals/export-fixture-other.txt").delete()
    }
    private fun original() = File(app.filesDir, "archive/originals/document.bin")

    @Test fun authenticatedExportUsesOneOpaqueContentUriAndRemainsReadableAfterCallback() = runBlocking {
        val encrypted = original().readBytes()
        val (uri, mime) = media.exportForViewing("document")
        assertEquals("content", uri.scheme); assertEquals(authority, uri.authority); assertEquals("text/plain", mime)
        assertTrue(uri.path!!.startsWith("/archive_exports/")); assertFalse(uri.toString().contains("私有"))
        val raw = ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")).use { it.readBytes() }
        assertArrayEquals(bytes, raw)
        assertEquals(1, scheduled.size)
        assertEquals(TimeUnit.HOURS.toMillis(1), scheduled.single().workSpec.initialDelay)
        assertTrue(File(app.cacheDir, "archive_previews").listFiles().orEmpty().isEmpty())
        assertTrue(File(app.cacheDir, "archive_export_staging").listFiles().orEmpty().isEmpty())
        assertArrayEquals(encrypted, original().readBytes())
    }

    @Test fun originalAndPrivateStagingCannotBeExposedAndTraversalDoesNotEscapeCacheRoot() = runBlocking {
        assertTrue(runCatching { FileProvider.getUriForFile(app, authority, original()) }.isFailure)
        val private = File(app.cacheDir, "archive_export_staging/private.txt").apply { parentFile!!.mkdirs(); writeText("private") }
        assertTrue(runCatching { FileProvider.getUriForFile(app, authority, private) }.isFailure)
        val (uri, _) = media.exportForViewing("document")
        val escaped = uri.buildUpon().encodedPath("/archive_exports/%2E%2E/archive_export_staging/private.txt").build()
        assertTrue(runCatching { provider.openFile(escaped, "r") }.isFailure)
    }

    @Test fun damagedCiphertextAndLostKeyNeverPublishOrSchedulePartialPlaintext() = runBlocking {
        val good = original().readBytes()
        original().writeBytes(good.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        assertTrue(runCatching { media.exportForViewing("document") }.isFailure)
        assertTrue(File(app.cacheDir, "archive_exports").listFiles().orEmpty().isEmpty())
        assertTrue(File(app.cacheDir, "archive_export_staging").listFiles().orEmpty().isEmpty()); assertTrue(scheduled.isEmpty())
        original().writeBytes(good); keyAvailable = false
        assertTrue(runCatching { media.exportForViewing("document") }.isFailure)
        assertArrayEquals(good, original().readBytes()); assertTrue(scheduled.isEmpty())
    }

    @Test fun cancellationBeforePublicationCleansPrivateCopiesAndKeepsEncryptedOriginal() = runBlocking {
        val cancelled = ArchiveMediaFiles(app, files) { throw CancellationException("fixture") }
        val encrypted = original().readBytes()
        assertTrue(runCatching { cancelled.exportForViewing("document") }.exceptionOrNull() is CancellationException)
        assertTrue(File(app.cacheDir, "archive_exports").listFiles().orEmpty().isEmpty())
        assertTrue(File(app.cacheDir, "archive_export_staging").listFiles().orEmpty().isEmpty())
        assertArrayEquals(encrypted, original().readBytes())
    }

    @Test fun coroutineCancellationAfterSchedulingNeverLeavesAPublishedViewingCopy() = runBlocking {
        val task = Job()
        val cancelled = ArchiveMediaFiles(app, files) { task.cancel() }
        assertTrue(runCatching { withContext(task) { cancelled.exportForViewing("document") } }.exceptionOrNull() is CancellationException)
        assertTrue(File(app.cacheDir, "archive_exports").listFiles().orEmpty().isEmpty())
        assertTrue(File(app.cacheDir, "archive_export_staging").listFiles().orEmpty().isEmpty())
        files.withDecryptedFile("document") { assertArrayEquals(bytes, it.readBytes()) }
    }

    @Test fun quotasRemoveOnlyOldViewingCopiesAndExpiryRevokesAccessWithoutDeletingOriginals() = runBlocking {
        var clock = 5_000_000L
        val cache = ArchiveExportCache(app, maxCount = 2, maxBytes = 20, now = { clock })
        val other = File(app.filesDir, "media/originals/export-fixture-other.txt").apply { parentFile!!.mkdirs(); writeText("other bot") }
        val first = cache.destination(UUID.randomUUID().toString()).apply { mkdirs(); File(this, "file.bin").writeBytes(ByteArray(8)); setLastModified(clock - 2_000) }
        val second = cache.destination(UUID.randomUUID().toString()).apply { mkdirs(); File(this, "file.bin").writeBytes(ByteArray(8)); setLastModified(clock - 1_000) }
        val originalBytes = original().readBytes()
        cache.locked { clean(reserveBytes = 10, reserveCount = 1) }
        assertFalse(first.exists()); assertTrue(second.exists())
        val uri = FileProvider.getUriForFile(app, authority, File(second, "file.bin"))
        clock += TimeUnit.HOURS.toMillis(2)
        cache.locked { clean() }
        assertFalse(second.exists()); assertTrue(runCatching { provider.openFile(uri, "r") }.isFailure)
        assertEquals("other bot", other.readText()); assertArrayEquals(originalBytes, original().readBytes())
        assertTrue(runCatching { cache.destination("../../outside") }.isFailure)
    }

    @Test fun restartRecoveryRemovesInterruptedPrivateExportAndExpiredCacheOnly() = runBlocking {
        val (uri, _) = media.exportForViewing("document")
        val folder = File(app.cacheDir, "archive_exports").listFiles()!!.single()
        folder.setLastModified(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2))
        File(app.cacheDir, "archive_export_staging/interrupted").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        media.cleanExpiredExports()
        assertFalse(folder.exists()); assertTrue(File(app.cacheDir, "archive_export_staging").listFiles().orEmpty().isEmpty())
        assertTrue(runCatching { provider.openFile(uri, "r") }.isFailure)
        files.withDecryptedFile("document") { assertArrayEquals(bytes, it.readBytes()) }
    }
}
