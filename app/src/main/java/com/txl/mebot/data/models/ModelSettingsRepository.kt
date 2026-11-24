package com.txl.mebot.data.models

import androidx.room.withTransaction
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.ModelSettingsEntity
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelSettingsRepository(
    private val database: MebotDatabase,
    private val cipher: CredentialCipher,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val dao = database.modelSettingsDao()

    suspend fun load(): StoredModelSettings = dao.read()?.asStored() ?: StoredModelSettings(ModelSettings(), 0, false)

    suspend fun save(settings: ModelSettings, keyChange: ApiKeyChange, expectedRevision: Long): StoredModelSettings =
        withContext(Dispatchers.IO) {
            val valid = settings.validated()
            database.withTransaction {
                val previous = dao.read()
                if ((previous?.revision ?: 0) != expectedRevision) throw ModelSettingsConflict()
                val encrypted = when (keyChange) {
                    ApiKeyChange.Keep -> previous?.encryptedKey()
                    is ApiKeyChange.Replace -> cipher.encrypt(validateApiKey(keyChange.value))
                }
                val record = ModelSettingsEntity(
                    languageModel = valid.languageModel, languageEndpoint = valid.languageEndpoint,
                    imageModel = valid.imageModel, imageEndpoint = valid.imageEndpoint, imageSize = valid.imageSize,
                    keyCiphertext = encrypted?.ciphertext, keyIv = encrypted?.iv,
                    revision = expectedRevision + 1, updatedAt = clock.millis(),
                )
                dao.save(record)
                record.asStored()
            }
        }

    suspend fun removeApiKey(expectedRevision: Long): StoredModelSettings = database.withTransaction {
        val previous = dao.read() ?: throw ModelSettingsConflict()
        if (previous.revision != expectedRevision) throw ModelSettingsConflict()
        val updated = previous.copy(keyCiphertext = null, keyIv = null, revision = previous.revision + 1, updatedAt = clock.millis())
        dao.save(updated)
        updated.asStored()
    }

    suspend fun access(expectedRevision: Long): ModelAccess = withContext(Dispatchers.IO) {
        val record = dao.read() ?: throw MissingApiKey()
        if (record.revision != expectedRevision) throw ModelSettingsConflict()
        val encrypted = record.encryptedKey() ?: throw MissingApiKey()
        val key = try { cipher.decrypt(encrypted) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { throw CredentialUnavailable() }
        ModelAccess(record.asSettings().validated(), key)
    }
}

private fun ModelSettingsEntity.encryptedKey(): EncryptedCredential? = if (keyCiphertext != null && keyIv != null) {
    EncryptedCredential(keyCiphertext, keyIv)
} else null

private fun ModelSettingsEntity.asSettings() = ModelSettings(languageModel, languageEndpoint, imageModel, imageEndpoint, imageSize)
private fun ModelSettingsEntity.asStored() = StoredModelSettings(asSettings(), revision, keyCiphertext != null && keyIv != null)
