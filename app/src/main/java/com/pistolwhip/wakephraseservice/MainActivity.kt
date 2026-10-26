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
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.EditText
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var diagnosticsView: TextView
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
            text = "Hey Claude"
            textSize = 26f
            gravity = Gravity.CENTER
        }
        root.addView(title, matchWrap())

        val description = TextView(this).apply {
            text = "Say “Hey Jarvis” to open your default digital assistant. Detection runs on-device. Listening pauses after detection; restart it after your conversation. If the assistant does not open, tap the detection notification."
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
            text = "Allow display over other apps (needed for wake launch)"
            setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }
        root.addView(overlayButton, matchWrap())

        val fullScreenButton = Button(this).apply {
            text = "Allow full-screen notifications (lock-screen fallback)"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
        }
        root.addView(fullScreenButton, matchWrap())

        root.addView(TextView(this).apply {
            text = "Launch method test"
            textSize = 20f
            setPadding(0, 32, 0, 8)
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "Tap each Test button and note which one opens Claude in voice mode. Then select that method below; the wake phrase will use it."
            textSize = 14f
        }, matchWrap())

        for (method in AssistantLauncher.Method.values()) {
            root.addView(Button(this).apply {
                text = "Test: ${method.label}"
                setOnClickListener {
                    if (!AssistantLauncher.launch(this@MainActivity, method)) {
                        statusView.text = "Nothing on this phone handles ${method.label}."
                    }
                }
            }, matchWrap())
        }

        root.addView(TextView(this).apply {
            text = "Method used by the wake phrase:"
            textSize = 16f
            setPadding(0, 24, 0, 8)
        }, matchWrap())
        val selected = AssistantLauncher.selectedMethod(this)
        val methodGroup = RadioGroup(this)
        for ((index, method) in AssistantLauncher.Method.values().withIndex()) {
            methodGroup.addView(RadioButton(this).apply {
                id = index + 1
                text = method.label
                isChecked = method == selected
                setOnClickListener { AssistantLauncher.setSelectedMethod(this@MainActivity, method) }
            })
        }
        root.addView(methodGroup, matchWrap())

        addVoiceSwitchFinder(root)

        root.addView(TextView(this).apply {
            text = "Diagnostics"
            textSize = 20f
            setPadding(0, 32, 0, 8)
        }, matchWrap())
        diagnosticsView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(diagnosticsView, matchWrap())

        root.addView(TextView(this).apply {
            text = "Wake-word audio is processed on-device. Display over other apps is used only to open the assistant when the wake phrase is detected. Wake phrase model: openWakeWord “Hey Jarvis” (CC BY-NC-SA 4.0, non-commercial)."
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }, matchWrap())

        setContentView(ScrollView(this).apply { addView(root) })
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        diagnosticsView.text = buildDiagnostics()
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

    private fun addVoiceSwitchFinder(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "Find Claude's voice switch"
            textSize = 20f
            setPadding(0, 32, 0, 8)
        }, matchWrap())

        val scanView = TextView(this).apply {
            textSize = 11f
            setTextIsSelectable(true)
        }
        root.addView(Button(this).apply {
            text = "Scan Claude app"
            setOnClickListener {
                scanView.text = "Scanning… (can take ~30 seconds)"
                Thread {
                    val result = try {
                        ClaudeScanner.scan(this@MainActivity)
                    } catch (error: Throwable) {
                        "Scan failed: ${error.javaClass.simpleName}: ${error.message}"
                    }
                    runOnUiThread { scanView.text = result }
                }.start()
            }
        }, matchWrap())
        root.addView(Button(this).apply {
            text = "Copy scan result"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Claude scan", scanView.text))
                statusView.text = "Scan result copied."
            }
        }, matchWrap())
        root.addView(scanView, matchWrap())

        val linkField = EditText(this).apply { hint = "Link to try, e.g. claude://…" }
        root.addView(linkField, matchWrap())
        root.addView(Button(this).apply {
            text = "Try link"
            setOnClickListener {
                tryLaunch(
                    Intent(Intent.ACTION_VIEW, Uri.parse(linkField.text.toString().trim()))
                        .setPackage(AssistantLauncher.assistantPackage(this@MainActivity))
                )
            }
        }, matchWrap())

        val extraField = EditText(this).apply { hint = "Switches to try, e.g. start_voice or mode=voice" }
        root.addView(extraField, matchWrap())
        root.addView(Button(this).apply {
            text = "Try switch (assistant screen)"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_ASSIST)
                    .setPackage(AssistantLauncher.assistantPackage(this@MainActivity))
                putExtras(intent, extraField.text.toString())
                tryLaunch(intent)
            }
        }, matchWrap())
    }

    /** Parses "key", "key=true", "key=3" or "key=text", comma-separated, into intent extras. */
    private fun putExtras(intent: Intent, spec: String) {
        for (part in spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val key = part.substringBefore('=').trim()
            val value = if ('=' in part) part.substringAfter('=').trim() else "true"
            when {
                value == "true" || value == "false" -> intent.putExtra(key, value.toBoolean())
                value.toIntOrNull() != null -> intent.putExtra(key, value.toInt())
                else -> intent.putExtra(key, value)
            }
        }
    }

    private fun tryLaunch(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: Exception) {
            statusView.text = "Launch failed: ${error.javaClass.simpleName}: ${error.message}"
        }
    }

    private fun buildDiagnostics(): String = buildString {
        append("Display over other apps: ")
        append(if (Settings.canDrawOverlays(this@MainActivity)) "granted" else "NOT granted")
        if (Build.VERSION.SDK_INT >= 34) {
            append("\nFull-screen notifications: ")
            val allowed = getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
            append(if (allowed) "allowed" else "not allowed")
        }
        append("\nAndroid SDK: ").append(Build.VERSION.SDK_INT)
        append("\n\n").append(AssistantLauncher.diagnostics(this@MainActivity))
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
