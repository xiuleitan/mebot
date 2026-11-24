package com.txl.mebot.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.txl.mebot.data.trading.BTC_CONTRACT
import com.txl.mebot.data.trading.POSITION_HISTORY_PAGE
import com.txl.mebot.data.trading.TradingPerformanceClose
import kotlinx.coroutines.flow.Flow

internal const val SHARED_TREND_SYMBOL = "*"

@Entity(tableName = "trading_connection")
internal data class TradingConnection(
    @PrimaryKey val id: Int = 1,
    val scope: String,
    val ciphertext: ByteArray?,
    val iv: ByteArray?,
)

@Entity(tableName = "trading_fill_sync", primaryKeys = ["scope", "symbol"])
internal data class TradingFillSync(
    val scope: String,
    val symbol: String,
    val fromTime: Long,
    val throughTime: Long,
    val historyPending: Boolean,
)

@Entity(
    tableName = "trading_fills",
    primaryKeys = ["scope", "symbol", "tradeId"],
    indices = [Index(value = ["scope", "symbol", "time"])],
)
internal data class TradingFill(
    val scope: String,
    val tradeId: Long,
    val orderId: Long,
    val time: Long,
    val side: String,
    val positionSide: String,
    val price: String,
    val quantity: String,
    val realizedPnl: String,
    val reason: String? = null,
    val reasonUpdatedAt: Long? = null,
    val symbol: String = BTC_CONTRACT,
)

@Entity(tableName = "trading_lines", indices = [Index(value = ["symbol"])])
internal data class TradingLine(
    @PrimaryKey val id: String,
    val kind: String,
    val time: Long?,
    val price: Double,
    val endTime: Long?,
    val endPrice: Double?,
    @ColumnInfo(defaultValue = "'BTCUSDT'") val symbol: String = BTC_CONTRACT,
)

@Entity(tableName = "trading_candles", primaryKeys = ["symbol", "interval", "time"])
internal data class TradingCandle(
    val interval: String,
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val symbol: String = BTC_CONTRACT,
)

@Entity(tableName = "trading_trend")
internal data class TradingTrendEntity(
    @PrimaryKey val symbol: String,
    val direction: String,
    val reason: String, // Earlier sideways assessments remain available as an uncategorized note.
    @ColumnInfo(defaultValue = "''") val bullishReason: String,
    @ColumnInfo(defaultValue = "''") val bearishReason: String,
)

@Entity(tableName = "trading_trend_history", indices = [Index(value = ["symbol", "recordedAt"])])
internal data class TradingTrendHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val direction: String,
    val recordedAt: Long,
)

@Entity(tableName = "trading_position_plans", primaryKeys = ["scope", "symbol", "positionKey"])
internal data class TradingPositionPlanEntity(
    val scope: String,
    val symbol: String,
    val positionKey: String,
    val stopLoss: String?,
    val takeProfit: String?,
)

@Entity(
    tableName = "trading_closed_positions",
    primaryKeys = ["scope", "symbol", "positionSide", "closingTradeId"],
    indices = [Index(value = ["scope", "symbol", "closedAt"])],
)
internal data class TradingClosedPosition(
    val scope: String,
    val positionSide: String,
    val closingTradeId: Long,
    val side: String,
    val firstTradeId: Long,
    val firstTradeAt: Long,
    val openingKnown: Boolean,
    val closedAt: Long,
    val openingQuantity: String?,
    val closingQuantity: String?,
    val realizedPnl: String,
    val trendAtOpen: String?,
    val symbol: String = BTC_CONTRACT,
)

@Entity(
    tableName = "trading_watchlist",
    foreignKeys = [ForeignKey(MediaAssetEntity::class, ["id"], ["iconAssetId"])],
    indices = [Index("iconAssetId")],
)
internal data class TradingWatchedContract(
    @PrimaryKey val symbol: String,
    val asset: String,
    val priceDecimals: Int,
    val addedAt: Long,
    val sortOrder: Int? = null,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
    val iconAssetId: String? = null,
    val category: String? = null,
)

@Entity(tableName = "trading_notes")
internal data class TradingNote(
    @PrimaryKey val symbol: String,
    val text: String,
)

@Dao
internal interface TradingDao {
    @Query("SELECT text FROM trading_notes WHERE symbol = :symbol")
    fun observeNote(symbol: String): Flow<String?>

    @Query("SELECT text FROM trading_notes WHERE symbol = :symbol")
    suspend fun note(symbol: String): String?

    @Query("SELECT * FROM trading_notes WHERE symbol <> :sharedSymbol ORDER BY symbol")
    suspend fun legacyNotes(sharedSymbol: String): List<TradingNote>

