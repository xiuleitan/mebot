package com.txl.mebot.data

import android.app.Application
import com.txl.mebot.data.archive.*
import com.txl.mebot.data.local.ArchiveIndexSettingsEntity
import com.txl.mebot.data.models.*
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ArchiveEmbeddingSettingsTest {
    private lateinit var f: ArchiveAnalysisFixture
    private lateinit var shared: ModelSettingsRepository
    private val config = ArchiveEmbeddingSettings(enabled = true, dimensions = 3)
    @Before fun setup() { f = ArchiveAnalysisFixture(); shared = ModelSettingsRepository(f.db, AesGcmCredentialCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") }) }
    @After fun teardown() { f.close() }
    private fun settings(storage: ArchiveStorage = f.storage) = ArchiveEmbeddingSettingsRepository(storage, ArchiveIndexRepository(storage), shared)

    @Test fun encryptedSettingsReloadAndIndependentCredentialsNeverRewriteSharedModelSettings() = runBlocking {
        shared.save(ModelSettings(), ApiKeyChange.Replace("shared-fixture-key"), 0)
        val repository = settings()
        val saved = repository.save(config, ArchiveEmbeddingKeyChange.Replace("private-fixture-key"), 0)
        val row = f.db.archives().settings()!!
        for (secret in listOf(config.endpoint, config.model, "private-fixture-key")) assertFalse(row.configurationPayload!!.toString(Charsets.ISO_8859_1).contains(secret))
        val reopened = settings()
        assertEquals(saved, reopened.load()); assertEquals("private-fixture-key", reopened.access().apiKey)
        assertEquals(1L, shared.load().revision); assertEquals("shared-fixture-key", shared.access(1).apiKey)
        val next = reopened.save(config, ArchiveEmbeddingKeyChange.UseShared, saved.revision)
        assertFalse(next.hasOwnKey); assertEquals("shared-fixture-key", reopened.access().apiKey)
        assertFalse(next.toString().contains("private-fixture-key")); assertFalse(reopened.access().toString().contains("shared-fixture-key"))
    }
    @Test fun onlyConfigurationCiphertextPreventsSilentKeyRecreationAndAllCiphertextIsRetained() = runBlocking {
        var available = true; var generated = 0
        val cipher = ArchiveCipher { create ->
            if (!available) { if (create) generated++ else throw ArchiveKeyUnavailable() }
            SecretKeySpec(ByteArray(32) { 6 }, "AES")
        }
        val storage = ArchiveStorage(f.db, f.db.archives(), cipher); val repository = settings(storage)
        repository.save(config, ArchiveEmbeddingKeyChange.Replace("private-fixture-key"), 0)
        assertTrue(f.db.archives().hasEncryptedData()); assertTrue(f.db.archives().sessions(10, 0).isEmpty())
        val before = f.db.archives().settings()!!.configurationPayload!!.copyOf()
        available = false
        assertTrue(runCatching { repository.load() }.exceptionOrNull() is ArchiveKeyUnavailable)
        assertTrue(runCatching { repository.save(config, ArchiveEmbeddingKeyChange.UseShared, 1) }.exceptionOrNull() is ArchiveKeyUnavailable)
        assertTrue(runCatching { ArchiveRepository(storage).createSession() }.exceptionOrNull() is ArchiveKeyUnavailable)
        assertEquals(0, generated); assertArrayEquals(before, f.db.archives().settings()!!.configurationPayload)
        assertTrue(f.db.archives().sessions(10, 0).isEmpty())
    }
    @Test fun wrongKeyTamperingWrongPurposeAndUnknownVersionNeverResetSettings() = runBlocking {
        val repository = settings(); repository.save(config, ArchiveEmbeddingKeyChange.Replace("private-fixture-key"), 0)
        val row = f.db.archives().settings()!!
        val wrong = settings(ArchiveStorage(f.db, f.db.archives(), ArchiveCipher { SecretKeySpec(ByteArray(32) { 8 }, "AES") }))
        assertTrue(runCatching { wrong.load() }.exceptionOrNull() is ArchiveIntegrityException)
        val corrupt = row.configurationPayload!!.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        f.db.archives().updateSettings(row.copy(configurationPayload = corrupt))
        assertTrue(runCatching { repository.load() }.exceptionOrNull() is ArchiveIntegrityException)
        assertTrue(runCatching { repository.save(config, ArchiveEmbeddingKeyChange.Keep, 1) }.isFailure)
        assertArrayEquals(corrupt, f.db.archives().settings()!!.configurationPayload)
        val wrongPurpose = f.storage.encrypt(JSONObject().put("version", 1), "archive-index-settings:2:v1")
        f.db.archives().updateSettings(row.copy(configurationPayload = wrongPurpose))
        assertTrue(runCatching { repository.load() }.exceptionOrNull() is ArchiveIntegrityException)
        val unsupported = f.storage.encrypt(JSONObject().put("version", 99), "archive-index-settings:1:v1")
        f.db.archives().updateSettings(row.copy(configurationPayload = unsupported))
        assertTrue(runCatching { repository.load() }.isFailure)
        assertArrayEquals(unsupported, f.db.archives().settings()!!.configurationPayload)
    }
    @Test fun legacyNullPayloadIsDisabledEvenWhenTheOldFingerprintIsPresent() = runBlocking {
        f.db.archives().insertSettings(ArchiveIndexSettingsEntity(fingerprint = "legacy-space", dimensions = 3, revision = 7, updatedAt = 1))
        var calls = 0
        val engine = f.semantic(ArchiveEmbeddingClient { _, _, _, _ -> calls++; floatArrayOf(1f, 0f, 0f) })
        assertFalse(engine.settings.load().values.enabled)
        assertEquals(7L, engine.settings.load().revision)
        engine.start(); assertFalse(engine.runQueue()); assertEquals(0, calls)
        assertEquals(ArchiveSemanticFailure.CONFIGURATION, (runCatching { engine.settings.access() }.exceptionOrNull() as ArchiveSemanticException).failure)
    }
    @Test fun invalidEndpointsCredentialsOrStaleEditsDoNotOverwriteTheLastValidConfiguration() = runBlocking {
        val repository = settings(); val saved = repository.save(config, ArchiveEmbeddingKeyChange.Replace("fixture-key"), 0)
        for (url in listOf("http://example.com/embeddings", "https://user:password@example.com/embeddings", "https://example.com/embeddings?key=secret", "https://example.com/chat/completions"))
            assertTrue(runCatching { repository.save(config.copy(endpoint = url), ArchiveEmbeddingKeyChange.Keep, saved.revision) }.isFailure)
        assertTrue(runCatching { repository.save(config, ArchiveEmbeddingKeyChange.Replace("Bearer bad"), saved.revision) }.isFailure)
        assertTrue(runCatching { repository.save(config, ArchiveEmbeddingKeyChange.UseShared, 0) }.exceptionOrNull() is ArchiveConflict)
        assertEquals(saved, repository.load()); assertEquals("fixture-key", repository.access().apiKey)
    }
    @Test fun suggestedEndpointStaysOnConfiguredHostAndDoesNotModifyAnything() = runBlocking {
        shared.save(ModelSettings(languageEndpoint = "https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions"), ApiKeyChange.Keep, 0)
        val repository = settings(); val suggested = repository.suggested()
        assertEquals("https://workspace.cn-beijing.maas.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding", suggested.endpoint)
        assertEquals(ArchiveEmbeddingProtocol.DASHSCOPE, suggested.protocol); assertFalse(suggested.enabled)
        assertNull(f.db.archives().settings()); assertEquals(1L, shared.load().revision)
    }
}
