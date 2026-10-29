package com.example.weartester

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * «Калибровка»: the owner types the meter's number, the watch says what it thinks of the moment
 * (see [CalibrationPolicy.check]) and, on a second confirmation, queues it for the sensor. The
 * collector sends it at the next contact and the verdict lands here and in the notification.
 */
class CalibrationActivity : Activity() {

    private lateinit var scroll: ScrollView
    private lateinit var sensorLine: TextView
    private lateinit var valueView: TextView
    private lateinit var mgdlView: TextView
    private lateinit var verdictView: TextView
    private lateinit var sendButton: Button
    private lateinit var cancelButton: Button
    private lateinit var historyView: TextView

    /** Tenths of mmol/L, so the crown and the buttons step cleanly. */
    private var tenths = 56
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val direct = DexcomConfigStore.loadDirectGlucose(this)
        direct.mgdl?.let { tenths = (CalibrationPolicy.mgdlToMmol(it) * 10).roundToInt() }
        savedInstanceState?.getInt("tenths")?.let { if (it > 0) tenths = it }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(22), dp(18), dp(40))
            setBackgroundColor(Color.BLACK)
        }
        root.addView(text("Калибровка G7", 18f, Color.WHITE, bold = true))
        root.addView(text("Цифра с глюкометра уйдёт в сам сенсор, как в xDrip. Сенсор решит сам и пересчитает свои показания.", 11f, DIM).apply { gravity = Gravity.CENTER })
        sensorLine = text("", 12f, DIM).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6)) }
        root.addView(sensorLine)

        valueView = text("", 34f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
        root.addView(valueView)
        mgdlView = text("", 13f, DIM).apply { gravity = Gravity.CENTER }
        root.addView(mgdlView)
        root.addView(row(
            button("−1") { step(-10) }, button("−0,1") { step(-1) }, button("+0,1") { step(1) }, button("+1") { step(10) }
        ))
        root.addView(text("Колёсико тоже меняет", 10f, DIM).apply { gravity = Gravity.CENTER })

        verdictView = text("", 12f, WARN).apply { setPadding(0, dp(8), 0, dp(4)) }
        root.addView(verdictView)

        sendButton = button("Отправить сенсору") { confirmSend() }.apply { textSize = 15f; setBackgroundColor(Color.parseColor("#2E7D32")) }
        root.addView(sendButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })
        cancelButton = button("Отменить отправку") { CalibrationStore.cancel(this, "owner"); render() }
        root.addView(cancelButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) })

        historyView = text("", 11f, DIM).apply { setPadding(0, dp(12), 0, 0) }
        root.addView(historyView)

        scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(root) }
        setContentView(scroll)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tenths", tenths)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val delta = event.getAxisValue(MotionEvent.AXIS_SCROLL).takeIf { it != 0f } ?: event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (delta != 0f) {
                step(if (delta < 0) 1 else -1)
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun step(byTenths: Int) {
        tenths = (tenths + byTenths).coerceIn(22, 222)
        render()
    }

    private fun meterMgdl(): Int = CalibrationPolicy.mmolToMgdl(tenths / 10.0)

    private fun sensorNow(): CalibrationPolicy.Sensor {
        val direct = DexcomConfigStore.loadDirectGlucose(this)
        val life = SensorSessionStore.load(this)
        return CalibrationPolicy.Sensor(
            mgdl = direct.mgdl,
            readingAtEpochMs = direct.receivedAtMillis.takeIf { it > 0 }?.minus(direct.ageSeconds * 1000L),
            trendTenths = direct.trendTenths,
            state = life.state,
            startedAtEpochMs = life.startedAt,
        )
    }

    private fun verdict(): CalibrationPolicy.Verdict {
        val now = System.currentTimeMillis()
        return CalibrationPolicy.check(
            meterMgdl = meterMgdl(), sensor = sensorNow(), nowEpochMs = now,
            pending = CalibrationStore.pending(this) != null,
            lastSentAtEpochMs = CalibrationStore.lastSentAtEpochMs(this),
            sentLast24h = CalibrationStore.sentLast24h(this, now),
        )
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val sensor = sensorNow()
        val mgdl = meterMgdl()
        valueView.text = String.format(Locale.US, "%.1f ммоль/л", tenths / 10.0)
        mgdlView.text = "= $mgdl мг/дл" + (sensor.mgdl?.let { s -> "   (сенсор $s, разница ${if (mgdl - s >= 0) "+" else ""}${mgdl - s})" } ?: "")
        sensorLine.text = buildString {
            val s = sensor.mgdl
            if (s == null || sensor.readingAtEpochMs == null) append("Показаний сенсора ещё нет")
            else {
                val minutes = ((now - sensor.readingAtEpochMs) / 60_000).coerceAtLeast(0)
                append("Сенсор: $s мг/дл, $minutes мин назад")
                sensor.trendTenths?.let { append(if (kotlin.math.abs(it) <= CalibrationPolicy.FLAT_TREND_TENTHS) ", ровно" else ", меняется ${it / 10.0}/мин") }
            }
            if (sensor.startedAtEpochMs > 0) append("\nсенсору ${((now - sensor.startedAtEpochMs) / 3600_000.0).let { h -> if (h < 48) "${h.roundToInt()} ч" else "${(h / 24).roundToInt()} дн" }}")
        }
        val pending = CalibrationStore.pending(this)
        val v = verdict()
        verdictView.text = when {
            pending != null -> "⏳ ${pending.mgdl} мг/дл ждёт контакта с сенсором: обычно до 5 минут. Ответ придёт вибрацией."
            else -> (v.blocks.map { "⛔ $it" } + v.warnings.map { "⚠ $it" }).joinToString("\n").ifEmpty { "✓ Момент подходящий" }
        }
        verdictView.setTextColor(if (pending != null) ACCENT else if (v.blocks.isNotEmpty()) BAD else if (v.warnings.isNotEmpty()) WARN else GOOD)
        sendButton.isEnabled = pending == null && v.allowed
        sendButton.alpha = if (sendButton.isEnabled) 1f else 0.4f
        cancelButton.visibility = if (pending != null) android.view.View.VISIBLE else android.view.View.GONE
        val history = CalibrationStore.history(this).take(5)
        historyView.text = if (history.isEmpty()) "Калибровок ещё не было" else "Последние:\n" + history.joinToString("\n") { r ->
            val time = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(r.atEpochMs))
            val value = if (r.mgdl > 0) "${r.mgdl} мг/дл" else "?"
            val mark = when {
                r.code == null -> "…"
                r.code == -1 -> "✖"
                r.code in setOf(0, 6, 13) -> "✓"
                else -> "✖"
            }
            "$mark $time $value: ${r.text}"
        }
    }

    private fun confirmSend() {
        val v = verdict()
        if (!v.allowed) return
        val mgdl = meterMgdl()
        val message = buildString {
            append("Отправить в сенсор ${String.format(Locale.US, "%.1f", tenths / 10.0)} ммоль/л = $mgdl мг/дл?\n")
            append("Время прокола: сейчас. Отменить потом нельзя: сенсор пересчитает показания сам.")
            if (v.warnings.isNotEmpty()) append("\n\n" + v.warnings.joinToString("\n") { "⚠ $it" })
        }
        AlertDialog.Builder(this)
            .setTitle("Калибровка")
            .setMessage(message)
            .setPositiveButton(if (v.warnings.isEmpty()) "Отправить" else "Всё равно отправить") { _, _ ->
                val now = System.currentTimeMillis()
                CalibrationStore.queue(this, CalibrationPolicy.Request(mgdl, now), now)
                // The collector sends it at the next contact; make sure it is running.
                runCatching { androidx.core.content.ContextCompat.startForegroundService(this, android.content.Intent(this, BondProbeService::class.java)) }
                render()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun row(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { b -> addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(2); marginEnd = dp(2) }) }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#263238"))
        setOnClickListener { onClick() }
    }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        val DIM = Color.parseColor("#B0BEC5")
        val WARN = Color.parseColor("#FFD54F")
        val BAD = Color.parseColor("#FF8A65")
        val GOOD = Color.parseColor("#81C784")
        val ACCENT = Color.parseColor("#90CAF9")
    }
}
