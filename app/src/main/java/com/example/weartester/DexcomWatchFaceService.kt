package com.example.weartester

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.RenderParameters
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.TapEvent
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyleSchema
import java.time.ZonedDateTime
import kotlin.math.min

private const val WATCH_FACE_AGE_REFRESH_MS = 30_000L

class DexcomWatchFaceService : WatchFaceService() {
    companion object {
        private const val TAG = "DexcomWatchFace"
    }

    override fun onCreate() {
        super.onCreate()
        ensureCollectorService()
    }

    override fun createUserStyleSchema(): UserStyleSchema = UserStyleSchema(emptyList())

    override fun createComplicationSlotsManager(
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): ComplicationSlotsManager = ComplicationSlotsManager(emptyList(), currentUserStyleRepository)

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): WatchFace {
        ensureCollectorService()
        val renderer = DexcomWatchFaceRenderer(
            applicationContext,
            surfaceHolder,
            currentUserStyleRepository,
            watchState,
        )
        return WatchFace(WatchFaceType.DIGITAL, renderer)
            .setTapListener(object : WatchFace.TapListener {
                override fun onTapEvent(
                    tapType: Int,
                    tapEvent: TapEvent,
                    complicationSlot: androidx.wear.watchface.ComplicationSlot?,
                ) {
                    Log.i(TAG, "Tap event type=$tapType x=${tapEvent.xPos} y=${tapEvent.yPos}")
                    if (!tapEvent.isInsideGlucoseShortcut()) return
                    ensureCollectorService()
                    val intent = Intent(applicationContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_OPEN_TIMER_FOCUS, true)
                        .putExtra(MainActivity.EXTRA_AUTO_START_PROBE, true)
                    val pendingIntent = PendingIntent.getActivity(
                        applicationContext,
                        7,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    runCatching { pendingIntent.send() }
                        .onFailure { Log.e(TAG, "Failed to open MainActivity from watch face tap", it) }
                }
            })
    }

    private fun ensureCollectorService() {
        val config = DexcomConfigStore.load(applicationContext)
        if (config.transmitterId.isBlank()) return
        runCatching {
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, BondProbeService::class.java),
            )
            Log.i(TAG, "Requested BondProbeService from watch face")
        }.onFailure {
            Log.e(TAG, "Failed to request BondProbeService from watch face", it)
        }
    }

    private fun TapEvent.isInsideGlucoseShortcut(): Boolean {
        val metrics = applicationContext.resources.displayMetrics
        val size = min(metrics.widthPixels, metrics.heightPixels).toFloat().coerceAtLeast(1f)
        val left = (metrics.widthPixels - size) / 2f
        val top = (metrics.heightPixels - size) / 2f
        val normalizedX = (xPos - left) / size
        val normalizedY = (yPos - top) / size
        return normalizedX in 0.16f..0.84f && normalizedY in 0.40f..0.72f
    }
}

private class DexcomWatchFaceRenderer(
    private val appContext: android.content.Context,
    surfaceHolder: SurfaceHolder,
    currentUserStyleRepository: CurrentUserStyleRepository,
    watchState: WatchState,
) : Renderer.CanvasRenderer2<DexcomWatchFaceRenderer.SharedAssets>(
    surfaceHolder,
    currentUserStyleRepository,
    watchState,
    CanvasType.HARDWARE,
    1000L,
    false,
) {
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshTicker = object : Runnable {
        override fun run() {
            postInvalidate()
            refreshHandler.postDelayed(this, WATCH_FACE_AGE_REFRESH_MS)
        }
    }
    private val backgroundPaint = Paint().apply { color = Color.BLACK }
    private val timePaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val glucosePaint = Paint().apply {
        color = Color.parseColor("#7CFF7C")
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val agePaint = Paint().apply {
        color = Color.parseColor("#B0BEC5")
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val labelPaint = Paint().apply {
        color = Color.parseColor("#4DD0E1")
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    init {
        refreshHandler.post(refreshTicker)
    }

    override fun onDestroy() {
        refreshHandler.removeCallbacks(refreshTicker)
        super.onDestroy()
    }

    override suspend fun createSharedAssets(): SharedAssets = SharedAssets()

    override fun render(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        sharedAssets: SharedAssets,
    ) {
        val glucose = DexcomConfigStore.loadGlucose(appContext)
        val now = System.currentTimeMillis()
        val size = min(bounds.width(), bounds.height()).toFloat()
        val isAmbient = renderParameters.drawMode == DrawMode.AMBIENT

        canvas.drawRect(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            backgroundPaint,
        )

        timePaint.textSize = size * 0.18f
        glucosePaint.textSize = size * 0.22f
        agePaint.textSize = size * 0.08f
        labelPaint.textSize = size * 0.07f

        if (isAmbient) {
            timePaint.color = Color.WHITE
            glucosePaint.color = Color.WHITE
            agePaint.color = Color.LTGRAY
            labelPaint.color = Color.LTGRAY
        } else {
            timePaint.color = Color.WHITE
            glucosePaint.color = when {
                glucose.mgdl == null -> Color.parseColor("#90A4AE")
                glucose.mgdl < 70 -> Color.parseColor("#FF8A80")
                glucose.mgdl > 180 -> Color.parseColor("#FFD180")
                else -> Color.parseColor("#7CFF7C")
            }
            agePaint.color = Color.parseColor("#B0BEC5")
            labelPaint.color = Color.parseColor("#4DD0E1")
        }

        val timeText = "%02d:%02d".format(zonedDateTime.hour, zonedDateTime.minute)
        val glucoseText = glucose.mgdl?.let { "$it" } ?: "--"
        val ageText = if (glucose.receivedAtMillis > 0L) {
            "обновлено ${ElapsedTimeFormatter.elapsedText(glucose.receivedAtMillis, now)}"
        } else {
            "нет данных"
        }
        val sourceText = if (glucose.receivedAtMillis > 0L) "Dexcom live" else "Ожидаю Dexcom"

        val centerX = bounds.exactCenterX()
        canvas.drawText(timeText, centerX, bounds.top + size * 0.28f, timePaint)
        canvas.drawText(glucoseText, centerX, bounds.top + size * 0.58f, glucosePaint)
        canvas.drawText("mg/dL", centerX, bounds.top + size * 0.69f, labelPaint)
        canvas.drawText(ageText, centerX, bounds.top + size * 0.81f, agePaint)
        canvas.drawText(sourceText, centerX, bounds.top + size * 0.90f, agePaint)
    }

    override fun renderHighlightLayer(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        sharedAssets: SharedAssets,
    ) {
        canvas.drawRect(bounds, backgroundPaint)
    }

    class SharedAssets : Renderer.SharedAssets {
        override fun onDestroy() = Unit
    }
}
