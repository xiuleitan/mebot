package com.txl.mebot.data

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.preferences.HapticPreferences
import com.txl.mebot.data.preferences.HapticPreferenceStore
import com.txl.mebot.ui.settings.HapticSettingsViewModel
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HapticSettingsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val models = ViewModelStore()
    @Before fun setup() { app.getSharedPreferences("interaction", 0).edit().clear().commit() }
    @After fun cleanup() { models.clear(); app.getSharedPreferences("interaction", 0).edit().clear().commit() }
    private fun model(store: HapticPreferenceStore = HapticPreferences(app)) = HapticSettingsViewModel(app, store).also { models.put("model", it) }
    @Test fun defaultIsEnabledAndDisabledPreferenceSurvivesRecreatingTheStoreAndViewModel() = runBlocking {
        assertTrue(HapticPreferences(app).read())
        val first = model(); await { first.state.value.loaded }
        first.select(false); await { !first.state.value.saving }
        assertFalse(first.state.value.enabled)
        assertFalse(HapticPreferences(app).read())
        val second = model(); await { second.state.value.loaded }
        assertFalse(second.state.value.enabled)
        second.select(true); await { !second.state.value.saving }
        assertTrue(HapticPreferences(app).read())
    }
    @Test fun failedSaveKeepsThePreviousSettingAndRetryPublishesOnlyAfterCommit() {
        var result = CompletableDeferred<Boolean>()
        var writes = 0
        val vm = model(object : HapticPreferenceStore {
            override suspend fun read() = true
            override suspend fun save(enabled: Boolean): Boolean { writes++; return result.await() }
        })
        await { vm.state.value.loaded }
        vm.select(false); assertTrue(vm.state.value.saving); assertTrue(vm.state.value.enabled)
        vm.select(false); assertEquals(1, writes)
        result.complete(false); await { !vm.state.value.saving }
        assertTrue(vm.state.value.enabled); assertNotNull(vm.state.value.error)
        result = CompletableDeferred(); vm.select(false)
        assertTrue(vm.state.value.enabled)
        result.complete(true); await { !vm.state.value.saving }
        assertFalse(vm.state.value.enabled); assertNull(vm.state.value.error); assertEquals(2, writes)
    }
    @Test fun readFailureStaysDisabledAtTheProviderUntilTheUserRetries() {
        var failed = true
        val vm = model(object : HapticPreferenceStore {
            override suspend fun read(): Boolean { if (failed) error("fixture"); return false }
            override suspend fun save(enabled: Boolean) = error("Must not save before reading")
        })
        await { !vm.state.value.saving }
        assertFalse(vm.state.value.loaded); assertNotNull(vm.state.value.error)
        vm.select(true); assertFalse(vm.state.value.loaded)
        failed = false; vm.load(); await { vm.state.value.loaded }
        assertFalse(vm.state.value.enabled); assertNull(vm.state.value.error)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "等待触觉设置超时" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)); Thread.sleep(3)
        }
    }
}
