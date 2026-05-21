package com.example.weartester

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object PhoneGlucoseSender {
    private const val TAG = "WearTesterRelay"
    private const val PREFS_NAME = "phone_relay"
    private const val KEY_LAST_MGDL = "last_mgdl"
    private const val KEY_LAST_TIMESTAMP = "last_timestamp"
    private const val KEY_LAST_SOURCE = "last_source"
    private const val KEY_LAST_SENT_AT = "last_sent_at"

    fun send(context: Context, mgdl: Int, timestamp: Long, source: String) {
        if (mgdl !in MIN_GLUCOSE_MGDL..MAX_GLUCOSE_MGDL) {
            Log.w(TAG, "Ignoring implausible glucose $mgdl from $source")
            return
        }
        val readingAt = timestamp.takeIf { it > 0L } ?: System.currentTimeMillis()
        saveLast(context, mgdl, readingAt, source)

        val request = PutDataMapRequest.create(PATH_GLUCOSE).apply {
            dataMap.putInt(KEY_MGDL, mgdl)
            dataMap.putLong(KEY_TIMESTAMP, readingAt)
            dataMap.putString(KEY_SOURCE, source)
            dataMap.putLong("sentAt", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        Wearable.getDataClient(context).putDataItem(request)
            .addOnSuccessListener {
                Log.i(TAG, "DataItem glucose sent: $mgdl source=$source")
            }
            .addOnFailureListener {
                Log.w(TAG, "Failed to send glucose DataItem", it)
            }

        val payload = JSONObject()
            .put(KEY_MGDL, mgdl)
            .put(KEY_TIMESTAMP, readingAt)
            .put(KEY_SOURCE, source)
            .toString()
            .toByteArray(Charsets.UTF_8)

        Wearable.getNodeClient(context).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    Log.w(TAG, "No connected Wear nodes for immediate glucose message")
                }
                nodes.forEach { node ->
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, PATH_GLUCOSE, payload)
                        .addOnSuccessListener {
                            Log.i(TAG, "Message glucose sent to ${node.displayName}: $mgdl source=$source")
                        }
                        .addOnFailureListener {
                            Log.w(TAG, "Failed to send glucose message to ${node.displayName}", it)
                        }
                }
            }
            .addOnFailureListener {
                Log.w(TAG, "Failed to list Wear nodes", it)
            }

        if (UDP_FALLBACK_ENABLED) {
            sendUdpFallback(context, mgdl, readingAt, source)
        }
    }

    fun lastStatus(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mgdl = prefs.getInt(KEY_LAST_MGDL, -1).takeIf { it > 0 }?.toString() ?: "нет"
        val timestamp = prefs.getLong(KEY_LAST_TIMESTAMP, 0L)
        val source = prefs.getString(KEY_LAST_SOURCE, "")?.ifBlank { "нет" } ?: "нет"
        val sentAt = prefs.getLong(KEY_LAST_SENT_AT, 0L)
        return "Последнее relay: $mgdl mg/dL\nИсточник: $source\nВремя глюкозы: ${formatAge(timestamp)}\nОтправлено: ${formatAge(sentAt)}"
    }

    private fun saveLast(context: Context, mgdl: Int, timestamp: Long, source: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_MGDL, mgdl)
            .putLong(KEY_LAST_TIMESTAMP, timestamp)
            .putString(KEY_LAST_SOURCE, source)
            .putLong(KEY_LAST_SENT_AT, System.currentTimeMillis())
            .apply()
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

    private fun sendUdpFallback(context: Context, mgdl: Int, timestamp: Long, source: String) {
        Thread {
            runCatching {
                val json = JSONObject()
                    .put(KEY_MGDL, mgdl)
                    .put(KEY_TIMESTAMP, timestamp)
                    .put(KEY_SOURCE, source)
                    .put("sentAt", System.currentTimeMillis())
                    .toString()
                val payload = json.toByteArray(Charsets.UTF_8)
                val targets = linkedSetOf<InetAddress>().apply {
                    add(InetAddress.getByName("255.255.255.255"))
                    subnetBroadcastAddress(context)?.let(::add)
                }
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    targets.forEach { address ->
                        socket.send(DatagramPacket(payload, payload.size, address, UDP_PORT))
                    }
                }
                Log.i(TAG, "UDP glucose broadcast sent: $mgdl source=$source targets=${targets.size}")
            }.onFailure {
                Log.w(TAG, "Failed to send UDP glucose fallback", it)
            }
        }.start()
    }

    private fun subnetBroadcastAddress(context: Context): InetAddress? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val dhcp = wifi.dhcpInfo ?: return null
        if (dhcp.ipAddress == 0 || dhcp.netmask == 0) return null
        val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
        val bytes = byteArrayOf(
            (broadcast and 0xFF).toByte(),
            (broadcast shr 8 and 0xFF).toByte(),
            (broadcast shr 16 and 0xFF).toByte(),
            (broadcast shr 24 and 0xFF).toByte(),
        )
        return InetAddress.getByAddress(bytes)
    }

    const val PATH_GLUCOSE = "/weartester/glucose"
    const val KEY_MGDL = "mgdl"
    const val KEY_TIMESTAMP = "timestamp"
    const val KEY_SOURCE = "source"
    const val UDP_PORT = 51725
    private const val UDP_FALLBACK_ENABLED = false
    private const val MIN_GLUCOSE_MGDL = 20
    private const val MAX_GLUCOSE_MGDL = 400
}
