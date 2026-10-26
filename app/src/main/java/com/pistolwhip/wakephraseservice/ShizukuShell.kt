package com.pistolwhip.wakephraseservice

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Runs shell commands with ADB-level rights through Shizuku (no root needed: Shizuku is
 * started via Wireless debugging). Used to ask Android to show the default assistant the
 * same way the side key does, which normal apps cannot do.
 */
object ShizukuShell {
    private const val TAG = "ShizukuShell"
    const val REQUEST_CODE = 4242

    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (error: Throwable) {
        false
    }

    fun hasPermission(): Boolean =
        isRunning() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun status(): String = when {
        !isRunning() -> "not running (open Shizuku and tap Start)"
        !hasPermission() -> "running, permission NOT granted"
        else -> "running, permission granted"
    }

    fun requestPermission() {
        if (isRunning() && !hasPermission()) Shizuku.requestPermission(REQUEST_CODE)
    }

    /** Runs [command] via `sh -c` and returns exit code plus output. Blocks; call off the main thread. */
    fun run(command: String): String {
        if (!hasPermission()) return "Shizuku not ready: ${status()}"
        return try {
            // Shizuku.newProcess is private in API 13 but still the simplest way to run a command.
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val output = process.inputStream.bufferedReader().readText() +
                process.errorStream.bufferedReader().readText()
            val exit = process.waitFor()
            Log.i(TAG, "`$command` exit=$exit output=$output")
            "exit $exit${if (output.isNotBlank()) ": ${output.trim()}" else ""}"
        } catch (error: Throwable) {
            Log.w(TAG, "`$command` failed", error)
            "failed: ${error.javaClass.simpleName}: ${error.message}"
        }
    }
}
