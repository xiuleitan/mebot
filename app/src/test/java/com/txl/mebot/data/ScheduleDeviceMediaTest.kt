package com.txl.mebot.data

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.ui.schedule.deviceImageChanges
import com.txl.mebot.ui.schedule.followLatestDeviceImage
import com.txl.mebot.ui.schedule.galleryAccess
import com.txl.mebot.ui.schedule.galleryPermissions
import com.txl.mebot.ui.schedule.queryDeviceImages
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ScheduleDeviceMediaTest {
    @Test fun firstAndRefreshedImagesFinishLoadingBeforeTheGridHasItsFirstLayout() = runBlocking {
        // 加载转圈时网格还没有挂载；定位不能等网格布局，否则无法退出加载态。
        val grid = LazyGridState()
        val first = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 1)
        val latest = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 2)
        assertTrue(grid.layoutInfo.visibleItemsInfo.isEmpty())
        withTimeout(1_000) {
            grid.followLatestDeviceImage(null, first)
            grid.followLatestDeviceImage(first, latest)
        }
        assertEquals(0, grid.firstVisibleItemIndex)
        assertEquals(0, grid.firstVisibleItemScrollOffset)
    }

    @Test fun photoRefreshDoesNotMoveAwayFromOlderImagesTheUserIsBrowsing() = runBlocking {
        val grid = LazyGridState(firstVisibleItemIndex = 4, firstVisibleItemScrollOffset = 16)
        val old = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 1)
        val latest = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 2)
        withTimeout(1_000) {
            grid.followLatestDeviceImage(old, latest)
            grid.followLatestDeviceImage(latest, latest)
            grid.followLatestDeviceImage(latest, null)
        }
        assertEquals(4, grid.firstVisibleItemIndex)
        assertEquals(16, grid.firstVisibleItemScrollOffset)
    }

    @Test fun selectedPhotoAccessIsDistinctFromFullAccessAndRevocation() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadow = shadowOf(app)
        shadow.denyPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertEquals(0, galleryAccess(app))
        shadow.grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertEquals(1, galleryAccess(app))
        shadow.grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        assertEquals(2, galleryAccess(app))
        shadow.denyPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertEquals(0, galleryAccess(app))
        assertEquals(setOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED), galleryPermissions().toSet())
    }

    @Test @Config(sdk = [28]) fun olderDevicesUseTheirStorageReadPermission() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        assertEquals(0, galleryAccess(app))
        shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        assertEquals(2, galleryAccess(app))
        assertArrayEquals(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), galleryPermissions())
    }

    @Test fun newestPhotosComeFirstEvenWhenProviderOnlyHonorsSqlSortingAndIgnoresLimit() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val provider = Robolectric.buildContentProvider(ImageProvider::class.java).create("media").get()
        // 默认顺序为最早入库；同一秒的新照片需要按 ID 倒序，避免新照片落在旧图之后。
        provider.rows = (1L..80L).map { ImageRow(it, it / 2, it + 100) }
        val images = queryDeviceImages(app, 60)
        assertEquals((80L downTo 21L).toList(), images.map { it.id })
        assertEquals(180L, images.first().modified)
        assertEquals(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 80), images.first().uri)
        assertTrue(requireNotNull(provider.lastCursor).isClosed)
    }

    @Test @Config(sdk = [28]) fun imageQueryAlsoSortsAndCapsResultsOnOlderAndroid() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val provider = Robolectric.buildContentProvider(ImageProvider::class.java).create("media").get()
        provider.rows = listOf(ImageRow(3, 20, 21), ImageRow(9, 40, 41), ImageRow(6, 40, 42))
        assertEquals(listOf(9L, 6L), queryDeviceImages(app, 2).map { it.id })
        assertTrue(requireNotNull(provider.lastCursor).isClosed)
    }

    @Test fun addedModifiedAndDeletedPhotosRefreshWithoutReopeningThePanelAndObserverIsReleased() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val provider = Robolectric.buildContentProvider(ImageProvider::class.java).create("media").get()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val resolver = app.contentResolver
        val results = Channel<List<com.txl.mebot.ui.schedule.DeviceImage>>(Channel.UNLIMITED)
        provider.rows = listOf(ImageRow(1, 10, 10))
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            deviceImageChanges(app).collect { results.send(queryDeviceImages(app, 60)) }
        }
        try {
            suspend fun next() = withTimeout(5_000) { results.receive() }
            fun changed(id: Long) {
                resolver.notifyChange(ContentUris.withAppendedId(collection, id), null)
                shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            assertEquals(listOf(1L), next().map { it.id })
            assertEquals(1, shadowOf(resolver).getContentObservers(collection).size)
            provider.rows = listOf(ImageRow(1, 10, 10), ImageRow(2, 20, 20))
            changed(2)
            assertEquals(listOf(2L, 1L), next().map { it.id })
            provider.rows = listOf(ImageRow(1, 10, 10), ImageRow(2, 20, 30))
            changed(2)
            assertEquals(30L, next().first().modified)
            provider.rows = listOf(ImageRow(1, 10, 10))
            changed(2)
            assertEquals(listOf(1L), next().map { it.id })
        } finally { job.cancelAndJoin() }
        assertTrue(shadowOf(resolver).getContentObservers(collection).isEmpty())
        // 面板关闭期间新增照片；下次打开仍会立即重新查询，而不复用旧快照。
        provider.rows = provider.rows + ImageRow(3, 40, 40)
        val reopened = async { deviceImageChanges(app).collect { results.send(queryDeviceImages(app, 60)) } }
        try {
            assertEquals(listOf(3L, 1L), withTimeout(5_000) { results.receive() }.map { it.id })
        } finally { reopened.cancelAndJoin() }
        assertTrue(shadowOf(resolver).getContentObservers(collection).isEmpty())
    }

    @Test fun unavailableProviderIsAnErrorInsteadOfAFalseEmptyGallery() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val provider = Robolectric.buildContentProvider(ImageProvider::class.java).create("media").get()
        provider.unavailable = true
        try {
            queryDeviceImages(app, 60)
            fail("Unavailable media provider must allow the panel to show retry")
        } catch (_: IllegalStateException) { }
        provider.unavailable = false
        provider.rows = listOf(ImageRow(1, 10, 10))
        assertEquals(listOf(1L), queryDeviceImages(app, 60).map { it.id })
    }

    data class ImageRow(val id: Long, val added: Long, val modified: Long)

    /** 模拟只支持 SQL 排序、忽略结构化排序和数量限制的设备媒体提供方。 */
    class ImageProvider : ContentProvider() {
        @Volatile var rows: List<ImageRow> = emptyList()
        @Volatile var unavailable = false
        @Volatile var lastCursor: MatrixCursor? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = "vnd.android.cursor.dir/image"
        override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor? =
            query(uri, projection, null, null, queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER))
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            if (unavailable) return null
            val sorted = if (sortOrder == "date_added DESC, _id DESC") rows.sortedWith(compareByDescending<ImageRow> { it.added }.thenByDescending { it.id }) else rows
            val columns = requireNotNull(projection)
            return MatrixCursor(columns).also { cursor ->
                sorted.forEach { row -> cursor.addRow(columns.map { column ->
                    when (column) {
                        MediaStore.Images.Media._ID -> row.id
                        MediaStore.Images.Media.DATE_ADDED -> row.added
                        MediaStore.Images.Media.DATE_MODIFIED -> row.modified
                        else -> error("Unexpected projection")
                    }
                }.toTypedArray()) }
                lastCursor = cursor
            }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only test provider")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read only test provider")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read only test provider")
    }
}
