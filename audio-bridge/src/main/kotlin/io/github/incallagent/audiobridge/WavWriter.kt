// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.audiobridge

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming 16-bit PCM WAV writer. The RIFF/data sizes are written as 0 at open
 * and fixed up in [close], so a partially written file is still mostly readable.
 */
class WavWriter(
    file: File,
    val sampleRate: Int,
    val channels: Int = 1,
) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var closed = false
    private var scratch = ByteArray(0)

    init {
        require(sampleRate > 0) { "sampleRate must be > 0" }
        require(channels in 1..2) { "channels must be 1 or 2" }
        raf.setLength(0)
        raf.write(header(sampleRate, channels, 0))
    }

    /** Number of sample frames written so far. */
    val framesWritten: Long get() = dataBytes / (2L * channels)

    fun write(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset) {
        check(!closed) { "WavWriter is closed" }
        val n = length * 2
        if (scratch.size < n) {
            scratch = ByteArray(n)
        }
        val bb = ByteBuffer.wrap(scratch, 0, n).order(ByteOrder.LITTLE_ENDIAN)
        for (i in offset until offset + length) {
            bb.putShort(samples[i])
        }
        raf.write(scratch, 0, n)
        dataBytes += n
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            val dataSize = dataBytes.coerceAtMost(0xFFFF_FFFFL - 36)
            raf.seek(0)
            raf.write(header(sampleRate, channels, dataSize))
        } finally {
            raf.close()
        }
    }

    companion object {
        const val HEADER_SIZE = 44

        fun header(sampleRate: Int, channels: Int, dataSize: Long): ByteArray {
            val byteRate = sampleRate * channels * 2
            val bb = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray(Charsets.US_ASCII))
            bb.putInt((36 + dataSize).toInt())
            bb.put("WAVE".toByteArray(Charsets.US_ASCII))
            bb.put("fmt ".toByteArray(Charsets.US_ASCII))
            bb.putInt(16) // PCM fmt chunk size
            bb.putShort(1) // PCM
            bb.putShort(channels.toShort())
            bb.putInt(sampleRate)
            bb.putInt(byteRate)
            bb.putShort((channels * 2).toShort()) // block align
            bb.putShort(16) // bits per sample
            bb.put("data".toByteArray(Charsets.US_ASCII))
            bb.putInt(dataSize.toInt())
            return bb.array()
        }
    }
}
