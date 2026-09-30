package com.example.weartester

import java.io.File

class RollingJournal(private val directory: File, private val maxBytes: Long = 512 * 1024, private val files: Int = 4) {
    init { require(maxBytes > 0 && files > 0) }
    @Synchronized fun append(line: String) {
        directory.mkdirs()
        val current = File(directory, "connection-0.jsonl")
        val text = line.replace('\n', ' ').replace('\r', ' ').take(2048) + "\n"
        if (current.length() + text.toByteArray(Charsets.UTF_8).size > maxBytes) {
            File(directory, "connection-${files - 1}.jsonl").delete()
            for (i in files - 2 downTo 0) File(directory, "connection-$i.jsonl").renameTo(File(directory, "connection-${i + 1}.jsonl"))
        }
        current.appendText(text, Charsets.UTF_8)
    }
}
