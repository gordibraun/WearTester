package com.example.weartester

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

object ConnectionMonitor {
    const val PING = "/weartester/diagnostic/ping"
    const val ACK = "/weartester/diagnostic/ack"
    private const val PREFS = "connection_monitor"
    private var previousTick = 0L

    fun tick(context: Context) {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val delay = if (previousTick > 0) elapsed - previousTick else 0L
        previousTick = elapsed
        val sample = DexcomConfigStore.loadDirectGlucose(context)
        val age = sample.receivedAtMillis.takeIf { it > 0 }?.let { now - it + sample.ageSeconds.coerceAtLeast(0) * 1000L }
        val pm = context.getSystemService(PowerManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ackElapsed = prefs.getLong("ack_elapsed", 0)
        val ackAge = (elapsed - ackElapsed).takeIf { ackElapsed > 0 && it >= 0 && prefs.getLong("ack_wall", 0) > now - 2 * 60_000L }
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            val health = LinkHealth.describe(age, nodes.size, ackAge)
            prefs.edit().putString("status", health).apply()
            ConnectionJournal.record(context, "health", "sensor_age_ms" to age, "nodes" to nodes.size, "ack_age_ms" to ackAge,
                "sensor_contact_at" to SensorSessionStore.load(context).lastContactAt, "sensor_state" to SensorSessionStore.load(context).state,
                "watchdog_interval_ms" to delay, "interactive" to pm?.isInteractive, "idle" to pm?.isDeviceIdleMode,
                "power_save" to pm?.isPowerSaveMode, "battery_exempt" to pm?.isIgnoringBatteryOptimizations(context.packageName))
            val ping = JSONObject().put("sentAt", now).put("elapsed", elapsed).toString().toByteArray()
            nodes.forEach { node -> Wearable.getMessageClient(context).sendMessage(node.id, PING, ping)
                .addOnFailureListener { ConnectionJournal.record(context, "ping_failed", "error" to it.javaClass.simpleName) } }
        }.addOnFailureListener { ConnectionJournal.record(context, "nodes_failed", "error" to it.javaClass.simpleName) }
    }

    fun acceptAck(context: Context, data: ByteArray) {
        runCatching {
            val json = JSONObject(String(data, Charsets.UTF_8))
            val sent = json.optLong("sentAt", 0)
            val rtt = System.currentTimeMillis() - sent
            if (rtt !in 0..120_000L) return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("ack_elapsed", SystemClock.elapsedRealtime()).putLong("ack_wall", System.currentTimeMillis()).apply()
            ConnectionJournal.record(context, "phone_ack", "stage" to json.optString("stage"), "round_trip_ms" to rtt,
                "reading_at" to json.optLong("readingAt", 0))
        }.onFailure { ConnectionJournal.record(context, "invalid_ack", "error" to it.javaClass.simpleName) }
    }

    fun status(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("status", "Диагностика связи запускается") ?: ""
}
