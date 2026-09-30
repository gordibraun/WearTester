package com.example.weartester

import android.content.Context
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

    private const val MIN_GLUCOSE_MGDL = 20
    private const val MAX_GLUCOSE_MGDL = 400
}
