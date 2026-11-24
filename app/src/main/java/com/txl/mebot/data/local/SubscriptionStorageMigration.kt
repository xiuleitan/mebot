package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 由集成会话预留的 14→15：仅追加订阅表，不改写原有业务、文件或历史 identity。 */
internal object SubscriptionStorageMigration : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) = SubscriptionStorageSchema.createTables(db)
}
