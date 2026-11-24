package com.txl.mebot.ui.subscriptions

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.ui.common.DetailDeleteConfirmationDialog

/** 聊天附件只读预览保留原入口，订阅详情通过同一个画布管理图库。 */
@Composable
internal fun SubscriptionImagePreview(assetId: String, model: SubscriptionsViewModel?, onDismiss: () -> Unit) {
    SubscriptionImagePreview(assetId, listOf(assetId), model, onDismiss = onDismiss)
}

@Composable
internal fun SubscriptionImagePreview(initialImageId: String, images: List<String>, model: SubscriptionsViewModel?,
    coverAssetId: String? = null, enabled: Boolean = true, busy: Boolean = false, error: String? = null,
    onSetCover: ((String) -> Unit)? = null, onRemove: ((String) -> Unit)? = null, onDismiss: () -> Unit) {
    if (images.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val pager = rememberPagerState(initialPage = images.indexOf(initialImageId).coerceAtLeast(0)) { images.size }
    com.txl.mebot.ui.common.PagerSelectionHaptics(pager) { images.getOrNull(it) }
    var removal by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(images.size) { if (pager.currentPage > images.lastIndex) pager.scrollToPage(images.lastIndex) }
    val current = images[pager.currentPage.coerceIn(0, images.lastIndex)]
    val managing = onSetCover != null && onRemove != null
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.clearFocus(); keyboard?.hide() }
    Dialog(onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            HorizontalPager(pager, key = { images[it] }, userScrollEnabled = !busy,
                modifier = Modifier.fillMaxSize().testTag("subscription_image_gallery")) { page ->
                val photo = subscriptionPhoto(images[page], model, maxPixels = 2048)
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 56.dp, bottom = if (managing) 80.dp else 0.dp), contentAlignment = Alignment.Center) {
                    val image = photo.image
                    when {
                        image != null -> Image(image, "订阅图片 ${page + 1}/${images.size}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        photo.loading -> CircularProgressIndicator(Modifier.size(32.dp), color = Color.White)
                        else -> Text("图片暂时无法显示", color = Color.White)
                    }
                }
            }
            Text("${pager.currentPage.coerceIn(0, images.lastIndex) + 1} / ${images.size}", color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(top = 22.dp).testTag("subscription_image_position"), fontSize = 14.sp)
            if (coverAssetId == current && managing) Text("当前封面", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start))
                    .padding(start = 20.dp, top = 22.dp))
            if (managing) Column(Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = Color(0xFFFFB4AB), fontSize = 12.sp) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Button(hapticAction { removal = current }, enabled = enabled, shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("subscription_image_delete"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBE0E0), contentColor = Color(0xFF9E3030))) { Text("删除") }
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    Button(hapticAction { onSetCover?.invoke(current) }, enabled = enabled && current != coverAssetId,
                        shape = RoundedCornerShape(24.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("subscription_image_set_cover"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3A), contentColor = Color.White,
                            disabledContainerColor = Color(0xFF303030), disabledContentColor = Color(0xFFAAAAAA))) { Text("设为封面") }
                }
            }
            IconButton(onClick = hapticAction(onDismiss), enabled = !busy,
                modifier = Modifier.align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                    .padding(top = 8.dp, end = 12.dp).size(48.dp), shape = CircleShape,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.55f), contentColor = Color.White)) {
                Icon(painterResource(R.drawable.ic_close), "关闭图片", Modifier.size(24.dp))
            }
        }
    }
    removal?.let { asset ->
        DetailDeleteConfirmationDialog(title = "删除这张图片？", onDismiss = { removal = null },
            onConfirm = { removal = null; if (asset in images) onRemove?.invoke(asset) }, enabled = enabled && asset in images,
            text = { Text("图片将从此订阅中移除，聊天记录和其他内容中的图片会保留。") })
    }
}
