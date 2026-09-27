package com.aditya.readaloud.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aditya.readaloud.MainActivity

class TtsPlaybackService : Service() {
    companion object {
        const val ACTION_PLAY_PAUSE = "com.aditya.readaloud.PLAY_PAUSE"
        const val ACTION_STOP = "com.aditya.readaloud.STOP"
        const val ACTION_NEXT = "com.aditya.readaloud.NEXT"
        const val ACTION_PREVIOUS = "com.aditya.readaloud.PREVIOUS"
        private const val CHANNEL_ID = "reading_playback"
        private const val NOTIFICATION_ID = 1701
    }

    override fun onCreate() {
        super.onCreate()
        TtsController.init(this)
        TtsController.onStateChanged = {
            if (TtsController.state.value.bookId == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                updateNotification()
            }
        }
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> if (TtsController.state.value.playing) TtsController.pause() else TtsController.resume()
            ACTION_STOP -> {
                TtsController.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_NEXT -> TtsController.nextPage()
            ACTION_PREVIOUS -> TtsController.previousPage()
        }
        updateNotification()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        TtsController.onStateChanged = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "पुस्तक वाचन", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "पुस्तक के निरंतर वाचन के नियंत्रण"
                    setSound(null, null)
                }
            )
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val s = TtsController.state.value
        val title = "Aditya Read Aloud"
        val text = when {
            s.bookId == null -> "तैयार"
            s.pageCount > 0 -> "पृष्ठ ${s.page + 1} / ${s.pageCount} • ${s.message}"
            else -> s.message
        }
        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.aditya.readaloud.R.drawable.ic_stat_book)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(s.bookId != null)
            .setOnlyAlertOnce(true)
            .addAction(action(ACTION_PREVIOUS, "पिछला"))
            .addAction(action(ACTION_PLAY_PAUSE, if (s.playing) "रोकें" else "चलाएँ"))
            .addAction(action(ACTION_NEXT, "अगला"))
            .addAction(action(ACTION_STOP, "बंद"))
            .build()
    }

    private fun action(action: String, label: String): NotificationCompat.Action {
        val intent = PendingIntent.getService(
            this, action.hashCode(), Intent(this, TtsPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(com.aditya.readaloud.R.drawable.ic_stat_book, label, intent).build()
    }
}
