package com.txl.mebot.data.archive

import android.content.Context
import com.txl.mebot.data.local.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 档案原件使用独立的加密文件格式，普通媒体缓存清理不能接触该目录。 */
class ArchiveAttachmentRepository(context: Context, private val storage: ArchiveStorage, private val now: () -> Long = System::currentTimeMillis) {
    private val originals = File(context.filesDir, "archive/originals")
    private val staging = File(context.filesDir, "archive/staging")
    private val previews = File(context.cacheDir, "archive_previews")
    private val filesDir = context.filesDir
    private val mutex = Mutex()
    private val dao get() = storage.dao

    suspend fun import(
        sessionId: String, name: String, mimeType: String, assetId: String = UUID.randomUUID().toString(),
        maxBytes: Long = 100L * 1024 * 1024, source: () -> InputStream,
    ): String = withContext(Dispatchers.IO) { mutex.withLock {
        require(assetId.matches(Regex("[A-Za-z0-9_-]{1,100}")) && name.length <= 1_000 && mimeType.length <= 200 && maxBytes in 1..100L * 1024 * 1024)
        storage.write { storage.activeSession(sessionId) }
        check(staging.isDirectory || staging.mkdirs()); check(originals.isDirectory || originals.mkdirs())
        val temporary = File.createTempFile("import-", ".part", staging)
        val relativePath = "archive/originals/$assetId.bin"
        val published = original(relativePath)
        var moved = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val coroutine = currentCoroutineContext()
            val count = source().use { input -> FileOutputStream(temporary).use { output ->
                val size = storage.cipher.encryptStream(input, output, "asset-file:$assetId", maxBytes) { buffer, length ->
                    coroutine.ensureActive(); digest.update(buffer, 0, length)
                }
                output.flush(); output.fd.sync(); size
            } }
            require(count > 0) { "附件不能为空" }
            val info = ArchiveAssetInfo(name, mimeType, count, digest.digest().joinToString("") { "%02x".format(it) })
            storage.write {
                storage.activeSession(sessionId)
                dao.asset(assetId)?.let { existing ->
                    require(ArchiveCodec.asset(storage.decrypt(existing.payload, "asset:$assetId")) == info &&
                        dao.sessionAssets(sessionId).any { it.assetId == assetId } && original(existing.relativePath).isFile) { "附件标识已使用或原件不可用" }
                    return@write
                }
                // 文件先持久发布，事务失败/进程中断后由孤儿扫描回收，绝不留下指向临时地址的正式记录。
                check(!published.exists()) { "附件发布标识冲突" }
                check(temporary.renameTo(published)) { "附件保存失败" }; moved = true
                dao.insertAsset(ArchiveAssetEntity(assetId, relativePath, storage.encrypt(ArchiveCodec.asset(info), "asset:$assetId"), published.length(), now()))
                val position = (dao.sessionAssets(sessionId).maxOfOrNull { it.position } ?: -1) + 1
                dao.insertSessionAsset(ArchiveSessionAssetEntity(sessionId, assetId, position, true))
            }
            assetId
        } finally {
            temporary.delete()
            if (moved) withContext(NonCancellable) { storage.read {
                if (dao.pathReferences(relativePath) == 0) dao.enqueueCleanup(ArchiveFileCleanupEntity(relativePath, now()))
            } }
        }
    } }

    suspend fun metadata(assetId: String): ArchiveAssetInfo = storage.read {
        ArchiveCodec.asset(storage.decrypt(requireNotNull(dao.asset(assetId)).payload, "asset:$assetId"))
    }

    /** 认证通过后才把临时明文交给调用方，退出（含取消、失败）时删除；不能把此路径存为原件。 */
    suspend fun <T> withDecryptedFile(assetId: String, block: suspend (File) -> T): T = withContext(Dispatchers.IO) { mutex.withLock {
        val asset = storage.read { requireNotNull(dao.asset(assetId)) }
        check(previews.isDirectory || previews.mkdirs())
        val temporary = File.createTempFile("private-", ".part", previews)
        try {
            original(asset.relativePath).inputStream().use { input -> FileOutputStream(temporary).use { output ->
                storage.cipher.decryptStream(input, output, "asset-file:$assetId")
                output.flush()
            } }
            currentCoroutineContext().ensureActive()
            block(temporary)
        } finally { temporary.delete() }
    } }

    suspend fun discardPending(sessionId: String, assetId: String) = withContext(Dispatchers.IO) { mutex.withLock {
        storage.read {
            storage.activeSession(sessionId)
            val link = dao.sessionAssets(sessionId).find { it.assetId == assetId } ?: return@read
            check(link.pending) { "已发送附件不能按草稿移除" }
            check(dao.messageReferences(sessionId, assetId) == 0) { "附件已被聊天引用" }
            dao.removeSessionAsset(sessionId, assetId)
            if (dao.sessionReferences(assetId) == 0 && dao.recordReferences(assetId) == 0) {
                val asset = requireNotNull(dao.asset(assetId))
                dao.deleteAsset(assetId); dao.enqueueCleanup(ArchiveFileCleanupEntity(asset.relativePath, now()))
            }
        }
        cleanQueue()
    } }

    suspend fun clearPreviewCache() = withContext(Dispatchers.IO) { mutex.withLock { previews.deleteRecursively(); Unit } }

    /** 启动恢复：只扫描自己拥有的目录；文件发布与注册之间中断不影响其他 Bot 原件。 */
    suspend fun recoverFiles() = withContext(Dispatchers.IO) { mutex.withLock {
        previews.deleteRecursively(); staging.listFiles()?.forEach { it.delete() }
        storage.read {
            val referenced = dao.assetPaths().toSet()
            originals.listFiles()?.filter { it.isFile }?.forEach { file ->
                val relativePath = "archive/originals/${file.name}"
                if (relativePath !in referenced) dao.enqueueCleanup(ArchiveFileCleanupEntity(relativePath, now()))
            }
        }
        cleanQueue()
    } }

    private suspend fun cleanQueue() {
        while (true) {
            val batch = storage.read { dao.cleanupQueue(100) }
            if (batch.isEmpty()) return
            var removed = 0
            batch.forEach { task -> storage.read {
                if (dao.pathReferences(task.relativePath) > 0) { dao.finishCleanup(task.relativePath); removed++ }
                else {
                    val file = original(task.relativePath)
                    if (!file.exists() || file.delete()) { dao.finishCleanup(task.relativePath); removed++ }
                }
            } }
            if (removed == 0) return
        }
    }

    private fun original(path: String): File {
        require(path.matches(Regex("archive/originals/[A-Za-z0-9_-]{1,100}\\.bin"))) { "无效档案文件路径" }
        return File(filesDir, path).also { check(it.canonicalFile.parentFile == originals.canonicalFile) }
    }
}
