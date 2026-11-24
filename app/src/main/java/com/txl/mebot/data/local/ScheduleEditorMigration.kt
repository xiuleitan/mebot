package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object ScheduleEditorMigration : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `schedule_editor_drafts` (`occurrenceId` TEXT NOT NULL, `requestId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `expectedRevision` INTEGER NOT NULL, `expectedTaskRevision` INTEGER NOT NULL, `attachmentFingerprint` TEXT NOT NULL, `title` TEXT NOT NULL, `detail` TEXT NOT NULL, `date` INTEGER NOT NULL, `time` INTEGER, `isRecurring` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`occurrenceId`), FOREIGN KEY(`occurrenceId`) REFERENCES `schedule_occurrences`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE TABLE IF NOT EXISTS `schedule_editor_attachments` (`occurrenceId` TEXT NOT NULL, `assetId` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`occurrenceId`, `assetId`), FOREIGN KEY(`occurrenceId`) REFERENCES `schedule_editor_drafts`(`occurrenceId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`assetId`) REFERENCES `media_assets`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedule_editor_attachments_assetId` ON `schedule_editor_attachments` (`assetId`)")
    }
}
