package com.txl.mebot.ui.flashnote

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.ui.common.ComposerPendingMediaRow
import com.txl.mebot.data.local.FlashnoteAttachment
import com.txl.mebot.ui.schedule.formatAttachmentSize
import com.txl.mebot.ui.schedule.localPhoto
import com.txl.mebot.ui.schedule.scheduleColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 草稿、编辑使用小图；详情及全屏按实际显示范围读取同一份本地原图。 */
@Composable
internal fun FlashnoteMediaContent(
    items: List<FlashnoteAttachment>, onOpenFile: suspend (FlashnoteAttachment) -> Intent?,
    modifier: Modifier = Modifier, dense: Boolean = false, enabled: Boolean = true,
    onRemove: ((String) -> Unit)? = null, imageWidth: Dp? = null, onAdd: (() -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val colors = scheduleColors()
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    val images = items.filter { it.isImage }
    LaunchedEffect(items) { if (items.none { it.asset.id == previewId }) previewId = null }
    val thumbnail: @Composable (FlashnoteAttachment, Modifier) -> Unit = { item, tileModifier ->
        val itemWidth = if (item.isImage && imageWidth != null) imageWidth else if (dense) 88.dp else 112.dp
        Surface(onClick = {
            if (item.isImage) previewId = item.asset.id
            else scope.launch {
                val message = try {
                    val intent = onOpenFile(item)
                    if (intent == null) R.string.schedule_attachment_missing
                    else { context.startActivity(intent); null }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: ActivityNotFoundException) { R.string.schedule_attachment_no_viewer
                } catch (_: Exception) { R.string.schedule_attachment_missing }
                message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            }
        }, shape = RoundedCornerShape(if (dense && onAdd != null) 13.dp else 14.dp), color = colors.past, modifier = tileModifier) {
            if (item.isImage) {
                val photo = if (imageWidth == null) localPhoto(item.asset.relativePath, maxDimension = 256)
                else {
                    val size = with(density) { itemWidth.roundToPx() }
                    flashnoteDetailPhoto(item.asset.relativePath, IntSize(size, size), crop = true)
                }
                if (photo != null) Image(photo, item.displayName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_image), stringResource(R.string.schedule_attachment_missing), Modifier.size(28.dp), tint = colors.secondary)
                }
            } else Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(painterResource(R.drawable.ic_file), null, Modifier.size(26.dp), tint = colors.secondary)
                Text(item.displayName, color = colors.ink, fontSize = 11.sp, lineHeight = 15.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!dense || onAdd != null) Text(formatAttachmentSize(item.asset.byteCount), color = colors.secondary, fontSize = if (dense) 9.sp else 10.sp)
            }
        }
    }
    if (dense && onAdd != null && onRemove != null) {
        ComposerPendingMediaRow(items, key = { it.asset.id }, isImage = { it.isImage }, enabled = enabled,
            onAdd = onAdd, onRemove = { onRemove(it.asset.id) }, modifier = modifier, thumbnail = thumbnail)
    } else LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = if (dense) PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp) else PaddingValues(0.dp)) {
        items(items, key = { it.asset.id }) { item ->
            val itemWidth = if (item.isImage && imageWidth != null) imageWidth else if (dense) 88.dp else 112.dp
            Box(Modifier.width(itemWidth)) {
                thumbnail(item, Modifier.fillMaxWidth().aspectRatio(1f))
                if (onRemove != null) IconButton({ onRemove(item.asset.id) }, enabled = enabled,
                    modifier = Modifier.align(Alignment.TopEnd).size(48.dp), shape = CircleShape) {
                    Surface(shape = CircleShape, color = colors.surface.copy(alpha = 0.95f), shadowElevation = 1.dp) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.schedule_remove_attachment),
                            Modifier.padding(5.dp).size(16.dp), tint = colors.ink)
                    }
                }
            }
        }
    }
    previewId?.let { id -> FlashnoteImagePreview(id, images, onDismiss = { previewId = null }) }
}

@Composable
private fun FlashnoteImagePreview(id: String, images: List<FlashnoteAttachment>, onDismiss: () -> Unit) {
    if (images.isEmpty()) return
    val pager = rememberPagerState(initialPage = images.indexOfFirst { it.asset.id == id }.coerceAtLeast(0)) { images.size }
    com.txl.mebot.ui.common.PagerSelectionHaptics(pager) { images.getOrNull(it)?.asset?.id }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.let {
            WindowCompat.getInsetsController(it, view).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
        } }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { images[it].asset.id }) { page ->
                val item = images[page]
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val density = LocalDensity.current
                    val targetSize = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
                    val photo = flashnoteDetailPhoto(item.asset.relativePath, targetSize, crop = false)
                    if (photo != null) Image(photo, item.displayName, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else Text(stringResource(R.string.schedule_attachment_missing), color = Color.White)
                }
            }
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${pager.currentPage + 1} / ${images.size}", Modifier.weight(1f), color = Color.White, fontSize = 14.sp)
                IconButton(hapticAction(onDismiss), Modifier.size(48.dp), shape = CircleShape,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f), contentColor = Color.White)) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close_panel), Modifier.size(24.dp))
                }
            }
        }
    }
}
