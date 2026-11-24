package com.txl.mebot.ui.trading

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class TradingOverviewBounds(val header: Rect, val overview: Rect, val viewport: Rect) {
    val height: Int get() = header.height() + overview.height()

    fun sourceRect(copied: Int): Rect? {
        if (header.isEmpty || overview.isEmpty || viewport.isEmpty ||
            header.left != overview.left || header.right != overview.right ||
            overview.left < viewport.left || overview.right > viewport.right || copied !in 0 until height
        ) return null

        val bottom = minOf(overview.bottom, viewport.bottom)
        val top = if (copied == 0) {
            if (header.bottom != overview.top || overview.top < viewport.top || bottom <= overview.top) return null
            header.top
        } else {
            if (copied < header.height()) return null
            val nextRow = overview.top + copied - header.height()
            if (nextRow < maxOf(viewport.top, header.bottom)) return null
            nextRow
        }
        return if (bottom > top) Rect(header.left, top, header.right, bottom) else null
    }
}

internal class TradingOverviewCapture {
    var header: LayoutCoordinates? = null
    var overview: LayoutCoordinates? = null
    var viewport: LayoutCoordinates? = null
    private var capturing by mutableStateOf(false)

    fun viewportClipBottom(): Float? {
        if (!capturing) return null
        val frame = bounds() ?: return 0f
        return (frame.overview.bottom - frame.viewport.top).coerceIn(0, frame.viewport.height()).toFloat()
    }

    suspend fun png(window: Window, list: LazyListState): ByteArray? = withContext(Dispatchers.Main.immediate) {
        if (capturing) return@withContext null
        val previousIndex = list.firstVisibleItemIndex
        val previousOffset = list.firstVisibleItemScrollOffset
        capturing = true
        val image = try {
            if (previousIndex != 0 || previousOffset != 0) list.scrollToItem(0)
            capture(window, list)
        } finally {
            capturing = false
            if (list.firstVisibleItemIndex != previousIndex || list.firstVisibleItemScrollOffset != previousOffset) {
                list.requestScrollToItem(previousIndex, previousOffset)
            }
        } ?: return@withContext null

        try {
            withContext(Dispatchers.Default) {
                ByteArrayOutputStream().use { output ->
                    if (image.compress(Bitmap.CompressFormat.PNG, 100, output)) output.toByteArray() else null
                }
            }
        } finally {
            image.recycle()
        }
    }

    private suspend fun capture(window: Window, list: LazyListState): Bitmap? {
        if (!awaitFrame(window.decorView)) return null
        val initial = bounds() ?: return null
        val first = copySlice(window, initial, 0) ?: return null
        if (first.height == initial.height) return first

        val result = Bitmap.createBitmap(first.width, initial.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        var copied = first.height
        canvas.drawBitmap(first, 0f, 0f, null)
        first.recycle()
        var complete = false
        try {
            while (copied < initial.height) {
                // Keep the fixed quote header out of subsequent scroll slices.
                list.scrollToItem(0, copied - initial.header.height())
                if (!awaitFrame(window.decorView)) return null
                val current = bounds() ?: return null
                if (current.header != initial.header || current.viewport != initial.viewport ||
                    current.overview.height() != initial.overview.height()
                ) return null
                val slice = copySlice(window, current, copied) ?: return null
                canvas.drawBitmap(slice, 0f, copied.toFloat(), null)
                copied += slice.height
                slice.recycle()
            }
            complete = true
            return result
        } finally {
            if (!complete) result.recycle()
        }
    }

    private fun bounds(): TradingOverviewBounds? {
        return TradingOverviewBounds(
            header.windowRect() ?: return null,
            overview.windowRect() ?: return null,
            viewport.windowRect() ?: return null,
        )
    }

    private suspend fun copySlice(window: Window, snapshot: TradingOverviewBounds, copied: Int): Bitmap? {
        val rect = snapshot.sourceRect(copied) ?: return null
        val decor = window.decorView
        if (!Rect(0, 0, decor.width, decor.height).contains(rect)) return null
        val image = copyWindow(window, rect) ?: return null
        // A relayout between measurement and the copied frame can change the bottom of the overview.
        if (bounds() != snapshot) {
            image.recycle()
            return null
        }
        return image
    }
}

private fun LayoutCoordinates?.windowRect(): Rect? {
    if (this?.isAttached != true) return null
    val origin = positionInWindow()
    if (!origin.x.isFinite() || !origin.y.isFinite()) return null
    val left = origin.x.roundToInt()
    val top = origin.y.roundToInt()
    return Rect(left, top, left + size.width, top + size.height)
}

private suspend fun awaitFrame(view: View): Boolean {
    if (!view.isAttachedToWindow) return false
    return withTimeoutOrNull(2_500L) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val observer = view.viewTreeObserver
            if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated && observer.isAlive) {
                val committed = Runnable { if (continuation.isActive) continuation.resume(Unit) }
                observer.registerFrameCommitCallback(committed)
                continuation.invokeOnCancellation {
                    view.post { if (observer.isAlive) observer.unregisterFrameCommitCallback(committed) }
                }
                view.invalidate()
            } else {
                view.postOnAnimation {
                    view.postOnAnimation { if (continuation.isActive) continuation.resume(Unit) }
                }
            }
        }
    } != null
}

private suspend fun copyWindow(window: Window, area: Rect): Bitmap? = withTimeoutOrNull(3_000L) {
    suspendCancellableCoroutine<Bitmap?> { continuation ->
        val image = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, Rect(area), image, { result ->
                if (result == PixelCopy.SUCCESS && continuation.isActive) {
                    continuation.resume(image)
                } else {
                    image.recycle()
                    if (continuation.isActive) continuation.resume(null)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: IllegalArgumentException) {
            image.recycle()
            continuation.resume(null)
        }
    }
}
