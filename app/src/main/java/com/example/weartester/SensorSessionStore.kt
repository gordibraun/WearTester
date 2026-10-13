package com.example.weartester

import android.content.Context

object SensorSessionStore {
    private fun prefs(context: Context) = context.getSharedPreferences("sensor_session", Context.MODE_PRIVATE)

    fun load(context: Context): SensorLifetime {
        val prefs = prefs(context)
        if (prefs.getString("transmitter", "") != DexcomConfigStore.load(context).sensorIdentity) return SensorLifetime()
        return SensorLifetime(
            prefs.getLong("started_at", 0), prefs.getInt("session_tick", -1), prefs.getInt("state", 0),
            prefs.getLong("last_contact_at", 0), prefs.getLong("grace", 0),
        )
    }

    @Synchronized fun recordTime(context: Context, session: SensorProtocol.Session, now: Long) {
        val old = load(context)
        val updated = old.withSession(session, now)
        store(context, updated, now)
        ConnectionJournal.record(context, "sensor_session", "current_tick" to session.transmitterTime,
            "session_tick" to session.startTime, "started_at" to load(context).startedAt, "active" to session.active)
    }

    /** G7: the session start as this glucose packet implies it. */
    @Synchronized fun recordStartedAt(context: Context, startedAt: Long, now: Long) {
        val old = load(context)
        val updated = old.withStartedAt(startedAt, now, SensorLifetime.G7_GRACE)
        store(context, updated, now)
        if (updated.startedAt != old.startedAt) {
            ConnectionJournal.record(context, "sensor_session", "started_at" to updated.startedAt, "previous_started_at" to old.startedAt, "model" to "G7")
        }
    }

    private fun store(context: Context, updated: SensorLifetime, now: Long) {
        prefs(context).edit().putString("transmitter", DexcomConfigStore.load(context).sensorIdentity)
            .putLong("started_at", updated.startedAt).putInt("session_tick", updated.sessionTick)
            .putInt("state", updated.state).putLong("last_contact_at", now).putLong("grace", updated.grace).apply()
    }

    @Synchronized fun recordReading(context: Context, reading: SensorProtocol.Reading, now: Long) {
        prefs(context).edit().putString("transmitter", DexcomConfigStore.load(context).sensorIdentity)
            .putInt("state", reading.state).putLong("last_contact_at", now).apply()
        ConnectionJournal.record(context, "sensor_status", "state" to reading.state, "usable" to reading.usable,
            "value" to reading.glucose, "age_seconds" to reading.ageSeconds, "display_only" to reading.displayOnly,
            "trend_tenths" to reading.trendTenths)
    }
}
