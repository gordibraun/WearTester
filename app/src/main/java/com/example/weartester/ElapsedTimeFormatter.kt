package com.example.weartester

object ElapsedTimeFormatter {
    fun elapsedSeconds(eventAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): Long {
        if (eventAtMillis <= 0L) return -1L
        return ((nowMillis - eventAtMillis) / 1000L).coerceAtLeast(0L)
    }

    fun elapsedMinutes(eventAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): Long {
        val seconds = elapsedSeconds(eventAtMillis, nowMillis)
        return if (seconds < 0L) -1L else seconds / 60L
    }

    fun elapsedText(
        eventAtMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
        emptyText: String = "ещё не было",
    ): String {
        val seconds = elapsedSeconds(eventAtMillis, nowMillis)
        if (seconds < 0L) return emptyText
        return "${seconds / 60L}м ${seconds % 60L}с назад"
    }

    fun compactMinutes(
        eventAtMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
        maxMinutes: Long = 99L,
    ): String {
        val minutes = elapsedMinutes(eventAtMillis, nowMillis)
        if (minutes < 0L) return "Dex"
        return if (minutes > maxMinutes) "${maxMinutes}m+" else "${minutes}m"
    }
}
