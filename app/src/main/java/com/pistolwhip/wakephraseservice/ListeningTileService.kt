package com.pistolwhip.wakephraseservice

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile that switches wake phrase listening on and off. */
class ListeningTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val listening = getSharedPreferences(WakeWordService.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(WakeWordService.KEY_LISTENING, false)
        tile.state = if (listening) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = if (listening) "Listening" else "Off"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ToggleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Asks the system to refresh the tile after listening changes. */
        fun refresh(context: Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, ListeningTileService::class.java))
            }
        }
    }
}
