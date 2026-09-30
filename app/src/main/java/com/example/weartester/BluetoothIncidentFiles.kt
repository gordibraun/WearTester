package com.example.weartester

import java.io.File
import java.io.RandomAccessFile

internal class BluetoothIncidentFiles(private val root: File, private val retained: Int = 12) {
    init { require(retained > 0) }

    fun create(id: String): File {
        require(id.matches(Regex("[0-9]+-[0-9]+")))
        check(root.isDirectory || root.mkdirs())
        val directory = File(root, "incident-$id")
        check(directory.mkdir())
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("incident-") }
            .sortedByDescending { it.name }.drop(retained).forEach { it.deleteRecursively() }
        return directory
    }

    companion object {
        const val MAX_SECTION_BYTES = 256 * 1024

        fun copyTail(source: File, destination: File, limit: Int = MAX_SECTION_BYTES) {
            require(limit > 0)
            RandomAccessFile(source, "r").use { input ->
                val size = input.length()
                val count = minOf(size, limit.toLong()).toInt()
                input.seek(size - count)
                val bytes = ByteArray(count)
                input.readFully(bytes)
                destination.writeBytes(bytes)
            }
        }
    }
}
