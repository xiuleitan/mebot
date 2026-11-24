package com.txl.mebot.ui.common

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import com.txl.mebot.data.home.HomeBot
import com.txl.mebot.data.home.HomeBotMention
import com.txl.mebot.ui.home.HomeBotTextField
import com.txl.mebot.ui.home.HomeComposerState
import com.txl.mebot.R
import com.txl.mebot.data.local.FlashnoteStatus
import com.txl.mebot.ui.flashnote.FlashnotePageState
import com.txl.mebot.ui.flashnote.FlashnoteScreen
import com.txl.mebot.ui.theme.AppTheme
import com.txl.mebot.ui.theme.MebotTheme
import java.time.Duration
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BotComposerInputTest {
    private lateinit var controller: ActivityController<ComponentActivity>
    private val decor get() = controller.get().window.decorView

    @Before fun setup() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    }

    @After fun teardown() {
        controller.pause().stop().destroy()
        frames(32)
        ShadowChoreographer.setPaused(false)
    }

    @Test fun buttonAndKeyboardShareSendingRulesForEmptyAndBusyInput() {
        val input = mutableStateOf("")
        val canSend = mutableStateOf(false)
        val sending = mutableStateOf(false)
        var sends = 0
        show {
            BotComposerInput(input.value, editable = true, sending.value, canSend.value,
                mediaExpanded = false, onAdd = {}, onInputChanged = { input.value = it },
                onInputFocus = {}, onSend = { sends++ }, inputLabel = "测试输入", hasPendingMedia = false)
        }
        val label = controller.get().getString(R.string.send_message)
        val field = described("测试输入")
        assertEquals(ImeAction.Send, field.config.getOrNull(SemanticsProperties.ImeAction))
        requireNotNull(field.config.getOrNull(SemanticsActions.OnImeAction)?.action).invoke()
        tap(label)
        assertEquals(0, sends)

        input.value = "明天取文件"; canSend.value = true; frames(64)
        requireNotNull(described("测试输入").config.getOrNull(SemanticsActions.OnImeAction)?.action).invoke()
        tap(label)
        assertEquals("Both input methods use the same send action", 2, sends)

        sending.value = true; frames(64)
        assertNotNull(described(label).config.getOrNull(SemanticsProperties.Disabled))
        described("测试输入").config.getOrNull(SemanticsActions.OnImeAction)?.action?.invoke()
        tap(label)
        assertEquals("Sending cannot submit another message", 2, sends)

        sending.value = false; canSend.value = false; frames(64)
        requireNotNull(described("测试输入").config.getOrNull(SemanticsActions.OnImeAction)?.action).invoke()
        tap(label)
        assertEquals("Blocked send applies to keyboard and button", 2, sends)
    }

    @Test fun inlineBotHasAColoredSpanAndTappingItsTextOpensTheExistingBotChoices() {
        val input = mutableStateOf("@日程 明天买菜")
        show {
            BotComposerInput(input.value, editable = true, sending = false,
                canSend = HomeComposerState(input = input.value, loaded = true).canSend,
                mediaExpanded = false, onAdd = {}, onInputChanged = { input.value = it }, onInputFocus = {},
                onSend = {}, inputLabel = "首页输入", hasPendingMedia = false,
                inputField = { modifier, focused, send ->
                    HomeBotTextField(input.value, true, false, { input.value = it },
                        { input.value = HomeBotMention.parse(input.value).withBot(it) }, focused, send, "首页输入", modifier)
                })
        }
        val field = described("首页输入")
        val text = requireNotNull(field.config.getOrNull(SemanticsProperties.EditableText))
        assertEquals("@日程 明天买菜", text.text)
        assertEquals(1, text.spanStyles.size); assertEquals(0, text.spanStyles.single().start)
        assertEquals(3, text.spanStyles.single().end)
        val layouts = mutableListOf<TextLayoutResult>()
        requireNotNull(field.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(layouts)
        val glyph = layouts.single().getBoundingBox(1)
        val density = decor.resources.displayMetrics.density
        touch(field.boundsInWindow.left + 10 * density + glyph.center.x,
            field.boundsInWindow.top + 8 * density + glyph.center.y)
        frames(240)
        val option = requireNotNull(nodes().firstOrNull { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "@档案" } == true
        }) { "Tapping the inline mention must open the Bot menu" }
        requireNotNull(option.config.getOrNull(SemanticsActions.OnClick)?.action).invoke(); frames(100)
        assertEquals("@档案 明天买菜", input.value)
        assertEquals(HomeBot.ARCHIVE, HomeBotMention.parse(input.value).bot)
        assertNotEquals(text.spanStyles.single().item.color,
            described("首页输入").config.getOrNull(SemanticsProperties.EditableText)!!.spanStyles.single().item.color)
    }

    @Test fun inlineMentionBackspaceAndInvalidNamesBlockBothKeyboardAndButtonSending() {
        val input = mutableStateOf("@日程 明天买菜")
        var sends = 0
        show {
            BotComposerInput(input.value, true, false, HomeComposerState(input = input.value, loaded = true).canSend,
                false, {}, { input.value = it }, {}, { sends++ }, "首页输入", false,
                inputField = { modifier, focused, send ->
                    HomeBotTextField(input.value, true, false, { input.value = it },
                        { input.value = HomeBotMention.parse(input.value).withBot(it) }, focused, send, "首页输入", modifier)
                })
        }
        val sendLabel = controller.get().getString(R.string.send_message)
        fun replace(text: String) {
            requireNotNull(described("首页输入").config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString(text))
            frames(80)
        }
        replace("@日程明天买菜") // 光标在标记后退格，删除了分隔空格。
        assertEquals("明天买菜", input.value)
        for (text in listOf("明天买菜", "@不存在 明天买菜", "@日程")) {
            replace(text)
            assertNotNull(described(sendLabel).config.getOrNull(SemanticsProperties.Disabled))
            described("首页输入").config.getOrNull(SemanticsActions.OnImeAction)?.action?.invoke()
            tap(sendLabel)
            assertEquals(0, sends)
        }
    }

    @Test fun automaticInlineInsertionAndChangingBotKeepTheCursorAtTheSameBodyPosition() {
        val input = mutableStateOf("明天买菜")
        show {
            BotComposerInput(input.value, true, false, false, false, {}, { input.value = it }, {}, {}, "首页输入", false,
                inputField = { modifier, focused, send ->
                    HomeBotTextField(input.value, true, false, { input.value = it }, {}, focused, send, "首页输入", modifier)
                })
        }
        requireNotNull(described("首页输入").config.getOrNull(SemanticsActions.SetSelection)?.action).invoke(2, 2, false)
        frames(80)
        input.value = "@日程 明天买菜"; frames(80)
        assertEquals(androidx.compose.ui.text.TextRange(6), described("首页输入").config.getOrNull(SemanticsProperties.TextSelectionRange))
        input.value = "@订阅管理 明天买菜"; frames(80)
        assertEquals(androidx.compose.ui.text.TextRange(8), described("首页输入").config.getOrNull(SemanticsProperties.TextSelectionRange))
    }

    @Test fun pendingMediaRemoveAndAddHaveIndependentTouchTargetsAndRespectBusyState() {
        val enabled = mutableStateOf(true)
        var removes = 0
        var adds = 0
        var previews = 0
        show {
            ComposerPendingMediaRow(listOf("photo"), key = { it }, isImage = { true }, enabled.value,
                onAdd = { adds++ }, onRemove = { removes++ }, thumbnail = { _, modifier ->
                    Surface(onClick = { previews++ }, modifier = modifier) {}
                })
        }
        val remove = controller.get().getString(R.string.schedule_remove_attachment)
        val add = controller.get().getString(R.string.schedule_add_media)
        val bounds = described(remove).boundsInWindow
        assertTrue(bounds.width >= 48 * decor.resources.displayMetrics.density - 1)
        assertTrue(bounds.height >= 48 * decor.resources.displayMetrics.density - 1)
        tap(remove); tap(add)
        assertEquals(1, removes); assertEquals(1, adds); assertEquals(0, previews)
        enabled.value = false; frames(64)
        tap(remove); tap(add)
        assertEquals("Busy attachments cannot be removed", 1, removes)
        assertEquals("Busy attachments cannot open another picker", 1, adds)
    }

    @Test fun flashnoteKeyboardUsesItsExistingLocalSaveAction() {
        var saves = 0
        show {
            FlashnoteScreen(FlashnotePageState(loading = false, draftLoaded = true, countsLoaded = true,
                input = "第一行\n第二行", pages = mapOf(FlashnoteStatus.PENDING to emptyList())),
                onOpenDrawer = {}, onReturnHome = {}, onSave = { saves++ })
        }
        val field = described(controller.get().getString(R.string.flashnote_input_label))
        assertEquals(ImeAction.Send, field.config.getOrNull(SemanticsProperties.ImeAction))
        requireNotNull(field.config.getOrNull(SemanticsActions.OnImeAction)?.action).invoke()
        assertEquals(1, saves)
    }

    private fun show(content: @Composable () -> Unit) {
        controller.get().setContent { MebotTheme(AppTheme.BasicDark) { Column { content() } } }
        frames(400)
    }

    private fun frames(milliseconds: Long) {
        repeat(((milliseconds + 15) / 16).toInt()) {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            windows().asSequence().flatMap { views(it) }.filter { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }
                .forEach { it.javaClass.getMethod("measureAndLayoutForTest").invoke(it) }
        }
    }

    private fun described(label: String): SemanticsNode = requireNotNull(nodes().firstOrNull {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    }) { "Missing $label" }

    private fun tap(label: String) {
        val bounds = described(label).boundsInWindow
        touch(bounds.center.x, bounds.center.y)
    }

    private fun touch(x: Float, y: Float) {
        val start = SystemClock.uptimeMillis()
        for ((time, action) in listOf(start to MotionEvent.ACTION_DOWN, start + 1 to MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(start, time, action, x, y, 0)
            try { decor.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    private fun windows() = (listOf(decor) + WindowInspector.getGlobalWindowViews()).distinct()

    private fun nodes(): List<SemanticsNode> = windows().asSequence().flatMap { views(it) }
        .filter { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }
        .flatMap {
            it.javaClass.getMethod("measureAndLayoutForTest").invoke(it)
            descendants((it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner).rootSemanticsNode)
        }.toList()

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
    private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        for (child in node.children) yieldAll(descendants(child))
    }
}
