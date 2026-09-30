package com.example.weartester

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** G6 control responses. Raw sensor current is not a calibrated glucose measurement. */
object SensorProtocol {
    data class Reading(val timestamp: Int, val glucose: Int, val ageSeconds: Int, val state: Int, val displayOnly: Boolean) {
        val usable: Boolean get() = state in setOf(6, 7) && glucose in 20..600 && !displayOnly && ageSeconds < 305
    }
    data class Session(val transmitterTime: Int, val startTime: Int) {
        val active: Boolean get() = startTime >= 0 && transmitterTime > startTime
        fun startedAt(now: Long): Long? = if (active) (now - (transmitterTime.toLong() - startTime) * 1000).takeIf { it > 0 } else null
    }

    fun session(packet: ByteArray): Session {
        require(packet.size >= 12 && packet[0].toInt() and 255 == 0x25 && validCrc(packet)) { "Invalid time response" }
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        return Session(data.getInt(2), data.getInt(6))
    }

    fun reading(packet: ByteArray): Reading {
        val opcode = packet.firstOrNull()?.toInt()?.and(255)
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val timestamp: Int
        val glucoseBits: Int
        val age: Int
        val state: Int
        when (opcode) {
            0x31, 0x4f -> {
                require(packet.size >= (if (opcode == 0x4f) 18 else 16) && validCrc(packet)) { "Invalid glucose response" }
                timestamp = data.getInt(6)
                glucoseBits = data.getShort(10).toInt() and 65535
                state = packet[12].toInt() and 255
                age = 0
            }
            0x4e -> {
                require(packet.size >= 19) { "Short glucose2 response" }
                timestamp = data.getInt(2)
                age = data.getShort(10).toInt() and 65535
                glucoseBits = data.getShort(12).toInt() and 65535
                state = packet[14].toInt() and 255
            }
            else -> error("Not a calibrated glucose response")
        }
        return Reading(timestamp, glucoseBits and 4095, age, state, glucoseBits and 0xf000 != 0)
    }

    fun validCrc(packet: ByteArray): Boolean {
        if (packet.size < 3) return false
        var crc = 0
        for (byte in packet.dropLast(2)) {
            crc = crc xor ((byte.toInt() and 255) shl 8)
            repeat(8) { crc = (if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1) and 65535 }
        }
        return crc == ((packet[packet.lastIndex - 1].toInt() and 255) or ((packet.last().toInt() and 255) shl 8))
    }
}
