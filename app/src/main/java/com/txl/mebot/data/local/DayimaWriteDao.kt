package com.txl.mebot.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DayimaWriteDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(receipt: DayimaWriteReceiptEntity): Long

    @Query("SELECT * FROM dayima_write_receipts WHERE requestId = :requestId")
    suspend fun receipt(requestId: String): DayimaWriteReceiptEntity?

    @Query("SELECT COALESCE(MAX(version), 0) FROM dayima_write_receipts")
    suspend fun dataVersion(): Long

    @Query("SELECT COALESCE(MAX(version), 0) FROM dayima_write_receipts")
    fun observeDataVersion(): Flow<Long>
}
