package com.example.weartester

import android.content.Context

data class DexcomConfig(
    val transmitterId: String,
    val sensorCode: String,
    val knownMac: String,
)

data class ScanDebugState(
    val seenName: String,
    val seenMac: String,
    val seenRssi: String,
    val lastEvent: String,
    val lastEventAtMillis: Long,
    val lastBondAtMillis: Long,
)

data class GlucoseReadingState(
    val mgdl: Int?,
    val receivedAtMillis: Long,
    val dexTimestamp: Int,
    val ageSeconds: Int,
    val source: String,
)

object DexcomConfigStore {
    private const val PREFS_NAME = "dexcom_config"
    private const val KEY_TRANSMITTER_ID = "transmitter_id"
    private const val KEY_SENSOR_CODE = "sensor_code"
    private const val KEY_KNOWN_MAC = "known_mac"
    private const val KEY_LAST_SEEN_NAME = "last_seen_name"
    private const val KEY_LAST_SEEN_MAC = "last_seen_mac"
    private const val KEY_LAST_SEEN_RSSI = "last_seen_rssi"
    private const val KEY_LAST_EVENT = "last_event"
    private const val KEY_LAST_EVENT_AT_MILLIS = "last_event_at_millis"
    private const val KEY_LAST_BOND_AT_MILLIS = "last_bond_at_millis"
    private const val KEY_GLUCOSE_MGDL = "glucose_mgdl"
    private const val KEY_GLUCOSE_RECEIVED_AT_MILLIS = "glucose_received_at_millis"
    private const val KEY_GLUCOSE_DEX_TIMESTAMP = "glucose_dex_timestamp"
    private const val KEY_GLUCOSE_AGE_SECONDS = "glucose_age_seconds"
    private const val KEY_GLUCOSE_SOURCE = "glucose_source"
    private const val KEY_DIRECT_GLUCOSE_MGDL = "direct_glucose_mgdl"
    private const val KEY_DIRECT_GLUCOSE_RECEIVED_AT_MILLIS = "direct_glucose_received_at_millis"
    private const val KEY_DIRECT_GLUCOSE_DEX_TIMESTAMP = "direct_glucose_dex_timestamp"
    private const val KEY_DIRECT_GLUCOSE_AGE_SECONDS = "direct_glucose_age_seconds"
    private const val KEY_DIRECT_GLUCOSE_SOURCE = "direct_glucose_source"
    private const val KEY_EVENT_LOG = "event_log"
    private const val EVENT_LOG_MAX_LINES = 90
    private const val EVENT_LOG_MAX_EVENT_CHARS = 220
    private const val DEFAULT_TRANSMITTER_ID = "8YUBGW"
    private const val DEFAULT_SENSOR_CODE = "7171"

