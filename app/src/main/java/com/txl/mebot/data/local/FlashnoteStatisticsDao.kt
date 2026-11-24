package com.txl.mebot.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 正式事项当前有效的完成日；撤销完成时移除，删除事项时转为按日汇总。 */
@Entity(tableName = "flashnote_completions",
    foreignKeys = [ForeignKey(FlashnoteEntity::class, ["id"], ["noteId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("day")])
data class FlashnoteCompletionEntity(@PrimaryKey val noteId: String, val day: Long)

/** 删除后只保留日期和数量，不保留事项 ID、正文或媒体。 */
@Entity(tableName = "flashnote_completion_archive")
data class FlashnoteCompletionArchiveEntity(@PrimaryKey val day: Long, val count: Long)

data class FlashnoteDayCount(val day: Long, val count: Long)
data class FlashnoteHistoryBounds(val firstCreatedAt: Long?, val firstCompletionDay: Long?)

private const val DailyCounts = """SELECT day, SUM(amount) AS count FROM (
    SELECT day, COUNT(*) AS amount FROM flashnote_completions WHERE day >= :fromDay AND day < :untilDay GROUP BY day
    UNION ALL SELECT day, count AS amount FROM flashnote_completion_archive WHERE day >= :fromDay AND day < :untilDay
    ) GROUP BY day ORDER BY day"""
private const val HistoryBounds = """SELECT (SELECT MIN(createdAt) FROM flashnotes) AS firstCreatedAt,
    (SELECT MIN(day) FROM (SELECT day FROM flashnote_completions UNION ALL SELECT day FROM flashnote_completion_archive)) AS firstCompletionDay"""

@Dao
interface FlashnoteStatisticsDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(item: FlashnoteCompletionEntity)
    @Query("SELECT * FROM flashnote_completions WHERE noteId = :id") suspend fun completion(id: String): FlashnoteCompletionEntity?
    @Query("DELETE FROM flashnote_completions WHERE noteId = :id") suspend fun remove(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun ensureArchive(item: FlashnoteCompletionArchiveEntity): Long
    @Query("UPDATE flashnote_completion_archive SET count = count + 1 WHERE day = :day") suspend fun incrementArchive(day: Long)
    @Query(DailyCounts) fun observeDays(fromDay: Long, untilDay: Long): Flow<List<FlashnoteDayCount>>
    @Query(DailyCounts) suspend fun days(fromDay: Long, untilDay: Long): List<FlashnoteDayCount>
    @Query(HistoryBounds) fun observeBounds(): Flow<FlashnoteHistoryBounds>
}
