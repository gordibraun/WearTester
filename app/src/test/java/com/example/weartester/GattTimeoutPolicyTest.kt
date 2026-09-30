package com.example.weartester

import org.junit.Assert.*
import org.junit.Test

class GattTimeoutPolicyTest {
    private fun phase(attempt: Long, connected: Long? = null, discovered: Boolean = false) =
        GattTimeoutPolicy.expiredPhase(attempt, connected, discovered, GattTimeoutPolicy.ADVERTISED_CONNECT_TIMEOUT_MS)

    @Test fun stalledLinkDoesNotOccupyTheWholeAdvertisementWindow() {
        assertNull(phase(7_999))
        assertEquals(GattTimeoutPolicy.Phase.CONNECTING, phase(8_000))
        // September 23 14:39:37 -> 14:40:08, strong RSSI -62, no connection.
        assertEquals(GattTimeoutPolicy.Phase.CONNECTING, phase(30_312))
    }

    @Test fun allObservedSuccessfulConnectionDurationsHaveTimeToComplete() {
        for (elapsed in listOf(432L, 752, 950, 1_228, 3_950, 4_405, 5_261)) assertNull(phase(elapsed))
    }

    @Test fun connectionJustBeforeDeadlineGetsItsOwnDiscoveryBudget() {
        assertNull(phase(8_000, 1))
        assertNull(phase(30_312, 25_000))
        assertNull(phase(82_998, 74_999))
        assertEquals(GattTimeoutPolicy.Phase.DISCOVERING, phase(82_999, 75_000))
    }

    @Test fun establishedProtocolExchangeIsNotCancelledByLinkDeadline() {
        assertNull(phase(90_000, 89_000, discovered = true))
        assertNull(phase(90_000, discovered = true))
    }

    @Test fun autoConnectKeepsItsExplicitLongDeadline() {
        assertNull(GattTimeoutPolicy.expiredPhase(8_000, null, false, 360_000))
        assertEquals(GattTimeoutPolicy.Phase.CONNECTING,
            GattTimeoutPolicy.expiredPhase(360_000, null, false, 360_000))
    }
}
