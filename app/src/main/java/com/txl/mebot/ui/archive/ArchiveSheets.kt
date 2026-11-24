package com.txl.mebot.ui.archive

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.data.archive.ArchiveRecord
import com.txl.mebot.ui.common.DetailDeleteButton
import com.txl.mebot.ui.common.DetailDeleteConfirmationDialog
import com.txl.mebot.ui.common.botSheetScrim
import com.txl.mebot.ui.schedule.scheduleColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArchiveSheet(title: String, dismiss: () -> Unit, busy: Boolean = false, beforeClose: suspend () -> Boolean = { true },
    content: @Composable ColumnScope.(close: (() -> Unit) -> Unit) -> Unit) {
    val colors = scheduleColors()
    val allowed by rememberUpdatedState(!busy)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || allowed })
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var closing by remember { mutableStateOf(false) }
    val close: (() -> Unit) -> Unit = { after ->
        if (allowed && !closing) scope.launch {
            closing = true; focus.clearFocus(); keyboard?.hide()
            try { if (beforeClose()) { sheet.hide(); dismiss(); after() } } finally { closing = false }
        }
    }
    ModalBottomSheet(onDismissRequest = { close {} }, sheetState = sheet, containerColor = colors.surface, contentColor = colors.ink,
        scrimColor = botSheetScrim(),
        sheetGesturesEnabled = false,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = true, shouldDismissOnClickOutside = true),
        dragHandle = null) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(top = 12.dp).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(hapticAction { close {} }, enabled = allowed && !closing) { Icon(painterResource(R.drawable.ic_close), "关闭面板", Modifier.size(22.dp)) }
            }
            content(close)
        }
    }
}

@Composable
internal fun ArchiveHistory(state: ArchivePageState, model: ArchiveViewModel) {
    val colors = scheduleColors()
    ArchiveSheet("档案会话", model::dismissHistory, state.busy) { close ->
        TextButton({ close { model.newSession() } }, Modifier.padding(horizontal = 12.dp)) { Text("新建档案会话") }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.sessions, key = { it.id }) { session ->
                Surface(onClick = { close { model.selectSession(session.id) } }, enabled = !state.busy, color = colors.past, shape = RoundedCornerShape(15.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(session.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(session.preview.ifBlank { if (session.closed) "历史记录" else "尚未填写内容" }, color = colors.secondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (state.moreSessions) item { TextButton({ model.history(true) }, enabled = !state.busy) { Text("加载更早会话") } }
        }
    }
}

@Composable
internal fun ArchiveDetail(record: ArchiveRecord, state: ArchivePageState, model: ArchiveViewModel) {
    val colors = scheduleColors()
    val context = LocalContext.current
    val data = record.content
    var preview by rememberSaveable(record.id) { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable(record.id) { mutableStateOf(false) }
    ArchiveSheet(data.title, model::closeDetail, state.busy) { close ->
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (data.type.isNotBlank() || data.tags.isNotEmpty()) item { Text((listOf(data.type).filter(String::isNotBlank) + data.tags).joinToString(" · "), color = colors.secondary, fontSize = 13.sp) }
            if (data.body.isNotBlank()) item { SelectionContainer { Text(data.body, fontSize = 15.sp, lineHeight = 22.sp) } }
            if (data.assetIds.isNotEmpty()) item {
                Text("附件", fontWeight = FontWeight.SemiBold)
                LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(data.assetIds, key = { it }) { id -> Surface(onClick = hapticAction { preview = id }, shape = RoundedCornerShape(14.dp), modifier = Modifier.size(120.dp)) {
                        ArchivePicture(id, model, Modifier.fillMaxSize())
                    } }
                }
            }
            itemsIndexed(data.fields) { _, field -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text(field.name.trimEnd(' ', '：', ':') + "：", Modifier.weight(.32f), fontSize = 13.sp, lineHeight = 22.sp, color = colors.secondary)
                SelectionContainer(Modifier.weight(.68f)) { Text(field.value, Modifier.fillMaxWidth(), fontSize = 15.sp, lineHeight = 22.sp) }
            } }
            itemsIndexed(data.links) { _, link ->
                Surface(shape = RoundedCornerShape(12.dp), color = colors.past) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        if (link.label.isNotBlank()) Text(link.label, fontWeight = FontWeight.Medium)
                        SelectionContainer { Text(link.url, fontSize = 13.sp, lineHeight = 21.sp) }
                        if (validArchiveLink(link.url)) TextButton({ try { context.startActivity(Intent(Intent.ACTION_VIEW, link.url.toUri())) }
                            catch (_: Exception) { model.noViewer() } }) { Text("打开链接") }
                    }
                }
            }
            state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) } }
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = colors.divider)
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailDeleteButton(onClick = { deleting = true }, enabled = !state.busy,
                modifier = Modifier.weight(1f), label = "删除档案")
            Button(hapticAction { close { model.editRecord(record.id) } }, enabled = !state.busy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDDEBFF), contentColor = Color(0xFF245580))) {
                Text("编辑档案", fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
        if (deleting) DetailDeleteConfirmationDialog(
            title = stringResource(R.string.archive_delete_confirmation_title),
            onDismiss = { deleting = false }, enabled = !state.busy,
            onConfirm = {
                if (deleting && !state.busy) {
                    deleting = false
                    close { model.delete(record) }
                }
            },
            text = { Text(stringResource(R.string.archive_delete_confirmation_body, data.title)) },
        )
    }
    preview?.let { ArchiveAssetDialog(it, model, data.assetIds) { preview = null } }
}

@Composable
internal fun ArchivePicture(id: String, model: ArchiveViewModel, modifier: Modifier = Modifier, label: String = "附件", maxEdge: Int = 384, fit: Boolean = false) {
    val colors = scheduleColors()
    var name by remember(id) { mutableStateOf(label) }
    var failed by remember(id) { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, id, maxEdge) {
        try { name = model.attachments.metadata(id).name; value = model.media.image(id, maxEdge)?.asImageBitmap() }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed = true }
    }
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = colors.past) {
        val bitmap = image
        if (bitmap != null) Image(bitmap, name, Modifier.fillMaxSize(), contentScale = if (fit) ContentScale.Fit else ContentScale.Crop)
        else Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_file), null, Modifier.size(26.dp), tint = colors.secondary)
            Text(if (failed) "附件预览不可用" else name, Modifier.padding(top = 7.dp), color = colors.secondary, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun ArchiveFileDialog(id: String, model: ArchiveViewModel, dismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = scheduleColors()
    var name by remember(id) { mutableStateOf("附件") }
    val state by model.state.collectAsState()
    LaunchedEffect(id) { try { name = model.attachments.metadata(id).name } catch (_: Exception) { } }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 640.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = colors.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(dismiss) { Icon(painterResource(R.drawable.ic_close), "关闭附件预览", Modifier.size(22.dp)) }
                }
                ArchivePicture(id, model, Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 440.dp).aspectRatio(.85f), name, maxEdge = 1600, fit = true)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                TextButton({ model.openFile(id) { uri, mime ->
                    try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    catch (_: ActivityNotFoundException) { model.noViewer() }
                } }, enabled = !state.busy) { Text("使用其他应用打开") }
            }
        }
    }
}
