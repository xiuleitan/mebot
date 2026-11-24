package com.txl.mebot.ui.home

import android.content.ActivityNotFoundException
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.txl.mebot.R
import com.txl.mebot.ui.common.*
import com.txl.mebot.ui.schedule.*
import kotlinx.coroutines.launch

@Composable
internal fun HomePendingMedia(model: HomeComposerViewModel?, state: HomeComposerState, enabled: Boolean, onAdd: () -> Unit) {
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    ComposerPendingMediaRow(state.items, key = { it.asset.id }, isImage = { it.isImage }, enabled = enabled,
        onAdd = onAdd, onRemove = { model?.remove(it.asset.id) }, thumbnail = { item, tile ->
            ComposerMediaTile(item.asset, item.link.displayName, { previewId = item.asset.id }, tile)
        })
    val preview = state.items.firstOrNull { it.asset.id == previewId }
    if (preview != null) {
        if (preview.isImage) HomePendingImagePreview(preview.asset.id, state, enabled,
            onRemove = { model?.remove(it) }, onDismiss = { previewId = null })
        else {
            val context = LocalContext.current
            val colors = scheduleColors()
            val scope = rememberCoroutineScope()
            Dialog(onDismissRequest = { previewId = null }) {
                Surface(shape = RoundedCornerShape(24.dp), color = colors.surface, contentColor = colors.ink) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(preview.link.displayName, Modifier.weight(1f), fontSize = 18.sp)
                            IconButton({ previewId = null }) {
                                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close_panel))
                            }
                        }
                        Text(formatAttachmentSize(preview.asset.byteCount), color = colors.secondary, fontSize = 12.sp)
                        TextButton({ scope.launch {
                            try { model?.fileIntent(preview)?.let(context::startActivity) }
                            catch (_: ActivityNotFoundException) { model?.report(AttachmentFailure.NO_VIEWER) }
                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { model?.report(AttachmentFailure.MISSING) }
                        } }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.schedule_open_file)) }
                        TextButton({ model?.remove(preview.asset.id) }, Modifier.fillMaxWidth(), enabled = enabled) {
                            Text(stringResource(R.string.schedule_remove_attachment), color = MaterialTheme.colorScheme.error)
                        }
                        state.failure?.let { Text(stringResource(it.label()), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    }
                }
            }
        }
    } else if (previewId != null) LaunchedEffect(previewId) { previewId = null }
}

@Composable
private fun HomePendingImagePreview(initialId: String, state: HomeComposerState, enabled: Boolean,
    onRemove: (String) -> Unit, onDismiss: () -> Unit) {
    val images = state.items.filter { it.isImage }
    val pager = rememberPagerState(initialPage = images.indexOfFirst { it.asset.id == initialId }.coerceAtLeast(0)) { images.size }
    PagerSelectionHaptics(pager) { images.getOrNull(it)?.asset?.id }
    LaunchedEffect(images.size) { if (pager.currentPage > images.lastIndex && images.isNotEmpty()) pager.scrollToPage(images.lastIndex) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
            HorizontalPager(pager, key = { images[it].asset.id }, modifier = Modifier.fillMaxSize()) { page ->
                val item = images[page]
                val photo = localPhoto(item.asset.relativePath, maxDimension = 2048)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (photo != null) Image(photo, item.link.displayName, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else Icon(painterResource(R.drawable.ic_image), stringResource(R.string.schedule_attachment_missing), tint = Color.White)
                }
            }
            Text("${pager.currentPage + 1} / ${images.size}", Modifier.align(Alignment.TopCenter).padding(20.dp), color = Color.White)
            IconButton(hapticAction(onDismiss), Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close_panel), tint = Color.White)
            }
            OutlinedButton(hapticAction { images.getOrNull(pager.currentPage)?.let { onRemove(it.asset.id) } },
                Modifier.align(Alignment.BottomEnd).padding(16.dp), enabled = enabled,
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Black.copy(alpha = .6f), contentColor = Color.White)) {
                Text(stringResource(R.string.schedule_remove_attachment))
            }
        }
    }
}
