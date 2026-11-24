package com.txl.mebot.data.local

import androidx.room.Entity
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "trading_performance_days", primaryKeys = ["scope", "day"])
internal data class TradingPerformanceDayRecord(
    val scope: String,
    val day: Long,
    val time: Long,
    val balance: String,
    val transfersFrom: Long? = null,
    val deposited: String? = null,
    val withdrawn: String? = null,
    val weightedNet: String? = null,
)

internal object TradingPerformanceMigration : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `trading_performance_days` (" +
                "`scope` TEXT NOT NULL, `day` INTEGER NOT NULL, `time` INTEGER NOT NULL, " +
                "`balance` TEXT NOT NULL, `transfersFrom` INTEGER, `deposited` TEXT, " +
                "`withdrawn` TEXT, `weightedNet` TEXT, PRIMARY KEY(`scope`, `day`))",
        )
    }
}
