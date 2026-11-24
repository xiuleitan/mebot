package com.txl.mebot.ui.trading

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.local.TradingCandle
import com.txl.mebot.data.local.TradingClosedPosition
import com.txl.mebot.data.local.TradingFill
import com.txl.mebot.data.local.TradingLine
import com.txl.mebot.data.trading.DrawingTool
import com.txl.mebot.data.trading.FearGreedMarket
import com.txl.mebot.data.trading.FearGreedValue
import com.txl.mebot.data.trading.MarketMarkPrice
import com.txl.mebot.data.trading.MarketQuote
import com.txl.mebot.data.trading.MarketTick
import com.txl.mebot.data.trading.POSITION_HISTORY_PAGE
import com.txl.mebot.data.trading.PositionAlertChoice
import com.txl.mebot.data.trading.TradingContract
import com.txl.mebot.data.trading.TradingHistory
import com.txl.mebot.data.trading.TradingHistorySummary
import com.txl.mebot.data.trading.TradingInterval
import com.txl.mebot.data.trading.TradingLineMove
import com.txl.mebot.data.trading.TradingPoint
import com.txl.mebot.data.trading.TradingPositionDetails
import com.txl.mebot.data.trading.TradingPositionKey
import com.txl.mebot.data.trading.TradingPositionPlan
import com.txl.mebot.data.trading.TradingProblem
import com.txl.mebot.data.trading.TradingTrend
import com.txl.mebot.data.trading.TradingTrendDirection
import com.txl.mebot.data.trading.TradingWatchlistEntry
import com.txl.mebot.data.trading.alerts.ProbeAlertException
import com.txl.mebot.data.trading.tradingProblem
import java.math.BigDecimal
import com.txl.mebot.data.trading.news.TradingNewsRegistrationWorker
import com.txl.mebot.data.trading.news.observeTradingNewsDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val OnlyCurrentPositionPreference = "only_current_position"
private const val ChartBarSpacePreference = "chart_bar_space"
private const val DefaultChartBarSpace = 6f
private const val PushCheckIntervalMillis = 3 * 60_000L
private const val PushCheckMinSpacingMillis = 20_000L
// The bundled KLineChart accepts spacing between 1 and 50 CSS pixels.
private val ChartBarSpaceRange = 1f..50f

internal enum class TradingPushStatus {
    CHECKING, CONNECTED, OFFLINE, UNCONFIRMED, NOT_CONFIGURED,
}

private fun ConnectivityManager.observeDefaultNetwork(): Flow<Network?> = callbackFlow<Unit> {
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(Unit) }
        override fun onLost(network: Network) { trySend(Unit) }
    }
    registerDefaultNetworkCallback(callback)
    trySend(Unit)
    awaitClose { unregisterNetworkCallback(callback) }
}.map { activeNetwork }.distinctUntilChanged()

