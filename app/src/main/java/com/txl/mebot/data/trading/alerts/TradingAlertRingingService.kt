package com.txl.mebot.data.trading.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import com.txl.mebot.MainActivity
import com.txl.mebot.MebotApplication
import com.txl.mebot.R
import com.txl.mebot.data.reminders.AlarmTonePlayer

class TradingAlertRingingService : Service() {
    private val visibility get() = (application as MebotApplication).tradingAlertVisibility
    private var player: AlarmTonePlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringingNotice: TradingAlertNotice? = null
    private var untilAppOpened = false
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        visibility.onAppOpened = { if (untilAppOpened) finish() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == Stop) {
            finish()
            return START_NOT_STICKY
        }
        val notice = intent?.notice()
        if (intent == null || notice == null || !TradingAlertNotifications.canNotify(this)) {
            finish()
            return START_NOT_STICKY
        }
        val received = TradingAlertAppState(intent.getLongExtra(AppResumes, 0), intent.getBooleanExtra(AppVisible, false))
        val acknowledged = received.acknowledgedBy(visibility.snapshot())
        if (acknowledged && ringingNotice != null) {
            TradingAlertNotifications.notice(this, notice)
            return START_NOT_STICKY
        }
        val stop = PendingIntent.getService(this, 61301, Intent(this, javaClass).setAction(Stop),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            ServiceCompat.startForeground(this, NotificationId,
                TradingAlertNotifications.ringing(this, notice, stop),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        } catch (_: RuntimeException) {
            TradingAlertNotifications.notice(this, notice)
            finish()
            return START_NOT_STICKY
        }
        ringingNotice?.takeIf { it.tag != notice.tag }?.let { TradingAlertNotifications.notice(this, it) }
        ringingNotice = notice
        val wasRingingUntilOpened = untilAppOpened
        untilAppOpened = !received.visible
        if (acknowledged) {
            finish()
            return START_NOT_STICKY
        }

        val playing = player?.takeUnless { it.isClosed }
        if (playing != null) {
            if (wasRingingUntilOpened != untilAppOpened) playing.setDuration(received.durationMillis)
        } else {
            releasePlayback()
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mebot:trading-alert")
                .apply { setReferenceCounted(false); acquire(20_000L) }
            val alarm = AlarmTonePlayer(this)
            player = alarm
            alarm.play(
                listOf(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    "android.resource://$packageName/${R.raw.schedule_alarm_fallback}".toUri(),
                ).filterNotNull(),
                durationMillis = received.durationMillis,
            ) {
                if (player === alarm) finish()
            }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        val finished = ringingNotice
        ringingNotice = null
        untilAppOpened = false
        releasePlayback()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        finished?.let { TradingAlertNotifications.notice(this, it) }
        stopSelf(latestStartId)
    }

    private fun releasePlayback() {
        player?.stop()
        player = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        visibility.onAppOpened = null
        releasePlayback()
        super.onDestroy()
    }

    internal companion object {
        private const val NotificationId = 61301
        private const val Stop = "com.txl.mebot.STOP_TRADING_ALERT"
        private const val NoticeTag = "tag"
        private const val NoticeTitle = "title"
        private const val AppResumes = "app_resumes"
        private const val AppVisible = "app_visible"

        fun intent(context: Context, notice: TradingAlertNotice, received: TradingAlertAppState) =
            Intent(context, TradingAlertRingingService::class.java)
                .putExtra(NoticeTag, notice.tag)
                .putExtra(NoticeTitle, notice.title)
                .putExtra(AppResumes, received.resumes)
                .putExtra(AppVisible, received.visible)

        private fun Intent.notice(): TradingAlertNotice? {
            val tag = getStringExtra(NoticeTag) ?: return null
            val title = getStringExtra(NoticeTitle) ?: return null
            return TradingAlertNotice(tag, title)
        }
    }
}

internal object TradingAlertNotifications {
    private const val Channel = "trading_price_alerts"

    fun canNotify(context: Context): Boolean {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(NotificationChannel(Channel, "交易价格提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            system.getNotificationChannel(Channel)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun ringing(context: Context, notice: TradingAlertNotice, stop: PendingIntent): Notification =
        builder(context, notice)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true).addAction(R.drawable.ic_close, "停止响铃", stop).build()

    fun notice(context: Context, notice: TradingAlertNotice) {
        if (!canNotify(context)) return
        val notification = builder(context, notice).setAutoCancel(true).build()
        context.getSystemService(NotificationManager::class.java).notify(notice.tag, 61302, notification)
    }

    private fun builder(context: Context, notice: TradingAlertNotice): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(context, 61303,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context, Channel)
            .setSmallIcon(R.drawable.ic_alarm).setContentTitle(notice.title)
            .setCategory(NotificationCompat.CATEGORY_ALARM).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open).setOnlyAlertOnce(true).setSilent(true)
    }
}
