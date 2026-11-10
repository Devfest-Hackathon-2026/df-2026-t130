// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import javax.crypto.AEADBadTagException

class ApiKeyStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // Fictitious key
    private val apiKey = "test-api-key-0000"

    /**
     * Not real encryption: the bytes are flipped and marked with the id of the key that made them,
     * so data made with an older key cannot be read with a newer one.
     */
    private class FakeCipher : SecretCipher {
        var key: Int? = null
        var broken = false

        /** Thrown by [decrypt] when set. */
        var decryptFailure: Throwable? = null

        /** What [isTemporary] answers (the keystore's own word for its failures). */
        var saysTemporary = false

        /** What [hasKey] answers. */
        var keyStillThere = false
        private var next = 1

        override fun encrypt(plain: ByteArray): ByteArray {
            if (broken) throw IllegalStateException("keystore")
            val k = key ?: next++.also { key = it }
            return byteArrayOf(k.toByte()) + ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }
        }

        override fun decrypt(data: ByteArray): ByteArray {
            decryptFailure?.let { throw it }
            val k = key ?: throw KeyMissingException()
            if (data.isEmpty() || data[0] != k.toByte()) throw AEADBadTagException()
            return ByteArray(data.size - 1) { (data[it + 1].toInt() xor 0x5A).toByte() }
        }

        override fun deleteKey() {
            key = null
        }

        override fun isTemporary(failure: Throwable) = saysTemporary

        override fun hasKey() = keyStillThere
    }

    private val cipher = FakeCipher()
    private fun dir(): File = tmp.root
    private fun store() = ApiKeyStore(dir(), cipher)
    private fun encrypted() = File(dir(), ApiKeyStore.FILE)
    private fun plain() = File(dir(), ApiKeyStore.PLAIN_FILE)
    private val otherCipher = FakeCipher()
    private fun claude() = ApiKeyStore(dir(), otherCipher, fileName = "claude_api_key.enc", plainFileName = "claude_api_key", who = "Claude の ")

    @Test
    fun aPlaintextFileOfTheOtherKeyIsNotThisKey() {
        plain().writeText("$apiKey\n")
        assertEquals(ApiKeyStore.Read.None, claude().read())
        assertTrue(plain().exists())
    }

    @Test
    fun clearingOneKeyLeavesTheOther() {
        store().save(apiKey)
        val other = claude()
        other.save("other-key-0000")
        assertEquals("other-key-0000", (other.read() as ApiKeyStore.Read.Key).value)
        other.clear()
        assertEquals(ApiKeyStore.Read.None, other.read())
        assertEquals(apiKey, (store().read() as ApiKeyStore.Read.Key).value)
    }

    @Test
    fun theProblemSaysWhichKey() {
        val other = claude()
        other.save(apiKey)
        otherCipher.deleteKey()
        other.read()
        assertTrue(other.problem!!.contains("Claude の API キー"))
    }

    @Test
    fun aSavedKeyIsReadBackAndNeverStoredInPlaintext() {
        val s = store()
        assertTrue(s.save("  $apiKey \n"))
        assertEquals(apiKey, (s.read() as ApiKeyStore.Read.Key).value)
        assertEquals(listOf(ApiKeyStore.FILE), dir().list()!!.toList())
        assertFalse(String(encrypted().readBytes(), Charsets.ISO_8859_1).contains(apiKey))
        // Another start of the app reads it too
        assertEquals(apiKey, (store().read() as ApiKeyStore.Read.Key).value)
        assertNull(s.problem)
    }

    @Test
    fun whenTheKeyIsGoneTheFileIsDeletedAndTheUserIsAskedToPasteAgain() {
        val s = store()
        s.save(apiKey)
        cipher.deleteKey() // e.g. the keystore lost it
        val r = s.read()
        assertEquals(ApiKeyStore.Read.Unreadable("KeyMissingException"), r)
        assertFalse(encrypted().exists())
        assertTrue(s.problem!!, s.problem!!.contains("貼り直して"))
        assertEquals(ApiKeyStore.Read.None, s.read())
        // Pasting it again works and clears the request
        assertTrue(s.save(apiKey))
        assertEquals(apiKey, (s.read() as ApiKeyStore.Read.Key).value)
        assertNull(s.problem)
    }

    @Test
    fun aDamagedFileIsDeletedAndItsKeyToo() {
        val s = store()
        s.save(apiKey)
        encrypted().writeBytes(byteArrayOf(9, 9, 9))
        assertTrue(s.read() is ApiKeyStore.Read.Unreadable)
        assertFalse(encrypted().exists())
        assertNull("the key that could not open it is deleted", cipher.key)
    }

    @Test
    fun aFailureTheKeystoreCallsTemporaryKeepsTheKeyForTheNextRead() {
        val s = store()
        s.save(apiKey)
        cipher.decryptFailure = IllegalStateException("busy")
        cipher.saysTemporary = true
        assertEquals(ApiKeyStore.Read.NotNow("IllegalStateException"), s.read())
        assertTrue(encrypted().exists())
        assertNotNull(cipher.key)
        assertTrue(s.problem!!, s.problem!!.contains("いま読めません"))
        // It clears up by itself: nothing had to be pasted again
        cipher.decryptFailure = null
        assertEquals(apiKey, (s.read() as ApiKeyStore.Read.Key).value)
        assertNull(s.problem)
    }

    @Test
    fun aFileThatCannotBeReadThisTimeIsKept() {
        for (failure in listOf(IOException("busy"), OutOfMemoryError())) {
            val s = store()
            s.save(apiKey)
            cipher.decryptFailure = failure
            assertTrue(s.read() is ApiKeyStore.Read.NotNow)
            assertTrue(encrypted().exists())
            assertNotNull(cipher.key)
            cipher.decryptFailure = null
        }
    }

    @Test
    fun aFailureNotKnownToBeTemporaryStillDeletes() {
        val s = store()
        s.save(apiKey)
        cipher.decryptFailure = IllegalStateException("broken")
        assertEquals(ApiKeyStore.Read.Unreadable("IllegalStateException"), s.read())
        assertFalse(encrypted().exists())
        assertNull(cipher.key)
    }

    /** The key is in the key store, and reading fails anyway: below API 33 the failure cannot say that it will pass. */
    private fun failingWithTheKeyThere() {
        store().save(apiKey)
        cipher.keyStillThere = true
        cipher.decryptFailure = IllegalStateException("keystore")
    }

    @Test
    fun aKeyThatIsStillThereIsNotDeletedAtTheFirstFailure() {
        failingWithTheKeyThere()
        val s = store()
        assertTrue(s.read() is ApiKeyStore.Read.NotNow)
        assertTrue(encrypted().exists())
        assertNotNull(cipher.key)
        assertNotNull(s.problem)
    }

    @Test
    fun afterAFewStartsOfTheAppItIsGivenUp() {
        failingWithTheKeyThere()
        repeat(ApiKeyStore.MAX_FAILED_RUNS) {
            assertTrue("start ${it + 1}", store().read() is ApiKeyStore.Read.NotNow)
            assertTrue(encrypted().exists())
        }
        assertTrue(store().read() is ApiKeyStore.Read.Unreadable)
        assertFalse(encrypted().exists())
        assertNull(cipher.key)
        assertEquals("nothing is left beside it", emptyList<String>(), dir().list()!!.toList())
    }

    @Test
    fun readingAgainInOneRunCountsOnce() {
        failingWithTheKeyThere()
        val s = store()
        repeat(ApiKeyStore.MAX_FAILED_RUNS * 3) { assertTrue(s.read() is ApiKeyStore.Read.NotNow) }
        assertTrue(encrypted().exists())
    }

    @Test
    fun aKeyThatComesBackStartsTheCountAgain() {
        failingWithTheKeyThere()
        repeat(ApiKeyStore.MAX_FAILED_RUNS) { store().read() }
        cipher.decryptFailure = null
        assertEquals(apiKey, (store().read() as ApiKeyStore.Read.Key).value)
        assertEquals(listOf(ApiKeyStore.FILE), dir().list()!!.toList())
        cipher.decryptFailure = IllegalStateException("keystore")
        assertTrue(store().read() is ApiKeyStore.Read.NotNow)
    }

    @Test
    fun aKeyThatIsGoneIsNotWaitedFor() {
        store().save(apiKey)
        cipher.keyStillThere = false
        cipher.decryptFailure = IllegalStateException("keystore")
        assertTrue(store().read() is ApiKeyStore.Read.Unreadable)
        assertFalse(encrypted().exists())
    }

    @Test
    fun aNewKeyForgetsTheCount() {
        failingWithTheKeyThere()
        repeat(ApiKeyStore.MAX_FAILED_RUNS) { store().read() }
        cipher.decryptFailure = null
        store().save("another-key-0000")
        assertEquals(listOf(ApiKeyStore.FILE), dir().list()!!.toList())
    }

    @Test
    fun whetherAKeyIsSavedIsKnownWithoutDecryptingIt() {
        val s = store()
        assertFalse(s.exists)
        s.save(apiKey)
        cipher.decryptFailure = IllegalStateException("not to be asked")
        assertTrue(s.exists)
        assertEquals("nothing was read", listOf(ApiKeyStore.FILE), dir().list()!!.toList())
        s.clear()
        assertFalse(s.exists)
    }

    @Test
    fun aPlaintextKeyFromBeforeCountsAsSaved() {
        assertFalse(store().exists)
        plain().writeText("$apiKey\n")
        assertTrue(store().exists)
    }

    @Test
    fun anEmptyPlaintextFileIsNoKey() {
        plain().writeText("")
        assertFalse(store().exists)
    }

    @Test
    fun pastingAgainWorksWhileTheOldKeyCannotBeRead() {
        val s = store()
        s.save(apiKey)
        cipher.decryptFailure = IOException("busy")
        s.read()
        assertTrue(s.save("new-key-0000"))
        assertNull(s.problem)
        cipher.decryptFailure = null
        assertEquals("new-key-0000", (s.read() as ApiKeyStore.Read.Key).value)
    }

    @Test
    fun aPlaintextKeyFromBeforeIsEncryptedAndItsFileDeleted() {
        plain().writeText("$apiKey\n")
        val s = store()
        assertEquals(apiKey, (s.read() as ApiKeyStore.Read.Key).value)
        assertFalse(plain().exists())
        assertTrue(encrypted().exists())
        assertEquals(apiKey, (store().read() as ApiKeyStore.Read.Key).value)
    }

    @Test
    fun aPlaintextKeyLeftNextToTheEncryptedOneIsDeleted() {
        val s = store()
        s.save(apiKey)
        plain().writeText("old-key")
        assertEquals(apiKey, (s.read() as ApiKeyStore.Read.Key).value)
        assertFalse(plain().exists())
    }

    @Test
    fun aPlaintextKeyThatCannotBeEncryptedStaysAndIsStillUsed() {
        plain().writeText(apiKey)
        cipher.broken = true
        assertEquals(apiKey, (store().read() as ApiKeyStore.Read.Key).value)
        assertTrue(plain().exists())
        assertFalse(encrypted().exists())
    }

    @Test
    fun aFailedSaveLeavesNothing() {
        cipher.broken = true
        val s = store()
        assertFalse(s.save(apiKey))
        assertTrue(dir().list()!!.isEmpty())
        assertFalse(s.save("   "))
    }

    @Test
    fun clearDeletesTheFilesAndTheKey() {
        val s = store()
        s.save(apiKey)
        plain().writeText("old-key")
        s.clear()
        assertTrue(dir().list()!!.isEmpty())
        assertNull(cipher.key)
        assertEquals(ApiKeyStore.Read.None, s.read())
    }

    @Test
    fun theKeyNeverShowsInToString() {
        assertFalse(ApiKeyStore.Read.Key(apiKey).toString().contains(apiKey))
    }
}
