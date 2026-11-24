package com.txl.mebot.data

import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.txl.mebot.data.archive.*
import com.txl.mebot.ui.archive.ArchiveImageDownloads
import com.txl.mebot.ui.archive.archiveImageAlbum
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ArchiveImageDownloadsTest {
    private lateinit var fixture: ArchiveAnalysisFixture
    private lateinit var downloads: ArchiveImageDownloads
    private lateinit var provider: SavedImagesProvider
    private val first = ByteArray(150_000) { (it % 251).toByte() }
    private val second = "different second image original".toByteArray()

    @Before fun setup() = runBlocking {
        fixture = ArchiveAnalysisFixture()
        fixture.records.createSession("session")
        fixture.files.import("session", "private-front.jpg", "image/jpeg", "front") { ByteArrayInputStream(first) }
        fixture.files.import("session", "private-back.png", "image/png", "back") { ByteArrayInputStream(second) }
        fixture.files.import("session", "notes.txt", "text/plain", "document") { ByteArrayInputStream("notes".toByteArray()) }
        provider = Robolectric.buildContentProvider(SavedImagesProvider::class.java).create("media").get()
        downloads = ArchiveImageDownloads(fixture.app, fixture.files)
    }

    @After fun teardown() { provider.directory.deleteRecursively(); fixture.close() }
    private fun encrypted(id: String) = File(fixture.app.filesDir, "archive/originals/$id.bin")
    private fun assertNoPrivateCopies() = assertTrue(File(fixture.app.cacheDir, "archive_previews").listFiles().orEmpty().isEmpty())

    @Test fun publishesOnlyAfterTheEntireOriginalWasCopiedAndPreservesBusinessDataAndCiphertext() = runBlocking {
        val original = encrypted("front").readBytes()
        val before = fixture.records.context("session")
        provider.expected = first
        val uri = downloads.saveToGallery("front")
        assertEquals("media", uri.authority)
        assertEquals(1, provider.insertions); assertEquals(1, provider.publications)
        assertTrue(provider.pendingWhenOpened)
        val row = provider.rows.getValue(ContentUris.parseId(uri))
        assertEquals("Pictures/mebot", row.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertEquals("image/jpeg", row.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertEquals(0, row.getAsInteger(MediaStore.Images.Media.IS_PENDING).toInt())
        assertFalse(row.getAsString(MediaStore.Images.Media.DISPLAY_NAME).contains("private-front"))
        assertArrayEquals(first, provider.file(uri).readBytes())
        assertArrayEquals(original, encrypted("front").readBytes())
        assertEquals(before, fixture.records.context("session")); assertNoPrivateCopies()
    }

    @Test fun savingTheSecondImageCopiesThatImageRatherThanTheCover() = runBlocking {
        val original = encrypted("back").readBytes()
        provider.expected = second
        val uri = downloads.saveToGallery("back")
        assertArrayEquals(second, provider.file(uri).readBytes())
        assertEquals("image/png", provider.rows.getValue(ContentUris.parseId(uri)).getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertArrayEquals(original, encrypted("back").readBytes()); assertNoPrivateCopies()
    }

    @Test fun authenticationOrKeyFailureAndOrdinaryFilesNeverCreateAPublicImage() = runBlocking {
        val original = encrypted("front").readBytes()
        encrypted("front").writeBytes(original.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        assertTrue(runCatching { downloads.saveToGallery("front") }.isFailure)
        encrypted("front").writeBytes(original)
        val locked = ArchiveAttachmentRepository(fixture.app, ArchiveStorage(fixture.db, fixture.db.archives(), ArchiveCipher { throw ArchiveKeyUnavailable() }))
        assertTrue(runCatching { ArchiveImageDownloads(fixture.app, locked).saveToGallery("front") }.isFailure)
        assertTrue(runCatching { downloads.saveToGallery("document") }.isFailure)
        assertEquals(0, provider.insertions); assertArrayEquals(original, encrypted("front").readBytes()); assertNoPrivateCopies()
    }

    @Test fun failedOpeningOrPublishingRemovesOnlyTheNewPendingTarget() = runBlocking {
        provider.failOpen = true
        assertTrue(runCatching { downloads.saveToGallery("front") }.isFailure)
        assertTrue(provider.rows.isEmpty()); assertEquals(0, provider.publications)
        provider.failOpen = false; provider.failPublish = true
        assertTrue(runCatching { downloads.saveToGallery("front") }.isFailure)
        assertTrue(provider.rows.isEmpty()); assertEquals(2, provider.deletions)
        fixture.files.withDecryptedFile("front") { assertArrayEquals(first, it.readBytes()) }
        assertNoPrivateCopies()
    }

    @Test fun cancellationDuringWritingCleansPendingMediaAndPrivatePlaintext() = runBlocking {
        val task = Job()
        provider.onOpen = { task.cancel() }
        val error = runCatching { withContext(task) { downloads.saveToGallery("front") } }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(provider.rows.isEmpty()); assertEquals(0, provider.publications); assertEquals(1, provider.deletions)
        fixture.files.withDecryptedFile("front") { assertArrayEquals(first, it.readBytes()) }; assertNoPrivateCopies()
    }

    @Test @Config(sdk = [28]) fun olderAndroidWritesTheUserChosenDocumentWithoutSharedStoragePermission() = runBlocking {
        val target = provider.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues())
        downloads.saveToDocument("back", target)
        assertArrayEquals(second, provider.file(target).readBytes())
        assertTrue(downloads.suggestedName("back").endsWith(".png"))
        assertTrue(runCatching { downloads.saveToGallery("back") }.isFailure)
        assertEquals(1, provider.insertions); assertNoPrivateCopies()
    }

    @Test fun failedDocumentSaveRemovesTheCreatedDocumentAndNeverWritesAnAppPrivateTarget() = runBlocking {
        val target = provider.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues())
        provider.failOpen = true
        assertTrue(runCatching { downloads.saveToDocument("front", target) }.isFailure)
        assertTrue(provider.rows.isEmpty())
        val original = encrypted("front").readBytes()
        assertTrue(runCatching { downloads.saveToDocument("front", Uri.fromFile(encrypted("front"))) }.isFailure)
        assertArrayEquals(original, encrypted("front").readBytes()); assertNoPrivateCopies()
    }

    @Test fun albumKeepsAttachmentOrderAndTheSelectedImageWhileExcludingFilesDuplicatesAndOtherArchives() = runBlocking {
        fixture.records.createSession("other")
        val unrelated = fixture.image("other", "unrelated")
        val album = archiveImageAlbum("back", listOf("front", "document", "back", "front", "missing"), fixture.files)
        assertEquals(listOf("front", "back"), album.imageIds)
        assertEquals(1, album.imageIds.indexOf("back")); assertFalse(unrelated in album.imageIds)
        assertTrue(archiveImageAlbum("document", listOf("front", "document"), fixture.files).imageIds.isEmpty())
        assertTrue(runCatching { archiveImageAlbum(unrelated, listOf("front", "back"), fixture.files) }.isFailure)
        assertEquals(0, provider.insertions)
    }

    class SavedImagesProvider : ContentProvider() {
        val rows = linkedMapOf<Long, ContentValues>()
        val directory get() = File(requireNotNull(context).cacheDir, "saved-image-fixture").apply { mkdirs() }
        var insertions = 0; var publications = 0; var deletions = 0
        var failOpen = false; var failPublish = false; var pendingWhenOpened = false
        var expected: ByteArray? = null
        var onOpen: (() -> Unit)? = null
        override fun onCreate() = true
        fun file(uri: Uri) = File(directory, "${ContentUris.parseId(uri)}.image")
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val id = (++insertions).toLong(); rows[id] = ContentValues(values ?: ContentValues())
            return ContentUris.withAppendedId(uri, id)
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (failOpen) throw FileNotFoundException("fixture failure")
            pendingWhenOpened = rows.getValue(ContentUris.parseId(uri)).getAsInteger(MediaStore.Images.Media.IS_PENDING) == 1
            onOpen?.invoke()
            return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            if (failPublish) return 0
            expected?.let { assertArrayEquals(it, file(uri).readBytes()) }
            rows.getValue(ContentUris.parseId(uri)).putAll(requireNotNull(values)); publications++
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            rows.remove(ContentUris.parseId(uri)); file(uri).delete(); deletions++; return 1
        }
        override fun getType(uri: Uri) = "image/jpeg"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    }
}
