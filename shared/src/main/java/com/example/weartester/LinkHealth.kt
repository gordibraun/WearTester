package com.example.weartester

object LinkHealth {
    fun describe(sensorAgeMs: Long?, connectedNodes: Int, ackAgeMs: Long?): String = when {
        sensorAgeMs == null -> "Нет измерения от сенсора"
        sensorAgeMs > 7 * 60_000L -> "Нет свежего измерения от сенсора"
        connectedNodes == 0 -> "Телефон не подключен к часам"
        ackAgeMs == null || ackAgeMs > 2 * 60_000L -> "Нет подтверждения приложения на телефоне"
        else -> "Сенсор и приложение телефона отвечают"
    }
}
