// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell.adb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The pairing did not end with both sides sure of each other. */
internal class AdbPairingException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The numbers and strings of the pairing protocol, from AOSP's adb (pairing_connection, pairing_auth, tls). */
internal object AdbPairingWire {
    const val VERSION: Byte = 1
    const val TYPE_SPAKE2: Byte = 0
    const val TYPE_PEER_INFO: Byte = 1

    /** kMaxPeerInfoSize: one byte of type and 8191 of data, always the full size. */
    const val PEER_INFO_SIZE = 8192
    const val PEER_INFO_RSA_PUB_KEY: Byte = 0
    const val MAX_FRAME_PAYLOAD = 2 * PEER_INFO_SIZE
    const val HEADER_SIZE = 6

    // Both names include the terminating NUL of the C string: adbd passes sizeof.
    val CLIENT_NAME: ByteArray = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
    val SERVER_NAME: ByteArray = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)

    /** The exporter label; adbd passes sizeof(label), so the NUL is part of it. */
    const val EXPORTED_KEY_LABEL = "adb-label\u0000"
    const val EXPORTED_KEY_SIZE = 64

    val HKDF_INFO: ByteArray = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)
    const val AES_KEY_SIZE = 16
    const val GCM_NONCE_SIZE = 12
    const val GCM_TAG_BITS = 128
}

/**
 * The pairing exchange after the TLS handshake (as the client): our SPAKE2 message first, then theirs;
 * then our public key, encrypted with the shared key, and then theirs. It ends once both sides have
 * proved that they know the code. The exported key material is bound in, as adbd does.
 */
internal class AdbPairingExchange(private val code: ByteArray, private val exportedKeyMaterial: ByteArray, private val peerInfo: ByteArray, private val randomBytes: (Int) -> ByteArray = { Spake2Random.bytes(it) }) {
    private var encryptCounter = 0L
    private var decryptCounter = 0L

    /** Runs the whole exchange. Throws [AdbPairingException] when it does not end in success. */
    fun run(input: InputStream, output: OutputStream) {
        require(peerInfo.size == AdbPairingWire.PEER_INFO_SIZE) { "bad PeerInfo size" }
        val spake = Spake2(isAlice = true, AdbPairingWire.CLIENT_NAME, AdbPairingWire.SERVER_NAME, randomBytes)
        val out = DataOutputStream(output)
        val `in` = DataInputStream(input)

        val mine = spake.generateMessage(code + exportedKeyMaterial)
        writeFrame(out, AdbPairingWire.TYPE_SPAKE2, mine)

        val theirs = readFrame(`in`, AdbPairingWire.TYPE_SPAKE2)
        val keyMaterial = spake.processMessage(theirs) ?: throw AdbPairingException("the peer's message is not a curve point")
        val key = hkdfSha256(keyMaterial, AdbPairingWire.HKDF_INFO, AdbPairingWire.AES_KEY_SIZE)

        writeFrame(out, AdbPairingWire.TYPE_PEER_INFO, gcm(Cipher.ENCRYPT_MODE, key, encryptCounter++, peerInfo))

        val sealed = try {
            readFrame(`in`, AdbPairingWire.TYPE_PEER_INFO)
        } catch (e: IOException) {
            // adbd closes the connection when it cannot decrypt our PeerInfo, for example with a wrong code.
            throw AdbPairingException("no answer from adbd (the code may be wrong or expired)", e)
        }
        val opened = try {
            gcm(Cipher.DECRYPT_MODE, key, decryptCounter++, sealed)
        } catch (e: AEADBadTagException) {
            throw AdbPairingException("the answer from adbd could not be decrypted", e)
        }
        if (opened.size != AdbPairingWire.PEER_INFO_SIZE) throw AdbPairingException("unexpected PeerInfo size")
    }

    private fun writeFrame(out: DataOutputStream, type: Byte, payload: ByteArray) {
        val header = ByteBuffer.allocate(AdbPairingWire.HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(AdbPairingWire.VERSION)
            .put(type)
            .putInt(payload.size)
            .array()
        out.write(header)
        out.write(payload)
        out.flush()
    }

    private fun readFrame(input: DataInputStream, expected: Byte): ByteArray {
        val header = ByteArray(AdbPairingWire.HEADER_SIZE)
        input.readFully(header)
        val fields = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = fields.get()
        val type = fields.get()
        val size = fields.getInt()
        if (version != AdbPairingWire.VERSION) throw AdbPairingException("unknown pairing version")
        if (type != expected) throw AdbPairingException("unexpected pairing message")
        if (size <= 0 || size > AdbPairingWire.MAX_FRAME_PAYLOAD) throw AdbPairingException("pairing message too long")
        return ByteArray(size).also { input.readFully(it) }
    }

    private fun gcm(mode: Int, key: ByteArray, counter: Long, data: ByteArray): ByteArray {
        // The nonce is the sequence number, little-endian, in the first 8 of the 12 bytes.
        val nonce = ByteBuffer.allocate(AdbPairingWire.GCM_NONCE_SIZE).order(ByteOrder.LITTLE_ENDIAN).putLong(counter).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(AdbPairingWire.GCM_TAG_BITS, nonce))
        return cipher.doFinal(data)
    }
}

/** HKDF-SHA256 with an empty salt (RFC 5869). */
internal fun hkdfSha256(inputKey: ByteArray, info: ByteArray, length: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(ByteArray(mac.macLength), "HmacSHA256"))
    val pseudoRandomKey = mac.doFinal(inputKey)
    mac.init(SecretKeySpec(pseudoRandomKey, "HmacSHA256"))
    val output = ByteArray(length)
    var block = ByteArray(0)
    var filled = 0
    var counter = 1
    while (filled < length) {
        mac.update(block)
        mac.update(info)
        mac.update(counter.toByte())
        block = mac.doFinal()
        block.copyInto(output, filled, 0, minOf(block.size, length - filled))
        filled += block.size
        counter++
    }
    return output
}

/** Random bytes for the exchange: SecureRandom in the app, fixed bytes in tests. */
internal object Spake2Random {
    fun bytes(size: Int): ByteArray = ByteArray(size).also { java.security.SecureRandom().nextBytes(it) }
}
