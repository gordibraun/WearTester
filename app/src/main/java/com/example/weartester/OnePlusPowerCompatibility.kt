package com.example.weartester

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object OnePlusPowerCompatibility {
    private const val PREFS = "oneplus_power_compatibility"
    private const val ENABLED = "enabled"
    private const val OWNED_BOOT = "owned_boot"
    private const val STATUS = "status"
    private const val CHECK_INTERVAL_MS = 5 * 60_000L
    private const val TESTED_FINGERPRINT =
        "OnePlus/OPWWE251/OPWWE251:14/AW2A.240903.001.A3.OPWWE251_11_A.162.260526/01:user/release-keys"
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "OnePlusPower") }
    @Volatile private var collectorRunning = false
    private var nextCheckAt = 0L
    private var queued = false
    private var forcePending = false

    fun isSupported() = Build.FINGERPRINT == TESTED_FINGERPRINT
    fun isEnabled(context: Context) = prefs(context).getBoolean(ENABLED, false)
    fun hasPermission(context: Context) =
        context.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED

    fun status(context: Context): String = prefs(context).getString(STATUS, null) ?: "Совместимость OnePlus: выключена"

    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        if (!prefs(app).edit().putBoolean(ENABLED, enabled).commit()) {
            report(app, "Не удалось сохранить настройку OnePlus")
            return
        }
        requestCheck(app, force = true)
    }

    fun collectorStarted(context: Context) {
        collectorRunning = true
        requestCheck(context, force = true)
    }

    fun collectorStopped(context: Context) {
        collectorRunning = false
        requestCheck(context, force = true)
    }

    @Synchronized
    fun requestCheck(context: Context, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (queued) {
            if (force) forcePending = true
            return
        }
        if (!force && now < nextCheckAt) return
        queued = true
        nextCheckAt = now + CHECK_INTERVAL_MS
        val app = context.applicationContext
        worker.execute {
            try {
                reconcile(app)
            } catch (error: Exception) {
                report(app, "Системный режим не подтверждён: ${error.javaClass.simpleName}")
            } finally {
                synchronized(this) {
                    queued = false
                    if (forcePending) {
                        forcePending = false
                        requestCheck(app, force = true)
                    }
                }
            }
        }
    }

    private fun reconcile(context: Context) {
        val store = prefs(context)
        val wanted = isEnabled(context) && collectorRunning
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (store.contains(OWNED_BOOT) && boot >= 0 && store.getInt(OWNED_BOOT, -1) != boot) {
            check(store.edit().remove(OWNED_BOOT).commit())
        }
        if (!wanted && !store.contains(OWNED_BOOT)) {
            report(context, if (isEnabled(context)) "Совместимость OnePlus: ждёт запуска сборщика" else "Совместимость OnePlus: выключена")
            return
        }
        if (!isSupported()) {
            report(context, "Совместимость OnePlus: эта прошивка не проверена")
            return
        }
        if (!hasPermission(context)) {
            report(context, if (store.contains(OWNED_BOOT)) "Нет DUMP: возврат ограничения не подтверждён" else "Совместимость OnePlus: требуется разрешение DUMP через ADB")
            return
        }
        val port = object : OnePlusPowerPolicy.Port {
            override fun ownedBoot(): Int? = if (store.contains(OWNED_BOOT)) store.getInt(OWNED_BOOT, -1) else null
            override fun saveOwnedBoot(boot: Int?): Boolean = store.edit().apply {
                if (boot == null) remove(OWNED_BOOT) else putInt(OWNED_BOOT, boot)
            }.commit()
            override fun readRestriction() = OnePlusPowerPolicy.parseRestriction(command("-sbpm"))
            override fun writeRestriction(enabled: Boolean) {
                command("-ebpm", enabled.toString())
            }
        }
        val result = OnePlusPowerPolicy.reconcile(wanted, boot, port)
        report(context, when (result) {
            OnePlusPowerPolicy.Result.ACTIVE -> "Совместимость OnePlus: системное ограничение снято; экран может спать"
            OnePlusPowerPolicy.Result.OFF -> "Совместимость OnePlus: ограничение возвращено"
            OnePlusPowerPolicy.Result.FAILED -> "Совместимость OnePlus: системное состояние не подтверждено"
            OnePlusPowerPolicy.Result.RESTORE_PENDING -> "OnePlus: не удалось вернуть ограничение; требуется проверка"
        })
    }

    private fun command(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("/system/bin/dumpsys", "-t", "2", "app.manager") + arguments)
            .redirectErrorStream(true).start()
        try {
            check(process.waitFor(3, TimeUnit.SECONDS)) { "System command timeout" }
            check(process.exitValue() == 0) { "System command failed" }
            return process.inputStream.bufferedReader().use { it.readText() }
        } finally {
            process.destroyForcibly()
        }
    }

    private fun report(context: Context, message: String) {
        if (prefs(context).getString(STATUS, null) == message) return
        prefs(context).edit().putString(STATUS, message).apply()
        ConnectionJournal.record(context, "oneplus_power_compatibility", "status" to message)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
