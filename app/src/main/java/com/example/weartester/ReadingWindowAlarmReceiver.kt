package com.example.weartester

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class ReadingWindowAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BondProbeService.ACTION_READING_WINDOW_WAKE) return
        val config = DexcomConfigStore.load(context)
        if (config.transmitterId.isBlank()) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, BondProbeService::class.java)
                .setAction(BondProbeService.ACTION_READING_WINDOW_WAKE),
        )
    }
}
