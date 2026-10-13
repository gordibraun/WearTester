package com.example.weartester

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * One connection to a Dexcom G7, from service discovery to the glucose packet.
 *
 * The sensor speaks four characteristics of its CGM service: control (glucose), authentication,
 * backfill and a fourth, wide one for the pairing material. A connection goes:
 *
 *  1. notifications on the pairing characteristic, indications on authentication;
 *  2. with no key for this sensor yet: three EC-JPAKE rounds over the pairing characteristic
 *     ("0A nn" on authentication asks the sensor for its round), which leave both sides holding a
 *     16-byte AES key derived from the 4-digit pairing code;
 *  3. the G6-style challenge: "02 token 02", the sensor's "03 tokenHash challenge" (its hash of our
 *     token proves it holds the same key), our "04 hash(challenge)", its "05 auth bond";
 *  4. once per sensor, when it does not yet trust this display: the display certificate chain, a
 *     signed 16-byte challenge, "0D 00 02", then "06 19" and a Bluetooth bond;
 *  5. notifications on control and a single-byte 0x4E; the 19-byte glucose packet comes back.
 *
 * Everything the sensor expects was established by Juggluco (GPL-3.0, Jaap Korthals Altes); this is
 * a new implementation of that exchange. All callbacks arrive on the service's GATT thread under
 * its lock; the blocking work (chunked writes) is handed to [worker].
 */
