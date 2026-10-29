package com.example.weartester

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationPolicyTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }

    @Test fun messageIsXdripsLayoutWithXmodemCrc() {
        // 70 mg/dL = 0x0046, sensor second 536_899 = 0x00083143, both little-endian, then the CRC.
        val bytes = CalibrationPolicy.message(70, 536_899)
        assertEquals(9, bytes.size)
        assertEquals("34460043310800", bytes.copyOfRange(0, 7).hex())
        assertTrue(SensorProtocol.validCrc(bytes))
        // The CRC is the one every Dexcom control packet carries, as on this real 0x4F packet.
        assertTrue(SensorProtocol.validCrc(hex("4F0075500000B2665E000600127F0D00A3D6")))
    }

    @Test fun crcMatchesTheSensorsOwnPackets() {
        val packet = hex("4F0075500000B2665E000600127F0D00A3D6")
        val crc = SensorProtocol.crc16(packet.copyOfRange(0, packet.size - 2))
        assertEquals(0xD6A3, crc)
    }

    @Test fun repliesAreReadAsXdripReadsThem() {
        fun reply(code: Int): ByteArray {
            val body = byteArrayOf(0x35, 0x00, code.toByte())
            val crc = SensorProtocol.crc16(body)
            return body + byteArrayOf((crc and 0xFF).toByte(), (crc shr 8).toByte())
        }
        assertTrue(CalibrationPolicy.parseReply(reply(0x00))!!.accepted)
        assertTrue(CalibrationPolicy.parseReply(reply(0x06))!!.accepted)
        assertTrue(CalibrationPolicy.parseReply(reply(0x0D))!!.accepted)
        assertFalse(CalibrationPolicy.parseReply(reply(0x08))!!.accepted)
        assertEquals("отклонена сенсором", CalibrationPolicy.parseReply(reply(0x08))!!.text)
        assertEquals("сенсор не готов к калибровке", CalibrationPolicy.parseReply(reply(0x0E))!!.text)
        assertNull(CalibrationPolicy.parseReply(hex("4E00")))
        assertFalse(CalibrationPolicy.parseReply(hex("3500000000"))!!.accepted)
    }

    @Test fun sensorTimeComesFromTheSessionStart() {
        val startedAt = 1_792_733_311_138L
        assertEquals(536_899, CalibrationPolicy.dexTimeSeconds(startedAt + 536_899_000L + 400, startedAt))
        assertNull(CalibrationPolicy.dexTimeSeconds(startedAt - 1000, startedAt))
        assertNull(CalibrationPolicy.dexTimeSeconds(startedAt + 1000, 0))
    }

    @Test fun unitsRoundTrip() {
        assertEquals(70, CalibrationPolicy.mmolToMgdl(3.9))
        assertEquals(100, CalibrationPolicy.mmolToMgdl(5.55))
        assertEquals(5.6, Math.round(CalibrationPolicy.mgdlToMmol(101) * 10) / 10.0, 0.01)
    }

    private val now = 1_793_270_000_000L
    private val flat = CalibrationPolicy.Sensor(mgdl = 101, readingAtEpochMs = now - 3 * 60_000L, trendTenths = -2, state = 6, startedAtEpochMs = now - 6 * 24 * 3600_000L)

    @Test fun todaysCaseIsAllowedWithoutWarnings() {
        val v = CalibrationPolicy.check(70, flat, now, pending = false, lastSentAtEpochMs = null, sentLast24h = 0)
        assertTrue(v.blocks.joinToString(), v.allowed)
        assertTrue(v.warnings.joinToString(), v.warnings.isEmpty())
    }

    @Test fun hardStopsBlock() {
        assertFalse(CalibrationPolicy.check(30, flat, now, false, null, 0).allowed)
        assertFalse(CalibrationPolicy.check(70, flat, now, pending = true, lastSentAtEpochMs = null, sentLast24h = 0).allowed)
        assertFalse(CalibrationPolicy.check(70, flat.copy(state = 2), now, false, null, 0).allowed)
        assertFalse(CalibrationPolicy.check(70, flat.copy(startedAtEpochMs = now - 10 * 60_000L), now, false, null, 0).allowed)
        assertFalse(CalibrationPolicy.check(70, flat.copy(startedAtEpochMs = 0), now, false, null, 0).allowed)
        assertFalse(CalibrationPolicy.check(70, flat, now, false, lastSentAtEpochMs = now - 5 * 60_000L, sentLast24h = 1).allowed)
    }

    @Test fun softSignsOnlyWarn() {
        val moving = CalibrationPolicy.check(70, flat.copy(trendTenths = -25), now, false, null, 0)
        assertTrue(moving.allowed)
        assertTrue(moving.warnings.any { it.contains("меняется") })
        val small = CalibrationPolicy.check(96, flat, now, false, null, 0)
        assertTrue(small.warnings.any { it.contains("невелико") })
        val young = CalibrationPolicy.check(70, flat.copy(startedAtEpochMs = now - 5 * 3600_000L), now, false, null, 0)
        assertTrue(young.allowed)
        assertTrue(young.warnings.any { it.contains("меньше суток") })
        val stale = CalibrationPolicy.check(70, flat.copy(readingAtEpochMs = now - 40 * 60_000L), now, false, null, 0)
        assertTrue(stale.allowed)
        assertTrue(stale.warnings.any { it.contains("свежего показания") })
    }
}
