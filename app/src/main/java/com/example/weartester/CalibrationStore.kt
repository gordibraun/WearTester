package com.example.weartester

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The one calibration waiting for the sensor, and the last few verdicts. The collector takes the
 * pending one at the next contact, right after the glucose packet, and records what the sensor
 * said; the screen reads both.
 */
object CalibrationStore {

    private const val PREFS = "calibration"
    private const val KEY_PENDING_MGDL = "pending_mgdl"
    private const val KEY_PENDING_AT = "pending_at"
    private const val KEY_PENDING_QUEUED_AT = "pending_queued_at"
    private const val KEY_HISTORY = "history"
    private const val KEY_LAST_SENT_AT = "last_sent_at"
    private const val HISTORY_MAX = 12

    data class Record(val mgdl: Int, val atEpochMs: Long, val sentAtEpochMs: Long?, val code: Int?, val text: String)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pending(context: Context): CalibrationPolicy.Request? {
        val p = prefs(context)
        val mgdl = p.getInt(KEY_PENDING_MGDL, 0)
        val at = p.getLong(KEY_PENDING_AT, 0L)
        return if (mgdl > 0 && at > 0) CalibrationPolicy.Request(mgdl, at) else null
    }

    fun pendingSinceEpochMs(context: Context): Long = prefs(context).getLong(KEY_PENDING_QUEUED_AT, 0L)

    @Synchronized fun queue(context: Context, request: CalibrationPolicy.Request, nowEpochMs: Long) {
        prefs(context).edit()
            .putInt(KEY_PENDING_MGDL, request.mgdl).putLong(KEY_PENDING_AT, request.atEpochMs).putLong(KEY_PENDING_QUEUED_AT, nowEpochMs)
            .apply()
        ConnectionJournal.record(context, "calibration_queued", "mgdl" to request.mgdl, "at" to request.atEpochMs)
    }

    @Synchronized fun cancel(context: Context, why: String) {
        val had = pending(context) ?: return
        prefs(context).edit().remove(KEY_PENDING_MGDL).remove(KEY_PENDING_AT).remove(KEY_PENDING_QUEUED_AT).apply()
        ConnectionJournal.record(context, "calibration_cancelled", "mgdl" to had.mgdl, "why" to why)
    }

    /** The pending request leaves the queue the moment it is handed to the sensor. */
    @Synchronized fun take(context: Context): CalibrationPolicy.Request? {
        val request = pending(context) ?: return null
        prefs(context).edit().remove(KEY_PENDING_MGDL).remove(KEY_PENDING_AT).remove(KEY_PENDING_QUEUED_AT).apply()
        return request
    }

    @Synchronized fun recordSent(context: Context, request: CalibrationPolicy.Request, nowEpochMs: Long) {
        prefs(context).edit().putLong(KEY_LAST_SENT_AT, nowEpochMs).apply()
        append(context, Record(request.mgdl, request.atEpochMs, nowEpochMs, null, "отправлена, жду ответ"))
        ConnectionJournal.record(context, "calibration_sent", "mgdl" to request.mgdl, "at" to request.atEpochMs)
    }

    /** The sensor's verdict replaces the «жду ответ» line of the last sent request. */
    @Synchronized fun recordResult(context: Context, result: CalibrationPolicy.Result, nowEpochMs: Long) {
        val records = history(context).toMutableList()
        val index = records.indexOfFirst { it.code == null }
        val updated = if (index >= 0) records[index].copy(code = result.code, text = result.text)
        else Record(0, nowEpochMs, nowEpochMs, result.code, result.text)
        if (index >= 0) records[index] = updated else records.add(0, updated)
        save(context, records)
        ConnectionJournal.record(context, "calibration_result", "code" to result.code, "accepted" to result.accepted, "text" to result.text)
    }

    @Synchronized fun recordDropped(context: Context, request: CalibrationPolicy.Request, why: String, nowEpochMs: Long) {
        append(context, Record(request.mgdl, request.atEpochMs, null, -1, why))
        ConnectionJournal.record(context, "calibration_dropped", "mgdl" to request.mgdl, "why" to why)
    }

    fun lastSentAtEpochMs(context: Context): Long? = prefs(context).getLong(KEY_LAST_SENT_AT, 0L).takeIf { it > 0 }

    fun sentLast24h(context: Context, nowEpochMs: Long): Int =
        history(context).count { it.sentAtEpochMs != null && nowEpochMs - it.sentAtEpochMs < 24 * 3600_000L }

    fun history(context: Context): List<Record> = runCatching {
        val array = JSONArray(prefs(context).getString(KEY_HISTORY, "[]") ?: "[]")
        List(array.length()) { i ->
            val j = array.getJSONObject(i)
            Record(j.getInt("mgdl"), j.getLong("at"), if (j.isNull("sentAt")) null else j.getLong("sentAt"), if (j.isNull("code")) null else j.getInt("code"), j.optString("text"))
        }
    }.getOrDefault(emptyList())

    private fun append(context: Context, record: Record) {
        save(context, (listOf(record) + history(context)).take(HISTORY_MAX))
    }

    private fun save(context: Context, records: List<Record>) {
        val array = JSONArray()
        records.forEach { r ->
            array.put(JSONObject().put("mgdl", r.mgdl).put("at", r.atEpochMs).put("sentAt", r.sentAtEpochMs ?: JSONObject.NULL).put("code", r.code ?: JSONObject.NULL).put("text", r.text))
        }
        prefs(context).edit().putString(KEY_HISTORY, array.toString()).apply()
    }
}
