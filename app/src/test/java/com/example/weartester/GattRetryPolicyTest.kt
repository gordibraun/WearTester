package com.example.weartester

import org.junit.Assert.assertEquals
import org.junit.Test

class GattRetryPolicyTest {
    @Test fun firstAdvertisementIsNotDelayed() {
        assertEquals(0L, GattRetryPolicy().remainingMs(100_000L))
    }

    @Test fun earlyFailureRequiresSettlingButLeavesNextWindowAvailable() {
        val policy = GattRetryPolicy()
        policy.disconnected(133, false, 100_000L)
        assertEquals(2_000L, policy.remainingMs(100_000L))
        assertEquals(1L, policy.remainingMs(101_999L))
        assertEquals(0L, policy.remainingMs(102_000L))
        assertEquals(0L, policy.remainingMs(400_000L))
    }

    @Test fun advertisementsDoNotKeepExtendingThePause() {
        val policy = GattRetryPolicy()
        policy.disconnected(133, false, 100_000L)
        for (now in 100_000L..102_000L step 100L) {
            assertEquals(102_000L - now, policy.remainingMs(now))
        }
    }

    @Test fun normalDisconnectAfterReadingDoesNotDelayAnotherConnection() {
        val policy = GattRetryPolicy()
        policy.disconnected(19, true, 100_000L)
        assertEquals(0L, policy.remainingMs(100_000L))
        policy.disconnected(0, false, 200_000L)
        assertEquals(0L, policy.remainingMs(200_000L))
    }

    @Test fun successfulConnectionClearsPreviousFailure() {
        val policy = GattRetryPolicy()
        policy.disconnected(133, false, 100_000L)
        policy.connected()
        assertEquals(0L, policy.remainingMs(100_500L))
    }

    @Test fun september23RapidRetryBurstIsNotRepeated() {
        val policy = GattRetryPolicy()
        // Original 16:39 failures and subsequent advertisement times, relative to 16:39:00.
        policy.disconnected(133, false, 39_279L)
        assertEquals(1_576L, policy.remainingMs(39_703L))
        assertEquals(631L, policy.remainingMs(40_648L))
        assertEquals(0L, policy.remainingMs(41_616L))
        policy.disconnected(133, false, 42_136L)
        assertEquals(1_578L, policy.remainingMs(42_558L))
        assertEquals(0L, policy.remainingMs(44_340L))
    }
}
