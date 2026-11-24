package com.txl.mebot.ui.archive

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.ui.common.ArchiveSearchField
import com.txl.mebot.ui.schedule.scheduleColors

/** 目的地内持有自己的 ViewModel；不改变其他 Bot 导航或输入状态。 */
@Composable
fun ArchiveRoute(onOpenDrawer: () -> Unit, onReturnHome: () -> Unit,
    pendingSearch: String? = null, onSearchConsumed: () -> Unit = {}) {
    val context = LocalContext.current
    val owner = remember(context) { context.archiveActivity() }
    val model = remember(owner) { ViewModelProvider(owner)[ArchiveViewModel::class.java] }
    LaunchedEffect(pendingSearch) {
        pendingSearch?.takeIf { it.isNotBlank() }?.let {
            model.changeQuery(it)
            model.search()
            onSearchConsumed()
        }
    }
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.saveInput()
            if (event == Lifecycle.Event.ON_RESUME) model.refreshList()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.saveInput() }
    }
    ArchivePage(state, model, onOpenDrawer, onReturnHome)
}

internal fun Context.archiveActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.archiveActivity()
    else -> error("Archive requires an Activity ViewModel owner")
}

@Composable
private fun ArchivePage(state: ArchivePageState, model: ArchiveViewModel, drawer: () -> Unit, home: () -> Unit) {
    val colors = scheduleColors()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it); model.dismissNotice() } }
    fun navigate(action: () -> Unit) { model.saveInput(); focus.clearFocus(); keyboard?.hide(); action() }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.backgroundTop, colors.backgroundBottom)))
        .windowInsetsPadding(WindowInsets.safeDrawing)) {
        ArchiveComposer(state, model, Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxSize()) { bottom ->
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp, 16.dp, 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NavigationButton(R.drawable.ic_menu, R.string.open_sidebar) { navigate(drawer) }
                    ArchiveSearchField(state.query, model::changeQuery, { navigate { model.search() } }, Modifier.weight(1f))
                    NavigationButton(R.drawable.ic_home, R.string.return_home) { navigate(home) }
                }
                ArchiveSearchStatus(state, model)
                LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottom + 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.loading) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (state.cards.isEmpty() && state.submittedQuery == null && !state.loading && state.error == null) item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(painterResource(R.drawable.ic_archive), null, Modifier.size(44.dp), tint = colors.secondary)
                            Text("把重要资料留在这里", Modifier.padding(top = 18.dp), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            Text("添加文字、图片或文件，整理后核对保存。", Modifier.padding(top = 10.dp), color = colors.secondary, fontSize = 13.sp)
                        }
                    }
                    if (state.error != null && state.context == null) item(span = { GridItemSpan(maxLineSpan) }) {
                        TextButton({ model.retry() }, enabled = !state.busy) { Text("档案暂未读取成功 · 重试") }
                    }
                    if (state.submittedQuery != null && state.searchCards.isEmpty() && !state.searchLoading && state.searchFailure == null) item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(if (state.localTextSearch) "没有匹配的本地档案" else if (state.indexSummary.ready == 0) "档案正在准备搜索，可在 App 设置 → 模型与 API 中查看进度。" else "当前可搜索的档案中没有结果", Modifier.padding(24.dp), color = colors.secondary)
                    }
                    items(state.displayedCards, key = { it.record.id }) { card -> ArchiveCard(card, model,
                        { navigate { model.openDetail(card.record.id) } }, !state.busy, Modifier.animateItem()) }
                    if (state.submittedQuery == null && state.moreRecords) item(span = { GridItemSpan(maxLineSpan) }) {
                        TextButton({ model.moreRecords() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("加载更多档案") }
                    }
                    if (state.submittedQuery != null && state.moreSearch) item(span = { GridItemSpan(maxLineSpan) }) {
                        TextButton(model::moreSearch, enabled = !state.searchLoading, modifier = Modifier.fillMaxWidth()) { Text("加载更多结果") }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp, start = 16.dp, end = 16.dp))
    }
    state.detail?.let { ArchiveDetail(it, state, model) }
    if (state.editorOpen) ArchiveEditor(state, model)
}


@Composable
private fun ArchiveCard(card: ArchiveCardData, model: ArchiveViewModel, click: () -> Unit, enabled: Boolean, modifier: Modifier) {
    val colors = scheduleColors()
    val content = card.record.content
    val titleHeight = with(LocalDensity.current) { 21.sp.toDp() * 2 }
    Surface(onClick = hapticAction(click), enabled = enabled, modifier = modifier.fillMaxWidth().height(24.dp + 10.dp + titleHeight + 72.dp),
        shape = RoundedCornerShape(20.dp), color = colors.surface, contentColor = colors.ink, shadowElevation = 2.dp,
        border = BorderStroke(1.dp, colors.divider.copy(alpha = .55f))) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(content.title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (card.coverId != null) {
                ArchivePicture(card.coverId, model, Modifier.fillMaxWidth().weight(1f))
                if (!card.snippet.isNullOrBlank()) Text(card.snippet, color = colors.secondary, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Text(card.snippet ?: content.body.ifBlank { content.fields.joinToString("\n") { "${it.name}：${it.value}" } }.ifBlank { content.links.joinToString("\n") { it.label.ifBlank { it.url } } },
                Modifier.fillMaxWidth().weight(1f), color = colors.secondary, fontSize = 13.sp, lineHeight = 21.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NavigationButton(icon: Int, label: Int, click: () -> Unit) {
    val colors = scheduleColors()
    IconButton(hapticAction(click), modifier = Modifier.size(48.dp), shape = CircleShape,
        colors = IconButtonDefaults.iconButtonColors(containerColor = colors.surface, contentColor = colors.ink)) {
        Icon(painterResource(icon), stringResource(label), Modifier.size(24.dp))
    }
}
