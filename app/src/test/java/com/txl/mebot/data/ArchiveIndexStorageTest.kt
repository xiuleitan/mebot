package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.archive.*
import com.txl.mebot.data.local.*
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ArchiveIndexStorageTest {
    private lateinit var context: Context
    private lateinit var database: MebotDatabase
    private lateinit var records: ArchiveRepository
    private lateinit var index: ArchiveIndexRepository
    private val cipher = ArchiveCipher { SecretKeySpec(ByteArray(32) { 5 }, "AES") }
    private val config = ArchiveEmbeddingConfiguration("https://example.com/v1", "fixture-embedding", 3)
    private val parts = listOf(ArchiveEmbeddingPart("银行借记卡资料", floatArrayOf(3f, 4f, 0f)))
    @Before fun setUp() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(MebotDatabase.FILE_NAME); open() }
    private fun open() {
        database = MebotDatabase.create(context)
        val storage = ArchiveStorage(database, database.archives(), cipher)
        records = ArchiveRepository(storage); index = ArchiveIndexRepository(storage)
    }
    @After fun tearDown() { database.close(); context.deleteDatabase(MebotDatabase.FILE_NAME) }
    private suspend fun save(): ArchiveSaveReceipt {
        records.createSession("a")
        val draft = records.saveDraft("a", 0, ArchiveDraftContent(candidate = ArchiveContent("中国银行卡")))
        return records.confirm("a", "save", draft.revision)
    }
    private suspend fun update(): ArchiveSaveReceipt {
        val draft = records.saveDraft("a", records.draft("a").revision, ArchiveDraftContent(candidate = ArchiveContent("新的银行卡资料")))
        return records.confirm("a", "update", draft.revision)
    }

    @Test fun noConfigurationMeansWaitingAndConfigurationQueuesExistingRecords() = runBlocking {
        val record = save()
        assertEquals(ArchiveIndexState.WAITING_CONFIGURATION, database.archives().job(record.recordId)?.state)
        assertNull(index.claim(record.recordId)); assertTrue(index.vectors().isEmpty())
        val configured = index.configure(config, 0)
        assertEquals(1L, configured.revision)
        assertEquals(ArchiveIndexState.QUEUED, database.archives().job(record.recordId)?.state)
        assertEquals(configured.fingerprint, index.queued().single().fingerprint)
    }

    @Test fun vectorAndSourceAreEncryptedNormalizedAndSurviveReopen() = runBlocking {
        val record = save(); index.configure(config, 0)
        val lease = requireNotNull(index.claim(record.recordId))
        assertNull(index.claim(record.recordId))
        assertTrue(index.complete(lease, parts)); assertFalse(index.complete(lease, parts))
        val row = database.archives().effectiveEmbeddings().single()
        assertFalse(row.text.toString(Charsets.UTF_8).contains(parts.single().text))
        assertTrue(row.vector.size > 3 * 4)
        database.close(); open()
        val vector = index.vectors().single()
        assertEquals(record.recordId, vector.recordId)
        assertArrayEquals(floatArrayOf(.6f, .8f, 0f), vector.vector, .00001f)
    }

    @Test fun updateInvalidatesOldVectorAndRejectsLatePreviousRevisionCompletion() = runBlocking {
        val record = save(); index.configure(config, 0)
        val old = requireNotNull(index.claim(record.recordId))
        update()
        assertFalse(index.complete(old, parts)); assertTrue(index.vectors().isEmpty())
        val next = requireNotNull(index.claim(record.recordId))
        assertEquals(2L, next.recordRevision); assertTrue(index.complete(next, parts))
        // 即便派生数据被错误保留，查询也必须再次检查正式记录版本。
        val row = requireNotNull(database.archives().record(record.recordId))
        database.archives().updateRecord(row.copy(revision = row.revision + 1))
        assertTrue(index.vectors().isEmpty())
    }

    @Test fun configurationChangesInvalidateVectorsOldLeasesAndWrongDimensions() = runBlocking {
        val record = save(); index.configure(config, 0)
        val first = requireNotNull(index.claim(record.recordId))
        val nextConfig = config.copy(model = "new-model", dimensions = 2)
        val next = index.configure(nextConfig, 1)
        assertFalse(index.complete(first, parts))
        val lease = requireNotNull(index.claim(record.recordId))
        assertTrue(runCatching { index.complete(lease, parts) }.isFailure)
        assertEquals(ArchiveIndexState.RUNNING, database.archives().job(record.recordId)?.state)
        assertTrue(index.complete(lease, listOf(ArchiveEmbeddingPart("新模型", floatArrayOf(1f, 0f)))))
        assertEquals(2, index.vectors().single().vector.size)
        index.configure(null, next.revision)
        assertTrue(index.vectors().isEmpty()); assertEquals(ArchiveIndexState.WAITING_CONFIGURATION, database.archives().job(record.recordId)?.state)
    }

    @Test fun recoveryRevokesRunningTokenAndOnlyNewLeaseMayFinish() = runBlocking {
        val record = save(); index.configure(config, 0)
        val old = requireNotNull(index.claim(record.recordId))
        database.close(); open()
        assertEquals(1, index.recoverInterrupted()); assertEquals(0, index.recoverInterrupted())
        assertFalse(index.complete(old, parts))
        val retry = requireNotNull(index.claim(record.recordId))
        assertNotEquals(old.token, retry.token)
        assertFalse(index.complete(old, parts))
        assertEquals(2, database.archives().job(record.recordId)?.attempts)
        assertTrue(index.fail(retry, "NETWORK_UNAVAILABLE"))
        assertFalse(index.fail(old, "NETWORK_UNAVAILABLE"))
        val final = requireNotNull(index.claim(record.recordId)); assertTrue(index.complete(final, parts))
    }

    @Test fun deletedRecordCannotReceiveLateIndexOrAppearInEffectiveResults() = runBlocking {
        val record = save(); index.configure(config, 0)
        val lease = requireNotNull(index.claim(record.recordId))
        records.delete("a", "delete", record.revision)
        assertFalse(index.complete(lease, parts)); assertTrue(index.vectors().isEmpty()); assertTrue(index.queued().isEmpty())
    }

    @Test fun invalidValuesZeroVectorsAndMixedPartDimensionsNeverPublishPartialIndex() = runBlocking {
        val record = save(); index.configure(config, 0)
        val lease = requireNotNull(index.claim(record.recordId))
        listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(Float.NaN, 1f, 1f), floatArrayOf(Float.POSITIVE_INFINITY, 0f, 1f)).forEach {
            assertTrue(runCatching { index.complete(lease, listOf(ArchiveEmbeddingPart("invalid", it))) }.isFailure)
            assertTrue(index.vectors().isEmpty())
        }
        assertTrue(runCatching { index.complete(lease, parts + ArchiveEmbeddingPart("wrong-dim", floatArrayOf(1f))) }.isFailure)
        assertTrue(index.complete(lease, parts))
    }
}