    @Upsert
    suspend fun saveNote(note: TradingNote)

    @Query("SELECT * FROM trading_watchlist ORDER BY addedAt, symbol")
    fun observeWatchedContracts(): Flow<List<TradingWatchedContract>>

    @Query("SELECT * FROM trading_watchlist")
    suspend fun watchedContracts(): List<TradingWatchedContract>

    @Query("SELECT * FROM trading_watchlist WHERE symbol = :symbol")
    suspend fun watchedContract(symbol: String): TradingWatchedContract?

    @Upsert
    suspend fun saveWatchedContracts(contracts: List<TradingWatchedContract>)

    @Query("DELETE FROM trading_watchlist WHERE symbol = :symbol")
    suspend fun deleteWatchedContract(symbol: String)

    @Query("""
        SELECT media.relativePath FROM trading_watchlist AS watched
        JOIN media_assets AS media ON media.id = watched.iconAssetId
        WHERE watched.symbol = :symbol AND watched.hidden = 0
    """)
    fun observeWatchedIconPath(symbol: String): Flow<String?>

    @Query("SELECT * FROM trading_closed_positions WHERE scope = (SELECT scope FROM trading_connection WHERE id = 1) AND symbol = :symbol ORDER BY closedAt DESC, closingTradeId DESC LIMIT :limit")
    fun observeClosedPositions(symbol: String = BTC_CONTRACT, limit: Int = POSITION_HISTORY_PAGE): Flow<List<TradingClosedPosition>>

    @Query("""
        SELECT realizedPnl FROM trading_closed_positions
        WHERE scope = (SELECT scope FROM trading_connection WHERE id = 1) AND symbol = :symbol
    """)
    fun observeClosedPositionPnl(symbol: String): Flow<List<String>>

    @Query("SELECT * FROM trading_closed_positions WHERE scope = :scope AND symbol = :symbol AND closedAt >= :from")
    suspend fun closedPositions(scope: String, symbol: String, from: Long): List<TradingClosedPosition>

    @Upsert
    suspend fun saveClosedPositions(positions: List<TradingClosedPosition>)

    @Query("SELECT * FROM trading_fills WHERE scope = :scope AND symbol = :symbol AND time BETWEEN :from AND :through ORDER BY time DESC, tradeId DESC")
    suspend fun fillsBetween(scope: String, from: Long, through: Long, symbol: String = BTC_CONTRACT): List<TradingFill>

    @Insert
    suspend fun storeTrendHistory(event: TradingTrendHistoryEntity)

    @Query("""
        SELECT COALESCE(
            (SELECT direction FROM trading_trend_history
             WHERE symbol = '$SHARED_TREND_SYMBOL' AND recordedAt <= :time
             ORDER BY recordedAt DESC, id DESC LIMIT 1),
            (SELECT direction FROM trading_trend_history
             WHERE symbol = :symbol AND recordedAt <= :time
             ORDER BY recordedAt DESC, id DESC LIMIT 1)
        )
    """)
    suspend fun trendAt(symbol: String, time: Long): String?

    @Query("SELECT * FROM trading_fills WHERE scope = :scope AND symbol = :symbol AND positionSide = :side AND time BETWEEN :from AND :through ORDER BY time DESC, tradeId DESC")
    suspend fun positionFills(scope: String, symbol: String, side: String, from: Long, through: Long): List<TradingFill>

    @Query("SELECT * FROM trading_position_plans WHERE symbol = :symbol AND scope = (SELECT scope FROM trading_connection WHERE id = 1 AND ciphertext IS NOT NULL)")
    fun observePositionPlans(symbol: String): Flow<List<TradingPositionPlanEntity>>

    @Query("SELECT * FROM trading_position_plans WHERE scope = :scope AND symbol = :symbol AND positionKey = :key")
    suspend fun positionPlan(scope: String, symbol: String, key: String): TradingPositionPlanEntity?

    @Upsert
    suspend fun savePositionPlan(plan: TradingPositionPlanEntity)

    @Query("SELECT * FROM trading_trend WHERE symbol = :symbol")
    fun observeTrend(symbol: String): Flow<TradingTrendEntity?>

    @Query("""
        SELECT trend.* FROM trading_trend AS trend
        WHERE trend.direction IN ('BULLISH', 'BEARISH', 'SIDEWAYS')
        ORDER BY CASE trend.symbol
            WHEN '$SHARED_TREND_SYMBOL' THEN 0
            WHEN '$BTC_CONTRACT' THEN 1
            ELSE 2
        END,
        (SELECT MAX(history.recordedAt) FROM trading_trend_history AS history
         WHERE history.symbol = trend.symbol) DESC,
        trend.symbol
        LIMIT 1
    """)
    suspend fun preferredTrend(): TradingTrendEntity?

