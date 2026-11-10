// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.audiobridge

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun headerIsFixedUpOnClose() {
        val f = tmp.newFile("t.wav")
        WavWriter(f, 48_000).use { w ->
            w.write(shortArrayOf(1, -1, 32767, -32768))
            w.write(ShortArray(96) { 0 }, 10, 6)
            assertEquals(10L, w.framesWritten)
        }

        val bytes = f.readBytes()
        assertEquals(44 + 20, bytes.size)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + 20, bb.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals(1, bb.getShort(20).toInt()) // PCM
        assertEquals(1, bb.getShort(22).toInt()) // mono
        assertEquals(48_000, bb.getInt(24))
        assertEquals(96_000, bb.getInt(28)) // byte rate
        assertEquals(16, bb.getShort(34).toInt())
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(20, bb.getInt(40))
        assertEquals(32767, bb.getShort(48).toInt())
        assertEquals(-32768, bb.getShort(50).toInt())
    }

    @Test
    fun emptyFileHasValidHeader() {
        val f = tmp.newFile("e.wav")
        WavWriter(f, 16_000).close()
        val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, f.length().toInt())
        assertEquals(36, bb.getInt(4))
        assertEquals(0, bb.getInt(40))
    }
}
