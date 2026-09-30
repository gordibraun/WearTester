package com.example.weartester

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

data class SensorLifetime(val startedAt: Long = 0, val sessionTick: Int = -1, val state: Int = 0, val lastContactAt: Long = 0) {
    val expiresAt: Long get() = if (startedAt > 0) startedAt + DURATION else 0
    data class Label(val text: String, val urgent: Boolean)

    fun withSession(session: SensorProtocol.Session, now: Long): SensorLifetime {
        val start = session.startedAt(now) ?: return copy(state = 1, lastContactAt = now)
        return if (sessionTick == session.startTime && startedAt > 0) copy(lastContactAt = now)
        else SensorLifetime(start, session.startTime, 0, now)
    }

    fun label(now: Long, zone: ZoneId = ZoneId.systemDefault()): Label = when {
        state == 0x0f || state == 0x18 -> Label("Сенсор завершён", true)
        state in setOf(1, 0x1a, 0xc2) -> Label("Сенсор остановлен", true)
        state in 0x0b..0x1e -> Label("Ошибка сенсора", true)
        startedAt <= 0 -> Label("Срок сенсора неизвестен", false)
        now >= expiresAt -> Label("Срок сенсора истёк", true)
        expiresAt - now <= WARNING -> Label("Сенсор: осталось ${ceil((expiresAt - now) / 3_600_000.0).toInt()} ч", true)
        else -> Label("Сенсор до ${DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(zone).format(Instant.ofEpochMilli(expiresAt))}", false)
    }

    fun glucoseStatus(): String = when (state) {
        2 -> "Прогрев"
        0x0f, 0x18 -> "Завершён"
        1, 0x1a, 0xc2 -> "Остановлен"
        4, 5, 7 -> "Калибровка"
        0 -> "Нет данных"
        else -> "Ошибка сенсора"
    }

    companion object {
        const val DURATION = 10 * 24 * 60 * 60_000L
        const val WARNING = 24 * 60 * 60_000L
    }
}
