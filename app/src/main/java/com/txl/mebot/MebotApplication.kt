package com.txl.mebot

import android.app.Application
import com.txl.mebot.data.AppContainer
import com.txl.mebot.data.trading.alerts.TradingAlertAppVisibility

class MebotApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    internal val tradingAlertVisibility by lazy { TradingAlertAppVisibility(this) }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(tradingAlertVisibility)
        container.startMaintenance()
    }

    override fun onTerminate() { container.stopMaintenance(); super.onTerminate() }
}
