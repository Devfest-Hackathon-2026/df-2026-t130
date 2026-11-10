// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest

class Spake2Test {
    // --- edwards25519 ---------------------------------------------------------------------------

    /** RFC 8032 section 7.1: the public key is [clamped SHA-512 of the secret] times the base point. */
    @Test
    fun publicKeysOfRfc8032AreMultiplesOfTheBasePoint() {
        val vectors = listOf(
            "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60" to "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb" to "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
        )
        for ((secret, public) in vectors) {
            val hash = MessageDigest.getInstance("SHA-512").digest(hex(secret)).copyOf(32)
            hash[0] = (hash[0].toInt() and 248).toByte()
            hash[31] = ((hash[31].toInt() and 127) or 64).toByte()
            val scalar = Edwards25519.fromLittleEndian(hash)
            assertArrayEquals(public, hex(public), Edwards25519.encode(Edwards25519.multiply(Edwards25519.BASE, scalar)))
        }
    }

    @Test
    fun theBasePointHasTheGroupOrder() {
        val identity = Edwards25519.multiply(Edwards25519.BASE, Edwards25519.L)
        assertArrayEquals(Edwards25519.encode(Edwards25519.IDENTITY), Edwards25519.encode(identity))
    }

    @Test
    fun aPointSurvivesEncodingAndDecoding() {
        val point = Edwards25519.multiply(Edwards25519.BASE, BigInteger.valueOf(123456789))
        val encoded = Edwards25519.encode(point)
        val decoded = Edwards25519.decode(encoded)
        assertNotNull(decoded)
        assertArrayEquals(encoded, Edwards25519.encode(decoded!!))
    }

    @Test
    fun bytesOffTheCurveAreRefused() {
        // 2 is not a valid y of the curve (x^2 has no root).
        val notOnCurve = ByteArray(32).also { it[0] = 2 }
        assertEquals(null, Edwards25519.decode(notOnCurve))
    }

    @Test
    fun addingANegatedPointGivesTheIdentity() {
        val point = Edwards25519.multiply(Edwards25519.BASE, BigInteger.valueOf(987))
        val zero = Edwards25519.add(point, Edwards25519.negate(point))
        assertArrayEquals(Edwards25519.encode(Edwards25519.IDENTITY), Edwards25519.encode(zero))
    }

    // --- SPAKE2 -----------------------------------------------------------------------------------

    @Test
    fun theFixedPointsAreTheHashesOfTheirSeeds() {
        for (label in listOf("M", "N")) {
            val digest = MessageDigest.getInstance("SHA-256").digest("edwards25519 point generation seed ($label)".toByteArray())
            val point = if (label == "M") Spake2Points.M else Spake2Points.N
            assertArrayEquals(digest, Edwards25519.encode(point))
        }
    }

    @Test
    fun aliceAndBobAgreeOnTheKey() {
        val password = "123456".toByteArray()
        val alice = Spake2(true, CLIENT, SERVER)
        val bob = Spake2(false, SERVER, CLIENT)
        val aliceMessage = alice.generateMessage(password)
        val bobMessage = bob.generateMessage(password)
        val aliceKey = alice.processMessage(bobMessage)
        val bobKey = bob.processMessage(aliceMessage)
        assertNotNull(aliceKey)
        assertArrayEquals(aliceKey, bobKey)
        assertEquals(64, aliceKey!!.size)
    }

    @Test
    fun differentPasswordsGiveDifferentKeys() {
        val alice = Spake2(true, CLIENT, SERVER)
        val bob = Spake2(false, SERVER, CLIENT)
        val aliceMessage = alice.generateMessage("123456".toByteArray())
        val bobMessage = bob.generateMessage("654321".toByteArray())
        val aliceKey = alice.processMessage(bobMessage)
        val bobKey = bob.processMessage(aliceMessage)
        assertFalse(aliceKey.contentEquals(bobKey))
    }

    @Test
    fun aMessageIs32Bytes() {
        assertEquals(32, Spake2(true, CLIENT, SERVER).generateMessage("p".toByteArray()).size)
    }

    @Test
    fun aMessageOffTheCurveGivesNoKey() {
        val alice = Spake2(true, CLIENT, SERVER)
        alice.generateMessage("p".toByteArray())
        val bad = ByteArray(32).also { it[0] = 2 }
        assertEquals(null, alice.processMessage(bad))
    }

    @Test
    fun thePasswordScalarIsMadeAMultipleOfEightByAddingMultiplesOfTheOrder() {
        for (value in listOf(0L, 1L, 2L, 3L, 4L, 5L, 7L, 8L, 123456789L)) {
            val result = Spake2.multipleOfEight(BigInteger.valueOf(value))
            assertTrue("value $value", result.mod(BigInteger.valueOf(8)).signum() == 0)
            assertTrue("value $value", (result - BigInteger.valueOf(value)).mod(Edwards25519.L).signum() == 0)
        }
    }

    private companion object {
        val CLIENT = "adb pair client\u0000".toByteArray()
        val SERVER = "adb pair server\u0000".toByteArray()

        fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
