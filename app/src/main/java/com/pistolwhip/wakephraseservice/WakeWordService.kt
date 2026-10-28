package com.pistolwhip.wakephraseservice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

class WakeWordService : Service() {
    private var detector: FixedOpenWakeWord? = null
    private var lastActionAt = 0L
    private var listeningSince = 0L
    private var started = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Handoff state: paused while the assistant has the microphone, then resumed automatically.
    private var paused = false
    private var pausedAt = 0L
    private var sawAssistantRecording = false
    private var quietSince = 0L
    private var unlockReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListeningByUser(startId)
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        if (paused) {
            resumeListening()
            return START_STICKY
        }

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

        // Let Android recreate the foreground service if the process is reclaimed.
        // Explicit Stop and startup failures still stop the service normally.
        return if (startDetector()) START_STICKY else START_NOT_STICKY
    }

    /** Starts the microphone and wake-word detector; on failure stops the service. */
    private fun startDetector(): Boolean {
        setDiagnosticStatus("Starting microphone and wake-word detector", listening = true)

        try {
            val micResult = checkMicrophone()
            if (micResult != null) {
                Log.e(TAG, micResult)
                setDiagnosticStatus(micResult, listening = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return false
            }

            setDiagnosticStatus("Preflight passed; detector thread is loading models and initializing audio.", listening = true)
            val personal = PersonalMatcher(PersonalPhrases.load(this))
            detector = FixedOpenWakeWord.Builder(this)
                .setModel(FixedOpenWakeWord.BuiltInModel.HEY_JARVIS)
                // Slightly more sensitive than the original 0.5 threshold.
                .setThreshold(DETECTION_THRESHOLD)
                .setStatusListener { status, healthy ->
                    mainHandler.post {
                        setDiagnosticStatus(status, listening = healthy)
                        if (!healthy) {
                            started = false
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
                .setEmbeddingListener { embedding, _ ->
                    personal.process(embedding)?.let { (phrase, distance) ->
                        mainHandler.post { onWakePhrase(phrase.label, "match distance %.3f".format(distance)) }
                    }
                }
                .build()

            started = true
            listeningSince = SystemClock.elapsedRealtime()
            detector?.start { score ->
                if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_JARVIS_ENABLED, true)) {
                    onWakePhrase("Hey Jarvis", "confidence %.3f".format(score))
                }
            }
            Log.i(TAG, "Wake-word detector worker launched; threshold=$DETECTION_THRESHOLD")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start wake-word service", error)
            setDiagnosticStatus(
                "Startup failed: ${error.javaClass.simpleName}: ${error.message ?: "no details"}",
                listening = false
            )
            started = false
            detector?.let { runCatching { it.stop() }; runCatching { it.release() } }
            detector = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return false
        }
        return true
    }

    private fun stopListeningByUser(startId: Int) {
        Log.i(TAG, "Explicit stop requested from the app")
        detector?.let { wakeWordDetector ->
            try {
                wakeWordDetector.stop()
            } catch (error: Exception) {
                Log.w(TAG, "Detector stop failed after explicit stop request", error)
            }
            try {
                wakeWordDetector.release()
            } catch (error: Exception) {
                Log.w(TAG, "Detector release failed after explicit stop request", error)
            }
        }
        detector = null
        started = false
        endPause()
        setDiagnosticStatus("Stopped by user.", listening = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    /**
     * The library starts audio capture on its own worker thread and does not expose
     * initialization errors to the caller. Do a short independent capture first so
     * common permission/device microphone failures are visible in the app UI.
     * A null result means the preflight succeeded.
     */
    private fun checkMicrophone(): String? {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
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

    /** Called on the main thread when any wake phrase is heard. */
    private fun onWakePhrase(phrase: String, detail: String) {
        if (!started) return
        val now = SystemClock.elapsedRealtime()
        // The detector emits unreliable output while its audio window first fills.
        if (now - listeningSince < WARMUP_MS) {
            Log.i(TAG, "Ignoring $phrase during warm-up; $detail")
            return
        }
        Log.i(TAG, "$phrase detected; $detail")
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_DETECTION_AT, System.currentTimeMillis())
            .putString(KEY_LAST_PHRASE, "$phrase ($detail)")
            .putString(KEY_DIAGNOSTIC_STATUS, "Wake phrase detected: $phrase ($detail)")
            .apply()

        if (now - lastActionAt >= ACTION_COOLDOWN_MS) {
            lastActionAt = now
            handoffToAssistant()
        }
    }

    /**
     * Release the wake-word microphone before handing control to the assistant, which needs
     * the microphone for voice mode. The service stays in the foreground, paused, and resumes
     * listening once the assistant stops recording. The detection notification is shown only
     * when the launch fails.
     */
    private fun handoffToAssistant() {
        val currentDetector = detector
        detector = null
        try {
            currentDetector?.stop()
        } catch (error: Exception) {
            Log.w(TAG, "Detector stop before assistant handoff failed", error)
        }
        try {
            currentDetector?.release()
        } catch (error: Exception) {
            Log.w(TAG, "Detector release before assistant handoff failed", error)
        }
        started = false

        val method = AssistantLauncher.selectedMethod(this)
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        // Shizuku methods run as the shell and need no overlay permission; intent methods
        // need it to start an activity from the background.
        val launched = if (method.shellCommand != null || Settings.canDrawOverlays(this)) {
            AssistantLauncher.launch(this, method)
        } else {
            Log.i(TAG, "Overlay permission missing; waiting for notification tap instead of direct launch")
            false
        }
        if (!launched) {
            showDetectedNotification()
            setDiagnosticStatus(
                "System assistant did not launch directly. Tap the detection notification; check Shizuku is running, or that Display over other apps is enabled.",
                listening = false
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        paused = true
        pausedAt = SystemClock.elapsedRealtime()
        sawAssistantRecording = false
        quietSince = 0L
        // The assistant refuses to talk on the lock screen; open it again once the user unlocks.
        if (locked) waitForUnlock(method)
        updateListeningNotification("Paused while the assistant is listening")
        setDiagnosticStatus(
            if (locked) "Assistant requested on lock screen; will reopen it after unlock."
            else "Assistant opened; listening resumes when the conversation ends.",
            listening = true
        )
        mainHandler.postDelayed(resumeMonitor, MONITOR_INTERVAL_MS)
    }

    /** Polls the microphone; resumes listening once the assistant has stopped recording. */
    private val resumeMonitor = object : Runnable {
        override fun run() {
            if (!paused) return
            val now = SystemClock.elapsedRealtime()
            val recording = getSystemService(AudioManager::class.java)
                .activeRecordingConfigurations.isNotEmpty()
            if (recording) {
                sawAssistantRecording = true
                quietSince = 0L
            } else if (quietSince == 0L) {
                quietSince = now
            }
            val resume = when {
                // Safety net in case some other app keeps the microphone busy indefinitely.
                now - pausedAt >= MAX_PAUSE_MS -> true
                recording -> false
                sawAssistantRecording -> now - quietSince >= RESUME_QUIET_MS
                unlockReceiver != null -> now - pausedAt >= UNLOCK_WAIT_MS
                else -> now - pausedAt >= NO_SESSION_TIMEOUT_MS
            }
            if (resume) resumeListening() else mainHandler.postDelayed(this, MONITOR_INTERVAL_MS)
        }
    }

    private fun waitForUnlock(method: AssistantLauncher.Method) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                unregisterUnlockReceiver()
                if (paused && SystemClock.elapsedRealtime() - pausedAt < UNLOCK_WAIT_MS) {
                    Log.i(TAG, "Unlocked after wake phrase; reopening assistant")
                    pausedAt = SystemClock.elapsedRealtime()
                    AssistantLauncher.launch(this@WakeWordService, method)
                }
            }
        }
        unlockReceiver = receiver
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun unregisterUnlockReceiver() {
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        unlockReceiver = null
    }

    private fun endPause() {
        paused = false
        mainHandler.removeCallbacks(resumeMonitor)
        unregisterUnlockReceiver()
    }

    private fun resumeListening() {
        Log.i(TAG, "Resuming wake-word listening after assistant handoff")
        endPause()
        updateListeningNotification("Say a wake phrase to open the assistant.")
        startDetector()
    }

    private fun updateListeningNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(LISTENING_NOTIFICATION_ID, buildListeningNotification(text))
    }

    private fun setDiagnosticStatus(status: String, listening: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val changed = prefs.getBoolean(KEY_LISTENING, false) != listening
        prefs.edit()
            .putBoolean(KEY_LISTENING, listening)
            .putString(KEY_DIAGNOSTIC_STATUS, status)
            .apply()
        if (changed) publishListeningState()
    }

    /** Updates the home-screen widget and Quick Settings tile. */
    private fun publishListeningState() {
        ToggleWidgetProvider.refresh(this)
        ListeningTileService.refresh(this)
    }

    override fun onDestroy() {
        endPause()
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
            !priorStatus.startsWith("Microphone check failed:") &&
            !priorStatus.startsWith("Detector failed:") &&
            !priorStatus.startsWith("System assistant")
        ) {
            setDiagnosticStatus("Not listening", listening = false)
        } else {
            prefs.edit().putBoolean(KEY_LISTENING, false).apply()
        }
        publishListeningState()
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

    private fun buildListeningNotification(
        text: String = "Say a wake phrase to open the assistant."
    ): Notification {
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
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopService)
            .build()
    }

    private fun showDetectedNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        val launchIntent = AssistantLauncher.buildIntent(this, AssistantLauncher.selectedMethod(this))
            ?: Intent(this, MainActivity::class.java)
        val openAssistant = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_ASSISTANT,
            launchIntent,
            pendingIntentFlags()
        )
        val notification = notificationBuilder(CHANNEL_DETECTED)
            .setContentTitle("Wake phrase detected")
            .setContentText("If the assistant did not open, tap here. Listening pauses after detection.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setAutoCancel(true)
            .setContentIntent(openAssistant)
            // Lets Android open the assistant over the lock screen when a direct launch is blocked,
            // if the user allows full-screen notifications for this app.
            .setFullScreenIntent(openAssistant, true)
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
        const val ACTION_STOP = "it.aicdp.heyclaude.action.STOP"
        const val PREFS_NAME = "wake_phrase_service"
        const val KEY_LISTENING = "is_listening"
        const val KEY_DIAGNOSTIC_STATUS = "diagnostic_status"
        const val KEY_LAST_SCORE = "last_confidence_score"
        const val KEY_LAST_DETECTION_AT = "last_detection_at"
        const val KEY_LAUNCH_METHOD = "launch_method"
        const val KEY_JARVIS_ENABLED = "jarvis_enabled"
        const val KEY_LAST_PHRASE = "last_phrase"

        private const val TAG = "WakePhraseService"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 1280
        private const val DETECTION_THRESHOLD = 0.35f
        private const val CHANNEL_LISTENING = "wake_listener"
        private const val CHANNEL_DETECTED = "wake_detected"
        private const val LISTENING_NOTIFICATION_ID = 1001
        private const val DETECTED_NOTIFICATION_ID = 1002
        private const val REQUEST_OPEN_APP = 2001
        private const val REQUEST_OPEN_ASSISTANT = 2002
        private const val REQUEST_STOP = 2003
        private const val ACTION_COOLDOWN_MS = 8000L
        private const val WARMUP_MS = 2000L
        private const val MONITOR_INTERVAL_MS = 1000L
        private const val RESUME_QUIET_MS = 3000L
        private const val UNLOCK_WAIT_MS = 30_000L
        private const val NO_SESSION_TIMEOUT_MS = 15_000L
        private const val MAX_PAUSE_MS = 10 * 60_000L
    }
}
