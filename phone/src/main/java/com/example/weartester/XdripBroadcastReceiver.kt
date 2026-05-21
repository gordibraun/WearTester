package com.example.weartester

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

class XdripBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: Bundle.EMPTY
        val parsed = readGlucose(extras)
        if (parsed == null) {
            Log.d(TAG, "No glucose in broadcast ${intent.action}: ${extras.keySet().joinToString()}")
            return
        }

        PhoneGlucoseSender.send(
            context = context.applicationContext,
            mgdl = parsed.mgdl,
            timestamp = parsed.timestamp,
            source = "xdrip-broadcast:${intent.action.orEmpty()}",
        )
    }

    private fun readGlucose(extras: Bundle): ParsedGlucose? {
        val directValue = readNumber(
            extras,
            "com.eveningoutpost.dexdrip.Extras.BgEstimate",
            "com.eveningoutpost.dexdrip.Extras.Raw",
            "sgv",
            "glucose",
            "mgdl",
        )
        if (directValue != null) {
            val mgdl = directValue.roundToInt().takeIf { it in MIN_GLUCOSE_MGDL..MAX_GLUCOSE_MGDL } ?: return null
            return ParsedGlucose(mgdl, readTimestamp(extras))
        }

        return parseNightscoutPayload(extras, "sgvs")
            ?: parseNightscoutPayload(extras, "sgv")
            ?: parseNightscoutPayload(extras, "entries")
    }

    private fun parseNightscoutPayload(extras: Bundle, key: String): ParsedGlucose? {
        if (!extras.containsKey(key)) return null
        val text = extras.get(key)?.toString()?.trim().orEmpty()
        if (text.isBlank()) return null

        return runCatching {
            val firstEntry = if (text.startsWith("[")) {
                JSONArray(text).optJSONObject(0)
            } else {
                JSONObject(text)
            } ?: return null
            val value = firstNonZeroDouble(firstEntry, "sgv", "mgdl", "glucose", "value") ?: return null
            val mgdl = value.roundToInt().takeIf { it in MIN_GLUCOSE_MGDL..MAX_GLUCOSE_MGDL } ?: return null
            val timestamp = normalizeTimestamp(
                firstNonZeroLong(firstEntry, "date", "mills", "timestamp", "time")
                    ?: readTimestamp(extras),
            )
            ParsedGlucose(mgdl, timestamp)
        }.getOrElse {
            Log.d(TAG, "Failed to parse $key payload: $text", it)
            null
        }
    }

    private fun firstNonZeroDouble(json: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            if (!json.has(key)) continue
            val value = json.optDouble(key, Double.NaN)
            if (!value.isNaN() && value > 0.0) return value
        }
        return null
    }

    private fun firstNonZeroLong(json: JSONObject, vararg keys: String): Long? {
        for (key in keys) {
            if (!json.has(key)) continue
            val value = json.optLong(key, 0L)
            if (value > 0L) return value
        }
        return null
    }

    private fun readNumber(extras: Bundle, vararg keys: String): Double? {
        for (key in keys) {
            if (!extras.containsKey(key)) continue
            val value = extras.get(key)
            return when (value) {
                is Double -> value
                is Float -> value.toDouble()
                is Int -> value.toDouble()
                is Long -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            }
        }
        return null
    }

    private fun readTimestamp(extras: Bundle): Long {
        return readLong(
            extras,
            "com.eveningoutpost.dexdrip.Extras.TIMESTAMP",
            "com.eveningoutpost.dexdrip.Extras.Time",
            "timestamp",
            "time",
            "date",
        )
    }

    private fun readLong(extras: Bundle, vararg keys: String): Long {
        for (key in keys) {
            if (!extras.containsKey(key)) continue
            val value = extras.get(key)
            val parsed = when (value) {
                is Long -> value
                is Int -> value.toLong()
                is Double -> value.toLong()
                is Float -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            }
            if (parsed != null && parsed > 0L) return normalizeTimestamp(parsed)
        }
        return System.currentTimeMillis()
    }

    private fun normalizeTimestamp(timestamp: Long): Long {
        if (timestamp <= 0L) return System.currentTimeMillis()
        return if (timestamp < 10_000_000_000L) timestamp * 1000L else timestamp
    }

    companion object {
        private const val TAG = "WearTesterRelay"
        private const val MIN_GLUCOSE_MGDL = 20
        private const val MAX_GLUCOSE_MGDL = 400
    }

    private data class ParsedGlucose(
        val mgdl: Int,
        val timestamp: Long,
    )
}
