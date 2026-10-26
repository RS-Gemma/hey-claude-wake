package com.pistolwhip.wakephraseservice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Launches the configured digital assistant (Claude) using one of several candidate methods.
 *
 * Which method lands in Claude's voice mode is not documented, so the method is
 * user-selectable and each one can be tested from the main screen. The selected method
 * is stored in shared preferences and used by the wake-word service on detection.
 */
object AssistantLauncher {
    private const val TAG = "AssistantLauncher"
    const val CLAUDE_PACKAGE = "com.anthropic.claude"
    const val ACTION_VOICE_ASSIST = "android.intent.action.VOICE_ASSIST"

    enum class Method(val label: String, val shellCommand: String? = null) {
        SHIZUKU_SHOW("Shizuku: cmd voiceinteraction show", "cmd voiceinteraction show"),
        // Wake the screen first: the assistant key is ignored while the screen is off.
        SHIZUKU_KEY_ASSIST(
            "Shizuku: assistant key",
            "input keyevent KEYCODE_WAKEUP; sleep 0.3; input keyevent KEYCODE_ASSIST"
        ),
        SHIZUKU_KEY_VOICE_ASSIST("Shizuku: voice assistant key", "input keyevent KEYCODE_VOICE_ASSIST"),
        ASSIST("ACTION_ASSIST (system default assistant)"),
        VOICE_ASSIST("ACTION_VOICE_ASSIST"),
        VOICE_COMMAND("ACTION_VOICE_COMMAND"),
        ASSIST_CLAUDE("ACTION_ASSIST sent directly to Claude"),
        CLAUDE_APP("Open Claude app (normal launch)"),
    }

    fun selectedMethod(context: Context): Method {
        val name = context.getSharedPreferences(WakeWordService.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(WakeWordService.KEY_LAUNCH_METHOD, null)
        return Method.values().firstOrNull { it.name == name } ?: Method.ASSIST
    }

    fun setSelectedMethod(context: Context, method: Method) {
        context.getSharedPreferences(WakeWordService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(WakeWordService.KEY_LAUNCH_METHOD, method.name)
            .apply()
    }

    /** Returns the intent for [method], or null if nothing on the device can handle it. */
    fun buildIntent(context: Context, method: Method): Intent? {
        val intent = when (method) {
            Method.SHIZUKU_SHOW, Method.SHIZUKU_KEY_ASSIST, Method.SHIZUKU_KEY_VOICE_ASSIST -> null
            Method.ASSIST -> Intent(Intent.ACTION_ASSIST)
            Method.VOICE_ASSIST -> Intent(ACTION_VOICE_ASSIST)
            Method.VOICE_COMMAND -> Intent(Intent.ACTION_VOICE_COMMAND)
            Method.ASSIST_CLAUDE -> Intent(Intent.ACTION_ASSIST).setPackage(assistantPackage(context))
            Method.CLAUDE_APP -> context.packageManager.getLaunchIntentForPackage(assistantPackage(context))
        } ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }

    fun launch(context: Context, method: Method = selectedMethod(context)): Boolean {
        method.shellCommand?.let { command ->
            if (!ShizukuShell.hasPermission()) {
                Log.w(TAG, "Shizuku not ready for $method: ${ShizukuShell.status()}")
                return false
            }
            Thread { ShizukuShell.run(command) }.start()
            return true
        }
        val intent = buildIntent(context, method)
        if (intent == null) {
            Log.w(TAG, "No activity handles $method")
            return false
        }
        return try {
            context.startActivity(intent)
            Log.i(TAG, "Assistant launch requested via $method")
            true
        } catch (error: Exception) {
            Log.w(TAG, "Assistant launch via $method failed", error)
            false
        }
    }

    /** Package of the system's configured assistant, falling back to Claude's package. */
    fun assistantPackage(context: Context): String =
        secureSetting(context, "assistant")?.let { ComponentName.unflattenFromString(it)?.packageName }
            ?: CLAUDE_PACKAGE

    private fun secureSetting(context: Context, key: String): String? =
        try {
            Settings.Secure.getString(context.contentResolver, key)?.takeIf { it.isNotBlank() }
        } catch (error: Exception) {
            null
        }

    /** Human-readable report of what the device exposes, used to pick a working method. */
    fun diagnostics(context: Context): String = buildString {
        val pm = context.packageManager
        append("Configured assistant: ")
        append(secureSetting(context, "assistant") ?: "(not readable)")
        append("\nVoice interaction service: ")
        append(secureSetting(context, "voice_interaction_service") ?: "(not readable)")

        for (method in Method.values()) {
            append("\n\n")
            append(method.label)
            append(":\n  ")
            if (method.shellCommand != null) {
                append("via Shizuku (${ShizukuShell.status()})")
                continue
            }
            val intent = buildIntent(context, method)
            val target = intent?.resolveActivity(pm)
            append(target?.flattenToShortString() ?: "nothing handles this")
        }

        val claude = assistantPackage(context)
        append("\n\nExported activities in $claude:")
        try {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(claude, PackageManager.GET_ACTIVITIES)
            val exported = info.activities.orEmpty().filter { it.exported }
            if (exported.isEmpty()) append("\n  (none)")
            exported.forEach { append("\n  ").append(it.name) }
        } catch (error: PackageManager.NameNotFoundException) {
            append("\n  (package not installed or not visible)")
        }
        try {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(claude, PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS)
            append("\n\nExported services in $claude:")
            info.services.orEmpty().filter { it.exported }
                .forEach { append("\n  ").append(it.name).append(it.permission?.let { p -> " (needs $p)" } ?: "") }
            append("\n\nExported receivers in $claude:")
            info.receivers.orEmpty().filter { it.exported }
                .forEach { append("\n  ").append(it.name).append(it.permission?.let { p -> " (needs $p)" } ?: "") }
        } catch (error: PackageManager.NameNotFoundException) {
        }
    }
}
