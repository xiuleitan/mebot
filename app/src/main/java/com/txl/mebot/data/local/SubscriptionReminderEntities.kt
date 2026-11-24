package com.txl.mebot.data.local

import androidx.room.*
import java.time.LocalDate

enum class SubscriptionReminderTarget { AUTO_CHARGE, EXPIRY }
enum class SubscriptionReminderPlanState { PENDING, SCHEDULED, BLOCKED, FAILED, POSTING, DELIVERED, UNCERTAIN, HANDLED }
enum class SubscriptionReminderDeliveryState { ATTEMPTING, RETRYABLE, DELIVERED, UNCERTAIN }

@Entity(tableName = "subscription_reminder_settings", foreignKeys = [
    ForeignKey(SubscriptionEntity::class, ["id"], ["subscriptionId"]),
])
data class SubscriptionReminderSettingsEntity(@PrimaryKey val subscriptionId: String, val enabled: Boolean = true,
    val minuteOfDay: Int = 540, val revision: Long = 0, val updatedAt: Long)

@Entity(tableName = "subscription_reminder_plans", foreignKeys = [
    ForeignKey(SubscriptionEntity::class, ["id"], ["subscriptionId"]),
], indices = [Index(value = ["state", "triggerAt"])])
data class SubscriptionReminderPlanEntity(@PrimaryKey val subscriptionId: String, val cycleId: String,
    val targetType: SubscriptionReminderTarget, val targetDate: LocalDate, val triggerAt: Long, val zoneId: String,
    val subscriptionRevision: Long, val settingsRevision: Long, val generation: String,
    val state: SubscriptionReminderPlanState, val failure: String? = null, val updatedAt: Long)

/** 交付不随当前计划、权限或提醒开关清理；UNCERTAIN 明确表示系统提交结果不明。 */
@Entity(tableName = "subscription_reminder_deliveries", primaryKeys = ["subscriptionId", "cycleId"], foreignKeys = [
    ForeignKey(SubscriptionEntity::class, ["id"], ["subscriptionId"]),
], indices = [Index("state")])
data class SubscriptionReminderDeliveryEntity(val subscriptionId: String, val cycleId: String,
    val state: SubscriptionReminderDeliveryState, val attemptId: String, val planGeneration: String,
    val targetType: SubscriptionReminderTarget, val targetDate: LocalDate, val startedAt: Long,
    val deliveredAt: Long? = null, val updatedAt: Long)

@Dao
interface SubscriptionReminderDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSettings(item: SubscriptionReminderSettingsEntity): Long
    @Query("SELECT * FROM subscription_reminder_settings WHERE subscriptionId = :id") suspend fun settings(id: String): SubscriptionReminderSettingsEntity?
    @Query("SELECT * FROM subscription_reminder_settings WHERE subscriptionId = :id") fun observeSettings(id: String): kotlinx.coroutines.flow.Flow<SubscriptionReminderSettingsEntity?>
    @Query("SELECT * FROM subscription_reminder_settings ORDER BY subscriptionId") fun observeSettings(): kotlinx.coroutines.flow.Flow<List<SubscriptionReminderSettingsEntity>>
    @Query("UPDATE subscription_reminder_settings SET enabled = :enabled, minuteOfDay = :minute, revision = revision + 1, updatedAt = :now WHERE subscriptionId = :id AND revision = :expected")
    suspend fun saveSettings(id: String, enabled: Boolean, minute: Int, expected: Long, now: Long): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun savePlan(item: SubscriptionReminderPlanEntity)
    @Query("SELECT * FROM subscription_reminder_plans WHERE subscriptionId = :id") suspend fun plan(id: String): SubscriptionReminderPlanEntity?
    @Query("SELECT * FROM subscription_reminder_plans ORDER BY triggerAt, subscriptionId") suspend fun plans(): List<SubscriptionReminderPlanEntity>
    @Query("SELECT * FROM subscription_reminder_plans WHERE subscriptionId = :id") fun observePlan(id: String): kotlinx.coroutines.flow.Flow<SubscriptionReminderPlanEntity?>
    @Query("DELETE FROM subscription_reminder_plans WHERE subscriptionId = :id") suspend fun deletePlan(id: String)
    @Query("UPDATE subscription_reminder_plans SET state = :state, failure = :failure, updatedAt = :now WHERE subscriptionId = :id AND generation = :generation")
    suspend fun markPlan(id: String, generation: String, state: SubscriptionReminderPlanState, failure: String?, now: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDelivery(item: SubscriptionReminderDeliveryEntity): Long
    @Update suspend fun updateDelivery(item: SubscriptionReminderDeliveryEntity): Int
    @Query("SELECT * FROM subscription_reminder_deliveries WHERE subscriptionId = :id AND cycleId = :cycle")
    suspend fun delivery(id: String, cycle: String): SubscriptionReminderDeliveryEntity?
    @Query("""UPDATE subscription_reminder_deliveries SET state = :state, deliveredAt = :deliveredAt, updatedAt = :now
        WHERE subscriptionId = :id AND cycleId = :cycle AND attemptId = :attempt AND state = 'ATTEMPTING'""")
    suspend fun finishDelivery(id: String, cycle: String, attempt: String, state: SubscriptionReminderDeliveryState, deliveredAt: Long?, now: Long): Int
}
