// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell.adb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * adbd did not accept this app: after the TLS handshake it closed the connection without saying
 * who it is. In TLS 1.3 this is how a client certificate that is not paired shows up.
 */
internal class AdbNotAuthorizedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The two directions of a connection. */
internal class AdbStreams(val input: InputStream, val output: OutputStream)

/** What a command printed (stdout and stderr together) and how it ended (null if adbd did not say). */
internal class AdbShellResult(val output: String, val exitCode: Int?)

/**
 * The shell protocol of `shell,v2`: frames of one id byte, a 4-byte little-endian length and the
 * data, which can arrive split across messages (shell_protocol.h in AOSP's adb).
 */
internal class AdbShellFrames {
    private var pending = ByteArray(0)
    private val output = ByteArrayOutputStream()

    /** The exit code of the command, once its frame has arrived. */
    var exitCode: Int? = null
        private set

    /** Takes the next piece of the stream. */
    fun feed(chunk: ByteArray) {
        pending += chunk
        while (pending.size >= HEADER_SIZE) {
            val length = ByteBuffer.wrap(pending, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
            if (length < 0 || length > AdbConstants.MAX_PAYLOAD) throw AdbProtocolException("bad shell frame")
            if (pending.size < HEADER_SIZE + length) return
            val id = pending[0].toInt() and 0xff
            val data = pending.copyOfRange(HEADER_SIZE, HEADER_SIZE + length)
            pending = pending.copyOfRange(HEADER_SIZE + length, pending.size)
            when (id) {
                ID_STDOUT, ID_STDERR -> {
                    if (output.size() + data.size > MAX_OUTPUT) throw AdbProtocolException("too much output")
                    output.write(data)
                }
                ID_EXIT -> exitCode = data.firstOrNull()?.let { it.toInt() and 0xff }
                else -> Unit
            }
        }
    }

    fun output(): String = output.toString(Charsets.UTF_8.name())

    private companion object {
        /** More than any command here prints: a limit so that memory cannot run away. */
        const val MAX_OUTPUT = 1 shl 20
        const val HEADER_SIZE = 5
        const val ID_STDOUT = 1
        const val ID_STDERR = 2
        const val ID_EXIT = 3
    }
}

/**
 * A small adb host, enough to run shell commands on the phone's own adbd. One thread uses it at a
 * time. It needs only an open stream pair and a way to change it to TLS, so the protocol can be
 * tested without a device.
 *
 * The protocol, as in AOSP's adb (adb.cpp, transport.cpp, sockets.cpp):
 *  1. CNXN from us, STLS from adbd, STLS from us, then TLS 1.3 with our client certificate, then
 *     CNXN from adbd. adbd compares the key of the certificate with the keys it knows from pairing.
 *  2. For each stream: OPEN "shell,v2,raw:<command>", OKAY, one WRTE per piece of output (each
 *     answered with OKAY), and CLSE at the end, answered with CLSE. The output comes in the frames of
 *     the shell protocol ([AdbShellFrames]).
 *
 * `shell,v2,raw:` is what the adb on a PC uses. The plain `shell:` must not be used: without the shell
 * protocol adbd runs the command on a PTY (daemon/shell_service.cpp), and when the connection is
 * closed the PTY's hangup can kill a background process that has not yet left the session, which is
 * exactly what happened to the helper here.
 */
internal class AdbClient private constructor(private val streams: AdbStreams, private val nowMs: () -> Long) {
    private var nextLocalId = 1

    /**
     * Runs [command] on the phone and returns what it printed (stdout and stderr together) and how it
     * ended. The call ends when the command has ended and closed its output. Time limits come from
     * the socket.
     */
    fun shell(command: String): AdbShellResult {
        val deadline = nowMs() + SHELL_LIMIT_MS
        val localId = nextLocalId++
        val destination = "$SHELL_SERVICE$command\u0000".toByteArray(Charsets.UTF_8)
        if (destination.size > AdbConstants.MAX_PAYLOAD_V1) throw AdbProtocolException("command too long")
        send(AdbMessage(AdbConstants.OPEN, localId, 0, destination))

        val frames = AdbShellFrames()
        var remoteId = 0
        while (true) {
            if (nowMs() > deadline) throw AdbProtocolException("the command took too long")
            val message = AdbMessage.read(streams.input)
            when (message.command) {
                AdbConstants.OKAY -> if (message.arg1 == localId) remoteId = message.arg0
                AdbConstants.WRTE -> if (message.arg1 == localId) {
                    frames.feed(message.payload)
                    send(AdbMessage(AdbConstants.OKAY, localId, remoteId))
                }
                AdbConstants.CLSE -> if (message.arg1 == localId) {
                    // arg0 is 0 when the service was refused before it opened.
                    if (remoteId == 0) throw AdbProtocolException("the shell service was refused")
                    send(AdbMessage(AdbConstants.CLSE, localId, remoteId))
                    return AdbShellResult(frames.output(), frames.exitCode)
                }
                else -> Unit
            }
        }
    }

    private fun send(message: AdbMessage) {
        streams.output.write(message.encode())
        streams.output.flush()
    }

    companion object {
        private const val SHELL_SERVICE = "shell,v2,raw:"

        /** One command is short here (a start script, a permission); anything longer has gone wrong. */
        private const val SHELL_LIMIT_MS = 60_000L

        /**
         * Connects over [plain]; [startTls] does the TLS handshake on the same connection and returns
         * the encrypted streams. Throws [AdbNotAuthorizedException] when adbd does not take us.
         */
        fun connect(plain: AdbStreams, nowMs: () -> Long = { System.nanoTime() / 1_000_000 }, startTls: () -> AdbStreams): AdbClient {
            val banner = AdbConstants.HOST_BANNER.toByteArray(Charsets.US_ASCII)
            plain.output.write(AdbMessage(AdbConstants.CNXN, AdbConstants.VERSION, AdbConstants.MAX_PAYLOAD, banner).encode())
            plain.output.flush()

            val first = AdbMessage.read(plain.input)
            if (first.command != AdbConstants.STLS) {
                // AUTH would be a plain (non-TLS) adbd, which this app never talks to.
                throw AdbProtocolException("adbd did not ask for TLS")
            }
            plain.output.write(AdbMessage(AdbConstants.STLS, AdbConstants.STLS_VERSION, 0).encode())
            plain.output.flush()

            val tls = try {
                startTls()
            } catch (e: IOException) {
                throw AdbNotAuthorizedException("TLS handshake failed", e)
            }
            try {
                while (true) {
                    val message = AdbMessage.read(tls.input)
                    if (message.command == AdbConstants.CNXN) break
                }
            } catch (e: IOException) {
                throw AdbNotAuthorizedException("adbd closed the connection after the TLS handshake", e)
            }
            return AdbClient(tls, nowMs)
        }
    }
}
