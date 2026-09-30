package com.example.weartester

import org.junit.Assert.*
import org.junit.Test

class RecoveryPolicyTest {
    @Test fun scanRenewalIsDueBeforePlatformFiveMinuteTimeout() {
        assertFalse(RecoveryPolicy.shouldRefreshScan(119_999, null))
        assertTrue(RecoveryPolicy.shouldRefreshScan(120_000, null))
        assertTrue(RecoveryPolicy.shouldRefreshScan(120_000, -1))
        assertTrue(RecoveryPolicy.shouldRefreshScan(120_000, 130_000))
        // 13:44:53 scanner -> 13:49:53 platform downgrade in the September 23 dump.
        assertTrue(RecoveryPolicy.shouldRefreshScan(120_000, 129_600))
        assertFalse(RecoveryPolicy.shouldRefreshScan(285_000, 294_600))
    }

    @Test fun scanRenewalProtectsTheAdvertisementAndSettlingTime() {
        for (age in listOf(0L, 60_000, 235_000, 240_000, 294_600, 300_000, 360_000, 600_000)) {
            assertFalse("contact age=$age", RecoveryPolicy.shouldRefreshScan(400_000, age))
        }
        assertTrue(RecoveryPolicy.shouldRefreshScan(150_000, 60_001))
        assertTrue(RecoveryPolicy.shouldRefreshScan(150_000, 234_999))
        assertTrue(RecoveryPolicy.shouldRefreshScan(450_000, 430_000))
    }

    @Test fun prolongedAbsenceCannotLeaveAContinuouslyDowngradedScanner() {
        // Replay an hour without readings from every possible scan-start phase,
        // including a watchdog/worker delayed by as much as 30 seconds.
        for (tick in listOf(1_000L, 30_000L)) {
            for (phase in 0L until 300_000L step 1_000L) {
                var scanStarted = 0L
                for (elapsed in tick..3_600_000L step tick) {
                    val age = elapsed - scanStarted
                    assertTrue("phase=$phase tick=$tick scanAge=$age", age < 300_000)
                    if (RecoveryPolicy.shouldRefreshScan(age, phase + elapsed)) {
                        val cycle = (phase + elapsed) % 300_000
                        assertTrue(cycle > 60_000 && cycle + 5_000 < 240_000)
                        scanStarted = elapsed
                    }
                }
            }
        }
    }

    @Test fun overnightAbsenceDoesNotRepeat45SecondProbeEveryMinute() {
        val overnight = 7 * 60 * 60_000L + 130_000L
        for (delay in listOf(0L, 35_000, 60_000, 9 * 60_000)) assertFalse(RecoveryPolicy.allowBlindProbe(overnight, delay))
        assertTrue(RecoveryPolicy.allowBlindProbe(overnight, 10 * 60_000))
        assertFalse(RecoveryPolicy.allowBlindProbe(overnight, -1))
    }

    @Test fun briefLossKeepsScannerInsteadOfConnectingToSleepingSensor() {
        for (age in 0L until RecoveryPolicy.LONG_ABSENCE step 1_000L) {
            assertFalse("age=$age", RecoveryPolicy.allowBlindProbe(age, Long.MAX_VALUE))
        }
    }

    @Test fun september23TenMinuteGapDoesNotRecreateBlindProbeStorm() {
        // Journal 10:09:39 -> 10:19:39: the pre-window and recovery probes stopped scanning.
        for (age in listOf(215_642L, 364_124, 407_653, 520_241, 582_939)) {
            assertFalse("Recorded blind attempt at $age", RecoveryPolicy.allowBlindProbe(age, Long.MAX_VALUE))
        }
        for (age in listOf(489_886L, 520_241, 582_939)) {
            assertFalse("Recorded silent restart at $age", RecoveryPolicy.allowSilentScanRestart(age, 52_007))
        }
    }

    @Test fun recoveryCannotOverlapAnyExpectedReadingWindow() {
        for (age in RecoveryPolicy.LONG_ABSENCE..(24 * 60 * 60_000L) step 1_000L) {
            if (RecoveryPolicy.allowBlindProbe(age, Long.MAX_VALUE)) {
                for (offset in 0L..(RecoveryPolicy.BLIND_PROBE_TIMEOUT + 5_000L) step 1_000L) {
                    assertFalse("age=$age offset=$offset", RecoveryPolicy.protectedScanWindow(age + offset))
                }
            }
        }
    }

    @Test fun periodicSilenceIsNotScannerFailure() {
        assertFalse(RecoveryPolicy.allowSilentScanRestart(430_000, 25_000))
        assertFalse(RecoveryPolicy.allowSilentScanRestart(430_000, 299_999))
        assertTrue(RecoveryPolicy.allowSilentScanRestart(430_000, 300_000))
        assertFalse(RecoveryPolicy.allowSilentScanRestart(599_000, 600_000))
        assertFalse(RecoveryPolicy.allowSilentScanRestart(610_000, 600_000))
    }

    @Test fun protectedWindowBoundaries() {
        assertFalse(RecoveryPolicy.protectedScanWindow(149_999))
        assertTrue(RecoveryPolicy.protectedScanWindow(150_000))
        assertTrue(RecoveryPolicy.protectedScanWindow(420_000))
        assertFalse(RecoveryPolicy.protectedScanWindow(420_001))
        assertFalse(RecoveryPolicy.protectedScanWindow(449_999))
        assertTrue(RecoveryPolicy.protectedScanWindow(450_000))
    }

    @Test fun closedGattCannotChangeNewConnectionState() {
        val old = Any()
        val current = Any()
        assertFalse(RecoveryPolicy.isCurrentCallback(current, old))
        assertFalse(RecoveryPolicy.isCurrentCallback(null, old))
        assertTrue(RecoveryPolicy.isCurrentCallback(current, current))
    }
}
