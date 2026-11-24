package com.txl.mebot.data

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.flashnote.*
import com.txl.mebot.data.local.*
import com.txl.mebot.ui.flashnote.FlashnoteViewModel
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.flow.*
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
class FlashnoteTitleViewModelTest {
    private lateinit var database: MebotDatabase
    private lateinit var repository: FlashnoteRepository
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var model: FlashnoteViewModel
    private val work = FakeWork()
    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(app, MebotDatabase::class.java).build()
        repository = FlashnoteRepository(database)
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FlashnoteViewModel(app, SavedStateHandle(), repository, titleWork = work) as T
        }
        model = ViewModelProvider(activity.get(), factory)[FlashnoteViewModel::class.java]
        model.setVisible(true)
        await { model.state.value.draftLoaded && !model.state.value.loading }
    }
    @After fun teardown() {
        model.setVisible(false)
        await { !model.state.value.isSaving }
        activity.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        database.close()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "等待闪念 AI 标题状态超时" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
    }

    @Test fun originalsAreSavedBeforeQueueingAndAnAiJobDoesNotBlockTheNextNote() = runBlocking {
        val original = "  第一条原文\n\n细节和空格都保留  "
        work.beforeEnqueue = { id -> assertEquals(original, repository.note(id)!!.content) }
        model.changeInput(original); model.save()
        await { !model.state.value.isSaving && work.requests.size == 1 && model.state.value.draftLoaded }
        val first = repository.notes(FlashnoteStatus.PENDING).single()
        assertEquals(original, first.content)
        assertFalse(model.state.value.saveFailed)
        await { model.state.value.titleStates[first.id]?.phase == FlashnoteTitlePhase.WAITING }
        work.beforeEnqueue = { id -> assertEquals("第二条原文", repository.note(id)!!.content) }
        model.changeInput("第二条原文"); model.save()
        await { !model.state.value.isSaving && work.requests.size == 2 }
        assertEquals(2, repository.notes(FlashnoteStatus.PENDING).size)
        assertEquals(original, repository.note(first.id)!!.content)
    }

    @Test fun retryOnlyQueuesATitleAndNeverResavesOrDuplicatesTheOriginal() = runBlocking {
        model.changeInput("要保留的原文"); model.save()
        await { !model.state.value.isSaving && work.requests.size == 1 }
        val note = repository.notes(FlashnoteStatus.PENDING).single()
        work.states.value = mapOf(note.id to FlashnoteTitleState(FlashnoteTitlePhase.FAILED, FlashnoteTitleFailure.NETWORK))
        await { model.state.value.titleStates[note.id]?.phase == FlashnoteTitlePhase.FAILED }
        model.openDetail(note.id)
        await { !model.state.value.detailLoading }
        model.generateTitle()
        await { work.requests.size == 2 }
        assertEquals(listOf(note.id to note.revision, note.id to note.revision), work.requests)
        assertEquals(note, repository.note(note.id))
        assertEquals(1, repository.notes(FlashnoteStatus.PENDING).size)
    }

    @Test fun queueFailureDoesNotBecomeASaveFailureAndKeepsExactText() = runBlocking {
        work.fail = true
        val original = "原始记录\r\n\r\n末尾空格  "
        model.changeInput(original); model.save()
        await { !model.state.value.isSaving && model.state.value.savedEvent != null }
        val note = repository.notes(FlashnoteStatus.PENDING).single()
        await { model.state.value.titleStates[note.id]?.phase == FlashnoteTitlePhase.FAILED }
        assertFalse(model.state.value.saveFailed)
        assertEquals("", model.state.value.input)
        assertEquals(original, note.content)
        assertEquals(note.id, model.state.value.savedEvent!!.id)
    }

    @Test fun openingExistingRecordsDoesNotAutomaticallyUploadThemAndHiddenPagesCanReadCompletedTitles() = runBlocking {
        val draft = FlashnoteDraftEntity(java.util.UUID.randomUUID().toString(), title = "原有标题", content = "原文\n第二行", updatedAt = 1_000)
        database.flashnoteDao().insertDraft(draft)
        val note = repository.save(draft)
        await { model.state.value.entries.any { it.id == note.id } }
        model.openDetail(note.id); await { !model.state.value.detailLoading }
        assertTrue(work.requests.isEmpty())
        model.generateTitle(); await { work.requests.size == 1 }
        model.setVisible(false)
        repository.applyGeneratedTitle(repository.titleSnapshot(note.id, note.revision), "新短标题")
        work.states.value = emptyMap()
        model.setVisible(true)
        await { model.state.value.detail?.title == "新短标题" && model.state.value.entries.any { it.title == "新短标题" } }
        assertEquals("原文\n第二行", model.state.value.detail!!.detail)
        assertEquals(note.createdAt, model.state.value.detail!!.createdAt)
        assertEquals(1, work.requests.size)
    }

    private class FakeWork : FlashnoteTitleWork {
        val states = MutableStateFlow<Map<String, FlashnoteTitleState>>(emptyMap())
        val requests = mutableListOf<Pair<String, Long>>()
        var fail = false
        var beforeEnqueue: suspend (String) -> Unit = {}
        override suspend fun enqueue(id: String, revision: Long) {
            beforeEnqueue(id)
            if (fail) throw IOException("test queue failure")
            requests += id to revision
            states.value = states.value + (id to FlashnoteTitleState(FlashnoteTitlePhase.WAITING))
        }
        override fun observe(notes: Map<String, Long>) = states.map { state -> state.filterKeys { it in notes } }
    }
}
