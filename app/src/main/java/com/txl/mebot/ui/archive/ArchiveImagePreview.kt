package com.txl.mebot.ui.archive

import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.txl.mebot.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
internal fun ArchiveAssetDialog(id: String, model: ArchiveViewModel, assetIds: List<String> = listOf(id), dismiss: () -> Unit) {
    key(id, assetIds) {
        var failed by remember { mutableStateOf(false) }
        val album by produceState<ArchiveImageAlbum?>(null) {
            try { value = archiveImageAlbum(id, assetIds, model.attachments) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
        val current = album
        if (current != null) {
            if (current.imageIds.isNotEmpty()) ArchiveImagePreview(id, current.imageIds, model, dismiss)
            else ArchiveFileDialog(id, model, dismiss)
        } else if (failed) AlertDialog(onDismissRequest = dismiss, title = { Text("附件暂时无法读取") },
            text = { Text("原档案仍保留，请关闭后重新尝试。") }, confirmButton = { TextButton(dismiss) { Text("关闭") } })
    }
}

/** 黑底全屏画布只显示图片；当前页操作绑定图片 ID，滑动后不会保存错图。 */
@Composable
private fun ArchiveImagePreview(initialId: String, images: List<String>, model: ArchiveViewModel, dismiss: () -> Unit) {
    val context = LocalContext.current
    val downloads = remember(model) { ArchiveImageDownloads(context.applicationContext, model.attachments) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = images.indexOf(initialId).coerceAtLeast(0)) { images.size }
    com.txl.mebot.ui.common.PagerSelectionHaptics(pager) { images.getOrNull(it) }
    var savingId by remember { mutableStateOf<String?>(null) }
    var pendingDocumentId by rememberSaveable { mutableStateOf<String?>(null) }
    fun notice(message: String) { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    fun saveImage(id: String, target: Uri? = null) {
        if (savingId != null) return
        savingId = id
        scope.launch {
            try {
                if (target == null) downloads.saveToGallery(id) else downloads.saveToDocument(id, target)
                val page = if (images.size > 1) "第 ${images.indexOf(id) + 1} 张图片" else "图片"
                notice(if (target == null) "${page}已保存到系统相册的 mebot 相簿" else "${page}已保存到所选位置")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("图片未能保存，请重试。原档案仍保留。") }
            finally { savingId = null }
        }
    }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
        val id = pendingDocumentId
        pendingDocumentId = null
        if (id != null && uri != null) saveImage(id, uri)
    }
    LaunchedEffect(Unit) { focus.clearFocus(); keyboard?.hide() }
    val currentId = images[pager.currentPage.coerceIn(0, images.lastIndex)]
    val busy = savingId != null || pendingDocumentId != null
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { images[it] }, beyondViewportPageCount = 0) { page ->
                if (abs(page - pager.currentPage) <= 1) ArchiveFullPhoto(images[page], model)
            }
            if (images.size > 1) Text("${pager.currentPage + 1} / ${images.size}", color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start))
                    .padding(top = 16.dp, start = 16.dp).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp))
            IconButton(dismiss, modifier = Modifier.align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                .padding(top = 8.dp, end = 12.dp).size(48.dp), shape = CircleShape,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = .55f), contentColor = Color.White)) {
                Icon(painterResource(R.drawable.ic_close), "关闭图片", Modifier.size(24.dp))
            }
            Button({
                if (!busy && !pager.isScrollInProgress) {
                    if (Build.VERSION.SDK_INT >= 29) saveImage(currentId)
                    else {
                        val targetId = currentId
                        savingId = targetId
                        scope.launch {
                            try {
                                val name = downloads.suggestedName(targetId)
                                pendingDocumentId = targetId
                                savingId = null
                                document.launch(name)
                            } catch (cancelled: CancellationException) { savingId = null; throw cancelled }
                            catch (_: Exception) { savingId = null; pendingDocumentId = null; notice("未能打开保存位置，请重试。") }
                        }
                    }
                }
            }, enabled = !busy && !pager.isScrollInProgress, modifier = Modifier.align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.End))
                .padding(end = 16.dp, bottom = 16.dp).heightIn(min = 48.dp), shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = .65f), contentColor = Color.White,
                    disabledContainerColor = Color.Black.copy(alpha = .65f), disabledContentColor = Color.White.copy(alpha = .7f))) {
                if (busy) { CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (busy) "正在保存…" else "下载到本地", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ArchiveFullPhoto(id: String, model: ArchiveViewModel) {
    var failed by remember(id) { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, id) {
        try { value = model.media.image(id, 2048)?.asImageBitmap(); failed = value == null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val photo = image
        if (photo != null) Image(photo, "档案图片", Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentScale = ContentScale.Fit)
        else if (failed) Text("图片暂时无法显示", color = Color.White)
        else CircularProgressIndicator(Modifier.size(32.dp), color = Color.White)
    }
}
