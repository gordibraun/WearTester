package com.example.weartester

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A fingerstick sent to the G7 the way xDrip sends it: the sensor applies it with its own
 * algorithm, this app only carries the number and reads the verdict. Nothing here touches the
 * glucose the watch shows or forwards; a calibration changes what the sensor itself reports.
 *
 * Message (xDrip CalibrateTxMessage): opcode 0x34, glucose mg/dL as u16 LE, the fingerstick's
 * time as u32 LE seconds on the sensor's clock, CRC-16/XMODEM LE. Reply 0x35: info byte, result
 * byte, CRC.
 */
object CalibrationPolicy {

    const val OPCODE: Int = 0x34
    const val REPLY_OPCODE: Int = 0x35
    const val MIN_MGDL = 40
    const val MAX_MGDL = 400
    /** xDrip refuses a fingerstick older than this; the sensor would not place it either. */
    const val MAX_AGE_MS = 60 * 60_000L
    /** The sensor needs time to apply one before another makes sense. */
    const val MIN_GAP_MS = 20 * 60_000L
    const val WARM_UP_MS = 30 * 60_000L
    const val FIRST_DAY_MS = 24 * 60 * 60_000L
    const val READING_FRESH_MS = 20 * 60_000L
    /** Up to this rate of change the trend counts as flat: tenths of mg/dL per minute. */
    const val FLAT_TREND_TENTHS = 10
    const val MGDL_PER_MMOL = 18.0182

    data class Request(val mgdl: Int, val atEpochMs: Long)

    /** What the sensor answered. */
    data class Result(val code: Int, val accepted: Boolean, val text: String)

    /** The sensor's side of things, as the watch knows it. */
    data class Sensor(
        val mgdl: Int?,
        val readingAtEpochMs: Long?,
        val trendTenths: Int?,
        val state: Int,
        val startedAtEpochMs: Long,
    )

    data class Verdict(val blocks: List<String>, val warnings: List<String>) {
        val allowed: Boolean get() = blocks.isEmpty()
    }

    fun mmolToMgdl(mmol: Double): Int = (mmol * MGDL_PER_MMOL).roundToInt()
    fun mgdlToMmol(mgdl: Int): Double = mgdl / MGDL_PER_MMOL

    /** Seconds on the sensor's clock for a wall-clock moment, or null when the clock is unknown. */
    fun dexTimeSeconds(atEpochMs: Long, startedAtEpochMs: Long): Int? {
        if (startedAtEpochMs <= 0) return null
        val seconds = (atEpochMs - startedAtEpochMs) / 1000
        if (seconds < 0 || seconds > 20L * 24 * 3600) return null
        return seconds.toInt()
    }

    fun message(mgdl: Int, dexTimeSeconds: Int): ByteArray {
        require(mgdl in MIN_MGDL..MAX_MGDL) { "glucose out of range: $mgdl" }
        require(dexTimeSeconds >= 0) { "negative sensor time" }
        val body = byteArrayOf(
            OPCODE.toByte(),
            (mgdl and 0xFF).toByte(), ((mgdl shr 8) and 0xFF).toByte(),
            (dexTimeSeconds and 0xFF).toByte(), ((dexTimeSeconds shr 8) and 0xFF).toByte(),
            ((dexTimeSeconds shr 16) and 0xFF).toByte(), ((dexTimeSeconds shr 24) and 0xFF).toByte(),
        )
        val crc = SensorProtocol.crc16(body)
        return body + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }

    /** Null when the packet is not a calibration reply. */
    fun parseReply(packet: ByteArray): Result? {
        if (packet.size < 3 || packet[0].toInt() and 0xFF != REPLY_OPCODE) return null
        if (packet.size >= 5 && !SensorProtocol.validCrc(packet)) return Result(0xFF, false, "ответ повреждён")
        val code = packet[2].toInt() and 0xFF
        return Result(code, code in ACCEPTED_CODES, replyText(code))
    }

    fun replyText(code: Int): String = when (code) {
        0x00 -> "принята"
        0x06 -> "принята, сенсор просит вторую"
        0x0D -> "уже была, повтор"
        0x01 -> "отклонена (код 1)"
        0x08 -> "отклонена сенсором"
        0x0B -> "сенсор остановлен"
        0x0E -> "сенсор не готов к калибровке"
        0xFF -> "ответ не разобран"
        else -> "неизвестный ответ $code"
    }

    fun check(meterMgdl: Int, sensor: Sensor, nowEpochMs: Long, pending: Boolean, lastSentAtEpochMs: Long?, sentLast24h: Int): Verdict {
        val blocks = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (meterMgdl !in MIN_MGDL..MAX_MGDL) blocks += "сенсор принимает только от $MIN_MGDL до $MAX_MGDL мг/дл"
        if (pending) blocks += "одна калибровка уже ждёт отправки"
        if (sensor.startedAtEpochMs <= 0) blocks += "часы ещё не знают время сенсора: нужен хотя бы один пакет"
        else if (nowEpochMs - sensor.startedAtEpochMs < WARM_UP_MS) blocks += "сенсор ещё прогревается"
        when (sensor.state) {
            1 -> blocks += "сенсор остановлен"
            2 -> blocks += "сенсор прогревается"
            0x0B -> blocks += "сенсор сообщил об отказе"
            0x0F, 0x18 -> blocks += "сеанс сенсора закончен"
        }
        if (lastSentAtEpochMs != null && nowEpochMs - lastSentAtEpochMs < MIN_GAP_MS) {
            blocks += "прошлая калибровка была меньше 20 минут назад: дайте сенсору применить её"
        }
        val readingAge = sensor.readingAtEpochMs?.let { nowEpochMs - it }
        if (readingAge == null || readingAge > READING_FRESH_MS) warnings += "свежего показания сенсора нет: отправлю при следующем контакте"
        val trend = sensor.trendTenths
        if (trend != null && abs(trend) > FLAT_TREND_TENTHS) warnings += "сахар сейчас меняется (${trend / 10.0} мг/дл в минуту): лучше калибровать на ровном"
        val sensorMgdl = sensor.mgdl
        if (sensorMgdl != null && readingAge != null && readingAge <= READING_FRESH_MS) {
            val diff = abs(meterMgdl - sensorMgdl)
            val percent = diff * 100.0 / maxOf(sensorMgdl, 1)
            if (percent < 15.0 && diff < 15) warnings += "расхождение с сенсором невелико ($diff мг/дл): Dexcom считает это нормой"
            if (percent > 50.0 && diff > 40) warnings += "расхождение очень большое ($diff мг/дл): сенсор может отвергнуть; проверьте глюкометр второй полоской"
        }
        if (sensor.startedAtEpochMs > 0 && nowEpochMs - sensor.startedAtEpochMs < FIRST_DAY_MS) warnings += "сенсору меньше суток: в первый день он сам ещё садится"
        if (sentLast24h >= 2) warnings += "за сутки уже $sentLast24h калибровки: чаще обычно не нужно"
        return Verdict(blocks, warnings)
    }

    private val ACCEPTED_CODES = setOf(0x00, 0x06, 0x0D)
}
