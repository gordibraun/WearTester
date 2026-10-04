package com.example.weartester

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

object GlucoseSyncBridge {
    private const val TAG = "GlucoseSyncBridge"

    const val PATH_WATCH_GLUCOSE = "/weartester/watch_glucose"
    const val KEY_MGDL = "mgdl"
    const val KEY_TIMESTAMP = "timestamp"
    const val KEY_DEX_TIMESTAMP = "dexTimestamp"
    const val KEY_AGE_SECONDS = "ageSeconds"
    const val KEY_SOURCE = "source"
    const val KEY_TRANSMITTER_ID = "transmitterId"

    fun sendWatchGlucose(
        context: Context,
        reading: GlucoseReadingState,
        transmitterId: String,
    ) {
        val mgdl = reading.mgdl
        if (mgdl == null || mgdl !in MIN_GLUCOSE_MGDL..MAX_GLUCOSE_MGDL) {
            Log.w(TAG, "Skip implausible watch glucose for sync: $mgdl")
            return
        }
        val readingAt = reading.receivedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        val source = "watch-dexcom:${reading.source}"

        handToPumpController(context, mgdl, readingAt, reading.ageSeconds)

        val request = PutDataMapRequest.create(PATH_WATCH_GLUCOSE).apply {
            dataMap.putInt(KEY_MGDL, mgdl)
            dataMap.putLong(KEY_TIMESTAMP, readingAt)
            dataMap.putInt(KEY_DEX_TIMESTAMP, reading.dexTimestamp)
            dataMap.putInt(KEY_AGE_SECONDS, reading.ageSeconds)
            dataMap.putString(KEY_SOURCE, source)
            dataMap.putString(KEY_TRANSMITTER_ID, transmitterId)
            dataMap.putLong("sentAt", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        Wearable.getDataClient(context).putDataItem(request)
            .addOnSuccessListener {
                Log.i(TAG, "Watch glucose DataItem queued for phone: $mgdl")
            }
            .addOnFailureListener {
                Log.w(TAG, "Failed to queue watch glucose DataItem", it)
            }

        val payload = JSONObject()
            .put(KEY_MGDL, mgdl)
            .put(KEY_TIMESTAMP, readingAt)
            .put(KEY_DEX_TIMESTAMP, reading.dexTimestamp)
            .put(KEY_AGE_SECONDS, reading.ageSeconds)
            .put(KEY_SOURCE, source)
            .put("sentAt", System.currentTimeMillis())
            .put(KEY_TRANSMITTER_ID, transmitterId)
            .toString()
            .toByteArray(Charsets.UTF_8)

        Wearable.getNodeClient(context).connectedNodes
            .addOnSuccessListener { nodes ->
                nodes.forEach { node ->
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, PATH_WATCH_GLUCOSE, payload)
                        .addOnSuccessListener {
                            Log.i(TAG, "Watch glucose message sent to ${node.displayName}: $mgdl")
                            ConnectionJournal.record(context, "glucose_message_queued", "reading_at" to readingAt)
                        }
                        .addOnFailureListener {
                            Log.w(TAG, "Failed to send watch glucose message to ${node.displayName}", it)
                            ConnectionJournal.record(context, "glucose_message_failed", "error" to it.javaClass.simpleName)
                        }
                }
            }
            .addOnFailureListener {
                Log.w(TAG, "Failed to list connected phone nodes", it)
            }
    }

    /**
     * Hand the reading to the pump controller on this watch. It keeps basal safe by itself when
     * the phone is away, and for that it needs glucose without the phone in between.
     *
     * Addressed to that one app and no other, and that app accepts it only from an app signed
     * with the same key. If the controller is not installed, nothing happens.
     */
    private fun handToPumpController(context: Context, mgdl: Int, receivedAt: Long, ageSeconds: Int) {
        runCatching {
            context.sendBroadcast(
                Intent(CONTROLLER_GLUCOSE_ACTION)
                    .setComponent(ComponentName(CONTROLLER_PACKAGE, CONTROLLER_GLUCOSE_RECEIVER))
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra("mgdl", mgdl)
                    .putExtra("timestamp", receivedAt)
                    .putExtra("ageSeconds", ageSeconds)
            )
        }.onFailure { Log.w(TAG, "Reading not handed to the pump controller", it) }
    }

    private const val CONTROLLER_PACKAGE = "app.aaps.combobench.manual"
    private const val CONTROLLER_GLUCOSE_RECEIVER = "app.aaps.combobench.controller.ControllerGlucoseReceiver"
    private const val CONTROLLER_GLUCOSE_ACTION = "app.aaps.combo.action.GLUCOSE"

    private const val MIN_GLUCOSE_MGDL = 20
    private const val MAX_GLUCOSE_MGDL = 400
}
