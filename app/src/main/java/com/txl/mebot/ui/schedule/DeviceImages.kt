package com.txl.mebot.ui.schedule

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

internal data class DeviceImage(val uri: Uri, val added: Long, val id: Long, val modified: Long = 0)

/** 先订阅再发出首次刷新，避免查询期间新增的照片被漏掉；仅面板可见时收集。 */
internal fun deviceImageChanges(context: Context) = callbackFlow {
    val resolver = context.contentResolver
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { trySend(Unit) }
    }
    resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
    trySend(Unit)
    awaitClose { resolver.unregisterContentObserver(observer) }
}.buffer(Channel.CONFLATED)

internal suspend fun queryDeviceImages(context: Context, limit: Int): List<DeviceImage> = withContext(Dispatchers.IO) {
    require(limit > 0)
    val result = ArrayList<DeviceImage>()
    val arguments = Bundle().apply {
        // 显式传 SQL 排序，避免设备媒体提供方忽略结构化排序后返回最早的照片。
        putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
            "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC")
        putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
    }
    context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_MODIFIED),
        arguments, null)?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
        while (result.size < limit && cursor.moveToNext()) {
            coroutineContext.ensureActive()
            val id = cursor.getLong(idColumn)
            result += DeviceImage(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                cursor.getLong(addedColumn), id, cursor.getLong(modifiedColumn))
        }
    } ?: error("Media query returned no cursor")
    result
}
