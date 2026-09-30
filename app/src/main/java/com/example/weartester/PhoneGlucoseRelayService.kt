package com.example.weartester

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

class PhoneGlucoseRelayService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.use { events ->
            for (event in events) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val item = event.dataItem
                if (item.uri.path != PATH_GLUCOSE) continue
                val map = DataMapItem.fromDataItem(item).dataMap
                acceptGlucose(
                    context = this,
                    mgdl = map.getInt(KEY_MGDL, -1),
                    receivedAtMillis = map.getLong(KEY_TIMESTAMP, 0L),
                    source = map.getString(KEY_SOURCE, "phone-data"),
                )
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == ConnectionMonitor.ACK) {
            ConnectionMonitor.acceptAck(this, messageEvent.data)
            return
        }
        if (messageEvent.path != PATH_GLUCOSE) return
        runCatching {
            val json = JSONObject(String(messageEvent.data, Charsets.UTF_8))
            acceptGlucose(
                context = this,
                mgdl = json.optInt(KEY_MGDL, -1),
                receivedAtMillis = json.optLong(KEY_TIMESTAMP, 0L),
                source = json.optString(KEY_SOURCE, "phone-message"),
            )
        }.onFailure {
            Log.w(TAG, "Failed to parse phone glucose message", it)
        }
    }

    companion object {
        const val ENABLED = false
        const val PATH_GLUCOSE = "/weartester/glucose"
        const val KEY_MGDL = "mgdl"
        const val KEY_TIMESTAMP = "timestamp"
        const val KEY_SOURCE = "source"
        private const val MIN_GLUCOSE_MGDL = 20
        private const val MAX_GLUCOSE_MGDL = 400
        private const val TAG = "PhoneRelay"

        fun pullLatest(context: Context, reason: String) {
            if (!ENABLED) return
            Wearable.getDataClient(context).dataItems
                .addOnSuccessListener { items ->
                    items.use { dataItems ->
                        var matched = false
                        for (item in dataItems) {
                            if (item.uri.path != PATH_GLUCOSE) continue
                            matched = true
                            val map = DataMapItem.fromDataItem(item).dataMap
                            acceptGlucose(
                                context = context,
                                mgdl = map.getInt(KEY_MGDL, -1),
                                receivedAtMillis = map.getLong(KEY_TIMESTAMP, 0L),
                                source = "${map.getString(KEY_SOURCE, "phone-data")}; pulled=$reason",
                            )
                        }
                        if (!matched) {
                            Log.d(TAG, "No phone glucose DataItem available while pulling: $reason")
                        }
                    }
                }
                .addOnFailureListener {
                    Log.w(TAG, "Failed to pull phone glucose DataItem: $reason", it)
                }
        }

        fun acceptGlucose(
            context: Context,
            mgdl: Int,
            receivedAtMillis: Long,
            source: String,
        ) {
            if (!ENABLED) {
                Log.d(TAG, "Phone relay disabled; ignoring glucose from $source")
                return
            }
            if (mgdl !in MIN_GLUCOSE_MGDL..MAX_GLUCOSE_MGDL) {
                Log.w(TAG, "Ignoring implausible phone glucose: $mgdl")
                return
            }

            val saved = DexcomConfigStore.saveGlucoseIfNewer(
                context = context,
                mgdl = mgdl,
                receivedAtMillis = receivedAtMillis,
                dexTimestamp = 0,
                ageSeconds = 0,
                source = "phone relay: $source",
            )
            if (!saved) {
                Log.d(TAG, "Phone relay glucose $mgdl ignored because watch data is newer")
                return
            }

            Log.i(TAG, "PHONE RELAY GLUCOSE $mgdl mg/dL source=$source")
            DexcomGlucoseComplicationService.requestImmediateUpdate(context)
        }
    }
}
