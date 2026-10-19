package com.example.weartester

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Gate in front of the setup screen for taps on the glucose: on the watch face, on the glucose
 * complication and on the sensor-expiry complication.
 *
 * Those taps arrive as PendingIntents, where taps cannot be counted, so each one lands here
 * instead of in MainActivity: the third within a second opens the setup, with the original
 * extras; any other just makes sure the collector is running, hints once in a while, and closes
 * again without a trace. A single stray tap on the glucose used to open the setup at once
 * (19 Oct 2026), where the next stray tap changed the sensor code or model.
 */
class TripleTapGateActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val taps = (prefs.getString(KEY_TAPS, "") ?: "")
            .split(',').mapNotNull { it.toLongOrNull() }.filter { now - it <= WINDOW_MS } + now
        if (taps.size >= TripleTap.TAPS) {
            prefs.edit().remove(KEY_TAPS).apply()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .apply { intent.extras?.let { putExtras(it) } }
            )
        } else {
            prefs.edit().putString(KEY_TAPS, taps.joinToString(",")).apply()
            // A lone tap still keeps the collector alive, as a tap on the face always did.
            if (DexcomConfigStore.load(this).isConfigured) {
                runCatching { ContextCompat.startForegroundService(this, Intent(this, BondProbeService::class.java)) }
            }
            if (taps.size == 1 && now - prefs.getLong(KEY_HINT_AT, 0L) >= HINT_EVERY_MS) {
                Toast.makeText(this, "Настройка: 3 быстрых нажатия", Toast.LENGTH_SHORT).show()
                prefs.edit().putLong(KEY_HINT_AT, now).apply()
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val PREFS = "triple_tap_gate"
        const val KEY_TAPS = "taps"
        const val KEY_HINT_AT = "hint_at"
        const val WINDOW_MS = 1_000L
        const val HINT_EVERY_MS = 8_000L
    }
}
