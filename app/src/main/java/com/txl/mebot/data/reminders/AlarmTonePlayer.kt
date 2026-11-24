package com.txl.mebot.data.reminders

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

/** 解码、文件读取、播放与释放均在专用线程；试听和真正响铃共享相同闹钟音量语义。 */
class AlarmTonePlayer(context: Context) {
    private val context = context.applicationContext
    private val thread = HandlerThread("mebot-alarm-audio").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var timeout: Runnable? = null
    private var duration: Long? = null
    val isClosed: Boolean get() = closed.get()

    fun play(sources: List<Uri>, durationMillis: Long?, onStarted: () -> Unit = {}, onFinished: (Boolean) -> Unit) {
        handler.post {
            if (closed.get()) return@post
            duration = durationMillis
            var index = 0
            var started = false
            fun finish() {
                if (closed.get()) return
                val played = started
                stop()
                main.post { onFinished(played) }
            }
            fun prepareNext() {
                if (closed.get()) return
                timeout?.let(handler::removeCallbacks)
                timeout = null
                player?.release(); player = null
                if (index >= sources.size) { finish(); return }
                val source = sources[index++]
                val next = MediaPlayer()
                player = next
                try {
                    next.setAudioAttributes(attributes)
                    next.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                    next.isLooping = true
                    next.setOnPreparedListener { prepared ->
                        if (closed.get() || player !== prepared) return@setOnPreparedListener
                        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(attributes)
                            .setOnAudioFocusChangeListener({ change -> if (change < 0) finish() }, handler).build()
                        focus = request
                        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                            finish(); return@setOnPreparedListener
                        }
                        try {
                            prepared.start()
                            started = true
                            main.post(onStarted)
                            timeout = Runnable { finish() }
                            updateTimeout()
                        } catch (_: Exception) { prepareNext() }
                    }
                    next.setOnErrorListener { failed, _, _ ->
                        if (!closed.get() && player === failed) prepareNext()
                        true
                    }
                    next.setDataSource(context, source)
                    next.prepareAsync()
                } catch (_: Exception) { prepareNext() }
            }
            prepareNext()
            handler.postDelayed({ if (!started) finish() }, 15_000L)
        }
    }

    fun setDuration(durationMillis: Long?) {
        handler.post {
            if (closed.get()) return@post
            duration = durationMillis
            updateTimeout()
        }
    }

    private fun updateTimeout() {
        val stop = timeout ?: return
        handler.removeCallbacks(stop)
        duration?.let { handler.postDelayed(stop, it) }
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        handler.post {
            handler.removeCallbacksAndMessages(null)
            player?.release(); player = null
            focus?.let { manager.abandonAudioFocusRequest(it) }; focus = null
            thread.quitSafely()
        }
    }
}
