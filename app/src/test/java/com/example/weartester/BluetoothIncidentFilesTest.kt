package com.example.weartester

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BluetoothIncidentFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun retentionKeepsNewestIncidentsAndUnrelatedFiles() {
        val root = temporary.newFolder()
        val unrelated = File(root, "settings").apply { mkdir() }
        val store = BluetoothIncidentFiles(root, 2)
        store.create("1000-100")
        store.create("2000-200")
        store.create("3000-300")
        assertFalse(File(root, "incident-1000-100").exists())
        assertTrue(File(root, "incident-2000-200").exists())
        assertTrue(File(root, "incident-3000-300").exists())
        assertTrue(unrelated.exists())
    }

    @Test fun largeSectionKeepsOnlyItsBoundedTail() {
        val source = temporary.newFile().apply { writeText("123456789") }
        val output = temporary.newFile()
        BluetoothIncidentFiles.copyTail(source, output, 4)
        assertEquals("6789", output.readText())
        assertEquals("123456789", source.readText())
    }

    @Test fun smallAndEmptySectionsArePreserved() {
        val source = temporary.newFile()
        val output = temporary.newFile()
        BluetoothIncidentFiles.copyTail(source, output)
        assertEquals(0L, output.length())
        source.writeText("example")
        BluetoothIncidentFiles.copyTail(source, output)
        assertEquals("example", output.readText())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathTraversal() {
        BluetoothIncidentFiles(temporary.newFolder()).create("../other")
    }

    @Test(expected = IllegalStateException::class)
    fun doesNotOverwriteAnExistingIncident() {
        val store = BluetoothIncidentFiles(temporary.newFolder())
        store.create("1000-100")
        store.create("1000-100")
    }
}
