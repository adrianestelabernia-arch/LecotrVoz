package com.lectorvoz.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ReaderService : Service() {

    companion object {
        const val ACTION_PLAY = "com.lectorvoz.app.PLAY"
        const val ACTION_TOGGLE = "com.lectorvoz.app.TOGGLE"
        const val ACTION_NEXT = "com.lectorvoz.app.NEXT"
        const val ACTION_PREV = "com.lectorvoz.app.PREV"
        const val ACTION_STOP = "com.lectorvoz.app.STOP"
        private const val CHANNEL = "reader"
        private const val NOTIF_ID = 42

        fun send(ctx: Context, action: String) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ReaderService::class.java).setAction(action))
        }
    }

    private data class Key(val title: String, val section: String, val playing: Boolean, val pct: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSessionCompat

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { ReaderEngine.pause() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lectura en voz alta", NotificationManager.IMPORTANCE_LOW))
        session = MediaSessionCompat(this, "LectorVoz").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { ReaderEngine.play() }
                override fun onPause() { ReaderEngine.pause() }
                override fun onSkipToNext() { ReaderEngine.skip(1) }
                override fun onSkipToPrevious() { ReaderEngine.skip(-1) }
                override fun onStop() { stopEverything() }
            })
            isActive = true
        }
        ContextCompat.registerReceiver(
            this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        scope.launch {
            ReaderEngine.state
                .map { Key(it.title, it.sectionTitles.getOrNull(it.section) ?: "", it.playing, (it.percent * 100).toInt()) }
                .distinctUntilChanged()
                .collect { refresh(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )
        when (intent?.action) {
            ACTION_PLAY -> ReaderEngine.play()
            ACTION_TOGGLE -> ReaderEngine.toggle()
            ACTION_NEXT -> ReaderEngine.skip(1)
            ACTION_PREV -> ReaderEngine.skip(-1)
            ACTION_STOP -> stopEverything()
        }
        return START_NOT_STICKY
    }

    private fun stopEverything() {
        ReaderEngine.pause()
        ReaderEngine.saveNow(true)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun refresh(k: Key) {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, k.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, k.section)
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (k.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (k.playing) 1f else 0f
                )
                .build()
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())
    }

    private fun action(code: Int, act: String): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, ReaderService::class.java).setAction(act),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(): Notification {
        val st = ReaderEngine.state.value
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val sec = st.sectionTitles.getOrNull(st.section) ?: ""
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(st.title.ifEmpty { "Lector Voz" })
            .setContentText("$sec · ${(st.percent * 100).toInt()} %")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(st.playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_media_previous, "Anterior", action(1, ACTION_PREV))
            .addAction(
                if (st.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (st.playing) "Pausa" else "Reproducir", action(2, ACTION_TOGGLE)
            )
            .addAction(android.R.drawable.ic_media_next, "Siguiente", action(3, ACTION_NEXT))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", action(4, ACTION_STOP))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        ReaderEngine.saveNow(true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        ReaderEngine.pause()
        ReaderEngine.saveNow(true)
        try { unregisterReceiver(noisy) } catch (e: Exception) { }
        session.release()
        scope.cancel()
        super.onDestroy()
    }
}
