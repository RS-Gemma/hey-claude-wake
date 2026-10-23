package com.pistolwhip.wakephraseservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.openwakeword.OpenWakeWord

class WakeWordService : Service() {
    private var detector: OpenWakeWord? = null
    private var lastActionAt = 0L
    private var started = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) return START_STICKY

        val persistentNotification = buildListeningNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                LISTENING_NOTIFICATION_ID,
                persistentNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(LISTENING_NOTIFICATION_ID, persistentNotification)
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LISTENING, true)
            .apply()

        try {
            detector = OpenWakeWord.Builder(this)
                .setModel(OpenWakeWord.BuiltInModel.HEY_JARVIS)
                .setThreshold(0.5f)
                .build()

            detector?.start {
                val now = SystemClock.elapsedRealtime()
                if (now - lastActionAt >= ACTION_COOLDOWN_MS) {
                    lastActionAt = now
                    showDetectedNotification()
                }
            }
            started = true
        } catch (error: Exception) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LISTENING, false)
                .apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        detector?.let { wakeWordDetector ->
            try {
                wakeWordDetector.stop()
            } catch (_: Exception) {
                // Stop may be called after a partial startup failure.
            }
            try {
                wakeWordDetector.release()
            } catch (_: Exception) {
                // Release best-effort; the service is already shutting down.
            }
        }
        detector = null
        started = false
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LISTENING, false)
            .apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LISTENING,
                "Wake phrase listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible status while the microphone wake-phrase listener is active."
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DETECTED,
                "Wake phrase detected",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Action notification shown after the wake phrase is detected."
            }
        )
    }

    private fun buildListeningNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java),
            pendingIntentFlags()
        )
        val stopService = PendingIntent.getBroadcast(
            this,
            REQUEST_STOP,
            Intent(this, StopListeningReceiver::class.java),
            pendingIntentFlags()
        )
        return notificationBuilder(CHANNEL_LISTENING)
            .setContentTitle("Wake Phrase Service is listening")
            .setContentText("Say “Hey Jarvis” to receive the ChatGPT action.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopService)
            .build()
    }

    private fun showDetectedNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        val launchIntent = packageManager.getLaunchIntentForPackage(CHATGPT_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW).setData(android.net.Uri.parse(CHATGPT_WEB_URL))
        val openChatGPT = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_CHATGPT,
            launchIntent,
            pendingIntentFlags()
        )
        val notification = notificationBuilder(CHANNEL_DETECTED)
            .setContentTitle("Wake phrase detected")
            .setContentText("Tap to open ChatGPT.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setAutoCancel(true)
            .setContentIntent(openChatGPT)
            .build()
        manager.notify(DETECTED_NOTIFICATION_ID, notification)
    }

    private fun notificationBuilder(channelId: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

    private fun pendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    companion object {
        const val PREFS_NAME = "wake_phrase_service"
        const val KEY_LISTENING = "is_listening"

        private const val CHANNEL_LISTENING = "wake_listener"
        private const val CHANNEL_DETECTED = "wake_detected"
        private const val LISTENING_NOTIFICATION_ID = 1001
        private const val DETECTED_NOTIFICATION_ID = 1002
        private const val REQUEST_OPEN_APP = 2001
        private const val REQUEST_OPEN_CHATGPT = 2002
        private const val REQUEST_STOP = 2003
        private const val ACTION_COOLDOWN_MS = 8000L
        private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
        private const val CHATGPT_WEB_URL = "https://chatgpt.com/"
    }
}
