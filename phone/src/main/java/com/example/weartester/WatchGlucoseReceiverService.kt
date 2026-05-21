package com.example.weartester

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

class WatchGlucoseReceiverService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.use { events ->
            for (event in events) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val item = event.dataItem
                if (item.uri.path != GlucoseSyncBridge.PATH_WATCH_GLUCOSE) continue
                val map = DataMapItem.fromDataItem(item).dataMap
                GlucoseSyncBridge.acceptWatchGlucose(
                    context = applicationContext,
                    mgdl = map.getInt(GlucoseSyncBridge.KEY_MGDL, -1),
                    timestamp = map.getLong(GlucoseSyncBridge.KEY_TIMESTAMP, 0L),
                    source = map.getString(GlucoseSyncBridge.KEY_SOURCE, "watch-data"),
                    transmitterId = map.getString(GlucoseSyncBridge.KEY_TRANSMITTER_ID, ""),
                    dexTimestamp = map.getInt(GlucoseSyncBridge.KEY_DEX_TIMESTAMP, 0),
                    ageSeconds = map.getInt(GlucoseSyncBridge.KEY_AGE_SECONDS, 0),
                    transport = "data-item",
                )
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != GlucoseSyncBridge.PATH_WATCH_GLUCOSE) return
        runCatching {
            val json = JSONObject(String(messageEvent.data, Charsets.UTF_8))
            GlucoseSyncBridge.acceptWatchGlucose(
                context = applicationContext,
                mgdl = json.optInt(GlucoseSyncBridge.KEY_MGDL, -1),
                timestamp = json.optLong(GlucoseSyncBridge.KEY_TIMESTAMP, 0L),
                source = json.optString(GlucoseSyncBridge.KEY_SOURCE, "watch-message"),
                transmitterId = json.optString(GlucoseSyncBridge.KEY_TRANSMITTER_ID, ""),
                dexTimestamp = json.optInt(GlucoseSyncBridge.KEY_DEX_TIMESTAMP, 0),
                ageSeconds = json.optInt(GlucoseSyncBridge.KEY_AGE_SECONDS, 0),
                transport = "message",
            )
        }.onFailure {
            Log.w(TAG, "Failed to parse watch glucose message", it)
        }
    }

    companion object {
        private const val TAG = "GlucoseSyncBridge"
    }
}
