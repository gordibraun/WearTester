package com.example.weartester

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.util.Locale

/**
 * A low glucose makes the watch vibrate (30.10.2026, after a two-hour night low that nothing
 * announced). Pure rules here; [LowGlucoseAlarmGuard] does the vibrating.
 *
 * Below [lowMgdl] the alarm fires and repeats every [repeatLowMs]; below [urgentMgdl] it fires
 * again at once and then repeats every [repeatUrgentMs]. It is cleared when glucose is back at
 * [clearMgdl]. «Тихо 30 мин» silences a low, but not an urgent low.
 */
class LowGlucoseAlarm(
    val lowMgdl: Int = 70,
    val urgentMgdl: Int = 55,
    val clearMgdl: Int = 80,
    val repeatLowMs: Long = 10 * 60_000L,
    val repeatUrgentMs: Long = 5 * 60_000L
) {

    enum class Level { NONE, LOW, URGENT }

    data class State(val lastAlarmAtMs: Long = 0L, val level: Level = Level.NONE, val snoozedUntilMs: Long = 0L, val lowSinceMs: Long = 0L)

    sealed class Verdict {
        object Quiet : Verdict()
        data class Alarm(val level: Level, val mgdl: Int, val repeat: Boolean, val lowForMinutes: Int) : Verdict()
        object Cleared : Verdict()
    }

    fun evaluate(state: State, mgdl: Int, atMs: Long): Pair<State, Verdict> {
        if (mgdl <= 0) return state to Verdict.Quiet
        if (mgdl >= clearMgdl) {
            return if (state.level != Level.NONE) State() to Verdict.Cleared else state to Verdict.Quiet
        }
        if (mgdl >= lowMgdl) return state to Verdict.Quiet // between low and clear: nothing new, nothing cleared
        val level = if (mgdl < urgentMgdl) Level.URGENT else Level.LOW
        val lowSince = if (state.lowSinceMs > 0L) state.lowSinceMs else atMs
        val lowForMinutes = ((atMs - lowSince) / 60_000L).toInt()
        val snoozed = atMs < state.snoozedUntilMs && level == Level.LOW
        val escalated = level == Level.URGENT && state.level != Level.URGENT
        val interval = if (level == Level.URGENT) repeatUrgentMs else repeatLowMs
        val due = state.level == Level.NONE || escalated || atMs - state.lastAlarmAtMs >= interval
        if (snoozed || !due) return state.copy(level = maxOf(state.level, level), lowSinceMs = lowSince) to Verdict.Quiet
        return State(lastAlarmAtMs = atMs, level = level, snoozedUntilMs = state.snoozedUntilMs, lowSinceMs = lowSince) to
            Verdict.Alarm(level, mgdl, repeat = state.level != Level.NONE && !escalated, lowForMinutes = lowForMinutes)
    }

    fun snooze(state: State, atMs: Long, forMs: Long = 30 * 60_000L): State = state.copy(snoozedUntilMs = atMs + forMs)

    companion object {

        fun mmol(mgdl: Int): String = String.format(Locale.getDefault(), "%.1f", mgdl / 18.0)
    }
}

/** Keeps the alarm's state in the app's preferences and turns a verdict into vibration, a notice and a journal line. */
object LowGlucoseAlarmGuard {

    private const val TAG = "LowGlucoseAlarm"
    private const val PREFS = "low_glucose_alarm"
    private const val CHANNEL = "glucose_low"
    private const val NOTIF_ID = 47
    private val policy = LowGlucoseAlarm()

    fun load(context: Context): LowGlucoseAlarm.State = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { p ->
        LowGlucoseAlarm.State(
            lastAlarmAtMs = p.getLong("last_alarm_at", 0L),
            level = runCatching { LowGlucoseAlarm.Level.valueOf(p.getString("level", "NONE") ?: "NONE") }.getOrDefault(LowGlucoseAlarm.Level.NONE),
            snoozedUntilMs = p.getLong("snoozed_until", 0L),
            lowSinceMs = p.getLong("low_since", 0L)
        )
    }

    private fun save(context: Context, state: LowGlucoseAlarm.State) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("last_alarm_at", state.lastAlarmAtMs).putString("level", state.level.name)
            .putLong("snoozed_until", state.snoozedUntilMs).putLong("low_since", state.lowSinceMs).apply()
    }

    /** A confirmed reading from the sensor, mg/dL. Never throws: a failed alarm must not touch the reading's path. */
    fun onReading(context: Context, mgdl: Int, atMs: Long) {
        runCatching {
            val (state, verdict) = policy.evaluate(load(context), mgdl, atMs)
            save(context, state)
            when (verdict) {
                is LowGlucoseAlarm.Verdict.Alarm   -> {
                    vibrate(context, verdict.level)
                    notify(context, verdict)
                    ConnectionJournal.record(context, "low_alarm", "value" to mgdl, "level" to verdict.level.name, "repeat" to verdict.repeat, "low_for_min" to verdict.lowForMinutes)
                    Log.w(TAG, "low glucose $mgdl mg/dL: ${verdict.level}")
                }
                LowGlucoseAlarm.Verdict.Cleared    -> {
                    context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
                    ConnectionJournal.record(context, "low_alarm_cleared", "value" to mgdl)
                }
                LowGlucoseAlarm.Verdict.Quiet      -> Unit
            }
        }.onFailure { Log.e(TAG, "alarm failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    fun snooze(context: Context, atMs: Long) {
        runCatching {
            save(context, policy.snooze(load(context), atMs))
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
            ConnectionJournal.record(context, "low_alarm_snoozed", "until" to atMs + 30 * 60_000L)
        }
    }

    private fun vibrate(context: Context, level: LowGlucoseAlarm.Level) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        // Low: three long pulses. Urgent: six, harder to sleep through.
        val pattern = if (level == LowGlucoseAlarm.Level.URGENT) longArrayOf(0, 600, 250, 600, 250, 600, 400, 600, 250, 600, 250, 600)
        else longArrayOf(0, 500, 250, 500, 250, 700)
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun notify(context: Context, alarm: LowGlucoseAlarm.Verdict.Alarm) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Низкий сахар", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500)
                (RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.let {
                    setSound(it, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                }
            }
        )
        val snooze = PendingIntent.getBroadcast(
            context, 0, Intent(context, LowAlarmSnoozeReceiver::class.java).setAction(LowAlarmSnoozeReceiver.ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (alarm.level == LowGlucoseAlarm.Level.URGENT) "Очень низкий сахар" else "Низкий сахар"
        val text = "${LowGlucoseAlarm.mmol(alarm.mgdl)} ммоль/л (${alarm.mgdl})" +
            (if (alarm.lowForMinutes >= 5) ", ниже 3,9 уже ${alarm.lowForMinutes} мин" else "") + ". Съешьте быстрые углеводы."
        manager.notify(
            NOTIF_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_ALARM)
                .addAction(Notification.Action.Builder(null, "Тихо 30 мин", snooze).build())
                .setAutoCancel(true)
                .build()
        )
    }
}

/** «Тихо 30 мин» on the low-glucose notice. An urgent low still comes through. */
class LowAlarmSnoozeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION) LowGlucoseAlarmGuard.snooze(context, System.currentTimeMillis())
    }

    companion object {

        const val ACTION = "com.example.weartester.LOW_ALARM_SNOOZE"
    }
}
