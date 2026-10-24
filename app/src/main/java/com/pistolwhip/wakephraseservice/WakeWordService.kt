package com.pistolwhip.wakephraseservice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
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

        setDiagnosticStatus("Starting microphone and wake-word detector", listening = true)

        try {
            val micResult = checkMicrophone()
            if (micResult != null) {
                Log.e(TAG, micResult)
                setDiagnosticStatus(micResult, listening = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            setDiagnosticStatus("Microphone check passed; loading wake-word detector", listening = true)
            detector = OpenWakeWord.Builder(this)
                .setModel(OpenWakeWord.BuiltInModel.HEY_JARVIS)
                // Slightly more sensitive than the original 0.5 threshold.
                .setThreshold(DETECTION_THRESHOLD)
                .build()

            detector?.start { score ->
                val now = SystemClock.elapsedRealtime()
                Log.i(TAG, "Hey Jarvis detection callback; confidence=$score")
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putFloat(KEY_LAST_SCORE, score)
                    .putLong(KEY_LAST_DETECTION_AT, System.currentTimeMillis())
                    .putString(KEY_DIAGNOSTIC_STATUS, "Wake phrase detected; confidence %.3f".format(score))
                    .apply()

                if (now - lastActionAt >= ACTION_COOLDOWN_MS) {
                    lastActionAt = now
                    showDetectedNotification()
                }
            }
            started = true
            setDiagnosticStatus(
                "Microphone check passed. Detector started; say “Hey Jarvis” clearly near the phone.",
                listening = true
            )
            Log.i(TAG, "Wake-word detector start requested; threshold=$DETECTION_THRESHOLD")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start wake-word service", error)
            setDiagnosticStatus(
                "Startup failed: ${error.javaClass.simpleName}: ${error.message ?: "no details"}",
                listening = false
            )
            detector?.let { runCatching { it.stop() }; runCatching { it.release() } }
            detector = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    /**
     * The library starts audio capture on its own worker thread and does not expose
     * initialization errors to the caller. Do a short independent capture first so
     * common permission/device microphone failures are visible in the app UI.
     * A null result means the preflight succeeded.
     */
    private fun checkMicrophone(): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "Microphone permission is missing. Grant it and start listening again."
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE || minBuffer <= 0) {
            return "Microphone check failed: Android returned an invalid audio buffer size ($minBuffer)."
        }

        var recorder: AudioRecord? = null
        return try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_SAMPLES * 4)
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                "Microphone check failed: AudioRecord did not initialize."
            } else {
                recorder.startRecording()
                val buffer = ShortArray(FRAME_SAMPLES)
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    "Microphone check failed: Android could not read audio (code $read)."
                } else {
                    null
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Microphone preflight failed", error)
            "Microphone check failed: ${error.javaClass.simpleName}: ${error.message ?: "no details"}"
        } finally {
            try { recorder?.stop() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
        }
    }

    private fun setDiagnosticStatus(status: String, listening: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LISTENING, listening)
            .putString(KEY_DIAGNOSTIC_STATUS, status)
            .apply()
    }

    override fun onDestroy() {
        detector?.let { wakeWordDetector ->
            try {
                wakeWordDetector.stop()
            } catch (_: Exception) {
                Log.w(TAG, "Detector stop failed during service shutdown")
            }
            try {
                wakeWordDetector.release()
            } catch (_: Exception) {
                Log.w(TAG, "Detector release failed during service shutdown")
            }
        }
        detector = null
        started = false
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val priorStatus = prefs.getString(KEY_DIAGNOSTIC_STATUS, "") ?: ""
        if (!priorStatus.startsWith("Startup failed:") &&
            !priorStatus.startsWith("Microphone check failed:")
        ) {
            setDiagnosticStatus("Not listening", listening = false)
        } else {
            prefs.edit().putBoolean(KEY_LISTENING, false).apply()
        }
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
        const val KEY_DIAGNOSTIC_STATUS = "diagnostic_status"
        const val KEY_LAST_SCORE = "last_confidence_score"
        const val KEY_LAST_DETECTION_AT = "last_detection_at"

        private const val TAG = "WakePhraseService"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 1280
        private const val DETECTION_THRESHOLD = 0.35f
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
