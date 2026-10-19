package com.example.weartester

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Toast

/**
 * Three quick taps open something; one or two do nothing beyond a hint.
 *
 * The sensor setup sits right under the glucose readout on a small round screen, and a single
 * stray tap while walking through the app opened an editor or flipped the sensor model, which
 * then reset the pairing and lost the sensor for an hour (18 and 19 Oct 2026). Anything that
 * changes the sensor setup is now behind this gate.
 */
object TripleTap {
    private const val WINDOW_MS = 700L
    private const val HINT_EVERY_MS = 8_000L
    const val TAPS = 3

    @SuppressLint("ClickableViewAccessibility")
    fun attach(view: View, hint: String, action: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var taps = 0
        var lastHintAt = 0L
        val expire = Runnable {
            if (taps in 1 until TAPS) {
                val now = System.currentTimeMillis()
                if (now - lastHintAt >= HINT_EVERY_MS) {
                    Toast.makeText(view.context, hint, Toast.LENGTH_SHORT).show()
                    lastHintAt = now
                }
            }
            taps = 0
        }
        // The view's own click handling is replaced: a single tap must not reach it.
        view.setOnClickListener(null)
        view.isClickable = true
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    taps++
                    handler.removeCallbacks(expire)
                    if (taps >= TAPS) {
                        taps = 0
                        action()
                    } else {
                        handler.postDelayed(expire, WINDOW_MS)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(expire)
                    taps = 0
                }
            }
            true
        }
    }
}
