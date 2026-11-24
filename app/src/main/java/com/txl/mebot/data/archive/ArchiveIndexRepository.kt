package com.txl.mebot.data.archive

import com.txl.mebot.data.local.*
import kotlinx.coroutines.flow.combine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import kotlin.math.sqrt

data class ArchiveIndexLease(val recordId: String, val recordRevision: Long, val fingerprint: String, val token: String)
data class ArchiveEmbeddingPart(val text: String, val vector: FloatArray) {
    override fun toString() = "ArchiveEmbeddingPart([protected], dimensions=${vector.size})"
}
data class ArchiveStoredVector(val recordId: String, val recordRevision: Long, val part: Int, val vector: FloatArray)
data class ArchiveEmbeddingConfiguration(val endpoint: String, val model: String, val dimensions: Int, val encoding: String = "legacy-v1")

/** 只负责可重建索引的生命周期和验证；模型调用与语义排序在后续搜索步骤接入。 */
class ArchiveIndexRepository(private val storage: ArchiveStorage, private val now: () -> Long = System::currentTimeMillis) {
    private val dao get() = storage.dao

    suspend fun configuration(): ArchiveIndexSettingsEntity? = storage.read { dao.settings() }

    suspend fun configure(configuration: ArchiveEmbeddingConfiguration?, expectedRevision: Long): ArchiveIndexSettingsEntity = storage.write {
        val fingerprint = configuration?.let { fingerprint(it.endpoint, it.model, it.dimensions, it.encoding) }
        val old = dao.settings()
        if ((old?.revision ?: 0L) != expectedRevision) throw ArchiveConflict()
        if (old != null && old.fingerprint == fingerprint) return@write old
        val time = now()
        val updated = ArchiveIndexSettingsEntity(fingerprint = fingerprint, dimensions = configuration?.dimensions, revision = expectedRevision + 1, updatedAt = time,
            configurationPayload = old?.configurationPayload)
        if (old == null) dao.insertSettings(updated) else dao.updateSettings(updated)
        dao.clearAllEmbeddings()
        dao.activeRecords().forEach { storage.enqueueIndex(it, time) }
        updated
    }

    suspend fun queued(limit: Int = 20): List<ArchiveIndexJobEntity> = storage.read { require(limit in 1..100); dao.queuedJobs(limit) }

    suspend fun claim(recordId: String): ArchiveIndexLease? = storage.write {
        val job = dao.job(recordId) ?: return@write null
        val record = dao.record(recordId) ?: return@write null
        val fingerprint = dao.settings()?.fingerprint ?: return@write null
        if (job.state !in setOf(ArchiveIndexState.QUEUED, ArchiveIndexState.FAILED) || job.attempts >= 3 || record.deletedAt != null ||
            job.recordRevision != record.revision || job.fingerprint != fingerprint) return@write null
        val token = UUID.randomUUID().toString()
        dao.updateJob(job.copy(state = ArchiveIndexState.RUNNING, leaseToken = token, attempts = job.attempts + 1, failureCode = null, updatedAt = now()))
        ArchiveIndexLease(recordId, record.revision, fingerprint, token)
    }

    suspend fun complete(lease: ArchiveIndexLease, parts: List<ArchiveEmbeddingPart>): Boolean = storage.write {
        val job = current(lease) ?: return@write false
        require(parts.isNotEmpty() && parts.size <= 100)
        val dimensions = parts.first().vector.size
        require(dimensions == dao.settings()?.dimensions && parts.all { it.vector.size == dimensions && it.text.length <= 200_000 })
        val rows = parts.mapIndexed { index, part ->
            val plain = normalizedBytes(part.vector)
            val purpose = purpose(lease.recordId, lease.recordRevision, lease.fingerprint, index)
            val vector = try { storage.cipher.encrypt(plain, "$purpose:vector") } finally { plain.fill(0) }
            ArchiveEmbeddingEntity(lease.recordId, index, lease.recordRevision, lease.fingerprint, dimensions, vector,
                storage.encrypt(JSONObject().put("text", part.text), "$purpose:text"))
        }
        dao.clearEmbeddings(lease.recordId); dao.insertEmbeddings(rows)
        dao.updateJob(job.copy(state = ArchiveIndexState.READY, leaseToken = null, failureCode = null, updatedAt = now()))
        true
    }

    suspend fun fail(lease: ArchiveIndexLease, code: String): Boolean = storage.write {
        require(code.matches(Regex("[A-Z0-9_]{1,64}"))) // 不把服务响应、个人内容或凭据写入普通错误列。
        val job = current(lease) ?: return@write false
        dao.updateJob(job.copy(state = ArchiveIndexState.FAILED, leaseToken = null, failureCode = code, updatedAt = now()))
        true
    }

    /** 仅在进程启动恢复时调用；被撤销执行令牌的旧响应不能再落库。 */
    suspend fun recoverInterrupted(): Int = storage.read { dao.recoverJobs(now()) }

    suspend fun vectors(): List<ArchiveStoredVector> = storage.read {
        dao.effectiveEmbeddings().map { row ->
            val plain = storage.cipher.decrypt(row.vector, "${purpose(row.recordId, row.recordRevision, row.fingerprint, row.part)}:vector")
            val vector = try {
                check(plain.size == row.dimensions * 4) { "无效向量格式" }
                val buffer = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(row.dimensions) { buffer.float }.also { check(it.all(Float::isFinite)) }
            } finally { plain.fill(0) }
            ArchiveStoredVector(row.recordId, row.recordRevision, row.part, vector)
        }
    }

