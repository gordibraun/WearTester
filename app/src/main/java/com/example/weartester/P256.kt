package com.example.weartester

import java.math.BigInteger
import java.security.SecureRandom

/**
 * NIST P-256 (secp256r1) in affine coordinates on BigInteger. Plain double-and-add: the G7 pairing
 * runs it a dozen times per sensor, so speed and timing side channels do not matter; being
 * obviously correct does, and the unit tests pin it to captured sensor packets.
 */
internal object P256 {
    val p: BigInteger = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    val a: BigInteger = p - BigInteger.valueOf(3)
    val b: BigInteger = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
    val n: BigInteger = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    val g: Point = Point(
        BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16),
        BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16),
    )

    class Point internal constructor(val x: BigInteger, val y: BigInteger, val infinity: Boolean) {
        constructor(x: BigInteger, y: BigInteger) : this(x, y, false)

        val onCurve: Boolean
            get() = infinity || (y * y - (x * x * x + a * x + b)).mod(p) == BigInteger.ZERO

        operator fun plus(other: Point): Point {
            if (infinity) return other
            if (other.infinity) return this
            if (x == other.x) {
                return if (y == other.y && y.signum() != 0) double() else INFINITY
            }
            val slope = ((other.y - y) * (other.x - x).modInverse(p)).mod(p)
            return fromSlope(slope, other.x)
        }

        operator fun unaryMinus(): Point = if (infinity) this else Point(x, (p - y).mod(p))
        operator fun minus(other: Point): Point = this + (-other)

        fun double(): Point {
            if (infinity || y.signum() == 0) return INFINITY
            val slope = ((x * x * BigInteger.valueOf(3) + a) * (y * BigInteger.valueOf(2)).modInverse(p)).mod(p)
            return fromSlope(slope, x)
        }

        private fun fromSlope(slope: BigInteger, otherX: BigInteger): Point {
            val rx = (slope * slope - x - otherX).mod(p)
            val ry = (slope * (x - rx) - y).mod(p)
            return Point(rx, ry)
        }

        operator fun times(k: BigInteger): Point {
            var result = INFINITY
            var addend = this
            var scalar = k.mod(n)
            while (scalar.signum() > 0) {
                if (scalar.testBit(0)) result += addend
                addend = addend.double()
                scalar = scalar.shiftRight(1)
            }
            return result
        }

        /** x || y, 32 bytes each, big-endian; the form the G7 pairing packets use. */
        fun encode64(): ByteArray = x.toFixed(32) + y.toFixed(32)

        /** 04 || x || y: SEC 1 uncompressed, the form the zero-knowledge proofs hash. */
        fun encode65(): ByteArray = byteArrayOf(4) + encode64()

        override fun equals(other: Any?): Boolean =
            other is Point && (infinity && other.infinity || !infinity && !other.infinity && x == other.x && y == other.y)

        override fun hashCode(): Int = if (infinity) 0 else x.hashCode() * 31 + y.hashCode()
        override fun toString(): String = if (infinity) "Point(infinity)" else "Point(${x.toString(16)}, ${y.toString(16)})"
    }

    val INFINITY: Point = Point(BigInteger.ZERO, BigInteger.ZERO, true)

    fun decode64(bytes: ByteArray, offset: Int = 0): Point {
        require(bytes.size >= offset + 64) { "Point needs 64 bytes" }
        val point = Point(BigInteger(1, bytes.copyOfRange(offset, offset + 32)), BigInteger(1, bytes.copyOfRange(offset + 32, offset + 64)))
        require(point.onCurve) { "Point is not on P-256" }
        return point
    }

    /** A scalar in [1, n-1]. */
    fun randomScalar(random: SecureRandom = SecureRandom()): BigInteger {
        while (true) {
            val candidate = BigInteger(n.bitLength(), random)
            if (candidate.signum() > 0 && candidate < n) return candidate
        }
    }

    fun BigInteger.toFixed(size: Int): ByteArray {
        val raw = toByteArray()
        val out = ByteArray(size)
        val start = if (raw.size > size) raw.size - size else 0
        require(raw.size - start <= size && (raw.size <= size || raw.take(raw.size - size).all { it == 0.toByte() })) { "Number does not fit in $size bytes" }
        System.arraycopy(raw, start, out, size - (raw.size - start), raw.size - start)
        return out
    }
}
