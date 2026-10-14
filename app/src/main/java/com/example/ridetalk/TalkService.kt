package com.example.ridetalk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent

class TalkService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var taps = 0

    private val resolveTaps = Runnable {
        val n = taps.coerceIn(1, 3)
        taps = 0
        gesture(n - 1)
    }
    private val keepAlive = object : Runnable {
        override fun run() { claimButtons(); h.postDelayed(this, 20000) }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ch = "ridetalk"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "RideTalk", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch)
            .setContentTitle("RideTalk فعال است")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)

        Settings.load(applicationContext)
        Engine.load(applicationContext)
        if (session == null) setupSession()
        Engine.start(applicationContext)
        h.removeCallbacks(keepAlive)
        h.postDelayed(keepAlive, 20000)
        return START_NOT_STICKY
    }

    // ---------- دکمه‌های لمسی هدفون ----------
    private fun setupSession() {
        val s = MediaSession(this, "RideTalk")
        s.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(i: Intent): Boolean {
                @Suppress("DEPRECATION")
                val ev: KeyEvent? = i.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                if (ev != null && ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) onKey(ev.keyCode)
                return true
            }
        })
        session = s
        claimButtons()
    }

    /** جلسه‌ی ما رو دوباره «فعال‌ترین» پخش‌کننده می‌کنه تا دکمه‌های هدفون به ما برسه. */
    private fun claimButtons() {
        val s = session ?: return
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
        s.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(PlaybackState.STATE_PAUSED, 0L, 1f).build())
        s.isActive = true
        s.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(PlaybackState.STATE_PLAYING, 0L, 1f).build())
    }

    private fun onKey(code: Int) {
        Controls.lastEvent = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                taps++
                h.removeCallbacks(resolveTaps)
                h.postDelayed(resolveTaps, 450)
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> gesture(1)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> gesture(2)
            else -> gesture(3)
        }
    }

    private fun gesture(i: Int) {
        Controls.lastAction = Settings.GESTURES[i] + " ← " + Settings.ACTIONS[Settings.taps[i]]
        perform(Settings.taps[i])
    }

    private fun perform(a: Int) {
        val am = getSystemService(AudioManager::class.java)
        when (a) {
            1 -> forward(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            2 -> forward(KeyEvent.KEYCODE_MEDIA_NEXT)
            3 -> forward(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            4 -> { Engine.talkEnabled = !Engine.talkEnabled; Engine.beep(Engine.talkEnabled) }
            5 -> {
                Settings.fullStop = !Settings.fullStop
                Settings.save(applicationContext)
                Engine.beep(!Settings.fullStop)
            }
            6 -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            7 -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
        }
    }

    /** دستور رو به پخش‌کننده‌ی موسیقی (مثلاً Spotify) می‌فرسته. */
    private fun forward(code: Int) {
        val s = session ?: return
        s.isActive = false
        val am = getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        h.postDelayed({ claimButtons() }, 700)
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        session?.release(); session = null
        Engine.stop()
        super.onDestroy()
    }
}

object Controls {
    @Volatile var lastEvent = "—"
    @Volatile var lastAction = "—"
}
