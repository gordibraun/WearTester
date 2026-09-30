package com.example.weartester

object RecoveryPolicy {
    const val LONG_ABSENCE = 15 * 60_000L
    const val BLIND_PROBE_INTERVAL = 10 * 60_000L
    const val BLIND_PROBE_TIMEOUT = 8_000L
    private const val READING_PERIOD = 5 * 60_000L
    private const val SCAN_WINDOW_START = 150_000L
    private const val SCAN_WINDOW_END = 120_000L
    private const val SCAN_REFRESH_AFTER = 120_000L
    private const val ADVERTISEMENT_MARGIN = 60_000L
    private const val SCAN_REFRESH_SETTLE_MARGIN = 5_000L

    // This watch downgrades scans after 300 s, without an onScanFailed callback.
    // Renew in the quiet part of the cycle; a brief renewal needs less clearance
    // than a blind GATT probe. Even a deferred renewal stays below 300 s.
    fun shouldRefreshScan(scanAge: Long, contactAge: Long?): Boolean {
        if (scanAge < SCAN_REFRESH_AFTER) return false
        if (contactAge == null || contactAge < 0L) return true
        val phase = contactAge % READING_PERIOD
        return phase > ADVERTISEMENT_MARGIN &&
            phase + SCAN_REFRESH_SETTLE_MARGIN < READING_PERIOD - ADVERTISEMENT_MARGIN
    }

    fun protectedScanWindow(contactAge: Long): Boolean {
        if (contactAge < SCAN_WINDOW_START) return false
        val phase = contactAge % READING_PERIOD
        return phase >= SCAN_WINDOW_START || phase <= SCAN_WINDOW_END
    }

    // Absence of advertisements between five-minute readings is normal, not a stuck scanner.
    fun allowBlindProbe(contactAge: Long, sincePreviousProbe: Long): Boolean =
        contactAge >= LONG_ABSENCE && sincePreviousProbe >= BLIND_PROBE_INTERVAL &&
            !protectedScanWindow(contactAge) &&
            !protectedScanWindow(contactAge + BLIND_PROBE_TIMEOUT + 5_000L)

    fun allowSilentScanRestart(contactAge: Long, silentFor: Long): Boolean =
        silentFor >= READING_PERIOD && !protectedScanWindow(contactAge)

    fun isCurrentCallback(active: Any?, callback: Any): Boolean = active === callback
}
