package com.example.weartester

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowGlucoseAlarmTest {

    private val policy = LowGlucoseAlarm()
    private val t0 = 1_800_000_000_000L
    private fun min(m: Int) = m * 60_000L

    @Test fun belowSeventyAlarmsOnceThenEveryTenMinutes() {
        var (state, verdict) = policy.evaluate(LowGlucoseAlarm.State(), 68, t0)
        assertTrue(verdict is LowGlucoseAlarm.Verdict.Alarm)
        assertEquals(LowGlucoseAlarm.Level.LOW, (verdict as LowGlucoseAlarm.Verdict.Alarm).level)
        assertFalse(verdict.repeat)
        // Five minutes later, still low: quiet. Ten minutes later: repeated.
        val (s2, v2) = policy.evaluate(state, 66, t0 + min(5)); state = s2
        assertTrue(v2 is LowGlucoseAlarm.Verdict.Quiet)
        val (s3, v3) = policy.evaluate(state, 64, t0 + min(10)); state = s3
        assertTrue((v3 as LowGlucoseAlarm.Verdict.Alarm).repeat)
        assertEquals(10, v3.lowForMinutes)
        // Back to 75: nothing yet; at 80: cleared and forgotten.
        val (s4, v4) = policy.evaluate(state, 75, t0 + min(20)); state = s4
        assertTrue(v4 is LowGlucoseAlarm.Verdict.Quiet)
        val (s5, v5) = policy.evaluate(state, 80, t0 + min(25))
        assertTrue(v5 is LowGlucoseAlarm.Verdict.Cleared)
        assertEquals(LowGlucoseAlarm.State(), s5)
    }

    @Test fun urgentBreaksThroughAndRepeatsEveryFive() {
        var (state, _) = policy.evaluate(LowGlucoseAlarm.State(), 68, t0)
        val (s2, v2) = policy.evaluate(state, 52, t0 + min(2)); state = s2
        assertEquals(LowGlucoseAlarm.Level.URGENT, (v2 as LowGlucoseAlarm.Verdict.Alarm).level)
        assertFalse(v2.repeat) // an escalation is announced as new
        val (s3, v3) = policy.evaluate(state, 50, t0 + min(5)); state = s3
        assertTrue(v3 is LowGlucoseAlarm.Verdict.Quiet)
        val (_, v4) = policy.evaluate(state, 50, t0 + min(7))
        assertTrue((v4 as LowGlucoseAlarm.Verdict.Alarm).repeat)
    }

    @Test fun snoozeSilencesLowButNotUrgent() {
        var (state, _) = policy.evaluate(LowGlucoseAlarm.State(), 68, t0)
        state = policy.snooze(state, t0 + min(1))
        val (s2, v2) = policy.evaluate(state, 66, t0 + min(12)); state = s2
        assertTrue(v2 is LowGlucoseAlarm.Verdict.Quiet)
        val (_, v3) = policy.evaluate(state, 54, t0 + min(13))
        assertTrue(v3 is LowGlucoseAlarm.Verdict.Alarm)
    }

    @Test fun normalGlucoseIsQuietAndZeroIsIgnored() {
        assertTrue(policy.evaluate(LowGlucoseAlarm.State(), 110, t0).second is LowGlucoseAlarm.Verdict.Quiet)
        assertTrue(policy.evaluate(LowGlucoseAlarm.State(), 0, t0).second is LowGlucoseAlarm.Verdict.Quiet)
        assertEquals("3.9", LowGlucoseAlarm.mmol(70).replace(',', '.'))
    }
}
