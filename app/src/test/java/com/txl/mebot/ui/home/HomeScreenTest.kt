package com.txl.mebot.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.txl.mebot.ui.flashnote.FlashnoteCard
import com.txl.mebot.ui.flashnote.FlashnoteEntry
import com.txl.mebot.ui.schedule.ScheduleCard
import com.txl.mebot.ui.schedule.ScheduleEntry
import com.txl.mebot.ui.schedule.ScheduleScreen
import com.txl.mebot.ui.schedule.SchedulePageState
import com.txl.mebot.ui.flashnote.FlashnoteScreen
import com.txl.mebot.ui.flashnote.FlashnotePageState
import com.txl.mebot.ui.common.BotDetailEntry
import com.txl.mebot.ui.common.ArchiveSearchField
import androidx.compose.ui.platform.AndroidUiFrameClock
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.txl.mebot.R
import com.txl.mebot.data.local.MediaSource
import com.txl.mebot.data.local.ScheduleDisplayRecord
import com.txl.mebot.data.local.ScheduleOccurrenceEntity
import com.txl.mebot.ui.settings.AppearanceScreen
import com.txl.mebot.ui.theme.AppTheme
import com.txl.mebot.ui.theme.MebotTheme
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.android.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class HomeScreenTest {
    @Before fun pauseFrames() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }
    @After fun resetFrames() { ShadowChoreographer.setPaused(false) }

    @Test fun appearanceKeepsBothBaseThemesSelectableAndAppliesPinkTheme() {
        val selected = mutableStateOf(AppTheme.BasicLight)
        withHome(content = {
            MebotTheme(selected.value) { AppearanceScreen(onBack = {}, onThemeSelected = { selected.value = it }) }
        }) { screen ->
            val luckyCat = screen.decor.context.getString(R.string.launcher_icon_lucky_cat)
            assertTrue(screen.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == luckyCat } == true })
            fun option(theme: AppTheme): SemanticsNode {
                val title = screen.decor.context.getString(theme.title)
                return screen.nodes().single { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == title } == true
                }
            }
            assertTrue(option(AppTheme.BasicLight).config[SemanticsProperties.Selected])
            for (theme in listOf(AppTheme.SoftPink, AppTheme.BasicDark, AppTheme.BasicLight)) {
                screen.click(option(theme)); screen.frames(500)
                assertEquals(theme, selected.value)
                assertTrue(option(theme).config[SemanticsProperties.Selected])
                if (theme == AppTheme.SoftPink) screen.snapshot("appearance-soft-pink")
            }
            assertEquals(AppTheme.SoftPink, AppTheme.fromId(AppTheme.SoftPink.id))
        }
    }

    @Test fun cardsHaveUniformSizesAndEyeIsTheOnlyCompletedVisibilityControl() {
        for (theme in AppTheme.entries) withHome(theme) { home ->
            val searchTop = home.tag("home_search").boundsInWindow.top
            val inputTop = home.input().boundsInWindow.top
            val todayIds = setOf("meeting", "groceries", "desk", "dinner", "membership", "health")
            val cards = todayIds.map { home.tag("home_card_$it") }
            val todaySize = cards.first().size
            cards.forEach {
                assertEquals(cards.first().size.width, it.size.width)
                assertEquals(cards.first().size.height, it.size.height)
            }
            assertEquals("已隐藏明天和已完成事项", home.tag("home_completed_visibility").config.getOrNull(SemanticsProperties.StateDescription))
            assertFalse(home.hasTag("home_card_completed-notes"))
            assertFalse(home.hasTag("home_card_notes"))
            home.snapshot("home-${theme.id}")
            home.click(home.tag("home_completed_visibility")); home.frames(1000)
            assertTrue(home.hasTag("home_day_1"))
            assertEquals("已展开明天和已完成事项", home.tag("home_completed_visibility").config.getOrNull(SemanticsProperties.StateDescription))
            assertFalse(home.hasTag("home_today_completed"))
            home.tag("home_timeline").config[SemanticsActions.ScrollBy].action!!.invoke(0f, home.tag("home_timeline").boundsInWindow.height * .65f)
            home.frames(700)
            assertNotNull(home.tag("home_card_completed-notes"))
            val past = home.tag("home_card_notes")
            assertTrue(past.size.width < todaySize.width)
            assertTrue(past.size.height < todaySize.height)
            assertTrue(home.hasTag("home_day_divider_-1"))
            assertEquals("已完成", past.config.getOrNull(SemanticsProperties.StateDescription))
            assertEquals(searchTop, home.tag("home_search").boundsInWindow.top, 1f)
            assertEquals(inputTop, home.input().boundsInWindow.top, 1f)
            home.tag("home_timeline").config[SemanticsActions.ScrollBy].action!!.invoke(0f, -home.tag("home_timeline").boundsInWindow.height * .65f)
            home.frames(700)
            home.click(home.tag("home_completed_visibility")); home.frames(1000)
            assertNotNull(home.tag("home_card_meeting"))
            assertFalse(home.hasTag("home_card_completed-notes"))
            assertFalse(home.hasTag("home_card_notes"))
            assertFalse(home.hasTag("home_day_1"))
            assertEquals("已隐藏明天和已完成事项", home.tag("home_completed_visibility").config.getOrNull(SemanticsProperties.StateDescription))

        }
    }

    @Test fun theEyeRevealsAndHidesTomorrow() = withHome { home ->
        val grid = home.tag("home_timeline").boundsInWindow
        home.swipe(home.decor, grid.center.x, grid.top + 100f, 300f); home.frames(1000)
        assertFalse(home.hasTag("home_day_1"))
        assertFalse(home.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "下拉查看明天" || it.text == "松开查看明天" } == true })
        home.click(home.tag("home_completed_visibility")); home.frames(1000)
        assertTrue(home.hasTag("home_day_1"))
        assertNotNull(home.tag("home_card_tomorrow-groceries"))
        assertTrue(home.tag("home_day_1").boundsInWindow.top < home.tag("home_day_0").boundsInWindow.top)
        home.snapshot("home-tomorrow")
        home.click(home.tag("home_completed_visibility")); home.frames(1000)
        assertFalse(home.hasTag("home_day_1"))
        assertNotNull(home.tag("home_card_meeting"))
        assertEquals("已隐藏明天和已完成事项", home.tag("home_completed_visibility").config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test fun theButtonToTheRightOfSearchOpensTrading() {
        var tradingOpens = 0
        var drawerOpens = 0
        withHome(content = {
            HomeScreen(
                today = LocalDate.of(2026, 10, 30),
                onOpenDrawer = { drawerOpens++ },
                onOpenTrading = { tradingOpens++ },
            )
        }) { home ->
            val trading = home.description("交易系统")
            assertTrue(home.tag("home_search_container").boundsInWindow.right < trading.boundsInWindow.left)
            home.click(trading)
            assertEquals(1, tradingOpens)
            assertEquals(0, drawerOpens)
        }
    }

    @Test fun todayStaysAtTheTopWhenTheFirstTimelineArrives() {
        val today = LocalDate.of(2026, 10, 30)
        val page = mutableStateOf(HomePageState(today = today))
        withHome(content = { HomeScreen(today = today, state = page.value) }) { home ->
            assertTrue(home.hasTag("home_day_0"))
            page.value = HomePageState(today = today, timeline = expansionData(today, count = 20), loading = false)
            home.frames(800)
            assertEquals(0f, home.timelineScrollPosition(), 0f)
            assertTrue(home.hasTag("home_day_0"))
            assertTrue(home.hasTag("home_card_today-1"))
        }
    }

    @Test fun reopeningHomeResetsScrollWhileTimelineUpdatesPreserveIt() {
        val today = LocalDate.of(2026, 10, 30)
        val visible = mutableStateOf(true)
        val page = mutableStateOf(HomePageState(today = today, timeline = expansionData(today, count = 20), loading = false))
        withHome(content = {
            val pages = rememberSaveableStateHolder()
            if (visible.value) {
                pages.SaveableStateProvider("Home") { HomeScreen(today = today, state = page.value) }
            }
        }) { home ->
            home.scrollIntoHistory()
            page.value = page.value.copy(timeline = expansionData(today, count = 24))
            home.frames(800)
            assertTrue(home.timelineScrollPosition() > 0f)

            visible.value = false
            home.frames(200)
            visible.value = true
            home.frames(800)
            assertEquals(0f, home.timelineScrollPosition(), 0f)
            assertTrue(home.hasTag("home_card_today-1"))
        }
    }

    @Test fun returningFromTheBackgroundStartsAtTodayAgain() {
        val today = LocalDate.of(2026, 10, 30)
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        val page = HomePageState(today = today, timeline = expansionData(today, count = 20), loading = false)
        withHome(content = {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                HomeScreen(today = today, state = page)
            }
        }) { home ->
            home.click(home.tag("home_completed_visibility"))
            home.frames(1000)
            assertTrue(home.hasTag("home_day_1"))
            home.scrollIntoHistory()
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            home.frames(100)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            home.frames(1000)
            assertEquals(0f, home.timelineScrollPosition(), 0f)
            assertTrue(home.hasTag("home_card_today-1"))
            assertFalse(home.hasTag("home_day_1"))
        }
    }

    @Test fun previewSearchAndSendKeepInputAndDetailsCannotBeDraggedAway() = withHome { home ->
        home.tag("home_search").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("合同"))
        home.frames(80)
        home.click(home.description("搜索")); home.frames(100)
        assertEquals("合同", home.tag("home_search").config[SemanticsProperties.EditableText].text)
        home.input().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("明天下午三点开会"))
        home.frames(80)
        home.input().config[SemanticsActions.OnImeAction].action!!.invoke()
        home.frames(100)
        assertEquals("明天下午三点开会", home.input().config[SemanticsProperties.EditableText].text)
        for (exit in listOf("close", "scrim", "back")) {
            home.click(home.tag("home_card_meeting")); home.frames(800)
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            val decor = requireNotNull(dialog.window).decorView
            val detail = nodes(decor).first { it.config.getOrNull(SemanticsProperties.TestTag) == "home_preview_detail" }
            val top = detail.boundsInWindow.top
            home.swipe(decor, decor.width / 2f, top + 60f, 200f); home.frames(500)
            assertTrue("Downward dragging must keep the detail open", dialog.isShowing)
            assertEquals(top, nodes(decor).first { it.config.getOrNull(SemanticsProperties.TestTag) == "home_preview_detail" }.boundsInWindow.top, 1f)
            when (exit) {
                "back" -> (dialog as ComponentDialog).onBackPressedDispatcher.onBackPressed()
                "scrim" -> home.tap(decor, decor.width / 2f, (top - 30f).coerceAtLeast(1f))
                else -> home.click(nodes(decor).first {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("关闭面板") == true
                })
            }
            home.frames(1000)
            assertFalse("$exit must close the detail", dialog.isShowing)
        }
        assertEquals("明天下午三点开会", home.input().config[SemanticsProperties.EditableText].text)
    }

    @Test fun homeUsesTheScheduleMediaPanelAndTypingCollapsesItWithoutLosingText() = withHome { home ->
        home.click(home.description("添加图片或文件")); home.frames(500)
        for (label in listOf("相机", "相册", "文件")) {
            assertTrue(home.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true })
        }
        assertFalse(home.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.contains("媒体预览") } == true })
        val field = home.input()
        field.config[SemanticsActions.RequestFocus].action!!.invoke(); home.frames(500)
        assertFalse(home.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "相册" } == true })
        home.input().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("继续编辑首页草稿"))
        home.frames(80)
        home.click(home.description("添加图片或文件")); home.frames(500)
        assertEquals("继续编辑首页草稿", home.input().config[SemanticsProperties.EditableText].text)
        home.click(home.description("关闭面板")); home.frames(500)
        assertEquals("继续编辑首页草稿", home.input().config[SemanticsProperties.EditableText].text)
    }

    @Test fun realHomeUsesActualTitlesRoutesSearchAndShowsOneSwipeableFlashnoteSlot() {
        val today = LocalDate.of(2026, 10, 30)
        fun card(id: String, source: com.txl.mebot.data.home.HomeSource) = com.txl.mebot.data.home.HomeCard(id, source, id, today,
            "真实事项$id", "真实内容", false, 1)
        val first = card("note1", com.txl.mebot.data.home.HomeSource.FLASHNOTE).copy(title = "做一个可以整理日程图片并且随时查看记录的小工具")
        val second = card("note2", com.txl.mebot.data.home.HomeSource.FLASHNOTE).copy(builtinKey = "review")
        val schedule = card("task", com.txl.mebot.data.home.HomeSource.SCHEDULE).copy(schedule = ScheduleDisplayRecord(
            occurrence = ScheduleOccurrenceEntity("task", "task-template", "once", "真实事项task", "真实内容",
                today.plusDays(1), LocalTime.of(22, 0), null, 1, createdAt = 0, updatedAt = 0),
            sessionId = "schedule-session", coverSource = MediaSource.BUILT_IN, coverBuiltinKey = "dinner",
            coverRelativePath = null, isRecurring = true, recurrence = null, recurrenceStopped = false, attachmentCount = 2))
        val past = (1..6).map { index -> card("past$index", com.txl.mebot.data.home.HomeSource.SCHEDULE)
            .copy(day = today.minusDays(1), completed = index % 2 == 0, title = "历史事项$index",
                builtinKey = listOf("meeting", "groceries", "fitness")[index % 3]).let {
                    if (index == 6) it.copy(source = com.txl.mebot.data.home.HomeSource.FLASHNOTE, builtinKey = null,
                        title = "把各个 Bot 的输入框样式统一到日程") else it
                } }
        val page = HomePageState(loading = false, today = today, timeline = com.txl.mebot.data.home.HomeTimelineData(listOf(
            com.txl.mebot.data.home.HomeDay(today, listOf(com.txl.mebot.data.home.HomeSlot("daily-note", listOf(first, second)),
                com.txl.mebot.data.home.HomeSlot("task", listOf(schedule)))),
            com.txl.mebot.data.home.HomeDay(today.minusDays(1), past.map { com.txl.mebot.data.home.HomeSlot(it.id, listOf(it)) })) ))
        var query: String? = null
        var selected: String? = null
        val opened = mutableListOf<com.txl.mebot.data.home.HomeCard>()
        withHome(page = page, search = { text -> query = text }, select = { _, id -> selected = id },
            open = { opened += it }) { home ->
            assertFalse(home.nodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text in setOf("语义", "文字", "语义搜索档案", "本地文字查找") } == true
            })
            assertTrue(home.hasTag("home_flashnote_deck"))
            assertFalse(home.hasTag("home_card_meeting"))
            assertTrue(home.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "10月31日 22:00" } == true })
            val unmergedNodes = nodes(home.decor, unmerged = true)
            val alarmLabel = home.decor.context.getString(R.string.exact_time_schedule)
            val alarmBounds = unmergedNodes.single { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(alarmLabel) == true
            }.boundsInWindow
            val titleBounds = unmergedNodes.single { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == schedule.title } == true
            }.boundsInWindow
            assertTrue(alarmBounds.right < titleBounds.left)
            assertEquals(titleBounds.center.y, alarmBounds.center.y, 1f)
            assertNotNull(home.description(home.decor.context.getString(R.string.schedule_recurring)))
            assertNotNull(home.description(home.decor.context.getString(R.string.schedule_attachments_count, 2)))
            val deck = home.tag("home_flashnote_deck")
            assertEquals(home.tag("home_card_task").size, deck.size)
            assertEquals(deck.boundsInWindow, home.tag("home_card_note1").boundsInWindow)
            assertEquals("未完成闪念，上下滑动切换", deck.config[SemanticsProperties.ContentDescription].single())
            val row = listOf(1, 3, 5).map { home.tag("home_card_past$it") }
            assertEquals(row.first().boundsInWindow.top, row.last().boundsInWindow.top, 1f)
            assertTrue(row.zipWithNext().all { (a, b) -> a.boundsInWindow.right < b.boundsInWindow.left })
            assertTrue(row.all { it.size.width < home.tag("home_card_task").size.width })
            assertTrue(home.hasTag("home_day_divider_-1"))
            assertFalse(home.hasTag("home_card_past2"))
            home.snapshot("home-real-data")
            home.click(home.tag("home_completed_visibility")); home.frames(700)
            assertEquals("已完成", home.tag("home_card_past2").config.getOrNull(SemanticsProperties.StateDescription))
            home.snapshot("home-completed-visible")
            home.tag("home_search").config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("保险合同"))
            home.frames(80); home.click(home.description("搜索")); home.frames(100)
            assertEquals("保险合同", query)
            val bounds = home.tag("home_flashnote_deck").boundsInWindow
            val headingTop = home.tag("home_day_0").boundsInWindow.top
            home.swipe(home.decor, bounds.center.x, bounds.bottom - 20f, -bounds.height * .7f); home.frames(1000)
            assertEquals("note2", selected)
            assertEquals("Swiping the flashnote must not scroll the timeline", headingTop, home.tag("home_day_0").boundsInWindow.top, 1f)
            assertEquals(bounds, home.tag("home_card_note2").boundsInWindow)
            home.swipe(home.decor, bounds.center.x, bounds.top + 20f, bounds.height * .7f); home.frames(1000)
            assertEquals("note1", selected)
            home.horizontal(home.decor, bounds.right - 20f, bounds.center.y, -bounds.width * .7f); home.frames(600)
            assertEquals("Horizontal swipes no longer change flashnotes", "note1", selected)
            home.swipe(home.decor, bounds.center.x, bounds.bottom - 20f, -bounds.height * .7f); home.frames(1000)
            assertEquals("note2", selected)
            for (id in listOf("note2", "task", "past2")) {
                assertFalse(home.hasTag("home_complete_$id"))
                val card = home.tag("home_card_$id")
                val pinLabels = if (id == "note2") 1 else 0
                assertEquals("Only the card and pending flashnote label are clickable", 1 + pinLabels,
                    descendants(card).count { it.config.getOrNull(SemanticsActions.OnClick) != null })
                assertEquals(pinLabels, descendants(card).count { it.config.getOrNull(SemanticsProperties.ToggleableState) != null })
                home.tap(home.decor, card.boundsInWindow.center.x, card.boundsInWindow.center.y); home.frames(200)
            }
            assertEquals(listOf("note2", "task", "past2"), opened.map { it.sourceId })
            assertFalse(opened[0].completed)
            assertFalse(opened[1].completed)
            assertTrue(opened[2].completed)
            assertFalse(home.hasTag("home_today_completed"))
            assertTrue(home.hasTag("home_flashnote_deck"))
            assertFalse(home.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "‹ 闪念 ›" } == true })
            val taskBefore = home.tag("home_card_task").boundsInWindow
            home.swipe(home.decor, taskBefore.center.x, taskBefore.bottom - 20f, -taskBefore.height * .6f); home.frames(700)
            assertTrue("Dragging outside the flashnote still scrolls the timeline", home.tag("home_card_task").boundsInWindow.top < taskBefore.top)
            assertEquals("note2", selected)
            assertEquals(3, opened.size)
        }
    }

    @Test fun verticalFlashnotesRestoreSelectionFeedBackOnSettlingAndKeepOneCardSizeAsNotesChange() {
        for (theme in AppTheme.entries) {
            val date = LocalDate.of(2026, 10, 30)
            val notes = listOf("a", "b", "c").map { id -> com.txl.mebot.data.home.HomeCard(id,
                com.txl.mebot.data.home.HomeSource.FLASHNOTE, id, date, "标题$id", "这是闪念的详细内容", false, 1,
                builtinKey = if (id == "c") "review" else null) }
            val slot = mutableStateOf(com.txl.mebot.data.home.HomeSlot("notes", notes, "b"))
            var selected: String? = null
            var selections = 0
            val opened = mutableListOf<String>()
            val feedback = object : com.txl.mebot.ui.common.AppHapticFeedback {
                override fun tap() = Unit
                override fun selection() { selections++ }
                override fun success() = Unit
            }
            withHome(theme, content = {
                androidx.compose.runtime.CompositionLocalProvider(com.txl.mebot.ui.common.LocalAppHapticOverride provides feedback) {
                    Column(Modifier.width(200.dp).padding(12.dp)) {
                        HomeTaskSlot(slot.value, { opened += it.sourceId }, { selected = it }, Modifier)
                    }
                }
            }) { home ->
                val bounds = home.tag("home_flashnote_deck").boundsInWindow
                assertNull(selected)
                assertEquals(0, selections)
                assertEquals(bounds, home.tag("home_card_b").boundsInWindow)
                home.snapshot("flashnote-single-${theme.id}")
                for ((distance, expected) in listOf(-.7f to "c", .7f to "b", .7f to "a", .7f to "c")) {
                    val start = if (distance < 0) bounds.bottom - 20f else bounds.top + 20f
                    home.swipe(home.decor, bounds.center.x, start, bounds.height * distance); home.frames(1000)
                    assertEquals(expected, selected)
                    assertEquals(bounds, home.tag("home_card_$expected").boundsInWindow)
                }
                assertEquals(4, selections)
                assertTrue(opened.isEmpty())
                home.snapshot("flashnote-image-${theme.id}")
                home.swipe(home.decor, bounds.center.x, bounds.center.y, 12f, cancel = true); home.frames(700)
                assertEquals("c", selected)
                assertEquals(4, selections)
                slot.value = slot.value.copy(selectedId = "a"); home.frames(800)
                assertEquals(bounds, home.tag("home_card_a").boundsInWindow)
                assertEquals(4, selections)
                slot.value = slot.value.copy(cards = notes.take(2), selectedId = "a"); home.frames(800)
                assertEquals("a", selected)
                assertEquals(bounds, home.tag("home_card_a").boundsInWindow)
                assertEquals(4, selections)
                slot.value = slot.value.copy(cards = listOf(notes.first()), selectedId = "a"); home.frames(800)
                assertFalse(home.hasTag("home_flashnote_deck"))
                assertEquals(bounds, home.tag("home_card_a").boundsInWindow)
                home.click(home.tag("home_card_a")); home.frames(100)
                assertEquals(listOf("a"), opened)
                assertEquals(4, selections)
            }
        }
    }

    @Test fun flashnoteLabelTogglesPinWithoutOpeningDetailsAndKeepsItsSizeForSingleNotes() {
        for (theme in AppTheme.entries) for (image in listOf(false, true)) {
            val date = LocalDate.of(2026, 11, 5)
            val notes = listOf("a", "b").map { id -> com.txl.mebot.data.home.HomeCard(id,
                com.txl.mebot.data.home.HomeSource.FLASHNOTE, id, date, "日程图片整理$id", "允许日程读取图片来整理标题和内容", false, 1,
                builtinKey = if (image) "review" else null) }
            val slot = mutableStateOf(com.txl.mebot.data.home.HomeSlot("notes", notes, "a"))
            var opened = 0
            withHome(theme, content = {
                Column(Modifier.width(200.dp).padding(12.dp)) {
                    HomeTaskSlot(slot.value, { opened++ }, { id -> slot.value = slot.value.copy(selectedId = id, pinned = false) }, Modifier,
                        pin = { id, pinned -> slot.value = slot.value.copy(selectedId = id, pinned = pinned) })
                }
            }) { home ->
                val cardBounds = home.tag("home_card_a").boundsInWindow
                val pin = home.tag("home_card_type_a")
                assertTrue(pin.boundsInWindow.left > cardBounds.center.x)
                assertEquals(cardBounds.top, pin.boundsInWindow.top, 1f)
                assertFalse(home.hasTag("home_flashnote_pin_a"))
                home.snapshot("flashnote-label-${theme.id}-${if (image) "image" else "text"}-normal")
                home.tap(home.decor, pin.boundsInWindow.center.x, pin.boundsInWindow.center.y); home.frames(250)
                assertTrue(slot.value.pinned)
                assertEquals("已固定", home.tag("home_card_type_a").config[SemanticsProperties.StateDescription])
                assertEquals(0, opened)
                assertEquals(pin.boundsInWindow, home.tag("home_card_type_a").boundsInWindow)
                home.snapshot("flashnote-label-${theme.id}-${if (image) "image" else "text"}-pinned")
                home.click(home.tag("home_card_type_a")); home.frames(200)
                assertFalse(slot.value.pinned)
                home.click(home.tag("home_card_type_a")); home.frames(200)
                home.swipe(home.decor, cardBounds.center.x, cardBounds.bottom - 20f, -cardBounds.height * .7f); home.frames(1000)
                assertEquals("b", slot.value.selectedId)
                assertFalse(slot.value.pinned)
                assertEquals(0, opened)
                assertEquals(cardBounds, home.tag("home_card_b").boundsInWindow)
                slot.value = slot.value.copy(cards = listOf(notes.last())); home.frames(300)
                assertTrue(home.hasTag("home_card_type_b"))
                home.click(home.tag("home_card_type_b")); home.frames(200)
                assertTrue(slot.value.pinned)
                home.click(home.tag("home_card_b")); home.frames(100)
                assertEquals(1, opened)
                slot.value = slot.value.copy(cards = listOf(notes.last().copy(completed = true)), pinned = false)
                home.frames(300)
                val completed = home.unmergedTag("home_card_type_b")
                assertNull(completed.config.getOrNull(SemanticsProperties.ToggleableState))
                assertNull(completed.config.getOrNull(SemanticsActions.OnClick))
            }
        }
    }

    @Test fun standaloneBotCardsOnlyOpenDetailsInBothCompletionStates() {
        for (theme in AppTheme.entries) for (completed in listOf(false, true)) {
            val opened = mutableListOf<String>()
            withHome(theme, content = {
                Column(Modifier.width(200.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScheduleCard(ScheduleEntry("schedule", "去食堂", LocalDate.of(2026, 10, 30),
                        time = LocalTime.of(22, 0), detail = "详情", completed = completed,
                        image = R.drawable.schedule_dinner, attachmentCount = 1),
                        onClick = { opened += "schedule" }, cardModifier = Modifier.testTag("bot_schedule"))
                    FlashnoteCard(FlashnoteEntry("note", "记录一个想法", "想法的详细内容", 0,
                        completed = completed, image = R.drawable.schedule_review, attachmentCount = 1),
                        onClick = { opened += "flashnote" }, modifier = Modifier.testTag("bot_flashnote"))
                }
            }) { home ->
                for (kind in listOf("schedule", "flashnote")) {
                    val card = home.tag("bot_$kind")
                    assertEquals(1, descendants(card).count { it.config.getOrNull(SemanticsActions.OnClick) != null })
                    assertFalse(descendants(card).any { it.config.getOrNull(SemanticsProperties.ToggleableState) != null })
                    home.tapTopEnd(card); home.frames(150)
                }
                assertEquals(listOf("schedule", "flashnote"), opened)
                home.snapshot("bot-cards-${theme.id}-${if (completed) "completed" else "pending"}")
            }
        }
    }

    @Test fun homeAndBotEntryRenderTheSameDetailAndKeepTheOriginalCompletionAction() {
        for (theme in AppTheme.entries) for (bot in listOf("schedule", "flashnote")) {
            var original: List<Pair<String, androidx.compose.ui.geometry.Rect>>? = null
            for (detailsOnly in listOf(false, true)) {
                var completions = 0
                val date = LocalDate.of(2026, 10, 30)
                val schedule = ScheduleEntry("same", "相同的日程详情", date, LocalTime.of(22, 0),
                    image = R.drawable.schedule_dinner, detail = "这是完整正文", attachmentCount = 1)
                val note = FlashnoteEntry("same", "相同的闪念详情", "这是完整正文", 0, image = R.drawable.schedule_review)
                withHome(theme, content = {
                    if (bot == "schedule") ScheduleScreen(SchedulePageState(date, entries = listOf(schedule),
                        loading = false, detailId = schedule.id, detail = schedule), onDateSelected = {},
                        onToggleCompleted = { completions++ }, onOpenDrawer = {}, onReturnHome = {},
                        initialDetail = schedule, detailsOnly = detailsOnly)
                    else FlashnoteScreen(FlashnotePageState(loading = false, detailId = note.id, detail = note),
                        onOpenDrawer = {}, onReturnHome = {}, onToggleCompleted = { completions++ }, detailsOnly = detailsOnly)
                }) { home ->
                    home.frames(500)
                    val decor = requireNotNull(ShadowDialog.getLatestDialog()?.window).decorView
                    val all = nodes(decor)
                    val labels = listOf(if (bot == "schedule") schedule.title else note.title, "删除", "编辑", "完成")
                    val signature = labels.map { text ->
                        val node = all.first { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
                        text to node.boundsInWindow
                    }
                    if (detailsOnly) assertEquals("Home must reuse the original detail layout for $bot in $theme", original, signature)
                    else original = signature
                    assertFalse(all.any { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "打开日程" || it.text == "打开闪念" } == true })
                    home.snapshot("detail-$bot-${theme.id}-${if (detailsOnly) "home" else "bot"}", decor)
                    val complete = all.first { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "完成" } == true &&
                        it.config.getOrNull(SemanticsActions.OnClick) != null }
                    home.tap(decor, complete.boundsInWindow.center.x, complete.boundsInWindow.center.y); home.frames(100)
                    assertEquals(1, completions)
                }
            }
        }
    }

    @Test fun scheduleEditOpensAManualFormFromBotAndHomeAndSavesTheTypedFields() {
        for (theme in AppTheme.entries) for (homeEntry in listOf(false, true)) {
            val day = LocalDate.of(2026, 11, 5)
            val entry = ScheduleEntry("edit", "原始标题", day, LocalTime.of(19, 0), detail = "原始正文", sessionId = "session", revision = 1)
            val draft = com.txl.mebot.data.local.ScheduleEditorDraftEntity(entry.id, "request", "session", 1, 1, "", entry.title, entry.detail,
                day, entry.time, false, updatedAt = 0)
            val editor = mutableStateOf<com.txl.mebot.ui.schedule.ScheduleEditorState?>(null)
            var editClicks = 0
            var submitted: com.txl.mebot.data.local.ScheduleEditorDraftEntity? = null
            var closed = 0
            withHome(theme, content = {
                val page = editor.value
                if (page != null) com.txl.mebot.ui.schedule.ScheduleEditorSheet(page,
                    change = { changed -> editor.value = page.copy(draft = changed(requireNotNull(page.draft))) },
                    save = { submitted = editor.value?.draft; editor.value = editor.value?.copy(saved = true) },
                    remove = {}, importImages = { _, _ -> }, reload = {}, beforeDismiss = { true },
                    onDismiss = { closed++; editor.value = null })
                else ScheduleScreen(SchedulePageState(day, entries = listOf(entry), loading = false, detailId = entry.id, detail = entry),
                    onDateSelected = {}, onToggleCompleted = {}, onOpenDrawer = {}, onReturnHome = {}, initialDetail = entry,
                    detailsOnly = homeEntry, onEditEntry = { editClicks++; editor.value = com.txl.mebot.ui.schedule.ScheduleEditorState(entry.id, draft, loading = false) })
            }) { fixture ->
                var decor = requireNotNull(ShadowDialog.getLatestDialog()?.window).decorView
                val edit = nodes(decor).first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "编辑" } == true && it.config.getOrNull(SemanticsActions.OnClick) != null }
                fixture.tap(decor, edit.boundsInWindow.center.x, edit.boundsInWindow.center.y); fixture.frames(900)
                assertEquals(1, editClicks)
                decor = requireNotNull(ShadowDialog.getLatestDialog()?.window).decorView
                assertTrue(nodes(decor).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "编辑日程" } == true })
                for ((label, value) in listOf("日程标题" to "手动标题", "日程正文" to "手动正文")) {
                    val field = nodes(decor).first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
                    assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
                    fixture.frames(100)
                }
                fixture.snapshot("schedule-manual-editor-${theme.id}-${if (homeEntry) "home" else "bot"}", decor)
                val save = nodes(decor).first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "保存" } == true && it.config.getOrNull(SemanticsActions.OnClick) != null }
                fixture.tap(decor, save.boundsInWindow.center.x, save.boundsInWindow.center.y); fixture.frames(900)
                assertEquals("手动标题", submitted?.title); assertEquals("手动正文", submitted?.detail)
                assertEquals(day, submitted?.date); assertEquals(entry.time, submitted?.time); assertEquals(1, closed)
            }
        }
    }

    @Test fun manualScheduleEditorAllowsScrollingAndClosesOnlyThroughExplicitDismissal() {
        for (method in listOf("close", "back", "outside")) {
            val day = LocalDate.of(2026, 11, 5)
            val draft = com.txl.mebot.data.local.ScheduleEditorDraftEntity("edit", "request", "session", 1, 1, "", "标题", "正文\n".repeat(20), day, null, false, updatedAt = 0)
            var dismissed = 0
            val shown = mutableStateOf(true)
            withHome(AppTheme.SoftPink, content = {
                if (shown.value) com.txl.mebot.ui.schedule.ScheduleEditorSheet(com.txl.mebot.ui.schedule.ScheduleEditorState("edit", draft, loading = false),
                    change = {}, save = {}, remove = {}, importImages = { _, _ -> }, reload = {}, beforeDismiss = { true },
                    onDismiss = { dismissed++; shown.value = false })
            }) { fixture ->
                fixture.frames(500)
                val decor = requireNotNull(ShadowDialog.getLatestDialog()?.window).decorView
                val field = nodes(decor).first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("日程正文") == true }
                fixture.swipe(decor, field.boundsInWindow.center.x, field.boundsInWindow.top + 20f, 120f); fixture.frames(600)
                assertEquals(0, dismissed)
                when (method) {
                    "back" -> (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
                    "outside" -> fixture.tap(decor, decor.width / 2f, 20f)
                    else -> {
                        val close = nodes(decor).first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("关闭") == true }
                        fixture.tap(decor, close.boundsInWindow.center.x, close.boundsInWindow.center.y)
                    }
                }
                fixture.frames(800); assertEquals(1, dismissed)
            }
        }
    }

    @Test fun detailEntryWaitsForTheOriginalFlowToFinishAndClosesOnlyOnce() {
        val showing = mutableStateOf(false)
        val working = mutableStateOf(true)
        var opens = 0
        var closes = 0
        withHome(content = {
            Text("详情流程")
            BotDetailEntry("record", showing.value, working.value, open = { opens++ }, onClosed = { closes++ })
        }) { home ->
            assertEquals(0, opens)
            working.value = false; home.frames(80)
            assertEquals(1, opens)
            showing.value = true; home.frames(80)
            working.value = true; showing.value = false; home.frames(80)
            assertEquals(0, closes)
            showing.value = true; working.value = false; home.frames(80)
            assertEquals(0, closes)
            showing.value = false; home.frames(80)
            assertEquals(1, closes)
            working.value = true; home.frames(80)
            working.value = false; home.frames(80)
            assertEquals(1, closes)
            assertEquals(1, opens)
        }
    }

    @Test fun flashnoteBodyUsesOnlyTheSpaceLeftAfterItsFullTitle() {
        val date = LocalDate.of(2026, 10, 30)
        fun note(id: String, title: String, body: String) = com.txl.mebot.data.home.HomeCard(id,
            com.txl.mebot.data.home.HomeSource.FLASHNOTE, id, date, title, body, false, 1)
        val cards = listOf(note("body", "日程图片整理", "允许日程读取图片并进行整理，然后归档便于查找。"),
            note("only-title", "只有标题", "只有标题"),
            note("long-title", "很长的标题".repeat(20), "正文不能挤掉还没显示完的标题"))
        withHome(content = {
            Column(Modifier.width(175.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                cards.forEach { HomeTaskCard(it, {}, Modifier) }
            }
        }) { home ->
            val body = home.unmergedTag("home_flashnote_body_body")
            val title = home.unmergedTag("home_flashnote_title_body")
            assertTrue(body.boundsInWindow.top > title.boundsInWindow.bottom)
            assertTrue(body.boundsInWindow.bottom <= home.tag("home_card_body").boundsInWindow.bottom - 12f)
            assertFalse(home.hasUnmergedTag("home_flashnote_body_only-title"))
            assertFalse(home.hasUnmergedTag("home_flashnote_body_long-title"))
            val sizes = cards.map { home.tag("home_card_${it.id}").size }
            assertTrue(sizes.all { it == sizes.first() })
            home.snapshot("flashnote-body-fit")
        }
    }

    @Test fun homeSearchHasTheSameHeightAsTheSharedArchiveField() {
        var archiveHeight = 0
        withHome(content = {
            ArchiveSearchField("", {}, {}, Modifier.width(240.dp).testTag("archive_search_reference"))
        }) { home -> archiveHeight = home.tag("archive_search_reference").size.height }
        withHome { home ->
            assertEquals(archiveHeight, home.tag("home_search_container").size.height)
            assertEquals(88, archiveHeight) // 44dp at the fixture's 2x density.
            assertFalse(home.hasTag("home_today_completed"))
        }
    }

    @Test fun theEyeControlsAllTomorrowCardsAndCompletionHistoryWithoutHidingPastPendingTasks() {
        val today = LocalDate.of(2026, 10, 30)
        fun task(id: String, day: LocalDate, done: Boolean) = com.txl.mebot.data.home.HomeCard(id,
            com.txl.mebot.data.home.HomeSource.SCHEDULE, id, day, id, "正文", done, 1,
            builtinKey = if (done) "review" else "meeting")
        val days = listOf(
            com.txl.mebot.data.home.HomeDay(today.plusDays(2), listOf(com.txl.mebot.data.home.HomeSlot("later", listOf(task("later", today.plusDays(2), false))))),
            com.txl.mebot.data.home.HomeDay(today.plusDays(1), listOf(false, true).map { done ->
                val id = if (done) "tomorrow-done" else "tomorrow-pending"
                com.txl.mebot.data.home.HomeSlot(id, listOf(task(id, today.plusDays(1), done))) }),
            com.txl.mebot.data.home.HomeDay(today, listOf(false, true).map { done ->
                val id = if (done) "today-done" else "today-pending"
                com.txl.mebot.data.home.HomeSlot(id, listOf(task(id, today, done))) }),
            com.txl.mebot.data.home.HomeDay(today.minusDays(1), listOf(false, true).map { done ->
                val id = if (done) "past-done" else "past-pending"
                com.txl.mebot.data.home.HomeSlot(id, listOf(task(id, today.minusDays(1), done))) }))
        withHome(page = HomePageState(today, timeline = com.txl.mebot.data.home.HomeTimelineData(days), loading = false)) { home ->
            assertNotNull(home.tag("home_card_past-pending"))
            for (id in listOf("tomorrow-pending", "tomorrow-done", "today-done", "past-done", "later")) assertFalse(home.hasTag("home_card_$id"))
            home.click(home.tag("home_completed_visibility")); home.frames(1000)
            for (id in listOf("tomorrow-pending", "tomorrow-done", "today-pending", "today-done", "past-pending", "past-done")) assertTrue(home.hasTag("home_card_$id"))
            assertFalse(home.hasTag("home_card_later"))
            assertEquals("已完成", home.tag("home_card_tomorrow-done").config.getOrNull(SemanticsProperties.StateDescription))
            home.snapshot("home-eye-expanded")
            home.click(home.tag("home_completed_visibility")); home.frames(1000)
            assertNotNull(home.tag("home_card_today-pending"))
            assertNotNull(home.tag("home_card_past-pending"))
            for (id in listOf("tomorrow-pending", "tomorrow-done", "today-done", "past-done", "later")) assertFalse(home.hasTag("home_card_$id"))
            home.snapshot("home-eye-collapsed")
        }
    }

    @Test fun openingTheEyeRevealsAllTomorrowCardsBeforeAddingCompletedTodayAndHistory() {
        val today = LocalDate.of(2026, 10, 30)
        val expanded = mutableStateOf(false)
        lateinit var grid: LazyGridState
        withHome(content = {
            grid = rememberLazyGridState()
            HomeTimeline(today, 0.dp, {}, modifier = Modifier.height(600.dp).background(com.txl.mebot.ui.schedule.scheduleColors().backgroundTop), data = expansionData(today),
                expanded = expanded.value, onToggleExpansion = { expanded.value = !expanded.value }, grid = grid)
        }) { home ->
            assertEquals(5, grid.layoutInfo.totalItemsCount)
            home.snapshot("home-eye-before-reveal")
            home.click(home.tag("home_completed_visibility"))
            home.frames(32)
            assertEquals("Only tomorrow has been inserted; today's and past pending cards remain", 8, grid.layoutInfo.totalItemsCount)
            assertFalse(home.hasTag("home_card_today-2"))
            assertFalse(home.hasTag("home_card_past-2"))
            home.snapshot("home-eye-inserting-tomorrow")
            var scrolledBeforeHistory = false
            var scrollingFrames = 0
            home.until {
                if (grid.isScrollInProgress) {
                    scrolledBeforeHistory = true
                    assertEquals("Completion history must wait until the reveal scroll settles", 8, grid.layoutInfo.totalItemsCount)
                    scrollingFrames++
                    if (scrollingFrames == 1) home.snapshot("home-eye-revealing-tomorrow")
                }
                grid.layoutInfo.totalItemsCount == 10
            }
            assertTrue(scrolledBeforeHistory)
            assertFalse(grid.isScrollInProgress)
            assertEquals(0, grid.firstVisibleItemIndex)
            assertEquals(0, grid.firstVisibleItemScrollOffset)
            assertTrue(home.hasTag("home_card_tomorrow-1"))
            assertEquals("已完成", home.tag("home_card_tomorrow-2").config.getOrNull(SemanticsProperties.StateDescription))
            home.frames(400)
            assertTrue(home.hasTag("home_card_today-2"))
            assertTrue(home.hasTag("home_card_past-2"))
            home.snapshot("home-eye-staged-open")
        }
    }

    @Test fun rapidEyeChangesCancelEarlierRevealsAndNeverReopenHiddenCards() {
        val today = LocalDate.of(2026, 10, 30)
        val expanded = mutableStateOf(false)
        lateinit var grid: LazyGridState
        withHome(content = {
            grid = rememberLazyGridState()
            HomeTimeline(today, 0.dp, {}, modifier = Modifier.height(600.dp).background(com.txl.mebot.ui.schedule.scheduleColors().backgroundTop), data = expansionData(today),
                expanded = expanded.value, onToggleExpansion = { expanded.value = !expanded.value }, grid = grid)
        }) { home ->
            for (wait in listOf(16L, 64L, 128L)) {
                expanded.value = true; home.frames(wait)
                expanded.value = false; home.frames(600)
                assertEquals(5, grid.layoutInfo.totalItemsCount)
                assertFalse(home.hasTag("home_day_1"))
                assertFalse(home.hasTag("home_card_today-2"))
                assertFalse(home.hasTag("home_card_past-2"))
            }
            expanded.value = true; home.frames(1000)
            assertEquals(10, grid.layoutInfo.totalItemsCount)
            expanded.value = false; home.frames(1000)
            assertEquals(5, grid.layoutInfo.totalItemsCount)
            assertEquals(0, grid.firstVisibleItemIndex)
            assertEquals("已隐藏明天和已完成事项", home.tag("home_completed_visibility").config.getOrNull(SemanticsProperties.StateDescription))
            home.frames(1500)
            assertEquals(5, grid.layoutInfo.totalItemsCount)
            assertTrue(home.hasTag("home_card_today-1"))
            assertTrue(home.hasTag("home_card_past-1"))
        }
    }

    @Test fun takingOverTheRevealScrollWithATouchStillFinishesTheLatestVisibilityChoice() {
        val today = LocalDate.of(2026, 10, 30)
        val expanded = mutableStateOf(false)
        lateinit var grid: LazyGridState
        withHome(content = {
            grid = rememberLazyGridState()
            HomeTimeline(today, 0.dp, {}, modifier = Modifier.height(600.dp).background(com.txl.mebot.ui.schedule.scheduleColors().backgroundTop), data = expansionData(today, 6),
                expanded = expanded.value, onToggleExpansion = { expanded.value = !expanded.value }, grid = grid)
        }) { home ->
            expanded.value = true
            home.until { grid.isScrollInProgress }
            val area = home.tag("home_timeline").boundsInWindow
            home.swipe(home.decor, area.center.x, area.center.y, -300f)
            home.frames(1500)
            assertTrue(expanded.value)
            assertFalse(grid.isScrollInProgress)
            assertEquals("A user drag must not leave the eye stuck in the first stage", 22, grid.layoutInfo.totalItemsCount)
            assertTrue("The reveal must not fight the user's scroll back to the top", grid.firstVisibleItemIndex > 0)
            expanded.value = false; home.frames(1000)
            assertEquals(9, grid.layoutInfo.totalItemsCount)
            assertEquals(0, grid.firstVisibleItemIndex)
        }
    }

    @Test fun disabledSystemAnimationsRevealTheFullTimelineWithoutWaitingForAFixedAnimationDelay() {
        val today = LocalDate.of(2026, 10, 30)
        val expanded = mutableStateOf(false)
        lateinit var grid: LazyGridState
        withHome(motionScale = 0f, content = {
            grid = rememberLazyGridState()
            HomeTimeline(today, 0.dp, {}, modifier = Modifier.height(600.dp).background(com.txl.mebot.ui.schedule.scheduleColors().backgroundTop), data = expansionData(today),
                expanded = expanded.value, onToggleExpansion = { expanded.value = !expanded.value }, grid = grid)
        }) { home ->
            expanded.value = true; home.frames(160)
            assertEquals(10, grid.layoutInfo.totalItemsCount)
            assertFalse(grid.isScrollInProgress)
            assertEquals(0, grid.firstVisibleItemIndex)
            assertEquals(0, grid.firstVisibleItemScrollOffset)
            expanded.value = false; home.frames(160)
            assertEquals(5, grid.layoutInfo.totalItemsCount)
            assertEquals(0, grid.firstVisibleItemIndex)
        }
    }

    @Test fun revealingThreeTomorrowCardsDoesNotStopAndRestartAtGridRowBoundaries() {
        val today = LocalDate.of(2026, 10, 30)
        val expanded = mutableStateOf(false)
        val source = expansionData(today, 6)
        val data = source.copy(days = source.days.map { day ->
            if (day.date == today.plusDays(1)) day.copy(slots = day.slots.take(3)) else day
        })
        lateinit var grid: LazyGridState
        withHome(content = {
            grid = rememberLazyGridState()
            HomeTimeline(today, 0.dp, {}, modifier = Modifier.height(600.dp).background(com.txl.mebot.ui.schedule.scheduleColors().backgroundTop),
                data = data, expanded = expanded.value, grid = grid,
                reflectionCard = { Text("写给自己的话\n".repeat(8), Modifier.height(260.dp)) })
        }) { home ->
            val positions = mutableListOf<Int>()
            expanded.value = true
            repeat(80) {
                home.frames(16)
                grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "day:0" }?.let { positions += it.offset.y }
            }
            val end = positions.last()
            val active = positions.filter { it >= end * .08f && it <= end * .94f }
            var longestPause = 0
            var pause = 0
            for ((a, b) in active.zipWithNext()) {
                pause = if (kotlin.math.abs(b - a) <= 1) pause + 1 else 0
                longestPause = maxOf(longestPause, pause)
            }
            val directory = File("build/reports/home-preview").apply { mkdirs() }
            File(directory, "eye-reveal-motion.csv").writeText("frame,today_y\n" + positions.mapIndexed { i, y -> "$i,$y" }.joinToString("\n"))
            assertTrue("The reveal must make measurable progress", active.size > 5)
            assertTrue("Reveal paused for $longestPause frames between grid rows: $positions", longestPause <= 2)
            assertTrue("The last step must not snap across an estimated row", positions.zipWithNext().all { (a, b) -> kotlin.math.abs(b - a) <= end * .25f })
            val lastMove = positions.zipWithNext().map { (a, b) -> kotlin.math.abs(b - a) }.lastOrNull { it > 0 } ?: 0
            assertTrue("The final alignment must only correct pixel rounding", lastMove <= maxOf(3f, end * .01f))
            assertEquals(0, grid.firstVisibleItemIndex)
            assertEquals(0, grid.firstVisibleItemScrollOffset)
        }
    }

    @Test fun revealUsesRealHeaderAndCardSizesForEmptyTomorrowAndLargerSystemFonts() {
        for ((count, scale) in listOf(0 to 1.6f, 1 to 1.3f, 3 to 1.6f, 6 to 1.3f)) {
            val today = LocalDate.of(2026, 10, 30)
            val expanded = mutableStateOf(false)
            val source = expansionData(today, 6)
            val tomorrow = source.days.first().copy(slots = source.days.first().slots.take(count))
            val data = source.copy(days = listOf(tomorrow) + source.days.drop(1))
            val keys = tomorrow.slots.map { it.id }.toSet() + setOf("day:0", "day:1", "empty:1")
            lateinit var grid: LazyGridState
            withHome(content = {
                val density = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit.Density(density.density, scale)) {
                    grid = rememberLazyGridState()
                    HomeTimeline(today, 0.dp, {}, modifier = Modifier.height(600.dp).background(com.txl.mebot.ui.schedule.scheduleColors().backgroundTop),
                        data = data, expanded = expanded.value, grid = grid,
                        reflectionCard = { Text("写给自己的话\n".repeat(8), Modifier.height(260.dp)) })
                }
            }) { home ->
                fun anchors() = grid.layoutInfo.visibleItemsInfo.filter { it.key in keys }.associate { it.key to it.offset.y }
                var previous = anchors()
                val positions = mutableListOf(0)
                expanded.value = true
                repeat(70) {
                    home.frames(16)
                    val current = anchors()
                    val moves = current.mapNotNull { (key, y) -> previous[key]?.let { y - it } }.sorted()
                    positions += positions.last() + if (moves.isEmpty()) 0 else moves[moves.size / 2]
                    previous = current
                }
                val end = positions.last()
                val active = positions.filter { it >= end * .08f && it <= end * .94f }
                var pause = 0
                var longestPause = 0
                for ((a, b) in active.zipWithNext()) {
                    pause = if (kotlin.math.abs(b - a) <= 1) pause + 1 else 0
                    longestPause = maxOf(longestPause, pause)
                }
                assertTrue("No reveal progress for $count cards at font scale $scale", end > 0 && active.size > 5)
                assertTrue("Mid-reveal pause for $count cards at font scale $scale: $positions", longestPause <= 2)
                assertTrue("Final correction jumped for $count cards at font scale $scale: $positions",
                    positions.zipWithNext().all { (a, b) -> kotlin.math.abs(b - a) <= end * .25f })
                val lastMove = positions.zipWithNext().map { (a, b) -> kotlin.math.abs(b - a) }.lastOrNull { it > 0 } ?: 0
                assertTrue("Large terminal alignment for $count cards at font scale $scale: $positions", lastMove <= maxOf(3f, end * .01f))
                assertEquals(0, grid.firstVisibleItemIndex)
                assertEquals(0, grid.firstVisibleItemScrollOffset)
            }
        }
    }

    private fun expansionData(today: LocalDate, count: Int = 2): com.txl.mebot.data.home.HomeTimelineData {
        return com.txl.mebot.data.home.HomeTimelineData(listOf(1L to "tomorrow", 0L to "today", -1L to "past").map { (offset, label) ->
            com.txl.mebot.data.home.HomeDay(today.plusDays(offset), (1..count).map { index ->
                val id = "$label-$index"
                com.txl.mebot.data.home.HomeSlot(id, listOf(com.txl.mebot.data.home.HomeCard(id,
                    com.txl.mebot.data.home.HomeSource.SCHEDULE, id, today.plusDays(offset), id, "", index % 2 == 0, 1,
                    builtinKey = if (index % 2 == 0) "review" else "meeting")))
            })
        })
    }

    private fun Fixture.timelineScrollPosition() =
        tag("home_timeline").config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun Fixture.scrollIntoHistory() {
        val timeline = tag("home_timeline")
        assertTrue(timeline.config[SemanticsActions.ScrollBy].action!!.invoke(0f, timeline.boundsInWindow.height * 2f))
        frames(900)
        assertTrue(timelineScrollPosition() > 0f)
    }

    private fun withHome(theme: AppTheme = AppTheme.BasicDark, page: HomePageState? = null,
        search: (String) -> Unit = {}, select: (LocalDate, String) -> Unit = { _, _ -> },
        open: (com.txl.mebot.data.home.HomeCard) -> Unit = {}, content: (@Composable () -> Unit)? = null,
        motionScale: Float? = null, block: (Fixture) -> Unit) {
        // 每组窗口使用本次 Robolectric 时钟，不复用其他测试留下的 Choreographer。
        val scale = motionScale?.let { object : MotionDurationScale { override val scaleFactor = it } } ?: EmptyCoroutineContext
        WindowRecomposerPolicy.withFactory({ view -> view.createLifecycleAwareWindowRecomposer(
            Handler(Looper.getMainLooper()).asCoroutineDispatcher() + AndroidUiFrameClock(Choreographer.getInstance()) + scale,
        ) }) {
            val home = Fixture(theme, page, search, select, open, content)
            try { block(home) } finally { home.destroy() }
        }
    }

    private class Fixture(theme: AppTheme, page: HomePageState?, search: (String) -> Unit, select: (LocalDate, String) -> Unit,
        open: (com.txl.mebot.data.home.HomeCard) -> Unit, content: (@Composable () -> Unit)?) {
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val decor get() = controller.get().window.decorView
        init {
            controller.get().setContent { MebotTheme(theme) {
                if (content != null) content() else HomeScreen(today = LocalDate.of(2026, 10, 30), state = page, onSearchArchive = search, onSelectNote = select, onOpenCard = open)
            } }
            frames(700)
        }
        fun nodes() = nodes(decor)
        fun unmergedTag(tag: String) = requireNotNull(nodes(decor, true).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }) { "Missing $tag" }
        fun hasUnmergedTag(tag: String) = nodes(decor, true).any { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun tag(tag: String) = requireNotNull(nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }) { "Missing $tag" }
        fun hasTag(tag: String) = nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun input() = description("首页需求输入")
        fun description(text: String) = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true }
        fun click(node: SemanticsNode) { assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()) }
        fun frames(milliseconds: Long) {
            repeat(((milliseconds + 15) / 16).toInt()) {
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                measure(decor)
                ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let(::measure)
            }
        }
        fun until(condition: () -> Boolean) {
            repeat(150) { if (condition()) return; frames(16) }
            assertTrue("Expected UI state within 150 frames", condition())
        }
        fun swipe(view: View, x: Float, y: Float, distance: Float, cancel: Boolean = false) {
            val start = SystemClock.uptimeMillis()
            for (step in 0..12) {
                val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 12 -> if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val event = MotionEvent.obtain(start, start + step * 16L, action, x, y + distance * step / 12f, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                frames(16)
            }
        }
        fun horizontal(view: View, x: Float, y: Float, distance: Float) {
            val start = SystemClock.uptimeMillis()
            for (step in 0..12) {
                val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val event = MotionEvent.obtain(start, start + step * 16L, action, x + distance * step / 12f, y, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                frames(16)
            }
        }
        fun tapTopEnd(node: SemanticsNode) {
            val bounds = node.boundsInWindow
            tap(decor, bounds.right - 20f, bounds.top + 20f)
        }
        fun tap(view: View, x: Float, y: Float) {
            val start = SystemClock.uptimeMillis()
            for ((time, action) in listOf(start to MotionEvent.ACTION_DOWN, start + 1 to MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(start, time, action, x, y, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        fun snapshot(name: String, view: View = decor) {
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            val directory = File("build/reports/home-preview").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        fun destroy() { ShadowDialog.getLatestDialog()?.dismiss(); controller.pause().stop().destroy(); frames(120) }
    }

    companion object {
        private fun views(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
        }
        private fun measure(view: View) {
            views(view).filter { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }
                .forEach { it.javaClass.getMethod("measureAndLayoutForTest").invoke(it) }
        }
        private fun nodes(view: View, unmerged: Boolean = false): List<SemanticsNode> = views(view)
            .filter { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }
            .flatMap {
                it.javaClass.getMethod("measureAndLayoutForTest").invoke(it)
                val owner = it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner
                descendants(if (unmerged) owner.unmergedRootSemanticsNode else owner.rootSemanticsNode)
            }.toList()
        private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            for (child in node.children) yieldAll(descendants(child))
        }
    }
}
