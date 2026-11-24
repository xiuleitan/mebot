package com.txl.mebot.data

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.MebotApplication
import com.txl.mebot.R
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.models.*
import com.txl.mebot.ui.settings.ModelSettingsViewModel
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MebotApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ModelSettingsViewModelTest {
    private lateinit var application: MebotApplication
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var database: MebotDatabase
    private lateinit var repository: ModelSettingsRepository
    private lateinit var model: ModelSettingsViewModel
    private val calls = AtomicInteger()
    private var request: suspend (String) -> ModelHttpResponse = { ModelHttpResponse(200, LANGUAGE_OK) }
    private var usedApplicationDatabase = false

    @Before fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(application, MebotDatabase::class.java).build()
        repository = ModelSettingsRepository(database, AesGcmCredentialCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") })
        createActivityAndModel()
    }

    private fun createActivityAndModel() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val checker = ModelConnectionChecker(ModelHttpTransport { _, _, body, _ -> calls.incrementAndGet(); request(body) })
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ModelSettingsViewModel(application, repository, checker) as T
        }
        model = ViewModelProvider(activity.get(), factory)[ModelSettingsViewModel::class.java]
        model.loadIfNeeded()
        await { model.state.value.isLoaded }
    }

    @After fun tearDown() {
        activity.pause().stop().destroy()
        database.close()
        if (usedApplicationDatabase) application.container.database.close()
        application.deleteDatabase(MebotDatabase.FILE_NAME)
    }

    @Test fun savingClearsPlaintextInputAndNewScreenLoadsOnlyCredentialPresence() {
        model.changeApiKey(TEST_KEY)
        assertFalse(model.state.value.toString().contains(TEST_KEY))
        model.save()
        await { !model.state.value.saving && model.state.value.hasApiKey }
        assertEquals("", model.state.value.newApiKey)
        activity.pause().stop().destroy()
        createActivityAndModel()
        assertTrue(model.state.value.hasApiKey)
        assertEquals("", model.state.value.newApiKey)
        assertFalse(model.state.value.dirty)
        assertEquals(0, calls.get())
    }

    @Test fun missingKeyAndUnsavedEditsCannotStartNetworkRequest() {
        model.check(ModelRole.LANGUAGE)
        assertEquals(R.string.models_missing_key, model.state.value.message)
        model.changeApiKey(TEST_KEY)
        model.save()
        await { !model.state.value.saving && model.state.value.hasApiKey }
        model.changeSettings(model.state.value.settings.copy(languageModel = "another-model"))
        model.check(ModelRole.LANGUAGE)
        assertEquals(0, calls.get())
        model.save()
        await { !model.state.value.saving && !model.state.value.dirty }
        model.check(ModelRole.LANGUAGE)
        await { model.state.value.checking == null && model.state.value.languageResult != null }
        assertEquals(ConnectionResultKind.SUCCESS, model.state.value.languageResult?.kind)
        assertEquals(1, calls.get())
    }

    @Test fun validationFailureRetainsInputAndDoesNotReplacePreviouslySavedKey() = runBlocking {
        model.changeApiKey(TEST_KEY)
        model.save()
        await { !model.state.value.saving && model.state.value.hasApiKey }
        val previous = repository.load()
        model.changeApiKey("sk-local-new-test-value")
        model.changeSettings(model.state.value.settings.copy(languageEndpoint = "http://invalid.example.com/v1"))
        model.save()
        await { !model.state.value.saving }
        assertEquals(R.string.models_invalid_language_endpoint, model.state.value.message)
        assertEquals("sk-local-new-test-value", model.state.value.newApiKey)
        assertEquals(TEST_KEY, repository.access(previous.revision).apiKey)
        assertEquals(0, calls.get())
    }

    @Test fun removalWorksEvenWhenUnsavedEndpointIsInvalidAndKeepsOtherEdits() = runBlocking {
        model.changeApiKey(TEST_KEY)
        model.save()
        await { !model.state.value.saving && model.state.value.hasApiKey }
        model.changeSettings(model.state.value.settings.copy(imageEndpoint = "unfinished URL"))
        model.changeApiKey("local-model-fixture-ModelSettingsViewModelTest-1")
        model.removeApiKey()
        await { !model.state.value.saving }
        assertFalse(model.state.value.hasApiKey)
        assertEquals("", model.state.value.newApiKey)
        assertEquals("unfinished URL", model.state.value.settings.imageEndpoint)
        assertTrue(model.state.value.dirty)
        assertFalse(repository.load().hasApiKey)
        assertEquals(ModelSettings().imageEndpoint, repository.load().settings.imageEndpoint)
    }

    @Test fun stopWaitingIgnoresLateResponseAndDoesNotAutomaticallyRetry() {
        model.changeApiKey(TEST_KEY)
        model.save()
        await { !model.state.value.saving && model.state.value.hasApiKey }
        val started = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val release = CompletableDeferred<Unit>()
        request = {
            started.set(true)
            withContext(NonCancellable) { release.await() }
            finished.set(true)
            ModelHttpResponse(200, LANGUAGE_OK)
        }
        model.check(ModelRole.LANGUAGE)
        await { started.get() }
        val original = model.state.value.settings
        model.changeSettings(original.copy(languageModel = "ignored-during-check"))
        assertEquals(original, model.state.value.settings)
        model.stopWaiting()
        assertEquals(ConnectionResultKind.CANCELLED, model.state.value.languageResult?.kind)
        release.complete(Unit)
        await { finished.get() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(ConnectionResultKind.CANCELLED, model.state.value.languageResult?.kind)
        assertNull(model.state.value.checking)
        assertEquals(1, calls.get())
    }

    @Test fun defaultAndroidViewModelFactoryCanCreateTheSettingsScreenModel() {
        usedApplicationDatabase = true
        val defaultModel = ViewModelProvider(activity.get())["default-model-settings", ModelSettingsViewModel::class.java]
        defaultModel.loadIfNeeded()
        await { defaultModel.state.value.isLoaded }
        assertFalse(defaultModel.state.value.hasApiKey)
        assertEquals("", defaultModel.state.value.newApiKey)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "等待模型设置状态超时：${model.state.value}" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
    }

    private companion object {
        const val TEST_KEY = "local-model-fixture-ModelSettingsViewModelTest-2"
        const val LANGUAGE_OK = "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}"
    }
}
