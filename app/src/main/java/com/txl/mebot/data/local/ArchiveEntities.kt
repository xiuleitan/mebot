package com.txl.mebot.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ArchiveSessionState { ACTIVE, TARGET_DELETED }
enum class ArchiveOperationKind { CREATE, UPDATE, DELETE }
enum class ArchiveIndexState { WAITING_CONFIGURATION, QUEUED, RUNNING, READY, FAILED }

@Entity(tableName = "archive_records", foreignKeys = [
    ForeignKey(ArchiveSessionEntity::class, ["id"], ["sourceSessionId"]),
], indices = [Index(value = ["sourceSessionId"], unique = true), Index(value = ["deletedAt", "updatedAt", "id"])])
data class ArchiveRecordEntity(
    @PrimaryKey val id: String, val sourceSessionId: String, val payload: ByteArray?,
    val revision: Long, val createdAt: Long, val updatedAt: Long, val deletedAt: Long? = null,
)

@Entity(tableName = "archive_sessions", foreignKeys = [
    ForeignKey(ArchiveRecordEntity::class, ["id"], ["recordId"]),
], indices = [Index("recordId"), Index("updatedAt")])
data class ArchiveSessionEntity(
    @PrimaryKey val id: String, val recordId: String? = null,
    val state: ArchiveSessionState = ArchiveSessionState.ACTIVE, val createdAt: Long, val updatedAt: Long,
)

@Entity(tableName = "archive_drafts", foreignKeys = [
    ForeignKey(ArchiveSessionEntity::class, ["id"], ["sessionId"]),
])
data class ArchiveDraftEntity(
    @PrimaryKey val sessionId: String, val payload: ByteArray, val revision: Long,
    val baseRecordRevision: Long? = null,
    val activeRequestId: String? = null, val requestRecordRevision: Long? = null,
    val formatVersion: Int = 1, val updatedAt: Long,
)

@Entity(tableName = "archive_messages", foreignKeys = [
    ForeignKey(ArchiveSessionEntity::class, ["id"], ["sessionId"]),
], indices = [Index(value = ["sessionId", "sequence"], unique = true), Index(value = ["id", "sessionId"], unique = true)])
data class ArchiveMessageEntity(
    @PrimaryKey val id: String, val sessionId: String, val sequence: Long,
    val role: MessageRole, val payload: ByteArray, val createdAt: Long,
)

@Entity(tableName = "archive_operations", foreignKeys = [
    ForeignKey(ArchiveSessionEntity::class, ["id"], ["sessionId"]),
    ForeignKey(ArchiveRecordEntity::class, ["id"], ["recordId"]),
], indices = [Index("sessionId"), Index("recordId")])
data class ArchiveOperationEntity(
    @PrimaryKey val id: String, val sessionId: String, val recordId: String,
    val kind: ArchiveOperationKind, val draftRevision: Long, val resultRevision: Long,
    val payload: ByteArray, val createdAt: Long,
)

@Entity(tableName = "archive_assets", indices = [Index(value = ["relativePath"], unique = true)])
data class ArchiveAssetEntity(
    @PrimaryKey val id: String, val relativePath: String, val payload: ByteArray,
    val encryptedByteCount: Long, val createdAt: Long,
)

@Entity(tableName = "archive_session_assets", primaryKeys = ["sessionId", "assetId"], foreignKeys = [
    ForeignKey(ArchiveSessionEntity::class, ["id"], ["sessionId"]),
    ForeignKey(ArchiveAssetEntity::class, ["id"], ["assetId"]),
], indices = [Index("assetId"), Index(value = ["sessionId", "position"])])
data class ArchiveSessionAssetEntity(val sessionId: String, val assetId: String, val position: Long, val pending: Boolean)

@Entity(tableName = "archive_message_assets", primaryKeys = ["messageId", "assetId"], foreignKeys = [
    ForeignKey(ArchiveMessageEntity::class, ["id", "sessionId"], ["messageId", "sessionId"]),
    ForeignKey(ArchiveSessionAssetEntity::class, ["sessionId", "assetId"], ["sessionId", "assetId"]),
], indices = [Index(value = ["messageId", "sessionId"]), Index(value = ["sessionId", "assetId"])])
data class ArchiveMessageAssetEntity(val messageId: String, val sessionId: String, val assetId: String, val position: Long)

@Entity(tableName = "archive_record_assets", primaryKeys = ["recordId", "assetId"], foreignKeys = [
    ForeignKey(ArchiveRecordEntity::class, ["id"], ["recordId"]),
    ForeignKey(ArchiveAssetEntity::class, ["id"], ["assetId"]),
], indices = [Index("assetId"), Index(value = ["recordId", "position"])])
data class ArchiveRecordAssetEntity(val recordId: String, val assetId: String, val position: Long)

@Entity(tableName = "archive_embeddings", primaryKeys = ["recordId", "part"], foreignKeys = [
    ForeignKey(ArchiveRecordEntity::class, ["id"], ["recordId"]),
], indices = [Index(value = ["fingerprint", "recordId"])])
data class ArchiveEmbeddingEntity(
    val recordId: String, val part: Int, val recordRevision: Long, val fingerprint: String,
    val dimensions: Int, val vector: ByteArray, val text: ByteArray,
)

@Entity(tableName = "archive_index_jobs", foreignKeys = [
    ForeignKey(ArchiveRecordEntity::class, ["id"], ["recordId"]),
], indices = [Index(value = ["state", "updatedAt"])])
data class ArchiveIndexJobEntity(
    @PrimaryKey val recordId: String, val recordRevision: Long, val fingerprint: String?,
    val state: ArchiveIndexState, val leaseToken: String? = null, val attempts: Int = 0,
    val failureCode: String? = null, val updatedAt: Long,
)

@Entity(tableName = "archive_index_settings")
data class ArchiveIndexSettingsEntity(@PrimaryKey val id: Int = 1, val fingerprint: String?, val dimensions: Int?, val revision: Long, val updatedAt: Long,
    val configurationPayload: ByteArray? = null)

@Entity(tableName = "archive_file_cleanup")
data class ArchiveFileCleanupEntity(@PrimaryKey val relativePath: String, val queuedAt: Long)
