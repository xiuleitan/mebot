package com.txl.mebot.ui.common

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiFrameClock
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.AppContainer
import com.txl.mebot.data.archive.*
import com.txl.mebot.data.local.*
import com.txl.mebot.data.subscriptions.*
import com.txl.mebot.ui.archive.*
import com.txl.mebot.ui.dayima.*
import com.txl.mebot.ui.schedule.*
import com.txl.mebot.ui.subscriptions.*
import com.txl.mebot.ui.theme.AppTheme
import com.txl.mebot.ui.theme.MebotTheme
import java.io.File
import java.time.Duration
import kotlinx.coroutines.android.asCoroutineDispatcher
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer

/** 直接对比四个实际聊天展示组件的原生文字布局，不使用替代气泡。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class BotChatLayoutTest {
    private enum class Bot { SCHEDULE, DAYIMA, SUBSCRIPTION, ARCHIVE }
    @Before fun pause() { ShadowChoreographer.setPaused(true); ShadowChoreographer.setFrameDelay(Duration.ofMillis(16)) }
    @After fun resume() { ShadowChoreographer.setPaused(false) }

    @Test fun everyBotsMessageTypographyWrappingAndAlignmentMatchesScheduleInBothThemes() {
        for (theme in listOf(AppTheme.BasicDark, AppTheme.BasicLight)) {
            var baseline: List<TextGeometry>? = null
            for (bot in Bot.entries) withChat(bot, theme) { chat ->
                val geometry = listOf(USER, ASSISTANT).map { value ->
                    val node = chat.text(value); val text = chat.layout(node)
                    assertEquals(14f, text.layoutInput.style.fontSize.value, 0.01f)
                    assertEquals(21f, text.layoutInput.style.lineHeight.value, 0.01f)
                    TextGeometry(node.boundsInWindow.left, node.boundsInWindow.top, text.size.width, text.size.height, text.lineCount)
                }
                assertTrue("User text must start to the right of assistant text", geometry.first().x > geometry.last().x)
                if (baseline == null) baseline = geometry else assertEquals("$bot differs from schedule in ${theme.id}", baseline, geometry)
                assertEquals("Normal chats contain only their messages", listOf(USER, ASSISTANT), chat.visibleTexts())
                chat.snapshot("chat-${bot.name.lowercase()}-${theme.id}")
            }
        }
    }

    @Test fun subscriptionConfirmationAndSavedRecordEntryRemainAvailableWithoutExtraInstructions() = withChat(Bot.SUBSCRIPTION) { chat ->
        val values = SubscriptionValues("云盘", 600, "CNY", SubscriptionCycleUnit.NONE, 0)
        val operation = SubscriptionOperationEntity("proposal", "session", null, null, SubscriptionOperationKind.CREATE,
            SubscriptionOperationState.PENDING, "{}", null, createdAt = 1, updatedAt = 1)
        chat.subscription.value = chat.subscription.value.copy(proposal = SubscriptionChatProposal(operation, values))
        chat.frames(500)
        assertFalse(chat.text("确认创建").config.contains(SemanticsProperties.Disabled))
        assertFalse(chat.visibleTexts().any { "交给已配置模型" in it || "不确认实际扣费" in it || "会话记录" == it })
        chat.subscription.value = chat.subscription.value.copy(input = "尚未发送的新内容")
        chat.frames(300)
        assertTrue(chat.text("确认创建").config.contains(SemanticsProperties.Disabled))
        chat.subscription.value = chat.subscription.value.copy(input = "", proposal = null, record = values.entity("record", 1, 1, 1))
        chat.frames(500)
        chat.click(chat.nodes().first { it.config.getOrNull(SemanticsActions.OnClick)?.label == "查看云盘详情" })
        assertEquals(1, chat.recordRequests)
    }

    @Test fun archiveReviewAndFailureRetryRemainAvailable() = withChat(Bot.ARCHIVE) { chat ->
        val candidate = ArchiveContent("我的保单", fields = listOf(ArchiveField("保单号", "123456")), body = "保障内容")
        val context = chat.archive.value.context!!
        chat.archive.value = chat.archive.value.copy(context = context.copy(draft = context.draft.copy(content = ArchiveDraftContent(
            candidate = candidate, analysis = ArchiveAnalysisMeta(status = ArchiveAnalysisStatus.READY)))))
        chat.frames(500)
        assertNotNull(chat.text("核对整理草稿")); assertNotNull(chat.text("保单号：123456"))
        assertFalse(chat.visibleTexts().any { "待核对草稿" == it || "发送时会将" in it || "当前档案：" in it })
        chat.archive.value = chat.archive.value.copy(context = context.copy(draft = context.draft.copy(content = ArchiveDraftContent(
            analysis = ArchiveAnalysisMeta(status = ArchiveAnalysisStatus.INTERRUPTED)))))
        chat.frames(500)
        assertNotNull(chat.text("重新整理"))
    }

    private data class TextGeometry(val x: Float, val y: Float, val width: Int, val height: Int, val lines: Int)
    private fun withChat(bot: Bot, theme: AppTheme = AppTheme.BasicDark, block: (Chat) -> Unit) {
        WindowRecomposerPolicy.withFactory({ view -> view.createLifecycleAwareWindowRecomposer(
            Handler(Looper.getMainLooper()).asCoroutineDispatcher() + AndroidUiFrameClock(Choreographer.getInstance())) }) {
            val chat = Chat(bot, theme)
            try { block(chat) } finally { chat.destroy() }
        }
    }

    private class Chat(bot: Bot, theme: AppTheme) {
        private val app = ApplicationProvider.getApplicationContext<Application>()
        private val container: AppContainer
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        private val decor get() = controller.get().window.decorView
        val subscription = mutableStateOf(SubscriptionChatState(sessionId = "session", loading = false, messages = listOf(
            SubscriptionMessageEntity("user", "session", 1, MessageRole.USER, USER, 1),
            SubscriptionMessageEntity("reply", "session", 2, MessageRole.ASSISTANT, ASSISTANT, 2))))
        val archive = mutableStateOf(ArchivePageState(loading = false, context = ArchiveContext("session", null,
            ArchiveDraft("session", ArchiveDraftContent(), 0, null), listOf(
                ArchiveChatMessage("user", MessageRole.USER, USER, emptyList(), 1),
                ArchiveChatMessage("reply", MessageRole.ASSISTANT, ASSISTANT, emptyList(), 2)))))
        var recordRequests = 0
        init {
            app.deleteDatabase(MebotDatabase.FILE_NAME); container = AppContainer(app)
            val archiveModel = ViewModelProvider(controller.get(), object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ArchiveViewModel(app, SavedStateHandle(), container.archives, container.archiveAttachments) as T
            })[ArchiveViewModel::class.java]
            controller.get().setContent { MebotTheme(theme) {
                BotComposerWorkspace(input = "", editable = true, sending = false, canSend = false, clearEnabled = true,
                    expanded = true, hasPendingMedia = false, hasConversation = true, working = false,
                    onInputChanged = {}, onSaveInput = {}, onSend = {}, onClear = {}, onExpand = {}, onDismiss = {}, inputLabel = "输入",
                    modifier = Modifier.fillMaxSize(), showClearAction = false, mediaPanel = {}, conversation = {
                        when (bot) {
                            Bot.SCHEDULE -> ScheduleComposer(ScheduleConversationState(sessionId = "session", isLoaded = true,
                                messages = listOf(ScheduleMessageEntity("user", "session", 1, MessageRole.USER, USER, 1),
                                    ScheduleMessageEntity("reply", "session", 2, MessageRole.ASSISTANT, ASSISTANT, 2))), {}, {}, {},
                                Modifier.weight(1f), {}, null)
                            Bot.DAYIMA -> DayimaChatContent(DayimaChatState(sessionId = "session", loaded = true, messages = listOf(
                                DayimaChatMessageEntity("user", "session", 1, DayimaMessageRole.USER, USER, 1),
                                DayimaChatMessageEntity("dayima:consult:reply", "session", 2, DayimaMessageRole.ASSISTANT, ASSISTANT, 2))), null, Modifier.weight(1f))
                            Bot.SUBSCRIPTION -> SubscriptionChatContent(subscription.value, null, {}, { recordRequests++ }, Modifier.weight(1f))
                            Bot.ARCHIVE -> ArchiveChatContent(archive.value, archiveModel, {}, Modifier.weight(1f))
                        }
                    }, content = {})
            } }
            frames(700)
        }
        fun frames(ms: Long) { repeat(((ms + 15) / 16).toInt()) {
            Snapshot.sendApplyNotifications(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)); measure(decor)
            Thread.sleep(2)
        } }
        fun nodes() = nodes(decor)
        fun text(value: String) = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
        fun visibleTexts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
        fun layout(node: SemanticsNode): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(layouts))
            return layouts.single()
        }
        fun click(node: SemanticsNode) { assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()) }
        fun snapshot(name: String) {
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888); decor.draw(Canvas(bitmap))
            val file = File("build/reports/chat-preview/$name.png").apply { parentFile!!.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        fun destroy() {
            controller.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            container.stopMaintenance(); container.database.close(); app.deleteDatabase(MebotDatabase.FILE_NAME)
        }
    }
    companion object {
        private const val USER = "今天晚上把这个洞洞鞋里边给洗一下"
        private const val ASSISTANT = "已创建「清洗洞洞鞋」。\n2026年10月30日"
        private fun views(view: View): Sequence<View> = sequence { yield(view); if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i))) }
        private fun measure(view: View) { views(view).filter { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }
            .forEach { it.javaClass.getMethod("measureAndLayoutForTest").invoke(it) } }
        private fun nodes(view: View): List<SemanticsNode> = views(view).filter { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }.flatMap {
            it.javaClass.getMethod("measureAndLayoutForTest").invoke(it)
            descendants((it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner).rootSemanticsNode)
        }.toList()
        private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence { yield(node); for (child in node.children) yieldAll(descendants(child)) }
    }
}
