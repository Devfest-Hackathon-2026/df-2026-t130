// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * The wire format of the two pipes of 1b (downlink: helper → app; uplink: app → helper): one
 * message is a 4-byte big-endian length followed by that many bytes of 16-bit little-endian PCM,
 * except the flush marker (length [FLUSH_MARKER], no payload), which means "drop whatever has not
 * played yet" (the uplink pipe only; [ServerEvent.Interrupted][io.github.incallagent.agent.live.ServerEvent.Interrupted]
 * → [CallAudioPath.flush][io.github.incallagent.rotep.call.CallAudioPath.flush]).
 *
 * Works on any [InputStream]/[OutputStream]; a `ParcelFileDescriptor`'s own streams are the only
 * Android-specific part, so this is checked on a PC with plain byte-array streams.
 */
object AudioPipe {
    /** No chunk is ever this large (20 ms at 48 kHz is 1920 bytes); guards against a corrupt stream. */
    const val MAX_CHUNK_BYTES = 64 * 1024

    private const val FLUSH_MARKER = -1
    private const val HEADER_BYTES = 4

    sealed interface Message {
        data class Chunk(val pcm: ByteArray) : Message
        data object Flush : Message
    }

    // One write per message (header and payload together), so a message is never split between
    // two writes, and a pipe sees one system call instead of five.
    fun writeChunk(out: OutputStream, pcm: ByteArray) {
        require(pcm.size <= MAX_CHUNK_BYTES) { "chunk too large: ${pcm.size}" }
        out.write(ByteBuffer.allocate(4 + pcm.size).putInt(pcm.size).put(pcm).array())
        out.flush()
    }

    fun writeFlush(out: OutputStream) {
        out.write(ByteBuffer.allocate(4).putInt(FLUSH_MARKER).array())
        out.flush()
    }

    /**
     * One message, or null at a clean end of stream (the other side closed its end). A stream that
     * ends inside the 4-byte header is also just an end (the writer died in the middle of it:
     * nothing usable); one that ends inside the payload is an [EOFException].
     */
    fun readMessage(input: InputStream): Message? {
        // One read loop for the header, no wrapper object per message (this runs 50 times a second).
        val header = ByteArray(HEADER_BYTES)
        if (!readFully(input, header)) return null
        val len = ByteBuffer.wrap(header).int
        if (len == FLUSH_MARKER) return Message.Flush
        require(len in 0..MAX_CHUNK_BYTES) { "chunk length out of range: $len" }
        val buf = ByteArray(len)
        if (!readFully(input, buf)) throw EOFException("message cut off ($len bytes announced)")
        return Message.Chunk(buf)
    }

    /** Fills [buf] from [input]; false when the stream ends before it is full. */
    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var at = 0
        while (at < buf.size) {
            val n = input.read(buf, at, buf.size - at)
            if (n < 0) return false
            at += n
        }
        return true
    }
}
