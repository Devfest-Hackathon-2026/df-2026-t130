// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class AdbClientTest {
    @Test
    fun theMessageHeaderHasTheProtocolForm() {
        val message = AdbMessage(AdbConstants.OPEN, 1, 2, byteArrayOf(0x01, 0x7f, -1))
        val bytes = message.encode()
        assertEquals(24 + 3, bytes.size)
        assertEquals(AdbConstants.OPEN, intAt(bytes, 0))
        assertEquals(1, intAt(bytes, 4))
        assertEquals(2, intAt(bytes, 8))
        assertEquals(3, intAt(bytes, 12))
        assertEquals(0x01 + 0x7f + 0xff, intAt(bytes, 16))
        assertEquals(AdbConstants.OPEN.inv(), intAt(bytes, 20))
    }

    @Test
    fun aMessageIsReadBack() {
        val original = AdbMessage(AdbConstants.WRTE, 7, 8, "hello".toByteArray())
        val read = AdbMessage.read(ByteArrayInputStream(original.encode()))
        assertEquals(original.command, read.command)
        assertEquals(7, read.arg0)
        assertEquals(8, read.arg1)
        assertArrayEquals("hello".toByteArray(), read.payload)
    }

    @Test
    fun aBadMagicIsRefused() {
        val bytes = AdbMessage(AdbConstants.OKAY, 1, 2).encode()
        bytes[20] = (bytes[20].toInt() xor 1).toByte()
        assertThrows<AdbProtocolException> { AdbMessage.read(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun aTooLongPayloadIsRefused() {
        val bytes = AdbMessage(AdbConstants.WRTE, 1, 2, ByteArray(9)).encode()
        assertThrows<AdbProtocolException> { AdbMessage.read(ByteArrayInputStream(bytes), maxPayload = 8) }
    }

    @Test
    fun aStreamThatEndsEarlyThrows() {
        val bytes = AdbMessage(AdbConstants.WRTE, 1, 2, ByteArray(9)).encode().copyOf(10)
        assertThrows<EOFException> { AdbMessage.read(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun connectGoesThroughStlsAndAnswersTheStream() {
        val adbd = FakeAdbd { fake ->
            assertEquals(AdbConstants.CNXN, fake.next().command)
            fake.plainSend(AdbMessage(AdbConstants.STLS, AdbConstants.STLS_VERSION, 0))
            val stls = fake.next()
            assertEquals(AdbConstants.STLS, stls.command)
            assertEquals(AdbConstants.STLS_VERSION, stls.arg0)
            fake.switchToTls()
            fake.send(AdbMessage(AdbConstants.CNXN, AdbConstants.VERSION, 4096, "device::".toByteArray()))
            val open = fake.next()
            assertEquals(AdbConstants.OPEN, open.command)
            assertEquals("shell,v2,raw:echo hi\u0000", String(open.payload))
            val localId = open.arg0
            fake.send(AdbMessage(AdbConstants.OKAY, 99, localId))
            fake.send(AdbMessage(AdbConstants.WRTE, 99, localId, frame(1, "hi".toByteArray())))
            val okay = fake.next()
            assertEquals(AdbConstants.OKAY, okay.command)
            assertEquals(localId, okay.arg0)
            assertEquals(99, okay.arg1)
            fake.send(AdbMessage(AdbConstants.WRTE, 99, localId, frame(2, " there".toByteArray()) + frame(3, byteArrayOf(7))))
            assertEquals(AdbConstants.OKAY, fake.next().command)
            fake.send(AdbMessage(AdbConstants.CLSE, 99, localId))
            val close = fake.next()
            assertEquals(AdbConstants.CLSE, close.command)
            assertEquals(localId, close.arg0)
            assertEquals(99, close.arg1)
        }
        val client = AdbClient.connect(adbd.plainStreams) { adbd.tlsStreams }
        val result = client.shell("echo hi")
        assertEquals("hi there", result.output)
        assertEquals(7, result.exitCode)
        adbd.join()
    }

    @Test
    fun aShellFrameSplitOverTwoMessagesIsPutBackTogether() {
        val frames = AdbShellFrames()
        val whole = frame(1, "abcdef".toByteArray())
        frames.feed(whole.copyOf(3))
        assertEquals("", frames.output())
        frames.feed(whole.copyOfRange(3, whole.size) + frame(3, byteArrayOf(0)))
        assertEquals("abcdef", frames.output())
        assertEquals(0, frames.exitCode)
    }

    @Test
    fun tooMuchOutputIsRefused() {
        val frames = AdbShellFrames()
        val big = ByteArray(1 shl 20)
        frames.feed(frame(1, big))
        assertThrows<AdbProtocolException> { frames.feed(frame(1, ByteArray(10))) }
    }

    @Test
    fun aCommandThatTakesTooLongIsStopped() {
        val adbd = FakeAdbd { fake ->
            fake.handshake()
            fake.next()
        }
        var now = 0L
        val client = AdbClient.connect(adbd.plainStreams, nowMs = { now }) { adbd.tlsStreams }
        val thrown = runCatching {
            // The clock jumps past the limit as soon as the command has been sent.
            now = 120_000
            client.shell("x")
        }.exceptionOrNull()
        assertTrue(thrown is AdbProtocolException || thrown is java.io.IOException)
        adbd.join()
    }

    @Test
    fun aLongerLengthThanAllowedIsRefused() {
        val bad = byteArrayOf(1, -1, -1, -1, 127)
        assertThrows<AdbProtocolException> { AdbShellFrames().feed(bad) }
    }

    @Test
    fun eachStreamGetsItsOwnLocalId() {
        val adbd = FakeAdbd { fake ->
            fake.handshake()
            for (expected in 1..2) {
                val open = fake.next()
                assertEquals(expected, open.arg0)
                fake.send(AdbMessage(AdbConstants.OKAY, 50, expected))
                fake.send(AdbMessage(AdbConstants.CLSE, 50, expected))
                assertEquals(AdbConstants.CLSE, fake.next().command)
            }
        }
        val client = AdbClient.connect(adbd.plainStreams) { adbd.tlsStreams }
        assertEquals("", client.shell("a").output)
        assertEquals("", client.shell("b").output)
        adbd.join()
    }

    @Test
    fun aRefusedServiceThrows() {
        val adbd = FakeAdbd { fake ->
            fake.handshake()
            val open = fake.next()
            fake.send(AdbMessage(AdbConstants.CLSE, 0, open.arg0))
        }
        val client = AdbClient.connect(adbd.plainStreams) { adbd.tlsStreams }
        assertThrows<AdbProtocolException> { client.shell("x") }
        adbd.join()
    }

    @Test
    fun anAdbdWithoutTlsIsRefused() {
        val adbd = FakeAdbd { fake ->
            fake.next()
            fake.plainSend(AdbMessage(AdbConstants.AUTH, 1, 0, ByteArray(20)))
        }
        assertThrows<AdbProtocolException> { AdbClient.connect(adbd.plainStreams) { adbd.tlsStreams } }
        adbd.join()
    }

    @Test
    fun theConnectionClosedAfterTlsMeansNotAuthorized() {
        val adbd = FakeAdbd { fake ->
            fake.next()
            fake.plainSend(AdbMessage(AdbConstants.STLS, AdbConstants.STLS_VERSION, 0))
            fake.next()
            fake.closeTlsSide()
        }
        assertThrows<AdbNotAuthorizedException> { AdbClient.connect(adbd.plainStreams) { adbd.tlsStreams } }
        adbd.join()
    }

    @Test
    fun aFailedHandshakeIsReportedAsNotAuthorized() {
        val adbd = FakeAdbd { fake ->
            fake.next()
            fake.plainSend(AdbMessage(AdbConstants.STLS, AdbConstants.STLS_VERSION, 0))
            fake.next()
        }
        assertThrows<AdbNotAuthorizedException> {
            AdbClient.connect(adbd.plainStreams) { throw javax.net.ssl.SSLHandshakeException("no") }
        }
        adbd.join()
    }

    /** One frame of the shell protocol: id, little-endian length, data. */
    private fun frame(id: Int, data: ByteArray): ByteArray =
        byteArrayOf(id.toByte()) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array() + data

    private fun intAt(bytes: ByteArray, offset: Int): Int =
        (0 until 4).sumOf { i -> (bytes[offset + i].toInt() and 0xff) shl (8 * i) }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            assertTrue("expected ${T::class.simpleName} but got $t", t is T)
            return
        }
        fail("expected ${T::class.simpleName}")
    }

    /** One adbd on another thread, with a plain pair of pipes and a TLS pair of pipes (no real TLS). */
    private class FakeAdbd(private val script: (FakeAdbd) -> Unit) {
        private val toAdbdPlain = PipedOutputStream()
        private val fromClientPlain = PipedInputStream(toAdbdPlain, 1 shl 16)
        private val toClientPlain = PipedOutputStream()
        private val clientReadsPlain = PipedInputStream(toClientPlain, 1 shl 16)
        private val toAdbdTls = PipedOutputStream()
        private val fromClientTls = PipedInputStream(toAdbdTls, 1 shl 16)
        private val toClientTls = PipedOutputStream()
        private val clientReadsTls = PipedInputStream(toClientTls, 1 shl 16)
        private var input: InputStream = fromClientPlain
        private var output: OutputStream = toClientPlain

        val plainStreams = AdbStreams(clientReadsPlain, toAdbdPlain)
        val tlsStreams = AdbStreams(clientReadsTls, toAdbdTls)

        private val worker = thread { script(this) }

        fun next(): AdbMessage = AdbMessage.read(input)

        fun send(message: AdbMessage) {
            output.write(message.encode())
            output.flush()
        }

        fun plainSend(message: AdbMessage) = send(message)

        fun switchToTls() {
            input = fromClientTls
            output = toClientTls
        }

        fun handshake() {
            next()
            plainSend(AdbMessage(AdbConstants.STLS, AdbConstants.STLS_VERSION, 0))
            next()
            switchToTls()
            send(AdbMessage(AdbConstants.CNXN, AdbConstants.VERSION, 4096, "device::".toByteArray()))
        }

        fun closeTlsSide() {
            toClientTls.close()
        }

        fun join() = worker.join(5_000)
    }
}
