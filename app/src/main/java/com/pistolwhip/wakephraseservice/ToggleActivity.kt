package com.pistolwhip.wakephraseservice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible screen that turns listening on or off, used by the Quick Settings tile and the
 * app-icon shortcut. Starting the microphone service needs a visible activity on modern Android.
 */
class ToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val listening = getSharedPreferences(WakeWordService.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(WakeWordService.KEY_LISTENING, false)
        val service = Intent(this, WakeWordService::class.java)
        when {
            listening -> {
                startService(service.setAction(WakeWordService.ACTION_STOP))
                Toast.makeText(this, "Wake phrase listening off", Toast.LENGTH_SHORT).show()
            }
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                // Permissions are requested from the main screen.
                startActivity(Intent(this, MainActivity::class.java))
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service) else startService(service)
                Toast.makeText(this, "Wake phrase listening on", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
