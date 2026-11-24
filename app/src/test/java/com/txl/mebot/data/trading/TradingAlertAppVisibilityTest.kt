package com.txl.mebot.data.trading

import android.app.Activity
import android.app.Application
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.trading.alerts.TradingAlertAppVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TradingAlertAppVisibilityTest {
    @Test
    fun foregroundAlertsAreBriefAndOpeningTheAppAcknowledgesAnEarlierBackgroundAlert() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        val visibility = TradingAlertAppVisibility(app)
        app.registerActivityLifecycleCallbacks(visibility)
        var openings = 0
        visibility.onAppOpened = { openings++ }
        val background = visibility.snapshot()
        val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        try {
            val foreground = visibility.snapshot()
            assertNull(background.durationMillis)
            assertFalse(background.acknowledgedBy(background))
            assertEquals(5_000L, foreground.durationMillis)
            assertEquals(1, openings)
            assertTrue(background.acknowledgedBy(foreground))
            activity.pause()
            val backAgain = visibility.snapshot()
            assertFalse(backAgain.visible)
            assertTrue(background.acknowledgedBy(backAgain))
            assertFalse(foreground.acknowledgedBy(backAgain))

            activity.resume()
            shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
            assertFalse(visibility.snapshot().visible)
        } finally {
            activity.pause().stop().destroy()
            app.unregisterActivityLifecycleCallbacks(visibility)
        }
    }
}
