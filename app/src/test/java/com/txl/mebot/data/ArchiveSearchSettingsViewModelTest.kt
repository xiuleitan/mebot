package com.txl.mebot.data

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.txl.mebot.data.archive.*
import com.txl.mebot.ui.archive.ArchiveSearchSettingsViewModel
import com.txl.mebot.ui.archive.ArchiveViewModel
import com.txl.mebot.data.models.*
import com.txl.mebot.ui.settings.ModelSettingsViewModel
import java.time.Duration
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ArchiveSearchSettingsViewModelTest {
    private lateinit var fixture: ArchiveAnalysisFixture
    private lateinit var engine: ArchiveSemanticEngine
    private lateinit var model: ArchiveSearchSettingsViewModel
    private lateinit var activity: ActivityController<ComponentActivity>
    private var requests = 0
    private var scheduled = 0

    @Before fun setup() {
        fixture = ArchiveAnalysisFixture()
        engine = fixture.semantic(ArchiveEmbeddingClient { _, _, _, _ -> requests++; floatArrayOf(1f, 0f, 0f) }) { scheduled++ }
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ArchiveSearchSettingsViewModel(fixture.app, engine) as T
        }
        model = ViewModelProvider(activity.get(), factory)[ArchiveSearchSettingsViewModel::class.java]
        model.start()
        await { !model.state.value.loading }
    }

    @After fun teardown() {
        activity.pause().stop().destroy()
        repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5) }
        fixture.close()
    }

    @Test fun viewingAndClosingSettingsDoesNotCreateArchivesConversationsConfigurationOrOnlineRequests() = runBlocking {
        model.open(); idle(); assertTrue(model.state.value.open)
        assertFalse(model.state.value.settings!!.values.enabled)
        model.close(); model.stop(); model.start(); idle()
        assertFalse(model.state.value.open)
        assertTrue(fixture.records.sessions().isEmpty()); assertTrue(fixture.records.records().isEmpty())
        assertEquals(0L, engine.settings.load().revision)
        assertNull(engine.index.configuration()); assertEquals(0, scheduled); assertEquals(0, requests)
    }

    @Test fun enablingFromAppSettingsQueuesExistingArchiveAndRetainsUnsentChatInputAndAttachment() = runBlocking {
        fixture.records.createSession("saved-archive")
        val draft = fixture.records.saveDraft("saved-archive", 0, ArchiveDraftContent(candidate = ArchiveContent("银行卡")))
        val receipt = fixture.records.confirm("saved-archive", "save", draft.revision)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ArchiveViewModel(fixture.app, SavedStateHandle(), fixture.records, fixture.files, null, engine) as T
        }
        val page = ViewModelProvider(activity.get(), factory)[ArchiveViewModel::class.java]
        await { !page.state.value.loading && !page.state.value.busy }
        val session = page.state.value.context!!.sessionId
        val asset = fixture.image(session, "unsent-image")
        page.changeInput("首页尚未发送的另一份档案")
        await { !page.state.value.dirty && !page.state.value.busy }
        val before = fixture.records.context(session)
        model.open(); idle()
        model.save(model.state.value.settings!!.values.copy(enabled = true, dimensions = 3), ArchiveEmbeddingKeyChange.Replace("settings-test-key")); idle()
        assertTrue(model.state.value.saved); assertTrue(engine.settings.load().values.enabled)
        assertTrue(scheduled > 0); assertEquals(0, requests)
        assertEquals(before, fixture.records.context(session))
        assertEquals(session, page.state.value.context!!.sessionId)
        assertEquals("首页尚未发送的另一份档案", page.state.value.input)
        assertTrue(fixture.records.context(session).assets.any { it.id == asset && it.pending })
        engine.runQueue()
        await { model.state.value.summary.ready == 1 }
        assertEquals(receipt.recordId, engine.index.vectors().single().recordId)
        assertEquals(1, requests)
    }

    @Test fun concurrentSettingsChangeRequiresExplicitReloadAndNeverOverwritesNewerConfiguration() = runBlocking {
        model.open(); idle()
        val original = model.state.value.settings!!
        val newer = original.values.copy(enabled = true, dimensions = 3, model = "newer-model")
        engine.saveSettings(newer, ArchiveEmbeddingKeyChange.Replace("settings-test-key"), original.revision)
        model.save(original.values.copy(enabled = true), ArchiveEmbeddingKeyChange.UseShared); idle()
        assertEquals(ArchiveSemanticFailure.CHANGED, model.state.value.failure)
        assertTrue(model.state.value.open); assertFalse(model.state.value.saved)
        assertEquals(newer, engine.settings.load().values)
        assertEquals(original.revision, model.state.value.settings!!.revision)
        val formGeneration = model.state.value.formGeneration
        model.reload(); idle()
        assertNull(model.state.value.failure); assertEquals(newer, model.state.value.settings!!.values)
        assertTrue(model.state.value.formGeneration > formGeneration)
        assertTrue(fixture.records.sessions().isEmpty()); assertEquals(0, requests)
    }

    @Test fun existingConfigurationAndIndependentKeySurviveOpeningReopeningAndNoOpSaveWithoutRebuildingVectors() = runBlocking {
        val enabled = ArchiveEmbeddingSettings(enabled = true, dimensions = 3, model = "configured-model")
        engine.saveSettings(enabled, ArchiveEmbeddingKeyChange.Replace("settings-test-key"), 0)
        fixture.records.createSession("archive")
        val draft = fixture.records.saveDraft("archive", 0, ArchiveDraftContent(candidate = ArchiveContent("身份证")))
        fixture.records.confirm("archive", "save", draft.revision)
        engine.runQueue(); assertEquals(1, requests)
        model.open(); idle()
        assertEquals(enabled, model.state.value.settings!!.values); assertTrue(model.state.value.settings!!.hasOwnKey)
        model.save(enabled, ArchiveEmbeddingKeyChange.Keep); idle()
        assertTrue(model.state.value.saved)
        assertEquals(1, engine.index.vectors().size); assertTrue(engine.index.pending().isEmpty())
        model.close(); model.stop(); model.start(); model.open(); idle()
        assertEquals(enabled, model.state.value.settings!!.values); assertTrue(model.state.value.settings!!.hasOwnKey)
        assertEquals("settings-test-key", engine.settings.access().apiKey)
        assertEquals(1, requests); assertEquals(1, fixture.records.records().size)
    }

    @Test fun searchSettingsUseOnlyTheSavedSharedKeyAndLeaveUnsavedLanguageModelAndKeyEditsUntouched() = runBlocking {
        val shared = ModelSettingsRepository(fixture.db, AesGcmCredentialCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") })
        val original = shared.save(ModelSettings(), ApiKeyChange.Replace("stored-global-key"), 0)
        val checker = ModelConnectionChecker(ModelHttpTransport { _, _, _, _ -> error("Settings editing must not check a model") })
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ModelSettingsViewModel(fixture.app, shared, checker) as T
        }
        val global = ViewModelProvider(activity.get(), factory)[ModelSettingsViewModel::class.java]
        global.loadIfNeeded(); await { global.state.value.isLoaded }
        global.changeApiKey("unsaved-global-key")
        global.changeSettings(global.state.value.settings.copy(languageModel = "unsaved-language-model"))
        model.open(); idle()
        model.save(model.state.value.settings!!.values.copy(enabled = true, dimensions = 3), ArchiveEmbeddingKeyChange.UseShared); idle()
        assertTrue(model.state.value.saved); assertFalse(model.state.value.settings!!.hasOwnKey)
        assertEquals("stored-global-key", engine.settings.access().apiKey)
        assertEquals(original.settings, shared.load().settings)
        assertEquals(original.revision, shared.load().revision)
        assertEquals("unsaved-global-key", global.state.value.newApiKey)
        assertEquals("unsaved-language-model", global.state.value.settings.languageModel)
        assertTrue(global.state.value.dirty); assertEquals(0, requests)
        assertTrue(fixture.records.sessions().isEmpty())
    }

    private fun idle() = await { !model.state.value.loading && !model.state.value.busy }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "搜索设置等待超时：${model.state.value}" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
