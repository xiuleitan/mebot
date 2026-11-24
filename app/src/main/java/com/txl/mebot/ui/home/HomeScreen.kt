package com.txl.mebot.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.txl.mebot.R
import com.txl.mebot.ui.common.ArchiveSearchField
import com.txl.mebot.ui.common.BotNavigationButton
import com.txl.mebot.ui.schedule.scheduleColors
import java.time.LocalDate
import kotlinx.coroutines.launch

/** 主界面布局；无状态参数时仅用于设计预览。正式入口通过 HomeRoute 读取本地事实。 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: () -> Unit = {},
    onOpenTrading: () -> Unit = {},
    today: LocalDate = LocalDate.now(),
    state: HomePageState? = null,
    onSearchArchive: (String) -> Unit = {},
    onOpenCard: (com.txl.mebot.data.home.HomeCard) -> Unit = {},
    onSelectNote: (LocalDate, String) -> Unit = { _, _ -> },
    onPinNote: (String, Boolean) -> Unit = { _, _ -> },
    onMore: () -> Unit = {}, onRetry: () -> Unit = {}, onNoticeConsumed: () -> Unit = {},
    reflectionCard: (@Composable () -> Unit)? = null,
    composerModel: HomeComposerViewModel? = null,
    composerHost: HomeComposerHost? = null,
) {
    val colors = scheduleColors()
    var query by rememberSaveable { mutableStateOf("") }
    val timelineGrid = remember(today) { LazyGridState() }
    var expandedTimeline by remember(today) { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val searchNotice = stringResource(R.string.home_search_preview)
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        expandedTimeline = false
        timelineGrid.requestScrollToItem(0)
    }
    fun notice(message: String) {
        scope.launch { snackbars.currentSnackbarData?.dismiss(); snackbars.showSnackbar(message) }
    }
    val search = { focus.clearFocus(); keyboard?.hide(); if (state == null) notice(searchNotice) else onSearchArchive(query.trim()) }
    LaunchedEffect(state?.notice) { state?.notice?.let { notice(it); onNoticeConsumed() } }
    val composer: HomeComposerHost = composerHost ?: { mod, notify, body -> HomeComposerWorkspace(composerModel, notify, mod, body) }

    Box(modifier.fillMaxSize().background(colors.backgroundTop)
        .windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        composer(Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxSize(), ::notice) { bottomPadding ->
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    HomeTopBar(query, { query = it }, search, onOpenDrawer) {
                        focus.clearFocus()
                        keyboard?.hide()
                        onOpenTrading()
                    }
                    if (state?.error != null) TextButton(onRetry, Modifier.fillMaxWidth()) { Text(state.error) }
                    if (state?.loading == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                    HomeTimeline(
                        today,
                        expanded = expandedTimeline, onToggleExpansion = { expandedTimeline = !expandedTimeline },
                        bottomPadding = bottomPadding,
                        onOpenEntry = { focus.clearFocus(); keyboard?.hide(); selectedId = it.id },
                        modifier = Modifier.weight(1f), data = state?.timeline, onOpenCard = { focus.clearFocus(); keyboard?.hide(); onOpenCard(it) },
                        onSelectNote = onSelectNote, onPinNote = onPinNote, onMore = onMore, loading = state?.loading == true,
                        reflectionCard = reflectionCard,
                        grid = timelineGrid,
                    )
                }
                SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = bottomPadding + 8.dp))
            }
        }
    }
    if (state == null) homePreviewEntries.firstOrNull { it.id == selectedId }?.let { entry ->
        HomePreviewDetail(entry, today.plusDays(entry.dayOffset)) { selectedId = null }
    }
}

@Composable
private fun HomeTopBar(
    query: String, onQuery: (String) -> Unit, onSearch: () -> Unit,
    onOpenDrawer: () -> Unit, onOpenTrading: () -> Unit,
) {
    val colors = scheduleColors()
    val searchLabel = stringResource(R.string.home_search_label)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        BotNavigationButton(R.drawable.ic_menu, R.string.open_sidebar, colors.surface, colors.ink, onOpenDrawer)
        ArchiveSearchField(query, onQuery, onSearch, Modifier.weight(1f).testTag("home_search_container"),
            fieldModifier = Modifier.testTag("home_search"), searchLabel = searchLabel,
            placeholder = stringResource(R.string.home_search_hint), searchAction = stringResource(R.string.home_search),
            clearAction = stringResource(R.string.home_clear_search))
        BotNavigationButton(R.drawable.ic_trading, R.string.trading_title, colors.surface, colors.ink, onOpenTrading)
    }
}
