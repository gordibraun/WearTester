package com.example.weartester

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * The watch's heartbeat says how old its last sensor reading is and whether its Bluetooth stack
 * has wedged. The watch itself may be out of sight on a charger (19 Oct 2026: three hours without
 * glucose, the watch's own alerts unseen), so the phone, which is usually in hand, repeats the
 * warning. Cleared by the next reading that comes through.
 */
object WatchHealthAlert {
    private const val PREFS = "watch_health_alert"
    private const val KEY_LAST_ALERT_AT = "last_alert_at"
    private const val CHANNEL_ID = "watch_health"
    private const val NOTIF_ID = 71
    private const val STALE_MS = 20 * 60_000L
    private const val REPEAT_MS = 10 * 60_000L

    fun onHeartbeat(context: Context, sensorAgeMs: Long?, btWedged: Boolean) {
        val stale = sensorAgeMs != null && sensorAgeMs >= STALE_MS
        if (!stale && !btWedged) {
            clear(context)
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_ALERT_AT, 0L) < REPEAT_MS) return
        prefs.edit().putLong(KEY_LAST_ALERT_AT, now).apply()
        val minutes = (sensorAgeMs ?: 0L) / 60_000L
        val text = when {
            btWedged -> "Часы видят сенсор, но подключения зависают: Bluetooth часов завис. Выключите и включите Bluetooth на часах (Настройки → Подключения)." +
                if (stale) " Глюкозы нет $minutes мин." else ""
            else -> "Часы не получают глюкозу $minutes мин. Поднесите часы к сенсору; если они рядом, проверьте Bluetooth часов."
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Связь часов с сенсором", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Когда часы долго не получают глюкозу или их Bluetooth завис"
                enableVibration(true)
            },
        )
        ConnectionJournal.record(context, "watch_health_alert", "sensor_age_ms" to sensorAgeMs, "bt_wedged" to btWedged)
        runCatching {
            manager.notify(
                NOTIF_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle(if (btWedged) "Bluetooth часов завис" else "Часы без глюкозы")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_LAST_ALERT_AT, 0L) == 0L) return
        prefs.edit().remove(KEY_LAST_ALERT_AT).apply()
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }
}
