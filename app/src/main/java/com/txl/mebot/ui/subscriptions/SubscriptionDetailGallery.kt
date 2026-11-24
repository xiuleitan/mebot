package com.txl.mebot.ui.subscriptions

import com.txl.mebot.ui.common.hapticAction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 多图缩略预览露出相邻一页；完整原图继续在全屏画布中查看。 */
@Composable
internal fun SubscriptionDetailGallery(images: List<String>, coverAssetId: String?, model: SubscriptionsViewModel?,
    enabled: Boolean, onOpenImage: (String) -> Unit, modifier: Modifier = Modifier) {
    if (images.isEmpty()) return
    if (images.size == 1) {
        SubscriptionPicture(images.single(), model,
            modifier.fillMaxWidth().height(220.dp).testTag("subscription_detail_gallery")
                .clickable(enabled = enabled) { onOpenImage(images.single()) }, maxPixels = 1024, fit = true)
        return
    }
    val pager = rememberPagerState(initialPage = images.indexOf(coverAssetId).coerceAtLeast(0)) { images.size }
    com.txl.mebot.ui.common.PagerSelectionHaptics(pager) { images.getOrNull(it) }
    LaunchedEffect(coverAssetId) {
        val index = images.indexOf(coverAssetId)
        if (index >= 0 && index != pager.currentPage) pager.animateScrollToPage(index)
    }
    LaunchedEffect(images.size) { if (pager.currentPage > images.lastIndex) pager.scrollToPage(images.lastIndex) }
    BoxWithConstraints(modifier.fillMaxWidth().height(220.dp).testTag("subscription_detail_gallery")) {
        // 12dp 间距后仍露出32dp缩略图；末页会露出上一张，提示可以往回滑。
        HorizontalPager(pager, pageSize = PageSize.Fixed((maxWidth - 44.dp).coerceAtLeast(1.dp)), pageSpacing = 12.dp,
            key = { images[it] }, userScrollEnabled = enabled, modifier = Modifier.fillMaxSize()) { page ->
            val id = images.getOrNull(page) ?: return@HorizontalPager
            SubscriptionPicture(id, model, Modifier.fillMaxSize().testTag("subscription_detail_image_$id")
                .clickable(enabled = enabled, onClick = hapticAction { onOpenImage(id) }), maxPixels = 1024)
        }
        Surface(Modifier.align(Alignment.BottomEnd).padding(10.dp), color = Color.Black.copy(alpha = 0.6f),
            contentColor = Color.White, shape = MaterialTheme.shapes.small) {
            Text("${pager.currentPage.coerceIn(0, images.lastIndex) + 1} / ${images.size}",
                Modifier.padding(horizontal = 10.dp, vertical = 5.dp).testTag("subscription_detail_image_position"), fontSize = 12.sp)
        }
    }
}
