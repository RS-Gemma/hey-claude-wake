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
import android.content.ContentValues
import android.provider.MediaStore
import android.view.WindowInsets
import android.widget.EditText
import android.widget.Toast
import android.content.res.ColorStateList
import android.os.SystemClock
import android.widget.Button
import android.widget.CheckBox
import android.widget.Switch
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
    private lateinit var listeningSwitch: Switch
    private var syncingSwitch = false
    private var switchTouchedAt = 0L
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
            text = "Say a wake phrase (“Hey Jarvis”, or your own trained phrases) to open your default digital assistant. Detection runs on-device. Listening pauses during the conversation and resumes by itself. If the assistant does not open, tap the detection notification."
            textSize = 16f
            setPadding(0, 20, 0, 20)
        }
        root.addView(description, matchWrap())

        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, 8, 0, 24)
        }
        root.addView(statusView, matchWrap())

        // One control for listening; it follows the real state, also when changed from the
        // home-screen widget, the Quick Settings tile, or by the service itself.
        listeningSwitch = Switch(this).apply {
            text = "Listening"
            textSize = 20f
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(0xFF2E7D32.toInt(), 0xFF9E9E9E.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(0xFFA5D6A7.toInt(), 0xFFBDBDBD.toInt()))
            setOnCheckedChangeListener { _, checked ->
                if (syncingSwitch) return@setOnCheckedChangeListener
                switchTouchedAt = SystemClock.elapsedRealtime()
                if (checked) {
                    if (permissionsGranted()) startListening() else requestNeededPermissions()
                } else {
                    // Deliver an explicit stop command so the service can release the
                    // detector, clear its status, and stop itself through one code path.
                    startService(
                        Intent(this@MainActivity, WakeWordService::class.java)
                            .setAction(WakeWordService.ACTION_STOP)
                    )
                    statusView.text = "Stopping listener…"
                }
            }
        }
        root.addView(listeningSwitch, matchWrap())

        root.addView(CheckBox(this).apply {
            text = "Use “Hey Jarvis”"
            isChecked = getSharedPreferences(WakeWordService.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(WakeWordService.KEY_JARVIS_ENABLED, true)
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences(WakeWordService.PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(WakeWordService.KEY_JARVIS_ENABLED, checked).apply()
            }
        }, matchWrap())

        root.addView(Button(this).apply {
            text = "Train personal phrases (Hey Claude, Hey buddy)"
            setOnClickListener { startActivity(Intent(this@MainActivity, TrainActivity::class.java)) }
        }, matchWrap())

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

        addShizukuSection(root)

        for (method in AssistantLauncher.Method.values()) {
            root.addView(Button(this).apply {
                text = "Test: ${method.label}"
                setOnClickListener {
                    val command = method.shellCommand
                    if (command != null) {
                        runShell(command)
                    } else if (!AssistantLauncher.launch(this@MainActivity, method)) {
                        toast("Nothing on this phone handles ${method.label}.")
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
            text = "Wake-word audio is processed on-device. Display over other apps is used only to open the assistant when the wake phrase is detected. Wake phrase models: openWakeWord “Hey Jarvis” (CC BY-NC-SA 4.0, non-commercial); personal phrases are your own recordings, stored only on this phone."
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }, matchWrap())

        val scroll = ScrollView(this).apply { addView(root) }
        // Android 15+ draws apps edge-to-edge; keep content clear of the status and navigation bars.
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        } else {
            scroll.fitsSystemWindows = true
        }
        setContentView(scroll)
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

    private fun addShizukuSection(root: LinearLayout) {
        val shizukuStatus = TextView(this).apply {
            textSize = 14f
            text = "Shizuku: ${ShizukuShell.status()}"
        }
        root.addView(shizukuStatus, matchWrap())
        root.addView(Button(this).apply {
            text = "Grant Shizuku permission"
            setOnClickListener {
                if (!ShizukuShell.isRunning()) {
                    toast("Shizuku is not running. Open Shizuku and tap Start first.")
                } else {
                    ShizukuShell.requestPermission()
                }
                shizukuStatus.text = "Shizuku: ${ShizukuShell.status()}"
            }
        }, matchWrap())
        root.addView(Button(this).apply {
            text = "Dump Claude shortcuts (Shizuku, saves a file)"
            setOnClickListener {
                Thread {
                    val dump = ShizukuShell.run(
                        "dumpsys shortcut | awk '/Package: com.anthropic.claude/{p=1} p&&/Package: /&&!/com.anthropic.claude/{p=0} p'"
                    )
                    runOnUiThread { saveAndShare("claude-shortcuts", dump) }
                }.start()
            }
        }, matchWrap())
    }

    private fun runShell(command: String) {
        Thread {
            val result = ShizukuShell.run(command)
            runOnUiThread { toast("$command → $result") }
        }.start()
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
            text = "Save & share scan (full report as a file)"
            setOnClickListener {
                val text = scanView.text.toString()
                if (text.startsWith("Package:")) saveAndShare("claude-scan", text) else toast("Run the scan first.")
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

        val extraField = EditText(this).apply { hint = "Switches to try, e.g. @class=…, @service=…, @action=…, @data=…, key=value" }
        root.addView(extraField, matchWrap())
        root.addView(Button(this).apply {
            text = "Try switch"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_ASSIST)
                    .setPackage(AssistantLauncher.assistantPackage(this@MainActivity))
                val isService = putExtras(intent, extraField.text.toString())
                if (isService) tryStartService(intent) else tryLaunch(intent)
            }
        }, matchWrap())
    }

    /** Saves [text] to Downloads and opens the share sheet, avoiding clipboard size limits. */
    private fun saveAndShare(name: String, text: String) {
        if (Build.VERSION.SDK_INT < 29) {
            toast("Saving needs Android 10 or newer; use Copy instead.")
            return
        }
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$name-${System.currentTimeMillis()}.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("could not create file")
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            val share = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(share, "Share $name"))
            toast("Saved to Downloads.")
        } catch (error: Exception) {
            toast("Save failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /**
     * Parses comma-separated "key", "key=true", "key=3" or "key=text" into intent extras.
     * Special keys: "@class=" targets a Claude activity, "@action=" and "@data=" set those fields.
     */
    private fun putExtras(intent: Intent, spec: String): Boolean {
        var isService = false
        for (part in spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val key = part.substringBefore('=').trim()
            val value = if ('=' in part) part.substringAfter('=').trim() else "true"
            when {
                key == "@class" -> intent.setClassName(intent.`package` ?: AssistantLauncher.CLAUDE_PACKAGE, value)
                key == "@service" -> {
                    intent.setClassName(intent.`package` ?: AssistantLauncher.CLAUDE_PACKAGE, value)
                    intent.action = null
                    isService = true
                }
                key == "@action" -> intent.action = value
                key == "@data" -> intent.data = Uri.parse(value)
                value == "true" || value == "false" -> intent.putExtra(key, value.toBoolean())
                value.toIntOrNull() != null -> intent.putExtra(key, value.toInt())
                else -> intent.putExtra(key, value)
            }
        }
        return isService
    }

    private fun tryStartService(intent: Intent) {
        try {
            val started = startService(intent)
            toast(if (started != null) "Service started: ${started.shortClassName}" else "Service not found")
        } catch (error: Exception) {
            toast("Service failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun tryLaunch(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: Exception) {
            toast("Launch failed: ${error.javaClass.simpleName}: ${error.message}")
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
            setSwitch(false)
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
        val lastPhrase = prefs.getString(WakeWordService.KEY_LAST_PHRASE, null)
        val lastDetectionAt = prefs.getLong(WakeWordService.KEY_LAST_DETECTION_AT, 0L)

        val details = buildString {
            append(diagnostic)
            if (lastPhrase != null && lastDetectionAt > 0L) {
                append("\nLast heard: ")
                append(lastPhrase)
                append(" at ")
                append(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastDetectionAt)))
            }
        }
        statusView.text = details
        // Give the service a moment to start or stop before following its state again.
        if (SystemClock.elapsedRealtime() - switchTouchedAt > SWITCH_SETTLE_MS) setSwitch(listening)
    }

    private fun setSwitch(checked: Boolean) {
        if (!::listeningSwitch.isInitialized || listeningSwitch.isChecked == checked) return
        syncingSwitch = true
        listeningSwitch.isChecked = checked
        syncingSwitch = false
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 10
        private const val SWITCH_SETTLE_MS = 3000L
        private const val STATUS_REFRESH_MS = 1000L
    }
}
