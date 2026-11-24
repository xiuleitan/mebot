package com.txl.mebot.ui.dayima

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.txl.mebot.R
import com.txl.mebot.data.dayima.DayimaCyclePhase
import com.txl.mebot.data.dayima.DayimaPhaseEstimate
import com.txl.mebot.ui.common.botSheetScrim
import com.txl.mebot.ui.schedule.scheduleColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayimaPhaseDetailsSheet(phase: DayimaCyclePhase?, estimate: DayimaPhaseEstimate, onDismiss: () -> Unit) {
    val colors = scheduleColors()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scroll = rememberScrollState()
    var mounted by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(DayimaCyclePhase.MENSTRUAL) }
    SideEffect { if (phase != null) last = phase }
    LaunchedEffect(phase) {
        if (phase != null) {
            scroll.scrollTo(0); mounted = true
            if (sheet.hasExpandedState) sheet.show()
        } else if (mounted) { sheet.hide(); mounted = false }
    }
    if (!mounted) return
    val shown = phase ?: last
    val palette = shown.colors()
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.85f }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, sheetMaxWidth = 688.dp,
        scrimColor = botSheetScrim(), sheetGesturesEnabled = false, dragHandle = null,
        containerColor = colors.surface, contentColor = colors.ink, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max = height).verticalScroll(scroll)
            .padding(top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(shown.label()), Modifier.weight(1f), color = palette.foreground,
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.dayima_close), Modifier.size(22.dp))
                    }
                }
                Text(stringResource(shown.features()), style = MaterialTheme.typography.bodyLarge)
                estimate.cycleDay?.let { Text(stringResource(R.string.dayima_phase_cycle_day, it),
                    style = MaterialTheme.typography.labelMedium, color = colors.secondary) }
            }
            key(shown) { DayimaPhaseIllustration(shown) }
        }
    }
}

@Composable
private fun DayimaPhaseIllustration(phase: DayimaCyclePhase) {
    val resource = when (phase) {
        DayimaCyclePhase.MENSTRUAL -> R.drawable.dayima_phase_menstrual
        DayimaCyclePhase.FOLLICULAR -> R.drawable.dayima_phase_follicular
        DayimaCyclePhase.OVULATION -> R.drawable.dayima_phase_ovulation
        DayimaCyclePhase.LUTEAL -> R.drawable.dayima_phase_luteal
    }
    val resources = LocalResources.current
    val illustration by produceState<ImageBitmap?>(null, resource, resources) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeResource(resources, resource)?.asImageBitmap() }
    }
    // 四张原图均为 1122 × 1402；解码前也保留比例，避免弹层高度跳动。
    Box(Modifier.fillMaxWidth().aspectRatio(1122f / 1402f)) {
        illustration?.let { bitmap ->
            Image(bitmap, stringResource(R.string.dayima_phase_illustration, stringResource(phase.label())),
                Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}
