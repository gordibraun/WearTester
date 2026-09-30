package com.example.weartester

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class SensorLifetimeTest {
    private val start = 1_789_000_000_000L
    private val sensor = SensorLifetime(start, 100, 6, start)

    @Test fun warnsAtExactly24HoursNotEarlier() {
        assertFalse(sensor.label(sensor.expiresAt - SensorLifetime.WARNING - 1).urgent)
        assertTrue(sensor.label(sensor.expiresAt - SensorLifetime.WARNING).urgent)
        assertTrue(sensor.label(sensor.expiresAt - SensorLifetime.WARNING).text.contains("24 ч"))
        assertTrue(sensor.label(sensor.expiresAt - 1).text.contains("1 ч"))
        assertEquals("Срок сенсора истёк", sensor.label(sensor.expiresAt).text)
    }

    @Test fun unknownStartDoesNotInventTenMoreDays() {
        assertEquals("Срок сенсора неизвестен", SensorLifetime().label(start).text)
    }

    @Test fun errorsAndEarlyEndOverrideEstimatedDeadline() {
        assertEquals("Ошибка сенсора", sensor.copy(state = 0x12).label(start).text)
        for (state in listOf(0x0f, 0x18)) assertEquals("Сенсор завершён", sensor.copy(state = state).label(start).text)
        assertTrue(sensor.copy(state = 0x18).label(start).urgent)
    }

    @Test fun timeZoneChangesOnlyDisplayedDate() {
        assertNotEquals(sensor.label(start, ZoneId.of("UTC")).text, sensor.label(start, ZoneId.of("Europe/Moscow")).text)
        assertEquals(start + SensorLifetime.DURATION, sensor.expiresAt)
    }

    @Test fun reconnectAndClockJitterCannotExtendDeadline() {
        val updated = sensor.withSession(SensorProtocol.Session(700, 100), start + 601_000)
        assertEquals(sensor.expiresAt, updated.expiresAt)
        assertEquals(6, updated.state)
    }

    @Test fun newSessionResetsStateAndEndedSessionDoesNotAutoRestart() {
        val newer = sensor.copy(state = 0x18).withSession(SensorProtocol.Session(10_600, 10_000), start + 1_000_000)
        assertEquals(start + 400_000, newer.startedAt)
        assertEquals(0, newer.state)
        val ended = sensor.withSession(SensorProtocol.Session(800, -1), start + 5_000)
        assertEquals(sensor.startedAt, ended.startedAt)
        assertEquals(1, ended.state)
    }
}
