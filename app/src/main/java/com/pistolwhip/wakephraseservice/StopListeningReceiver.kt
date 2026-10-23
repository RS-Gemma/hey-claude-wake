package com.pistolwhip.wakephraseservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StopListeningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        context.stopService(Intent(context, WakeWordService::class.java))
    }
}
