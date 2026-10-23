package com.pistolwhip.wakephraseservice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusView: TextView

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
            text = "Stage 1 listens locally for “Hey Jarvis”. When detected, a notification lets you open ChatGPT. It does not automatically start ChatGPT Voice."
            textSize = 16f
            setPadding(0, 20, 0, 20)
        }
        root.addView(description, matchWrap())

        statusView = TextView(this).apply {
            textSize = 17f
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
                stopService(Intent(this@MainActivity, WakeWordService::class.java))
                refreshStatus()
            }
        }
        root.addView(stopButton, matchWrap())

        val privacy = TextView(this).apply {
            text = "Microphone audio is processed on-device by the wake-word detector. Android shows a persistent notification while listening. Start the service while this screen is visible."
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }
        root.addView(privacy, matchWrap())

        setContentView(root)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::statusView.isInitialized) refreshStatus()
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
        val listening = getSharedPreferences(WakeWordService.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(WakeWordService.KEY_LISTENING, false)
        statusView.text = if (listening) {
            "Status: listening for “Hey Jarvis”"
        } else {
            "Status: not listening"
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 10
    }
}
