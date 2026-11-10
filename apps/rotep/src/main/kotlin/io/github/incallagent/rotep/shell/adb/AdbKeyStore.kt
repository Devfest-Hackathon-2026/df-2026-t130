// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell.adb

import io.github.incallagent.rotep.app.SecretCipher
import io.github.incallagent.rotep.app.replaceFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64

/** The key the app uses towards adbd, and the certificate it shows in the TLS handshake. */
internal class AdbKeys(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val publicKey: RSAPublicKey get() = certificate.publicKey as RSAPublicKey
}

/**
 * Keeps the app's adb key: whoever has it gets a shell on the phone once it is paired. It is
 * encrypted with a key that stays in AndroidKeyStore ([cipher]) and written to [dir]
 * (noBackupFilesDir in the app: never part of a backup or a device-to-device transfer).
 *
 * Nothing here logs the key, and the bytes never leave this class except as [AdbKeys].
 *
 * An unreadable file (the keystore key is gone, the file is damaged) is replaced by a new key, and
 * the next connection then asks for a pairing again. A temporary failure of the keystore
 * ([SecretCipher.isTemporary]) throws instead, so that a working key is not thrown away.
 */
internal class AdbKeyStore(dir: File, private val cipher: SecretCipher) {
    private val file = File(dir, FILE)
    private val temp = File(dir, "$FILE.tmp")

    /**
     * The saved key, or null when there is none. Never replaces anything: a failure to read (the
     * keystore can still be busy right after a restart) is thrown and leaves the file where it is.
     */
    @Synchronized
    fun loadExisting(): AdbKeys? = if (file.exists()) decode(cipher.decrypt(file.readBytes())) else null

    @Synchronized
    fun loadOrCreate(): AdbKeys {
        if (file.exists()) {
            try {
                return decode(cipher.decrypt(file.readBytes()))
            } catch (t: Throwable) {
                if (t is IOException || cipher.isTemporary(t)) throw t
                file.delete()
                runCatching { cipher.deleteKey() }
            }
        }
        val keys = generate()
        val blob = encode(keys)
        replaceFile(temp, file, cipher.encrypt(blob))
        return keys
    }

    private fun encode(keys: AdbKeys): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        val privateKey = keys.privateKey.encoded
        data.writeInt(privateKey.size)
        data.write(privateKey)
        data.write(keys.certificate.encoded)
        data.flush()
        return out.toByteArray()
    }

    private fun decode(blob: ByteArray): AdbKeys {
        val buffer = ByteBuffer.wrap(blob)
        val keySize = buffer.getInt()
        require(keySize in 1 until blob.size) { "bad key size" }
        val keyBytes = ByteArray(keySize).also { buffer.get(it) }
        val certBytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        return AdbKeys(key, cert)
    }

    companion object {
        const val FILE = "adb_key.enc"
        const val KEYSTORE_ALIAS = "rotep_adb_key"

        /** Makes a new RSA-2048 key and a self-signed certificate for it. */
        fun generate(): AdbKeys {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            val pair = generator.generateKeyPair()
            return AdbKeys(pair.private, AdbCertificate.selfSigned(pair.public as RSAPublicKey, pair.private))
        }
    }
}

/** The adb form of an RSA public key and the certificate, written by hand: there is no public API for either on Android. */
internal object AdbKeyFormat {
    private const val MODULUS_BYTES = 256
    private const val KEY_STRUCT_SIZE = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4

    /**
     * What goes in the PeerInfo of a pairing: base64 of the adb public key, a space and the name that
     * the phone shows in its list of paired devices, in one zero-padded 8192-byte block with the type byte in front.
     */
    fun peerInfo(publicKey: RSAPublicKey, deviceName: String): ByteArray {
        val encoded = Base64.getEncoder().encodeToString(publicKeyStruct(publicKey))
        val data = "$encoded $deviceName".toByteArray(Charsets.UTF_8)
        require(data.size <= AdbPairingWire.PEER_INFO_SIZE - 2) { "PeerInfo too long" }
        val out = ByteArray(AdbPairingWire.PEER_INFO_SIZE)
        out[0] = AdbPairingWire.PEER_INFO_RSA_PUB_KEY
        data.copyInto(out, 1)
        return out
    }

    /**
     * The struct of libcrypto_utils' android_pubkey: little-endian, the size of the modulus in 32-bit
     * words, -1/n mod 2^32, the modulus, R^2 mod n (R = 2^2048) and the exponent.
     */
    fun publicKeyStruct(key: RSAPublicKey): ByteArray {
        val modulus = key.modulus
        require(modulus.bitLength() == MODULUS_BYTES * 8) { "the key must be RSA-2048" }
        val out = ByteBuffer.allocate(KEY_STRUCT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MODULUS_BYTES / 4)
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32 - modulus.mod(r32).modInverse(r32)
        out.putInt(n0inv.toInt())
        out.put(littleEndian(modulus))
        val r = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8)
        out.put(littleEndian(r.multiply(r).mod(modulus)))
        out.putInt(key.publicExponent.toInt())
        return out.array()
    }

    private fun littleEndian(value: BigInteger): ByteArray {
        val big = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        require(big.size <= MODULUS_BYTES) { "value too long" }
        return ByteArray(MODULUS_BYTES).also { out -> big.reversedArray().copyInto(out) }
    }
}

/** A minimal self-signed X.509 v3 certificate in DER, so the app needs no ASN.1 library. */
internal object AdbCertificate {
    private const val SUBJECT = "aitel-adb"
    private const val VALID_DAYS = 3650L

    fun selfSigned(publicKey: RSAPublicKey, privateKey: PrivateKey, now: Instant = Instant.now()): X509Certificate {
        val tbs = der(
            0x30,
            der(0xa0, derInteger(BigInteger.valueOf(2))),
            derInteger(BigInteger.ONE),
            SHA256_WITH_RSA,
            name(),
            der(0x30, derUtcTime(now.minus(1, ChronoUnit.DAYS)), derUtcTime(now.plus(VALID_DAYS, ChronoUnit.DAYS))),
            name(),
            publicKey.encoded,
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(tbs)
            sign()
        }
        val certificate = der(0x30, tbs, SHA256_WITH_RSA, der(0x03, byteArrayOf(0), signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
    }

    private val SHA256_WITH_RSA = der(0x30, oid(0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0b), byteArrayOf(0x05, 0x00))

    private fun name(): ByteArray =
        der(0x30, der(0x31, der(0x30, oid(0x55, 0x04, 0x03), der(0x0c, SUBJECT.toByteArray(Charsets.UTF_8)))))

    private fun oid(vararg bytes: Int): ByteArray = der(0x06, ByteArray(bytes.size) { bytes[it].toByte() })

    private fun derInteger(value: BigInteger): ByteArray = der(0x02, value.toByteArray())

    private fun derUtcTime(instant: Instant): ByteArray =
        der(0x17, DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC).format(instant).toByteArray(Charsets.US_ASCII))

    private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { acc, part -> acc + part }
        return byteArrayOf(tag.toByte()) + derLength(body.size) + body
    }

    private fun derLength(length: Int): ByteArray = when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length < 0x100 -> byteArrayOf(0x81.toByte(), length.toByte())
        length < 0x10000 -> byteArrayOf(0x82.toByte(), (length ushr 8).toByte(), length.toByte())
        else -> throw IllegalArgumentException("too long")
    }
}
