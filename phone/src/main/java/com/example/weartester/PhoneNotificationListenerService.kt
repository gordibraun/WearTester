package com.example.weartester

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class PhoneNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications?.forEach(::parseAndSend)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        parseAndSend(sbn)
    }

    private fun parseAndSend(sbn: StatusBarNotification) {
        if (sbn.packageName !in SOURCE_PACKAGES) return
        val extras = sbn.notification.extras ?: return
        val text = listOfNotNull(
            extras.getCharSequence("android.title")?.toString(),
            extras.getCharSequence("android.text")?.toString(),
            extras.getCharSequence("android.subText")?.toString(),
            extras.getCharSequence("android.bigText")?.toString(),
        ).joinToString(separator = " ")
        val mgdl = parseMgdl(text) ?: return
        PhoneGlucoseSender.send(
            context = applicationContext,
            mgdl = mgdl,
            timestamp = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
            source = "notification:${sbn.packageName}",
        )
        Log.d(TAG, "Relayed notification glucose $mgdl from ${sbn.packageName}")
    }

    private fun parseMgdl(text: String): Int? {
        for (match in GLUCOSE_PATTERN.findAll(text)) {
            val value = match.groupValues[1].toIntOrNull() ?: continue
            if (value in 20..600) return value
        }
        return null
    }

    companion object {
        private const val TAG = "WearTesterRelay"
        private val SOURCE_PACKAGES = setOf(
            "com.eveningoutpost.dexdrip",
            "info.nightscout.androidaps",
        )
        private val GLUCOSE_PATTERN = Regex(
            pattern = """(?<![\d.])([2-9]\d|[1-3]\d{2}|400)\s*(?:mg/?d[lL]|→|↗|↘|↑|↓|↔|⇒|⇈|⇊)""",
        )
    }
}
