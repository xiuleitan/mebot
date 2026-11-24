package com.txl.mebot.data.archive

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 页面、启动恢复与 Worker 共用；先恢复一次，再允许任何执行者领取任务。 */
class ArchiveSemanticEngine(val settings: ArchiveEmbeddingSettingsRepository, val index: ArchiveIndexRepository,
    private val records: ArchiveRepository, private val client: ArchiveEmbeddingClient = QwenArchiveEmbeddingClient(),
    private val enqueue: () -> Unit = {}, private val now: () -> Long = System::currentTimeMillis) {
    private val initialization = Mutex(); private var initialized = false
    private val indexing = Mutex()
    private class Cached(val fingerprint: String, val query: String, val at: Long, val vector: FloatArray)
    private val cacheLock = Mutex(); private val queries = ArrayDeque<Cached>()

    suspend fun initialize() = initialization.withLock {
        if (!initialized) { index.recoverInterrupted(); initialized = true }
    }
    suspend fun start() { initialize(); nudge() }
    suspend fun nudge() {
        if (settings.load().values.enabled && index.pending().isNotEmpty()) {
            try { enqueue() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Room内的待办保留，下次启动/页面恢复/手动重试重新登记。 */ }
        }
    }
    suspend fun saveSettings(values: ArchiveEmbeddingSettings, key: ArchiveEmbeddingKeyChange, expectedRevision: Long): ArchiveSemanticSettings {
        initialize()
        val saved = settings.save(values, key, expectedRevision)
        cacheLock.withLock { queries.clear() }
        index.retryFailed(); nudge()
        return saved
    }
    suspend fun retryIndex() { initialize(); if (settings.load().values.enabled) { index.retryFailed(); nudge() } }
    suspend fun rebuild() { initialize(); index.rebuild(); cacheLock.withLock { queries.clear() }; nudge() }

    /** 返回是否仍有可执行任务；暂态失败最多自动尝试三次，之后等待手动操作。 */
    suspend fun runQueue(): Boolean = indexing.withLock {
        initialize()
        if (!settings.load().values.enabled) return@withLock false
        for (job in index.pending()) {
            currentCoroutineContext().ensureActive()
            val lease = index.claim(job.recordId) ?: continue
            try {
                val access = settings.access()
                if (lease.fingerprint != ArchiveEmbeddingSettingsRepository.fingerprint(access.settings)) throw ArchiveSemanticException(ArchiveSemanticFailure.CHANGED)
                val record = records.record(lease.recordId)
                if (record == null || record.revision != lease.recordRevision) throw ArchiveSemanticException(ArchiveSemanticFailure.CHANGED)
                val parts = mutableListOf<ArchiveEmbeddingPart>()
                for (text in archiveSearchParts(record.content)) {
                    currentCoroutineContext().ensureActive()
                    if (!index.isCurrent(lease)) throw ArchiveSemanticException(ArchiveSemanticFailure.CHANGED)
                    val vector = client.embed(access.settings, access.apiKey, text, ArchiveEmbeddingUse.DOCUMENT)
                    if (vector.size != access.settings.dimensions) throw ArchiveSemanticException(ArchiveSemanticFailure.INVALID_RESULT)
                    parts += ArchiveEmbeddingPart(text, archiveUnitVector(vector))
                }
                currentCoroutineContext().ensureActive()
                index.complete(lease, parts)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { runCatching { index.fail(lease, ArchiveSemanticFailure.INTERRUPTED.name) } }
                throw cancelled
            } catch (error: Exception) { index.fail(lease, ((error as? ArchiveSemanticException)?.failure ?: ArchiveSemanticFailure.LOCAL_STORAGE).name) }
        }
        index.pending().isNotEmpty()
    }

    suspend fun search(query: String, localOnly: Boolean = false): ArchiveSearchSnapshot = withContext(Dispatchers.IO) {
        initialize()
        val text = query.trim()
        if (text.isBlank() || text.length > 1000 || text.toByteArray(Charsets.UTF_8).size > 6000) throw ArchiveSemanticException(ArchiveSemanticFailure.INPUT_LIMIT)
        val stored = settings.load()
        if (!stored.values.enabled) throw ArchiveSemanticException(ArchiveSemanticFailure.CONFIGURATION)
        val fingerprint = ArchiveEmbeddingSettingsRepository.fingerprint(stored.values)
        var vector = cacheLock.withLock { queries.firstOrNull { it.fingerprint == fingerprint && it.query == text && now() - it.at in 0..300_000 }?.vector }
        if (vector == null) {
            if (localOnly) throw ArchiveSemanticException(ArchiveSemanticFailure.CHANGED)
            val access = settings.access()
            if (access.revision != stored.revision) throw ArchiveSemanticException(ArchiveSemanticFailure.CHANGED)
            val response = client.embed(access.settings, access.apiKey, text, ArchiveEmbeddingUse.QUERY)
            currentCoroutineContext().ensureActive()
            if (response.size != access.settings.dimensions) throw ArchiveSemanticException(ArchiveSemanticFailure.INVALID_RESULT)
            val normalized = archiveUnitVector(response)
            vector = normalized
            requireConfiguration(fingerprint, stored.revision)
            cacheLock.withLock { queries.removeAll { it.fingerprint == fingerprint && it.query == text }; queries.addLast(Cached(fingerprint, text, now(), normalized)); while (queries.size > 4) queries.removeFirst() }
        }
        val queryVector = requireNotNull(vector)
        val best = mutableMapOf<String, ArchiveSearchHit>(); var afterId = ""; var afterPart = -1
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = index.vectorPage(fingerprint, afterId, afterPart)
            if (page.isEmpty()) break
            for (row in page) {
                if (row.vector.size != queryVector.size) throw ArchiveSemanticException(ArchiveSemanticFailure.INVALID_RESULT)
                var score = 0.0
                for (position in queryVector.indices) score += row.vector[position].toDouble() * queryVector[position]
                val old = best[row.recordId]
                if (old == null || score > old.score) best[row.recordId] = ArchiveSearchHit(row.recordId, row.recordRevision, row.part, score.coerceIn(-1.0, 1.0))
            }
            afterId = page.last().recordId; afterPart = page.last().part
        }
        requireConfiguration(fingerprint, stored.revision)
        ArchiveSearchSnapshot(text, fingerprint, best.values.sortedWith(compareByDescending<ArchiveSearchHit> { it.score }.thenBy { it.recordId }), stored.revision)
    }

    suspend fun page(snapshot: ArchiveSearchSnapshot, offset: Int, limit: Int = 24): Pair<List<ArchiveSearchItem>, Int> = withContext(Dispatchers.IO) {
        require(offset >= 0 && limit in 1..100)
        val local = snapshot.fingerprint == LocalText
        if (!local) requireConfiguration(snapshot.fingerprint, snapshot.configurationRevision)
        val result = mutableListOf<ArchiveSearchItem>(); var cursor = offset
        while (cursor < snapshot.hits.size && result.size < limit) {
            currentCoroutineContext().ensureActive()
            val hit = snapshot.hits[cursor++]
            val item = if (local) records.record(hit.recordId)?.takeIf { it.revision == hit.revision }?.let { record ->
                val text = localText(record.content)
                val at = text.lowercase().indexOf(snapshot.query.lowercase()).coerceAtLeast(0)
                ArchiveSearchItem(record, text.drop((at - 30).coerceAtLeast(0)).take(220), 0.0)
            } else index.searchItem(hit, snapshot.fingerprint)
            item?.let { result += it }
        }
        if (!local) requireConfiguration(snapshot.fingerprint, snapshot.configurationRevision)
        result to cursor
    }
    suspend fun localSearch(query: String): ArchiveSearchSnapshot = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.isBlank() || text.length > 1000) throw ArchiveSemanticException(ArchiveSemanticFailure.INPUT_LIMIT)
        val hits = mutableListOf<ArchiveSearchHit>(); var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = records.records(100, offset)
            if (page.isEmpty()) break
            page.filter { localText(it.content).contains(text, ignoreCase = true) }.forEach { hits += ArchiveSearchHit(it.id, it.revision, -1, 0.0) }
            offset += page.size
        }
        ArchiveSearchSnapshot(text, LocalText, hits)
    }
    private fun localText(content: ArchiveContent) = (listOf(content.title, content.type, content.body) + content.tags +
        content.fields.map { "${it.name}：${it.value}" } + content.links.map { "${it.label} ${it.url}" }).joinToString("\n")
    private suspend fun requireConfiguration(fingerprint: String, revision: Long) {
        val current = index.configuration()
        if (current?.fingerprint != fingerprint || current.revision != revision) throw ArchiveSemanticException(ArchiveSemanticFailure.CHANGED)
    }
    private companion object { const val LocalText = "archive-local-text-v1" }
}
