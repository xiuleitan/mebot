package com.txl.mebot.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "trading_alert_connection")
internal data class TradingAlertConnection(
    @PrimaryKey val id: Int = 1,
    val endpoint: String,
    val deviceId: String,
    val tokenCiphertext: ByteArray,
    val tokenIv: ByteArray,
)

@Entity(
    tableName = "trading_price_alerts",
    indices = [Index(value = ["deviceId", "symbol", "createdAt"])],
)
internal data class TradingPriceAlert(
    @PrimaryKey val requestId: String,
    val deviceId: String,
    val instanceId: String,
    val symbol: String,
    val kind: String,
    val threshold: String,
    val minutes: Int?,
    val unit: String?,
    val createdAt: Long,
    val serverId: String? = null,
    val direction: String? = null,
    val status: String = "pending",
)

@Entity(
    tableName = "trading_price_alert_events",
    primaryKeys = ["deviceId", "instanceId", "eventId"],
    indices = [Index(value = ["deviceId", "symbol", "triggeredAt"])],
)
internal data class TradingPriceAlertEvent(
    val deviceId: String,
    val instanceId: String,
    val eventId: Long,
    val ruleId: String,
    val kind: String,
    val symbol: String,
    val markPrice: String,
    val triggeredAt: Long,
)

@Dao
internal interface TradingAlertDao {
    @Query("SELECT * FROM trading_alert_connection WHERE id = 1")
    suspend fun connection(): TradingAlertConnection?

    @Query("SELECT endpoint FROM trading_alert_connection WHERE id = 1")
    fun observeEndpoint(): Flow<String?>

    @Query("SELECT deviceId FROM trading_alert_connection WHERE id = 1")
    fun observeDeviceId(): Flow<String?>

    @Upsert
    suspend fun saveConnection(connection: TradingAlertConnection)

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM trading_price_alerts
            WHERE deviceId = (SELECT deviceId FROM trading_alert_connection WHERE id = 1)
            AND symbol = :symbol AND status IN ('active', 'cancelling')
        )
    """)
    fun observeHasActiveAlerts(symbol: String): Flow<Boolean>

    @Query("""
        SELECT * FROM trading_price_alerts
        WHERE deviceId = (SELECT deviceId FROM trading_alert_connection WHERE id = 1) AND symbol = :symbol
        AND createdAt >= :openedAt AND requestId GLOB :requests
        AND status IN ('pending', 'active', 'cancelling')
        ORDER BY createdAt DESC
    """)
    fun observePositionRules(symbol: String, requests: String, openedAt: Long): Flow<List<TradingPriceAlert>>

    @Query("""
        SELECT * FROM trading_price_alerts
        WHERE deviceId = (SELECT deviceId FROM trading_alert_connection WHERE id = 1) AND symbol = :symbol
        ORDER BY createdAt DESC LIMIT 50
    """)
    fun observeRules(symbol: String): Flow<List<TradingPriceAlert>>

    @Query("""
        SELECT * FROM trading_price_alerts
        WHERE deviceId = (SELECT deviceId FROM trading_alert_connection WHERE id = 1) AND symbol = :symbol
        AND status IN ('pending', 'active', 'cancelling')
        ORDER BY createdAt DESC
    """)
    fun observeOpenRules(symbol: String): Flow<List<TradingPriceAlert>>

    @Query("""
        SELECT * FROM trading_price_alert_events
        WHERE deviceId = (SELECT deviceId FROM trading_alert_connection WHERE id = 1) AND symbol = :symbol
        ORDER BY triggeredAt DESC, eventId DESC LIMIT 10
    """)
    fun observeEvents(symbol: String): Flow<List<TradingPriceAlertEvent>>

    @Query("""
        SELECT * FROM trading_price_alerts
        WHERE deviceId = :deviceId AND instanceId = :instanceId AND symbol = :symbol AND kind = :kind
        AND threshold = :threshold AND minutes IS :minutes AND unit IS :unit AND status IN ('pending', 'active', 'cancelling')
        ORDER BY createdAt DESC LIMIT 1
    """)
    suspend fun matchingRule(
        deviceId: String, instanceId: String, symbol: String, kind: String,
        threshold: String, minutes: Int?, unit: String?,
    ): TradingPriceAlert?

    @Query("SELECT * FROM trading_price_alerts WHERE requestId = :id")
    suspend fun rule(id: String): TradingPriceAlert?

    @Query("""
        SELECT * FROM trading_price_alerts
        WHERE deviceId = :deviceId AND instanceId = :instanceId AND symbol = :symbol
        AND serverId = :ruleId AND kind = :kind
        ORDER BY createdAt DESC LIMIT 1
    """)
    suspend fun ruleForEvent(deviceId: String, instanceId: String, symbol: String, ruleId: String, kind: String): TradingPriceAlert?

    @Query("""
        SELECT * FROM trading_price_alerts
        WHERE deviceId = :deviceId AND instanceId = :instanceId AND status IN ('active', 'cancelling')
    """)
    suspend fun activeRules(deviceId: String, instanceId: String): List<TradingPriceAlert>

    @Upsert
    suspend fun saveRule(rule: TradingPriceAlert)

    @Query("""
        UPDATE trading_price_alerts SET status = 'expired'
        WHERE deviceId = :deviceId AND instanceId != :instanceId AND status IN ('pending', 'active', 'cancelling')
    """)
    suspend fun expireRules(deviceId: String, instanceId: String)

    @Query("""
        UPDATE trading_price_alerts SET status = 'triggered'
        WHERE deviceId = :deviceId AND instanceId = :instanceId AND serverId = :ruleId
        AND symbol = :symbol AND kind = 'target' AND status IN ('active', 'inactive', 'cancelling')
    """)
    suspend fun markTriggered(deviceId: String, instanceId: String, ruleId: String, symbol: String)

    @Query("""
        SELECT EXISTS(SELECT 1 FROM trading_price_alert_events
        WHERE deviceId = :deviceId AND instanceId = :instanceId AND ruleId = :ruleId AND symbol = :symbol)
    """)
    suspend fun didTrigger(deviceId: String, instanceId: String, ruleId: String, symbol: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordEvent(event: TradingPriceAlertEvent): Long
}
