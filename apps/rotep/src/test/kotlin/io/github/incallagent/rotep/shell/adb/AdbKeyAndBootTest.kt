// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell.adb

import io.github.incallagent.rotep.app.SecretCipher
import io.github.incallagent.rotep.shell.HelperProtocol
import org.junit.Assert.assertArrayEquals
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
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.util.Base64

class AdbKeyAndBootTest {
    @get:Rule
    val folder = TemporaryFolder()

    // --- the adb form of the public key ------------------------------------------------------------

    @Test
    fun thePublicKeyStructHasTheFieldsAdbdReads() {
        val keys = AdbKeyStore.generate()
        val struct = AdbKeyFormat.publicKeyStruct(keys.publicKey)
        assertEquals(4 + 4 + 256 + 256 + 4, struct.size)
        val buffer = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, buffer.getInt())
        val n0inv = buffer.getInt()
        val modulusBytes = ByteArray(256).also { buffer.get(it) }
        val rrBytes = ByteArray(256).also { buffer.get(it) }
        val exponent = buffer.getInt()

        val modulus = BigInteger(1, modulusBytes.reversedArray())
        assertEquals(keys.publicKey.modulus, modulus)
        val r32 = BigInteger.ONE.shiftLeft(32)
        val low = modulus.mod(r32)
        assertEquals((r32 - BigInteger.ONE).toLong(), low.multiply(BigInteger.valueOf(n0inv.toLong() and 0xffffffffL)).mod(r32).toLong())
        val r = BigInteger.ONE.shiftLeft(2048)
        assertEquals(r.multiply(r).mod(modulus), BigInteger(1, rrBytes.reversedArray()))
        assertEquals(65537, exponent)
    }

    /**
     * The form of a key made by `adb keygen` of the Android SDK (platform-tools 36.0.2), which is the
     * reference for this format. A throwaway key: only its public part is here.
     */
    @Test
    fun theStructIsByteForByteWhatAdbItselfWrites() {
        val fromAdb = Base64.getDecoder().decode(ADB_KEYGEN_PUBLIC_KEY)
        assertEquals(524, fromAdb.size)
        val fields = ByteBuffer.wrap(fromAdb).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, fields.getInt())
        fields.getInt()
        val modulus = BigInteger(1, ByteArray(256).also { fields.get(it) }.reversedArray())
        fields.position(fields.position() + 256)
        val exponent = fields.getInt()
        val key = java.security.KeyFactory.getInstance("RSA")
            .generatePublic(java.security.spec.RSAPublicKeySpec(modulus, BigInteger.valueOf(exponent.toLong())))
        assertArrayEquals(fromAdb, AdbKeyFormat.publicKeyStruct(key as java.security.interfaces.RSAPublicKey))
    }

    @Test
    fun thePeerInfoIsTheBlockAdbdExpects() {
        val keys = AdbKeyStore.generate()
        val info = AdbKeyFormat.peerInfo(keys.publicKey, "AITEL")
        assertEquals(8192, info.size)
        assertEquals(0.toByte(), info[0])
        val text = String(info, 1, info.size - 1).trimEnd('\u0000')
        val parts = text.split(" ")
        assertEquals(2, parts.size)
        assertEquals("AITEL", parts[1])
        assertArrayEquals(AdbKeyFormat.publicKeyStruct(keys.publicKey), Base64.getDecoder().decode(parts[0]))
        // The block is padded with zeros, so a C string ends there.
        assertEquals(0.toByte(), info[1 + text.length])
    }

    @Test
    fun theCertificateIsSelfSignedAndNamesItsOwnKey() {
        val keys = AdbKeyStore.generate()
        val cert = keys.certificate
        cert.verify(keys.publicKey)
        cert.checkValidity()
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertEquals(3, cert.version)
    }

    // --- the file with the key ----------------------------------------------------------------------

    @Test
    fun aKeyIsMadeOnceAndReadBack() {
        val store = AdbKeyStore(folder.root, FlipCipher())
        val first = store.loadOrCreate()
        val second = AdbKeyStore(folder.root, FlipCipher()).loadOrCreate()
        assertArrayEquals(first.privateKey.encoded, second.privateKey.encoded)
        assertArrayEquals(first.certificate.encoded, second.certificate.encoded)
    }

    @Test
    fun theFileNeverHoldsTheKeyInTheClear() {
        val keys = AdbKeyStore(folder.root, FlipCipher()).loadOrCreate()
        val bytes = File(folder.root, AdbKeyStore.FILE).readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(String(keys.privateKey.encoded.copyOf(32), Charsets.ISO_8859_1)))
    }

    @Test
    fun aFileThatCannotBeDecryptedIsReplacedByANewKey() {
        val cipher = FlipCipher()
        val store = AdbKeyStore(folder.root, cipher)
        val first = store.loadOrCreate()
        cipher.failDecrypt = GeneralSecurityException("gone")
        val second = store.loadOrCreate()
        assertFalse(first.privateKey.encoded.contentEquals(second.privateKey.encoded))
    }

    @Test
    fun aKeystoreThatIsBusyDoesNotThrowTheKeyAway() {
        val cipher = FlipCipher()
        val store = AdbKeyStore(folder.root, cipher)
        val first = store.loadOrCreate()
        cipher.failDecrypt = IOException("busy")
        try {
            store.loadOrCreate()
            throw AssertionError("expected the failure to come through")
        } catch (expected: IOException) {
            assertEquals("busy", expected.message)
        }
        cipher.failDecrypt = null
        assertArrayEquals(first.privateKey.encoded, store.loadOrCreate().privateKey.encoded)
    }

    @Test
    fun readingOnlyNeverReplacesTheKey() {
        val cipher = FlipCipher()
        val store = AdbKeyStore(folder.root, cipher)
        assertNull(store.loadExisting())
        val first = store.loadOrCreate()
        assertArrayEquals(first.privateKey.encoded, store.loadExisting()!!.privateKey.encoded)
        cipher.failDecrypt = GeneralSecurityException("not ready")
        val thrown = runCatching { store.loadExisting() }.exceptionOrNull()
        assertTrue(thrown is GeneralSecurityException)
        cipher.failDecrypt = null
        // The failure replaced nothing: the same key is still there.
        assertArrayEquals(first.privateKey.encoded, store.loadExisting()!!.privateKey.encoded)
    }

    // --- the lines that start the helper --------------------------------------------------------------

    @Test
    fun theScriptIsPackagedAsAnAssetSoTheAppRunsTheSameTextAsAPc() {
        // Reading the asset itself needs Android; the build file is what puts it there.
        val gradle = moduleFile("build.gradle.kts").readText()
        assertTrue(gradle.contains("assets.srcDir(\"helper\")"))
        assertTrue(moduleFile("helper/${HelperBoot.SCRIPT_ASSET}").isFile)
    }

    @Test
    fun theScriptCarriesThePartsThatMakeTheStartSafe() {
        val script = moduleFile("helper/rotep-helper.sh").readText()
        // pm path without the "package:" prefix.
        assertTrue(script.contains("pm path \"\$PKG\" | grep base.apk | head -n 1 | cut -d: -f2"))
        // A session of its own, output away from the (soon closed) adb stream, and in the background.
        assertTrue(script.contains("setsid app_process /system/bin --nice-name=\"\$NAME\" \"\$MAIN\""))
        assertTrue(script.contains("</dev/null >/dev/null 2>&1 &"))
        assertTrue(script.contains("NAME=${HelperProtocol.PROCESS_NAME}"))
        assertTrue(script.contains("MAIN=${HelperProtocol.MAIN_CLASS}"))
    }

    @Test
    fun theScriptOutcomeIsReadFromExitCodeAndOutput() {
        assertTrue(HelperBoot.scriptStarted(AdbShellResult("rotep-helper: started (check with: pidof rotep-helper)\n", 0)))
        assertFalse(HelperBoot.scriptStarted(AdbShellResult("rotep-helper: io.github.incallagent.rotep is not installed\n", 1)))
        assertFalse(HelperBoot.scriptStarted(AdbShellResult("", null)))
    }

    /** Gradle runs unit tests with the module folder (apps/rotep) as the working directory. */
    private fun moduleFile(path: String): File {
        val direct = File(path)
        if (direct.exists()) return direct
        return File("apps/rotep/$path")
    }

    @Test
    fun theAppOpsLinesAreForTheFullyAutomaticControl() {
        assertEquals("appops set io.github.incallagent.rotep MANAGE_ONGOING_CALLS allow", HelperBoot.appOpsSetCommand())
        assertEquals("appops get io.github.incallagent.rotep MANAGE_ONGOING_CALLS", HelperBoot.appOpsGetCommand())
    }

    @Test
    fun theAppOpsOutputIsRead() {
        assertTrue(HelperBoot.appOpsAllowed("MANAGE_ONGOING_CALLS: allow\n"))
        assertTrue(HelperBoot.appOpsAllowed("MANAGE_ONGOING_CALLS: allow; time=+2d\n"))
        assertFalse(HelperBoot.appOpsAllowed("MANAGE_ONGOING_CALLS: ignore\n"))
        assertFalse(HelperBoot.appOpsAllowed("No operations.\n"))
    }

    // --- ports and codes ------------------------------------------------------------------------------

    @Test
    fun aServiceCountsWhenItIsOnThisDevice() {
        assertTrue(AdbPorts.isOnThisDevice(listOf("192.168.1.5"), setOf("127.0.0.1", "192.168.1.5")))
        assertTrue(AdbPorts.isOnThisDevice(listOf("fe80::1%wlan0"), setOf("fe80::1")))
        assertFalse(AdbPorts.isOnThisDevice(listOf("192.168.1.9"), setOf("127.0.0.1", "192.168.1.5")))
        assertFalse(AdbPorts.isOnThisDevice(emptyList(), setOf("192.168.1.5")))
    }

    @Test
    fun portsAreChecked() {
        assertEquals(37123, AdbPorts.parsePort(" 37123 "))
        assertNull(AdbPorts.parsePort("0"))
        assertNull(AdbPorts.parsePort("70000"))
        assertNull(AdbPorts.parsePort("abc"))
    }

    @Test
    fun codesAreSixDigits() {
        assertEquals("123456", AdbPorts.parseCode("123456"))
        assertEquals("123456", AdbPorts.parseCode(" 123 456 "))
        assertNull(AdbPorts.parseCode("12345"))
        assertNull(AdbPorts.parseCode("1234567"))
        assertNull(AdbPorts.parseCode("12345a"))
    }

    // --- secrets stay out of the logs ---------------------------------------------------------------

    /**
     * The pairing code, the key and the exchanged values must never be logged or put on the screen
     * (ADR-021). Log calls and screen events in shell/adb hold fixed texts, port numbers and exception
     * class names only, so this scan reads every such line.
     */
    @Test
    fun noLogOrEventLineMentionsASecret() {
        val forbidden = Regex("""\$\{?(code|codeText|key|keys|keyMaterial|exported|peerInfo|password|privateKey|mine|theirs|pseudoRandomKey)\b""")
        val sources = moduleFile("src/main/kotlin/io/github/incallagent/rotep/shell/adb")
        val logged = Regex("""\b(Log\.[a-z]|event)\(""")
        val hits = sources.listFiles()!!.filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().filter { logged.containsMatchIn(it) && forbidden.containsMatchIn(it) }.map { "${file.name}: $it" }
        }
        assertEquals(emptyList<String>(), hits)
    }

    /** Not real encryption: reverses the bytes, which is enough to tell plain from encrypted here. */
    private class FlipCipher : SecretCipher {
        var failDecrypt: Throwable? = null

        override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray()

        override fun decrypt(data: ByteArray): ByteArray {
            failDecrypt?.let { throw it }
            return data.reversedArray()
        }

        override fun deleteKey() = Unit

        override fun isTemporary(failure: Throwable): Boolean = failure is IOException
    }

    private companion object {
        // The first field of the line `adb keygen` writes to its .pub file (see the test that uses it).
        const val ADB_KEYGEN_PUBLIC_KEY =
            "QAAAAAFB8v//QHHM9SBQAsXidfs2sL25pYOatkgPso37S4Ke9cvftF2WbKlTU46VLm6msYWu0z+803UYjH7i71ySiiWQ7/Ue" +
                "a8tX3wnCcXvzvh2BwsifFemBdOfrtFBXAuIhuglZpzcGmuOgp+9XsrSDIXc7igJ8ZD0H1KY1HGh7evRizi538ahbN58L/PGY" +
                "db596FofBNCT6rSEOB9DTbBuxeoywg1C3mGyGgUs3pNPC5nPJgeVrHdOshdBxNXLl5iVkj1NMtlrE0CQcfG3CUaBUaUJQ4az" +
                "TW1r5U9Js1ApGHHFe1rrKFXwQi8rOGeVacc+Y8Ec2Y2eBQRBrn8/9v6Jwyi8MdS3e32FLYQiMTrVjIoDLB/nwF9d6Hn85bNh" +
                "306byoVm1B/j0hbSqByObpqaRIwJb60paQ6cKc5tV2srr0fF/4mmCNFZmSIUXUXJqi/0Ww9GJeZF00nc6+DH9TIhQqTeYe7i" +
                "2oTmKx05fC8/InhYRYwilfvKXtOY3T4YZWdttmSwT6UMhT9gQ20fu0I9R1ZjI6CR6tNgaBCOnqv/Mw6RlJ3w2EEWSZL2okhb" +
                "4u+O8y5UXuBsN9n+IjiVXf+7XLQmWPud/RZppCExpweeDBDYY0I9SNOpJFMbM+8qHyGojkCRVQxHWLmUGZhVeI9TCjQN0o7w" +
                "ZS5EeFjwGwKKj61hkCQMQgEAAQA="
    }
}
