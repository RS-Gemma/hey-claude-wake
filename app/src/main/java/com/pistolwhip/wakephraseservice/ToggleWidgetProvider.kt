package com.pistolwhip.wakephraseservice

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen switch for wake phrase listening: green when listening, grey when off. */
class ToggleWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    companion object {
        /** Redraws all placed widgets with the current listening state. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ToggleWidgetProvider::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context): RemoteViews {
            val listening = context.getSharedPreferences(WakeWordService.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(WakeWordService.KEY_LISTENING, false)
            val toggle = PendingIntent.getActivity(
                context,
                0,
                Intent(context, ToggleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return RemoteViews(context.packageName, R.layout.widget_toggle).apply {
                setInt(
                    R.id.widget_root,
                    "setBackgroundResource",
                    if (listening) R.drawable.widget_bg_on else R.drawable.widget_bg_off
                )
                setImageViewResource(
                    R.id.widget_switch,
                    if (listening) R.drawable.widget_switch_on else R.drawable.widget_switch_off
                )
                setTextViewText(R.id.widget_status, if (listening) "Listening" else "Off")
                setOnClickPendingIntent(R.id.widget_root, toggle)
            }
        }
    }
}
