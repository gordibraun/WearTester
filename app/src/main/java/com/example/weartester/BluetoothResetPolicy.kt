package com.example.weartester

/**
 * When the collector may switch the watch's Bluetooth off and on by itself. The stack wedges now
 * and then (19 and 30 Oct 2026: every connect hung for hours while the sensor advertised) and
 * only an adapter restart cures it; but a restart also drops the pump and the phone for a few
 * seconds, so it is rationed: not more often than [MIN_INTERVAL_MS], and not more than
 * [MAX_RESETS_WITHOUT_GLUCOSE] times without a reading in between — when restarts do not help,
 * the owner is told instead of Bluetooth being bounced all night.
 */
object BluetoothResetPolicy {

    const val MIN_INTERVAL_MS = 30 * 60_000L
    const val MAX_RESETS_WITHOUT_GLUCOSE = 3

    fun shouldReset(now: Long, lastResetAt: Long, resetsSinceGlucose: Int): Boolean {
        if (resetsSinceGlucose >= MAX_RESETS_WITHOUT_GLUCOSE) return false
        return lastResetAt <= 0L || now - lastResetAt >= MIN_INTERVAL_MS
    }
}
