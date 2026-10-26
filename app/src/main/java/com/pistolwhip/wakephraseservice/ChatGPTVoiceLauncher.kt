package com.pistolwhip.wakephraseservice

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Experimental launcher for the system-selected digital assistant.
 *
 * The owner wants the assistant overlay invoked by the system power-button gesture,
 * not ChatGPT's full-screen Voice activity. ACTION_ASSIST asks Android to launch the
 * configured assistant. Android may still defer its operation until the device is unlocked.
 */
object ChatGPTVoiceLauncher {
    private const val TAG = "ChatGPTVoiceLauncher"

    fun launch(context: Context): Boolean {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Assistant launch skipped: display-over-other-apps permission is not granted")
            return false
        }

        return try {
            context.startActivity(
                Intent(Intent.ACTION_ASSIST)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Log.i(TAG, "System assistant overlay launch requested via ACTION_ASSIST")
            true
        } catch (error: Exception) {
            Log.w(TAG, "System assistant launch failed; retain notification fallback", error)
            false
        }
    }
}
