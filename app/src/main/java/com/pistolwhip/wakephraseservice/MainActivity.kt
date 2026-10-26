package com.pistolwhip.wakephraseservice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRefresh = object : Runnable {
        override fun run() {
            if (::statusView.isInitialized) refreshStatus()
            statusHandler.postDelayed(this, STATUS_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 32, 28, 24)
        }

        val title = TextView(this).apply {
            text = "Wake Phrase Service"
            textSize = 26f
            gravity = Gravity.CENTER
        }
        root.addView(title, matchWrap())

        val description = TextView(this).apply {
            text = "Stage 3 experimental assistant handoff. The custom “yo, Ai Overlord” detector runs on-device and requests Android’s configured digital assistant overlay. Android may defer assistant operation until unlock. If the assistant does not open, tap the detection notification. Listening pauses after detection; restart it after your conversation."
            textSize = 16f
            setPadding(0, 20, 0, 20)
        }
        root.addView(description, matchWrap())

        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, 8, 0, 24)
        }
        root.addView(statusView, matchWrap())

        val startButton = Button(this).apply {
            text = "Start listening"
            setOnClickListener {
                if (permissionsGranted()) {
                    startListening()
                } else {
                    requestNeededPermissions()
                }
            }
        }
        root.addView(startButton, matchWrap())

        val stopButton = Button(this).apply {
            text = "Stop listening"
            setOnClickListener {
                // Deliver an explicit stop command so the service can release the
                // detector, clear its status, and stop itself through one code path.
                startService(
                    Intent(this@MainActivity, WakeWordService::class.java)
                        .setAction(WakeWordService.ACTION_STOP)
                )
                statusView.text = "Stopping listener…"
            }
        }
        root.addView(stopButton, matchWrap())

        val overlayButton = Button(this).apply {
            text = "Enable assistant launch"
            setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }
        root.addView(overlayButton, matchWrap())

        val assistantSetup = TextView(this).apply {
            text = "The wake trigger requests Android’s configured digital assistant using ACTION_ASSIST, intended to open the same assistant overlay as the power-button gesture. Display over other apps permission is required for this experimental background launch. The wake-word model is a custom candidate and requires real-device false-positive and missed-detection testing."
            textSize = 13f
            setPadding(0, 12, 0, 0)
        }
        root.addView(assistantSetup, matchWrap())

        val diagnostics = TextView(this).apply {
            text = "Diagnostics now report ONNX model loading, microphone recording state, captured audio frames, inference count, and the latest confidence score. The confidence score updates during listening, including below-threshold results."
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }
        root.addView(diagnostics, matchWrap())

        val privacy = TextView(this).apply {
            text = "Wake-word audio is processed on-device. Display over other apps is a sensitive Android permission used to attempt the wake-triggered launch. Start listening while this screen is visible. After a wake, listening stops to release the microphone to ChatGPT; restart it manually after the conversation."
            textSize = 13f
            setPadding(0, 12, 0, 0)
        }
        root.addView(privacy, matchWrap())

        setContentView(root)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        statusHandler.removeCallbacks(statusRefresh)
        statusHandler.post(statusRefresh)
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusRefresh)
        super.onPause()
    }

    override fun onDestroy() {
        statusHandler.removeCallbacks(statusRefresh)
        super.onDestroy()
    }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun permissionsGranted(): Boolean {
        val microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifications = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return microphone && notifications
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(permissions.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && permissionsGranted()) {
            startListening()
        } else if (requestCode == REQUEST_PERMISSIONS) {
            statusView.text = "Microphone and notification permissions are required to listen and show the action."
        }
    }

    private fun startListening() {
        val intent = Intent(this, WakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusView.text = "Starting listener…"
    }

    private fun refreshStatus() {
        val prefs = getSharedPreferences(WakeWordService.PREFS_NAME, MODE_PRIVATE)
        val listening = prefs.getBoolean(WakeWordService.KEY_LISTENING, false)
        val diagnostic = prefs.getString(WakeWordService.KEY_DIAGNOSTIC_STATUS, null)
            ?: if (listening) "Detector starting" else "Not listening"
        val lastScore = if (prefs.contains(WakeWordService.KEY_LAST_SCORE)) {
            prefs.getFloat(WakeWordService.KEY_LAST_SCORE, 0f)
        } else {
            null
        }
        val lastDetectionAt = prefs.getLong(WakeWordService.KEY_LAST_DETECTION_AT, 0L)

        val details = buildString {
            append(if (listening) "Status: active\n" else "Status: inactive\n")
            append(diagnostic)
            if (lastScore != null) {
                append("\nLast confidence score: ")
                append(String.format(Locale.US, "%.3f", lastScore))
                if (lastDetectionAt > 0L) {
                    append(" (")
                    append(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastDetectionAt)))
                    append(")")
                }
            }
        }
        statusView.text = details
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 10
        private const val STATUS_REFRESH_MS = 1000L
    }
}
