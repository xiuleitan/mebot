package com.txl.mebot.data.trading

import android.os.SystemClock
import androidx.room.withTransaction
import com.txl.mebot.data.local.MediaCleanupEntity
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.SHARED_TREND_SYMBOL
import com.txl.mebot.data.local.TradingCandle
import com.txl.mebot.data.local.TradingClosedPosition
import com.txl.mebot.data.local.TradingConnection
import com.txl.mebot.data.local.TradingFill
import com.txl.mebot.data.local.TradingFillSync
import com.txl.mebot.data.local.TradingLine
import com.txl.mebot.data.local.TradingNote
import com.txl.mebot.data.local.TradingPerformanceDayRecord
import com.txl.mebot.data.local.TradingPositionPlanEntity
import com.txl.mebot.data.local.TradingTrendEntity
import com.txl.mebot.data.local.TradingTrendHistoryEntity
import com.txl.mebot.data.local.TradingWatchedContract
import com.txl.mebot.data.media.LocalMediaStore
import com.txl.mebot.data.models.CredentialCipher
import com.txl.mebot.data.models.EncryptedCredential
import java.io.InputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val SharedNoteSymbol = "*"

internal class TradingRepository(
    private val database: MebotDatabase,
    private val cipher: CredentialCipher,
    private val api: BinanceTradingApi = BinanceTradingApi(),
    private val media: LocalMediaStore? = null,
) {
    private val dao get() = database.trading()
    private val accountSync = Mutex()
    private val performanceSync = Mutex()
    private val defaultWatchlist = TradingContract.defaults.mapIndexed { index, contract ->
        TradingWatchedContract(contract.symbol, contract.asset, contract.priceDecimals, Long.MIN_VALUE + index)
    }.associateBy { it.symbol }

    val watchlist: Flow<List<TradingWatchlistEntry>> = dao.observeWatchedContracts()
        .map { rows ->
            visibleWatchlist(rows).map { TradingWatchlistEntry(TradingContract(it.symbol, it.asset, it.priceDecimals), it.category) }
        }
        .distinctUntilChanged()

    val watchedContracts: Flow<List<TradingContract>> = watchlist.map { entries -> entries.map { it.contract } }
        .distinctUntilChanged()

    suspend fun chartContract(symbol: String): TradingContract =
        dao.watchedContract(symbol)?.let { TradingContract(it.symbol, it.asset, it.priceDecimals) }
            ?: TradingContract.defaults.firstOrNull { it.symbol == symbol }
            ?: api.findContract(symbol)
            ?: throw TradingException(TradingProblem.CONTRACT_UNAVAILABLE)

    suspend fun addWatchedContract(input: String): TradingContract {
        val symbol = normalizeUsdtSymbol(input) ?: throw TradingException(TradingProblem.CONTRACT_INPUT)
        checkCanAddContract(symbol)
        val contract = chartContract(symbol)
        database.withTransaction {
            checkCanAddContract(symbol)
            val row = TradingWatchedContract(contract.symbol, contract.asset, contract.priceDecimals, System.currentTimeMillis())
            dao.saveWatchedContracts(listOf(row))
        }
        return contract
    }

    suspend fun reorderWatchedContracts(symbols: List<String>) = database.withTransaction {
        val current = visibleWatchlist(dao.watchedContracts())
        if (current.map { it.symbol } == symbols) return@withTransaction
        val rows = current.associateBy { it.symbol }
        require(symbols.size == rows.size && symbols.toSet() == rows.keys)
        val reordered = symbols.map(rows::getValue)
        require(reordered.map { it.category } == current.map { it.category })
        saveWatchlistOrder(current, reordered)
    }

    suspend fun setWatchlistCategory(symbol: String, input: String?): String? = database.withTransaction {
        val name = input?.trim()?.takeUnless { it.isEmpty() }
        require(name == null || name.length <= WATCHLIST_CATEGORY_LIMIT && name.none { it.isISOControl() })
        val current = visibleWatchlist(dao.watchedContracts())
        val index = current.indexOfFirst { it.symbol == symbol }
        check(index >= 0)
        val row = current[index]
        val category = name?.let { candidate ->
            current.firstOrNull { it.category.equals(candidate, ignoreCase = true) }?.category ?: candidate
        }
        if (row.category == category) return@withTransaction category
        val updated = current.toMutableList()
        updated.removeAt(index)
        val groupEnd = if (category == null) -1 else updated.indexOfLast { it.category == category }
        updated.add(if (groupEnd >= 0) groupEnd + 1 else index.coerceAtMost(updated.size), row.copy(category = category))
        saveWatchlistOrder(current, groupWatchlist(updated))
        category
    }

    private suspend fun saveWatchlistOrder(current: List<TradingWatchedContract>, ordered: List<TradingWatchedContract>) {
        val previous = current.associateBy { it.symbol }
        val changes = ordered.mapIndexedNotNull { index, row ->
            row.copy(sortOrder = index).takeUnless { it == previous[row.symbol] }
        }
        dao.saveWatchedContracts(changes)
    }

    suspend fun removeWatchedContract(symbol: String) = database.withTransaction {
        val row = requireWatchedContract(symbol)
        if (symbol in defaultWatchlist) {
            dao.saveWatchedContracts(listOf(row.copy(hidden = true, sortOrder = null, iconAssetId = null, category = null)))
        } else {
            dao.deleteWatchedContract(symbol)
        }
        releaseWatchlistIcon(row.iconAssetId)
    }

    fun watchedIconPath(symbol: String): Flow<String?> = dao.observeWatchedIconPath(symbol).distinctUntilChanged()

    suspend fun saveWatchedIcon(symbol: String, openImage: () -> InputStream) {
        requireNotNull(media).importImage(openImage, onImported = { image ->
            val row = requireWatchedContract(symbol)
            if (row.iconAssetId != image.id) {
                dao.saveWatchedContracts(listOf(row.copy(iconAssetId = image.id)))
                releaseWatchlistIcon(row.iconAssetId)
            }
        })
    }

    private fun visibleWatchlist(rows: List<TradingWatchedContract>): List<TradingWatchedContract> {
        val savedSymbols = rows.mapTo(mutableSetOf()) { it.symbol }
        val ordered = (defaultWatchlist.values.filterNot { it.symbol in savedSymbols } + rows)
            .filterNot { it.hidden }
            .sortedWith(compareBy({ it.sortOrder ?: Int.MAX_VALUE }, { it.addedAt }, { it.symbol }))
        return groupWatchlist(ordered)
    }

    private fun groupWatchlist(ordered: List<TradingWatchedContract>): List<TradingWatchedContract> {
        val groups = ordered.filter { it.category != null }.groupBy { it.category }
        val emitted = mutableSetOf<String>()
        return buildList {
            for (row in ordered) {
                val category = row.category
                if (category == null) add(row)
                else if (emitted.add(category)) addAll(groups.getValue(category))
            }
        }
    }

    private suspend fun requireWatchedContract(symbol: String): TradingWatchedContract =
        checkNotNull((dao.watchedContract(symbol) ?: defaultWatchlist[symbol])?.takeUnless { it.hidden })

    private suspend fun checkCanAddContract(symbol: String) {
        if ((dao.watchedContract(symbol) ?: defaultWatchlist[symbol])?.hidden == false) {
            throw TradingException(TradingProblem.CONTRACT_ALREADY_ADDED)
        }
    }

    private suspend fun releaseWatchlistIcon(id: String?) {
        val mediaDao = database.mediaDao()
        val asset = id?.let { mediaDao.asset(it) } ?: return
        if (mediaDao.deleteIfUnused(asset.id) == 1) {
            asset.relativePath?.let { mediaDao.queueCleanup(MediaCleanupEntity(it)) }
        }
    }

    fun fills(contract: TradingContract, from: Long): Flow<List<TradingFill>> =
        dao.observeFills(contract.symbol, from).distinctUntilChanged()

    fun lines(contract: TradingContract): Flow<List<TradingLine>> =
        dao.observeLines(contract.symbol).distinctUntilChanged()

    val note: Flow<String> = dao.observeNote(SharedNoteSymbol)
        .onStart {
            database.withTransaction {
                if (dao.note(SharedNoteSymbol) == null) {
                    val legacy = dao.legacyNotes(SharedNoteSymbol).filter { it.text.isNotBlank() }.distinctBy { it.text }
                    val text = if (legacy.size <= 1) legacy.firstOrNull()?.text.orEmpty()
                        else legacy.joinToString("\n\n") { "【${it.symbol}】\n${it.text}" }
                    dao.saveNote(TradingNote(SharedNoteSymbol, text))
                }
            }
        }
        .map { it.orEmpty() }
        .distinctUntilChanged()

    suspend fun saveNote(text: String): String {
        // An empty shared row is intentional and prevents importing old notes again.
        val saved = text.takeUnless { it.isBlank() }.orEmpty()
        dao.saveNote(TradingNote(SharedNoteSymbol, saved))
        return saved
    }

    fun closedPositions(contract: TradingContract, limit: Int): Flow<List<TradingClosedPosition>> =
        dao.observeClosedPositions(contract.symbol, limit).distinctUntilChanged()

    fun closedPositionSummary(contract: TradingContract): Flow<TradingHistorySummary> =
        dao.observeClosedPositionPnl(contract.symbol)
            .map { amounts ->
                var netPnl = BigDecimal.ZERO
                var wins = 0
                for (amount in amounts) {
                    val pnl = amount.toBigDecimal()
                    netPnl += pnl
                    if (pnl.signum() > 0) wins++
                }
                TradingHistorySummary(
                    netPnl = netPnl.stripTrailingZeros(),
                    winRatePercent = if (amounts.isEmpty()) null else wins * 100.0 / amounts.size,
                )
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    val connectionConfigured: Flow<Boolean> get() = dao.observeConnectionConfigured().distinctUntilChanged()

    fun positionPlans(contract: TradingContract): Flow<Map<TradingPositionKey, TradingPositionPlan>> =
        dao.observePositionPlans(contract.symbol).map { plans ->
            plans.associate { TradingPositionKey(it.scope, it.positionKey, it.symbol) to TradingPositionPlan(it.stopLoss, it.takeProfit) }
        }.distinctUntilChanged()

    val trend: Flow<TradingTrend?> = dao.observeTrend(SHARED_TREND_SYMBOL)
        .onStart {
            database.withTransaction {
                val previous = dao.preferredTrend() ?: return@withTransaction
                if (previous.symbol != SHARED_TREND_SYMBOL) {
                    writeSharedTrend(previous.copy(symbol = SHARED_TREND_SYMBOL), previous)
                }
            }
        }
        .map { trend ->
            trend?.let {
                TradingTrend(
                    direction = TradingTrendDirection.valueOf(it.direction),
                    bullishReason = it.bullishReason,
                    bearishReason = it.bearishReason,
                    legacyReason = it.reason,
                )
            }
        }
        .distinctUntilChanged()

    suspend fun saveTrend(direction: TradingTrendDirection, bullishReason: String, bearishReason: String) {
        val bullish = bullishReason.trim()
        val bearish = bearishReason.trim()
        require(bullish.length <= TREND_REASON_LIMIT && bearish.length <= TREND_REASON_LIMIT)
        database.withTransaction {
            val previous = dao.preferredTrend()
            writeSharedTrend(
                TradingTrendEntity(
                    symbol = SHARED_TREND_SYMBOL,
                    direction = direction.name,
                    reason = previous?.reason.orEmpty(),
                    bullishReason = bullish,
                    bearishReason = bearish,
                ),
                previous,
            )
        }
    }

    private suspend fun writeSharedTrend(trend: TradingTrendEntity, previous: TradingTrendEntity?) {
        if (previous?.symbol != SHARED_TREND_SYMBOL || previous.direction != trend.direction) {
            dao.storeTrendHistory(
                TradingTrendHistoryEntity(
                    symbol = SHARED_TREND_SYMBOL,
                    direction = trend.direction,
                    recordedAt = System.currentTimeMillis(),
                ),
            )
        }
        dao.saveTrend(trend)
    }

    suspend fun savePositionPlan(key: TradingPositionKey, stopLoss: String, takeProfit: String) {
        val stop = stopLoss.takeIf { it.isNotBlank() }?.let {
            requireNotNull(parsePositionPlanPrice(it)).stripTrailingZeros().toPlainString()
        }
        val target = takeProfit.takeIf { it.isNotBlank() }?.let {
            requireNotNull(parsePositionPlanPrice(it)).stripTrailingZeros().toPlainString()
        }
        dao.savePositionPlan(TradingPositionPlanEntity(key.scope, key.symbol, key.positionKey, stop, target))
    }

    suspend fun connected(): Boolean = dao.connection()?.ciphertext != null

    val performanceAccount: Flow<String?> get() = dao.observeConnectedScope().distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun performance(): Flow<TradingPerformance?> = performanceAccount.flatMapLatest { scope ->
        if (scope == null) flowOf(null)
        else combine(dao.observePerformanceDays(scope), dao.observePerformanceCloses(scope)) { days, closes ->
            summarizeTradingPerformance(days.map { it.toModel() }, closes, tradingDay(System.currentTimeMillis()))
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun syncDailyPerformance(): Boolean = performanceSync.withLock {
        val connection = dao.connection()?.takeIf { it.ciphertext != null } ?: return@withLock true
        val scope = connection.scope
        val account = api.account(readCredentials(connection))
        val today = tradingDay(account.serverTime)
        var days = dao.performanceDays(scope)
        if (days.lastOrNull()?.day != today - 1) {
            val stored = days.associateBy { it.day }
            val snapshots = account.dailyWalletBalances().filter { tradingDay(it.time) < today }
                .groupBy { tradingDay(it.time) }.mapNotNull { (day, readings) ->
                    val reading = readings.maxBy { it.time }
                    val previous = stored[day]
                    val balance = reading.balance.toPlainString()
                    val row = if (previous != null && previous.time == reading.time) previous.copy(balance = balance)
                        else TradingPerformanceDayRecord(scope, day, reading.time, balance)
                    row.takeUnless { it == previous }
                }
            if (!savePerformanceDays(scope, snapshots)) return@withLock true
            days = dao.performanceDays(scope)
        }
        val oldestTransfer = oldestTradeTime(account.serverTime)
        val pending = days.zipWithNext().filter { (previous, current) ->
            current.transfersFrom != previous.time && previous.time >= oldestTransfer
        }
        if (pending.isNotEmpty()) {
            val transfers = account.walletTransfers(pending.first().first.time + 1, pending.last().second.time)
            val verified = pending.map { (previous, current) ->
                val totals = dailyTransfers(previous.time, current.time, transfers)
                current.copy(
                    transfersFrom = totals.from,
                    deposited = totals.deposited.toPlainString(),
                    withdrawn = totals.withdrawn.toPlainString(),
                    weightedNet = totals.weightedNet.toPlainString(),
                )
            }
            if (!savePerformanceDays(scope, verified)) return@withLock true
        }
        days.isEmpty() || days.last().day == today - 1
    }

    private suspend fun savePerformanceDays(scope: String, days: List<TradingPerformanceDayRecord>): Boolean =
        database.withTransaction {
            val active = dao.connection()
            if (active?.scope != scope || active.ciphertext == null) return@withTransaction false
            if (days.isNotEmpty()) dao.savePerformanceDays(days)
            true
        }

    private fun TradingPerformanceDayRecord.toModel(): TradingPerformanceDay = TradingPerformanceDay(
        time,
        balance.toBigDecimal(),
        transfersFrom?.let { from ->
            TradingDailyTransfers(
                from,
                checkNotNull(deposited).toBigDecimal(),
                checkNotNull(withdrawn).toBigDecimal(),
                checkNotNull(weightedNet).toBigDecimal(),
            )
        },
    )

    suspend fun cachedCandles(
        contract: TradingContract,
        interval: TradingInterval,
        before: Long = Long.MAX_VALUE,
    ): List<TradingCandle> = dao.candles(interval.apiValue, before, CANDLE_PAGE, contract.symbol).asReversed()

    suspend fun recent(contract: TradingContract, interval: TradingInterval): List<TradingCandle> =
        api.candles(contract, interval).also { cacheCandles(it) }

    suspend fun earlier(contract: TradingContract, interval: TradingInterval, before: Long): TradingHistory = try {
        val bars = api.candles(contract, interval, before)
        cacheCandles(bars)
        TradingHistory(bars, bars.size >= CANDLE_PAGE)
    } catch (error: TradingException) {
        val bars = cachedCandles(contract, interval, before)
        if (bars.isEmpty()) throw error
        TradingHistory(bars, bars.size >= CANDLE_PAGE)
    }

    suspend fun candlesForReview(contract: TradingContract, interval: TradingInterval, at: Long): List<TradingCandle> {
        val before = minOf(interval.shiftCandles(at, CANDLE_PAGE / 4), System.currentTimeMillis() + 1)
        val cached = cachedCandles(contract, interval, before)
        if (cached.coversCandle(interval, at)) return cached
        val bars = api.candles(contract, interval, before)
        if (!bars.coversCandle(interval, at)) throw TradingException(TradingProblem.RESPONSE)
        cacheCandles(bars)
        return bars
    }

    private suspend fun cacheCandles(bars: List<TradingCandle>) {
        val first = bars.firstOrNull() ?: return
        database.withTransaction {
            dao.storeCandles(bars)
            dao.pruneCandles(first.symbol, first.interval, CANDLE_CACHE_LIMIT)
        }
    }

    suspend fun positionReview(position: TradingClosedPosition): TradingPositionReview = database.withTransaction {
        val fills = position.reviewFills(
            dao.positionFills(position.scope, position.symbol, position.positionSide, position.firstTradeAt, position.closedAt),
        )
        val plan = if (position.openingKnown) {
            dao.positionPlan(position.scope, position.symbol, "${position.positionSide}:${position.side}:fill:${position.firstTradeId}")
                ?.let { TradingPositionPlan(it.stopLoss, it.takeProfit) }
        } else null
        TradingPositionReview(fills, plan)
    }

    fun market(contract: TradingContract, interval: TradingInterval): Flow<MarketUpdate> = api.market(contract, interval)

    suspend fun quote(contract: TradingContract): MarketQuote = api.quote(contract)

    suspend fun rememberClose(tick: MarketTick) {
        if (tick.closed) cacheCandles(listOf(tick.candle))
    }

    suspend fun connect(key: String, secret: String, contract: TradingContract): TradingPositionSnapshot<TradingPositionDetails> =
        accountSync.withLock { withContext(Dispatchers.IO) { connectAccount(key, secret, contract) } }

    private suspend fun connectAccount(key: String, secret: String, contract: TradingContract): TradingPositionSnapshot<TradingPositionDetails> {
        val credentials = TradingCredentials(key.trim(), secret.trim())
        if (!credentials.isValid()) throw TradingException(TradingProblem.INPUT)
        val account = api.account(credentials)
        account.verifyReadOnly()
        val snapshot = account.positions(contract)
        val scope = MessageDigest.getInstance("SHA-256").digest(credentials.key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val encrypted = cipher.encrypt(JSONObject().put("key", credentials.key).put("secret", credentials.secret).toString())
        val previous = dao.fillSync(scope, contract.symbol)
        val details = describePositions(scope, contract.symbol, snapshot.positions, previous)
        dao.saveConnection(TradingConnection(scope = scope, ciphertext = encrypted.ciphertext, iv = encrypted.iv))
        return TradingPositionSnapshot(details, snapshot.openSymbols)
    }

    suspend fun disconnect() = accountSync.withLock {
        database.withTransaction {
            dao.connection()?.let { dao.saveConnection(it.copy(ciphertext = null, iv = null)) }
        }
    }

    suspend fun syncAccount(
        contract: TradingContract,
        onPosition: (List<TradingPositionDetails>) -> Unit,
        onPositionError: (TradingProblem) -> Unit,
        onOpenPositionSymbols: (Set<String>) -> Unit = {},
    ) = accountSync.withLock {
        val connection = dao.connection()?.takeIf { it.ciphertext != null } ?: return@withLock
        val scope = connection.scope
        val symbol = contract.symbol
        val account = api.account(readCredentials(connection))
        val snapshot = try {
            account.positions(contract)
        } catch (error: TradingException) {
            onPositionError(error.problem)
            null
        }
        if (snapshot != null) onOpenPositionSymbols(snapshot.openSymbols)
        val positions = snapshot?.positions
        // Enclose the position request between two exchange clock reads. Only use
        // its balances for replay when the synchronized fills show no trade in that window.
        val snapshotEnd = if (positions != null) {
            try {
                api.serverTime().takeIf { it >= account.serverTime }
            } catch (_: TradingException) {
                null
            }
        } else null
        val stop = snapshotEnd ?: account.serverTime
        val oldest = oldestTradeTime(stop)
        val previous = dao.fillSync(scope, symbol)?.takeIf { it.throughTime in (oldest - 1)..stop }
        val coverageStart = previous?.fromTime ?: oldest
        var coverage = previous
        var from = previous?.let { maxOf(oldest, it.fromTime, it.throughTime - 60_000) } ?: oldest
        try {
            if (previous == null && positions != null) {
                onPosition(describePositions(scope, symbol, positions, null))
            }
            while (from <= stop) {
                val to = minOf(stop, from + TRADE_WINDOW_MS - 1)
                loadTradeWindow(account, scope, contract, from, to)
                coverage = database.withTransaction {
                    val saved = dao.fillSync(scope, symbol)
                    // An overlap scan may finish before an already committed checkpoint.
                    TradingFillSync(
                        scope, symbol, coverageStart,
                        maxOf(to, saved?.throughTime?.takeIf { saved.fromTime == coverageStart } ?: to),
                        saved?.fromTime != coverageStart || saved.historyPending,
                    ).also { dao.saveFillSync(it) }
                }
                from = to + 1
            }
            val synced = coverage
            if (positions != null && synced != null && snapshotEnd != null && synced.throughTime == snapshotEnd &&
                synced.historyPending && positions.none { it.updatedAt > snapshotEnd }
            ) {
                archiveClosedPositions(synced, account.serverTime, positions)
            }
        } finally {
            if (positions != null && currentCoroutineContext().isActive) {
                onPosition(describePositions(scope, symbol, positions, coverage))
            }
        }
    }

    private suspend fun readCredentials(connection: TradingConnection) = withContext(Dispatchers.IO) {
        val encrypted = EncryptedCredential(checkNotNull(connection.ciphertext), checkNotNull(connection.iv))
        val plaintext = JSONObject(cipher.decrypt(encrypted))
        TradingCredentials(plaintext.getString("key"), plaintext.getString("secret"))
    }

    private suspend fun archiveClosedPositions(
        sync: TradingFillSync,
        snapshotStart: Long,
        positions: List<TradingPosition>,
    ) = withContext(Dispatchers.Default) {
        val fills = dao.fillsBetween(sync.scope, sync.fromTime, sync.throughTime, sync.symbol)
        if (fills.firstOrNull()?.let { it.time >= snapshotStart } == true) return@withContext
        val replay = closedTradingPositions(fills, positions)
        database.withTransaction {
            if (replay.positions.isNotEmpty()) {
                val alreadySaved = dao.closedPositions(sync.scope, sync.symbol, sync.fromTime)
                    .associateBy { it.positionSide to it.closingTradeId }
                val updates = replay.positions.mapNotNull { episode ->
                    val saved = alreadySaved[episode.positionSide to episode.closingTradeId]
                    if (saved != null && episode.copy(trendAtOpen = saved.trendAtOpen) == saved) return@mapNotNull null
                    if (saved != null && !episode.openingKnown &&
                        (saved.openingKnown || saved.firstTradeAt < episode.firstTradeAt ||
                            saved.firstTradeAt == episode.firstTradeAt && saved.firstTradeId < episode.firstTradeId)
                    ) return@mapNotNull null
                    val trend = if (episode.openingKnown) dao.trendAt(sync.symbol, episode.firstTradeAt) else null
                    episode.copy(trendAtOpen = trend).takeIf { it != saved }
                }
                if (updates.isNotEmpty()) dao.saveClosedPositions(updates)
            }
            if (replay.complete) dao.markHistoryReplayed(sync.scope, sync.symbol, sync.throughTime)
        }
    }

    private suspend fun describePositions(
        scope: String,
        symbol: String,
        positions: List<TradingPosition>,
        sync: TradingFillSync?,
    ): List<TradingPositionDetails> = withContext(Dispatchers.Default) {
        positions.map { position ->
            val opening = if (sync != null && position.updatedAt in sync.fromTime..sync.throughTime) {
                openingPositionFill(
                    position, dao.positionFills(scope, symbol, position.positionSide, sync.fromTime, position.updatedAt),
                )
            } else null
            val side = "${position.positionSide}:${position.side}"
            val snapshot = TradingPositionKey(scope, "$side:snapshot:${position.updatedAt}:${position.entryPrice}:${position.quantity}", symbol)
            val key = opening?.let { TradingPositionKey(scope, "$side:fill:${it.tradeId}", symbol) } ?: snapshot
            if (key != snapshot) {
                // Keep a plan entered before this same position snapshot had enough synced fills.
                database.withTransaction {
                    if (dao.positionPlan(scope, symbol, key.positionKey) == null) {
                        dao.positionPlan(scope, symbol, snapshot.positionKey)?.let {
                            dao.savePositionPlan(it.copy(positionKey = key.positionKey))
                        }
                    }
                }
            }
            val openingTrend = opening?.let { dao.trendAt(symbol, it.time) }
                ?.let(TradingTrendDirection::valueOf)
            TradingPositionDetails(position, key, snapshot, opening, openingTrend)
        }
    }

    private suspend fun loadTradeWindow(account: BinanceTradingApi.Account, scope: String, contract: TradingContract, from: Long, to: Long) {
        val items = account.fills(scope, contract, from, to)
        if (items.size < TRADE_PAGE) {
            if (items.isNotEmpty()) database.withTransaction {
                if (dao.storeFills(items).any { it != -1L }) dao.requestHistoryReplay(scope, contract.symbol)
            }
            return
        }
        // A full response can be truncated. Bisect until every execution in the range is covered.
        if (from == to) throw TradingException(TradingProblem.RESPONSE)
        val middle = from + (to - from) / 2
        loadTradeWindow(account, scope, contract, from, middle)
        loadTradeWindow(account, scope, contract, middle + 1, to)
    }

    suspend fun saveReason(fill: TradingFill, reason: String) {
        val text = reason.trim().takeIf { it.isNotEmpty() }
        require(text == null || text.length <= 1_000)
        check(dao.updateReason(fill.scope, fill.tradeId, text, text?.let { System.currentTimeMillis() }, fill.symbol) == 1)
    }

    suspend fun addLine(contract: TradingContract, tool: DrawingTool, points: List<TradingPoint>) {
        require(points.size == if (tool == DrawingTool.HORIZONTAL) 1 else 2)
        require(points.all { it.time > 0 && it.price.isFinite() && it.price > 0 })
        val start = points.first()
        val end = points.getOrNull(1)
        dao.storeLine(
            TradingLine(
                id = UUID.randomUUID().toString(),
                kind = tool.overlay,
                time = start.time.takeIf { end != null },
                price = start.price,
                endTime = end?.time,
                endPrice = end?.price,
                symbol = contract.symbol,
            ),
        )
    }

    suspend fun deleteLine(contract: TradingContract, id: String) {
        dao.removeLine(id, contract.symbol)
    }

    suspend fun moveLine(contract: TradingContract, move: TradingLineMove) {
        val (id, endpoint, time, price) = move
        require(id.isNotBlank() && price.isFinite() && price > 0)
        val tool = if (time == null) {
            require(endpoint == 0)
            DrawingTool.HORIZONTAL
        } else {
            require(endpoint in 0..1 && time > 0)
            DrawingTool.SEGMENT
        }
        check(dao.moveLine(id, contract.symbol, tool.overlay, endpoint, time, price) == 1)
    }
}

internal fun Exception.tradingProblem(): TradingProblem = when (this) {
    is CancellationException -> throw this
    is TradingException -> problem
    else -> TradingProblem.STORAGE
}
