package com.example.weartester

import android.content.Context

object SensorSessionStore {
    private fun prefs(context: Context) = context.getSharedPreferences("sensor_session", Context.MODE_PRIVATE)

    fun load(context: Context): SensorLifetime {
        val prefs = prefs(context)
        if (prefs.getString("transmitter", "") != DexcomConfigStore.load(context).transmitterId) return SensorLifetime()
        return SensorLifetime(prefs.getLong("started_at", 0), prefs.getInt("session_tick", -1), prefs.getInt("state", 0), prefs.getLong("last_contact_at", 0))
    }

    @Synchronized fun recordTime(context: Context, session: SensorProtocol.Session, now: Long) {
        val old = load(context)
        val updated = old.withSession(session, now)
        val edit = prefs(context).edit().putString("transmitter", DexcomConfigStore.load(context).transmitterId)
        edit.putLong("started_at", updated.startedAt).putInt("session_tick", updated.sessionTick)
            .putInt("state", updated.state).putLong("last_contact_at", now).apply()
        ConnectionJournal.record(context, "sensor_session", "current_tick" to session.transmitterTime,
            "session_tick" to session.startTime, "started_at" to load(context).startedAt, "active" to session.active)
    }

    @Synchronized fun recordReading(context: Context, reading: SensorProtocol.Reading, now: Long) {
        prefs(context).edit().putString("transmitter", DexcomConfigStore.load(context).transmitterId)
            .putInt("state", reading.state).putLong("last_contact_at", now).apply()
        ConnectionJournal.record(context, "sensor_status", "state" to reading.state, "usable" to reading.usable,
            "value" to reading.glucose, "age_seconds" to reading.ageSeconds, "display_only" to reading.displayOnly)
    }
}
