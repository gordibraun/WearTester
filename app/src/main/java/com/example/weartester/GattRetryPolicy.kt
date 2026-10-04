package com.example.weartester

/**
 * How long to leave the transmitter alone after a connection attempt failed before it got anywhere.
 *
 * Normally two seconds: the stack settles and a storm of connects is avoided (23 Sept). The one
 * exception is a reading window shared with another display device - the phone's xDrip on 4 Oct:
 * the other device connects first, the watch's attempt ends with status 133 within a second or
 * two, and the transmitter is advertising again 0.6-3 s later for its second client. Those packets
 * fell into the two-second pause in three of the nine windows the watch missed that morning. So an
 * early 133 of a connect made from an advertisement inside the reading window is answered by
 * connecting on the very next advertisement - at most twice per window, so that a stack that
 * refuses outright (four instant 133s at 10:18) is not hammered.
 */
internal class GattRetryPolicy {
    companion object {
        const val EARLY_FAILURE_SETTLE_MS = 2_000L

        /** Quick retries allowed per reading window; the next early failure settles as usual. */
        const val QUICK_RETRIES_PER_WINDOW = 2

        /** Early failures further apart than this belong to different windows. */
        const val QUICK_RETRY_WINDOW_MS = 40_000L
    }

    private var retryAtElapsed = 0L
    private var quickRetriesUsed = 0
    private var firstQuickFailureAtElapsed = 0L

    /**
     * @param quickRetry true when the failure was an early status 133 of a connect made from an
     *   advertisement inside the reading window. The caller knows the window; this class only counts.
     * @return true when, because of [quickRetry], no pause was imposed.
     */
    fun disconnected(status: Int, wasConnected: Boolean, nowElapsed: Long, quickRetry: Boolean = false): Boolean {
        if (status == 0 || wasConnected) {
            retryAtElapsed = 0L
            return false
        }
        if (quickRetry) {
            if (nowElapsed - firstQuickFailureAtElapsed > QUICK_RETRY_WINDOW_MS) {
                firstQuickFailureAtElapsed = nowElapsed
                quickRetriesUsed = 0
            }
            if (quickRetriesUsed < QUICK_RETRIES_PER_WINDOW) {
                quickRetriesUsed += 1
                retryAtElapsed = 0L
                return true
            }
        }
        // Leave the scanner running while the failed controller connection settles.
        // Do not apply the outer loop's 25-60 s delay to a short sensor window.
        retryAtElapsed = nowElapsed + EARLY_FAILURE_SETTLE_MS
        return false
    }

    fun connected() {
        retryAtElapsed = 0L
        quickRetriesUsed = 0
        firstQuickFailureAtElapsed = 0L
    }

    fun remainingMs(nowElapsed: Long): Long = (retryAtElapsed - nowElapsed).coerceAtLeast(0L)
}
