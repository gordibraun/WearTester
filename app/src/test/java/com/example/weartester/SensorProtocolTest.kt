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
