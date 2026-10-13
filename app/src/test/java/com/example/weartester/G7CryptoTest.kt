package com.example.weartester

import com.example.weartester.P256.toFixed
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class G7CryptoTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }

    // Two round-1 packets a real G7 sent (captured in Juggluco's test data).
    private val sensorRound1 = hex(
        "7CCC36E133643A357A1FFBA9A2A266246ED504697F4BA03E6B2F4E7B62B4BB88B47E39052E0C11F525F344D6B3B0924F3D33CC25775B8A55CDC6117A518CFF26" +
            "2CC2267B156F5BFC4BBBB0F93BF1F9CE09E17D621398C2B36E0ACD772E713A77B14E175AE07B943411918FCFED480066A47C06F4C25B01CB20B148C036819F4A" +
            "FED6F7AAF7DFCFBCF0965AE8E11900022E9298B6A546B14769CBFEE1C77B9170",
    )
    private val sensorRound2 = hex(
        "0B7D5BC678F018F2D0D86EF4B982813E7F501C0D142975EFDA08E539DBF8E04D0AB6FD611DBCFE1BAFD46A2FB806640C75872A2186B747A6AFB8BEA721E381BF" +
            "823E7BE9BE45757C219F6A9F0F5D2D9DE01CD05D3D72C911D0BAE22C48EF05717AD3FC962BC47915F983285C4B78174BE1D63151725DEC834C4CF0769B44F836" +
            "7DFFB961D2A174BF3F8148707E5DAE974ADFFB3F41C3E378A8C44D8666168EF3",
    )

    @Test fun curveArithmeticMatchesKnownMultiples() {
        assertTrue(P256.g.onCurve)
        assertEquals(P256.INFINITY, P256.g * P256.n)
        assertEquals(P256.g.double(), P256.g * BigInteger.valueOf(2))
        assertEquals(P256.g.double() + P256.g, P256.g * BigInteger.valueOf(3))
        assertEquals(
            "7CF27B188D034F7E8A52380304B51AC3C08969E277F21B35A60B48FC47669978",
            (P256.g * BigInteger.valueOf(2)).x.toString(16).uppercase().padStart(64, '0'),
        )
        assertEquals(P256.g, P256.decode64(P256.g.encode64()))
        assertEquals(P256.INFINITY, P256.g - P256.g)
    }

    @Test fun displayPrivateKeyBelongsToTheDisplayCertificate() {
        assertEquals(G7Certificates.displayPublicKey, P256.g * G7Certificates.displayPrivateKey)
        assertEquals(494, G7Certificates.chain[0].size)
        assertEquals(465, G7Certificates.chain[1].size)
    }

    @Test fun capturedSensorProofsVerifyAgainstTheGenerator() {
        for (packet in listOf(sensorRound1, sensorRound2)) {
            val proof = JPake.Proof.decode(packet)
            assertTrue(proof.verify(P256.g, JPake.SENSOR_ID))
            assertFalse(proof.verify(P256.g, JPake.CLIENT_ID))
            assertArrayEquals(packet, proof.encode())
        }
        val tampered = sensorRound1.copyOf().also { it[130] = (it[130] + 1).toByte() }
        assertFalse(JPake.Proof.decode(tampered).verify(P256.g, JPake.SENSOR_ID))
    }

    @Test fun ourProofsVerifyAndDoNotLeakTheSecret() {
        val random = SecureRandom()
        val secret = P256.randomScalar(random)
        val proof = JPake.Proof.make(P256.g, secret, JPake.CLIENT_ID, random)
        assertTrue(proof.verify(P256.g, JPake.CLIENT_ID))
        assertFalse(proof.verify(P256.g, JPake.SENSOR_ID))
        assertEquals(160, proof.encode().size)
    }

    /** Two displays pairing with each other derive the same key when, and only when, the codes agree. */
    @Test fun bothSidesDeriveTheSameKeyFromTheSameCode() {
        assertEquals(16, pairAs("1155", "1155")!!.first.size)
        val (ours, theirs) = pairAs("1155", "1155")!!
        assertArrayEquals(ours, theirs)
        val (mine, other) = pairAs("1155", "1156")!!
        assertFalse(mine.contentEquals(other))
    }

    private fun pairAs(codeA: String, codeB: String): Pair<ByteArray, ByteArray>? {
        val a = G7Pairing(codeA)
        val b = G7Pairing(codeB)
        // The side playing "sensor" must present proofs under the sensor identity: run B's
        // rounds through the raw proof API with B's own scalars reconstructed from its points.
        val sensor = SensorSide(codeB)
        assertTrue(a.acceptSensorRound1(sensor.round1()))
        assertTrue(a.acceptSensorRound2(sensor.round2()))
        assertTrue(sensor.acceptDisplayRound1(a.round1Message()))
        assertTrue(sensor.acceptDisplayRound2(a.round2Message()))
        val keyA = a.acceptSensorRound3(sensor.round3()) ?: return null
        val keyB = sensor.acceptDisplayRound3(a.round3Message()) ?: return null
        return keyA to keyB
    }

    /** The sensor's half of the exchange, mirrored for the test. */
    private class SensorSide(code: String) {
        private val random = SecureRandom()
        private val secret = BigInteger(1, code.toByteArray(Charsets.US_ASCII))
        private val x3 = P256.randomScalar(random)
        private val x4 = P256.randomScalar(random)
        private val g3 = P256.g * x3
        private val g4 = P256.g * x4
        private var g1: P256.Point? = null
        private var g2: P256.Point? = null

        fun round1() = JPake.Proof.make(P256.g, x3, JPake.SENSOR_ID, random).encode()
        fun round2() = JPake.Proof.make(P256.g, x4, JPake.SENSOR_ID, random).encode()
        fun acceptDisplayRound1(bytes: ByteArray) = JPake.Proof.decode(bytes).let { it.verify(P256.g, JPake.CLIENT_ID).also { ok -> if (ok) g1 = it.x } }
        fun acceptDisplayRound2(bytes: ByteArray) = JPake.Proof.decode(bytes).let { it.verify(P256.g, JPake.CLIENT_ID).also { ok -> if (ok) g2 = it.x } }
        fun round3() = JPake.Proof.make(g1!! + g2!! + g3, (x4 * secret).mod(P256.n), JPake.SENSOR_ID, random).encode()
        fun acceptDisplayRound3(bytes: ByteArray): ByteArray? {
            val proof = JPake.Proof.decode(bytes)
            if (!proof.verify(g1!! + g3 + g4, JPake.CLIENT_ID)) return null
            val k = (proof.x - g2!! * (x4 * secret).mod(P256.n)) * x4
            return java.security.MessageDigest.getInstance("SHA-256").digest(k.x.toFixed(32)).copyOf(16)
        }
    }

    @Test fun challengeHashIsTheG6ConstructionWithTheSharedKey() {
        val key = hex("6F8326744BEF03FAA520AD9C5CFF673F")
        val data = hex("2A404290C4B63B01")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        assertArrayEquals(cipher.doFinal(data + data).copyOf(8), G7Crypto.hash8(key, data))
        assertEquals(8, G7Crypto.hash8(key, data).size)
    }

    @Test fun signatureVerifiesWithTheJdkUnderTheDisplayCertificateKey() {
        val challenge = hex("0C000CEE691B765A497D225823D14F278DD3")
        val message = challenge.copyOfRange(2, 18)
        val raw = G7Crypto.signRaw(G7Certificates.displayPrivateKey, message)
        assertEquals(64, raw.size)
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        val public = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(G7Certificates.displayPublicKey.x, G7Certificates.displayPublicKey.y), params),
        )
        val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(public); update(message) }
        assertTrue(verifier.verify(der(raw)))
        val other = Signature.getInstance("SHA256withECDSA").apply { initVerify(public); update(message + 1) }
        assertFalse(other.verify(der(raw)))
    }

    private fun der(raw: ByteArray): ByteArray {
        fun integer(bytes: ByteArray): ByteArray {
            val value = BigInteger(1, bytes).toByteArray()
            return byteArrayOf(2, value.size.toByte()) + value
        }
        val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    @Test fun fixedWidthEncodingPadsAndRejectsOverflow() {
        assertEquals("0000000000000000000000000000000000000000000000000000000000000001", BigInteger.ONE.toFixed(32).hex())
        assertEquals(32, P256.n.toFixed(32).size)
        assertThrows(IllegalArgumentException::class.java) { (P256.p * P256.p).toFixed(32) }
    }
}