    @Upsert
    suspend fun saveTrend(trend: TradingTrendEntity)

    @Query("SELECT * FROM trading_connection WHERE id = 1")
    suspend fun connection(): TradingConnection?

    @Query("SELECT EXISTS(SELECT 1 FROM trading_connection WHERE id = 1 AND ciphertext IS NOT NULL)")
    fun observeConnectionConfigured(): Flow<Boolean>

    @Query("SELECT scope FROM trading_connection WHERE id = 1 AND ciphertext IS NOT NULL")
    fun observeConnectedScope(): Flow<String?>

    @Query("SELECT * FROM trading_performance_days WHERE scope = :scope ORDER BY day")
    suspend fun performanceDays(scope: String): List<TradingPerformanceDayRecord>

    @Query("SELECT * FROM trading_performance_days WHERE scope = :scope ORDER BY day")
    fun observePerformanceDays(scope: String): Flow<List<TradingPerformanceDayRecord>>

    @Query("SELECT closedAt, realizedPnl FROM trading_closed_positions WHERE scope = :scope")
    fun observePerformanceCloses(scope: String): Flow<List<TradingPerformanceClose>>

    @Upsert
    suspend fun savePerformanceDays(days: List<TradingPerformanceDayRecord>)

    @Upsert
    suspend fun saveConnection(connection: TradingConnection)

    @Query("SELECT * FROM trading_fill_sync WHERE scope = :scope AND symbol = :symbol")
    suspend fun fillSync(scope: String, symbol: String): TradingFillSync?

    @Upsert
    suspend fun saveFillSync(sync: TradingFillSync)

    @Query("UPDATE trading_fill_sync SET historyPending = 1 WHERE scope = :scope AND symbol = :symbol")
    suspend fun requestHistoryReplay(scope: String, symbol: String)

    @Query("UPDATE trading_fill_sync SET historyPending = 0 WHERE scope = :scope AND symbol = :symbol AND throughTime = :through")
    suspend fun markHistoryReplayed(scope: String, symbol: String, through: Long)

    @Query("SELECT * FROM trading_fills WHERE scope = (SELECT scope FROM trading_connection WHERE id = 1) AND symbol = :symbol AND time >= :from ORDER BY time DESC, tradeId DESC")
    fun observeFills(symbol: String = BTC_CONTRACT, from: Long = 0): Flow<List<TradingFill>>

    // Each fill is immutable at Binance. Replaying a range must preserve the locally edited reason.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun storeFills(items: List<TradingFill>): List<Long>

    @Query("UPDATE trading_fills SET reason = :reason, reasonUpdatedAt = :editedAt WHERE scope = :scope AND symbol = :symbol AND tradeId = :tradeId")
    suspend fun updateReason(scope: String, tradeId: Long, reason: String?, editedAt: Long?, symbol: String = BTC_CONTRACT): Int

    @Query("SELECT * FROM trading_lines WHERE symbol = :symbol ORDER BY id")
    fun observeLines(symbol: String = BTC_CONTRACT): Flow<List<TradingLine>>

    @Insert
    suspend fun storeLine(line: TradingLine)

    @Query("""
        UPDATE trading_lines
        SET time = CASE WHEN :endpoint = 0 THEN :time ELSE time END,
            price = CASE WHEN :endpoint = 0 THEN :price ELSE price END,
            endTime = CASE WHEN :endpoint = 1 THEN :time ELSE endTime END,
            endPrice = CASE WHEN :endpoint = 1 THEN :price ELSE endPrice END
        WHERE id = :id AND symbol = :symbol AND kind = :kind
    """)
    suspend fun moveLine(id: String, symbol: String, kind: String, endpoint: Int, time: Long?, price: Double): Int

    @Query("DELETE FROM trading_lines WHERE id = :id AND symbol = :symbol")
    suspend fun removeLine(id: String, symbol: String): Int

    @Query("SELECT * FROM trading_candles WHERE symbol = :symbol AND interval = :interval AND time < :before ORDER BY time DESC LIMIT :limit")
    suspend fun candles(interval: String, before: Long, limit: Int, symbol: String = BTC_CONTRACT): List<TradingCandle>

    @Upsert
    suspend fun storeCandles(items: List<TradingCandle>)

    @Query("""
        DELETE FROM trading_candles WHERE symbol = :symbol AND interval = :interval AND time < (
            SELECT time FROM trading_candles WHERE symbol = :symbol AND interval = :interval
            ORDER BY time DESC LIMIT 1 OFFSET (:keep - 1)
        )
    """)
    suspend fun pruneCandles(symbol: String, interval: String, keep: Int)
}
