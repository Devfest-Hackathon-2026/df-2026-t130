// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import android.os.Build
import android.security.KeyStoreException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets: [KeystoreCipher] in the app, a fake in tests. */
interface SecretCipher {
    /** Throws when it cannot encrypt. */
    fun encrypt(plain: ByteArray): ByteArray

    /** Throws when the key is gone or [data] was not made with it. */
    fun decrypt(data: ByteArray): ByteArray

    /** Deletes the key; the next [encrypt] makes a new one. */
    fun deleteKey()

    /** true when the key is still there, so that a failure of [decrypt] does not mean that it is lost. */
    fun hasKey(): Boolean = false

    /** true when [failure] of [decrypt] can go away by itself: neither the key nor the data is to blame. */
    fun isTemporary(failure: Throwable): Boolean = false
}

/** The key that encrypted the saved data is not there any more. */
class KeyMissingException : GeneralSecurityException("the key is missing")

/**
 * An API key, encrypted with [cipher], in a file under [dir] (noBackupFilesDir in the app:
 * app-private, never part of a cloud backup or a device-to-device transfer; ADR-026).
 *
 * - Saved: encrypted, written to a new file, then moved into place
 * - Unreadable (the key is gone, the file is damaged): the file and the key are deleted, and
 *   [problem] asks the user to paste the API key again
 * - Not readable just now (the key store is busy, the file cannot be read this time): nothing is
 *   deleted, and the next [read] tries again
 * - A failure not known to be temporary, with the key still in the key store: kept for a few starts of
 *   the app, and then it counts as unreadable, so the app can never stay stuck with a key that does not work
 * - A plaintext file from before: encrypted into place, then deleted. If it cannot be encrypted,
 *   it stays as it was and is still used
 *
 * The API key is never logged.
 */
