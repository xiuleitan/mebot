package com.txl.mebot.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.archive.*
import com.txl.mebot.data.local.MebotDatabase
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

internal class ArchiveAnalysisFixture : AutoCloseable {
    val app: Application = ApplicationProvider.getApplicationContext()
    val db: MebotDatabase
    val records: ArchiveRepository
    val files: ArchiveAttachmentRepository
    val storage: ArchiveStorage
    init {
        File(app.filesDir, "archive").deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(app, MebotDatabase::class.java).build()
        storage = ArchiveStorage(db, db.archives(), ArchiveCipher { SecretKeySpec(ByteArray(32) { 6 }, "AES") })
        records = ArchiveRepository(storage)
        files = ArchiveAttachmentRepository(app, storage)
    }
    suspend fun send(id: String, text: String, assets: List<String> = emptyList()) {
        val draft = records.draft(id)
        val ready = records.saveDraft(id, draft.revision, draft.content.copy(input = text))
        records.sendMessage(id, UUID.randomUUID().toString(), ready.revision, text, assets)
    }
    suspend fun image(id: String, name: String): String = files.import(id, name, "image/jpeg") { ByteArrayInputStream(name.toByteArray()) }
    fun coordinator(interpreter: ArchiveInterpreter, image: suspend (File) -> String = { file ->
        "data:image/jpeg;base64," + android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
    }) = ArchiveAnalysisCoordinator(records, files, interpreter, image)
    fun semantic(client: ArchiveEmbeddingClient, enqueue: () -> Unit = {}): ArchiveSemanticEngine {
        val index = ArchiveIndexRepository(storage)
        val shared = com.txl.mebot.data.models.ModelSettingsRepository(db, com.txl.mebot.data.models.AesGcmCredentialCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") })
        return ArchiveSemanticEngine(ArchiveEmbeddingSettingsRepository(storage, index, shared), index, records, client, enqueue)
    }
    override fun close() {
        db.close()
        File(app.filesDir, "archive").deleteRecursively()
        File(app.cacheDir, "archive").deleteRecursively()
    }
}
