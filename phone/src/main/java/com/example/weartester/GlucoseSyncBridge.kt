package com.example.weartester

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object GlucoseSyncBridge {
    private const val TAG = "GlucoseSyncBridge"
    private const val PREFS_NAME = "watch_glucose_bridge"
    private const val KEY_LAST_MGDL = "last_mgdl"
    private const val KEY_LAST_TIMESTAMP = "last_timestamp"
    private const val KEY_LAST_SOURCE = "last_source"
    private const val KEY_LAST_SENT_TO_XDRIP_AT = "last_sent_to_xdrip_at"
    private const val KEY_LAST_XDRIP_ACTION = "last_xdrip_action"
    private const val KEY_LAST_FORWARDED_MGDL = "last_forwarded_mgdl"
    private const val KEY_LAST_FORWARDED_TIMESTAMP = "last_forwarded_timestamp"

    const val PATH_WATCH_GLUCOSE = "/weartester/watch_glucose"
    const val KEY_MGDL = "mgdl"
    const val KEY_TIMESTAMP = "timestamp"
    const val KEY_DEX_TIMESTAMP = "dexTimestamp"
    const val KEY_AGE_SECONDS = "ageSeconds"
    const val KEY_SOURCE = "source"
    const val KEY_TRANSMITTER_ID = "transmitterId"

    private const val XDRIP_PACKAGE = "com.eveningoutpost.dexdrip"
    private const val ACTION_NIGHTSCOUT_NEW_SGV = "info.nightscout.client.NEW_SGV"
    private const val ACTION_XDRIP_WATCH_GLUCOSE = "com.example.weartester.action.WATCH_GLUCOSE"
    private const val EXTRA_SGV = "sgv"
    private const val EXTRA_SGVS = "sgvs"
    private const val EXTRA_MGDL = "mgdl"
    private const val EXTRA_TIMESTAMP = "timestamp"
    private const val EXTRA_SOURCE = "source"
    private const val EXTRA_TRANSMITTER_ID = "transmitterId"
    private const val EXTRA_DEX_TIMESTAMP = "dexTimestamp"
    private const val EXTRA_AGE_SECONDS = "ageSeconds"
    private const val MIN_GLUCOSE_MGDL = 20
    private const val MAX_GLUCOSE_MGDL = 400

    // AAPS takes glucose from xDrip's own broadcast; the same broadcast, addressed to AAPS alone,
    // carries the watch's reading there directly. xDrip then need not collect from the
    // transmitter (which fights the watch for every reading) nor relay anything (which its
    // follower mode does not do, 4 Oct): whatever source it is set to, AAPS is fed.
    private const val AAPS_PACKAGE = "info.nightscout.androidaps"
    private const val ACTION_XDRIP_BG_ESTIMATE = "com.eveningoutpost.dexdrip.BgEstimate"
    private const val XDRIP_EXTRA_BG_ESTIMATE = "com.eveningoutpost.dexdrip.Extras.BgEstimate"
    private const val XDRIP_EXTRA_RAW = "com.eveningoutpost.dexdrip.Extras.Raw"
    private const val XDRIP_EXTRA_TIME = "com.eveningoutpost.dexdrip.Extras.Time"
    private const val XDRIP_EXTRA_SLOPE = "com.eveningoutpost.dexdrip.Extras.BgSlope"
    private const val XDRIP_EXTRA_SLOPE_NAME = "com.eveningoutpost.dexdrip.Extras.BgSlopeName"
    private const val XDRIP_EXTRA_SOURCE_INFO = "com.eveningoutpost.dexdrip.Extras.SourceInfo"
    private const val XDRIP_EXTRA_SOURCE_DESC = "com.eveningoutpost.dexdrip.Extras.SourceDesc"
    /** What AAPS calls a G6 read natively through xDrip; the same word keeps its handling of the value unchanged. */
    private const val SOURCE_INFO_G6_NATIVE = "G6 Native"
    private const val SOURCE_DESC_WATCH = "WearTester watch bridge"
    private const val KEY_LAST_SENT_TO_AAPS_AT = "last_sent_to_aaps_at"

    fun acceptWatchGlucose(
        context: Context,
        mgdl: Int,
        timestamp: Long,
        source: String,
        transmitterId: String,
        dexTimestamp: Int,
        ageSeconds: Int,
        transport: String,
    ) {
        if (mgdl !in MIN_GLUCOSE_MGDL..MAX_GLUCOSE_MGDL) {
            Log.w(TAG, "Ignoring implausible watch glucose: $mgdl")
            return
        }
        val readingAt = normalizeTimestamp(timestamp)
        if (readingAt <= 0L) {
            Log.w(TAG, "Ignoring watch glucose with invalid timestamp: $timestamp")
            return
        }
        val sampleAt = normalizeSampleTimestamp(readingAt, ageSeconds)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_LAST_MGDL, mgdl)
            .putLong(KEY_LAST_TIMESTAMP, sampleAt)
            .putString(KEY_LAST_SOURCE, "$source; $transport")
            .apply()

        val previousMgdl = prefs.getInt(KEY_LAST_FORWARDED_MGDL, -1)
        val previousAt = prefs.getLong(KEY_LAST_FORWARDED_TIMESTAMP, 0L)
        val duplicateForward = previousMgdl == mgdl && previousAt == sampleAt
        if (duplicateForward) {
            Log.d(TAG, "Watch glucose already forwarded to xDrip: $mgdl @ $sampleAt")
            return
        }

        forwardToXdrip(
            context = context,
            mgdl = mgdl,
            timestamp = sampleAt,
            source = source,
            transmitterId = transmitterId,
            dexTimestamp = dexTimestamp,
            ageSeconds = ageSeconds,
        )
        forwardToAaps(context, mgdl, sampleAt, previousMgdl.takeIf { it > 0 }, previousAt)
        prefs.edit()
            .putInt(KEY_LAST_FORWARDED_MGDL, mgdl)
            .putLong(KEY_LAST_FORWARDED_TIMESTAMP, sampleAt)
            .putLong(KEY_LAST_SENT_TO_XDRIP_AT, System.currentTimeMillis())
            .putLong(KEY_LAST_SENT_TO_AAPS_AT, System.currentTimeMillis())
            .putString(KEY_LAST_XDRIP_ACTION, ACTION_NIGHTSCOUT_NEW_SGV)
            .apply()
    }

    /** The reading to AAPS directly, in the form of xDrip's own broadcast, addressed to AAPS alone. */
    private fun forwardToAaps(context: Context, mgdl: Int, timestamp: Long, previousMgdl: Int?, previousAt: Long) {
        val intent = Intent(ACTION_XDRIP_BG_ESTIMATE)
            .setPackage(AAPS_PACKAGE)
            .putExtra(XDRIP_EXTRA_BG_ESTIMATE, mgdl.toDouble())
            .putExtra(XDRIP_EXTRA_RAW, mgdl.toDouble())
            .putExtra(XDRIP_EXTRA_TIME, timestamp)
            .putExtra(XDRIP_EXTRA_SOURCE_INFO, SOURCE_INFO_G6_NATIVE)
            .putExtra(XDRIP_EXTRA_SOURCE_DESC, SOURCE_DESC_WATCH)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        // The trend from the previous forwarded reading, in Dexcom's words, when it is recent enough to mean anything.
        if (previousMgdl != null && previousAt > 0L && timestamp - previousAt in 60_000L..(15 * 60_000L)) {
            val perMinute = (mgdl - previousMgdl) / ((timestamp - previousAt) / 60_000.0)
            intent.putExtra(XDRIP_EXTRA_SLOPE, perMinute / 60_000.0)
            intent.putExtra(XDRIP_EXTRA_SLOPE_NAME, slopeName(perMinute))
        }
        context.sendBroadcast(intent)
        ConnectionJournal.record(context, "glucose_forwarded_to_aaps_broadcast", "sample_at" to timestamp)
        Log.i(TAG, "Forwarded watch glucose to AAPS directly: $mgdl @ $timestamp")
    }

    /** Dexcom's trend names by the usual per-minute thresholds. */
    private fun slopeName(perMinute: Double): String = when {
        perMinute >= 3.5  -> "DoubleUp"
        perMinute >= 2.0  -> "SingleUp"
        perMinute >= 1.0  -> "FortyFiveUp"
        perMinute > -1.0  -> "Flat"
        perMinute > -2.0  -> "FortyFiveDown"
        perMinute > -3.5  -> "SingleDown"
        else              -> "DoubleDown"
    }

    fun lastWatchStatus(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mgdl = prefs.getInt(KEY_LAST_MGDL, -1).takeIf { it > 0 }?.toString() ?: "нет"
        val timestamp = prefs.getLong(KEY_LAST_TIMESTAMP, 0L)
        val source = prefs.getString(KEY_LAST_SOURCE, "")?.ifBlank { "нет" } ?: "нет"
        val sentAt = prefs.getLong(KEY_LAST_SENT_TO_XDRIP_AT, 0L)
        val action = prefs.getString(KEY_LAST_XDRIP_ACTION, "")?.ifBlank { "нет" } ?: "нет"
        val sentToAaps = prefs.getLong(KEY_LAST_SENT_TO_AAPS_AT, 0L)
        return "Последнее с часов: $mgdl mg/dL\nИсточник: $source\nВремя глюкозы: ${formatAge(timestamp)}\nПередано в xDrip: ${formatAge(sentAt)}\nПередано в AAPS напрямую: ${formatAge(sentToAaps)}\nКанал xDrip: $action"
    }

    private fun forwardToXdrip(
        context: Context,
        mgdl: Int,
        timestamp: Long,
        source: String,
        transmitterId: String,
        dexTimestamp: Int,
        ageSeconds: Int,
    ) {
        val sgv = buildNightscoutSgv(
            mgdl = mgdl,
            timestamp = timestamp,
            source = source,
            transmitterId = transmitterId,
            dexTimestamp = dexTimestamp,
            ageSeconds = ageSeconds,
        )
        val nightscoutIntent = Intent(ACTION_NIGHTSCOUT_NEW_SGV)
            .setPackage(XDRIP_PACKAGE)
            .putExtra(EXTRA_SGV, sgv.toString())
            .putExtra(EXTRA_SGVS, JSONArray().put(sgv).toString())
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        context.sendBroadcast(nightscoutIntent)

        // Keep the patched-xDrip action as a harmless fallback for development builds.
        val legacyIntent = Intent(ACTION_XDRIP_WATCH_GLUCOSE)
            .setPackage(XDRIP_PACKAGE)
            .putExtra(EXTRA_MGDL, mgdl)
            .putExtra(EXTRA_TIMESTAMP, timestamp)
            .putExtra(EXTRA_SOURCE, source)
            .putExtra(EXTRA_TRANSMITTER_ID, transmitterId)
            .putExtra(EXTRA_DEX_TIMESTAMP, dexTimestamp)
            .putExtra(EXTRA_AGE_SECONDS, ageSeconds)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        context.sendBroadcast(legacyIntent)
        ConnectionJournal.record(context, "glucose_forwarded_to_xdrip_broadcast", "sample_at" to timestamp)
        Log.i(TAG, "Forwarded watch glucose to xDrip via Nightscout SGV: $mgdl @ $timestamp source=$source")
    }

    private fun buildNightscoutSgv(
        mgdl: Int,
        timestamp: Long,
        source: String,
        transmitterId: String,
        dexTimestamp: Int,
        ageSeconds: Int,
    ): JSONObject {
        val raw = mgdl * 1000.0
        return JSONObject()
            .put("type", "sgv")
            .put("device", "WearTester")
            .put("enteredBy", "WearTester")
            .put("source", source)
            .put("transmitterId", transmitterId)
            .put("dexTimestamp", dexTimestamp)
            .put("ageSeconds", ageSeconds)
            .put("mgdl", mgdl.toDouble())
            .put("sgv", mgdl.toDouble())
            .put("mills", timestamp.toDouble())
            .put("date", timestamp)
            .put("direction", "Flat")
            .put("filtered", raw)
            .put("unfiltered", raw)
            .put("noise", 1.0)
    }

    private fun normalizeTimestamp(timestamp: Long): Long {
        if (timestamp <= 0L) return System.currentTimeMillis()
        return if (timestamp < 10_000_000_000L) timestamp * 1000L else timestamp
    }

    private fun normalizeSampleTimestamp(receivedAt: Long, ageSeconds: Int): Long {
        val ageMillis = ageSeconds.toLong() * 1000L
        return if (ageSeconds in 1..900) {
            (receivedAt - ageMillis).coerceAtLeast(1L)
        } else {
            receivedAt
        }
    }

    private fun formatAge(timestamp: Long): String {
        if (timestamp <= 0L) return "нет"
        val ageSeconds = ((System.currentTimeMillis() - timestamp) / 1000L).coerceAtLeast(0L)
        return when {
            ageSeconds < 60L -> "${ageSeconds}с назад"
            ageSeconds < 3600L -> "${ageSeconds / 60L}м ${ageSeconds % 60L}с назад"
            else -> "${ageSeconds / 3600L}ч ${(ageSeconds % 3600L) / 60L}м назад"
        }
    }
}
