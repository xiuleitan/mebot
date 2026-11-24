package com.txl.mebot.ui.settings

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.txl.mebot.R
import com.txl.mebot.data.reminders.CalendarRinging
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NotificationSettingsScreen(onBack: () -> Unit, onOpenSchedule: (LocalDate) -> Unit, model: NotificationSettingsViewModel?) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val colors = settingsColors()
    val state = model?.state?.collectAsStateWithLifecycle()?.value ?: NotificationSettingsState()
    var requestedNotifications by rememberSaveable { mutableStateOf(false) }
    val requestNotification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model?.refreshPermissions() }
    val calendarPermissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    var requestedCalendar by rememberSaveable { mutableStateOf(false) }
    val requestCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model?.refreshPermissions()
    }
    fun openSystemPage(intent: Intent, error: String = "无法打开系统设置，请在应用信息中开启对应权限。") {
        try { context.startActivity(intent) }
        catch (_: Exception) { model?.message(error, true) }
    }

    DisposableEffect(model, lifecycle) {
        model?.setVisible(true)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model?.refreshPermissions()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model?.setVisible(false) }
    }

    Box(modifier = Modifier.fillMaxSize()
        .background(Brush.verticalGradient(listOf(colors.backgroundTop, colors.backgroundBottom)))
        .windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, end = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = colors.ink)) {
                    Icon(painterResource(R.drawable.ic_arrow_back), "返回设置", modifier = Modifier.size(24.dp))
                }
                Text("通知提醒", modifier = Modifier.padding(start = 8.dp), color = colors.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item("intro") {
                    Text("日程到点，及时提醒", modifier = Modifier.semantics { heading() },
                        color = colors.ink, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                }
                item("calendar") {
                    NotificationSection {
                        Text("系统日历", color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("系统日历负责日程的准点和提前提醒，支持的设备会自动开启日历响铃。",
                            color = colors.secondary, fontSize = 13.sp, lineHeight = 21.sp)
                        val status = when {
                            !state.loaded -> "正在检查日历权限…"
                            !state.calendarAccess -> "允许访问日历后，未到时间的日程提醒会自动登记。"
                            state.engine.calendarRegistrationFailed -> "日历提醒暂未登记，请重试。"
                            !state.engine.calendarSynced -> "正在登记日历提醒…"
                            state.engine.nextCalendarTriggerAt != null -> "下一次日历提醒：" + Instant.ofEpochMilli(state.engine.nextCalendarTriggerAt)
                                .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
                            else -> "已使用系统日历，暂无待提醒的日程。"
                        }
                        Text(status, color = colors.secondary, fontSize = 13.sp, lineHeight = 21.sp)
                        if (state.calendarAccess) {
                            val ringingStatus = when (state.engine.calendarRinging) {
                                CalendarRinging.ENABLED -> "已确认日历响铃开关开启。"
                                CalendarRinging.UNAVAILABLE -> "此设备的日历接口未提供响铃开关，将使用普通日历提醒。"
                                CalendarRinging.UNCONFIRMED -> "普通日历提醒已登记，暂未确认响铃开关开启。"
                                null -> null
                            }
                            ringingStatus?.let { Text(it, color = colors.secondary, fontSize = 13.sp, lineHeight = 21.sp) }
                        }
                        if (state.loaded && !state.calendarAccess) {
                            OutlinedButton(onClick = {
                                if (!requestedCalendar) {
                                    requestedCalendar = true
                                    requestCalendar.launch(calendarPermissions)
                                } else {
                                    openSystemPage(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)))
                                }
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text("开启日历权限")
                            }
                        }
                        if (state.calendarAccess && (state.engine.calendarRegistrationFailed || state.engine.calendarRinging == CalendarRinging.UNCONFIRMED)) {
                            TextButton(onClick = { model?.retryRegistration() }) { Text("重新登记日历提醒") }
                        }
                    }
                }
                item("test") {
                    NotificationSection {
                        Text("验证日历响铃", color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        val canTest = state.loaded && !state.busy && state.calendarAccess
                        Text("在系统日历中打开测试日程，检查响铃开关并等到点确认。",
                            color = colors.secondary, fontSize = 13.sp, lineHeight = 21.sp)
                        Button(onClick = { model?.createTest() }, enabled = canTest,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("创建约两分钟后测试日程") }
                        state.test?.let { created ->
                            Text("${created.title} · ${created.date.monthValue}月${created.date.dayOfMonth}日 ${created.time}", color = colors.ink, fontSize = 14.sp)
                            OutlinedButton(onClick = { onOpenSchedule(created.date) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("去日程查看") }
                        }
                        state.test?.calendarRegistration?.let { registered ->
                            OutlinedButton(onClick = {
                                openSystemPage(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, registered.eventId)),
                                    "请在系统日历中按上方标题和时间找到测试日程。")
                            }, enabled = canTest, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("打开系统日历中的测试日程") }
                        }
                    }
                }
                if (state.busy) item("busy") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("处理中…", color = colors.secondary, fontSize = 14.sp)
                    }
                }
                state.message?.let { message -> item("message") {
                    Text(message, color = if (state.error) MaterialTheme.colorScheme.error else colors.ink, fontSize = 14.sp, lineHeight = 22.sp)
                } }
                if (state.loaded) item("notifications") {
                    NotificationSection {
                        Text("交易通知", color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("价格提醒和方程式新闻使用 mebot 的通知权限。",
                            color = colors.secondary, fontSize = 13.sp, lineHeight = 21.sp)
                        NotificationPermissionRow("mebot 通知权限", state.notificationsAllowed) {
                            if (Build.VERSION.SDK_INT >= 33 && !requestedNotifications &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                requestedNotifications = true
                                requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                openSystemPage(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                            }
                        }
                    }
                }
                item("home-reminders") {
                    NotificationSection {
                        Text("首页提醒", color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("经期和订阅扣费提醒在首页查看。", color = colors.secondary, fontSize = 13.sp, lineHeight = 21.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationSection(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = settingsColors().surface, contentColor = settingsColors().ink) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun NotificationPermissionRow(title: String, allowed: Boolean, onEnable: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(if (allowed) "已允许" else "未开启", color = settingsColors().secondary, fontSize = 13.sp)
        }
        if (!allowed) OutlinedButton(onClick = onEnable, modifier = Modifier.heightIn(min = 48.dp)) { Text("去开启") }
    }
}
