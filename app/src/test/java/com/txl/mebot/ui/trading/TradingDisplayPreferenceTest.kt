package com.txl.mebot.ui.trading

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.local.TradingWatchedContract
import com.txl.mebot.data.trading.TradingContract
import com.txl.mebot.data.trading.TradingInterval
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MebotApplication::class)
class TradingDisplayPreferenceTest {
    @Test
    fun anAlertOpensTheSavedContractWithItsPricePrecision() = runBlocking<Unit> {
        val application = ApplicationProvider.getApplicationContext<MebotApplication>()
        val contract = TradingContract("TQQQUSDT", "TQQQ", 2)
        application.container.database.trading().saveWatchedContracts(listOf(
            TradingWatchedContract(contract.symbol, contract.asset, contract.priceDecimals, 10L),
        ))
        val model = TradingViewModel(application)

        model.selectAlertContract(contract.symbol)

        assertEquals(contract, model.state.value.contract)
    }

    @Test
    fun watchlistColorsSeparateDefaultCategoriesAndRestoreTheUserChoice() {
        val application = ApplicationProvider.getApplicationContext<MebotApplication>()
        val display = application.getSharedPreferences("trading_display", Context.MODE_PRIVATE)
        val initial = TradingWatchlistColorPreferences(display)
        val names = listOf("大盘", "原油", "贵金属", "加密货币", "半导体")
        val defaults = watchlistCategoryColors(names, initial.overrides.value)
        assertEquals(names.size, defaults.values.toSet().size)

        val picked = WatchlistCategoryColor.INDIGO
        initial.setColor("原油", picked)
        val reopened = TradingWatchlistColorPreferences(display)
        assertEquals(mapOf("原油" to picked), reopened.overrides.value)
        val colors = watchlistCategoryColors(names, reopened.overrides.value)
        assertEquals(picked, colors["原油"])
        assertEquals(names.size, colors.values.toSet().size)
    }

    @Test
    fun fillScopeRestoresTheLastChoiceAfterReopeningAndSwitchingContracts() {
        val application = ApplicationProvider.getApplicationContext<MebotApplication>()
        val initial = TradingViewModel(application)
        assertFalse(initial.state.value.onlyCurrentPosition)

        initial.toggleFillScope()
        assertTrue(initial.state.value.onlyCurrentPosition)
        val reopened = TradingViewModel(application)
        assertTrue(reopened.state.value.onlyCurrentPosition)

        reopened.selectContract(TradingContract.ETH)
        reopened.selectInterval(TradingInterval.H4)
        assertTrue(reopened.state.value.onlyCurrentPosition)
        reopened.toggleFillScope()
        assertFalse(reopened.state.value.onlyCurrentPosition)
        assertFalse(TradingViewModel(application).state.value.onlyCurrentPosition)
    }

    @Test
    fun barSpaceRestoresTheLastZoomAfterReopeningAndSwitchingWindows() {
        val application = ApplicationProvider.getApplicationContext<MebotApplication>()
        val initial = TradingViewModel(application)
        assertEquals(6f, initial.chartBarSpace, 0f)
        initial.saveChartBarSpace(14.25f)

        val reopened = TradingViewModel(application)
        reopened.selectContract(TradingContract.ETH)
        reopened.selectInterval(TradingInterval.H4)
        assertEquals(14.25f, reopened.chartBarSpace, 0f)
        reopened.saveChartBarSpace(Float.NaN)
        reopened.saveChartBarSpace(0f)
        reopened.saveChartBarSpace(51f)
        assertEquals(14.25f, reopened.chartBarSpace, 0f)

        reopened.saveChartBarSpace(3.5f)
        assertEquals(3.5f, TradingViewModel(application).chartBarSpace, 0f)
    }
}
