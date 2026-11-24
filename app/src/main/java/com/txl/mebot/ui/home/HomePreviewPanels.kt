package com.txl.mebot.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.R
import com.txl.mebot.ui.common.botSheetScrim
import com.txl.mebot.ui.schedule.scheduleColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomePreviewDetail(entry: HomePreviewEntry, date: LocalDate, onDismiss: () -> Unit) {
    val colors = scheduleColors()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val dismiss by rememberUpdatedState(onDismiss)
    val close = {
        if (!closing) {
            closing = true
            scope.launch {
                try { sheet.hide(); if (!sheet.isVisible) dismiss() }
                finally { if (sheet.isVisible) closing = false }
            }
        }
    }
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .85f }
    ModalBottomSheet(onDismissRequest = close, sheetState = sheet, sheetGesturesEnabled = false,
        dragHandle = null, sheetMaxWidth = 688.dp, scrimColor = botSheetScrim(),
        containerColor = colors.surface, contentColor = colors.ink,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max = height).testTag("home_preview_detail")
            .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(entry.title), Modifier.weight(1f), fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold)
                IconButton(close, enabled = !closing, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close_panel), Modifier.size(22.dp))
                }
            }
            Text(stringResource(entry.source) + " · " + date.format(DateTimeFormatter.ofPattern("yyyy年M月d日")),
                fontSize = 12.sp, color = colors.secondary)
            HorizontalDivider(color = colors.divider)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(painterResource(entry.cover), null, Modifier.fillMaxWidth().aspectRatio(1.5f), contentScale = ContentScale.Crop)
                Text(stringResource(R.string.home_preview_label), fontSize = 12.sp, color = colors.secondary)
                Text(stringResource(R.string.home_preview_body), fontSize = 15.sp, lineHeight = 23.sp)
                Text(stringResource(if (entry.completed) R.string.home_preview_completed else R.string.home_preview_pending),
                    fontSize = 12.sp, color = colors.secondary)
            }
        }
    }
}