    fun load(context: Context): DexcomConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return DexcomConfig(
            transmitterId = prefs.getString(KEY_TRANSMITTER_ID, DEFAULT_TRANSMITTER_ID)
                ?.trim()
                ?.ifBlank { DEFAULT_TRANSMITTER_ID }
                .orEmpty(),
            sensorCode = prefs.getString(KEY_SENSOR_CODE, DEFAULT_SENSOR_CODE)
                ?.trim()
                ?.ifBlank { DEFAULT_SENSOR_CODE }
                .orEmpty(),
            knownMac = prefs.getString(KEY_KNOWN_MAC, "")
                ?.trim()
                .orEmpty()
                .uppercase(),
        )
    }

    fun save(context: Context, config: DexcomConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val previousTransmitter = prefs.getString(KEY_TRANSMITTER_ID, "")?.trim()?.uppercase().orEmpty()
        val previousSensor = prefs.getString(KEY_SENSOR_CODE, "")?.trim()?.uppercase().orEmpty()
        val nextTransmitter = config.transmitterId.trim().uppercase()
        val nextSensor = config.sensorCode.trim().uppercase()
        val configChanged = prefs.contains(KEY_TRANSMITTER_ID) &&
            (previousTransmitter != nextTransmitter || previousSensor != nextSensor)

        prefs.edit()
            .putString(KEY_TRANSMITTER_ID, config.transmitterId.trim().uppercase())
            .putString(KEY_SENSOR_CODE, config.sensorCode.trim().uppercase())
            .putString(KEY_KNOWN_MAC, config.knownMac.trim().uppercase())
            .apply {
                if (configChanged) {
                    remove(KEY_GLUCOSE_MGDL)
                    remove(KEY_GLUCOSE_RECEIVED_AT_MILLIS)
                    remove(KEY_GLUCOSE_DEX_TIMESTAMP)
                    remove(KEY_GLUCOSE_AGE_SECONDS)
                    remove(KEY_GLUCOSE_SOURCE)
                    remove(KEY_DIRECT_GLUCOSE_MGDL)
                    remove(KEY_DIRECT_GLUCOSE_RECEIVED_AT_MILLIS)
                    remove(KEY_DIRECT_GLUCOSE_DEX_TIMESTAMP)
                    remove(KEY_DIRECT_GLUCOSE_AGE_SECONDS)
                    remove(KEY_DIRECT_GLUCOSE_SOURCE)
                }
            }
            .apply()
        if (configChanged) {
            appendEvent(
                prefs,
                System.currentTimeMillis(),
                "config changed $previousTransmitter/$previousSensor -> $nextTransmitter/$nextSensor; cleared stale glucose",
            )
        }
    }

    fun cacheDetectedMac(context: Context, mac: String) {
        val normalized = mac.trim().uppercase()
        if (normalized.isBlank() || normalized == "?") return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_KNOWN_MAC, normalized)
            .apply()
    }

    fun clearDetectedMac(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_KNOWN_MAC)
            .apply()
    }

    fun loadScanDebug(context: Context): ScanDebugState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return ScanDebugState(
            seenName = prefs.getString(KEY_LAST_SEEN_NAME, "") ?: "",
            seenMac = prefs.getString(KEY_LAST_SEEN_MAC, "") ?: "",
            seenRssi = prefs.getString(KEY_LAST_SEEN_RSSI, "") ?: "",
            lastEvent = prefs.getString(KEY_LAST_EVENT, "") ?: "",
            lastEventAtMillis = prefs.getLong(KEY_LAST_EVENT_AT_MILLIS, 0L),
            lastBondAtMillis = prefs.getLong(KEY_LAST_BOND_AT_MILLIS, 0L),
        )
    }

    fun loadGlucose(context: Context): GlucoseReadingState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val display = loadDisplayGlucose(prefs)
        val direct = loadDirectGlucose(prefs)
        return if (
            direct != null &&
            display.source.startsWith("phone relay", ignoreCase = true)
        ) {
            direct
        } else {
            display
        }
    }

    fun loadDirectGlucose(context: Context): GlucoseReadingState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadDirectGlucose(prefs)?.let { return it }

        val display = loadDisplayGlucose(prefs)
        val displaySource = display.source.lowercase()
        return if (
            display.mgdl != null &&
            !displaySource.startsWith("phone") &&
            !displaySource.startsWith("sync")
        ) {
            display
        } else {
            GlucoseReadingState(null, 0L, 0, 0, "")
        }
    }

    private fun loadDisplayGlucose(
        prefs: android.content.SharedPreferences,
    ): GlucoseReadingState {
        val mgdl = if (prefs.contains(KEY_GLUCOSE_MGDL)) prefs.getInt(KEY_GLUCOSE_MGDL, 0) else null
        return GlucoseReadingState(
            mgdl = mgdl?.takeIf { it in 20..600 },
            receivedAtMillis = prefs.getLong(KEY_GLUCOSE_RECEIVED_AT_MILLIS, 0L),
            dexTimestamp = prefs.getInt(KEY_GLUCOSE_DEX_TIMESTAMP, 0),
            ageSeconds = prefs.getInt(KEY_GLUCOSE_AGE_SECONDS, 0),
            source = prefs.getString(KEY_GLUCOSE_SOURCE, "") ?: "",
        )
    }

    private fun loadDirectGlucose(
        prefs: android.content.SharedPreferences,
    ): GlucoseReadingState? {
        if (!prefs.contains(KEY_DIRECT_GLUCOSE_MGDL)) return null
        if (prefs.getInt(KEY_DIRECT_GLUCOSE_MGDL, 0) !in 20..600) return null
        return GlucoseReadingState(
            mgdl = prefs.getInt(KEY_DIRECT_GLUCOSE_MGDL, 0),
            receivedAtMillis = prefs.getLong(KEY_DIRECT_GLUCOSE_RECEIVED_AT_MILLIS, 0L),
            dexTimestamp = prefs.getInt(KEY_DIRECT_GLUCOSE_DEX_TIMESTAMP, 0),
            ageSeconds = prefs.getInt(KEY_DIRECT_GLUCOSE_AGE_SECONDS, 0),
            source = prefs.getString(KEY_DIRECT_GLUCOSE_SOURCE, "") ?: "",
        )
    }

    fun saveScanDebug(
        context: Context,
        seenName: String,
        seenMac: String,
        seenRssi: Int?,
        lastEvent: String,
    ) {
        val normalizedEvent = lastEvent.lowercase()
        val isBondEvent = "bond" in normalizedEvent ||
            "pairing" in normalizedEvent ||
            "auth=" in normalizedEvent
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_SEEN_NAME, seenName)
            .putString(KEY_LAST_SEEN_MAC, seenMac)
            .putString(KEY_LAST_SEEN_RSSI, seenRssi?.toString() ?: "")
            .putString(KEY_LAST_EVENT, lastEvent)
            .putLong(KEY_LAST_EVENT_AT_MILLIS, now)
            .apply {
                if (isBondEvent) {
                    putLong(KEY_LAST_BOND_AT_MILLIS, now)
                }
            }
            .apply()
        appendEvent(prefs, now, lastEvent)
        ConnectionJournal.record(context, "sensor_event", "detail" to lastEvent.take(220), "rssi" to seenRssi)
    }

    fun saveGlucose(
        context: Context,
        mgdl: Int,
        dexTimestamp: Int,
        ageSeconds: Int,
        source: String,
    ): GlucoseReadingState {
        return saveDirectGlucose(
            context = context,
            mgdl = mgdl,
            dexTimestamp = dexTimestamp,
            ageSeconds = ageSeconds,
            source = source,
        )
    }

    fun saveDirectGlucose(
        context: Context,
        mgdl: Int,
        dexTimestamp: Int,
        ageSeconds: Int,
        source: String,
    ): GlucoseReadingState {
        require(mgdl in 20..600) { "Sensor status code is not glucose" }
        val receivedAtMillis = System.currentTimeMillis()
        val previous = loadDirectGlucose(context).receivedAtMillis
        ConnectionJournal.record(context, "sensor_reading", "gap_ms" to (receivedAtMillis - previous).takeIf { previous > 0 },
            "sample_age_seconds" to ageSeconds)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_DIRECT_GLUCOSE_MGDL, mgdl)
            .putLong(KEY_DIRECT_GLUCOSE_RECEIVED_AT_MILLIS, receivedAtMillis)
            .putInt(KEY_DIRECT_GLUCOSE_DEX_TIMESTAMP, dexTimestamp)
            .putInt(KEY_DIRECT_GLUCOSE_AGE_SECONDS, ageSeconds)
            .putString(KEY_DIRECT_GLUCOSE_SOURCE, source)
            .apply()
        appendEvent(prefs, receivedAtMillis, "direct glucose ${mgdl} mg/dL source=$source dexTs=$dexTimestamp age=${ageSeconds}s")
        saveGlucoseAt(
            context = context,
            mgdl = mgdl,
            receivedAtMillis = receivedAtMillis,
            dexTimestamp = dexTimestamp,
            ageSeconds = ageSeconds,
            source = source,
        )
        return GlucoseReadingState(mgdl, receivedAtMillis, dexTimestamp, ageSeconds, source)
    }

    fun saveGlucoseIfNewer(
        context: Context,
        mgdl: Int,
        receivedAtMillis: Long,
        dexTimestamp: Int,
        ageSeconds: Int,
        source: String,
        freshnessToleranceMillis: Long = 30_000L,
    ): Boolean {
        val incomingAt = receivedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        val current = loadGlucose(context)
        val shouldSave = current.mgdl == null ||
            incomingAt > current.receivedAtMillis + freshnessToleranceMillis ||
            (current.source.startsWith("phone", ignoreCase = true) && incomingAt > current.receivedAtMillis)
        if (!shouldSave) return false

        saveGlucoseAt(
            context = context,
            mgdl = mgdl,
            receivedAtMillis = incomingAt,
            dexTimestamp = dexTimestamp,
            ageSeconds = ageSeconds,
            source = source,
        )
        return true
    }

    fun saveGlucoseAt(
        context: Context,
        mgdl: Int,
        receivedAtMillis: Long,
        dexTimestamp: Int,
        ageSeconds: Int,
        source: String,
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_GLUCOSE_MGDL, mgdl)
            .putLong(KEY_GLUCOSE_RECEIVED_AT_MILLIS, receivedAtMillis)
            .putInt(KEY_GLUCOSE_DEX_TIMESTAMP, dexTimestamp)
            .putInt(KEY_GLUCOSE_AGE_SECONDS, ageSeconds)
            .putString(KEY_GLUCOSE_SOURCE, source)
            .apply()
    }

    private fun appendEvent(
        prefs: android.content.SharedPreferences,
        atMillis: Long,
        event: String,
    ) {
        val cleanEvent = event
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(EVENT_LOG_MAX_EVENT_CHARS)
        if (cleanEvent.isBlank()) return

        val existingLines = prefs.getString(KEY_EVENT_LOG, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
        val lines = (existingLines + "$atMillis $cleanEvent").takeLast(EVENT_LOG_MAX_LINES)
        prefs.edit()
            .putString(KEY_EVENT_LOG, lines.joinToString("\n"))
            .apply()
    }
}