internal data class TradingState(
    val contract: TradingContract = TradingContract.BTC,
    val interval: TradingInterval = TradingInterval.M15,
    val bars: List<TradingCandle> = emptyList(),
    val tick: TradingCandle? = null,
    val quote: MarketQuote? = null,
    val markPrice: BigDecimal? = null,
    val loading: Boolean = true,
    val marketProblem: TradingProblem? = null,
    val fearGreed: Map<FearGreedMarket, FearGreedValue> = emptyMap(),
    val trend: Result<TradingTrend?>? = null,
    val note: String? = null,
    val connected: Boolean = false,
    val positions: List<TradingPositionDetails>? = null,
    val openPositionSymbols: Set<String> = emptySet(),
    val positionPlans: Map<TradingPositionKey, TradingPositionPlan> = emptyMap(),
    val positionsProblem: TradingProblem? = null,
    val closedPositions: List<TradingClosedPosition> = emptyList(),
    val historySummary: TradingHistorySummary? = null,
    val hasMoreClosedPositions: Boolean = false,
    val fills: List<TradingFill> = emptyList(),
    val onlyCurrentPosition: Boolean = false,
    val lines: List<TradingLine> = emptyList(),
    val notice: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class TradingViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as MebotApplication).container
    private val repository = container.trading
    internal val priceAlerts get() = container.tradingAlerts
    private val news = container.tradingNews
    private val newsDay = observeTradingNewsDay(application).onEach { day ->
        try { news.refreshPriceAlerts(day) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Read the news for the current day even if cleanup fails. */ }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)
    val latestNews = newsDay.flatMapLatest(news::latest)
    val newsHistory = newsDay.flatMapLatest(news::history)
    val hasUnreadNews = newsDay.flatMapLatest(news::hasUnread)

    suspend fun markNewsOpened(): Set<String> = try {
        news.markOpened()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptySet()
    }

    private val mutablePushStatus = MutableStateFlow(TradingPushStatus.CHECKING)
    val pushStatus = mutablePushStatus.asStateFlow()
    private var lastPushCheckAt: Long? = null
    private val display = application.getSharedPreferences("trading_display", Context.MODE_PRIVATE)
    private val watchlistColorPreferences = TradingWatchlistColorPreferences(display)
    val watchlistColorOverrides = watchlistColorPreferences.overrides
    val chartBarSpace: Float
        get() = display.getFloat(ChartBarSpacePreference, DefaultChartBarSpace)
            .takeIf { it in ChartBarSpaceRange } ?: DefaultChartBarSpace
    private val mutable = MutableStateFlow(TradingState(
        contract = TradingContract.defaults.firstOrNull { it.symbol == display.getString("contract", null) } ?: TradingContract.BTC,
        interval = TradingInterval.entries.firstOrNull { it.apiValue == display.getString("interval", null) } ?: TradingInterval.M15,
        onlyCurrentPosition = display.getBoolean(OnlyCurrentPositionPreference, false),
    ))
    val state = mutable.asStateFlow()
    private val contractChoices = MutableStateFlow(TradingContract.defaults.map { TradingWatchlistEntry(it) })
    val watchlist = contractChoices.asStateFlow()
    private val mutableQuotes = MutableStateFlow<Map<TradingContract, MarketQuote>>(emptyMap())
    val contractQuotes = mutableQuotes.asStateFlow()
    val connectionConfiguration = repository.connectionConfigured
        .map { Result.success(it) }
        .catch { emit(Result.failure(it)) }
    private var visible = false
    private var watchlistVisible = false
    private val nextQuoteRefresh = mutableMapOf<TradingContract, Long>()
    private var quoteRetryAfter = 0L
    private val quotePermits = Semaphore(4)
    private var marketJob: Job? = null
    private var watchlistJob: Job? = null
    private var contractChoicesJob: Job? = null
    private var localJob: Job? = null
    private var chartContextJob: Job? = null
    private var pushStatusJob: Job? = null
    private var fearGreedJob: Job? = null
    private var accountJob: Job? = null
    private var connectionJob: Job? = null
    private val fillFrom = MutableStateFlow<Long?>(null)
    private val historyLimit = MutableStateFlow(POSITION_HISTORY_PAGE)

    private fun observeContract() {
        localJob?.cancel()
        val contract = state.value.contract
        localJob = viewModelScope.launch {
            launch {
                historyLimit.collectLatest { limit ->
                    collectLocal(contract, repository.closedPositions(contract, limit + 1)) { state, positions ->
                        if (historyLimit.value != limit) state else state.copy(
                            closedPositions = positions.take(limit),
                            hasMoreClosedPositions = positions.size > limit,
                        )
                    }
                }
            }
            launch {
                collectLocal(contract, repository.closedPositionSummary(contract)) { state, summary ->
                    state.copy(historySummary = summary)
                }
            }
            launch {
                collectLocal(contract, repository.positionPlans(contract)) { state, plans -> state.copy(positionPlans = plans) }
            }
            launch {
                collectLocal(contract, repository.lines(contract)) { state, lines -> state.copy(lines = lines) }
            }
            launch {
                fillFrom.collectLatest { from ->
                    if (from == null) return@collectLatest
                    collectLocal(contract, repository.fills(contract, from)) { state, fills ->
                        if (fillFrom.value == from) state.copy(fills = fills) else state
                    }
                }
            }
        }
    }

    private fun observeChartContext() {
        chartContextJob = viewModelScope.launch {
            launch {
                repository.trend
                    .map { Result.success(it) }
                    .catch { emit(Result.failure(it)) }
                    .collect { result -> update { it.copy(trend = result) } }
            }
            launch {
                repository.note
                    .catch { update { it.copy(notice = TradingProblem.STORAGE.message) } }
                    .collect { note -> update { it.copy(note = note) } }
            }
            launch {
                news.configuredDevice.collectLatest { deviceId ->
                    if (deviceId != null) coroutineScope {
                        launch {
                            val ready = try { priceAlerts.registerNewsDevice() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { false }
                            if (!ready) runCatching { TradingNewsRegistrationWorker.enqueue(getApplication<Application>()) }
                        }
                        try {
                            news.refresh()
                        }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Keep displaying the cached news if the VPS is unavailable. */ }
                    }
                }
            }
        }
    }

    private fun monitorPushStatus() = viewModelScope.launch {
        val connectivity = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        if (connectivity == null) {
            mutablePushStatus.value = TradingPushStatus.UNCONFIRMED
            return@launch
        }
        combine(news.configuredDevice, connectivity.observeDefaultNetwork()) { device, network -> device to network }
            .catch { mutablePushStatus.value = TradingPushStatus.UNCONFIRMED }
            .collectLatest { (device, network) ->
                if (device == null || network == null) {
                    mutablePushStatus.value =
                        if (device == null) TradingPushStatus.NOT_CONFIGURED else TradingPushStatus.OFFLINE
                    return@collectLatest
                }
                mutablePushStatus.value = TradingPushStatus.CHECKING
                while (isActive) {
                    lastPushCheckAt?.let { last ->
                        delay((last + PushCheckMinSpacingMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                    }
                    lastPushCheckAt = SystemClock.elapsedRealtime()
                    val received = try {
                        priceAlerts.verifyPush(device)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    mutablePushStatus.value = if (received) TradingPushStatus.CONNECTED else TradingPushStatus.UNCONFIRMED
                    delay(PushCheckIntervalMillis)
                }
            }
    }

    private suspend fun <T> collectLocal(
        contract: TradingContract,
        flow: Flow<T>,
        transform: (TradingState, T) -> TradingState,
    ) {
        flow.catch { updateContract(contract) { it.copy(notice = TradingProblem.STORAGE.message) } }
            .collect { value -> updateContract(contract) { transform(it, value) } }
    }

    fun setVisible(isVisible: Boolean) {
        if (visible == isVisible) return
        visible = isVisible
        if (isVisible) {
            observeChartContext()
            pushStatusJob = monitorPushStatus()
            observeWatchedContracts()
            startFearGreed()
            startWatchlist()
        } else {
            contractChoicesJob?.cancel()
            localJob?.cancel()
            chartContextJob?.cancel()
            pushStatusJob?.cancel()
            marketJob?.cancel()
            fearGreedJob?.cancel()
            accountJob?.cancel()
            watchlistJob?.cancel()
            resetContract(state.value.contract)
        }
    }

    fun selectContract(contract: TradingContract) {
        if (state.value.contract == contract) return
        display.edit { putString("contract", contract.symbol) }
        resetContract(contract)
        if (visible) startSelectedContract()
    }

    suspend fun selectAlertContract(symbol: String) {
        val current = state.value.contract
        val watched = contractChoices.value.firstOrNull { it.contract.symbol == symbol }?.contract
        try {
            val contract = watched ?: repository.chartContract(symbol)
            if (state.value.contract == current) selectContract(contract)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (state.value.contract == current) update { it.copy(notice = failure.tradingProblem().message) }
        }
    }

    suspend fun addContract(input: String): TradingContract = repository.addWatchedContract(input)

    fun observeCustomContractIcon(symbol: String) = repository.watchedIconPath(symbol)

    fun performance() = repository.performance()

    suspend fun saveContractIcon(symbol: String, image: Uri): Boolean = save {
        repository.saveWatchedIcon(symbol) {
            requireNotNull(getApplication<Application>().contentResolver.openInputStream(image))
        }
    }

    suspend fun removeContract(symbol: String): Boolean = save { repository.removeWatchedContract(symbol) }

    suspend fun saveContractCategory(symbol: String, category: String?, color: WatchlistCategoryColor?): Boolean =
        save {
            val storedCategory = repository.setWatchlistCategory(symbol, category)
            if (storedCategory != null && color != null) watchlistColorPreferences.setColor(storedCategory, color)
        }

    fun saveContractOrder(symbols: List<String>, onSaved: (Boolean) -> Unit) {
        viewModelScope.launch { onSaved(save { repository.reorderWatchedContracts(symbols) }) }
    }

    private fun observeWatchedContracts() {
        contractChoicesJob = viewModelScope.launch {
            var restoring = true
            repository.watchlist
                .catch {
                    update { it.copy(notice = TradingProblem.STORAGE.message) }
                    emit(contractChoices.value)
                }
                .collect { entries ->
                    contractChoices.value = entries
                    val contracts = entries.map { it.contract }
                    val current = state.value.contract
                    val preferred = if (restoring) display.getString("contract", current.symbol) else current.symbol
                    val contract = contracts.firstOrNull { it.symbol == preferred }
                        ?: contracts.firstOrNull() ?: TradingContract.BTC
                    if (current != contract) selectContract(contract)
                    if (restoring) {
                        restoring = false
                        if (marketJob?.isActive != true) startSelectedContract()
                    }
                }
        }
    }

    private fun startSelectedContract() {
        observeContract()
        startMarket()
        startAccount()
    }

    private fun resetContract(contract: TradingContract) {
        fillFrom.value = null
        historyLimit.value = POSITION_HISTORY_PAGE
        update {
            TradingState(
                contract = contract,
                interval = it.interval,
                fearGreed = it.fearGreed,
                trend = it.trend,
                note = it.note,
                connected = it.connected,
                openPositionSymbols = it.openPositionSymbols,
                onlyCurrentPosition = it.onlyCurrentPosition,
            )
        }
    }

    fun showMoreClosedPositions() {
        if (state.value.hasMoreClosedPositions) historyLimit.update { it + POSITION_HISTORY_PAGE }
    }

    fun setWatchlistVisible(isVisible: Boolean) {
        if (watchlistVisible == isVisible) return
        watchlistVisible = isVisible
        if (isVisible) startWatchlist() else watchlistJob?.cancel()
    }

    private fun startWatchlist() {
        if (!visible || !watchlistVisible || watchlistJob?.isActive == true) return
        watchlistJob = viewModelScope.launch {
            val expiry = SystemClock.elapsedRealtime() - 60_000L
            mutableQuotes.update { it.filterKeys { contract -> (nextQuoteRefresh[contract] ?: 0L) >= expiry } }
            contractChoices.map { entries -> entries.mapTo(mutableSetOf()) { it.contract } }
                .distinctUntilChanged()
                .collectLatest { contracts ->
                    nextQuoteRefresh.keys.retainAll(contracts)
                    mutableQuotes.update { it.filterKeys(contracts::contains) }
                    if (contracts.isEmpty()) return@collectLatest
                    while (isActive) {
                        val next = contracts.minOf { nextQuoteRefresh[it] ?: 0L }
                        delay((maxOf(next, quoteRetryAfter) - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                        val now = SystemClock.elapsedRealtime()
                        refreshQuotes(contracts.filter { (nextQuoteRefresh[it] ?: 0L) <= now })
                    }
                }
        }
    }

    private suspend fun refreshQuotes(contracts: List<TradingContract>) {
        coroutineScope {
            contracts.map { contract ->
                async {
                    quotePermits.withPermit {
                        if (SystemClock.elapsedRealtime() < quoteRetryAfter) return@withPermit
                        try {
                            val quote = repository.quote(contract)
                            mutableQuotes.update { it + (contract to quote) }
                        } catch (error: Exception) {
                            val problem = error.tradingProblem()
                            mutableQuotes.update { it - contract }
                            if (problem == TradingProblem.RATE_LIMIT) {
                                quoteRetryAfter = SystemClock.elapsedRealtime() + 60_000L
                            }
                        }
                        nextQuoteRefresh[contract] = SystemClock.elapsedRealtime() + 15_000L
                    }
                }
            }.awaitAll()
        }
    }

    fun selectInterval(interval: TradingInterval) {
        if (state.value.interval == interval) return
        display.edit().putString("interval", interval.apiValue).apply()
        fillFrom.value = null
        update { it.copy(interval = interval, bars = emptyList(), fills = emptyList(), tick = null, loading = true, marketProblem = null) }
        if (visible) startMarket()
    }

    fun toggleFillScope() {
        val onlyCurrentPosition = !state.value.onlyCurrentPosition
        display.edit { putBoolean(OnlyCurrentPositionPreference, onlyCurrentPosition) }
        update { it.copy(onlyCurrentPosition = onlyCurrentPosition) }
    }

    fun saveChartBarSpace(value: Float) {
        if (value in ChartBarSpaceRange && value != chartBarSpace) {
            display.edit { putFloat(ChartBarSpacePreference, value) }
        }
    }

    private fun startFearGreed() {
        fearGreedJob = viewModelScope.launch {
            for (market in FearGreedMarket.entries) {
                launch {
                    container.tradingFearGreed.observe(market).collect { value ->
                        update { it.copy(fearGreed = it.fearGreed + (market to value)) }
                    }
                }
            }
        }
    }

    private fun startMarket() {
        val previous = marketJob
        previous?.cancel()
        val (contract, interval) = state.value
        marketJob = viewModelScope.launch {
            previous?.join()
            if (state.value.bars.isEmpty()) {
                try {
                    val cached = repository.cachedCandles(contract, interval)
                    showBars(contract, interval, cached, fresh = false)
                } catch (error: Exception) {
                    update { it.copy(marketProblem = error.tradingProblem()) }
                }
            }
            var retryMillis = 2_000L
            while (isActive) {
                try {
                    val bars = repository.recent(contract, interval)
                    showBars(contract, interval, bars, fresh = true)
                } catch (error: Exception) {
                    val problem = error.tradingProblem()
                    update { it.copy(loading = false, marketProblem = problem) }
                }
                try {
                    repository.market(contract, interval).collect { market ->
                        if (!showsMarket(contract, interval)) return@collect
                        retryMillis = 2_000L
                        when (market) {
                            is MarketQuote -> update { it.copy(quote = market) }
                            is MarketMarkPrice -> update { it.copy(markPrice = market.price) }
                            is MarketTick -> {
                                if (fillFrom.value == null) fillFrom.value = market.candle.time
                                update { it.copy(tick = market.candle, loading = false, marketProblem = null) }
                                repository.rememberClose(market)
                            }
                        }
                    }
                    update { it.copy(quote = null, markPrice = null, marketProblem = TradingProblem.NETWORK) }
                } catch (error: Exception) {
                    val problem = error.tradingProblem()
                    update { it.copy(quote = null, markPrice = null, marketProblem = problem) }
                }
                delay(if (state.value.marketProblem == TradingProblem.RATE_LIMIT) 60_000 else retryMillis)
                retryMillis = (retryMillis * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun showBars(contract: TradingContract, interval: TradingInterval, bars: List<TradingCandle>, fresh: Boolean) {
        if (!showsMarket(contract, interval)) return
        fillFrom.value = bars.firstOrNull()?.time
        update { state ->
            if (fresh) state.copy(bars = bars, tick = null, loading = false, marketProblem = null)
            else state.copy(bars = bars)
        }
    }

    suspend fun positionReview(position: TradingClosedPosition) = repository.positionReview(position)

    suspend fun candlesForReview(contract: TradingContract, interval: TradingInterval, at: Long) =
        repository.candlesForReview(contract, interval, at)

    suspend fun older(contract: TradingContract, interval: TradingInterval, before: Long, mainChart: Boolean = false): TradingHistory = try {
        repository.earlier(contract, interval, before).also { history ->
            if (mainChart && showsMarket(contract, interval)) {
                history.bars.firstOrNull()?.let { bar -> fillFrom.update { minOf(it ?: bar.time, bar.time) } }
            }
        }
    } catch (error: Exception) {
        val problem = error.tradingProblem()
        update { it.copy(notice = problem.message) }
        TradingHistory(emptyList(), false)
    }

    private fun startAccount() {
        if (connectionJob?.isActive == true || !visible) return
        val previous = accountJob
        previous?.cancel()
        val contract = state.value.contract
        accountJob = viewModelScope.launch {
            try {
                previous?.join()
                val connected = repository.connected()
                updateContract(contract) {
                    it.copy(connected = connected, openPositionSymbols = if (connected) it.openPositionSymbols else emptySet())
                }
                if (!connected) return@launch
                while (isActive) {
                    val problem = syncAccount(contract)
                    delay(if (problem == TradingProblem.RATE_LIMIT) 60_000 else 30_000)
                }
            } catch (error: Exception) {
                val problem = error.tradingProblem()
                updateContract(contract) { it.copy(positionsProblem = problem) }
            }
        }
    }

    private suspend fun syncAccount(contract: TradingContract): TradingProblem? = try {
        repository.syncAccount(
            contract,
            onPosition = { positions -> updateContract(contract) { it.copy(positions = positions, positionsProblem = null) } },
            onPositionError = { problem -> updateContract(contract) { it.copy(positionsProblem = problem) } },
            onOpenPositionSymbols = { symbols -> updateContract(contract) { it.copy(openPositionSymbols = symbols) } },
        )
        null
    } catch (error: Exception) {
        val problem = error.tradingProblem()
        updateContract(contract) {
            if (it.positions == null && it.positionsProblem == null) it.copy(positionsProblem = problem) else it
        }
        problem
    }

    fun connect(key: String, secret: String, complete: (TradingProblem?) -> Unit) {
        if (connectionJob?.isActive == true) return
        connectionJob = viewModelScope.launch {
            accountJob?.cancelAndJoin()
            val problem = try {
                val contract = state.value.contract
                val snapshot = repository.connect(key, secret, contract)
                update {
                    it.copy(
                        connected = true,
                        positions = snapshot.positions.takeIf { _ -> visible && it.contract == contract },
                        openPositionSymbols = snapshot.openSymbols,
                        positionsProblem = null,
                    )
                }
                null
            } catch (error: Exception) {
                error.tradingProblem()
            }
            connectionJob = null
            startAccount()
            complete(problem)
        }
    }

    fun disconnect(complete: (TradingProblem?) -> Unit) {
        if (connectionJob?.isActive == true) return
        connectionJob = viewModelScope.launch {
            accountJob?.cancelAndJoin()
            val problem = try {
                repository.disconnect()
                update { it.copy(connected = false, positions = null, openPositionSymbols = emptySet(), positionsProblem = null) }
                null
            } catch (error: Exception) {
                error.tradingProblem()
            }
            connectionJob = null
            if (problem != null) startAccount()
            complete(problem)
        }
    }

    suspend fun savePositionPlan(key: TradingPositionKey, stopLoss: String, takeProfit: String): Boolean = save {
        check(state.value.positions.orEmpty().any { it.key == key })
        repository.savePositionPlan(key, stopLoss, takeProfit)
    }

    suspend fun setPositionAlert(key: TradingPositionKey, choice: PositionAlertChoice, stopLoss: String) =
        viewModelScope.async {
            val current = state.value
            val position = current.positions.orEmpty().firstOrNull { it.key == key || it.snapshotKey == key }
                ?: throw ProbeAlertException("仓位已变化，请重新打开仓位计划")
            priceAlerts.setPositionAlert(position, choice, stopLoss, current.markPrice)
        }.await()

    suspend fun cancelPositionAlert(requestId: String) =
        viewModelScope.async { priceAlerts.cancel(requestId) }.await()

    suspend fun saveTrend(direction: TradingTrendDirection, bullishReason: String, bearishReason: String): Boolean =
        save { repository.saveTrend(direction, bullishReason, bearishReason) }

    suspend fun saveNote(text: String): Boolean = save {
        val saved = repository.saveNote(text)
        update { it.copy(note = saved) }
    }

    suspend fun saveReason(fill: TradingFill, reason: String): Boolean =
        save { repository.saveReason(fill, reason) }

    fun addLine(contract: TradingContract, tool: DrawingTool, points: List<TradingPoint>) {
        viewModelScope.launch { save { repository.addLine(contract, tool, points) } }
    }

    fun moveLine(contract: TradingContract, move: TradingLineMove, onSaved: (Boolean) -> Unit) {
        viewModelScope.launch { onSaved(save { repository.moveLine(contract, move) }) }
    }

    suspend fun deleteLine(contract: TradingContract, id: String): Boolean = save { repository.deleteLine(contract, id) }

    private suspend fun save(write: suspend () -> Unit): Boolean = try {
        write()
        true
    } catch (error: Exception) {
        val problem = error.tradingProblem()
        update { it.copy(notice = problem.message) }
        false
    }

    fun dismissNotice() = update { it.copy(notice = null) }

    private fun showsMarket(contract: TradingContract, interval: TradingInterval): Boolean =
        visible && state.value.contract == contract && state.value.interval == interval

    private fun updateContract(contract: TradingContract, transform: (TradingState) -> TradingState) =
        update { if (visible && it.contract == contract) transform(it) else it }

    private fun update(transform: (TradingState) -> TradingState) = mutable.update(transform)
}
