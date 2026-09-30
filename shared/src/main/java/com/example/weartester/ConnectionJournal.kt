package com.example.weartester

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object ConnectionJournal {
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(256), ThreadPoolExecutor.DiscardOldestPolicy())
    fun record(context: Context, event: String, vararg values: Pair<String, Any?>) {
        val app = context.applicationContext
        val json = JSONObject().put("at", System.currentTimeMillis()).put("elapsed", SystemClock.elapsedRealtime())
            .put("pid", Process.myPid()).put("event", event)
        values.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        val line = json.toString()
        Log.i("ConnectionJournal", line)
        writer.execute { runCatching { RollingJournal(File(app.filesDir, "connection-journal")).append(line) }
            .onFailure { Log.w("ConnectionJournal", "Cannot persist connection diagnostic", it) } }
    }
}
