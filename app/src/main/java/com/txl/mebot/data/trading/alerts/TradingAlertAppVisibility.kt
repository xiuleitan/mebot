package com.txl.mebot.data.trading.alerts

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.os.Bundle
import android.os.PowerManager

internal data class TradingAlertAppState(val resumes: Long, val visible: Boolean) {
    val durationMillis: Long? get() = if (visible) 5_000L else null

    fun acknowledgedBy(current: TradingAlertAppState): Boolean =
        !visible && (current.visible || current.resumes > resumes)
}

/** Uses activity callbacks so opening any mebot screen stops the background alarm immediately. */
internal class TradingAlertAppVisibility(application: Application) : Application.ActivityLifecycleCallbacks {
    private val power = application.getSystemService(PowerManager::class.java)
    private val keyguard = application.getSystemService(KeyguardManager::class.java)
    private var resumedActivity: Activity? = null
    @Volatile private var state = TradingAlertAppState(0, false)

    // The ringing service installs the listener on the main thread for its own lifetime.
    var onAppOpened: (() -> Unit)? = null

    fun snapshot(): TradingAlertAppState = state.let { current ->
        if (current.visible && (!power.isInteractive || keyguard.isKeyguardLocked)) {
            current.copy(visible = false)
        } else current
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivity = activity
        state = TradingAlertAppState(state.resumes + 1, true)
        onAppOpened?.invoke()
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity === activity) {
            resumedActivity = null
            state = state.copy(visible = false)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
