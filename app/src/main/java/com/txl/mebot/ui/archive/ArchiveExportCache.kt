package com.txl.mebot.ui.archive

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 有界的查看副本；共享 URI 不含业务文件名，原件从不进入此目录。 */
internal class ArchiveExportCache(private val context: Context, private val maxCount: Int = 10,
    private val maxBytes: Long = 200L * 1024 * 1024, private val now: () -> Long = System::currentTimeMillis) {
    val staging = File(context.cacheDir, "archive_export_staging")
    private val root = File(context.cacheDir, "archive_exports")

    suspend fun <T> locked(block: suspend ArchiveExportCache.() -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }

    fun clean(reserveBytes: Long = 0, reserveCount: Int = 0) {
        require(reserveBytes in 0..maxBytes && reserveCount in 0..maxCount)
        val cutoff = now() - TimeUnit.HOURS.toMillis(1)
        val entries = root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.lastModified() }.toMutableList()
        entries.filter { it.lastModified() <= cutoff }.toList().forEach { directory -> remove(directory.name); entries.remove(directory) }
        var bytes = entries.sumOf { directory -> directory.listFiles().orEmpty().sumOf { it.length() } }
        while (entries.size + reserveCount > maxCount || bytes + reserveBytes > maxBytes) {
            val oldest = entries.removeAt(0)
            bytes -= oldest.listFiles().orEmpty().sumOf { it.length() }
            remove(oldest.name)
        }
    }
    fun clearInterruptedStaging() { staging.listFiles()?.forEach { it.deleteRecursively() } }
    fun destination(name: String): File {
        require(name.matches(Regex("[a-f0-9-]{36}")))
        check(root.isDirectory || root.mkdirs())
        return File(root, name).also { check(it.canonicalFile.parentFile == root.canonicalFile) }
    }
    fun remove(name: String) {
        val directory = destination(name)
        directory.listFiles()?.filter(File::isFile)?.forEach { file ->
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        check(!directory.exists() || directory.deleteRecursively()) { "查看副本暂未清理" }
    }

    companion object { private val lock = Mutex() }
}
