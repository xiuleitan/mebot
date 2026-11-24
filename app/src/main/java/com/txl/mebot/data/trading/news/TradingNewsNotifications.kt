package com.txl.mebot.data.trading.news

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.txl.mebot.MainActivity
import com.txl.mebot.R

internal object TradingNewsNotifications {
    internal const val Channel = "trading_news_chime"
    private const val LegacyChannel = "trading_news"
    private val ChimeAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun post(context: Context, story: TradingNewsStory) {
        if (story.channel != TradingNewsChannel.MAIN ||
            !NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val label = context.getString(R.string.trading_news_main)
        val channel = getChannel(context, manager, label) ?: return
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return
        val open = PendingIntent.getActivity(
            context, 61304,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = story.displayTitle
        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_trading)
            .setContentTitle(label)
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setWhen(story.publishedAt)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            manager.notify("news:${story.id}", 61304, notification)
        } catch (_: SecurityException) {
            // Permission can be changed while the app is receiving a message.
        }
    }

    private fun getChannel(context: Context, manager: NotificationManager, label: String): NotificationChannel? {
        val legacy = manager.getNotificationChannel(LegacyChannel)
        var channel = manager.getNotificationChannel(Channel)
        if (channel == null) {
            if (legacy?.importance == NotificationManager.IMPORTANCE_NONE) return legacy
            // Android keeps the previous channel's default sound across app updates.
            val chime = Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(context.packageName).appendPath("raw")
                .appendPath(context.resources.getResourceEntryName(R.raw.trading_news_chime)).build()
            val hasChosenSound = legacy != null &&
                (legacy.sound != null || (Build.VERSION.SDK_INT >= 30 && legacy.hasUserSetSound()))
            val newChannel = NotificationChannel(Channel, label, legacy?.importance ?: NotificationManager.IMPORTANCE_DEFAULT)
            newChannel.setSound(
                if (hasChosenSound) legacy?.sound else chime,
                if (hasChosenSound) legacy?.audioAttributes ?: ChimeAttributes else ChimeAttributes,
            )
            newChannel.lockscreenVisibility = legacy?.lockscreenVisibility ?: Notification.VISIBILITY_PRIVATE
            legacy?.let {
                newChannel.setShowBadge(it.canShowBadge())
                newChannel.vibrationPattern = it.vibrationPattern
                newChannel.enableVibration(it.shouldVibrate())
                newChannel.enableLights(it.shouldShowLights())
                newChannel.lightColor = it.lightColor
                newChannel.setBypassDnd(it.canBypassDnd())
            }
            manager.createNotificationChannel(newChannel)
            channel = manager.getNotificationChannel(Channel) ?: return null
        }
        if (legacy != null && manager.activeNotifications.none { it.notification.channelId == LegacyChannel }) {
            manager.deleteNotificationChannel(LegacyChannel)
        }
        return channel
    }
}
