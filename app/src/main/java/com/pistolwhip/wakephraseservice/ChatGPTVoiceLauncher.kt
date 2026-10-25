package com.pistolwhip.wakephraseservice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * Experimental ChatGPT Voice launcher.
 *
 * ChatGPT does not document a public Android intent for Voice mode. The first route targets
 * the currently observed exported Voice activity; the deeplink is a best-effort fallback.
 * Either route may break when ChatGPT changes. Overlay permission is required before trying
 * to launch from a background wake-word callback under modern Android background-launch rules.
 */
object ChatGPTVoiceLauncher {
    private const val TAG = "ChatGPTVoiceLauncher"
    private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
    private val VOICE_COMPONENT = ComponentName(
        CHATGPT_PACKAGE,
        "com.openai.voice.assistant.AssistantActivity"
    )

    fun launch(context: Context): Boolean {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Direct launch skipped: display-over-other-apps permission is not granted")
            return false
        }

        try {
            val info = context.packageManager.getActivityInfo(VOICE_COMPONENT, 0)
            if (!info.exported || info.permission != null) {
                Log.w(TAG, "Direct Voice activity is not externally launchable")
            } else {
                context.startActivity(
                    Intent().setComponent(VOICE_COMPONENT)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                Log.i(TAG, "Direct ChatGPT Voice activity launch requested")
                return true
            }
        } catch (error: Exception) {
            Log.w(TAG, "Direct Voice activity unavailable; trying deeplink", error)
        }

        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://chat.com/?mode=voice"))
                    .setPackage(CHATGPT_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Log.i(TAG, "ChatGPT Voice deeplink launch requested")
            return true
        } catch (error: Exception) {
            Log.w(TAG, "ChatGPT Voice deeplink failed", error)
        }

        return false
    }
}
