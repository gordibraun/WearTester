package com.example.weartester

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var mainScrollView: ScrollView
    private lateinit var rootContainer: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var transmitterField: EditText
    private lateinit var sensorCodeField: EditText
    private lateinit var statusView: TextView
    private lateinit var transmitterInput: FieldInput
    private lateinit var sensorInput: FieldInput
    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: before setContentView")
        prepareWindowForPairingAttention()
        setContentView(R.layout.activity_main)
        Log.d(TAG, "onCreate: after setContentView")

        mainScrollView = findViewById(R.id.mainScrollView)
        rootContainer = findViewById(R.id.rootContainer)
        titleView = findViewById(R.id.titleView)
        transmitterField = findViewById(R.id.transmitterIdField)
        sensorCodeField = findViewById(R.id.sensorCodeField)
        statusView = findViewById(R.id.statusView)
        Log.d(TAG, "onCreate: view binding complete")

        configureWearInput()

        findViewById<Button>(R.id.editTransmitterButton).setOnClickListener {
            showEditDialog(transmitterInput)
        }
        findViewById<Button>(R.id.editSensorButton).setOnClickListener {
            showEditDialog(sensorInput)
        }
        findViewById<Button>(R.id.startButton).setOnClickListener {
            saveConfig()
            ensurePermissionsAndStart()
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, BondProbeService::class.java))
            refreshStatus(getString(R.string.status_stopped))
        }
        Log.d(TAG, "onCreate: listeners wired")

        populateFields()
        Log.d(TAG, "onCreate: populateFields complete")
        refreshStatus()
        Log.d(TAG, "onCreate: refreshStatus complete")
        handleIntentAction(intent)
        Log.d(TAG, "onCreate: handleIntentAction complete")
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (handleRotaryScroll(event)) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_NAVIGATE_NEXT,
                -> {
                    mainScrollView.smoothScrollBy(0, ROTARY_SCROLL_PIXELS)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_NAVIGATE_PREVIOUS,
                -> {
                    mainScrollView.smoothScrollBy(0, -ROTARY_SCROLL_PIXELS)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        prepareWindowForPairingAttention()
        refreshStatus()
        uiHandler.removeCallbacks(refreshTicker)
        uiHandler.post(refreshTicker)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(refreshTicker)
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return

        val missing = PermissionUtils.missingPermissions(this)
        if (missing.isEmpty()) {
            startProbeService()
            refreshStatus(getString(R.string.status_permissions_ok))
        } else {
            refreshStatus(
                getString(
                    R.string.status_permissions_missing,
                    missing.joinToString(separator = ", "),
                ),
            )
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent")
        if (intent != null) {
            setIntent(intent)
            handleIntentAction(intent)
        }
    }

    private fun populateFields() {
        val config = DexcomConfigStore.load(this)
        transmitterField.setText(config.transmitterId)
        sensorCodeField.setText(config.sensorCode)
    }

    private fun configureWearInput() {
        mainScrollView.setOnGenericMotionListener { _, event -> handleRotaryScroll(event) }
        transmitterInput = FieldInput(
            field = transmitterField,
            title = getString(R.string.dialog_title_transmitter),
            maxLength = 6,
            digitsOnly = false,
        )
        sensorInput = FieldInput(
            field = sensorCodeField,
            title = getString(R.string.dialog_title_sensor),
            maxLength = 4,
            digitsOnly = true,
        )
        configureEditableField(transmitterInput)
        configureEditableField(sensorInput)
        mainScrollView.requestFocus()
    }

    private fun configureEditableField(inputConfig: FieldInput) {
        val field = inputConfig.field
        field.isEnabled = true
        field.isClickable = true
        field.isFocusable = true
        field.isFocusableInTouchMode = true
        field.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                showEditDialog(inputConfig)
            }
            true
        }
        field.setOnClickListener {
            showEditDialog(inputConfig)
        }
    }

    private fun showEditDialog(inputConfig: FieldInput) {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(inputConfig.field.text?.toString().orEmpty())
            selectAll()
            inputType = if (inputConfig.digitsOnly) {
                android.text.InputType.TYPE_CLASS_NUMBER
            } else {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            }
            filters = inputConfig.maxLength?.let {
                arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(it))
            } ?: emptyArray()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(inputConfig.title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                inputConfig.field.setText(input.text?.toString().orEmpty())
                saveConfig()
                refreshStatus(getString(R.string.config_saved))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            val inputManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputManager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    private fun handleRotaryScroll(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL) return false

        val scrollAxis = event.getAxisValue(MotionEvent.AXIS_SCROLL)
        val verticalAxis = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val delta = if (scrollAxis != 0f) scrollAxis else verticalAxis
        if (delta == 0f) return false

        Log.d(TAG, "Rotary/generic scroll: source=${event.source}, delta=$delta")
        mainScrollView.smoothScrollBy(0, (-delta * ROTARY_SCROLL_PIXELS).toInt())
        mainScrollView.requestFocus()
        return true
    }

    private fun saveConfig() {
        val previous = DexcomConfigStore.load(this)
        val transmitterId = transmitterField.text?.toString().orEmpty()
        DexcomConfigStore.save(
            this,
            DexcomConfig(
                transmitterId = transmitterId,
                sensorCode = sensorCodeField.text?.toString().orEmpty(),
                knownMac = if (previous.transmitterId.equals(transmitterId.trim(), ignoreCase = true)) {
                    previous.knownMac
                } else {
                    ""
                },
            ),
        )
    }

    private fun ensurePermissionsAndStart() {
        val missing = PermissionUtils.missingPermissions(this)
        if (missing.isNotEmpty()) {
            requestPermissions(missing, REQUEST_PERMISSIONS)
            refreshStatus(
                getString(
                    R.string.status_permissions_request,
                    missing.joinToString(separator = ", "),
                ),
            )
            return
        }
        startProbeService()
    }

    private fun startProbeService() {
        ContextCompat.startForegroundService(this, Intent(this, BondProbeService::class.java))
        refreshStatus(getString(R.string.status_started))
    }

    private fun handleIntentAction(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_FORCE_PAIRING_ATTENTION, false)) {
            val deviceName = intent.getStringExtra("pairing_device_name").orEmpty()
            val deviceMac = intent.getStringExtra("pairing_device_mac").orEmpty()
            val label = listOf("ПОДТВЕРДИТЕ PAIRING СЕЙЧАС", deviceName.ifBlank { null }, deviceMac.ifBlank { null })
                .filterNotNull()
                .joinToString("\n")
            refreshStatus(label)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_TIMER_FOCUS, false)) {
            refreshStatus("Окно Dexcom и таймер")
        }
        if (intent.getBooleanExtra(EXTRA_AUTO_START_PROBE, false)) {
            ensurePermissionsAndStart()
        }
    }

    @Suppress("DEPRECATION")
    private fun prepareWindowForPairingAttention() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun refreshStatus(prefix: String? = null) {
        val config = DexcomConfigStore.load(this)
        val scanDebug = DexcomConfigStore.loadScanDebug(this)
        val glucose = DexcomConfigStore.loadGlucose(this)
        val transmitter = config.transmitterId.ifBlank { getString(R.string.status_empty_value) }
        val sensorCode = config.sensorCode.ifBlank { getString(R.string.status_empty_value) }
        val missingPerms = PermissionUtils.missingPermissions(this)
        val permissionStatus = if (missingPerms.isEmpty()) {
            getString(R.string.status_permissions_granted)
        } else {
            getString(
                R.string.status_permissions_missing_short,
                missingPerms.joinToString(separator = ", "),
            )
        }
        val lines = listOfNotNull(
            prefix,
            getString(R.string.status_summary, transmitter, sensorCode),
            getString(
                R.string.status_scan_debug,
                scanDebug.seenName.ifBlank { getString(R.string.status_empty_value) },
                scanDebug.seenMac.ifBlank { getString(R.string.status_empty_value) },
                scanDebug.seenRssi.ifBlank { getString(R.string.status_empty_value) },
                scanDebug.lastEvent.ifBlank { getString(R.string.status_empty_value) },
            ),
            buildGlucoseLine(glucose),
            buildDexcomTimerLine(scanDebug),
            permissionStatus,
        )
        statusView.text = lines.joinToString(separator = "\n")
        applyStatusColors(scanDebug.lastEvent)
    }

    private fun buildGlucoseLine(glucose: GlucoseReadingState): String {
        val value = glucose.mgdl?.let { "$it mg/dL" } ?: "ещё нет"
        val age = ElapsedTimeFormatter.elapsedText(glucose.receivedAtMillis, emptyText = "ещё не было")
        return "Последняя глюкоза: $value, получена $age"
    }

    private fun buildDexcomTimerLine(scanDebug: ScanDebugState): String {
        val now = System.currentTimeMillis()
        val lastEvent = ElapsedTimeFormatter.elapsedText(scanDebug.lastEventAtMillis, now)
        val lastBond = ElapsedTimeFormatter.elapsedText(scanDebug.lastBondAtMillis, now)
        val nextWindow = formatNextWindow(scanDebug.lastEventAtMillis, now)
        return "Dexcom таймер: событие $lastEvent, bond/auth $lastBond, окно ~$nextWindow"
    }

    private fun formatNextWindow(eventAtMillis: Long, nowMillis: Long): String {
        if (eventAtMillis <= 0L) return "ждём первое"
        val elapsedSeconds = ((nowMillis - eventAtMillis) / 1000L).coerceAtLeast(0L)
        val remainingSeconds = (DEXCOM_WINDOW_SECONDS - elapsedSeconds).coerceAtLeast(0L)
        return if (remainingSeconds == 0L) {
            "сейчас"
        } else {
            "${remainingSeconds / 60L}м ${remainingSeconds % 60L}с"
        }
    }

    private fun applyStatusColors(lastEvent: String) {
        val normalized = lastEvent.lowercase()
        val (rootColor, cardColor, titleColor) = when {
            "pairing request" in normalized -> Triple("#5A2A00", "#FF8A00", "#FFF4E5")
            "dexcom connected" in normalized || "sensor rx" in normalized || "glucose" in normalized ->
                Triple("#0F2B1D", "#1FAA59", "#E8FFF1")
            "matched advertisement" in normalized || "auth=1" in normalized || "bonded=1" in normalized ->
                Triple("#10293D", "#1F7AE0", "#EAF4FF")
            "missing" in normalized || "scan failed" in normalized || "not authorized" in normalized ->
                Triple("#351417", "#C62828", "#FFECEC")
            else -> Triple("#101418", "#24313F", "#F5F7FA")
        }
        rootContainer.setBackgroundColor(Color.parseColor(rootColor))
        statusView.setBackgroundColor(Color.parseColor(cardColor))
        titleView.setTextColor(Color.parseColor(titleColor))
        statusView.setTextColor(Color.parseColor("#F5F7FA"))
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
        const val EXTRA_AUTO_START_PROBE = "auto_start_probe"
        const val EXTRA_FORCE_PAIRING_ATTENTION = "force_pairing_attention"
        const val EXTRA_OPEN_TIMER_FOCUS = "open_timer_focus"
        private const val TAG = "WearTesterMain"
        private const val DEXCOM_WINDOW_SECONDS = 5L * 60L
        private const val ROTARY_SCROLL_PIXELS = 140
    }

    private data class FieldInput(
        val field: EditText,
        val title: String,
        val maxLength: Int?,
        val digitsOnly: Boolean,
    )
}
