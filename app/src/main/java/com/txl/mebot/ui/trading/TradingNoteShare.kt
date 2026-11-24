package com.txl.mebot.ui.trading

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.StringRes
import com.txl.mebot.R
import com.txl.mebot.data.trading.TradingContract
import com.txl.mebot.data.trading.TradingInterval
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class TradingNoteExport(val name: String, val png: ByteArray, val text: String)

internal enum class TradingNoteShareResult(@StringRes val message: Int, val error: Boolean = false) {
    DONE(R.string.trading_note_share_done),
    IMAGE_ONLY(R.string.trading_note_share_image_saved),
    COPY_FAILED(R.string.trading_note_share_copy_failed, error = true),
    SAVE_FAILED(R.string.trading_note_share_save_failed, error = true),
    CHART_UNAVAILABLE(R.string.trading_note_share_chart_unavailable, error = true),
}

internal fun tradingNoteExportName(contract: TradingContract, interval: TradingInterval): String {
    val symbol = contract.symbol.filter(Char::isLetterOrDigit)
    val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
    return "mebot_${symbol}_${interval.apiValue}_$time.png"
}

internal class TradingNoteShare(private val context: Context) {
    suspend fun finish(export: TradingNoteExport, document: Uri? = null): TradingNoteShareResult {
        try {
            withContext(Dispatchers.IO) { writeImage(export, document) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return TradingNoteShareResult.SAVE_FAILED
        }
        if (export.text.isBlank()) return TradingNoteShareResult.IMAGE_ONLY
        return withContext(Dispatchers.Main.immediate) {
            try {
                val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
                val label = context.getString(R.string.trading_note_accessibility)
                clipboard.setPrimaryClip(ClipData.newPlainText(label, export.text))
                TradingNoteShareResult.DONE
            } catch (_: Exception) {
                TradingNoteShareResult.COPY_FAILED
            }
        }
    }

    suspend fun discardDocument(uri: Uri) = withContext(NonCancellable + Dispatchers.IO) {
        if (uri.scheme == "content" && uri.authority != "${context.packageName}.attachments") {
            deleteCreatedImage(uri, document = true)
        }
    }

    private suspend fun writeImage(export: TradingNoteExport, document: Uri?) {
        val resolver = context.contentResolver
        val uri = document ?: createGalleryEntry(export.name)
        require(uri.scheme == "content" && uri.authority != "${context.packageName}.attachments")
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")).use { output -> output.write(export.png) }
            currentCoroutineContext().ensureActive()
            if (document == null) {
                val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                check(resolver.update(uri, published, null, null) == 1)
            }
        } catch (error: Exception) {
            withContext(NonCancellable) { deleteCreatedImage(uri, document = document != null) }
            throw error
        }
    }

    private fun createGalleryEntry(name: String): Uri {
        if (Build.VERSION.SDK_INT < 29) error("Use the document picker on this Android version")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/mebot")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return checkNotNull(context.contentResolver.insert(collection, values))
    }

    private fun deleteCreatedImage(uri: Uri, document: Boolean) {
        runCatching {
            val resolver = context.contentResolver
            if (document && DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(resolver, uri)
            else resolver.delete(uri, null, null)
        }
    }
}
