package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.MediaSource
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.media.BuiltinCover
import com.txl.mebot.data.media.LocalMediaStore
import com.txl.mebot.data.schedule.ScheduleDraft
import com.txl.mebot.data.schedule.ScheduleRepository
import com.txl.mebot.ui.schedule.ScheduleEntry
import com.txl.mebot.ui.schedule.asEntry
import com.txl.mebot.ui.schedule.clearSchedulePhotoCache
import com.txl.mebot.ui.schedule.loadCachedSchedulePhoto
import com.txl.mebot.ui.schedule.loadSchedulePhoto
import com.txl.mebot.ui.schedule.peekSchedulePhoto
import com.txl.mebot.ui.schedule.scheduleThumbnailDimension
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SchedulePhotoTest {
    private lateinit var context: Context
    private lateinit var database: MebotDatabase
    private lateinit var media: LocalMediaStore
    private lateinit var repository: ScheduleRepository
    private val day = LocalDate.now()

    @Before fun setUp() {
        clearSchedulePhotoCache()
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, MebotDatabase::class.java).build()
        media = LocalMediaStore(context, database)
        repository = ScheduleRepository(database)
    }

    @After fun tearDown() {
        clearSchedulePhotoCache()
        database.close()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        File(context.filesDir, "media").deleteRecursively()
    }

    @Test fun importedAndGeneratedCoversLoadFromPersistentFilesWithBoundedDecode() = runBlocking {
        for ((index, source) in listOf(MediaSource.IMPORTED, MediaSource.AI_GENERATED).withIndex()) {
            val bytes = ByteArrayOutputStream().use { output ->
                val bitmap = Bitmap.createBitmap(2048, 1536, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(if (index == 0) Color.BLUE else Color.GREEN)
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                bitmap.recycle()
                output.toByteArray()
            }
            val asset = media.importImage({ ByteArrayInputStream(bytes) }, source = source)
            val session = repository.openSession()
            repository.createTask(session.id, UUID.randomUUID().toString(), ScheduleDraft("本地图片", date = day, coverAssetId = asset.id))
            val entry = repository.observeDisplayDate(day).first().first { it.sessionId == session.id }.asEntry()
            assertEquals(asset.relativePath, entry.coverRelativePath)
            assertNull(entry.image)
            val photo = requireNotNull(loadSchedulePhoto(context, entry))
            assertTrue(photo.width in 1..1024 && photo.height in 1..1024)
            assertEquals(2048, asset.width)
            val original = requireNotNull(media.fileFor(asset.id))
            media.clearDerivedCache()
            assertTrue(original.isFile)
            assertNotNull(loadSchedulePhoto(context, entry))
        }
    }

    @Test fun allBuiltinKeysResolveToPackagedImagesWithoutFilePaths() = runBlocking {
        for (cover in BuiltinCover.entries) {
            val asset = media.builtin(cover)
            val session = repository.openSession()
            repository.createTask(session.id, UUID.randomUUID().toString(), ScheduleDraft("内置封面", date = day, coverAssetId = asset.id))
            val entry = repository.observeDisplayDate(day).first().first { it.sessionId == session.id }.asEntry()
            assertNotNull(entry.image)
            assertNull(entry.coverRelativePath)
            val photo = requireNotNull(loadSchedulePhoto(context, entry))
            assertTrue(photo.width in 1..1024 && photo.height in 1..1024)
        }
    }

    @Test fun imageHeavyDayKeepsSmallCoversAfterFullScreenPreviewAndDoesNotChangeOriginals() = runBlocking {
        val pixels = Bitmap.createBitmap(2048, 1536, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().use { output ->
            assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, output))
            pixels.recycle()
            output.toByteArray()
        }
        val entries = (0 until 10).map { index ->
            val file = File(context.filesDir, "media/cached-day-$index.png")
            requireNotNull(file.parentFile).mkdirs()
            file.writeBytes(bytes)
            ScheduleEntry("photo-$index", "图片任务", day, detail = "", coverRelativePath = file.relativeTo(context.filesDir).path)
        }
        val dimension = scheduleThumbnailDimension(420)
        val thumbnails = entries.map { requireNotNull(loadCachedSchedulePhoto(context, it, dimension)) }
        assertTrue(thumbnails.all { it.width <= dimension && it.height <= dimension })
        // 详情和全屏界面先读列表小图，后台加载完成后仍可取得真正的大图。
        assertSame(thumbnails.first(), peekSchedulePhoto(context, entries.first(), 1024))
        assertSame(thumbnails.first(), peekSchedulePhoto(context, entries.first(), 2048))
        val preview = requireNotNull(loadCachedSchedulePhoto(context, entries.first(), 2048))
        assertEquals(2048, preview.width)
        assertEquals(1536, preview.height)
        for ((index, entry) in entries.withIndex()) {
            // 返回多图日期时复用已经解码的小图；全屏预览不应把它们挤出缓存。
            assertSame(thumbnails[index], loadCachedSchedulePhoto(context, entry, dimension))
            assertArrayEquals(bytes, File(context.filesDir, requireNotNull(entry.coverRelativePath)).readBytes())
        }
    }

    @Test fun recurringAndAdjacentPagesShareOneDecodedCoverAndCacheClearHonorsRemovedFiles() = runBlocking {
        val file = File(context.filesDir, "media/shared-cover.png")
        requireNotNull(file.parentFile).mkdirs()
        val pixels = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888)
        file.outputStream().use { assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        pixels.recycle()
        val entry = ScheduleEntry("today", "每天喂猫", day, detail = "", coverRelativePath = file.relativeTo(context.filesDir).path)
        val photos = coroutineScope {
            (0 until 6).map { index -> async {
                loadCachedSchedulePhoto(context, entry.copy(id = "day-$index", date = day.plusDays(index.toLong())), 512)
            } }.awaitAll()
        }
        assertNotNull(photos.first())
        assertTrue(photos.all { it === photos.first() })
        assertTrue(file.delete())
        clearSchedulePhotoCache()
        assertNull(loadCachedSchedulePhoto(context, entry, 512))
    }

    @Test fun missingCorruptOrEscapingPathsDoNotBreakTheTaskOrReadOutsideFilesDir() = runBlocking {
        val entry = ScheduleEntry("without-cover", "任务仍可查看", day, detail = "详情继续保留")
        assertNull(loadSchedulePhoto(context, entry))
        assertNull(loadSchedulePhoto(context, entry.copy(coverRelativePath = "media/missing.png")))
        val corrupt = File(context.filesDir, "media/corrupt.png")
        requireNotNull(corrupt.parentFile).mkdirs()
        corrupt.writeText("not an image")
        assertNull(loadSchedulePhoto(context, entry.copy(coverRelativePath = "media/corrupt.png")))
        val outside = File(context.cacheDir, "outside.png")
        outside.writeBytes(Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jD1cAAAAASUVORK5CYII=",
        ))
        assertNull(loadSchedulePhoto(context, entry.copy(coverRelativePath = outside.relativeTo(context.filesDir).path)))
        assertTrue(outside.isFile)
        assertEquals("任务仍可查看", entry.title)
    }

    @Test fun deletingTasksRemovesOnlyUnsharedPersistentImagesAndCleanupSurvivesReopen() = runBlocking {
        database.close(); database = MebotDatabase.create(context)
        media = LocalMediaStore(context, database); repository = ScheduleRepository(database)
        val bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jD1cAAAAASUVORK5CYII=")
        val asset = media.importImage({ ByteArrayInputStream(bytes) })
        val first = repository.openSession(); val second = repository.openSession()
        val one = repository.createTask(first.id, "one", ScheduleDraft("第一项", date = day, coverAssetId = asset.id))
        val two = repository.createTask(second.id, "two", ScheduleDraft("第二项", date = day, coverAssetId = asset.id))
        val file = requireNotNull(media.fileFor(asset.id))
        repository.deleteTask(first.id, "delete-one", one.revision)
        media.cleanPendingDeletions()
        assertTrue(file.isFile); assertNotNull(database.mediaDao().asset(asset.id))
        repository.deleteTask(second.id, "delete-two", two.revision)
        assertNull(database.mediaDao().asset(asset.id))
        assertTrue(file.isFile) // 先提交删除事务，物理清理可以跨进程继续。
        database.close(); database = MebotDatabase.create(context)
        media = LocalMediaStore(context, database); repository = ScheduleRepository(database)
        media.cleanPendingDeletions()
        assertFalse(file.exists())
        assertTrue(database.mediaDao().cleanupPaths(10).isEmpty())
    }

    @Test fun cleanupDoesNotDeleteAFileReimportedBeforeCleanupOrAnUnsafePath() = runBlocking {
        val bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jD1cAAAAASUVORK5CYII=")
        val asset = media.importImage({ ByteArrayInputStream(bytes) })
        val session = repository.openSession()
        val task = repository.createTask(session.id, "one", ScheduleDraft("日程", date = day, coverAssetId = asset.id))
        repository.deleteTask(session.id, "delete", task.revision)
        val reused = media.importImage({ ByteArrayInputStream(bytes) })
        assertNotEquals(asset.id, reused.id)
        val outside = File(context.filesDir, "credentials-should-stay").apply { writeText("local fixture") }
        database.mediaDao().queueCleanup(com.txl.mebot.data.local.MediaCleanupEntity(outside.name))
        media.cleanPendingDeletions()
        assertTrue(requireNotNull(media.fileFor(reused.id)).isFile)
        assertTrue(outside.isFile)
        assertTrue(database.mediaDao().cleanupPaths(10).isEmpty())
    }
}
