package com.example.weartester

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun anEarly133InsideTheReadingWindowIsAnsweredOnTheNextAdvertisement() {
        // 4 Oct 11:28: the phone connected 0.13 s before the watch, the watch's connect ended 133
        // after 1.8 s, the transmitter advertised again 0.9 s later - into the old two-second pause.
        val policy = GattRetryPolicy()
        assertTrue(policy.disconnected(133, false, 100_000L, quickRetry = true))
        assertEquals(0L, policy.remainingMs(100_900L))
        assertTrue(policy.disconnected(133, false, 103_000L, quickRetry = true))
        assertEquals(0L, policy.remainingMs(103_100L))
        // The third early failure of the same window settles as before: 10:18 was four instant 133s.
        assertFalse(policy.disconnected(133, false, 106_000L, quickRetry = true))
        assertEquals(2_000L, policy.remainingMs(106_000L))
    }

    @Test fun quickRetriesAreCountedPerWindow() {
        val policy = GattRetryPolicy()
        policy.disconnected(133, false, 100_000L, quickRetry = true)
        policy.disconnected(133, false, 102_000L, quickRetry = true)
        assertFalse(policy.disconnected(133, false, 104_000L, quickRetry = true))
        // The next window, five minutes on, has its own two.
        assertTrue(policy.disconnected(133, false, 400_000L, quickRetry = true))
    }

    @Test fun aConnectionStartsTheCountAfresh() {
        val policy = GattRetryPolicy()
        policy.disconnected(133, false, 100_000L, quickRetry = true)
        policy.disconnected(133, false, 102_000L, quickRetry = true)
        policy.connected()
        assertTrue(policy.disconnected(133, false, 104_000L, quickRetry = true))
    }

    @Test fun outsideTheWindowAnEarlyFailureSettlesAsBefore() {
        val policy = GattRetryPolicy()
        assertFalse(policy.disconnected(133, false, 100_000L))
        assertEquals(2_000L, policy.remainingMs(100_000L))
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
