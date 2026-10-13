package com.example.weartester

import org.junit.Assert.*
import org.junit.Test

class SensorProtocolTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun actualSeptember21ErrorIsNotSixMgdl() {
        val reading = SensorProtocol.reading(hex("4F0075500000B2665E000600127F0D00A3D6"))
        assertEquals(6, reading.glucose)
        assertEquals(0x12, reading.state)
        assertFalse(reading.usable)
    }

    @Test fun actualSessionTimestampComesFromTransmitterNotAppLaunch() {
        val session = SensorProtocol.session(hex("2500C3665E0014625E0001000000F5CE"))
        assertEquals(6186691, session.transmitterTime)
        assertEquals(6185492, session.startTime)
        assertEquals(1_789_978_779_801L - 1199_000L, session.startedAt(1_789_978_779_801L))
    }

    @Test fun statusDisplayOnlyAndStaleGlucoseCannotBePublished() {
        val valid = SensorProtocol.Reading(100, 123, 0, 6, false)
        assertTrue(valid.usable)
        assertTrue(valid.copy(state = 7).usable)
        for (state in listOf(0, 1, 2, 3, 4, 5, 0x0f, 0x12, 0x18)) assertFalse(valid.copy(state = state).usable)
        assertFalse(valid.copy(displayOnly = true).usable)
        assertFalse(valid.copy(ageSeconds = 305).usable)
        assertFalse(valid.copy(glucose = 5).usable)
    }

    @Test fun corruptTruncatedAndRawPacketsAreRejected() {
        val packet = hex("4F0075500000B2665E000600127F0D00A3D6")
        packet[10] = 120
        assertThrows(IllegalArgumentException::class.java) { SensorProtocol.reading(packet) }
        assertThrows(IllegalArgumentException::class.java) { SensorProtocol.reading(byteArrayOf(0x4f, 0)) }
        assertThrows(IllegalStateException::class.java) { SensorProtocol.reading(ByteArray(16).also { it[0] = 0x2f }) }
    }
}

class G7PacketTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** The packet layout documented for the G7: timestamp 2005 s, sequence 9, age 5 s, 97 mg/dL, state 6, trend +0.1. */
    @Test fun g7GlucosePacketIsReadLikeTheG6SecondMessage() {
        val reading = SensorProtocol.reading(hex("4E00D5070000090000010500610006010ffff0e".uppercase().padEnd(38, '0').take(38)))
        assertEquals(2005, reading.timestamp)
        assertEquals(5, reading.ageSeconds)
        assertEquals(97, reading.glucose)
        assertEquals(6, reading.state)
        assertEquals(1, reading.trendTenths)
        assertFalse(reading.displayOnly)
        assertTrue(reading.usable)
    }

    @Test fun g7TrendAbsentAndWarmupAreCarried() {
        val packet = hex("4E00D5070000090000010500610006010FFFF0E".padEnd(38, '0').take(38))
        packet[15] = 0x7f
        packet[14] = 2
        val reading = SensorProtocol.reading(packet)
        assertNull(reading.trendTenths)
        assertEquals(2, reading.state)
        assertFalse(reading.usable)
    }

    @Test fun g7SessionStartIsKeptUntilItMovesByMoreThanTheTolerance() {
        val now = 1_790_000_000_000L
        val first = SensorLifetime().withStartedAt(now - 3_600_000L, now, SensorLifetime.G7_GRACE)
        assertEquals(now - 3_600_000L, first.startedAt)
        assertEquals(SensorLifetime.G7_GRACE, first.grace)
        val jitter = first.withStartedAt(now - 3_600_000L + 90_000L, now + 300_000L, SensorLifetime.G7_GRACE)
        assertEquals(first.startedAt, jitter.startedAt)
        val replaced = jitter.withStartedAt(now + 200_000L, now + 2_000_000L, SensorLifetime.G7_GRACE)
        assertEquals(now + 200_000L, replaced.startedAt)
        assertEquals(0, replaced.state)
    }

    @Test fun g7GraceHoursAreShownAfterTenDays() {
        val start = 1_789_000_000_000L
        val sensor = SensorLifetime(start, 0, 6, start, SensorLifetime.G7_GRACE)
        assertEquals("Сенсор: запас 12 ч", sensor.label(sensor.expiresAt).text)
        assertTrue(sensor.label(sensor.expiresAt).urgent)
        assertEquals("Сенсор: запас 1 ч", sensor.label(sensor.endsAt - 1).text)
        assertEquals("Срок сенсора истёк", sensor.label(sensor.endsAt).text)
        assertEquals("Срок сенсора истёк", SensorLifetime(start, 100, 6, start).label(start + SensorLifetime.DURATION).text)
    }

    @Test fun g7NamesAreRecognised() {
        for (name in listOf("DXCM7A", "dxcmAB", "DX02ZZ", "DX01xx", "DX04ab")) assertTrue(name, G7Session.isG7Name(name))
        for (name in listOf(null, "Dexcom7A", "DX", "DXC", "DX99AB", "Garmin")) assertFalse(name ?: "null", G7Session.isG7Name(name))
    }
}