class ApiKeyStore(
    dir: File,
    private val cipher: SecretCipher,
    private val log: (String) -> Unit = {},
    fileName: String = FILE,
    plainFileName: String = PLAIN_FILE,
    /** Which key, for the words on the screen ("Claude の "). */
    private val who: String = "",
) {
    sealed interface Read {
        class Key(val value: String) : Read {
            override fun toString() = "Key(<${value.length} chars>)"
        }

        data object None : Read

        /** The saved key could not be read and was deleted ([why]: the exception's class name). */
        data class Unreadable(val why: String) : Read

        /** The saved key cannot be read just now and was kept: the next [read] tries again. */
        data class NotNow(val why: String) : Read
    }

    private val file = File(dir, fileName)
    private val temp = File(dir, "$fileName.tmp")

    /** Where the API key was kept, in plaintext, before it was encrypted. */
    private val plain = File(dir, plainFileName)

    /** How many starts of the app in a row could not read it, with the key still there. */
    private val failedRuns = File(dir, "$fileName.failed")
    private var countedThisRun = false

    /** Why the saved API key cannot be used, for the screen: set until one is read or saved again. */
    @Volatile
    var problem: String? = null
        private set

    /** Whether a key is saved, without decrypting anything or waiting for a [read]. It can still turn out to be unreadable. */
    val exists: Boolean get() = file.exists() || plain.length() > 0

    @Synchronized
    fun read(): Read {
        if (!file.exists()) return readPlain()?.let { moveFromPlain(it) } ?: Read.None
        val why = try {
            val value = cipher.decrypt(file.readBytes()).toString(Charsets.UTF_8)
            if (value.isNotEmpty()) {
                // Left over when moving from the plaintext file was cut short
                plain.delete()
                forgetFailures()
                problem = null
                return Read.Key(value)
            }
            "empty"
        } catch (t: Throwable) {
            if (isTemporary(t) || mayStillPass()) return keptForNow(t.javaClass.simpleName)
            t.javaClass.simpleName
        }
        log("api key: unreadable ($why), deleted")
        file.delete()
        forgetFailures()
        runCatching { cipher.deleteKey() }
        readPlain()?.let { return moveFromPlain(it) }
        problem = UserMessages.apiKeyUnreadable(who)
        return Read.Unreadable(why)
    }

    /** true when the API key was saved. A blank key is not saved. */
    @Synchronized
    fun save(key: String): Boolean {
        val value = key.trim()
        if (value.isEmpty() || !write(value)) return false
        plain.delete()
        forgetFailures()
        problem = null
        return true
    }

    @Synchronized
    fun clear() {
        file.delete()
        temp.delete()
        plain.delete()
        forgetFailures()
        runCatching { cipher.deleteKey() }
        problem = null
    }

    private fun isTemporary(t: Throwable) = t is IOException || t is OutOfMemoryError || cipher.isTemporary(t)

    /**
     * The key is in the key store and reading it failed anyway. Below API 33 a failure cannot say that
     * it will pass, so what is saved is not given up before [MAX_FAILED_RUNS] starts of the app.
     */
    private fun mayStillPass(): Boolean {
        if (!runCatching { cipher.hasKey() }.getOrDefault(false)) return false
        val runs = runCatching { failedRuns.readText().toInt() }.getOrDefault(0)
        if (countedThisRun) return runs <= MAX_FAILED_RUNS
        if (runs >= MAX_FAILED_RUNS) return false
        countedThisRun = true
        runCatching { failedRuns.writeText((runs + 1).toString()) }
        return true
    }

    private fun forgetFailures() {
        failedRuns.delete()
        countedThisRun = false
    }

    private fun keptForNow(why: String): Read {
        log("api key: not readable this time ($why), kept")
        problem = UserMessages.apiKeyNotReadableNow(who)
        return Read.NotNow(why)
    }

    private fun moveFromPlain(value: String): Read {
        if (write(value)) {
            plain.delete()
            log("api key: moved from the plaintext file to the encrypted one")
        } else {
            log("api key: the plaintext file could not be encrypted and stays")
        }
        return Read.Key(value)
    }

    private fun write(value: String): Boolean = try {
        replaceFile(temp, file, cipher.encrypt(value.toByteArray(Charsets.UTF_8)))
        true
    } catch (t: Throwable) {
        temp.delete()
        log("api key: not saved (${t.javaClass.simpleName})")
        false
    }

    private fun readPlain(): String? = runCatching { plain.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    companion object {
        const val FILE = "gemini_api_key.enc"
        const val PLAIN_FILE = "gemini_api_key"
        const val MAX_FAILED_RUNS = 3
    }
}

/**
 * AES-256-GCM with a key made in AndroidKeyStore: the key cannot be taken out of the phone. The
 * data is a version byte, the IV's length, the IV (chosen by the keystore), then the ciphertext
 * with its tag.
 */
class KeystoreCipher(private val alias: String = "rotep_gemini_api_key") : SecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, existingKey() ?: newKey())
        val iv = c.iv
        return byteArrayOf(VERSION, iv.size.toByte()) + iv + c.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        if (data.size < 2 || data[0] != VERSION) throw GeneralSecurityException("unknown format")
        val ivSize = data[1].toInt()
        if (ivSize !in 12..16 || data.size <= 2 + ivSize) throw GeneralSecurityException("bad length")
        val key = existingKey() ?: throw KeyMissingException()
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data, 2, ivSize))
        return c.doFinal(data, 2 + ivSize, data.size - 2 - ivSize)
    }

    override fun deleteKey() {
        val ks = keyStore()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    /** A key store that does not answer has not said that the key is gone. */
    override fun hasKey(): Boolean = runCatching { keyStore().containsAlias(alias) }.getOrDefault(true)

    /** KeyStoreException says so itself, from API 33. Before that, every failure counts as lasting. */
    override fun isTemporary(failure: Throwable): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        // It comes wrapped in what the cipher throws (IllegalBlockSizeException, InvalidKeyException...)
        return generateSequence(failure) { it.cause }
            .take(MAX_CAUSES)
            .any { it is KeyStoreException && it.isTransientFailure }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun newKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val VERSION: Byte = 1
        const val MAX_CAUSES = 8
    }
}
