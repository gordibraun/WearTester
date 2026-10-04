package com.example.weartester

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.media.AudioManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.NoSuchPaddingException
import javax.crypto.spec.SecretKeySpec

class BondProbeService : Service() {

    companion object {
        const val ACTION_READING_WINDOW_WAKE = "com.example.weartester.action.READING_WINDOW_WAKE"

        private const val TAG = "BondProbe"
        private const val NOTIF_CH_ID = "bond_probe_channel"
        private const val ALERT_CH_ID = "bond_probe_alerts"
        private const val NOTIF_ID = 42
        private const val ALERT_NOTIF_ID = 43
        private const val READING_WINDOW_ALARM_REQUEST_CODE = 1042
        private const val READING_WINDOW_ALARM_SHOW_REQUEST_CODE = 1043
        private const val NAME_PREFIX = "Dexcom"
        private const val SCAN_MS = 480_000L
        private const val BLE_RECOVERY_SETTLE_MS = 1_500L
        private const val SCAN_FAILED_RECOVERY_RETRY_DELAY_MS = 2_000L
        private const val SCANNER_STOP_START_SETTLE_MS = 250L
        private const val CONNECT_AFTER_SCAN_STOP_DELAY_MS = 150L
        /** What the rest of the transmitter's advertising burst allows a second, background connect. */
        private const val IN_WINDOW_RETRY_TIMEOUT_MS = 12_000L
        private const val MATCH_CONNECT_GRACE_MS = 10_000L
        private const val MIN_CONNECTABLE_DEXCOM_RSSI = -98
        private const val POST_GATT_FAILURE_MIN_CONNECT_RSSI = -94
        private const val POST_GATT_FAILURE_WEAK_FALLBACK_RSSI = -95
        private const val CONNECT_ATTEMPT_TIMEOUT_MS = GattTimeoutPolicy.ADVERTISED_CONNECT_TIMEOUT_MS
        private const val AUTOCONNECT_ATTEMPT_TIMEOUT_MS = 6 * 60_000L
        private const val RECONNECT_DELAY_MS = 15_000L
        private const val COMPLICATION_REFRESH_MS = 60_000L
        private const val PHONE_RELAY_PULL_MS = 30_000L
        private const val PHONE_RELAY_UDP_PORT = 51725
        private const val PHONE_RELAY_UDP_BUFFER_BYTES = 2048
        private const val SCAN_DEBUG_THROTTLE_MS = 30_000L
        private const val DEXCOM_READING_PERIOD_MS = 5 * 60_000L

        /**
         * A connect failing early this close to (or past) the next reading is in the reading window:
         * the transmitter is up and advertising, and another display device may have got it first.
         */
        private const val QUICK_RETRY_WINDOW_LEAD_MS = 20_000L
        private const val POST_GATT_FAILURE_RSSI_GUARD_MS = DEXCOM_READING_PERIOD_MS + 30_000L
        private const val WEAK_MATCH_FALLBACK_AFTER_MS = 2_000L
        private const val WEAK_MATCH_CANDIDATE_RESET_MS = 15_000L
        private const val BROAD_SCAN_BEFORE_NEXT_READING_MS = 2 * 60_000L + 30_000L
        private const val MISSED_READING_RECOVERY_MS = DEXCOM_READING_PERIOD_MS + 5_000L
        private const val HARD_MISSED_READING_MS = DEXCOM_READING_PERIOD_MS + 30_000L
        private const val NO_ADV_DIRECT_RECOVERY_MS = DEXCOM_READING_PERIOD_MS + 45_000L
        private const val STALE_SCAN_DIRECT_RECOVERY_MS = DEXCOM_READING_PERIOD_MS + 45_000L
        private const val BROAD_RECOVERY_SCAN_MS = 6 * 60_000L
        private const val DIRECT_RECOVERY_RETRY_MS = 10 * 60_000L
        private const val STALE_DIRECT_RECOVERY_RETRY_MS = 90_000L
        private const val STALE_SCAN_DIRECT_RECOVERY_RETRY_MS = 35_000L
        private const val STALE_DIRECT_CONNECT_TIMEOUT_MS = 8_000L
        private const val STALE_AUTOCONNECT_TIMEOUT_MS = 45_000L
        private const val STALE_AUTOCONNECT_AFTER_GATT_FAILURES = 2
        private const val SILENT_BROAD_SCAN_DIRECT_RESTARTS = 2
        private const val SILENT_BROAD_SCAN_DIRECT_TIMEOUT_MS = 18_000L
        private const val WATCHDOG_CONNECTED_WITHOUT_GLUCOSE_MS = 45_000L
        private const val WATCHDOG_ACTIVE_GATT_HARD_MS = 95_000L
        private const val KNOWN_MAC_AUTOCONNECT_AFTER_NO_ADV_MS = 2 * 60_000L
        private const val STALE_STARTUP_REBOND_MS = 30 * 60_000L
        private const val REBOND_AFTER_EARLY_GATT_FAILURES = 2
        private const val AUTH_STATUS_READ_DELAY_MS = 80L
        private const val ALT_SLOT_DISCOVERY_PAUSE_MS = 1_000L
        private const val AUTH_REQUEST_END_BYTE_ALT = 0x01.toByte()
        private const val PREFER_SCAN_AFTER_SUCCESS_MS = 30 * 60_000L
        private const val PREFER_SCAN_AFTER_DISCONNECT_MS = 10 * 60_000L
        private const val CONNECTED_SESSION_WITHOUT_GLUCOSE_TIMEOUT_MS = 120_000L
        private const val CONNECTED_SENSOR_REFRESH_MS = DEXCOM_READING_PERIOD_MS + 2_000L
        private const val CONNECTED_SENSOR_REFRESH_RETRY_MS = 12_000L
        private const val DEXCOM_TIMESTAMP_RESET_ACCEPT_SECONDS = 60 * 60
        private const val POST_GLUCOSE_DUPLICATE_SUPPRESSION_MS = 2 * 60_000L
        private const val BROAD_SCAN_SILENT_RESTART_MS = 25_000L
        private const val COLLECTOR_WAKE_LOCK_TIMEOUT_MS = 6 * 60_000L
        private const val COLLECTOR_WAKE_LOCK_REFRESH_MS = 60_000L
        private const val DEBUG_WIFI_KEEPALIVE_ENABLED = true
        private const val READING_WINDOW_WAKE_START_MS = 3 * 60_000L + 30_000L
        private const val READING_WINDOW_WAKE_AFTER_BOUNDARY_MS = 2 * 60_000L
        private const val READING_WINDOW_WAKE_LOCK_TIMEOUT_MS = 2 * 60_000L + 30_000L
        private const val READING_WINDOW_WAKE_LOCK_REFRESH_MS = 30_000L
        private const val AUTH_OPCODE_CHALLENGE = 0x03
        private const val AUTH_OPCODE_STATUS = 0x05
        private const val AUTH_OPCODE_TIME_RX = 0x25
        private const val AUTH_OPCODE_SESSION_START_RX = 0x27
        private const val SENSOR_OPCODE = 0x2F
        private const val GLUCOSE_OPCODE = 0x31
        private const val E_GLUCOSE_OPCODE = 0x4F
        private const val E_GLUCOSE2_OPCODE = 0x4E
    }

    private val btAdapter: BluetoothAdapter? by lazy {
        getSystemService(BluetoothManager::class.java)?.adapter
    }
    private var scanner: BluetoothLeScanner? = null
    @Volatile private var scanning = false
    private val gattLock = Any()
    private val gattRetryPolicy = GattRetryPolicy()
    private var gattRetryWaitLogged = false
    @Volatile private var gatt: BluetoothGatt? = null
    private val collectorStartedAt = System.currentTimeMillis()
    @Volatile private var wantConnected = true
    @Volatile private var config: DexcomConfig = DexcomConfig("", "", "")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val protocolWorker: ExecutorService = Executors.newSingleThreadExecutor()
    private val watchdogWorker: ExecutorService = Executors.newSingleThreadExecutor()
    private val phoneRelayUdpWorker: ExecutorService = Executors.newSingleThreadExecutor()
    private val seenAdvertisers = linkedSetOf<String>()
    private var authCharacteristic: BluetoothGattCharacteristic? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var authRequestToken: ByteArray? = null
    @Volatile private var bondFlowStarted = false
    @Volatile private var sensorRequestSent = false
    @Volatile private var reconnectDelayMs = RECONNECT_DELAY_MS
    @Volatile private var servicesDiscovered = false
    @Volatile private var currentGattAutoConnect = false
    @Volatile private var currentScanBroad = false
    @Volatile private var directMacFailures = 0
    @Volatile private var earlyGattFailureCount = 0
    @Volatile private var preferScanUntilMillis = 0L
    @Volatile private var lastSensorContactAtMillis = 0L
    @Volatile private var waitingBondConfirmation = 0
    @Volatile private var keepAliveInFlight = false
    @Volatile private var localBondAuthRetryCount = 0
    @Volatile private var freshBondPrepInFlight = false
    @Volatile private var timeRequestSent = false
    @Volatile private var timeRequestPendingAfterControlNotify = false
    @Volatile private var sessionStartSent = false
    @Volatile private var lastSensorRequestAtMillis = 0L
    @Volatile private var gattConnectStartedAtMillis = 0L
    @Volatile private var gattAttemptCreatedAtMillis = 0L
    @Volatile private var gattAttemptCreatedAtElapsed = 0L
    @Volatile private var gattConnectedAtElapsed = 0L
    @Volatile private var gattConnectedAtMillis = 0L
    @Volatile private var currentGattConnectTimeoutMs = CONNECT_ATTEMPT_TIMEOUT_MS
    @Volatile private var lastComplicationRefreshAtMillis = 0L
    @Volatile private var lastPhoneRelayPullAtMillis = 0L
    @Volatile private var lastNonMatchDebugAtMillis = 0L
    @Volatile private var lastDirectRecoveryAttemptAtMillis = 0L
    @Volatile private var lastDexcomAdvertisementAtMillis = 0L
    @Volatile private var lastScanStartedAtMillis = 0L
    @Volatile private var lastScanStartedAtElapsed = 0L
    @Volatile private var lastAnyScanCallbackAtMillis = 0L
    @Volatile private var consecutiveSilentBroadScanRestarts = 0
    @Volatile private var forceBroadScanUntilMillis = 0L
    @Volatile private var lastBroadRecoveryStartedAtMillis = 0L
    @Volatile private var lastCollectorWakeLockRefreshAtMillis = 0L
    @Volatile private var lastReadingWindowWakeLockAtMillis = 0L
    @Volatile private var pendingBleRecoveryAtMillis = 0L
    @Volatile private var pendingBleRecoveryReason = ""
    @Volatile private var pendingMatchConnectUntilMillis = 0L
    @Volatile private var readingWindowRefreshRequestedAtMillis = 0L
    /** Whether the one immediate retry a hung connect is allowed has been spent in this window. */
    @Volatile private var inWindowRetryUsed = false
    @Volatile private var weakGattFailureGuardUntilMillis = 0L
    @Volatile private var currentGattMatchRssi = Int.MIN_VALUE
    @Volatile private var weakMatchCandidateFirstAtMillis = 0L
    @Volatile private var weakMatchCandidateBestRssi = Int.MIN_VALUE
    private var pairingWakeLock: PowerManager.WakeLock? = null
    private var collectorWakeLock: PowerManager.WakeLock? = null
    private var readingWindowWakeLock: PowerManager.WakeLock? = null
    private var debugWifiLock: WifiManager.WifiLock? = null
    private var phoneRelayUdpSocket: DatagramSocket? = null
    private var bondReceiverRegistered = false
    private var pairingReceiverRegistered = false
    private var adapterReceiverRegistered = false
    private var phoneRelayListenersRegistered = false