@SuppressLint("MissingPermission")
internal class G7Session(
    private val gatt: BluetoothGatt,
    private val pairingCode: String,
    private val storedKey: ByteArray?,
    private val worker: Executor,
    private val host: Host,
    private val random: SecureRandom = SecureRandom(),
) {
    interface Host {
        fun journal(event: String, vararg values: Pair<String, Any?>)
        fun status(text: String)
        /** Both sides derived this key; keep it for the sensor so the next connection skips pairing. */
        fun onSharedKey(key: ByteArray)
        /** The sensor rejected our key: a fresh one means the pairing code is wrong (or another sensor answered). */
        fun onKeyRejected(afterFreshPairing: Boolean)
        /** The sensor has accepted us; it is about to be asked for glucose. */
        fun onAuthenticated(sensorName: String?)
        /** A 0x4E control packet. */
        fun onGlucose(packet: ByteArray)
        /** The sensor wants a Bluetooth bond; create it and get the user to confirm. */
        fun onBondRequired(device: BluetoothDevice)
        /** The handshake cannot continue; the host disconnects. */
        fun onFailure(reason: String)
    }

    enum class Phase { START, ROUND1, ROUND2, ROUND3, REQUEST_AUTH, CHALLENGE_REPLY, CERT0, CERT1, KEY_CHALLENGE, KEY_CHALLENGE_OUT, GET_DATA, FAILED }

    @Volatile var phase: Phase = Phase.START
        private set

    /** True from the first glucose request on: the connection has reached its purpose. */
    @Volatile var dataRequested: Boolean = false
        private set

    /** True while the sensor's certificate exchange or bond is in progress: it needs the user and takes time. */
    val pairingInProgress: Boolean
        get() = phase in setOf(Phase.ROUND1, Phase.ROUND2, Phase.ROUND3, Phase.CERT0, Phase.CERT1, Phase.KEY_CHALLENGE, Phase.KEY_CHALLENGE_OUT) || bondRequested

    private val service = gatt.getService(CGM_SERVICE)
    private val control = service?.getCharacteristic(CONTROL)
    private val auth = service?.getCharacteristic(AUTHENTICATION)
    private val backfill = service?.getCharacteristic(BACKFILL)
    private val pairingChar = service?.getCharacteristic(PAIRING)

    private var pairing: G7Pairing? = null
    private var key: ByteArray? = storedKey
    private var freshKey = false
    private var token = ByteArray(8)
    private var inbound = ByteArray(0)
    private var expectedInbound = 0
    @Volatile private var bondRequested = false
    @Volatile private var writeLatch: CountDownLatch? = null
    @Volatile private var closed = false

    fun start() {
        if (control == null || auth == null || pairingChar == null) {
            fail("G7 characteristics missing: control=${control != null} auth=${auth != null} pairing=${pairingChar != null}")
            return
        }
        host.journal("g7_session_start", "stored_key" to (storedKey != null), "bonded" to locallyBonded())
        host.status("G7: обмен ключами…")
        if (!enable(pairingChar, indication = false)) fail("cannot enable pairing notifications")
    }

    fun close() {
        closed = true
        writeLatch?.countDown()
    }

    fun onDescriptorWrite(descriptor: BluetoothGattDescriptor, status: Int) {
        if (closed) return
        val ch = descriptor.characteristic
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("descriptor write failed status=$status uuid=${ch.uuid}")
            return
        }
        when (ch.uuid) {
            PAIRING -> if (!enable(auth!!, indication = auth.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)) fail("cannot enable auth indications")
            AUTHENTICATION -> {
                if (phase != Phase.START) return
                if (locallyBonded() && key != null) {
                    requestAuth()
                } else {
                    phase = Phase.ROUND1
                    pairing = G7Pairing(pairingCode, random)
                    expect(JPake.Proof.SIZE)
                    host.journal("g7_pairing_round", "round" to 1)
                    writeAuth(byteArrayOf(0x0A, 0x00))
                }
            }
            CONTROL -> requestGlucose()
            else -> Unit
        }
    }

    fun onCharacteristicWrite(ch: BluetoothGattCharacteristic, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "write failed status=$status uuid=${ch.uuid}")
            host.journal("g7_write_failed", "status" to status, "uuid" to ch.uuid.toString())
        }
        writeLatch?.countDown()
    }

    fun onCharacteristicChanged(ch: BluetoothGattCharacteristic, value: ByteArray) {
        if (closed) return
        when (ch.uuid) {
            CONTROL -> onControl(value)
            AUTHENTICATION -> onAuth(value)
            PAIRING -> onPairingBytes(value)
            BACKFILL -> host.journal("g7_backfill", "bytes" to value.size)
        }
    }

    fun onBondStateChanged(state: Int) {
        if (closed) return
        host.journal("g7_bond_state", "state" to state, "phase" to phase.name)
        when (state) {
            BluetoothDevice.BOND_BONDING -> host.status("G7: подтвердите сопряжение на часах")
            BluetoothDevice.BOND_BONDED -> {
                bondRequested = false
                if (phase != Phase.GET_DATA || !dataRequested) dataPhase()
            }
            BluetoothDevice.BOND_NONE -> if (bondRequested) {
                bondRequested = false
                fail("bond refused or cancelled")
            }
        }
    }

    /** Ask again on a connection kept open past the first packet. */
    fun requestGlucose() {
        val control = control ?: return
        dataRequested = true
        host.status("G7: запрашиваю глюкозу")
        worker.execute { write(control, byteArrayOf(0x4E), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }
    }

    // ---- authentication characteristic -------------------------------------------------------

    private fun onAuth(value: ByteArray) {
        if (value.isEmpty()) return
        host.journal("g7_auth_rx", "phase" to phase.name, "hex" to value.toHex())
        if (!locallyBonded() && !bondRequested && BOND_TRIGGERS.any { it.contentEquals(value) }) {
            bondRequested = true
            host.status("G7: сенсор просит сопряжение")
            host.onBondRequired(gatt.device)
        }
        when (phase) {
            Phase.REQUEST_AUTH -> onChallenge(value)
            Phase.CHALLENGE_REPLY -> onAuthStatus(value)
            Phase.CERT0, Phase.CERT1 -> onCertificateSize(value)
            Phase.KEY_CHALLENGE -> onKeyChallenge(value)
            Phase.KEY_CHALLENGE_OUT -> {
                phase = Phase.GET_DATA
                host.journal("g7_identity_accepted")
                writeAuth(byteArrayOf(0x06, 0x19))
            }
            Phase.GET_DATA -> if (value.contentEquals(byteArrayOf(0x06, 0x01)) || locallyBonded()) {
                if (!dataRequested) dataPhase()
            }
            else -> Log.i(TAG, "auth bytes in $phase ignored")
        }
    }

    private fun requestAuth() {
        val key = key ?: run { fail("no key for auth request"); return }
        phase = Phase.REQUEST_AUTH
        random.nextBytes(token)
        host.journal("g7_auth_request", "fresh_key" to freshKey)
        host.status("G7: проверка ключа")
        check(key.size == 16)
        writeAuth(byteArrayOf(0x02) + token + byteArrayOf(0x02))
    }

    private fun onChallenge(value: ByteArray) {
        if (value.size < 17 || value[0].toInt() and 0xFF != 0x03) {
            if (value[0].toInt() and 0xFF == 0x05) onAuthStatus(value) else Log.i(TAG, "expected challenge, got ${value.toHex()}")
            return
        }
        val key = key ?: return
        val tokenHash = value.copyOfRange(1, 9)
        val challenge = value.copyOfRange(9, 17)
        val ours = G7Crypto.hash8(key, token)
        if (!ours.contentEquals(tokenHash)) {
            host.journal("g7_key_mismatch", "fresh_key" to freshKey)
            this.key = null
            host.onKeyRejected(freshKey)
            fail(if (freshKey) "pairing code rejected by the sensor" else "stored key no longer valid")
            return
        }
        phase = Phase.CHALLENGE_REPLY
        writeAuth(byteArrayOf(0x04) + G7Crypto.hash8(key, challenge))
    }

    private fun onAuthStatus(value: ByteArray) {
        if (value.size < 3 || value[0].toInt() and 0xFF != 0x05) {
            Log.i(TAG, "expected auth status, got ${value.toHex()}")
            return
        }
        val authenticated = value[1].toInt() and 0xFF
        val bonded = value[2].toInt() and 0xFF
        host.journal("g7_auth_status", "auth" to authenticated, "bond" to bonded, "locally_bonded" to locallyBonded(), "fresh_key" to freshKey)
        when {
            bonded == 3 -> {
                key = null
                host.onKeyRejected(freshKey)
                fail("sensor reports bond state 3")
            }
            !freshKey && authenticated != 1 -> {
                key = null
                host.onKeyRejected(false)
                fail("sensor does not accept the stored key (auth=$authenticated)")
            }
            (authenticated == 1 && bonded == 1) || (locallyBonded() && bonded == 2) -> dataPhase()
            else -> askCertificate(0)
        }
    }

    // ---- display identity -------------------------------------------------------------------

    private fun askCertificate(index: Int) {
        phase = if (index == 0) Phase.CERT0 else Phase.CERT1
        val ours = G7Certificates.chain[index]
        host.status("G7: обмен сертификатами ${index + 1}/2")
        host.journal("g7_certificate_request", "index" to index, "our_size" to ours.size)
        expect(0)
        writeAuth(ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).put(0x0B).put(index.toByte()).putInt(ours.size).array())
    }

    private fun onCertificateSize(value: ByteArray) {
        if (value.size < 5 || value[0].toInt() and 0xFF != 0x0B) {
            Log.i(TAG, "expected certificate size, got ${value.toHex()}")
            return
        }
        val size = ByteBuffer.wrap(value, 3, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        host.journal("g7_certificate_size", "index" to (value[2].toInt() and 0xFF), "size" to size)
        if (size <= 0 || size > 4096) {
            fail("implausible sensor certificate size $size")
            return
        }
        expect(size)
        if (inbound.size >= size) onInboundComplete()
    }

    private fun sendKeyChallenge() {
        phase = Phase.KEY_CHALLENGE
        expect(64)
        val challenge = ByteArray(16).also { random.nextBytes(it) }
        host.status("G7: подпись")
        host.journal("g7_key_challenge_sent")
        writeAuth(byteArrayOf(0x0C) + challenge)
    }

    private fun onKeyChallenge(value: ByteArray) {
        if (value.size < 18 || value[0].toInt() and 0xFF != 0x0C) {
            Log.i(TAG, "expected key challenge, got ${value.toHex()}")
            return
        }
        val signature = G7Crypto.signRaw(G7Certificates.displayPrivateKey, value.copyOfRange(2, 18), random)
        host.journal("g7_key_challenge_rx", "signature_bytes" to signature.size)
        worker.execute {
            if (!writeChunks(signature)) return@execute
            phase = Phase.KEY_CHALLENGE_OUT
            writeAuth(byteArrayOf(0x0D, 0x00, 0x02))
        }
    }

    // ---- pairing characteristic -------------------------------------------------------------

    private fun expect(size: Int) {
        inbound = ByteArray(0)
        expectedInbound = size
    }

    private fun onPairingBytes(value: ByteArray) {
        inbound += value
        host.journal("g7_pairing_rx", "phase" to phase.name, "received" to inbound.size, "expected" to expectedInbound)
        if (expectedInbound > 0 && inbound.size >= expectedInbound) onInboundComplete()
    }

    private fun onInboundComplete() {
        val bytes = inbound.copyOf(expectedInbound)
        expect(0)
        val pairing = pairing
        when (phase) {
            Phase.ROUND1 -> {
                if (pairing == null || !pairing.acceptSensorRound1(bytes)) { fail("sensor round 1 proof invalid"); return }
                worker.execute {
                    if (!writeChunks(pairing.round1Message())) return@execute
                    phase = Phase.ROUND2
                    expect(JPake.Proof.SIZE)
                    host.journal("g7_pairing_round", "round" to 2)
                    writeAuth(byteArrayOf(0x0A, 0x01))
                }
            }
            Phase.ROUND2 -> {
                if (pairing == null || !pairing.acceptSensorRound2(bytes)) { fail("sensor round 2 proof invalid"); return }
                worker.execute {
                    if (!writeChunks(pairing.round2Message())) return@execute
                    phase = Phase.ROUND3
                    expect(JPake.Proof.SIZE)
                    host.journal("g7_pairing_round", "round" to 3)
                    writeAuth(byteArrayOf(0x0A, 0x02))
                }
            }
            Phase.ROUND3 -> {
                val derived = pairing?.acceptSensorRound3(bytes)
                if (pairing == null || derived == null) { fail("sensor round 3 proof invalid"); return }
                key = derived
                freshKey = true
                host.onSharedKey(derived)
                host.journal("g7_pairing_key_derived")
                worker.execute {
                    if (!writeChunks(pairing.round3Message())) return@execute
                    requestAuth()
                }
            }
            Phase.CERT0, Phase.CERT1 -> {
                val index = if (phase == Phase.CERT0) 0 else 1
                host.journal("g7_sensor_certificate", "index" to index, "size" to bytes.size)
                worker.execute {
                    if (!writeChunks(G7Certificates.chain[index])) return@execute
                    if (index == 0) askCertificate(1) else sendKeyChallenge()
                }
            }
            Phase.KEY_CHALLENGE -> host.journal("g7_sensor_signature", "size" to bytes.size)
            else -> Log.i(TAG, "pairing bytes in $phase ignored")
        }
    }

    // ---- data ---------------------------------------------------------------------------------

    private fun dataPhase() {
        phase = Phase.GET_DATA
        host.onAuthenticated(gatt.device?.name)
        host.status("G7: авторизован, жду данные")
        val control = control ?: return
        if (!enable(control, indication = control.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0)) {
            fail("cannot enable control notifications")
        }
    }

    private fun onControl(value: ByteArray) {
        if (value.isEmpty()) return
        when (value[0].toInt() and 0xFF) {
            0x4E -> host.onGlucose(value)
            0x59 -> host.journal("g7_backfill_end")
            else -> host.journal("g7_control_rx", "hex" to value.toHex())
        }
    }

    // ---- GATT helpers -------------------------------------------------------------------------

    private fun locallyBonded(): Boolean = runCatching { gatt.device?.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)

    private fun enable(ch: BluetoothGattCharacteristic, indication: Boolean): Boolean {
        val descriptor = ch.getDescriptor(CCCD) ?: return false
        if (!runCatching { gatt.setCharacteristicNotification(ch, true) }.getOrDefault(false)) return false
        val value = if (indication) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.setValue(value) && @Suppress("DEPRECATION") gatt.writeDescriptor(descriptor)
        }
    }

    private fun writeAuth(bytes: ByteArray) {
        val auth = auth ?: return
        host.journal("g7_auth_tx", "hex" to bytes.toHex())
        worker.execute { write(auth, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }
    }

    /** The pairing characteristic takes 20 bytes at a time, each acknowledged before the next. */
    private fun writeChunks(bytes: ByteArray): Boolean {
        val ch = pairingChar ?: return false
        var offset = 0
        while (offset < bytes.size) {
            if (closed) return false
            val end = minOf(offset + CHUNK, bytes.size)
            if (!write(ch, bytes.copyOfRange(offset, end), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)) {
                fail("chunk write failed at $offset/${bytes.size}")
                return false
            }
            offset = end
        }
        host.journal("g7_pairing_tx", "bytes" to bytes.size, "phase" to phase.name)
        return true
    }

    /** Writes and waits for the stack's acknowledgement; must run on [worker]. */
    private fun write(ch: BluetoothGattCharacteristic, bytes: ByteArray, type: Int): Boolean {
        for (attempt in 0 until 8) {
            if (closed) return false
            val latch = CountDownLatch(1)
            writeLatch = latch
            val issued = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(ch, bytes, type) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    ch.writeType = type
                    @Suppress("DEPRECATION")
                    ch.setValue(bytes) && @Suppress("DEPRECATION") gatt.writeCharacteristic(ch)
                }
            }.getOrElse { Log.e(TAG, "write error", it); false }
            if (issued) {
                latch.await(WRITE_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                writeLatch = null
                return true
            }
            writeLatch = null
            Thread.sleep(25L * (attempt + 1))
        }
        return false
    }

    private fun fail(reason: String) {
        if (phase == Phase.FAILED) return
        phase = Phase.FAILED
        host.journal("g7_session_failed", "reason" to reason)
        host.onFailure(reason)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    companion object {
        private const val TAG = "G7Session"
        private const val CHUNK = 20
        private const val WRITE_ACK_TIMEOUT_MS = 700L
        val CGM_SERVICE: UUID = UUID.fromString("f8083532-849e-531c-c594-30f1f86a4ea5")
        val CONTROL: UUID = UUID.fromString("f8083534-849e-531c-c594-30f1f86a4ea5")
        val AUTHENTICATION: UUID = UUID.fromString("f8083535-849e-531c-c594-30f1f86a4ea5")
        val BACKFILL: UUID = UUID.fromString("f8083536-849e-531c-c594-30f1f86a4ea5")
        val PAIRING: UUID = UUID.fromString("f8083538-849e-531c-c594-30f1f86a4ea5")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Authentication bytes after which the sensor expects the display to bond. */
        private val BOND_TRIGGERS = listOf(byteArrayOf(0x06, 0x19), byteArrayOf(0xFF.toByte(), 0x06, 0x01), byteArrayOf(0x06, 0x00))

        /** G7 advertising names: "DXCMxx"; Dexcom ONE+ "DX02xx", and the two other known prefixes. */
        fun isG7Name(name: String?): Boolean {
            if (name == null || name.length < 4 || !name.startsWith("DX", ignoreCase = true)) return false
            return name.substring(2, 4).uppercase() in setOf("CM", "02", "01", "04")
        }
    }
}
