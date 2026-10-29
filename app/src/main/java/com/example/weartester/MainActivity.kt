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
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var mainScrollView: ScrollView
    private lateinit var rootContainer: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var transmitterField: EditText
    private lateinit var sensorCodeField: EditText
    private lateinit var g7PairingCodeField: EditText
    private lateinit var sensorModelButton: Button
    private lateinit var editG7PairingCodeButton: Button
    private lateinit var editTransmitterButton: Button
    private lateinit var editSensorButton: Button
    private lateinit var calibrationButton: Button
    private lateinit var statusView: TextView
    private lateinit var transmitterInput: FieldInput
    private lateinit var sensorInput: FieldInput
    private lateinit var g7PairingInput: FieldInput
    private var sensorModel: SensorModel = SensorModel.G6
    private val uiHandler = Handler(Looper.getMainLooper())
    private val endPairingAttention = Runnable { clearPairingAttention() }
    private val refreshTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: before setContentView")
        setContentView(R.layout.activity_main)
        Log.d(TAG, "onCreate: after setContentView")

        mainScrollView = findViewById(R.id.mainScrollView)
        rootContainer = findViewById(R.id.rootContainer)
        titleView = findViewById(R.id.titleView)
        transmitterField = findViewById(R.id.transmitterIdField)
        sensorCodeField = findViewById(R.id.sensorCodeField)
        g7PairingCodeField = findViewById(R.id.g7PairingCodeField)
        sensorModelButton = findViewById(R.id.sensorModelButton)
        editG7PairingCodeButton = findViewById(R.id.editG7PairingCodeButton)
        editTransmitterButton = findViewById(R.id.editTransmitterButton)
        editSensorButton = findViewById(R.id.editSensorButton)
        statusView = findViewById(R.id.statusView)
        Log.d(TAG, "onCreate: view binding complete")

        val controls = Button(this).apply {
            text = "Еда, инсулин, нагрузка"
            isAllCaps = false
            setOnClickListener {
                val launch = Intent().setClassName("info.nightscout.androidaps", "app.aaps.wear.interaction.menus.MainMenuActivity")
                if (launch.resolveActivity(packageManager) != null) startActivity(launch)
                else android.widget.Toast.makeText(this@MainActivity, "Нужно приложение AAPS на часах", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        rootContainer.addView(controls, 1)
        calibrationButton = Button(this).apply {
            text = "Калибровка G7"; isAllCaps = false
            setOnClickListener { startActivity(Intent(this@MainActivity, CalibrationActivity::class.java)) }
        }
        rootContainer.addView(calibrationButton, 2)
        rootContainer.addView(Button(this).apply {
            text = "Связь"; isAllCaps = false
            setOnClickListener {
                ConnectionMonitor.tick(this@MainActivity)
                AlertDialog.Builder(this@MainActivity).setTitle("Связь")
                    .setMessage(ConnectionMonitor.status(this@MainActivity) + "\n\n" + OnePlusPowerCompatibility.status(this@MainActivity)
                        + "\n\n" + BluetoothIncidentRecorder.status(this@MainActivity))
                    .setNeutralButton("Питание") { _, _ -> showPowerCompatibility() }
                    .setNegativeButton("Снимок") { _, _ -> BluetoothIncidentRecorder.capture(this@MainActivity, "manual") }
                    .setPositiveButton("ОК", null).show()
            }
        }, 2)

        configureWearInput()

        // Everything that changes the sensor setup opens only on three quick taps: a single stray
        // tap next to the glucose readout used to open an editor or flip the model (see TripleTap).
        TripleTap.attach(findViewById<Button>(R.id.editTransmitterButton), EDIT_HINT) {
            showEditDialog(transmitterInput)
        }
        TripleTap.attach(editG7PairingCodeButton, EDIT_HINT) {
            showEditDialog(g7PairingInput)
        }
        TripleTap.attach(sensorModelButton, "Сменить датчик: 3 быстрых нажатия") {
            sensorModel = if (sensorModel == SensorModel.G7) SensorModel.G6 else SensorModel.G7
            saveConfig()
            applySensorModel()
            refreshStatus(getString(R.string.config_saved))
        }
        TripleTap.attach(findViewById<Button>(R.id.editSensorButton), EDIT_HINT) {
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
        refreshStatus()
        uiHandler.removeCallbacks(refreshTicker)
        uiHandler.post(refreshTicker)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(refreshTicker)
        clearPairingAttention()
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(refreshTicker)
        uiHandler.removeCallbacks(endPairingAttention)
        super.onDestroy()
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
        g7PairingCodeField.setText(config.g7PairingCode)
        sensorModel = config.sensorModel
        applySensorModel()
    }

    /** One model's fields at a time: the other's stay stored, out of the way. */
    private fun applySensorModel() {
        val g7 = sensorModel == SensorModel.G7
        sensorModelButton.setText(if (g7) R.string.button_sensor_model_g7 else R.string.button_sensor_model_g6)
        val g7Visibility = if (g7) android.view.View.VISIBLE else android.view.View.GONE
        val g6Visibility = if (g7) android.view.View.GONE else android.view.View.VISIBLE
        g7PairingCodeField.visibility = g7Visibility
        editG7PairingCodeButton.visibility = g7Visibility
        transmitterField.visibility = g6Visibility
        editTransmitterButton.visibility = g6Visibility
        sensorCodeField.visibility = g6Visibility
        editSensorButton.visibility = g6Visibility
        if (::calibrationButton.isInitialized) calibrationButton.visibility = g7Visibility
    }

    private fun showPowerCompatibility() {
        fun statusText() = when {
            !OnePlusPowerCompatibility.isSupported() -> "Эта прошивка не проверена"
            !OnePlusPowerCompatibility.hasPermission(this) -> "Для включения требуется разрешение DUMP через ADB"
            else -> OnePlusPowerCompatibility.status(this)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (12 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val toggle = Switch(this).apply {
            text = "Совместимость OnePlus"
            isChecked = OnePlusPowerCompatibility.isEnabled(this@MainActivity)
        }
        val status = TextView(this).apply {
            text = statusText()
            textSize = 14f
        }
        content.addView(toggle)
        content.addView(status)
        var updating = false
        toggle.setOnCheckedChangeListener { _, enabled ->
            if (updating) return@setOnCheckedChangeListener
            if (!enabled) {
                OnePlusPowerCompatibility.setEnabled(this, false)
                status.text = "Возврат системной настройки..."
            } else {
                updating = true
                toggle.isChecked = false
                updating = false
                if (!OnePlusPowerCompatibility.isSupported() || !OnePlusPowerCompatibility.hasPermission(this)) {
                    status.text = if (!OnePlusPowerCompatibility.isSupported()) "Эта прошивка не проверена" else "Сначала требуется разрешение DUMP через ADB"
                    return@setOnCheckedChangeListener
                }
                AlertDialog.Builder(this).setTitle("Системное ограничение")
                    .setMessage("Снимает ограничение фонового пробуждения для всех приложений OnePlus. Экран не включается, но батарея может расходоваться быстрее. Режим повторно применяется при запуске сборщика, включая перезагрузку часов.")
                    .setPositiveButton("Включить") { _, _ ->
                        OnePlusPowerCompatibility.setEnabled(this, true)
                        updating = true
                        toggle.isChecked = OnePlusPowerCompatibility.isEnabled(this)
                        updating = false
                        status.text = "Проверка системной настройки..."
                    }
                    .setNegativeButton("Отмена", null).show()
            }
        }
        val dialog = AlertDialog.Builder(this).setTitle("Фоновая работа")
            .setView(ScrollView(this).apply { addView(content) }).setPositiveButton("ОК", null).create()
        val refresh = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                status.text = statusText()
                uiHandler.postDelayed(this, 1_000L)
            }
        }
        dialog.setOnDismissListener { uiHandler.removeCallbacks(refresh) }
        dialog.show()
        uiHandler.postDelayed(refresh, 1_000L)
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
        g7PairingInput = FieldInput(
            field = g7PairingCodeField,
            title = getString(R.string.dialog_title_g7_pairing_code),
            maxLength = 4,
            digitsOnly = true,
        )
        configureEditableField(transmitterInput)
        configureEditableField(sensorInput)
        configureEditableField(g7PairingInput)
        mainScrollView.requestFocus()
    }

    private fun configureEditableField(inputConfig: FieldInput) {
        val field = inputConfig.field
        field.isEnabled = true
        field.isClickable = true
        field.isFocusable = true
        field.isFocusableInTouchMode = true
        // A tap on the field itself used to open the editor at once; now three quick taps do.
        TripleTap.attach(field, EDIT_HINT) {
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
                val entered = input.text?.toString().orEmpty().trim()
                val current = DexcomConfigStore.load(this)
                // A paired G7 is lost with its code: the key and the sensor's name go with it, and
                // the sensor rejects every connection until the right code is back (18 Oct 2026, an
                // hour without glucose after a slip of the finger). So a change asks first.
                val breaksPairing = inputConfig === g7PairingInput && current.isG7 &&
                    entered != current.g7PairingCode && current.g7SensorName.isNotBlank()
                if (breaksPairing) {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.dialog_title_g7_code_change))
                        .setMessage(getString(R.string.dialog_message_g7_code_change, current.g7SensorName, current.g7PairingCode, entered))
                        .setPositiveButton(R.string.button_g7_code_change_confirm) { _, _ ->
                            inputConfig.field.setText(entered)
                            saveConfig()
                            refreshStatus(getString(R.string.config_saved))
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> refreshStatus(getString(R.string.g7_code_kept)) }
                        .show()
                    return@setPositiveButton
                }
                inputConfig.field.setText(entered)
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
        val pairingCode = g7PairingCodeField.text?.toString().orEmpty().trim()
        val sameSensor = if (sensorModel == SensorModel.G7) {
            previous.sensorModel == SensorModel.G7 && previous.g7PairingCode == pairingCode
        } else {
            previous.sensorModel == SensorModel.G6 && previous.transmitterId.equals(transmitterId.trim(), ignoreCase = true)
        }
        DexcomConfigStore.save(
            this,
            DexcomConfig(
                transmitterId = transmitterId,
                sensorCode = sensorCodeField.text?.toString().orEmpty(),
                knownMac = if (sameSensor) previous.knownMac else "",
                sensorModel = sensorModel,
                g7PairingCode = pairingCode,
                g7SensorName = if (sameSensor) previous.g7SensorName else "",
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
        if (intent.getBooleanExtra("bluetooth_diagnostic_snapshot", false)) {
            intent.removeExtra("bluetooth_diagnostic_snapshot")
            BluetoothIncidentRecorder.capture(this, "manual")
        }
        if (intent.getBooleanExtra(EXTRA_FORCE_PAIRING_ATTENTION, false)) {
            intent.removeExtra(EXTRA_FORCE_PAIRING_ATTENTION)
            prepareWindowForPairingAttention()
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
        uiHandler.removeCallbacks(endPairingAttention)
        uiHandler.postDelayed(endPairingAttention, 60_000L)
    }

    @Suppress("DEPRECATION")
    private fun clearPairingAttention() {
        uiHandler.removeCallbacks(endPairingAttention)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        } else {
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
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
        val summary = if (config.isG7) {
            getString(R.string.status_summary_g7, config.g7SensorName.ifBlank { "(имя ещё не известно)" }, config.g7PairingCode.ifBlank { getString(R.string.status_empty_value) })
        } else {
            getString(R.string.status_summary, transmitter, sensorCode)
        }
        val lines = listOfNotNull(
            prefix,
            summary,
            getString(
                R.string.status_scan_debug,
                scanDebug.seenName.ifBlank { getString(R.string.status_empty_value) },
                scanDebug.seenMac.ifBlank { getString(R.string.status_empty_value) },
                scanDebug.seenRssi.ifBlank { getString(R.string.status_empty_value) },
                scanDebug.lastEvent.ifBlank { getString(R.string.status_empty_value) },
            ),
            buildGlucoseLine(glucose),
            SensorSessionStore.load(this).label(System.currentTimeMillis()).text,
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
        private const val EDIT_HINT = "Изменить: 3 быстрых нажатия"
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
