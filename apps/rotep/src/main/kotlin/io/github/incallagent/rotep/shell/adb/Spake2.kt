// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell.adb

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A point of edwards25519 in extended coordinates (x = X/Z, y = Y/Z, x*y = T/Z).
 *
 * Plain [BigInteger] arithmetic, not constant time. That is enough for what it is used for here: the
 * one pairing with the phone's own adbd, for a few seconds, inside a TLS connection on 127.0.0.1.
 */
internal class EdPoint(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

/** The group operations of edwards25519 (RFC 8032, section 5.1), in the encoding of that RFC. */
internal object Edwards25519 {
    private val TWO = BigInteger.valueOf(2)
    private val FOUR = BigInteger.valueOf(4)
    private val EIGHT = BigInteger.valueOf(8)

    /** The prime of the field. */
    val P: BigInteger = TWO.pow(255) - BigInteger.valueOf(19)

    /** The order of the prime-order subgroup. */
    val L: BigInteger = TWO.pow(252) + BigInteger("27742317777372353535851937790883648493")

    private val D: BigInteger = BigInteger.valueOf(-121665).mod(P) * BigInteger.valueOf(121666).modInverse(P) % P

    private val TWO_D: BigInteger = D * TWO % P

    /** A square root of -1 modulo P. */
    private val SQRT_M1: BigInteger = TWO.modPow((P - BigInteger.ONE) / FOUR, P)

    val IDENTITY = EdPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    /** The base point: y = 4/5, x even (RFC 8032). */
    val BASE: EdPoint = run {
        val encoded = ByteArray(32) { 0x66 }
        encoded[0] = 0x58
        requireNotNull(decode(encoded)) { "the base point does not decode" }
    }

    fun add(a: EdPoint, b: EdPoint): EdPoint {
        val pa = (a.y - a.x).mod(P) * (b.y - b.x).mod(P) % P
        val pb = (a.y + a.x) * (b.y + b.x) % P
        val pc = a.t * TWO_D % P * b.t % P
        val pd = a.z * TWO % P * b.z % P
        val e = (pb - pa).mod(P)
        val f = (pd - pc).mod(P)
        val g = (pd + pc) % P
        val h = (pb + pa) % P
        return EdPoint(e * f % P, g * h % P, f * g % P, e * h % P)
    }

    fun double(a: EdPoint): EdPoint {
        val pa = a.x * a.x % P
        val pb = a.y * a.y % P
        val pc = TWO * a.z * a.z % P
        val h = (pa + pb) % P
        val sum = (a.x + a.y) % P
        val e = (h - sum * sum % P).mod(P)
        val g = (pa - pb).mod(P)
        val f = (pc + g) % P
        return EdPoint(e * f % P, g * h % P, f * g % P, e * h % P)
    }

    fun negate(a: EdPoint): EdPoint = EdPoint((P - a.x) % P, a.y, a.z, (P - a.t) % P)

    /** [scalar] times [point]. The scalar is used as it is (it may be larger than L); it must not be negative. */
    fun multiply(point: EdPoint, scalar: BigInteger): EdPoint {
        require(scalar.signum() >= 0) { "negative scalar" }
        var result = IDENTITY
        for (i in scalar.bitLength() - 1 downTo 0) {
            result = double(result)
            if (scalar.testBit(i)) result = add(result, point)
        }
        return result
    }

    /** The 32 bytes of RFC 8032: y little-endian, with the sign of x in the top bit. */
    fun encode(a: EdPoint): ByteArray {
        val zInverse = a.z.modInverse(P)
        val x = a.x * zInverse % P
        val y = a.y * zInverse % P
        val out = littleEndian(y, 32)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /** The point for [bytes], or null when they are not on the curve. */
    fun decode(bytes: ByteArray): EdPoint? {
        if (bytes.size != 32) return null
        val sign = bytes[31].toInt() ushr 7 and 1
        val clean = bytes.copyOf()
        clean[31] = (clean[31].toInt() and 0x7f).toByte()
        val y = fromLittleEndian(clean).mod(P)
        val yy = y * y % P
        val u = (yy - BigInteger.ONE).mod(P)
        val v = (D * yy + BigInteger.ONE) % P
        // x = u * v^3 * (u * v^7)^((P-5)/8), then fixed up by sqrt(-1) if needed (RFC 8032, 5.1.3).
        val v3 = v * v % P * v % P
        val v7 = v3 * v3 % P * v % P
        var x = u * v3 % P * (u * v7 % P).modPow((P - BigInteger.valueOf(5)) / EIGHT, P) % P
        val check = v * x % P * x % P
        when {
            check == u -> Unit
            check == (P - u) % P -> x = x * SQRT_M1 % P
            else -> return null
        }
        if ((if (x.testBit(0)) 1 else 0) != sign) x = (P - x) % P
        return EdPoint(x, y, BigInteger.ONE, x * y % P)
    }

    fun fromLittleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    fun littleEndian(value: BigInteger, size: Int): ByteArray {
        val big = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        require(big.size <= size) { "value too long" }
        return ByteArray(size).also { out -> big.reversedArray().copyInto(out) }
    }
}

/**
 * SPAKE2 in the form of BoringSSL's SPAKE2_CTX, which adbd uses for wireless pairing
 * (pairing_auth.cpp in AOSP's adb). Both sides here and there must match byte for byte, including
 * the two details that are specific to that implementation:
 *  - the points M and N are the SHA-256 hashes of fixed strings, read as encoded points;
 *  - the "password scalar" is made a multiple of 8 by adding multiples of the group order, and is
 *    then used as it is, without reducing it again.
 *
 * One object for one exchange: [generateMessage] once, then [processMessage] once.
 */
internal class Spake2(
    private val isAlice: Boolean,
    private val myName: ByteArray,
    private val theirName: ByteArray,
    private val randomBytes: (Int) -> ByteArray = ::secureRandomBytes,
) {
    private var privateKey: BigInteger = BigInteger.ZERO
    private var passwordScalar: BigInteger = BigInteger.ZERO
    private var passwordHash: ByteArray = ByteArray(0)
    private var myMessage: ByteArray = ByteArray(0)

    /** Our 32-byte message, derived from [password]. */
    fun generateMessage(password: ByteArray): ByteArray {
        val random = randomBytes(64)
        privateKey = Edwards25519.fromLittleEndian(random).mod(Edwards25519.L).shiftLeft(3)

        val hash = sha512(password)
        passwordHash = hash
        passwordScalar = multipleOfEight(Edwards25519.fromLittleEndian(hash).mod(Edwards25519.L))

        val mask = Edwards25519.multiply(if (isAlice) Spake2Points.M else Spake2Points.N, passwordScalar)
        val ephemeral = Edwards25519.multiply(Edwards25519.BASE, privateKey)
        myMessage = Edwards25519.encode(Edwards25519.add(ephemeral, mask))
        return myMessage.copyOf()
    }

    /** The shared 64-byte key, or null when [theirMessage] is not a point of the curve. */
    fun processMessage(theirMessage: ByteArray): ByteArray? {
        check(myMessage.isNotEmpty()) { "generateMessage first" }
        val theirPoint = Edwards25519.decode(theirMessage) ?: return null
        val theirMask = Edwards25519.multiply(if (isAlice) Spake2Points.N else Spake2Points.M, passwordScalar)
        val unmasked = Edwards25519.add(theirPoint, Edwards25519.negate(theirMask))
        val shared = Edwards25519.encode(Edwards25519.multiply(unmasked, privateKey))

        val transcript = MessageDigest.getInstance("SHA-512")
        if (isAlice) {
            transcript.updateWithLength(myName)
            transcript.updateWithLength(theirName)
            transcript.updateWithLength(myMessage)
            transcript.updateWithLength(theirMessage)
        } else {
            transcript.updateWithLength(theirName)
            transcript.updateWithLength(myName)
            transcript.updateWithLength(theirMessage)
            transcript.updateWithLength(myMessage)
        }
        transcript.updateWithLength(shared)
        transcript.updateWithLength(passwordHash)
        return transcript.digest()
    }

    private fun MessageDigest.updateWithLength(data: ByteArray) {
        val length = ByteArray(8)
        var value = data.size.toLong()
        for (i in 0 until 8) {
            length[i] = (value and 0xff).toByte()
            value = value ushr 8
        }
        update(length)
        update(data)
    }

    companion object {
        /** Adds L, 2L and 4L as needed so that the low three bits of [scalar] are zero (BoringSSL's workaround). */
        internal fun multipleOfEight(scalar: BigInteger): BigInteger {
            var result = scalar
            var multiple = Edwards25519.L
            for (bit in 0 until 3) {
                if (result.testBit(bit)) result += multiple
                multiple = multiple.shiftLeft(1)
            }
            return result
        }

        private fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

        private fun sha512(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(bytes)
    }
}

/** The two fixed points of the exchange: the SHA-256 hash of a known string, read as an encoded point. */
internal object Spake2Points {
    val M: EdPoint = point("edwards25519 point generation seed (M)")
    val N: EdPoint = point("edwards25519 point generation seed (N)")

    private fun point(seed: String): EdPoint {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.US_ASCII))
        return requireNotNull(Edwards25519.decode(digest)) { "the seed does not give a point" }
    }
}
