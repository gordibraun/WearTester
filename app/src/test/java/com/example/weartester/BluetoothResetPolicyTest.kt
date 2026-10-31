package com.example.weartester

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothResetPolicyTest {

    private val t0 = 1_800_000_000_000L

    @Test fun firstWedgeIsResetAtOnce() {
        assertTrue(BluetoothResetPolicy.shouldReset(t0, 0L, 0))
    }

    @Test fun notTwiceWithinHalfAnHour() {
        assertFalse(BluetoothResetPolicy.shouldReset(t0 + 10 * 60_000L, t0, 1))
        assertTrue(BluetoothResetPolicy.shouldReset(t0 + BluetoothResetPolicy.MIN_INTERVAL_MS, t0, 1))
    }

    @Test fun givesUpAfterThreeResetsWithoutAReading() {
        assertFalse(BluetoothResetPolicy.shouldReset(t0 + 2 * 60 * 60_000L, t0, BluetoothResetPolicy.MAX_RESETS_WITHOUT_GLUCOSE))
        assertTrue(BluetoothResetPolicy.shouldReset(t0 + 2 * 60 * 60_000L, t0, 0))
    }
}
