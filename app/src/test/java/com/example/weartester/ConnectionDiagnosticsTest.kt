package com.example.weartester

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ConnectionDiagnosticsTest {
    @Test fun separatesSensorPhoneAndAppLoss() {
        assertEquals("Нет свежего измерения от сенсора", LinkHealth.describe(500_000, 1, 1000))
        assertEquals("Телефон не подключен к часам", LinkHealth.describe(1000, 0, 1000))
        assertEquals("Нет подтверждения приложения на телефоне", LinkHealth.describe(1000, 1, null))
        assertEquals("Сенсор и приложение телефона отвечают", LinkHealth.describe(1000, 1, 1000))
    }

    @Test fun journalIsBoundedAndKeepsNewestEntries() {
        val dir = Files.createTempDirectory("watch-log-test").toFile()
        try {
            val journal = RollingJournal(dir, 100, 3)
            repeat(100) { journal.append("$it " + "x".repeat(30)) }
            val files = dir.listFiles()!!
            assertEquals(3, files.size)
            assertTrue(files.all { it.length() <= 100 })
            assertTrue(dir.resolve("connection-0.jsonl").readText().contains("99 "))
        } finally { dir.deleteRecursively() }
    }

    @Test fun logSurvivesWriterRecreation() {
        val dir = Files.createTempDirectory("watch-log-restart").toFile()
        try {
            RollingJournal(dir).append("before")
            RollingJournal(dir).append("after")
            assertEquals("before\nafter\n", dir.resolve("connection-0.jsonl").readText())
        } finally { dir.deleteRecursively() }
    }
}
