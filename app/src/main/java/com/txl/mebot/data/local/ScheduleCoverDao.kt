package com.txl.mebot.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class ScheduleCoverState { QUEUED, GENERATING, DOWNLOADING, READY, FAILED, SKIPPED }
enum class ScheduleCoverFailure { CONFIGURATION, AUTH, BILLING, RATE_LIMIT, SERVICE, NETWORK, INVALID_RESPONSE, DOWNLOAD, INTERRUPTED, LOCAL_SAVE }

/** 业务状态保存在主库；临时下载地址仅用于恢复下载，不作为封面的持久引用。 */
@Entity(tableName = "schedule_cover_jobs",
    foreignKeys = [ForeignKey(ScheduleTaskEntity::class, ["id"], ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sessionId"], unique = true)])
data class ScheduleCoverJobEntity(
    @PrimaryKey val taskId: String,
    val sessionId: String,
    val attemptId: String,
    val prompt: String,
    val state: ScheduleCoverState,
    val remoteUrl: String? = null,
    val generatedAt: Long? = null,
    val generator: String? = null,
    val failure: ScheduleCoverFailure? = null,
    val updatedAt: Long,
) {
    override fun toString() = "ScheduleCoverJob(taskId=$taskId, state=$state, failure=$failure)"
}

@Dao
interface ScheduleCoverDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(job: ScheduleCoverJobEntity): Long
    @Update suspend fun update(job: ScheduleCoverJobEntity): Int
    @Query("SELECT * FROM schedule_cover_jobs WHERE taskId = :taskId") suspend fun job(taskId: String): ScheduleCoverJobEntity?
    @Query("SELECT * FROM schedule_cover_jobs WHERE sessionId = :sessionId") suspend fun forSession(sessionId: String): ScheduleCoverJobEntity?
    @Query("SELECT * FROM schedule_cover_jobs WHERE sessionId = :sessionId") fun observeForSession(sessionId: String): Flow<ScheduleCoverJobEntity?>
    @Query("SELECT * FROM schedule_cover_jobs WHERE state IN ('QUEUED', 'GENERATING', 'DOWNLOADING')")
    suspend fun unfinished(): List<ScheduleCoverJobEntity>
    @Query("UPDATE schedule_cover_jobs SET state = 'SKIPPED', remoteUrl = NULL, failure = NULL, updatedAt = :now WHERE sessionId = :sessionId")
    suspend fun preferUserImage(sessionId: String, now: Long)
}