    internal fun changes() = combine(dao.observeIndexJobs(), dao.observeIndexSettingsRevision()) { _, _ -> Unit }
    suspend fun summary(): ArchiveIndexSummary = storage.read {
        val jobs = dao.indexJobs()
        ArchiveIndexSummary(dao.recordCount(), jobs.count { it.state == ArchiveIndexState.READY }, jobs.count { it.state == ArchiveIndexState.QUEUED },
            jobs.count { it.state == ArchiveIndexState.RUNNING }, jobs.count { it.state == ArchiveIndexState.FAILED },
            jobs.count { it.state == ArchiveIndexState.WAITING_CONFIGURATION },
            jobs.firstOrNull { it.state == ArchiveIndexState.FAILED }?.failureCode?.let { runCatching { ArchiveSemanticFailure.valueOf(it) }.getOrNull() })
    }
    internal suspend fun pending(): List<ArchiveIndexJobEntity> = storage.read { dao.runnableIndexJobs() }
    internal suspend fun isCurrent(lease: ArchiveIndexLease): Boolean = storage.read { current(lease) != null }
    suspend fun retryFailed() = storage.write {
        val time = now()
        dao.indexJobs().filter { it.state == ArchiveIndexState.FAILED || it.state == ArchiveIndexState.QUEUED && it.attempts >= 3 }
            .forEach { dao.updateJob(it.copy(state = ArchiveIndexState.QUEUED, attempts = 0, failureCode = null, leaseToken = null, updatedAt = time)) }
    }
    suspend fun rebuild() = storage.write {
        val settings = requireNotNull(dao.settings()); check(settings.fingerprint != null)
        dao.updateSettings(settings.copy(revision = settings.revision + 1, updatedAt = now()))
        val time = now(); dao.clearAllEmbeddings(); dao.activeRecords().forEach { storage.enqueueIndex(it, time) }
    }
    internal suspend fun vectorPage(fingerprint: String, afterId: String, afterPart: Int): List<ArchiveStoredVector> = storage.read {
        dao.effectiveEmbeddingPage(fingerprint, afterId, afterPart, 128).map { row ->
            val plain = storage.cipher.decrypt(row.vector, "${purpose(row.recordId, row.recordRevision, row.fingerprint, row.part)}:vector")
            val vector = try {
                if (plain.size != row.dimensions * 4) throw ArchiveSemanticException(ArchiveSemanticFailure.LOCAL_STORAGE)
                val buffer = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
                archiveUnitVector(FloatArray(row.dimensions) { buffer.float })
            } finally { plain.fill(0) }
            ArchiveStoredVector(row.recordId, row.recordRevision, row.part, vector)
        }
    }
    internal suspend fun searchItem(hit: ArchiveSearchHit, fingerprint: String): ArchiveSearchItem? = storage.read {
        val row = dao.effectiveEmbedding(hit.recordId, hit.part, hit.revision, fingerprint) ?: return@read null
        val record = dao.record(hit.recordId)?.takeIf { it.deletedAt == null && it.revision == hit.revision } ?: return@read null
        val text = storage.decrypt(row.text, "${purpose(row.recordId, row.recordRevision, row.fingerprint, row.part)}:text").getString("text")
        val snippet = text.lineSequence().filterNot { it.startsWith("标题：") || it.startsWith("类型：") || it.startsWith("标签：") }.joinToString("\n").take(220)
        ArchiveSearchItem(storage.record(record), snippet, hit.score)
    }

    private suspend fun current(lease: ArchiveIndexLease): ArchiveIndexJobEntity? {
        val job = dao.job(lease.recordId) ?: return null
        val record = dao.record(lease.recordId) ?: return null
        return job.takeIf { it.state == ArchiveIndexState.RUNNING && it.leaseToken == lease.token && it.recordRevision == lease.recordRevision &&
            it.fingerprint == lease.fingerprint && record.deletedAt == null && record.revision == lease.recordRevision && dao.settings()?.fingerprint == lease.fingerprint }
    }

    companion object {
        /** 调用方须把实际 endpoint / 模型 / 维数一起纳入，配置切换不能复用旧向量。 */
        fun fingerprint(endpoint: String, model: String, dimensions: Int, encoding: String = "legacy-v1"): String {
            require(endpoint.isNotBlank() && model.isNotBlank() && dimensions in 1..8192)
            val value = JSONObject().put("endpoint", endpoint.trim().trimEnd('/')).put("model", model.trim()).put("dimensions", dimensions).put("format", "float32-unit-v1")
            if (encoding != "legacy-v1") value.put("encoding", encoding)
            return MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        }
        private fun purpose(id: String, revision: Long, fingerprint: String, part: Int) = "embedding:$id:$revision:$fingerprint:$part"
        private fun normalizedBytes(vector: FloatArray): ByteArray {
            require(vector.all(Float::isFinite)) { "向量含无效数值" }
            val norm = sqrt(vector.sumOf { it.toDouble() * it.toDouble() })
            require(norm.isFinite() && norm > 0) { "向量不能为空" }
            return ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { vector.forEach { putFloat((it / norm).toFloat()) } }.array()
        }
    }
}
