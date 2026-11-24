package com.txl.mebot.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/** schema 16 只追加档案表；DDL 逐项对应 Room 导出，由正式迁移测试校验。 */
internal object ArchiveStorageSchema {
    val tables = listOf("archive_records", "archive_sessions", "archive_drafts", "archive_messages", "archive_operations", "archive_assets", "archive_session_assets", "archive_message_assets", "archive_record_assets", "archive_embeddings", "archive_index_jobs", "archive_index_settings", "archive_file_cleanup")

    fun createTables(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `archive_records` (`id` TEXT NOT NULL, `sourceSessionId` TEXT NOT NULL, `payload` BLOB, `revision` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`sourceSessionId`) REFERENCES `archive_sessions`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_archive_records_sourceSessionId` ON `archive_records` (`sourceSessionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_archive_records_deletedAt_updatedAt_id` ON `archive_records` (`deletedAt`, `updatedAt`, `id`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `archive_sessions` (`id` TEXT NOT NULL, `recordId` TEXT, `state` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`recordId`) REFERENCES `archive_records`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_archive_sessions_recordId` ON `archive_sessions` (`recordId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_archive_sessions_updatedAt` ON `archive_sessions` (`updatedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `archive_drafts` (`sessionId` TEXT NOT NULL, `payload` BLOB NOT NULL, `revision` INTEGER NOT NULL, `baseRecordRevision` INTEGER, `activeRequestId` TEXT, `requestRecordRevision` INTEGER, `formatVersion` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`sessionId`), FOREIGN KEY(`sessionId`) REFERENCES `archive_sessions`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE TABLE IF NOT EXISTS `archive_messages` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `sequence` INTEGER NOT NULL, `role` TEXT NOT NULL, `payload` BLOB NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`sessionId`) REFERENCES `archive_sessions`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_archive_messages_sessionId_sequence` ON `archive_messages` (`sessionId`, `sequence`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_archive_messages_id_sessionId` ON `archive_messages` (`id`, `sessionId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `archive_operations` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `recordId` TEXT NOT NULL, `kind` TEXT NOT NULL, `draftRevision` INTEGER NOT NULL, `resultRevision` INTEGER NOT NULL, `payload` BLOB NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`sessionId`) REFERENCES `archive_sessions`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`recordId`) REFERENCES `archive_records`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_archive_operations_sessionId` ON `archive_operations` (`sessionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_archive_operations_recordId` ON `archive_operations` (`recordId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `archive_assets` (`id` TEXT NOT NULL, `relativePath` TEXT NOT NULL, `payload` BLOB NOT NULL, `encryptedByteCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_archive_assets_relativePath` ON `archive_assets` (`relativePath`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `archive_session_assets` (`sessionId` TEXT NOT NULL, `assetId` TEXT NOT NULL, `position` INTEGER NOT NULL, `pending` INTEGER NOT NULL, PRIMARY KEY(`sessionId`, `assetId`), FOREIGN KEY(`sessionId`) REFERENCES `archive_sessions`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`assetId`) REFERENCES `archive_assets`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_archive_session_assets_assetId` ON `archive_session_assets` (`assetId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_archive_session_assets_sessionId_position` ON `archive_session_assets` (`sessionId`, `position`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `archive_message_assets` (`messageId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `assetId` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`messageId`, `assetId`), FOREIGN KEY(`messageId`, `sessionId`) REFERENCES `archive_messages`(`id`, `sessionId`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`sessionId`, `assetId`) REFERENCES `archive_session_assets`(`sessionId`, `assetId`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_archive_message_assets_messageId_sessionId` ON `archive_message_assets` (`messageId`, `sessionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_archive_message_assets_sessionId_assetId` ON `archive_message_assets` (`sessionId`, `assetId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `archive_record_assets` (`recordId` TEXT NOT NULL, `assetId` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`recordId`, `assetId`), FOREIGN KEY(`recordId`) REFERENCES `archive_records`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`assetId`) REFERENCES `archive_assets`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_archive_record_assets_assetId` ON `archive_record_assets` (`assetId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_archive_record_assets_recordId_position` ON `archive_record_assets` (`recordId`, `position`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `archive_embeddings` (`recordId` TEXT NOT NULL, `part` INTEGER NOT NULL, `recordRevision` INTEGER NOT NULL, `fingerprint` TEXT NOT NULL, `dimensions` INTEGER NOT NULL, `vector` BLOB NOT NULL, `text` BLOB NOT NULL, PRIMARY KEY(`recordId`, `part`), FOREIGN KEY(`recordId`) REFERENCES `archive_records`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_archive_embeddings_fingerprint_recordId` ON `archive_embeddings` (`fingerprint`, `recordId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `archive_index_jobs` (`recordId` TEXT NOT NULL, `recordRevision` INTEGER NOT NULL, `fingerprint` TEXT, `state` TEXT NOT NULL, `leaseToken` TEXT, `attempts` INTEGER NOT NULL, `failureCode` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`recordId`), FOREIGN KEY(`recordId`) REFERENCES `archive_records`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_archive_index_jobs_state_updatedAt` ON `archive_index_jobs` (`state`, `updatedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `archive_index_settings` (`id` INTEGER NOT NULL, `fingerprint` TEXT, `dimensions` INTEGER, `revision` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `archive_file_cleanup` (`relativePath` TEXT NOT NULL, `queuedAt` INTEGER NOT NULL, PRIMARY KEY(`relativePath`))")
        createTriggers(db)
    }

    fun createTriggers(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS archive_session_target_immutable
            BEFORE UPDATE OF recordId ON archive_sessions
            WHEN OLD.recordId IS NOT NULL AND NEW.recordId IS NOT OLD.recordId
            BEGIN SELECT RAISE(ABORT, 'Archive session target is immutable'); END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS archive_record_source_immutable
            BEFORE UPDATE OF sourceSessionId ON archive_records
            WHEN NEW.sourceSessionId IS NOT OLD.sourceSessionId
            BEGIN SELECT RAISE(ABORT, 'Archive source session is immutable'); END""")
    }
}
