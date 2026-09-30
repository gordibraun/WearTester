package com.example.weartester

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import java.time.Instant

class SensorExpiryComplicationService : ComplicationDataSourceService() {
    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        if (request.complicationType != ComplicationType.LONG_TEXT) { listener.onComplicationData(null); return }
        val now = System.currentTimeMillis()
        val session = SensorSessionStore.load(this)
        // Future labels make the warning independent of a live sensor or phone connection.
        val entries = if (session.startedAt > 0 && session.state in 0..10) {
            (24 downTo 0).mapNotNull { hours ->
                val start = session.expiresAt - hours * 3_600_000L
                val end = if (hours > 0) start + 3_600_000L else start + 365 * 24 * 3_600_000L
                if (end <= now) null else TimelineEntry(TimeInterval(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end)), data(session, start))
            }
        } else emptyList()
        listener.onComplicationDataTimeline(ComplicationDataTimeline(data(session, now), entries))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.LONG_TEXT) data(SensorLifetime(), System.currentTimeMillis()) else null

    private fun data(session: SensorLifetime, now: Long): ComplicationData {
        val label = session.label(now)
        val open = PendingIntent.getActivity(this, 24, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return LongTextComplicationData.Builder(PlainComplicationText.Builder(label.text).build(), PlainComplicationText.Builder(label.text).build())
            .setTitle(PlainComplicationText.Builder(if (label.urgent) "!" else "").build())
            .setTapAction(open).build()
    }

    companion object {
        fun requestUpdate(context: Context) {
            ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, SensorExpiryComplicationService::class.java)).requestUpdateAll()
        }
    }
}
