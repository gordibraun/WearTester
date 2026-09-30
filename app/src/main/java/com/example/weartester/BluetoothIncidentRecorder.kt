package com.example.weartester

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object BluetoothIncidentRecorder {
    private const val PREFS = "bluetooth_incidents"
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "BluetoothIncident") }
    private var queued = false
    private var nextCaptureElapsed = 0L

    fun status(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("status", "Диагностика: снимков сбоя пока нет") ?: ""

    fun capture(context: Context, reason: String, vararg fields: Pair<String, Any?>) {
        try {
            enqueue(context, reason, fields)
        } catch (error: Exception) {
            synchronized(this) { queued = false }
            Log.w("BluetoothIncident", "Cannot queue diagnostic", error)
        }
    }

    @Synchronized
    private fun enqueue(context: Context, reason: String, fields: Array<out Pair<String, Any?>>) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (queued || nowElapsed < nextCaptureElapsed) return
        queued = true
        nextCaptureElapsed = nowElapsed + 30_000L
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        // No disk or dumpsys work runs on a GATT/Binder callback thread.
        worker.execute {
            try {
                @Suppress("DEPRECATION")
                val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
                val details = JSONObject().put("at", now).put("elapsed", nowElapsed)
                    .put("reason", reason).put("version", packageInfo.versionName)
                    .put("version_code", packageInfo.longVersionCode)
                    .put("fingerprint", Build.FINGERPRINT)
                    .put("boot_count", Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1))
                fields.forEach { (name, value) -> details.put(name, value ?: JSONObject.NULL) }
                val directory = BluetoothIncidentFiles(File(app.noBackupFilesDir, "bluetooth-incidents"))
                    .create("$now-$nowElapsed")
                File(directory, "event.json").writeText(details.toString(), Charsets.UTF_8)
                for (index in 0..1) {
                    val journal = File(app.filesDir, "connection-journal/connection-$index.jsonl")
                    if (journal.exists()) runCatching {
                        BluetoothIncidentFiles.copyTail(journal, File(directory, "connection-$index.jsonl"))
                    }
                }
                val permitted = app.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED
                val completeDumps = if (permitted) {
                    listOf(
                        dump(directory, "bluetooth", "bluetooth_manager"),
                        dump(directory, "process-exits", "activity", "exit-info", "com.android.bluetooth"),
                        dump(directory, "power", "power"),
                    ).count { it }
                } else {
                    File(directory, "permission.txt").writeText("DUMP is not granted; system snapshots unavailable.\n")
                    0
                }
                val text = when {
                    !permitted -> "Диагностика: события сохранены; нет разрешения DUMP"
                    completeDumps == 3 -> "Диагностика: снимок сбоя сохранён"
                    else -> "Диагностика: снимок частичный, системных разделов $completeDumps из 3"
                }
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("status", text).apply()
                ConnectionJournal.record(app, "bluetooth_incident_saved", "directory" to directory.name,
                    "reason" to reason, "dump_permission" to permitted, "complete_dumps" to completeDumps)
            } catch (error: Exception) {
                ConnectionJournal.record(app, "bluetooth_incident_failed", "error" to error.javaClass.simpleName)
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("status", "Диагностика: не удалось сохранить снимок сбоя").apply()
            } finally {
                synchronized(this) { queued = false }
            }
        }
    }

    private fun dump(directory: File, name: String, vararg arguments: String): Boolean {
        val temporary = File(directory, "$name.tmp")
        val output = File(directory, "$name.txt")
        val result = JSONObject()
        var process: java.lang.Process? = null
        try {
            process = ProcessBuilder(listOf("/system/bin/dumpsys", "-t", "2") + arguments)
                .redirectErrorStream(true).redirectOutput(temporary).start()
            val complete = process.waitFor(3, TimeUnit.SECONDS)
            result.put("timed_out", !complete)
            if (complete) result.put("exit_code", process.exitValue())
        } catch (error: Exception) {
            result.put("error", error.javaClass.simpleName)
        } finally {
            process?.destroyForcibly()
            process?.waitFor(1, TimeUnit.SECONDS)
            if (temporary.exists()) {
                result.put("original_bytes", temporary.length())
                BluetoothIncidentFiles.copyTail(temporary, output)
                temporary.delete()
            }
            File(directory, "$name-result.json").writeText(result.toString(), Charsets.UTF_8)
        }
        return !result.optBoolean("timed_out", true) && result.optInt("exit_code", -1) == 0 &&
            output.exists() && output.length() > 0L && !output.readText().contains("Permission Denial")
    }
}
