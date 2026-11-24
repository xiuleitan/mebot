package com.txl.mebot.data.archive

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.txl.mebot.data.local.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ArchiveStorage(val database: RoomDatabase, internal val dao: ArchiveDao, internal val cipher: ArchiveCipher) {
    internal suspend fun <T> read(block: suspend () -> T): T = withContext(Dispatchers.IO) { database.withTransaction { block() } }
    internal suspend fun <T> write(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        database.withTransaction {
            cipher.ensureAvailable(allowCreate = !dao.hasEncryptedData())
            block()
        }
    }
    internal fun encrypt(json: JSONObject, purpose: String): ByteArray {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        return try { cipher.encrypt(bytes, purpose) } finally { bytes.fill(0) }
    }
    internal fun decrypt(bytes: ByteArray, purpose: String): JSONObject {
        val plain = cipher.decrypt(bytes, purpose)
        return try { JSONObject(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
    }
    internal fun content(record: ArchiveRecordEntity) = ArchiveCodec.content(decrypt(requireNotNull(record.payload), "record:${record.id}"))
    internal fun record(record: ArchiveRecordEntity) = ArchiveRecord(record.id, content(record), record.revision, record.createdAt, record.updatedAt)
    internal fun draft(row: ArchiveDraftEntity): ArchiveDraft {
        require(row.formatVersion == 1) { "不支持的草稿格式，原数据已保留" }
        return ArchiveDraft(row.sessionId, ArchiveCodec.draft(decrypt(row.payload, "draft:${row.sessionId}")), row.revision, row.baseRecordRevision, row.activeRequestId)
    }
    internal suspend fun activeSession(id: String): ArchiveSessionEntity = requireNotNull(dao.session(id)) { "档案会话不存在" }.also {
        check(it.state == ArchiveSessionState.ACTIVE) { "该会话对应的档案已删除，请新建会话" }
        if (it.recordId != null) check(dao.record(it.recordId)?.deletedAt == null) { "档案已删除" }
    }
    internal suspend fun enqueueIndex(record: ArchiveRecordEntity, now: Long) {
        dao.clearEmbeddings(record.id)
        val fingerprint = dao.settings()?.fingerprint
        val job = ArchiveIndexJobEntity(record.id, record.revision, fingerprint,
            if (fingerprint == null) ArchiveIndexState.WAITING_CONFIGURATION else ArchiveIndexState.QUEUED, updatedAt = now)
        if (dao.job(record.id) == null) dao.insertJob(job) else dao.updateJob(job)
    }
}
