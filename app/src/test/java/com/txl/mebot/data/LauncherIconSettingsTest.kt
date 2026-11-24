package com.txl.mebot.data

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.MainActivity
import com.txl.mebot.R
import com.txl.mebot.data.preferences.LauncherIcon
import com.txl.mebot.data.preferences.LauncherIconSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = Application::class)
class LauncherIconSettingsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val packageManager = app.packageManager
    private val settings = LauncherIconSettings(app)

    @Test fun onlyTheChosenIconCanLaunchAndItSurvivesReloadingTheSettings() = runBlocking {
        assertEquals(LauncherIcon.Pink, settings.selected())
        assertLauncher(LauncherIcon.Pink)

        for (icon in listOf(LauncherIcon.Classic, LauncherIcon.LuckyCat, LauncherIcon.Classic, LauncherIcon.Pink, LauncherIcon.LuckyCat)) {
            settings.select(icon)
            assertLauncher(icon)
            assertEquals(icon, LauncherIconSettings(app).selected())
        }
    }

    @Test fun anInterruptedSwitchRecoversOneLauncherEntry() = runBlocking {
        packageManager.setComponentEnabledSetting(
            ComponentName.createRelative(app, LauncherIcon.LuckyCat.alias),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP,
        )
        assertEquals(LauncherIcon.Pink, settings.selected())
        assertLauncher(LauncherIcon.Pink)
    }

    private fun assertLauncher(icon: LauncherIcon) {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName)
        val activity = packageManager.queryIntentActivities(launchIntent, 0).single().activityInfo
        assertEquals(ComponentName.createRelative(app, icon.alias).className, activity.name)
        assertEquals(MainActivity::class.java.name, activity.targetActivity)
        val expectedDrawables = if (icon == LauncherIcon.Classic) setOf(icon.drawable, R.mipmap.ic_launcher_round) else setOf(icon.drawable)
        assertTrue(activity.icon in expectedDrawables)
        val reminder = Intent(app, MainActivity::class.java)
        assertEquals(MainActivity::class.java.name, packageManager.resolveActivity(reminder, 0)?.activityInfo?.name)
    }
}
