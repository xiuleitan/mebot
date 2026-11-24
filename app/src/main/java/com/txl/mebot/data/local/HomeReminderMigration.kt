package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 19 已装机冻结；20 仅追加三张表，DDL 与全新安装保持一致。 */
object HomeReminderMigration : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `home_events` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `cycleKey` TEXT, `eventDate` INTEGER NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `completedAt` INTEGER, `resolvedById` TEXT, `cancelled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `revision` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_home_events_eventDate_id` ON `home_events` (`eventDate`, `id`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_home_events_kind_sourceId` ON `home_events` (`kind`, `sourceId`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `home_flashnote_days` (`day` INTEGER NOT NULL, `seed` INTEGER NOT NULL, `selectedNoteId` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`day`))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `dayima_reminder_plans` (`id` TEXT NOT NULL, `periodId` TEXT NOT NULL, `periodDate` INTEGER NOT NULL, `periodRevision` INTEGER NOT NULL, `kind` TEXT NOT NULL, `predictedDate` INTEGER NOT NULL, `rangeStart` INTEGER NOT NULL, `rangeEnd` INTEGER NOT NULL, `reminderDate` INTEGER NOT NULL, `minuteOfDay` INTEGER NOT NULL, `triggerAt` INTEGER NOT NULL, `zoneId` TEXT NOT NULL, `dataVersion` INTEGER NOT NULL, `ruleFingerprint` TEXT NOT NULL, `generation` TEXT NOT NULL, `active` INTEGER NOT NULL, `deliveryState` TEXT NOT NULL, `attemptId` TEXT, `attemptStartedAt` INTEGER, `deliveredAt` INTEGER, `failure` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_dayima_reminder_plans_periodId_kind` ON `dayima_reminder_plans` (`periodId`, `kind`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_dayima_reminder_plans_active_triggerAt` ON `dayima_reminder_plans` (`active`, `triggerAt`)""")
    }
}
