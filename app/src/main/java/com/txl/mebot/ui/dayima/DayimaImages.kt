package com.txl.mebot.ui.dayima

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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.ui.common.ComposerPendingMediaRow
import com.txl.mebot.data.dayima.DayimaImage
import com.txl.mebot.ui.schedule.localPhoto
import com.txl.mebot.ui.schedule.scheduleColors

@Composable
internal fun DayimaImages(owner: String, images: List<DayimaImage>, modifier: Modifier = Modifier,
    dense: Boolean = false, enabled: Boolean = true, onRemove: ((String) -> Unit)? = null, onAdd: (() -> Unit)? = null,
    thumbnailSize: DpSize = DpSize(112.dp, 112.dp), spacing: Dp = 8.dp) {
    if (images.isEmpty()) return
    val colors = scheduleColors()
    var preview by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    LaunchedEffect(images) { if (images.none { it.asset.id == preview }) preview = null }
    val thumbnail: @Composable (DayimaImage, Modifier) -> Unit = { item, tileModifier ->
        Surface(onClick = { preview = item.asset.id }, shape = RoundedCornerShape(if (dense && onAdd != null) 13.dp else 14.dp), color = colors.past,
            modifier = tileModifier) {
            val photo = localPhoto(item.asset.relativePath, maxDimension = 256)
            if (photo != null) Image(photo, item.displayName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_image), stringResource(R.string.schedule_attachment_missing),
                    Modifier.size(28.dp), tint = colors.secondary)
            }
        }
    }
    if (dense && onAdd != null && onRemove != null) {
        ComposerPendingMediaRow(images, key = { it.asset.id }, isImage = { true }, enabled = enabled,
            onAdd = onAdd, onRemove = { onRemove(it.asset.id) }, modifier = modifier, thumbnail = thumbnail)
    } else LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
        items(images, key = { it.asset.id }) { item ->
            Box(Modifier.size(if (dense) DpSize(80.dp, 80.dp) else thumbnailSize)) {
                thumbnail(item, Modifier.fillMaxSize())
                if (onRemove != null) IconButton(onClick = { onRemove(item.asset.id) }, enabled = enabled,
                    modifier = Modifier.align(Alignment.TopEnd).size(48.dp), shape = CircleShape) {
                    Surface(shape = CircleShape, color = colors.surface.copy(alpha = 0.95f), shadowElevation = 1.dp) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.schedule_remove_attachment),
                            Modifier.padding(5.dp).size(16.dp), tint = colors.ink)
                    }
                }
            }
        }
    }
    preview?.let { id -> DayimaImagePreview(id, images) { preview = null } }
}

@Composable
private fun DayimaImagePreview(id: String, images: List<DayimaImage>, onDismiss: () -> Unit) {
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
                val image = images[page]
                val photo = localPhoto(image.asset.relativePath, maxDimension = 2048)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (photo != null) Image(photo, image.displayName, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else Text(stringResource(R.string.schedule_attachment_missing), color = Color.White)
                }
            }
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${pager.currentPage + 1} / ${images.size}", Modifier.weight(1f), color = Color.White)
                IconButton(onClick = hapticAction(onDismiss), modifier = Modifier.size(48.dp), shape = CircleShape,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f), contentColor = Color.White)) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close_panel), Modifier.size(24.dp))
                }
            }
        }
    }
}
