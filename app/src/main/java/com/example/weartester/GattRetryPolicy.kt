package com.example.weartester

internal class GattRetryPolicy {
    companion object {
        const val EARLY_FAILURE_SETTLE_MS = 2_000L
    }

    private var retryAtElapsed = 0L

    fun disconnected(status: Int, wasConnected: Boolean, nowElapsed: Long) {
        // Leave the scanner running while the failed controller connection settles.
        // Do not apply the outer loop's 25-60 s delay to a short sensor window.
        retryAtElapsed = if (status != 0 && !wasConnected) {
            nowElapsed + EARLY_FAILURE_SETTLE_MS
        } else {
            0L
        }
    }

    fun connected() {
        retryAtElapsed = 0L
    }

    fun remainingMs(nowElapsed: Long): Long = (retryAtElapsed - nowElapsed).coerceAtLeast(0L)
}
