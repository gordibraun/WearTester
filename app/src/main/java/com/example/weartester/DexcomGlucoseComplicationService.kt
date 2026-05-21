package com.example.weartester

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.data.TimeRange
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import java.time.Instant
import java.util.concurrent.TimeUnit

class DexcomGlucoseComplicationService : ComplicationDataSourceService() {
    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        listener.onComplicationData(buildData(request.complicationType))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return buildData(type, preview = true)
    }

    private fun buildData(type: ComplicationType, preview: Boolean = false): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        val glucose = if (preview) {
            GlucoseReadingState(null, 0L, 0, 0, "preview")
        } else {
            DexcomConfigStore.loadGlucose(this)
        }
        val nowMillis = System.currentTimeMillis()
        val ageMinutes = ElapsedTimeFormatter.elapsedMinutes(glucose.receivedAtMillis, nowMillis)
        val stale = !preview && glucose.mgdl != null && ageMinutes >= STALE_AFTER_MINUTES
        val compactAge = ElapsedTimeFormatter.compactMinutes(glucose.receivedAtMillis, nowMillis)
        val shortText = when {
            stale -> "OLD"
            glucose.mgdl != null -> glucose.mgdl.toString()
            else -> "--"
        }
        val titleText: ComplicationText = when {
            ageMinutes >= 0 -> TimeDifferenceComplicationText.Builder(
                TimeDifferenceStyle.SHORT_SINGLE_UNIT,
                CountUpTimeReference(Instant.ofEpochMilli(glucose.receivedAtMillis)),
            )
                .setMinimumTimeUnit(TimeUnit.MINUTES)
                .build()
            stale -> PlainComplicationText.Builder("${glucose.mgdl} $compactAge").build()
            else -> PlainComplicationText.Builder("Dex").build()
        }
        val description = if (stale) {
            "Устаревшая глюкоза ${glucose.mgdl}, $compactAge назад"
        } else if (glucose.mgdl != null) {
            "Глюкоза ${glucose.mgdl} миллиграмм на децилитр"
        } else {
            "Ожидаю данные Dexcom"
        }
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_TIMER_FOCUS, true)
                .putExtra(MainActivity.EXTRA_AUTO_START_PROBE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val now = Instant.now()
        val validTimeRange = if (preview) {
            TimeRange.ALWAYS
        } else {
            TimeRange.between(now, now.plusSeconds(75))
        }

        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(shortText).build(),
            contentDescription = PlainComplicationText.Builder(description).build(),
        )
            .setTitle(titleText)
            .setValidTimeRange(validTimeRange)
            .setTapAction(openApp)
            .build()
    }

    companion object {
        private const val STALE_AFTER_MINUTES = 10L

        fun requestImmediateUpdate(context: android.content.Context) {
            runCatching {
                androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
                    .create(
                        context,
                        ComponentName(context, DexcomGlucoseComplicationService::class.java),
                    )
                    .requestUpdateAll()
            }
        }
    }
}