    private val phoneRelayMessageListener = MessageClient.OnMessageReceivedListener { event ->
        handlePhoneRelayMessage(event)
    }
    private val phoneRelayDataListener = DataClient.OnDataChangedListener { events ->
        events.use { dataEvents ->
            for (event in dataEvents) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val item = event.dataItem
                if (item.uri.path != PhoneGlucoseRelayService.PATH_GLUCOSE) continue
                val map = DataMapItem.fromDataItem(item).dataMap
                PhoneGlucoseRelayService.acceptGlucose(
                    context = this,
                    mgdl = map.getInt(PhoneGlucoseRelayService.KEY_MGDL, -1),
                    receivedAtMillis = map.getLong(PhoneGlucoseRelayService.KEY_TIMESTAMP, 0L),
                    source = "${map.getString(PhoneGlucoseRelayService.KEY_SOURCE, "phone-data")}; active-data-listener",
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        ConnectionJournal.record(this, "collector_created")
        config = DexcomConfigStore.load(this)
        lastSensorContactAtMillis = maxOf(DexcomConfigStore.loadDirectGlucose(this).receivedAtMillis, SensorSessionStore.load(this).lastContactAt)
        DexcomConfigStore.saveScanDebug(this, "", "", null, "service created")
        ensureNotifChannel()
        ensureCollectorWakeLock("service created")
        ensureDebugWifiLock("service created")
        registerBondReceiver()
        registerPairingReceiver()
        registerAdapterReceiver()
        if (PhoneGlucoseRelayService.ENABLED) {
            registerPhoneRelayListeners()
        }
        maybePrepareStaleStartupRecovery()
        startForegroundWithNotification(buildStartupText())
        OnePlusPowerCompatibility.collectorStarted(this)
        requestComplicationRefresh("service created", force = true)
        if (PhoneGlucoseRelayService.ENABLED) {
            maybePullPhoneRelay("service created", force = true)
        }
        scheduleNextReadingWindowAlarm("service created")
        worker.execute { connectLoop() }
        watchdogWorker.execute { missedReadingWatchdogLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        config = DexcomConfigStore.load(this)
        lastSensorContactAtMillis = maxOf(lastSensorContactAtMillis, DexcomConfigStore.loadDirectGlucose(this).receivedAtMillis)
        ensureCollectorWakeLock("service start")
        ensureDebugWifiLock("service start")
        if (intent?.action == ACTION_READING_WINDOW_WAKE) {
            handleReadingWindowAlarm()
        }
        updateNotif(buildStartupText())
        requestComplicationRefresh("service start", force = true)
        if (PhoneGlucoseRelayService.ENABLED) {
            maybePullPhoneRelay("service start", force = true)
        }
        scheduleNextReadingWindowAlarm("service start")
        return START_STICKY
    }

    override fun onDestroy() {
        wantConnected = false
        stopScan()
        closeGatt("service destroy")
        unregisterBondReceiver()
        unregisterPairingReceiver()
        unregisterAdapterReceiver()
        unregisterPhoneRelayListeners()
        worker.shutdownNow()
        protocolWorker.shutdownNow()
        watchdogWorker.shutdownNow()
        stopPhoneRelayUdpListener()
        phoneRelayUdpWorker.shutdownNow()
        releasePairingWakeLock()
        releaseCollectorWakeLock()
        releaseReadingWindowWakeLock("service destroy")
        releaseDebugWifiLock("service destroy")
        OnePlusPowerCompatibility.collectorStopped(this)
        cancelReadingWindowAlarm()
        super.onDestroy()
        Log.i(TAG, "onDestroy")
        ConnectionJournal.record(this, "collector_destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerPhoneRelayListeners() {
        if (!PhoneGlucoseRelayService.ENABLED) return
        if (phoneRelayListenersRegistered) return
        Wearable.getMessageClient(this).addListener(phoneRelayMessageListener)
        Wearable.getDataClient(this).addListener(
            phoneRelayDataListener,
            Uri.Builder()
                .scheme("wear")
                .path(PhoneGlucoseRelayService.PATH_GLUCOSE)
                .build(),
            DataClient.FILTER_LITERAL,
        )
        phoneRelayListenersRegistered = true
        Log.i(TAG, "Phone relay active listeners registered")
    }

    private fun unregisterPhoneRelayListeners() {
        if (!phoneRelayListenersRegistered) return
        Wearable.getMessageClient(this).removeListener(phoneRelayMessageListener)
        Wearable.getDataClient(this).removeListener(phoneRelayDataListener)
        phoneRelayListenersRegistered = false
        Log.i(TAG, "Phone relay active listeners unregistered")
    }

    private fun handlePhoneRelayMessage(event: MessageEvent) {
        if (!PhoneGlucoseRelayService.ENABLED) return
        if (event.path != PhoneGlucoseRelayService.PATH_GLUCOSE) return
        runCatching {
            val json = JSONObject(String(event.data, Charsets.UTF_8))
            PhoneGlucoseRelayService.acceptGlucose(
                context = this,
                mgdl = json.optInt(PhoneGlucoseRelayService.KEY_MGDL, -1),
                receivedAtMillis = json.optLong(PhoneGlucoseRelayService.KEY_TIMESTAMP, 0L),
                source = "${json.optString(PhoneGlucoseRelayService.KEY_SOURCE, "phone-message")}; active-message-listener",
            )
        }.onFailure {
            Log.w(TAG, "Failed to parse active phone relay message", it)
        }
    }

    private fun startPhoneRelayUdpListener() {
        if (!PhoneGlucoseRelayService.ENABLED) return
        phoneRelayUdpWorker.execute {
            runCatching {
                DatagramSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(PHONE_RELAY_UDP_PORT))
                    socket.soTimeout = 3_000
                    phoneRelayUdpSocket = socket
                    Log.i(TAG, "Phone relay UDP listener started on $PHONE_RELAY_UDP_PORT")
                    val buffer = ByteArray(PHONE_RELAY_UDP_BUFFER_BYTES)
                    while (wantConnected && !Thread.currentThread().isInterrupted) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (timeout: java.net.SocketTimeoutException) {
                            continue
                        }
                        handlePhoneRelayUdpPacket(packet)
                    }
                }
            }.onFailure {
                if (wantConnected) {
                    Log.w(TAG, "Phone relay UDP listener stopped with error", it)
                }
            }
        }
    }

    private fun stopPhoneRelayUdpListener() {
        phoneRelayUdpSocket?.close()
        phoneRelayUdpSocket = null
    }

    private fun handlePhoneRelayUdpPacket(packet: DatagramPacket) {
        runCatching {
            val json = JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
            PhoneGlucoseRelayService.acceptGlucose(
                context = this,
                mgdl = json.optInt(PhoneGlucoseRelayService.KEY_MGDL, -1),
                receivedAtMillis = json.optLong(PhoneGlucoseRelayService.KEY_TIMESTAMP, 0L),
                source = "${json.optString(PhoneGlucoseRelayService.KEY_SOURCE, "phone-udp")}; udp ${packet.address.hostAddress}",
            )
        }.onFailure {
            Log.w(TAG, "Failed to parse phone relay UDP packet", it)
        }
    }

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bondStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(bondStateReceiver, filter)
        }
        Log.i(TAG, "Bond receiver registered")
        bondReceiverRegistered = true
    }

    private fun unregisterBondReceiver() {
        if (!bondReceiverRegistered) return
        runCatching { unregisterReceiver(bondStateReceiver) }
        bondReceiverRegistered = false
    }

    private fun registerPairingReceiver() {
        if (pairingReceiverRegistered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pairingRequestReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(pairingRequestReceiver, filter)
        }
        Log.i(TAG, "Pairing receiver registered")
        pairingReceiverRegistered = true
    }

    private fun unregisterPairingReceiver() {
        if (!pairingReceiverRegistered) return
        runCatching { unregisterReceiver(pairingRequestReceiver) }
        pairingReceiverRegistered = false
    }

    private fun registerAdapterReceiver() {
        if (adapterReceiverRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(adapterStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(adapterStateReceiver, filter)
        }
        Log.i(TAG, "Bluetooth adapter receiver registered")
        adapterReceiverRegistered = true
    }

    private fun unregisterAdapterReceiver() {
        if (!adapterReceiverRegistered) return
        runCatching { unregisterReceiver(adapterStateReceiver) }
        adapterReceiverRegistered = false
    }

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            val previous = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_OFF) {
                BluetoothIncidentRecorder.capture(this@BondProbeService, "adapter_off", "previous_state" to previous)
            }
            Log.w(TAG, "Bluetooth adapter state changed prev=$previous state=$state")
            DexcomConfigStore.saveScanDebug(
                this@BondProbeService,
                "",
                config.knownMac,
                null,
                "bluetooth adapter state changed prev=$previous state=$state",
            )
            when (state) {
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF -> {
                    requestBleSessionRecovery("bluetooth adapter state=$state")
                }

                BluetoothAdapter.STATE_ON -> {
                    ensureCollectorWakeLock("bluetooth adapter on")
                    requestBleSessionRecovery("bluetooth adapter recovered")
                }
            }
        }
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
            Log.i(
                TAG,
                "Bond update name=${device.name} addr=${device.address} state=$bondState prev=$previous current=${device.bondState}",
            )
            if (bondState == BluetoothDevice.BOND_NONE || bondState == BluetoothDevice.BOND_BONDED) {
                freshBondPrepInFlight = false
            }
            val activeGatt = gatt ?: return
            if (!device.address.equals(activeGatt.device.address, ignoreCase = true)) return
            if (device.bondState == BluetoothDevice.BOND_BONDED && waitingBondConfirmation == 1) {
                waitingBondConfirmation = 2
                Log.i(TAG, "Bond confirmation received from system")
                scheduleAuthStatusRead(activeGatt, "bond-state-receiver")
            }
        }
    }

    private val pairingRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
            Log.i(TAG, "Pairing request addr=${device.address} name=${device.name} variant=$variant")
            DexcomConfigStore.saveScanDebug(
                this@BondProbeService,
                device.name ?: "",
                device.address ?: "",
                null,
                "pairing request: confirm on watch",
            )
            alertPairingNeeded(device)
            wakePairingScreen(device)
            val activeGatt = gatt ?: return
            if (!device.address.equals(activeGatt.device.address, ignoreCase = true)) return
            runCatching {
                device.setPairingConfirmation(true)
                Log.i(TAG, "setPairingConfirmation(true) sent")
                abortBroadcast()
            }.onFailure {
                Log.e(TAG, "setPairingConfirmation failed: ${it.message}")
            }
        }
    }

    private fun ensureNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                NOTIF_CH_ID,
                "Bond probe",
                NotificationManager.IMPORTANCE_LOW,
            )
            nm?.createNotificationChannel(ch)
            val alertChannel = NotificationChannel(
                ALERT_CH_ID,
                "Bond probe alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                enableVibration(true)
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                setSound(
                    soundUri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
            nm?.createNotificationChannel(alertChannel)
        }
    }

    private fun startForegroundWithNotification(text: String) {
        val notif = NotificationCompat.Builder(this, NOTIF_CH_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Bond/GATT probe")
            .setContentText(text)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun updateNotif(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notif = NotificationCompat.Builder(this, NOTIF_CH_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Bond/GATT probe")
            .setContentText(text)
            .setOngoing(true)
            .build()
        nm?.notify(NOTIF_ID, notif)
    }

    private fun alertPairingNeeded(device: BluetoothDevice) {
        updateNotif("Подтвердите Bluetooth pairing для ${device.name ?: "Dexcom"}")
        vibrateAlert()
        playPairingTone()
        DexcomConfigStore.saveScanDebug(
            this,
            device.name ?: "",
            device.address ?: "",
            null,
            "pairing request: confirm on watch now",
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            1001,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_FORCE_PAIRING_ATTENTION, true)
                putExtra("pairing_device_name", device.name ?: "Dexcom")
                putExtra("pairing_device_mac", device.address ?: "")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nm = getSystemService(NotificationManager::class.java)
        val notif = NotificationCompat.Builder(this, ALERT_CH_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Нужно подтвердить pairing")
            .setContentText("Dexcom ${device.name ?: device.address} просит Bluetooth pairing")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        nm?.notify(ALERT_NOTIF_ID, notif)
    }

    @Suppress("DEPRECATION")
    private fun wakePairingScreen(device: BluetoothDevice) {
        acquirePairingWakeLock()
        Log.i(TAG, "Pairing screen wake requested without launching app over system dialog")
    }

    @Suppress("DEPRECATION")
    private fun acquirePairingWakeLock() {
        releasePairingWakeLock()
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        pairingWakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "$packageName:pairing-screen-wake",
        ).apply {
            setReferenceCounted(false)
            acquire(30_000L)
        }
        Log.i(TAG, "pairing wake lock acquired")
    }

    private fun releasePairingWakeLock() {
        runCatching {
            pairingWakeLock?.let { if (it.isHeld) it.release() }
        }
        pairingWakeLock = null
    }

    private fun ensureCollectorWakeLock(reason: String) {
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        val now = System.currentTimeMillis()
        runCatching {
            val wakeLock = collectorWakeLock ?: powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "$packageName:collector",
                ).apply {
                    setReferenceCounted(false)
                    collectorWakeLock = this
                }
            val wasHeld = wakeLock.isHeld
            val refreshDue = now - lastCollectorWakeLockRefreshAtMillis >= COLLECTOR_WAKE_LOCK_REFRESH_MS
            if (!wasHeld || refreshDue) {
                if (wasHeld && refreshDue) {
                    // Some Wear vendor power managers can drop the underlying
                    // kernel wakelock while the Java object still reports held.
                    // A forced release+acquire makes the system state converge.
                    runCatching { wakeLock.release() }
                }
                wakeLock.acquire(COLLECTOR_WAKE_LOCK_TIMEOUT_MS)
                lastCollectorWakeLockRefreshAtMillis = now
                if (wasHeld) {
                    Log.d(TAG, "collector wake lock force-refreshed ($reason)")
                } else {
                    Log.i(TAG, "collector wake lock acquired ($reason)")
                }
            }
        }.onFailure {
            Log.e(TAG, "collector wake lock failed: ${it.message}")
        }
    }

    private fun releaseCollectorWakeLock() {
        runCatching {
            collectorWakeLock?.let { if (it.isHeld) it.release() }
        }
        lastCollectorWakeLockRefreshAtMillis = 0L
        collectorWakeLock = null
    }

    @Synchronized
    @Suppress("DEPRECATION")
    private fun ensureDebugWifiLock(reason: String) {
        try {
            if (!wantConnected || !DEBUG_WIFI_KEEPALIVE_ENABLED ||
                Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) != 1
            ) {
                releaseDebugWifiLock("wireless debugging disabled")
                return
            }
            val wifiManager = applicationContext.getSystemService(WifiManager::class.java) ?: return
            // LOW_LATENCY is inactive with the screen off. On Android 14 the device also
            // needs wifi/high_perf_lock_deprecated=false, set explicitly through ADB.
            val wifiLock = debugWifiLock ?: wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "WearTester:debug-wifi",
            ).apply {
                setReferenceCounted(false)
                debugWifiLock = this
            }
            if (!wifiLock.isHeld) {
                wifiLock.acquire()
                ConnectionJournal.record(this, "debug_wifi_lock_requested", "mode" to "high_perf", "reason" to reason)
                Log.i(TAG, "debug Wi-Fi high-perf lock requested ($reason); effective mode requires system verification")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "debug Wi-Fi lock failed ($reason): ${t.message}")
        }
    }

    @Synchronized
    private fun releaseDebugWifiLock(reason: String) {
        try {
            debugWifiLock?.let {
                if (it.isHeld) {
                    it.release()
                    ConnectionJournal.record(this, "debug_wifi_lock_released", "reason" to reason)
                    Log.i(TAG, "debug Wi-Fi lock released ($reason)")
                }
            }
        } catch (_: Throwable) {
        }
        debugWifiLock = null
    }

    @Suppress("DEPRECATION")
    private fun maybeEnsureReadingWindowWakeLock(reason: String) {
        val last = lastSensorContactAtMillis
        if (last <= 0L) return
        val now = System.currentTimeMillis()
        val age = now - last
        if (!isDexcomReadingWindow(age)) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        runCatching {
            val wakeLock = readingWindowWakeLock ?: powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:reading-window",
            ).apply {
                setReferenceCounted(false)
                readingWindowWakeLock = this
            }
            val wasHeld = wakeLock.isHeld
            val refreshDue = now - lastReadingWindowWakeLockAtMillis >= READING_WINDOW_WAKE_LOCK_REFRESH_MS
            if (!wasHeld || refreshDue) {
                if (wasHeld && refreshDue) {
                    runCatching { wakeLock.release() }
                }
                wakeLock.acquire(READING_WINDOW_WAKE_LOCK_TIMEOUT_MS)
                lastReadingWindowWakeLockAtMillis = now
                val ageSeconds = age / 1_000L
                if (wasHeld) {
                    Log.d(TAG, "reading-window wake lock refreshed ($reason, age=${ageSeconds}s)")
                } else {
                    Log.i(TAG, "reading-window wake lock acquired ($reason, age=${ageSeconds}s)")
                }
            }
        }.onFailure {
            Log.e(TAG, "reading-window wake lock failed: ${it.message}")
        }
    }

    private fun releaseReadingWindowWakeLock(reason: String) {
        runCatching {
            readingWindowWakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i(TAG, "reading-window wake lock released ($reason)")
                }
            }
        }
        lastReadingWindowWakeLockAtMillis = 0L
        readingWindowWakeLock = null
    }

    private fun handleReadingWindowAlarm() {
        Log.i(TAG, "reading-window alarm fired")
        DexcomConfigStore.saveScanDebug(this, "", config.knownMac, null, "reading-window alarm fired")
        maybeEnsureReadingWindowWakeLock("alarm")
        readingWindowRefreshRequestedAtMillis = System.currentTimeMillis()
    }

    private fun scheduleNextReadingWindowAlarm(reason: String) {
        val last = lastSensorContactAtMillis
        if (last <= 0L) return
        val now = System.currentTimeMillis()
        var triggerAt = last + READING_WINDOW_WAKE_START_MS
        while (triggerAt <= now + 5_000L) {
            triggerAt += DEXCOM_READING_PERIOD_MS
        }
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = readingWindowAlarmPendingIntent(PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val showIntent = PendingIntent.getActivity(
            this,
            READING_WINDOW_ALARM_SHOW_REQUEST_CODE,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_AUTO_START_PROBE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt, showIntent),
                pendingIntent,
            )
            Log.i(TAG, "Scheduled reading-window alarm in ${triggerAt - now}ms ($reason)")
        }.onFailure {
            Log.e(TAG, "schedule reading-window alarm failed: ${it.message}")
        }
    }

    private fun cancelReadingWindowAlarm() {
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            readingWindowAlarmPendingIntent(PendingIntent.FLAG_NO_CREATE)?.let { alarmManager.cancel(it) }
        }
    }

    private fun readingWindowAlarmPendingIntent(extraFlags: Int): PendingIntent? {
        val intent = Intent(this, ReadingWindowAlarmReceiver::class.java)
            .setAction(ACTION_READING_WINDOW_WAKE)
        return PendingIntent.getBroadcast(
            this,
            READING_WINDOW_ALARM_REQUEST_CODE,
            intent,
            extraFlags or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun isDexcomReadingWindow(ageMillis: Long): Boolean {
        if (ageMillis < READING_WINDOW_WAKE_START_MS) return false
        val cycleAge = ageMillis % DEXCOM_READING_PERIOD_MS
        return cycleAge >= READING_WINDOW_WAKE_START_MS ||
            cycleAge <= READING_WINDOW_WAKE_AFTER_BOUNDARY_MS
    }

    private fun isDexcomProtectedScanWindow(ageMillis: Long): Boolean =
        RecoveryPolicy.protectedScanWindow(ageMillis)

    private fun shouldHonorConnectCooldown(now: Long = System.currentTimeMillis()): Boolean {
        val last = lastSensorContactAtMillis
        if (last <= 0L) return false
        val age = now - last
        return age in 0 until POST_GLUCOSE_DUPLICATE_SUPPRESSION_MS
    }

    private fun minConnectableDexcomRssi(now: Long): Int {
        return if (now < weakGattFailureGuardUntilMillis) {
            POST_GATT_FAILURE_MIN_CONNECT_RSSI
        } else {
            MIN_CONNECTABLE_DEXCOM_RSSI
        }
    }

    private fun resetWeakMatchCandidate() {
        weakMatchCandidateFirstAtMillis = 0L
        weakMatchCandidateBestRssi = Int.MIN_VALUE
    }

    private fun setPhoneCollectionCooldown(reason: String) {
        Log.i(TAG, "Watch-primary mode: no phone cooldown ($reason)")
    }

    private fun vibrateAlert() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 180, 300, 180, 500), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 300, 180, 300, 180, 500), -1)
            }
        }.onFailure {
            Log.e(TAG, "vibrateAlert failed: ${it.message}")
        }
    }

    private fun playPairingTone() {
        protocolWorker.execute {
            repeat(6) {
                runCatching {
                    val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                    tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 350)
                    sleep(450)
                    tone.release()
                }.onFailure {
                    Log.e(TAG, "playPairingTone failed: ${it.message}")
                }
            }
        }
    }

    private fun connectLoop() {
        wantConnected = true
        while (wantConnected) {
            try {
                ensureCollectorWakeLock("connect loop")
                config = DexcomConfigStore.load(this)
                val transmitterId = config.transmitterId
                val knownMac = config.knownMac

                if (btAdapter == null || !btAdapter!!.isEnabled) {
                    handlePendingBleSessionRecovery()
                    Log.w(TAG, "Bluetooth OFF — включи BT на часах")
                    sleep(reconnectDelayMs)
                    continue
                }

                handlePendingBleSessionRecovery()

                if (transmitterId.isBlank()) {
                    updateNotif("Укажите код трансмиттера в настройках")
                    Log.w(TAG, "No transmitter ID configured")
                    DexcomConfigStore.saveScanDebug(this, "", "", null, "missing transmitter id")
                    sleep(reconnectDelayMs)
                    continue
                }

                if (knownMac.isNotEmpty()) {
                    Log.i(TAG, "Known MAC $knownMac configured; using scan-first collector on watch")
                }

                Log.i(TAG, "Start BLE scan for $SCAN_MS ms (prefix=$NAME_PREFIX, txid=$transmitterId)")
                updateNotif("Сканирую Dexcom ${transmitterId.ifBlank { "" }}")
                DexcomConfigStore.saveScanDebug(this, "", "", null, "scan started for $transmitterId")
                if (!startScanInternal()) {
                    sleep(reconnectDelayMs)
                    continue
                }
                var scanDeadline = System.currentTimeMillis() + SCAN_MS
                while (wantConnected) {
                    val loopNow = System.currentTimeMillis()
                    if (loopNow >= scanDeadline) {
                        val lastGlucoseAt = lastSensorContactAtMillis
                        val age = if (lastGlucoseAt > 0L) loopNow - lastGlucoseAt else 0L
                        val preserveScan =
                            scanning && gatt == null && lastGlucoseAt > 0L &&
                                (age >= MISSED_READING_RECOVERY_MS || isDexcomProtectedScanWindow(age))
                        if (!preserveScan) break

                        Log.w(TAG, "Scan deadline reached while glucose stale/protected; extending continuous scan age=${age}ms")
                        DexcomConfigStore.saveScanDebug(
                            this,
                            "",
                            config.knownMac,
                            null,
                            "scan deadline extended stale/protected ${age}ms",
                        )
                        scanDeadline = loopNow + SCAN_MS
                    }
                    if (handlePendingBleSessionRecovery()) {
                        sleep(SCANNER_STOP_START_SETTLE_MS)
                        continue
                    }
                    handleReadingWindowRefreshRequest()
                    if (gatt != null) {
                        waitConnectedLoop()
                        if (gatt == null && scanning && wantConnected) {
                            continue
                        }
                        break
                    }
                    val now = System.currentTimeMillis()
                    maybeEnsureReadingWindowWakeLock("scan loop")
                    val shouldBroadScan = shouldUseBroadDexcomScan()
                    val needsBroadSwitch = shouldBroadScan && !currentScanBroad
                    // Both modes use the same controller filters. Changing the mode needs no BLE restart.
                    if (scanning && needsBroadSwitch) currentScanBroad = true
                    refreshScanBeforePlatformTimeout()
                    if (!scanning && gatt == null && btAdapter?.isEnabled == true) {
                        val pendingMatchConnectMs = pendingMatchConnectUntilMillis - now
                        if (pendingMatchConnectMs > 0L) {
                            Log.d(TAG, "Scanner paused while Dexcom match connect is pending (${pendingMatchConnectMs}ms left)")
                            sleep(500)
                            continue
                        }
                        Log.w(TAG, "Scanner is not running inside scan window; restarting")
                        DexcomConfigStore.saveScanDebug(this, "", config.knownMac, null, "scanner restart inside scan window")
                        sleep(SCAN_FAILED_RECOVERY_RETRY_DELAY_MS)
                        startScanInternal()
                    }
                    sleep(1_000)
                }
                if (scanning) {
                    stopScan()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "connectLoop recovered from error", t)
                DexcomConfigStore.saveScanDebug(this, "", config.knownMac, null, "connectLoop recovered: ${t.javaClass.simpleName}")
                sleep(2_000)
            }
        }
    }

    private fun waitConnectedLoop() {
        var count = 0
        while (wantConnected && gatt != null) {
            val attempt = gatt ?: break
            if (expireGattPhaseIfNeeded()) break
            val connectedAt = gattConnectedAtMillis
            val connectedGatt = gatt
            if (connectedAt > 0L && lastSensorContactAtMillis < connectedAt) {
                val connectedAge = System.currentTimeMillis() - connectedAt
                if (connectedAge >= CONNECTED_SESSION_WITHOUT_GLUCOSE_TIMEOUT_MS) {
                    Log.w(TAG, "Connected for ${connectedAge}ms without glucose; restarting watch-primary Dexcom session")
                    DexcomConfigStore.saveScanDebug(
                        this,
                        "",
                        config.knownMac,
                        null,
                        "restart stale gatt after ${connectedAge}ms without glucose",
                    )
                    setPhoneCollectionCooldown("connected timeout without glucose")
                    if (!closeGatt("connected timeout without glucose; restart watch collector", attempt)) continue
                    if (wantConnected && btAdapter?.isEnabled == true && !scanning) {
                        runCatching { startScanInternal() }
                    }
                    break
                }
            }
            if (connectedAt > 0L && connectedGatt != null) {
                maybeRequestConnectedGlucoseRefresh(connectedGatt)
            }
            sleep(1_000)
            if (++count % 30 == 0) Log.d(TAG, "still connected loop tick…")
        }
    }

    private fun maybeRequestConnectedGlucoseRefresh(connectedGatt: BluetoothGatt) {
        val now = System.currentTimeMillis()
        val lastGlucoseAt = lastSensorContactAtMillis
        if (lastGlucoseAt <= 0L) return
        val connectedAt = gattConnectedAtMillis
        if (!servicesDiscovered || connectedAt <= 0L || lastGlucoseAt <= connectedAt) return
        if (timeRequestSent || timeRequestPendingAfterControlNotify || keepAliveInFlight || sensorRequestSent) return
        val glucoseAge = now - lastGlucoseAt
        if (glucoseAge < CONNECTED_SENSOR_REFRESH_MS) return
        if (now - lastSensorRequestAtMillis < CONNECTED_SENSOR_REFRESH_RETRY_MS) return

        lastSensorRequestAtMillis = now
        sensorRequestSent = false
        Log.i(TAG, "Connected Dexcom refresh: requesting glucose age=${glucoseAge}ms")
        DexcomConfigStore.saveScanDebug(
            this,
            "Dexcom connected",
            connectedGatt.device?.address ?: config.knownMac,
            null,
            "connected glucose refresh age=${glucoseAge}ms",
        )
        protocolWorker.execute {
            if (gatt === connectedGatt) {
                writeSensorRequest(connectedGatt)
            }
        }
    }

    private fun missedReadingWatchdogLoop() {
        while (wantConnected) {
            sleep(30_000)
            if (!wantConnected) break
            ensureCollectorWakeLock("watchdog")
            ensureDebugWifiLock("watchdog")
            OnePlusPowerCompatibility.requestCheck(this)
            runCatching { ConnectionMonitor.tick(this) }
                .onFailure { Log.w(TAG, "Connection diagnostic unavailable", it) }
            requestComplicationRefresh("watchdog age tick")
            lastSensorContactAtMillis = maxOf(
                lastSensorContactAtMillis,
                DexcomConfigStore.loadDirectGlucose(this).receivedAtMillis,
            )
            val last = lastSensorContactAtMillis
            if (last <= 0L) continue
            val age = System.currentTimeMillis() - last
            if (PhoneGlucoseRelayService.ENABLED) {
                maybePullPhoneRelay("watchdog age ${age}ms", force = age >= MISSED_READING_RECOVERY_MS)
            }
            maybeEnsureReadingWindowWakeLock("watchdog")
            if (gatt != null) {
                if (age >= MISSED_READING_RECOVERY_MS) {
                    Log.w(TAG, "No glucose for ${age}ms, but GATT attempt is active; waiting for connection outcome")
                    DexcomConfigStore.saveScanDebug(this, "", config.knownMac, null, "gatt active while stale ${age}ms")
                    if (recoverStaleActiveGattFromWatchdog(age)) {
                        continue
                    }
                } else {
                    Log.d(TAG, "GATT active while glucose is fresh (${age}ms)")
                }
                continue
            }
            val now = System.currentTimeMillis()
            val noRecentDexcomAdvertisement =
                lastDexcomAdvertisementAtMillis <= 0L ||
                    now - lastDexcomAdvertisementAtMillis >= KNOWN_MAC_AUTOCONNECT_AFTER_NO_ADV_MS
            if (age >= HARD_MISSED_READING_MS && noRecentDexcomAdvertisement) {
                val inProtectedScanWindow = isDexcomProtectedScanWindow(age)
                val noAdvFor = if (lastDexcomAdvertisementAtMillis <= 0L) -1L else now - lastDexcomAdvertisementAtMillis
                val reason = "no dexcom advertisement ${noAdvFor}ms while stale ${age}ms"
                if (!isBroadRecoveryScanActive()) {
                    enableBroadRecoveryScan(reason)
                }
                DexcomConfigStore.saveScanDebug(this, "", config.knownMac, null, "broad recovery scan: $reason")
                if (gatt == null && btAdapter?.isEnabled == true) {
                    if (scanning && currentScanBroad) {
                        restartSilentBroadScanIfNeeded(now, age)
                        val useAutoConnectProbe =
                            consecutiveSilentBroadScanRestarts >= SILENT_BROAD_SCAN_DIRECT_RESTARTS * 2
                        if (
                            consecutiveSilentBroadScanRestarts >= SILENT_BROAD_SCAN_DIRECT_RESTARTS &&
                            age >= NO_ADV_DIRECT_RECOVERY_MS &&
                            maybeTryBondedDirectRecovery(
                                reason = "silent broad scan ${if (useAutoConnectProbe) "autoconnect" else "direct"} probe age=${age}ms restarts=$consecutiveSilentBroadScanRestarts noAdvFor=${noAdvFor}ms",
                                allowDuringBroadScan = true,
                                minRetryMs = STALE_SCAN_DIRECT_RECOVERY_RETRY_MS,
                                preferAutoConnect = useAutoConnectProbe,
                                connectTimeoutMs = if (useAutoConnectProbe) {
                                    STALE_AUTOCONNECT_TIMEOUT_MS
                                } else {
                                    SILENT_BROAD_SCAN_DIRECT_TIMEOUT_MS
                                },
                            )
                        ) {
                            continue
                        }
                        Log.w(TAG, "No advertisement while stale; keeping broad scan active instead of direct GATT")
                        continue
                    }
                    if (
                        age >= NO_ADV_DIRECT_RECOVERY_MS &&
                        maybeTryBondedDirectRecovery(
                            reason = "no advertisement direct recovery age=${age}ms noAdvFor=${noAdvFor}ms",
                            allowDuringBroadScan = true,
                            minRetryMs = STALE_SCAN_DIRECT_RECOVERY_RETRY_MS,
                            preferAutoConnect = false,
                        )
                    ) {
                        continue
                    }
                    if (inProtectedScanWindow && scanning && currentScanBroad) {
                        Log.w(TAG, "No advertisement while stale; preserving continuous broad scan inside Dexcom protected window")
                        continue
                    }
                    if (scanning && currentScanBroad) {
                        Log.w(TAG, "No advertisement while stale; keeping broad scan active instead of direct GATT")
                        continue
                    }
                    if (!scanning || !currentScanBroad) {
                        runCatching { stopScan() }
                        sleep(200)
                        runCatching { startScanInternal() }
                    }
                }
                Log.w(TAG, "No glucose for ${age}ms; keeping broad scan active instead of background autoConnect")
                continue
            }
            if (
                age >= STALE_SCAN_DIRECT_RECOVERY_MS &&
                maybeTryBondedDirectRecovery(
                    reason = "stale scan direct recovery age=${age}ms",
                    allowDuringBroadScan = true,
                    minRetryMs = STALE_SCAN_DIRECT_RECOVERY_RETRY_MS,
                )
            ) {
                continue
            }
            val alreadyWaitingForDexcom = (scanning || isBroadRecoveryScanActive()) && gatt == null
            if (age >= MISSED_READING_RECOVERY_MS && alreadyWaitingForDexcom) {
                if (System.currentTimeMillis() >= forceBroadScanUntilMillis) {
                    enableBroadRecoveryScan("active stale scan ${age}ms")
                }
                Log.w(TAG, "No glucose for ${age}ms, recovery scan already active; keeping scanner running")
                DexcomConfigStore.saveScanDebug(this, "", config.knownMac, null, "recovery scan active ${age}ms")
                continue
            }
            if (age >= HARD_MISSED_READING_MS) {
                Log.w(TAG, "No glucose for ${age}ms, performing hard recovery")
                performMissedReadingRecovery("hard stale glucose ${age}ms")
            } else if (age >= MISSED_READING_RECOVERY_MS) {
                Log.w(TAG, "No glucose for ${age}ms, performing scan-first recovery")
                performMissedReadingRecovery("stale glucose ${age}ms")
            }
        }
    }

    private fun requestComplicationRefresh(reason: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastComplicationRefreshAtMillis < COMPLICATION_REFRESH_MS) return
        lastComplicationRefreshAtMillis = now
        Log.d(TAG, "Request complication refresh: $reason")
        DexcomGlucoseComplicationService.requestImmediateUpdate(this)
    }

    private fun maybePullPhoneRelay(reason: String, force: Boolean = false) {
        if (!PhoneGlucoseRelayService.ENABLED) return
        val now = System.currentTimeMillis()
        if (!force && now - lastPhoneRelayPullAtMillis < PHONE_RELAY_PULL_MS) return
        lastPhoneRelayPullAtMillis = now
        Log.d(TAG, "Pull phone relay glucose: $reason")
        PhoneGlucoseRelayService.pullLatest(this, reason)
    }

    private fun recoverStaleActiveGattFromWatchdog(glucoseAgeMs: Long): Boolean {
        if (expireGattPhaseIfNeeded()) return true
        val attempt = gatt ?: return false
        val now = System.currentTimeMillis()
        val connectStartedAt = gattConnectStartedAtMillis
        val connectedAt = gattConnectedAtMillis
        val activeSince = when {
            connectStartedAt > 0L -> connectStartedAt
            connectedAt > 0L -> connectedAt
            else -> return false
        }
        val activeAge = now - activeSince
        val connectedNoGlucose = servicesDiscovered &&
            connectedAt > 0L &&
            lastSensorContactAtMillis < connectedAt &&
            activeAge >= WATCHDOG_CONNECTED_WITHOUT_GLUCOSE_MS
        val hardStuck = activeAge >= WATCHDOG_ACTIVE_GATT_HARD_MS
        if (!connectedNoGlucose && !hardStuck) return false

        val reason = "watchdog stale gatt active=${activeAge}ms glucoseAge=${glucoseAgeMs}ms autoConnect=$currentGattAutoConnect services=$servicesDiscovered"
        Log.w(TAG, "Closing stale active GATT from watchdog: $reason")
        DexcomConfigStore.saveScanDebug(this, "", config.knownMac, null, reason)
        enableBroadRecoveryScan(reason)
        if (!closeGatt(reason, attempt)) return false
        if (wantConnected && btAdapter?.isEnabled == true && gatt == null) {
            if (
                maybeTryBondedDirectRecovery(
                    reason = reason,
                    allowDuringBroadScan = true,
                    minRetryMs = 0L,
                    preferAutoConnect = false,
                )
            ) {
                return true
            }
            if (!scanning) {
                runCatching { startScanInternal() }
            }
        }
        return true
    }

    private fun expireGattPhaseIfNeeded(): Boolean = synchronized(gattLock) {
        val attempt = gatt ?: return false
        if (!wantConnected || gattAttemptCreatedAtElapsed <= 0L) return false
        val now = SystemClock.elapsedRealtime()
        val attemptAge = now - gattAttemptCreatedAtElapsed
        val connectedAge = gattConnectedAtElapsed.takeIf { it > 0L }?.let { now - it }
        val phase = GattTimeoutPolicy.expiredPhase(
            attemptAge, connectedAge, servicesDiscovered, currentGattConnectTimeoutMs,
        ) ?: return false

        // Recheck and close under the callback lock: a just-established link must
        // never be cancelled using the shorter CONNECTING deadline.
        ConnectionJournal.record(this, "gatt_phase_timeout", "phase" to phase.name,
            "attempt_ms" to attemptAge, "connected_ms" to connectedAge,
            "rssi" to currentGattMatchRssi.takeUnless { it == Int.MIN_VALUE })
        BluetoothIncidentRecorder.capture(this, "gatt_timeout", "phase" to phase.name,
            "attempt_ms" to attemptAge, "connected_ms" to connectedAge)
        val reason = "gatt ${phase.name} timeout ${attemptAge}ms"
        DexcomConfigStore.saveScanDebug(this, "", config.knownMac, null, reason)
        val device = attempt.device
        val matchRssi = currentGattMatchRssi
        if (!closeGatt(reason, attempt)) return false
        // A connect that hung before CONNECTED never reached the transmitter, which goes on
        // advertising for the rest of its ~15 s burst. One more try, at once, on the same device
        // - once per window, so a transmitter that really is gone costs one short attempt, not a
        // storm. The retry goes the other way through the stack: a direct connect issued right
        // after a hung one hung again both times it was seen (3 Oct); a background connect lets
        // the stack itself connect on the next advertisement it hears. It gets the rest of the
        // burst. Only after that does the usual rule hold: wait for a new advertisement.
        if (phase == GattTimeoutPolicy.Phase.CONNECTING && !inWindowRetryUsed && wantConnected && btAdapter?.isEnabled == true) {
            inWindowRetryUsed = true
            ConnectionJournal.record(this, "in_window_retry", "after_ms" to attemptAge, "rssi" to matchRssi.takeUnless { it == Int.MIN_VALUE })
            Log.i(TAG, "connect hung ${attemptAge}ms; retrying once within the window by background connect")
            // Not "expired" for the caller: a new attempt is under way and the wait loop keeps
            // watching it, so that its own deadline is seen on time.
            if (connectGatt(device, connectTimeoutMs = IN_WINDOW_RETRY_TIMEOUT_MS, matchRssi = matchRssi, autoConnectOverride = true, inWindowRetry = true)) return false
        }
        if (wantConnected && btAdapter?.isEnabled == true && !scanning) startScanInternal()
        true
    }

    private fun performMissedReadingRecovery(reason: String): Unit = synchronized(gattLock) {
        // An advertisement callback may have connected since the watchdog's earlier check.
        if (gatt != null || !wantConnected) return
        reconnectDelayMs = 2_000L
        directMacFailures = maxOf(directMacFailures, 1)
        preferScanUntilMillis = System.currentTimeMillis() + PREFER_SCAN_AFTER_SUCCESS_MS
        enableBroadRecoveryScan(reason)
        DexcomConfigStore.saveScanDebug(this, "", config.knownMac, null, reason)
        updateNotif("Recovery: жду новое окно Dexcom")
        runCatching { stopScan() }
        runCatching { closeGatt(reason) }
        if (wantConnected && btAdapter?.isEnabled == true && !scanning) {
            sleep(200)
            if (runCatching { startScanInternal() }.getOrDefault(false)) {
                return
            }
        }
        if (maybeTryBondedDirectRecovery(reason)) return
        if (wantConnected && btAdapter?.isEnabled == true && !scanning) {
            sleep(200)
            runCatching { startScanInternal() }
        }
    }

    private fun maybeTryBondedDirectRecovery(
        reason: String,
        allowDuringBroadScan: Boolean = false,
        minRetryMs: Long = DIRECT_RECOVERY_RETRY_MS,
        preferAutoConnect: Boolean = false,
        connectTimeoutMs: Long? = null,
    ): Boolean = synchronized(gattLock) {
        val now = System.currentTimeMillis()
        if (gattRetryPolicy.remainingMs(SystemClock.elapsedRealtime()) > 0L) return false
        if (shouldHonorConnectCooldown(now)) return false
        val contactAge = now - lastSensorContactAtMillis.takeIf { it > 0L }.let { it ?: collectorStartedAt }
        if (!RecoveryPolicy.allowBlindProbe(contactAge, now - lastDirectRecoveryAttemptAtMillis)) return false
        if (now - lastDirectRecoveryAttemptAtMillis < minRetryMs) return false
        if (!allowDuringBroadScan && scanning && now - lastBroadRecoveryStartedAtMillis < BROAD_RECOVERY_SCAN_MS) return false
        val knownMac = config.knownMac
        if (knownMac.isBlank() || btAdapter?.isEnabled != true || gatt != null) return false
        val fallbackDevice = runCatching {
            btAdapter?.bondedDevices?.firstOrNull { it.address.equals(knownMac, ignoreCase = true) }
        }.getOrNull() ?: return false
        if (fallbackDevice.bondState != BluetoothDevice.BOND_BONDED) return false

        lastDirectRecoveryAttemptAtMillis = now
        val useAutoConnect = preferAutoConnect
        val mode = if (useAutoConnect) "autoConnect" else "direct connect"
        Log.w(TAG, "Missed-reading recovery: trying bonded $mode ${fallbackDevice.address} ($reason)")
        DexcomConfigStore.saveScanDebug(this, "", fallbackDevice.address, null, "stale $mode: $reason")
        runCatching { stopScan() }
        val timeout = minOf(connectTimeoutMs ?: RecoveryPolicy.BLIND_PROBE_TIMEOUT, RecoveryPolicy.BLIND_PROBE_TIMEOUT)
        return connectGatt(
            device = fallbackDevice,
            connectTimeoutMs = timeout,
            autoConnectOverride = useAutoConnect,
        )
    }

    private fun enableBroadRecoveryScan(reason: String) {
        val now = System.currentTimeMillis()
        lastBroadRecoveryStartedAtMillis = now
        forceBroadScanUntilMillis = now + BROAD_RECOVERY_SCAN_MS
        Log.w(TAG, "Broad Dexcom recovery scan enabled for ${BROAD_RECOVERY_SCAN_MS}ms: $reason")
        DexcomConfigStore.saveScanDebug(this, "", config.knownMac, null, "broad recovery scan: $reason")
    }

    private fun maybePrepareStaleStartupRecovery() {
        val knownMac = config.knownMac
        val lastGlucoseAt = lastSensorContactAtMillis
        if (knownMac.isBlank() || lastGlucoseAt <= 0L) return
        val age = System.currentTimeMillis() - lastGlucoseAt
        if (age < STALE_STARTUP_REBOND_MS) return
        val bondedDexcom = runCatching {
            btAdapter?.bondedDevices?.firstOrNull { it.address.equals(knownMac, ignoreCase = true) }
        }.getOrNull() ?: return
        if (bondedDexcom.bondState != BluetoothDevice.BOND_BONDED) return

        directMacFailures = maxOf(directMacFailures, 1)
        preferScanUntilMillis = System.currentTimeMillis() + PREFER_SCAN_AFTER_DISCONNECT_MS
        Log.w(TAG, "Glucose stale for ${age}ms on startup; preserving local Dexcom bond and starting recovery scan")
        DexcomConfigStore.saveScanDebug(
            this,
            bondedDexcom.name ?: "Dexcom",
            bondedDexcom.address ?: knownMac,
            null,
            "stale startup recovery ${age}ms",
        )
        enableBroadRecoveryScan("stale startup recovery ${age}ms")
    }

    private fun isBroadRecoveryScanActive(): Boolean {
        return System.currentTimeMillis() < forceBroadScanUntilMillis
    }

    private fun restartSilentBroadScanIfNeeded(now: Long, glucoseAge: Long): Boolean = synchronized(gattLock) {
        if (!scanning || !currentScanBroad || gatt != null || btAdapter?.isEnabled != true) return false
        val lastActivity = maxOf(lastAnyScanCallbackAtMillis, lastScanStartedAtMillis)
        if (lastActivity <= 0L || !RecoveryPolicy.allowSilentScanRestart(glucoseAge, now - lastActivity)) return false

        Log.w(TAG, "Broad scan silent for ${now - lastActivity}ms while stale ${glucoseAge}ms; restarting BLE scan")
        consecutiveSilentBroadScanRestarts += 1
        DexcomConfigStore.saveScanDebug(
            this,
            "",
            config.knownMac,
            null,
            "broad scan silent restart age=${glucoseAge}ms silent=${now - lastActivity}ms",
        )
        runCatching { stopScan() }
        sleep(SCANNER_STOP_START_SETTLE_MS)
        return runCatching { startScanInternal() }.getOrDefault(false)
    }

    private fun shouldUseBroadDexcomScan(): Boolean {
        return isBroadRecoveryScanActive() || shouldUseBroadNearReadingWindow()
    }

    private fun shouldUseBroadNearReadingWindow(): Boolean {
        val last = lastSensorContactAtMillis
        return last > 0L && System.currentTimeMillis() - last >= BROAD_SCAN_BEFORE_NEXT_READING_MS
    }

    private fun ensureBond(device: BluetoothDevice): Boolean {
        return when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> true
            BluetoothDevice.BOND_NONE -> {
                Log.i(TAG, "createBond()")
                val ok = runCatching { device.createBond() }.getOrElse {
                    Log.e(TAG, "createBond error: ${it.message}")
                    false
                }
                if (!ok) return false
                repeat(30) { i ->
                    val state = device.bondState
                    Log.i(TAG, "Bond poll[$i]: state=$state (10=NONE,11=BONDING,12=BONDED)")
                    if (state == BluetoothDevice.BOND_BONDED) return true
                    sleep(1_000)
                }
                Log.w(TAG, "Bond timeout")
                false
            }

            else -> {
                repeat(30) {
                    val state = device.bondState
                    Log.i(TAG, "Bonding… state=$state")
                    if (state == BluetoothDevice.BOND_BONDED) return true
                    sleep(1_000)
                }
                Log.w(TAG, "Bonding timeout")
                false
            }
        }
    }

    private fun connectGatt(
        device: BluetoothDevice,
        connectTimeoutMs: Long = CONNECT_ATTEMPT_TIMEOUT_MS,
        matchRssi: Int = Int.MIN_VALUE,
        autoConnectOverride: Boolean? = null,
        inWindowRetry: Boolean = false,
    ): Boolean = synchronized(gattLock) {
        if (gatt != null || !wantConnected) return false
        if (gattRetryPolicy.remainingMs(SystemClock.elapsedRealtime()) > 0L) return false
        // A fresh attempt opens a fresh window: it may be retried once if it hangs.
        if (!inWindowRetry) inWindowRetryUsed = false
        ensureCollectorWakeLock("connect gatt")
        closeGatt("before new connect")
        bondFlowStarted = false
        sensorRequestSent = false
        servicesDiscovered = false
        gattConnectStartedAtMillis = 0L
        gattConnectedAtMillis = 0L
        gattConnectedAtElapsed = 0L
        val autoConnect = autoConnectOverride ?: shouldUseGattAutoConnect()
        currentGattAutoConnect = autoConnect
        currentGattConnectTimeoutMs = connectTimeoutMs
        currentGattMatchRssi = matchRssi
        keepAliveInFlight = false
        localBondAuthRetryCount = 0
        freshBondPrepInFlight = false
        timeRequestSent = false
        timeRequestPendingAfterControlNotify = false
        sessionStartSent = false
        updateNotif("Подключение к ${device.address}…")
        return try {
            gattConnectStartedAtMillis = System.currentTimeMillis()
            gattAttemptCreatedAtMillis = gattConnectStartedAtMillis
            gattAttemptCreatedAtElapsed = SystemClock.elapsedRealtime()
            gatt = issueConnectOnMainThread(device, autoConnect)
            Log.i(TAG, "connectGatt issued autoConnect=$autoConnect timeout=${connectTimeoutMs}ms")
            ConnectionJournal.record(this, "gatt_attempt",
                "source" to when { inWindowRetry -> "in_window_retry"; matchRssi == Int.MIN_VALUE -> "blind"; else -> "advertisement" },
                "rssi" to matchRssi.takeUnless { it == Int.MIN_VALUE }, "timeout_ms" to connectTimeoutMs,
                "contact_age_ms" to (gattConnectStartedAtMillis - lastSensorContactAtMillis), "auto_connect" to autoConnect)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "connectGatt SecurityException: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "connectGatt error: ${e.message}")
            false
        }
    }

    // OnePlus Watch 3: issuing device.connectGatt() from the binder scan-callback
    // thread intermittently produces status 133 or a silent CONNECTING hang (no
    // callback at all), which costs a whole 5-minute Dexcom window. Running the
    // connect on the main looper is the documented-reliable path. The posted call
    // returns almost immediately (it only registers the client), so the short wait
    // here resolves in milliseconds; the 2s cap is a safety net, never the norm.
    private fun issueConnectOnMainThread(
        device: BluetoothDevice,
        autoConnect: Boolean,
    ): BluetoothGatt? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return device.connectGatt(this, autoConnect, gattCb, BluetoothDevice.TRANSPORT_LE)
        }
        val result = AtomicReference<BluetoothGatt?>(null)
        val latch = CountDownLatch(1)
        mainHandler.post {
            result.set(
                runCatching {
                    device.connectGatt(this, autoConnect, gattCb, BluetoothDevice.TRANSPORT_LE)
                }.getOrNull(),
            )
            latch.countDown()
        }
        if (!latch.await(2, TimeUnit.SECONDS)) {
            Log.w(TAG, "connectGatt main-thread dispatch timed out")
        }
        return result.get()
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int): Unit = synchronized(gattLock) {
            if (!RecoveryPolicy.isCurrentCallback(gatt, g)) {
                ConnectionJournal.record(this@BondProbeService, "stale_gatt_callback", "callback" to "onConnectionStateChange")
                return
            }
            Log.i(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            ConnectionJournal.record(this@BondProbeService, "gatt_state", "status" to status, "state" to newState,
                "attempt_ms" to gattAttemptCreatedAtMillis.takeIf { it > 0L }?.let { System.currentTimeMillis() - it },
                "services" to servicesDiscovered, "rssi" to currentGattMatchRssi.takeUnless { it == Int.MIN_VALUE })
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gattRetryPolicy.connected()
                    gattConnectStartedAtMillis = System.currentTimeMillis()
                    gattConnectedAtMillis = gattConnectStartedAtMillis
                    gattConnectedAtElapsed = SystemClock.elapsedRealtime()
                    reconnectDelayMs = RECONNECT_DELAY_MS
                    directMacFailures = 0
                    updateNotif("Подключено, обнаружение сервисов…")
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    protocolWorker.execute {
                        sleep(ALT_SLOT_DISCOVERY_PAUSE_MS)
                        if (gatt !== g) {
                            Log.i(TAG, "Skipping delayed service discovery; GATT changed")
                            return@execute
                        }
                        val ok = g.discoverServices()
                        Log.i(TAG, "discoverServices() -> $ok (wear alt slot)")
                    }
                }

	                BluetoothProfile.STATE_DISCONNECTED -> {
                        if (status != BluetoothGatt.GATT_SUCCESS && !servicesDiscovered) {
                            BluetoothIncidentRecorder.capture(this@BondProbeService, "gatt_early_failure",
                                "status" to status, "connected" to (gattConnectedAtElapsed > 0L),
                                "rssi" to currentGattMatchRssi.takeUnless { it == Int.MIN_VALUE },
                                "attempt_ms" to gattAttemptCreatedAtElapsed.takeIf { it > 0L }
                                    ?.let { SystemClock.elapsedRealtime() - it })
                        }
                        val nowElapsed = SystemClock.elapsedRealtime()
                        // An early 133 of a connect made from an advertisement, inside the reading
                        // window: most likely another display device (the phone's xDrip, 4 Oct) got
                        // the transmitter first. It advertises again for its second client within
                        // seconds, so the next packet is connected to at once instead of after a pause.
                        val sensorAgeMs = lastSensorContactAtMillis.takeIf { it > 0L }?.let { System.currentTimeMillis() - it }
                        val quickRetry = status == 133 && gattConnectedAtElapsed <= 0L && !servicesDiscovered &&
                            currentGattMatchRssi != Int.MIN_VALUE && sensorAgeMs != null &&
                            sensorAgeMs >= DEXCOM_READING_PERIOD_MS - QUICK_RETRY_WINDOW_LEAD_MS
                        if (gattRetryPolicy.disconnected(status, gattConnectedAtElapsed > 0L, nowElapsed, quickRetry)) {
                            ConnectionJournal.record(this@BondProbeService, "gatt_quick_retry",
                                "status" to status, "sensor_age_ms" to sensorAgeMs, "rssi" to currentGattMatchRssi)
                            Log.i(TAG, "Early GATT $status inside the reading window; the next advertisement is connected to at once")
                        }
                        gattRetryWaitLogged = false
                        val retryDelay = gattRetryPolicy.remainingMs(nowElapsed)
                        if (retryDelay > 0L) {
                            ConnectionJournal.record(this@BondProbeService, "gatt_retry_pause",
                                "status" to status, "delay_ms" to retryDelay)
                        }
	                    updateNotif("Отключено; переподключение…")
		                    val now = System.currentTimeMillis()
		                    val lastGlucoseAt = lastSensorContactAtMillis
		                    val glucoseAge = if (lastGlucoseAt > 0L) now - lastGlucoseAt else 0L
		                    val matchRssi = currentGattMatchRssi
		                    if (!servicesDiscovered && config.knownMac.isNotBlank()) {
		                        directMacFailures += 1
		                        earlyGattFailureCount += 1
                        Log.i(TAG, "Direct MAC early disconnect count=$directMacFailures")
                        Log.i(TAG, "Early GATT failure count=$earlyGattFailureCount")
                    }
                    if (status == 147 || status == 19 || status == 133) {
                        directMacFailures = maxOf(directMacFailures, 1)
	                        preferScanUntilMillis = now + PREFER_SCAN_AFTER_DISCONNECT_MS
		                        if (!servicesDiscovered) {
		                            enableBroadRecoveryScan("gatt disconnect status=$status")
		                        }
	                        Log.i(TAG, "Transient GATT disconnect status=$status; forcing scan-first recovery until $preferScanUntilMillis")
	                    }
	                    if (
	                        !servicesDiscovered &&
	                        (status == 147 || status == 19 || status == 133) &&
	                        matchRssi != Int.MIN_VALUE &&
	                        matchRssi < POST_GATT_FAILURE_MIN_CONNECT_RSSI
	                    ) {
	                        weakGattFailureGuardUntilMillis = maxOf(
	                            weakGattFailureGuardUntilMillis,
	                            now + POST_GATT_FAILURE_RSSI_GUARD_MS,
	                        )
	                        Log.w(
	                            TAG,
	                            "Early GATT failure status=$status at RSSI=$matchRssi; requiring RSSI >= " +
	                                "$POST_GATT_FAILURE_MIN_CONNECT_RSSI until $weakGattFailureGuardUntilMillis",
	                        )
	                        DexcomConfigStore.saveScanDebug(
	                            this@BondProbeService,
	                            g.device?.name ?: "Dexcom",
	                            g.device?.address ?: config.knownMac,
	                            matchRssi,
	                            "gatt $status weak rssi $matchRssi; waiting stronger packet",
	                        )
	                    }
                    if (
                        status == 133 &&
                        !servicesDiscovered &&
                        earlyGattFailureCount >= REBOND_AFTER_EARLY_GATT_FAILURES &&
                        g.device?.bondState == BluetoothDevice.BOND_BONDED
                    ) {
                        earlyGattFailureCount = 0
                        waitingBondConfirmation = 0
                        setPhoneCollectionCooldown("repeated early gatt 133")
                        Log.w(TAG, "Repeated early GATT 133; preserving Dexcom bond and using phone cooldown only while glucose is fresh")
                        DexcomConfigStore.saveScanDebug(
                            this@BondProbeService,
                            g.device?.name ?: "Dexcom",
                            g.device?.address ?: "",
                            null,
                            "preserve bond after repeated gatt 133",
                        )
	                    }
	                    reconnectDelayMs = nextReconnectDelay(status)
	                    closeGatt("STATE_DISCONNECTED", alreadyDisconnected = true)
	                    if (
	                        status == 133 &&
	                        !servicesDiscovered &&
	                        lastGlucoseAt > 0L &&
	                        (glucoseAge >= MISSED_READING_RECOVERY_MS || isDexcomProtectedScanWindow(glucoseAge))
	                    ) {
	                        Log.w(TAG, "Matched GATT ended with 133 while stale/protected; returning to continuous scan-first")
	                        DexcomConfigStore.saveScanDebug(
	                            this@BondProbeService,
	                            g.device?.name ?: "Dexcom",
	                            g.device?.address ?: config.knownMac,
	                            null,
	                            "matched gatt 133; scan-first age=${glucoseAge}ms",
	                        )
	                    }
	                    if (wantConnected && btAdapter?.isEnabled == true && !scanning) {
	                        Log.i(TAG, "Immediate scan restart from disconnect callback")
                        runCatching { startScanInternal() }
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int): Unit = synchronized(gattLock) {
            if (!RecoveryPolicy.isCurrentCallback(gatt, g)) {
                ConnectionJournal.record(this@BondProbeService, "stale_gatt_callback", "callback" to "onServicesDiscovered")
                return
            }
            Log.i(TAG, "onServicesDiscovered: status=$status, services=${g.services?.size ?: 0}")
            updateNotif("Сервисы найдены (${g.services?.size ?: 0}) — ждём auth/start")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            servicesDiscovered = true
            earlyGattFailureCount = 0
            weakGattFailureGuardUntilMillis = 0L
            resetWeakMatchCandidate()
            gattConnectStartedAtMillis = 0L
            cacheDexcomCharacteristics(g.services)
            readAuthCharacteristic(g)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Unit = synchronized(gattLock) {
            if (!RecoveryPolicy.isCurrentCallback(gatt, g)) {
                ConnectionJournal.record(this@BondProbeService, "stale_gatt_callback", "callback" to "onCharacteristicChanged")
                return
            }
            val value = ch.value ?: byteArrayOf()
            Log.i(TAG, "onCharacteristicChanged: ${ch.uuid} len=${value.size} hex=${value.toHex()}")
            if (ch.uuid == authCharacteristic?.uuid && value.isNotEmpty()) {
                if (value[0].toInt() and 0xFF == 0x06) {
                    Log.i(TAG, "KeepAlive response received: ${value.toHex()}")
                    return
                }
                when (value[0].toInt() and 0xFF) {
                    AUTH_OPCODE_STATUS -> handleAuthStatus(g, value)
                    AUTH_OPCODE_CHALLENGE -> handleAuthChallenge(g, value)
                    AUTH_OPCODE_TIME_RX -> handleTransmitterTime(g, value)
                    AUTH_OPCODE_SESSION_START_RX -> handleSessionStart(g, value)
                    else -> Log.i(TAG, "Unhandled auth indication opcode=${value[0].toInt() and 0xFF}")
                }
                return
            }
            if (ch.uuid == controlCharacteristic?.uuid && value.isNotEmpty()) {
                when (value[0].toInt() and 0xFF) {
                    AUTH_OPCODE_TIME_RX -> handleTransmitterTime(g, value)
                    AUTH_OPCODE_SESSION_START_RX -> handleSessionStart(g, value)
                    SENSOR_OPCODE, GLUCOSE_OPCODE, E_GLUCOSE_OPCODE, E_GLUCOSE2_OPCODE -> {
                        val opcode = value[0].toInt() and 0xFF
                        runCatching { SensorProtocol.reading(value) }
                            .onSuccess { sensor ->
                                lastSensorContactAtMillis = System.currentTimeMillis()
                                SensorSessionStore.recordReading(this@BondProbeService, sensor, lastSensorContactAtMillis)
                                earlyGattFailureCount = 0
                                consecutiveSilentBroadScanRestarts = 0
                                sensorRequestSent = false
                                if (!sensor.usable) {
                                    if (sensor.displayOnly && sensor.state in setOf(6, 7) && sensor.glucose in 20..600 && sensor.ageSeconds < 305) {
                                        // Shown with a mark on the face; not handed to the phone or the pump controller.
                                        Log.i(TAG, "display-only glucose ${sensor.glucose} mg/dL; shown, not forwarded")
                                        DexcomConfigStore.saveDisplayOnlyGlucose(
                                            this@BondProbeService, sensor.glucose, sensor.timestamp, sensor.ageSeconds,
                                            "opcode=0x${opcode.toString(16).uppercase()} display-only",
                                        )
                                        updateNotif("Dexcom: ${sensor.glucose} (не подтверждено передатчиком)")
                                        requestComplicationRefresh("display-only glucose", force = true)
                                        scheduleNextReadingWindowAlarm("display-only glucose")
                                        releaseReadingWindowWakeLock("display-only glucose received")
                                        keepGattAfterGlucose(g)
                                        return@onSuccess
                                    }
                                    val status = SensorSessionStore.load(this@BondProbeService).glucoseStatus()
                                    updateNotif(status)
                                    requestComplicationRefresh("sensor status", force = true)
                                    scheduleNextReadingWindowAlarm("sensor status")
                                    releaseReadingWindowWakeLock("sensor status received")
                                    keepGattAfterGlucose(g)
                                    return@onSuccess
                                }
                                val previousDirect = DexcomConfigStore.loadDirectGlucose(this@BondProbeService)
                                val timestampRollbackSeconds = previousDirect.dexTimestamp - sensor.timestamp
                                val looksLikeNewTransmitterTimeline =
                                    timestampRollbackSeconds >= DEXCOM_TIMESTAMP_RESET_ACCEPT_SECONDS
                                if (
                                    previousDirect.dexTimestamp > 0 &&
                                    sensor.timestamp <= previousDirect.dexTimestamp &&
                                    !looksLikeNewTransmitterTimeline
                                ) {
                                    sensorRequestSent = false
                                    val event = "duplicate glucose ${sensor.glucose} mg/dL opcode=0x${opcode.toString(16).uppercase()} ts=${sensor.timestamp} currentTs=${previousDirect.dexTimestamp}"
                                    Log.i(TAG, event)
                                    DexcomConfigStore.saveScanDebug(
                                        this@BondProbeService,
                                        "Dexcom connected",
                                        g.device?.address ?: "",
                                        null,
                                        event,
                                    )
                                    updateNotif("Dexcom: жду свежий пакет")
                                    maybeEnsureReadingWindowWakeLock("duplicate glucose")
                                    keepGattAfterGlucose(g)
                                    return@onSuccess
                                }
                                if (looksLikeNewTransmitterTimeline) {
                                    val resetEvent =
                                        "dex timestamp reset accepted ${previousDirect.dexTimestamp}->${sensor.timestamp}; treating as new transmitter/session"
                                    Log.w(TAG, resetEvent)
                                    DexcomConfigStore.saveScanDebug(
                                        this@BondProbeService,
                                        "Dexcom connected",
                                        g.device?.address ?: "",
                                        null,
                                        resetEvent,
                                    )
                                }
                                lastSensorContactAtMillis = System.currentTimeMillis()
                                earlyGattFailureCount = 0
                                consecutiveSilentBroadScanRestarts = 0
                                sensorRequestSent = false
                                lastSensorRequestAtMillis = 0L
                                preferScanUntilMillis = lastSensorContactAtMillis + PREFER_SCAN_AFTER_SUCCESS_MS
                                forceBroadScanUntilMillis = 0L
                                releaseReadingWindowWakeLock("glucose received")
                                val event = "GLUCOSE ${sensor.glucose} mg/dL opcode=0x${opcode.toString(16).uppercase()} ts=${sensor.timestamp} age=${sensor.ageSeconds}"
                                Log.i(TAG, event)
                                val directReading = DexcomConfigStore.saveGlucose(
                                    this@BondProbeService,
                                    sensor.glucose,
                                    sensor.timestamp,
                                    sensor.ageSeconds,
                                    "opcode=0x${opcode.toString(16).uppercase()}",
                                )
                                GlucoseSyncBridge.sendWatchGlucose(
                                    context = this@BondProbeService,
                                    reading = directReading,
                                    transmitterId = config.transmitterId,
                                )
                                requestComplicationRefresh("glucose packet", force = true)
                                updateNotif("Глюкоза ${sensor.glucose} mg/dL")
                                scheduleNextReadingWindowAlarm("glucose packet")
                                DexcomConfigStore.saveScanDebug(
                                    this@BondProbeService,
                                    "Dexcom connected",
                                    g.device?.address ?: "",
                                    null,
                                    event,
                                )
                                keepGattAfterGlucose(g)
                            }
                            .onFailure { error ->
                                Log.e(TAG, "Failed to parse glucose-like packet opcode=0x${opcode.toString(16).uppercase()} hex=${value.toHex()}", error)
                                DexcomConfigStore.saveScanDebug(
                                    this@BondProbeService,
                                    "Dexcom parse error",
                                    g.device?.address ?: "",
                                    null,
                                    "glucose parse error opcode=0x${opcode.toString(16).uppercase()} hex=${value.toHex()}",
                                )
                            }
                    }
                    else -> Log.i(TAG, "Unhandled control indication opcode=${value[0].toInt() and 0xFF}")
                }
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int,
        ): Unit = synchronized(gattLock) {
            if (!RecoveryPolicy.isCurrentCallback(gatt, g)) {
                ConnectionJournal.record(this@BondProbeService, "stale_gatt_callback", "callback" to "onCharacteristicRead")
                return
            }
            val value = ch.value ?: byteArrayOf()
            Log.i(TAG, "onCharacteristicRead: uuid=${ch.uuid} status=$status hex=${value.toHex()}")
            if (status != BluetoothGatt.GATT_SUCCESS || ch.uuid != authCharacteristic?.uuid || value.isEmpty()) return
            when (value[0].toInt() and 0xFF) {
                AUTH_OPCODE_STATUS -> handleAuthStatus(g, value)
                AUTH_OPCODE_CHALLENGE -> handleAuthChallenge(g, value)
                AUTH_OPCODE_TIME_RX -> handleTransmitterTime(g, value)
                AUTH_OPCODE_SESSION_START_RX -> handleSessionStart(g, value)
                else -> {
                    Log.i(TAG, "Unhandled auth opcode ${(value[0].toInt() and 0xFF)}; sending auth request")
                    sendAuthRequest(g)
                }
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ): Unit = synchronized(gattLock) {
            if (!RecoveryPolicy.isCurrentCallback(gatt, g)) {
                ConnectionJournal.record(this@BondProbeService, "stale_gatt_callback", "callback" to "onDescriptorWrite")
                return
            }
            Log.i(TAG, "onDescriptorWrite: ${descriptor.characteristic.uuid} status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (descriptor.characteristic.uuid == authCharacteristic?.uuid) {
                sendAuthRequest(g)
            } else if (descriptor.characteristic.uuid == controlCharacteristic?.uuid) {
                if (timeRequestPendingAfterControlNotify) {
                    timeRequestPendingAfterControlNotify = false
                    writeTimeRequest(g)
                } else {
                    writeSensorRequest(g)
                }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int,
        ): Unit = synchronized(gattLock) {
            if (!RecoveryPolicy.isCurrentCallback(gatt, g)) {
                ConnectionJournal.record(this@BondProbeService, "stale_gatt_callback", "callback" to "onCharacteristicWrite")
                return
            }
            val value = ch.value ?: byteArrayOf()
            Log.i(TAG, "onCharacteristicWrite: uuid=${ch.uuid} status=$status hex=${value.toHex()}")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (ch.uuid == authCharacteristic?.uuid && keepAliveInFlight) {
                Log.i(TAG, "KeepAlive write complete; sending bond request next")
                launchBondRequestAfterKeepAlive(g)
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int): Unit = synchronized(gattLock) {
            if (!RecoveryPolicy.isCurrentCallback(gatt, g)) {
                ConnectionJournal.record(this@BondProbeService, "stale_gatt_callback", "callback" to "onReadRemoteRssi")
                return
            }
            Log.d(TAG, "RSSI=$rssi status=$status")
        }
    }

    private fun keepGattAfterGlucose(gattToKeep: BluetoothGatt) {
        if (gatt === gattToKeep) {
            Log.i(TAG, "Keeping Dexcom GATT after glucose in watch-primary mode")
        }
    }

    private fun requestBleSessionRecovery(reason: String) {
        reconnectDelayMs = 2_000L
        pendingBleRecoveryReason = reason
        pendingBleRecoveryAtMillis = System.currentTimeMillis()
        Log.w(TAG, "BLE session recovery requested: $reason")
        DexcomConfigStore.saveScanDebug(this, "", config.knownMac, null, "ble recovery requested: $reason")
    }

    private fun handlePendingBleSessionRecovery(): Boolean {
        val requestedAt = pendingBleRecoveryAtMillis
        if (requestedAt <= 0L) return false
        val elapsed = System.currentTimeMillis() - requestedAt
        if (elapsed < BLE_RECOVERY_SETTLE_MS) return true

        val reason = pendingBleRecoveryReason.ifBlank { "ble recovery" }
        pendingBleRecoveryAtMillis = 0L
        pendingBleRecoveryReason = ""
        Log.w(TAG, "Handling BLE session recovery: $reason")
        DexcomConfigStore.saveScanDebug(this, "", config.knownMac, null, "ble recovery: $reason")
        resetBleSessionAfterAdapterRestart(reason)
        enableBroadRecoveryScan(reason)
        scheduleNextReadingWindowAlarm(reason)

        if (btAdapter?.isEnabled != true || !wantConnected) {
            Log.w(TAG, "BLE recovery paused; adapter disabled or service stopping")
            return true
        }

        maybeEnsureReadingWindowWakeLock(reason)
        sleep(1_000)
        startScanInternal()
        return true
    }

    private fun handleReadingWindowRefreshRequest(): Boolean = synchronized(gattLock) {
        val requestedAt = readingWindowRefreshRequestedAtMillis
        if (requestedAt <= 0L) return false
        readingWindowRefreshRequestedAtMillis = 0L
        maybeEnsureReadingWindowWakeLock("alarm worker")
        if (gatt != null || btAdapter?.isEnabled != true) return false
        if (scanning) {
            currentScanBroad = true
            return false
        }

        Log.i(TAG, "Reading-window alarm starting missing scanner before Dexcom packet")
        runCatching { startScanInternal() }
        return true
    }

    private fun resetBleSessionAfterAdapterRestart(reason: String) {
        Log.w(TAG, "Reset BLE session after adapter change: $reason")
        runCatching { stopScan() }
        scanner = null
        currentScanBroad = false
        servicesDiscovered = false
        sensorRequestSent = false
        timeRequestSent = false
        timeRequestPendingAfterControlNotify = false
        sessionStartSent = false
        keepAliveInFlight = false
        authCharacteristic = null
        controlCharacteristic = null
        authRequestToken = null
        closeGatt(reason)
    }

    private fun closeGatt(
        reason: String,
        expected: BluetoothGatt? = null,
        alreadyDisconnected: Boolean = false,
    ): Boolean = synchronized(gattLock) {
        if (expected != null && gatt !== expected) return false
        gattAttemptCreatedAtMillis = 0L
        gattAttemptCreatedAtElapsed = 0L
        gattConnectedAtElapsed = 0L
        gattConnectStartedAtMillis = 0L
        gattConnectedAtMillis = 0L
        currentGattAutoConnect = false
        currentGattConnectTimeoutMs = CONNECT_ATTEMPT_TIMEOUT_MS
        currentGattMatchRssi = Int.MIN_VALUE
        lastSensorRequestAtMillis = 0L
        resetWeakMatchCandidate()
        val closing = gatt
        gatt = null
        closing?.let {
            Log.i(TAG, "closeGatt ($reason)")
            if (!alreadyDisconnected) runCatching { it.disconnect() }
            // Clear the stale GATT service cache before releasing the client. A
            // poisoned cache is a common cause of status 133 on the next connect;
            // refresh() is hidden API, hence reflection, and best-effort.
            runCatching { it.javaClass.getMethod("refresh").invoke(it) }
            runCatching { it.close() }
        }
        true
    }

    private fun startScanInternal(): Boolean = synchronized(gattLock) {
        if (scanning) return true
        if (gatt != null || !wantConnected) return false
        ensureCollectorWakeLock("start scan")
        maybeEnsureReadingWindowWakeLock("start scan")
        val adapter = btAdapter ?: return false
        val bleScanner = adapter.bluetoothLeScanner ?: return false
            scanner = bleScanner
            scanning = true
            val scanStartedAt = System.currentTimeMillis()
            try {
            val settingsBuilder = ScanSettings.Builder()
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                settingsBuilder
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settingsBuilder.setLegacy(true)
            }
            val settings = settingsBuilder.build()
            val filters = mutableListOf<ScanFilter>()
            val knownMac = config.knownMac
            val transmitterSuffix = config.transmitterId.takeLast(2)
            val expectedDexcomName =
                if (transmitterSuffix.length == 2) "$NAME_PREFIX$transmitterSuffix" else ""
            val broadScan = shouldUseBroadDexcomScan()
            if (expectedDexcomName.isNotBlank()) {
                filters += ScanFilter.Builder()
                    .setDeviceName(expectedDexcomName)
                    .build()
            }
            if (knownMac.isNotBlank()) {
                filters += ScanFilter.Builder()
                    .setDeviceAddress(knownMac)
                    .build()
            }
            currentScanBroad = broadScan || filters.isEmpty()
            // OnePlus Watch 3 appears to throttle unfiltered scans when asleep.
            // Keep controller-side Dexcom filters even during recovery, then do
            // manual validation in the callback before connecting.
            bleScanner.startScan(filters.ifEmpty { null }, settings, leCallback)
            lastScanStartedAtMillis = scanStartedAt
            lastScanStartedAtElapsed = SystemClock.elapsedRealtime()
            lastAnyScanCallbackAtMillis = 0L
            ConnectionJournal.record(this, "scan_started", "filters" to filters.size)
            val filterMode = when {
                isBroadRecoveryScanActive() -> "(dexcom-filtered recovery)"
                shouldUseBroadNearReadingWindow() -> "(dexcom-filtered reading window)"
                expectedDexcomName.isNotBlank() -> "(device name=$expectedDexcomName)"
                filters.isEmpty() -> "(manual match/no mac)"
                else -> "(addr filter)"
            }
            Log.i(
                TAG,
                "BLE scan started filters=${filters.size}$filterMode txid=${config.transmitterId} mac=${config.knownMac}",
            )
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "startScan SecurityException: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "startScan error: ${e.message}")
        }
        scanning = false
        currentScanBroad = false
        return false
    }

    private fun shouldUseGattAutoConnect(): Boolean {
        // OnePlus Watch 3 is most stable when we connect only after seeing a
        // real connectable Dexcom advertisement.
        return false
    }

    private fun refreshScanBeforePlatformTimeout(): Boolean = synchronized(gattLock) {
        if (!scanning || gatt != null || !wantConnected || btAdapter?.isEnabled != true) return false
        val now = System.currentTimeMillis()
        if (pendingMatchConnectUntilMillis > now || lastScanStartedAtElapsed <= 0L) return false
        val scanAge = SystemClock.elapsedRealtime() - lastScanStartedAtElapsed
        val contactAge = lastSensorContactAtMillis.takeIf { it > 0L }?.let { now - it }
        if (!RecoveryPolicy.shouldRefreshScan(scanAge, contactAge)) return false

        ConnectionJournal.record(this, "scan_refresh", "reason" to "before_platform_timeout",
            "scan_age_ms" to scanAge, "contact_age_ms" to contactAge)
        stopScan()
        sleep(SCANNER_STOP_START_SETTLE_MS)
        startScanInternal()
    }

    private fun stopScan(): Unit = synchronized(gattLock) {
        if (!scanning) return
        try {
            scanner?.stopScan(leCallback)
        } catch (_: Exception) {
        }
        ConnectionJournal.record(this, "scan_stopped", "scan_age_ms" to
            lastScanStartedAtElapsed.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - it })
        scanning = false
        currentScanBroad = false
        scanner = null
        lastScanStartedAtMillis = 0L
        lastScanStartedAtElapsed = 0L
        lastAnyScanCallbackAtMillis = 0L
        Log.i(TAG, "Scan stopped")
    }

    private val leCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult): Unit = synchronized(gattLock) {
            if (!wantConnected || gatt != null) return
            val now = System.currentTimeMillis()
            lastAnyScanCallbackAtMillis = now
            consecutiveSilentBroadScanRestarts = 0
            val device = result.device ?: return
            val name = result.scanRecord?.deviceName ?: device.name
            val addr = device.address ?: "?"
            val transmitterId = config.transmitterId
            val knownMac = config.knownMac
            val uuids = result.scanRecord?.serviceUuids?.joinToString() ?: ""
            val raw = result.scanRecord?.bytes?.joinToString(separator = "") { "%02X".format(it) }.orEmpty()
            val connectable = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || result.isConnectable
            val firstSeen = synchronized(seenAdvertisers) { seenAdvertisers.add(addr) }
            val rawNameOk = raw.contains("446578636F6D", ignoreCase = true)
            val dexcomServiceOk = uuids.contains("febc", ignoreCase = true) || raw.contains("BCFE", ignoreCase = true)

            val nameOk = name?.startsWith(NAME_PREFIX, ignoreCase = true) == true || rawNameOk
            val macOk = knownMac.isNotEmpty() && addr.equals(knownMac, ignoreCase = true)
            val txidOk = transmitterId.isNotBlank() &&
                (
                    name?.contains(transmitterId.takeLast(2), ignoreCase = true) == true ||
                        raw.contains(transmitterId.takeLast(2).toByteArray(Charsets.UTF_8).toHex(), ignoreCase = true)
                    )

            val wantTxid = transmitterId.isNotEmpty()
            val match = if (knownMac.isNotEmpty()) {
                macOk || (nameOk && (!wantTxid || txidOk))
            } else {
                nameOk && (!wantTxid || txidOk)
            }

            if (match || nameOk || dexcomServiceOk) {
                lastDexcomAdvertisementAtMillis = now
            }
            val shouldSampleNonMatch = now - lastNonMatchDebugAtMillis >= SCAN_DEBUG_THROTTLE_MS
            val looksRelevant = match || nameOk || dexcomServiceOk
            if (looksRelevant || shouldSampleNonMatch) {
                if (!looksRelevant) lastNonMatchDebugAtMillis = now
                Log.d(
                    TAG,
                    "scan: $name [$addr] rssi=${result.rssi} uuids=$uuids " +
                        "connectable=$connectable nameOk=$nameOk macOk=$macOk txidOk=$txidOk match=$match",
                )
            }
            if (firstSeen && (looksRelevant || shouldSampleNonMatch)) {
                Log.i(TAG, "adv-first: [$addr] name=$name raw=$raw")
            }
            if (match) {
                val packetAge = (SystemClock.elapsedRealtimeNanos() - result.timestampNanos) / 1_000_000L
                ConnectionJournal.record(this@BondProbeService, "sensor_advertisement",
                    "packet_age_ms" to packetAge, "rssi" to result.rssi, "connectable" to connectable,
                    "interactive" to getSystemService(PowerManager::class.java)?.isInteractive)
                DexcomConfigStore.saveScanDebug(
                    this@BondProbeService,
                    name ?: "",
                    addr,
                    result.rssi,
                    if (match) "matched advertisement" else "advertisement seen",
                )
            }

            if (match) {

                val retryWait = gattRetryPolicy.remainingMs(SystemClock.elapsedRealtime())
                if (retryWait > 0L) {
                    if (!gattRetryWaitLogged) {
                        ConnectionJournal.record(this@BondProbeService, "gatt_retry_wait",
                            "remaining_ms" to retryWait, "scanning" to scanning)
                        gattRetryWaitLogged = true
                    }
                    return
                }
	                if (!connectable) {
	                    Log.i(TAG, "MATCH non-connectable: $name [$addr] — keeping scan open for connectable Dexcom window")
	                    DexcomConfigStore.saveScanDebug(
	                        this@BondProbeService,
                        name ?: "",
                        addr,
                        result.rssi,
                        "matched non-connectable advertisement",
	                    )
	                    return
	                }
			                val minConnectRssi = minConnectableDexcomRssi(now)
			                if (result.rssi < minConnectRssi) {
			                    val guarded = minConnectRssi > MIN_CONNECTABLE_DEXCOM_RSSI
			                    val reason = if (guarded) {
			                        "matched weak rssi after gatt failure; waiting stronger packet"
			                    } else {
			                        "matched weak rssi; waiting stronger packet"
			                    }
			                    if (guarded && result.rssi >= MIN_CONNECTABLE_DEXCOM_RSSI && gatt == null) {
			                        if (
			                            weakMatchCandidateFirstAtMillis <= 0L ||
			                            now - weakMatchCandidateFirstAtMillis > WEAK_MATCH_CANDIDATE_RESET_MS
			                        ) {
			                            weakMatchCandidateFirstAtMillis = now
			                            weakMatchCandidateBestRssi = result.rssi
			                        } else {
			                            weakMatchCandidateBestRssi = maxOf(weakMatchCandidateBestRssi, result.rssi)
			                        }
			                        val candidateAge = now - weakMatchCandidateFirstAtMillis
			                        if (
			                            candidateAge >= WEAK_MATCH_FALLBACK_AFTER_MS &&
			                            result.rssi >= POST_GATT_FAILURE_WEAK_FALLBACK_RSSI &&
			                            result.rssi >= weakMatchCandidateBestRssi
			                        ) {
			                            Log.w(
			                                TAG,
			                                "MATCH weak fallback RSSI ${result.rssi} after ${candidateAge}ms: " +
			                                    "$name [$addr] — connecting to best weak Dexcom packet",
			                            )
			                            DexcomConfigStore.saveScanDebug(
			                                this@BondProbeService,
			                                name ?: "",
			                                addr,
			                                result.rssi,
			                                "weak rssi fallback connect after gatt failure",
			                            )
			                            DexcomConfigStore.cacheDetectedMac(this@BondProbeService, addr)
			                            config = config.copy(knownMac = addr)
			                            updateNotif("Dexcom слабый RSSI ${result.rssi}, пробую лучшее окно")
			                            pendingMatchConnectUntilMillis = now + MATCH_CONNECT_GRACE_MS
			                            resetWeakMatchCandidate()
			                            stopScan()
			                            sleep(CONNECT_AFTER_SCAN_STOP_DELAY_MS)
			                            if (gatt == null) {
			                                if (!connectGatt(device, matchRssi = result.rssi)) {
			                                    pendingMatchConnectUntilMillis = 0L
			                                }
			                            } else {
			                                pendingMatchConnectUntilMillis = 0L
			                                Log.i(TAG, "Ignoring weak fallback while GATT already active")
			                            }
			                            return
			                        }
			                    }
			                    Log.i(
			                        TAG,
			                        "MATCH weak RSSI ${result.rssi} (min=$minConnectRssi): $name [$addr] — " +
		                            "keeping scan open for stronger Dexcom packet",
		                    )
		                    DexcomConfigStore.saveScanDebug(
		                        this@BondProbeService,
		                        name ?: "",
		                        addr,
		                        result.rssi,
		                        reason,
		                    )
		                    return
		                }
                if (shouldHonorConnectCooldown(now)) {
                    val last = lastSensorContactAtMillis
                    val age = if (last > 0L) now - last else 0L
                    Log.i(TAG, "MATCH shortly after glucose (${age}ms); suppressing duplicate connect")
                    DexcomConfigStore.saveScanDebug(
                        this@BondProbeService,
                        name ?: "",
                        addr,
                        result.rssi,
                        "recent glucose; suppress duplicate connect ${age}ms",
                    )
                    return
                }
                Log.i(TAG, "MATCH: $name [$addr] — connect/bond")
                DexcomConfigStore.cacheDetectedMac(this@BondProbeService, addr)
                config = config.copy(knownMac = addr)
                updateNotif("Нашёл $name, пытаюсь спариться")
                pendingMatchConnectUntilMillis = now + MATCH_CONNECT_GRACE_MS
                stopScan()
                sleep(CONNECT_AFTER_SCAN_STOP_DELAY_MS)
	                if (gatt == null) {
	                    if (!connectGatt(device, matchRssi = result.rssi)) {
	                        pendingMatchConnectUntilMillis = 0L
	                    }
                } else {
                    pendingMatchConnectUntilMillis = 0L
                    Log.i(TAG, "Ignoring duplicate match while GATT already active")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            BluetoothIncidentRecorder.capture(this@BondProbeService, "scan_failure", "error_code" to errorCode)
            if (errorCode == ScanCallback.SCAN_FAILED_ALREADY_STARTED) {
                Log.w(TAG, "BLE scan already started; keeping scanner state instead of recovery storm")
                scanning = true
                DexcomConfigStore.saveScanDebug(
                    this@BondProbeService,
                    "",
                    config.knownMac,
                    null,
                    "scan already started; keeping active",
                )
                return
            }
            Log.e(TAG, "Scan failed: $errorCode")
            scanning = false
            currentScanBroad = false
            scanner = null
            DexcomConfigStore.saveScanDebug(
                this@BondProbeService,
                "",
                "",
                null,
                "scan failed: $errorCode",
            )
            enableBroadRecoveryScan("scan failed: $errorCode")
            requestBleSessionRecovery("scan failed: $errorCode")
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun buildStartupText(): String {
        return when {
            config.transmitterId.isNotBlank() ->
                "Жду Dexcom ${config.transmitterId} / сенсор ${config.sensorCode.ifBlank { "не задан" }}"
            else -> "Укажите Dexcom трансмиттер в настройках"
        }
    }

    private fun cacheDexcomCharacteristics(services: List<BluetoothGattService>) {
        val cgmService =
            services.firstOrNull { it.uuid.toString().equals("f8083532-849e-531c-c594-30f1f86a4ea5", ignoreCase = true) }
        authCharacteristic =
            cgmService?.getCharacteristic(UUID.fromString("F8083535-849E-531C-C594-30F1F86A4EA5"))
        controlCharacteristic =
            cgmService?.getCharacteristic(UUID.fromString("F8083534-849E-531C-C594-30F1F86A4EA5"))
        Log.i(TAG, "Dexcom characteristics cached auth=${authCharacteristic != null} control=${controlCharacteristic != null}")
    }

    private fun readAuthCharacteristic(gatt: BluetoothGatt) {
        val auth = authCharacteristic ?: run {
            Log.e(TAG, "Authentication characteristic not found")
            return
        }
        Log.i(TAG, "Auth characteristic props=${auth.properties}")
        runCatching { gatt.setCharacteristicNotification(auth, true) }
        val descriptor = auth.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        if (descriptor == null) {
            Log.i(TAG, "Auth CCCD missing, direct AuthRequest write")
            sendAuthRequest(gatt)
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        val ok = runCatching { gatt.writeDescriptor(descriptor) }.getOrDefault(false)
        Log.i(TAG, "enableAuthNotifications -> $ok")
        if (!ok) {
            sendAuthRequest(gatt)
        }
    }

    private fun scheduleAuthStatusRead(gatt: BluetoothGatt, reason: String) {
        protocolWorker.execute {
            sleep(AUTH_STATUS_READ_DELAY_MS)
            if (this.gatt != gatt) {
                Log.i(TAG, "skip auth read ($reason): gatt changed")
                return@execute
            }
            val auth = authCharacteristic ?: return@execute
            val ok = runCatching { gatt.readCharacteristic(auth) }.getOrDefault(false)
            Log.i(TAG, "readAuthCharacteristic[$reason] -> $ok")
        }
    }

    private fun sendAuthRequest(gatt: BluetoothGatt) {
        val auth = authCharacteristic ?: return
        val token = ByteArray(8)
        SecureRandom().nextBytes(token)
        authRequestToken = token
        val packet = byteArrayOf(0x01, *token, AUTH_REQUEST_END_BYTE_ALT)
        Log.i(TAG, "Sending AuthRequest ${packet.toHex()} (wear alt slot)")
        writeCharacteristicBytes(gatt, auth, packet)
    }

    private fun handleAuthChallenge(gatt: BluetoothGatt, value: ByteArray) {
        if (value.size < 17) return
        val tokenHash = value.copyOfRange(1, 9)
        val challenge = value.copyOfRange(9, 17)
        Log.i(TAG, "AuthChallenge tokenHash=${tokenHash.toHex()} challenge=${challenge.toHex()}")
        val token = authRequestToken
        if (token != null) {
            val verify = calculateDexcomHash(token)
            Log.i(TAG, "AuthChallenge verify=${verify?.toHex()}")
        }
        val response = calculateDexcomHash(challenge) ?: return
        val auth = authCharacteristic ?: return
        val packet = byteArrayOf(0x04, *response)
        Log.i(TAG, "Sending AuthChallengeReply ${packet.toHex()}")
        writeCharacteristicBytes(gatt, auth, packet)
        scheduleAuthStatusRead(gatt, "after-challenge-reply")
    }

    private fun handleAuthStatus(gatt: BluetoothGatt, value: ByteArray) {
        if (value.size < 3) return
        val authenticated = value[1].toInt() and 0xFF
        val bonded = value[2].toInt() and 0xFF
        val locallyBonded = runCatching { gatt.device?.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        Log.i(TAG, "AuthStatus authenticated=$authenticated bonded=$bonded")
        DexcomConfigStore.saveScanDebug(
            this,
            "Dexcom auth",
            gatt.device?.address ?: "",
            null,
            "auth=$authenticated bonded=$bonded",
        )
        when {
            authenticated == 1 && bonded == 1 -> {
                localBondAuthRetryCount = 0
                val sensorCode = config.sensorCode
                if (!sessionStartSent && sensorCode.isNotBlank()) {
                    protocolWorker.execute {
                        sleep(150)
                        sendTimeRequest(gatt)
                    }
                } else if (!sensorRequestSent) {
                    protocolWorker.execute {
                        sleep(150)
                        enableControlNotifications(gatt)
                    }
                }
            }
            authenticated == 1 && bonded == 2 -> {
                if (!bondFlowStarted) {
                    bondFlowStarted = true
                    Log.i(TAG, "Proceeding with keepalive/bond flow (locallyBonded=$locallyBonded)")
                    protocolWorker.execute { sendKeepAlive(gatt) }
                } else {
                    Log.i(TAG, "Bond flow already started; waiting for next auth status")
                }
            }
            else -> {
                bondFlowStarted = false
                sensorRequestSent = false
                sendAuthRequest(gatt)
            }
        }
    }

    private fun sendKeepAlive(gatt: BluetoothGatt) {
        val auth = authCharacteristic ?: return
        val keepAlive = byteArrayOf(0x06, 0x19)
        keepAliveInFlight = true
        Log.i(TAG, "Sending KeepAlive ${keepAlive.toHex()}")
        val keepAliveOk = writeCharacteristicBytes(gatt, auth, keepAlive)
        Log.i(TAG, "writeKeepAlive -> $keepAliveOk")
        if (!keepAliveOk) {
            keepAliveInFlight = false
        }
    }

    private fun launchBondRequestAfterKeepAlive(gatt: BluetoothGatt) {
        if (!keepAliveInFlight) return
        keepAliveInFlight = false
        protocolWorker.execute { sendBondRequestAndCreateBond(gatt) }
    }

    private fun sendBondRequestAndCreateBond(gatt: BluetoothGatt) {
        val auth = authCharacteristic ?: return
        val device = gatt.device
        val bondStateBefore = runCatching { device?.bondState }.getOrNull()
        val alreadyBonded = bondStateBefore == BluetoothDevice.BOND_BONDED
        Log.i(TAG, "Local bond state before bond flow=$bondStateBefore")

        waitingBondConfirmation = 1
        val packet = byteArrayOf(0x07)
        Log.i(TAG, "Sending BondRequest ${packet.toHex()}")
        val bondOk = writeCharacteristicBytes(gatt, auth, packet)
        Log.i(TAG, "writeBondRequest -> $bondOk")
        if (!bondOk) {
            if (alreadyBonded) {
                Log.i(TAG, "BondRequest write failed, but local bond already exists; continuing with transmitter time request")
                protocolWorker.execute {
                    sleep(250)
                    if (this.gatt == gatt) {
                        sendTimeRequest(gatt)
                    }
                }
            }
            return
        }

        sleep(800)
        if (!alreadyBonded) {
            wakePairingScreen(device)
            runCatching {
                val createOk = device?.createBond()
                Log.i(TAG, "createBond after bond request -> $createOk")
            }.onFailure {
                Log.e(TAG, "createBond error: ${it.message}")
            }
        } else {
            Log.i(TAG, "Skipping wake/createBond because local bond already exists")
        }

        protocolWorker.execute {
            waitForLocalBond(device)
            scheduleAuthStatusRead(gatt, "after-bond-request")
            if (alreadyBonded && !sessionStartSent && config.sensorCode.isNotBlank()) {
                sleep(600)
                if (this.gatt == gatt) {
                    Log.i(TAG, "Local bond already present after bond request; optimistically requesting transmitter time")
                    sendTimeRequest(gatt)
                }
            }
        }
    }

    private fun prepareFreshBond(gatt: BluetoothGatt) {
        val device = gatt.device ?: return
        updateNotif("Сбрасываю локальный bond Dexcom и переподключаю")
        removeBond(device)
        directMacFailures = 1
        sleep(250)
        if (this.gatt == gatt) {
            runCatching { gatt.disconnect() }
        }
    }

    private fun removeBond(device: BluetoothDevice?) {
        if (device == null) return
        runCatching {
            Log.i(TAG, "removeBond() for ${device.address}")
            val method = device.javaClass.getMethod("removeBond")
            val removed = method.invoke(device) as? Boolean
            Log.i(TAG, "removeBond() -> $removed")
        }.onFailure {
            Log.e(TAG, "removeBond failed: ${it.message}")
        }
    }

    private fun waitForLocalBond(device: BluetoothDevice?) {
        if (device == null) return
        repeat(10) { i ->
            val state = device.bondState
            Log.i(TAG, "Bond wait[$i]: state=$state waiting=$waitingBondConfirmation")
            if (state == BluetoothDevice.BOND_BONDED || waitingBondConfirmation == 2) return
            if (state == BluetoothDevice.BOND_BONDING) {
                runCatching {
                    device.setPairingConfirmation(true)
                    Log.i(TAG, "Bond wait[$i]: setPairingConfirmation(true)")
                }.onFailure {
                    Log.e(TAG, "Bond wait[$i]: pairing confirmation failed: ${it.message}")
                }
            } else if (state == BluetoothDevice.BOND_NONE && i == 0) {
                runCatching {
                    val createOk = device.createBond()
                    Log.i(TAG, "createBond during wait -> $createOk")
                }.onFailure {
                    Log.e(TAG, "createBond during wait error: ${it.message}")
                }
            }
            sleep(1000)
        }
    }

    private fun nextReconnectDelay(status: Int): Long {
        reconnectDelayMs = when {
            status == 133 -> minOf(reconnectDelayMs + 10_000L, 60_000L)
            status == 147 || status == 19 -> 2_000L
            else -> RECONNECT_DELAY_MS
        }
        Log.i(TAG, "Next reconnect delay set to ${reconnectDelayMs}ms for status=$status")
        return reconnectDelayMs
    }

    private fun enableControlNotifications(gatt: BluetoothGatt) {
        val control = controlCharacteristic ?: run {
            Log.e(TAG, "Control characteristic not found")
            return
        }
        Log.i(TAG, "Control characteristic props=${control.properties}")
        runCatching { gatt.setCharacteristicNotification(control, true) }
        val descriptor = control.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        if (descriptor == null) {
            writeSensorRequest(gatt)
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        val ok = runCatching { gatt.writeDescriptor(descriptor) }.getOrDefault(false)
        Log.i(TAG, "enableControlNotifications -> $ok")
        if (!ok) {
            sleep(150)
            val retryOk = runCatching { gatt.writeDescriptor(descriptor) }.getOrDefault(false)
            Log.i(TAG, "enableControlNotifications retry -> $retryOk")
            if (!retryOk) {
                writeSensorRequest(gatt)
            }
        }
    }

    private fun sendTimeRequest(gatt: BluetoothGatt) {
        if (timeRequestSent) return
        timeRequestPendingAfterControlNotify = true
        enableControlNotifications(gatt)
    }

    private fun writeTimeRequest(gatt: BluetoothGatt) {
        val control = controlCharacteristic ?: return
        val packet = buildSimpleAuthTx(0x24)
        timeRequestSent = true
        Log.i(TAG, "Sending TimeTx ${packet.toHex()}")
        val ok = writeCharacteristicBytes(gatt, control, packet)
        Log.i(TAG, "writeTimeTx -> $ok")
        if (!ok) timeRequestSent = false
    }

    private fun handleTransmitterTime(gatt: BluetoothGatt, value: ByteArray) {
        val session = runCatching { SensorProtocol.session(value) }.getOrElse {
            Log.w(TAG, "Invalid transmitter time response", it)
            return
        }
        val currentTime = session.transmitterTime
        val sessionStartTime = session.startTime
        SensorSessionStore.recordTime(this, session, System.currentTimeMillis())
        requestComplicationRefresh("sensor session time", force = true)
        Log.i(TAG, "TransmitterTime current=$currentTime sessionStart=$sessionStartTime")
        timeRequestSent = false
        if (sessionStartTime != -1 && currentTime != sessionStartTime) {
            Log.i(TAG, "Sensor session already active on transmitter; requesting glucose data")
            DexcomConfigStore.saveScanDebug(
                this,
                "Dexcom session",
                gatt.device?.address ?: "",
                null,
                "session already active",
            )
            protocolWorker.execute {
                sleep(150)
                writeSensorRequest(gatt)
            }
            return
        }
        // Reconnecting is read-only: a missing session is never authorization to start one.
        updateNotif("Сессия сенсора завершена. Запустите новый сенсор на телефоне.")
        protocolWorker.execute {
            if (this.gatt === gatt) writeSensorRequest(gatt)
        }
    }

    private fun handleSessionStart(gatt: BluetoothGatt, value: ByteArray) {
        if (value.size < 17) {
            Log.w(TAG, "SessionStartRx packet too short: ${value.toHex()}")
            return
        }
        val status = value[1].toInt() and 0xFF
        val info = value[2].toInt() and 0xFF
        val requestedStart = ByteBuffer.wrap(value, 3, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val sessionStart = ByteBuffer.wrap(value, 7, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val transmitterTime = ByteBuffer.wrap(value, 11, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val ok = status == 0 && (info == 0x01 || info == 0x05) && sessionStart != -1
        Log.i(
            TAG,
            "SessionStartRx ok=$ok status=$status info=$info requested=$requestedStart session=$sessionStart txTime=$transmitterTime hex=${value.toHex()}",
        )
        DexcomConfigStore.saveScanDebug(
            this,
            "Dexcom session",
            gatt.device?.address ?: "",
            null,
            if (ok) "session started" else "session start failed: status=$status info=$info",
        )
        if (ok) {
            updateNotif("Сенсор запущен, жду данные Dexcom")
            protocolWorker.execute {
                sleep(150)
                enableControlNotifications(gatt)
            }
        } else {
            sessionStartSent = false
        }
    }

    private fun writeSensorRequest(gatt: BluetoothGatt) {
        val control = controlCharacteristic ?: return
        val packet = buildSensorTx()
        sensorRequestSent = true
        lastSensorRequestAtMillis = System.currentTimeMillis()
        Log.i(TAG, "Sending EGlucoseTx ${packet.toHex()}")
        val ok = writeCharacteristicBytes(gatt, control, packet)
        Log.i(TAG, "writeEGlucoseTx -> $ok")
        if (!ok) {
            sensorRequestSent = false
            recoverAfterGattWriteFailure(gatt, "sensor request write failed")
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristicBytes(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        packet: ByteArray,
    ): Boolean = synchronized(gattLock) {
        if (this.gatt !== gatt) {
            ConnectionJournal.record(this, "stale_gatt_write", "opcode" to packet.firstOrNull()?.toInt()?.and(255))
            return false
        }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val status = gatt.writeCharacteristic(
                    characteristic,
                    packet,
                    characteristic.writeType,
                )
                val success = status == BluetoothStatusCodes.SUCCESS
                if (!success) {
                    Log.w(
                        TAG,
                        "writeCharacteristic status=$status uuid=${characteristic.uuid} " +
                            "writeType=${characteristic.writeType} packet=${packet.toHex()}",
                    )
                }
                success
            } else {
                characteristic.value = packet
                gatt.writeCharacteristic(characteristic)
            }
        }.onFailure {
            Log.e(TAG, "writeCharacteristicBytes failed: ${it.javaClass.simpleName}: ${it.message}", it)
        }.getOrDefault(false)
        if (!ok) {
            recoverAfterGattWriteFailure(gatt, "gatt write failed")
        }
        return ok
    }

    private fun recoverAfterGattWriteFailure(failedGatt: BluetoothGatt, reason: String) {
        if (gatt !== failedGatt) return
        Log.w(TAG, "Recovering Dexcom BLE after $reason")
        sensorRequestSent = false
        timeRequestSent = false
        timeRequestPendingAfterControlNotify = false
        sessionStartSent = false
        keepAliveInFlight = false
        enableBroadRecoveryScan(reason)
        DexcomConfigStore.saveScanDebug(this, "", config.knownMac, null, reason)
        updateNotif("BLE сбой, переподключаю Dexcom")
        closeGatt(reason)
        lastDirectRecoveryAttemptAtMillis = System.currentTimeMillis()
        consecutiveSilentBroadScanRestarts = 0
        if (wantConnected && btAdapter?.isEnabled == true) {
            runCatching { stopScan() }
            sleep(SCANNER_STOP_START_SETTLE_MS)
            runCatching { startScanInternal() }
        }
    }

    private fun calculateDexcomHash(challenge: ByteArray): ByteArray? {
        if (challenge.size != 8) return null
        val txid = config.transmitterId
        if (txid.length != 6) return null
        val key = ("00${txid}00${txid}").toByteArray(Charsets.UTF_8)
        val plainText = ByteArray(16)
        System.arraycopy(challenge, 0, plainText, 0, 8)
        System.arraycopy(challenge, 0, plainText, 8, 8)
        return try {
            val secretKeySpec = SecretKeySpec(key, "AES")
            val aesCipher = Cipher.getInstance("AES/ECB/PKCS7Padding")
            aesCipher.init(Cipher.ENCRYPT_MODE, secretKeySpec)
            aesCipher.doFinal(plainText).copyOfRange(0, 8)
        } catch (
            e: NoSuchAlgorithmException,
        ) {
            Log.e(TAG, "calculateDexcomHash NoSuchAlgorithmException: ${e.message}")
            null
        } catch (
            e: NoSuchPaddingException,
        ) {
            Log.e(TAG, "calculateDexcomHash NoSuchPaddingException: ${e.message}")
            null
        } catch (
            e: IllegalBlockSizeException,
        ) {
            Log.e(TAG, "calculateDexcomHash IllegalBlockSizeException: ${e.message}")
            null
        } catch (
            e: BadPaddingException,
        ) {
            Log.e(TAG, "calculateDexcomHash BadPaddingException: ${e.message}")
            null
        } catch (
            e: InvalidKeyException,
        ) {
            Log.e(TAG, "calculateDexcomHash InvalidKeyException: ${e.message}")
            null
        }
    }

    private fun buildSensorTx(): ByteArray {
        val opcode: Byte = 0x4E
        val crc = fastCrc16(byteArrayOf(opcode))
        return byteArrayOf(opcode, crc[0], crc[1])
    }

    private fun buildSimpleAuthTx(opcode: Int): ByteArray {
        val op = opcode.toByte()
        val crc = fastCrc16(byteArrayOf(op))
        return byteArrayOf(op, crc[0], crc[1])
    }

    private fun buildSessionStartTx(startTimeMs: Long, currentDexTime: Int, code: String): ByteArray {
        val params = g6CalibrationParameters(code)
            ?: throw IllegalArgumentException("Invalid G6 code for session start: $code")
        val usingG6 = true
        val payloadLength = if (params.second == 0) 13 else 17
        val data = ByteBuffer.allocate(payloadLength).order(ByteOrder.LITTLE_ENDIAN)
        data.put(0x26)
        data.putInt(currentDexTime)
        data.putInt((startTimeMs / 1000L).toInt())
        if (params.second != 0) {
            data.putShort(params.first.toShort())
            data.putShort(params.second.toShort())
        }
        if (usingG6) {
            data.putShort(0x0000.toShort())
        }
        val bytes = data.array()
        val crc = fastCrc16(bytes.copyOf(bytes.size - 2))
        bytes[bytes.size - 2] = crc[0]
        bytes[bytes.size - 1] = crc[1]
        return bytes
    }

    private fun g6CalibrationParameters(code: String): Pair<Int, Int>? {
        return when (code.trim().uppercase()) {
            "0000" -> 1 to 0
            "5915", "9759" -> 3100 to 3600
            "5917", "9357" -> 3000 to 3500
            "5931", "9137" -> 2900 to 3400
            "5937", "7197" -> 2800 to 3300
            "5951", "9517" -> 3100 to 3500
            "5955", "9179" -> 3000 to 3400
            "7171", "7539" -> 2700 to 3300
            "9117", "7135" -> 2700 to 3200
            "9159", "5397" -> 2600 to 3200
            "9311", "5391" -> 2600 to 3100
            "9371", "5375" -> 2500 to 3100
            "9515", "5795" -> 2500 to 3000
            "9551", "5317" -> 2400 to 3000
            "9577", "5177" -> 2400 to 2900
            "9713", "5171" -> 2300 to 2900
            else -> null
        }
    }

    private fun fastCrc16(bytes: ByteArray): ByteArray {
        val table = intArrayOf(
            0x0000, 0x1021, 0x2042, 0x3063, 0x4084, 0x50a5, 0x60c6, 0x70e7,
            0x8108, 0x9129, 0xa14a, 0xb16b, 0xc18c, 0xd1ad, 0xe1ce, 0xf1ef,
            0x1231, 0x0210, 0x3273, 0x2252, 0x52b5, 0x4294, 0x72f7, 0x62d6,
            0x9339, 0x8318, 0xb37b, 0xa35a, 0xd3bd, 0xc39c, 0xf3ff, 0xe3de,
            0x2462, 0x3443, 0x0420, 0x1401, 0x64e6, 0x74c7, 0x44a4, 0x5485,
            0xa56a, 0xb54b, 0x8528, 0x9509, 0xe5ee, 0xf5cf, 0xc5ac, 0xd58d,
            0x3653, 0x2672, 0x1611, 0x0630, 0x76d7, 0x66f6, 0x5695, 0x46b4,
            0xb75b, 0xa77a, 0x9719, 0x8738, 0xf7df, 0xe7fe, 0xd79d, 0xc7bc,
            0x48c4, 0x58e5, 0x6886, 0x78a7, 0x0840, 0x1861, 0x2802, 0x3823,
            0xc9cc, 0xd9ed, 0xe98e, 0xf9af, 0x8948, 0x9969, 0xa90a, 0xb92b,
            0x5af5, 0x4ad4, 0x7ab7, 0x6a96, 0x1a71, 0x0a50, 0x3a33, 0x2a12,
            0xdbfd, 0xcbdc, 0xfbbf, 0xeb9e, 0x9b79, 0x8b58, 0xbb3b, 0xab1a,
            0x6ca6, 0x7c87, 0x4ce4, 0x5cc5, 0x2c22, 0x3c03, 0x0c60, 0x1c41,
            0xedae, 0xfd8f, 0xcdec, 0xddcd, 0xad2a, 0xbd0b, 0x8d68, 0x9d49,
            0x7e97, 0x6eb6, 0x5ed5, 0x4ef4, 0x3e13, 0x2e32, 0x1e51, 0x0e70,
            0xff9f, 0xefbe, 0xdfdd, 0xcffc, 0xbf1b, 0xaf3a, 0x9f59, 0x8f78,
            0x9188, 0x81a9, 0xb1ca, 0xa1eb, 0xd10c, 0xc12d, 0xf14e, 0xe16f,
            0x1080, 0x00a1, 0x30c2, 0x20e3, 0x5004, 0x4025, 0x7046, 0x6067,
            0x83b9, 0x9398, 0xa3fb, 0xb3da, 0xc33d, 0xd31c, 0xe37f, 0xf35e,
            0x02b1, 0x1290, 0x22f3, 0x32d2, 0x4235, 0x5214, 0x6277, 0x7256,
            0xb5ea, 0xa5cb, 0x95a8, 0x8589, 0xf56e, 0xe54f, 0xd52c, 0xc50d,
            0x34e2, 0x24c3, 0x14a0, 0x0481, 0x7466, 0x6447, 0x5424, 0x4405,
            0xa7db, 0xb7fa, 0x8799, 0x97b8, 0xe75f, 0xf77e, 0xc71d, 0xd73c,
            0x26d3, 0x36f2, 0x0691, 0x16b0, 0x6657, 0x7676, 0x4615, 0x5634,
            0xd94c, 0xc96d, 0xf90e, 0xe92f, 0x99c8, 0x89e9, 0xb98a, 0xa9ab,
            0x5844, 0x4865, 0x7806, 0x6827, 0x18c0, 0x08e1, 0x3882, 0x28a3,
            0xcb7d, 0xdb5c, 0xeb3f, 0xfb1e, 0x8bf9, 0x9bd8, 0xabbb, 0xbb9a,
            0x4a75, 0x5a54, 0x6a37, 0x7a16, 0x0af1, 0x1ad0, 0x2ab3, 0x3a92,
            0xfd2e, 0xed0f, 0xdd6c, 0xcd4d, 0xbdaa, 0xad8b, 0x9de8, 0x8dc9,
            0x7c26, 0x6c07, 0x5c64, 0x4c45, 0x3ca2, 0x2c83, 0x1ce0, 0x0cc1,
            0xef1f, 0xff3e, 0xcf5d, 0xdf7c, 0xaf9b, 0xbfba, 0x8fd9, 0x9ff8,
            0x6e17, 0x7e36, 0x4e55, 0x5e74, 0x2e93, 0x3eb2, 0x0ed1, 0x1ef0,
            0x8616,
        )
        var crc = 0
        for (i in bytes.indices) {
            crc = (crc shl 8) xor table[((crc ushr 8) xor bytes[i].toInt()) and 0xff]
        }
        return byteArrayOf((crc and 0xff).toByte(), ((crc ushr 8) and 0xff).toByte())
    }

    private fun ByteArray?.toHex(): String = this?.joinToString(separator = "") { "%02X".format(it) } ?: ""
}
