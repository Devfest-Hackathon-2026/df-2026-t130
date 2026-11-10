// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class AdbPairingTest {
    private val exported = ByteArray(64) { it.toByte() }
    private val peerInfo = ByteArray(AdbPairingWire.PEER_INFO_SIZE).also { it[1] = 'A'.code.toByte() }

    @Test
    fun thePairingEndsWhenBothKnowTheCode() {
        val server = FakePairingServer("123456", exported)
        AdbPairingExchange("123456".toByteArray(), exported, peerInfo).run(server.input, server.output)
        server.join()
        assertArrayEquals(peerInfo, server.clientPeerInfo)
    }

    @Test
    fun aWrongCodeFailsAndTheServerDoesNotGetThePeerInfo() {
        val server = FakePairingServer("123456", exported)
        try {
            AdbPairingExchange("000000".toByteArray(), exported, peerInfo).run(server.input, server.output)
            fail("expected a failure")
        } catch (expected: AdbPairingException) {
            assertTrue(expected.message!!.isNotEmpty())
        }
        server.join()
        assertEquals(null, server.clientPeerInfo)
    }

    @Test
    fun differentKeyingMaterialAlsoFails() {
        val server = FakePairingServer("123456", exported)
        val otherExported = exported.copyOf().also { it[0] = 99 }
        try {
            AdbPairingExchange("123456".toByteArray(), otherExported, peerInfo).run(server.input, server.output)
            fail("expected a failure")
        } catch (expected: AdbPairingException) {
            assertTrue(expected.message!!.isNotEmpty())
        }
        server.join()
    }

    /** RFC 5869, test case 3 (zero-length salt and info). */
    @Test
    fun hkdfMatchesTheRfc() {
        val out = hkdfSha256(ByteArray(22) { 0x0b }, ByteArray(0), 42)
        assertArrayEquals(
            hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
            out,
        )
    }

    /** One pairing server on another thread, as adbd behaves: reads our message, sends its own, reads PeerInfo, answers with PeerInfo. */
    private class FakePairingServer(private val code: String, private val exported: ByteArray) {
        private val fromClient = PipedOutputStream()
        val input = PipedInputStream(1 shl 16)
        val output = PipedOutputStream()
        private val serverRead = PipedInputStream(output, 1 shl 16)
        private val serverWrite = PipedOutputStream(input)

        @Volatile
        var clientPeerInfo: ByteArray? = null

        private val worker = thread {
            val read = DataInputStream(serverRead)
            val write = DataOutputStream(serverWrite)
            val spake = Spake2(false, AdbPairingWire.SERVER_NAME, AdbPairingWire.CLIENT_NAME)
            val mine = spake.generateMessage(code.toByteArray() + exported)
            val theirs = frame(read, AdbPairingWire.TYPE_SPAKE2)
            sendFrame(write, AdbPairingWire.TYPE_SPAKE2, mine)
            val keyMaterial = requireNotNull(spake.processMessage(theirs))
            val key = hkdfSha256(keyMaterial, AdbPairingWire.HKDF_INFO, AdbPairingWire.AES_KEY_SIZE)
            val sealed = frame(read, AdbPairingWire.TYPE_PEER_INFO)
            try {
                val opened = gcm(Cipher.DECRYPT_MODE, key, 0, sealed)
                assertEquals(AdbPairingWire.PEER_INFO_SIZE, opened.size)
                clientPeerInfo = opened
                sendFrame(write, AdbPairingWire.TYPE_PEER_INFO, gcm(Cipher.ENCRYPT_MODE, key, 0, ByteArray(AdbPairingWire.PEER_INFO_SIZE)))
            } catch (e: AEADBadTagException) {
                // A wrong code: adbd closes the connection.
                write.close()
            }
        }

        fun join() = worker.join(5_000)

        private fun frame(input: DataInputStream, expected: Byte): ByteArray {
            val version = input.readByte()
            val type = input.readByte()
            val size = input.readInt()
            assertEquals(AdbPairingWire.VERSION, version)
            assertEquals(expected, type)
            return ByteArray(size).also { input.readFully(it) }
        }

        private fun sendFrame(output: DataOutputStream, type: Byte, payload: ByteArray) {
            output.writeByte(AdbPairingWire.VERSION.toInt())
            output.writeByte(type.toInt())
            output.writeInt(payload.size)
            output.write(payload)
            output.flush()
        }

        private fun gcm(mode: Int, key: ByteArray, counter: Long, data: ByteArray): ByteArray {
            val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(counter).array()
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                doFinal(data)
            }
        }
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
