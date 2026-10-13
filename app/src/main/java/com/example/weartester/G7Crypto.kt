package com.example.weartester

import com.example.weartester.P256.toFixed
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * The Dexcom G7 pairs with a display over EC-JPAKE on P-256 (mbedTLS flavour: lengths-prefixed
 * SHA-256 Schnorr proofs, secret from the 4-digit pairing code), then authenticates every
 * connection with the G6-style AES challenge, keyed by the derived secret instead of the
 * transmitter ID. The exchange as the sensor expects it was worked out in Juggluco
 * (Jaap Korthals Altes, GPL-3.0); this is a fresh implementation of that protocol.
 */
internal object JPake {
    /** The display's zero-knowledge-proof identity. */
    val CLIENT_ID: ByteArray = "client".toByteArray(Charsets.US_ASCII)

    /** The sensor's identity: the mbedTLS default, confirmed against captured sensor proofs. */
    val SENSOR_ID: ByteArray = "server".toByteArray(Charsets.US_ASCII)

    /** H(len || base || len || V || len || X || len || id) mod n, every point SEC 1 uncompressed. */
    fun challenge(base: P256.Point, v: P256.Point, x: P256.Point, party: ByteArray): BigInteger {
        val digest = MessageDigest.getInstance("SHA-256")
        for (point in listOf(base, v, x)) {
            digest.update(ByteBuffer.allocate(4).putInt(65).array())
            digest.update(point.encode65())
        }
        digest.update(ByteBuffer.allocate(4).putInt(party.size).array())
        digest.update(party)
        return BigInteger(1, digest.digest()).mod(P256.n)
    }

    /** X = base * secret with a Schnorr proof of knowing the secret: 160 bytes on the wire (X, V, r). */
    class Proof(val x: P256.Point, val v: P256.Point, val r: BigInteger) {
        fun encode(): ByteArray = x.encode64() + v.encode64() + r.toFixed(32)

        fun verify(base: P256.Point, party: ByteArray): Boolean {
            if (x.infinity || v.infinity || r.signum() < 0 || r >= P256.n) return false
            val c = challenge(base, v, x, party)
            return base * r + x * c == v
        }

        companion object {
            const val SIZE = 160

            fun decode(bytes: ByteArray): Proof {
                require(bytes.size == SIZE) { "Proof needs $SIZE bytes, got ${bytes.size}" }
                return Proof(P256.decode64(bytes, 0), P256.decode64(bytes, 64), BigInteger(1, bytes.copyOfRange(128, 160)))
            }

            fun make(base: P256.Point, secret: BigInteger, party: ByteArray, random: SecureRandom): Proof {
                val x = base * secret
                val nonce = P256.randomScalar(random)
                val v = base * nonce
                val c = challenge(base, v, x, party)
                val r = (nonce - c * secret).mod(P256.n)
                return Proof(x, v, r)
            }
        }
    }
}

/**
 * One pairing with one sensor. The sensor speaks first in every round; our answer to its round
 * goes back before the next round is requested.
 */
internal class G7Pairing(pairingCode: String, private val random: SecureRandom = SecureRandom()) {
    private val secret: BigInteger = BigInteger(1, pairingCode.toByteArray(Charsets.US_ASCII))
    private val x1: BigInteger = P256.randomScalar(random)
    private val x2: BigInteger = P256.randomScalar(random)
    val g1: P256.Point = P256.g * x1
    val g2: P256.Point = P256.g * x2
    private var g3: P256.Point? = null
    private var g4: P256.Point? = null

    init {
        require(pairingCode.length == 4 && pairingCode.all { it.isDigit() }) { "Pairing code is 4 digits" }
    }

    /** The sensor's first ephemeral key; false if its proof does not check out. */
    fun acceptSensorRound1(bytes: ByteArray): Boolean = accept(bytes) { g3 = it }

    fun acceptSensorRound2(bytes: ByteArray): Boolean = accept(bytes) { g4 = it }

    private fun accept(bytes: ByteArray, keep: (P256.Point) -> Unit): Boolean {
        val proof = runCatching { JPake.Proof.decode(bytes) }.getOrNull() ?: return false
        if (!proof.verify(P256.g, JPake.SENSOR_ID)) return false
        keep(proof.x)
        return true
    }

    fun round1Message(): ByteArray = JPake.Proof.make(P256.g, x1, JPake.CLIENT_ID, random).encode()

    fun round2Message(): ByteArray = JPake.Proof.make(P256.g, x2, JPake.CLIENT_ID, random).encode()

    /**
     * The sensor's key-confirmation point B = (G1 + G2 + G3) * x4 * s. Returns the 16-byte AES key
     * both sides now share, or null if the proof fails. A wrong pairing code does not fail here:
     * it shows up as a token-hash mismatch in the first authentication.
     */
    fun acceptSensorRound3(bytes: ByteArray): ByteArray? {
        val g3 = g3 ?: return null
        val g4 = g4 ?: return null
        val proof = runCatching { JPake.Proof.decode(bytes) }.getOrNull() ?: return null
        if (!proof.verify(g1 + g2 + g3, JPake.SENSOR_ID)) return null
        val x2s = (x2 * secret).mod(P256.n)
        val k = (proof.x - g4 * x2s) * x2
        if (k.infinity) return null
        return MessageDigest.getInstance("SHA-256").digest(k.x.toFixed(32)).copyOf(16)
    }

    /** Our key-confirmation point A = (G1 + G3 + G4) * x2 * s. */
    fun round3Message(): ByteArray {
        val base = g1 + checkNotNull(g3) { "Sensor round 1 not accepted" } + checkNotNull(g4) { "Sensor round 2 not accepted" }
        return JPake.Proof.make(base, (x2 * secret).mod(P256.n), JPake.CLIENT_ID, random).encode()
    }
}

internal object G7Crypto {
    /** AES-128-ECB of data || data, first 8 bytes: the Dexcom challenge hash. */
    fun hash8(key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 16 && data.size == 8) { "16-byte key and 8-byte block" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data + data).copyOf(8)
    }

    /** ECDSA over P-256 of SHA-256(message), as r || s with 32 bytes each. */
    fun signRaw(privateKey: BigInteger, message: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val z = BigInteger(1, MessageDigest.getInstance("SHA-256").digest(message))
        while (true) {
            val k = P256.randomScalar(random)
            val r = (P256.g * k).x.mod(P256.n)
            if (r.signum() == 0) continue
            val s = (k.modInverse(P256.n) * (z + r * privateKey)).mod(P256.n)
            if (s.signum() == 0) continue
            return r.toFixed(32) + s.toFixed(32)
        }
    }
}
