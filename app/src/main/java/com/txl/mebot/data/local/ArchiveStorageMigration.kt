package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 版本由集成会话统一分配；必须通过 Room 的正式升级事务与结构验证。 */
internal object ArchiveStorageMigration : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) = ArchiveStorageSchema.createTables(db)
}
