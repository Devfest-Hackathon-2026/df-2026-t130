// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class AudioPipeTest {
    private fun bytes(vararg writes: (ByteArrayOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        for (w in writes) w(out)
        return out.toByteArray()
    }

    private fun chunk(n: Int, seed: Int = 1) = ByteArray(n) { ((it * 31 + seed) % 251).toByte() }

    @Test
    fun aChunkComesBackAsItWentIn() {
        val pcm = chunk(960)
        val message = AudioPipe.readMessage(ByteArrayInputStream(bytes({ AudioPipe.writeChunk(it, pcm) })))
        assertArrayEquals(pcm, (message as AudioPipe.Message.Chunk).pcm)
    }

    @Test
    fun messagesComeBackInOrderThenTheStreamEnds() {
        val a = chunk(640, 1)
        val b = chunk(960, 2)
        val input = ByteArrayInputStream(
            bytes(
                { AudioPipe.writeChunk(it, a) },
                { AudioPipe.writeFlush(it) },
                { AudioPipe.writeChunk(it, ByteArray(0)) },
                { AudioPipe.writeChunk(it, b) },
            ),
        )
        assertArrayEquals(a, (AudioPipe.readMessage(input) as AudioPipe.Message.Chunk).pcm)
        assertEquals(AudioPipe.Message.Flush, AudioPipe.readMessage(input))
        assertEquals(0, (AudioPipe.readMessage(input) as AudioPipe.Message.Chunk).pcm.size)
        assertArrayEquals(b, (AudioPipe.readMessage(input) as AudioPipe.Message.Chunk).pcm)
        assertNull(AudioPipe.readMessage(input))
        assertNull(AudioPipe.readMessage(input))
    }

    @Test
    fun theHeaderIsABigEndianLengthAndTheFlushMarkerIsMinusOne() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 3, 7, 8, 9), bytes({ AudioPipe.writeChunk(it, byteArrayOf(7, 8, 9)) }))
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), bytes({ AudioPipe.writeFlush(it) }))
    }

    @Test
    fun aMessageIsOneWrite() {
        // A message must never be split between two writes: header and payload go out together.
        val writes = ArrayList<Int>()
        val counting = object : OutputStream() {
            override fun write(b: Int) {
                writes += 1
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                writes += len
            }
        }
        AudioPipe.writeChunk(counting, chunk(960))
        AudioPipe.writeFlush(counting)
        assertEquals(listOf(964, 4), writes)
    }

    @Test
    fun aTooLargeChunkIsRefusedAndNothingIsWritten() {
        val out = ByteArrayOutputStream()
        try {
            AudioPipe.writeChunk(out, ByteArray(AudioPipe.MAX_CHUNK_BYTES + 1))
            fail("should have refused")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, out.size())
        // The largest allowed chunk still goes through.
        AudioPipe.writeChunk(out, ByteArray(AudioPipe.MAX_CHUNK_BYTES))
        assertEquals(AudioPipe.MAX_CHUNK_BYTES, (AudioPipe.readMessage(ByteArrayInputStream(out.toByteArray())) as AudioPipe.Message.Chunk).pcm.size)
    }

    @Test
    fun aCorruptLengthIsRefusedOnRead() {
        for (header in listOf(
            byteArrayOf(0x7F, -1, -1, -1), // huge
            byteArrayOf(-1, -1, -1, -2), // negative, but not the flush marker
            byteArrayOf(0, 1, 0, 1), // 65537: one past the limit
        )) {
            try {
                AudioPipe.readMessage(ByteArrayInputStream(header + ByteArray(8)))
                fail("should have refused ${header.toList()}")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun aCutOffPayloadIsAnErrorButAHalfHeaderIsJustAnEnd() {
        try {
            AudioPipe.readMessage(ByteArrayInputStream(byteArrayOf(0, 0, 0, 10, 1, 2, 3, 4, 5)))
            fail("should have thrown")
        } catch (_: EOFException) {
        }
        // The writer died in the middle of a header: nothing usable, so the stream just ends.
        assertNull(AudioPipe.readMessage(ByteArrayInputStream(byteArrayOf(0, 0))))
    }

    @Test
    fun worksOverARealPipeWithAWriterThread() {
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut, 1024) // small, so the writer really has to wait for the reader
        val sent = (0 until 200).map { chunk(960, it) }
        val writer = Thread {
            sent.forEachIndexed { i, pcm ->
                AudioPipe.writeChunk(pipeOut, pcm)
                if (i % 50 == 49) AudioPipe.writeFlush(pipeOut)
            }
            pipeOut.close()
        }
        writer.start()
        var chunks = 0
        var flushes = 0
        while (true) {
            when (val m = AudioPipe.readMessage(pipeIn) ?: break) {
                is AudioPipe.Message.Chunk -> {
                    assertArrayEquals("chunk $chunks", sent[chunks], m.pcm)
                    chunks++
                }
                AudioPipe.Message.Flush -> flushes++
            }
        }
        writer.join(2000)
        assertEquals(200, chunks)
        assertEquals(4, flushes)
        assertTrue(!writer.isAlive)
    }

    /** Gives the bytes out one at a time, the way a slow pipe can. */
    private class Trickle(private val data: ByteArray) : InputStream() {
        private var at = 0

        override fun read(): Int = if (at < data.size) data[at++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (at >= data.size) return -1
            b[off] = data[at++]
            return 1
        }
    }

    @Test
    fun messagesAreReadWholeFromAStreamThatGivesOneByteAtATime() {
        val a = chunk(640, 1)
        val b = chunk(960, 2)
        val input = Trickle(
            bytes({ AudioPipe.writeChunk(it, a) }, { AudioPipe.writeFlush(it) }, { AudioPipe.writeChunk(it, b) }),
        )
        assertArrayEquals(a, (AudioPipe.readMessage(input) as AudioPipe.Message.Chunk).pcm)
        assertEquals(AudioPipe.Message.Flush, AudioPipe.readMessage(input))
        assertArrayEquals(b, (AudioPipe.readMessage(input) as AudioPipe.Message.Chunk).pcm)
        assertNull(AudioPipe.readMessage(input))
    }

    @Test
    fun anEndInsideTheHeaderIsJustAnEndAndAnEndInsideThePayloadIsAnErrorAtEveryPosition() {
        val whole = bytes({ AudioPipe.writeChunk(it, chunk(10)) }) // 4 bytes of header, 10 of payload
        for (cut in 0 until whole.size) {
            val input = Trickle(whole.copyOf(cut))
            if (cut < 4) {
                assertNull("cut after $cut bytes", AudioPipe.readMessage(input))
            } else {
                try {
                    AudioPipe.readMessage(input)
                    fail("cut after $cut bytes should have thrown")
                } catch (_: EOFException) {
                }
            }
        }
        assertArrayEquals(chunk(10), (AudioPipe.readMessage(Trickle(whole)) as AudioPipe.Message.Chunk).pcm)
    }
}
