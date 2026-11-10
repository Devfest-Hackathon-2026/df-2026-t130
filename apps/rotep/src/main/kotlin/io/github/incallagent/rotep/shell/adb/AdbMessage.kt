// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell.adb

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Something the adb peer sent that this client does not understand. */
internal class AdbProtocolException(message: String) : IOException(message)

/** The constants of the adb protocol, from AOSP's adb.h. */
internal object AdbConstants {
    const val CNXN = 0x4e584e43
    const val STLS = 0x534c5453
    const val AUTH = 0x48545541
    const val OPEN = 0x4e45504f
    const val OKAY = 0x59414b4f
    const val CLSE = 0x45534c43
    const val WRTE = 0x45545257

    const val VERSION = 0x01000001
    const val STLS_VERSION = 0x01000000

    /** What we offer to receive at most. The other side may offer less; we stay below this. */
    const val MAX_PAYLOAD = 1024 * 1024

    /** The largest payload of the messages before the connection is up (OPEN and CNXN in practice). */
    const val MAX_PAYLOAD_V1 = 4 * 1024

    const val HEADER_SIZE = 24

    /** What a host says about itself. An empty feature list keeps adbd to the plain protocol. */
    const val HOST_BANNER = "host::"
}

/** One message of the adb protocol: a 24-byte little-endian header and a payload. */
internal class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray = ByteArray(0)) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(AdbConstants.HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(command)
        buffer.putInt(arg0)
        buffer.putInt(arg1)
        buffer.putInt(payload.size)
        buffer.putInt(sum(payload))
        buffer.putInt(command.inv())
        buffer.put(payload)
        return buffer.array()
    }

    companion object {
        /** The sum of the unsigned payload bytes. adbd can ask for it on older connections; it is always sent. */
        fun sum(payload: ByteArray): Int = payload.fold(0) { acc, b -> acc + (b.toInt() and 0xff) }

        /** Reads one message. Throws [IOException] on a closed stream or a bad header. */
        fun read(input: InputStream, maxPayload: Int = AdbConstants.MAX_PAYLOAD): AdbMessage {
            val header = ByteArray(AdbConstants.HEADER_SIZE)
            readFully(input, header)
            val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = fields.getInt()
            val arg0 = fields.getInt()
            val arg1 = fields.getInt()
            val length = fields.getInt()
            fields.getInt()
            val magic = fields.getInt()
            if (magic != command.inv()) throw AdbProtocolException("bad message header")
            if (length < 0 || length > maxPayload) throw AdbProtocolException("message too long")
            val payload = ByteArray(length)
            readFully(input, payload)
            return AdbMessage(command, arg0, arg1, payload)
        }

        /** Fills [buffer] or throws. */
        fun readFully(input: InputStream, buffer: ByteArray) {
            var done = 0
            while (done < buffer.size) {
                val count = input.read(buffer, done, buffer.size - done)
                if (count < 0) throw EOFException("the stream ended")
                done += count
            }
        }
    }
}
