// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

class HelperProtocolTest {
    // --- who may register -----------------------------------------------------------------------

    @Test
    fun onlyTheShellAndRootMayRegisterAHelper() {
        assertTrue(RegistrationPolicy.accepts(2000))
        assertTrue(RegistrationPolicy.accepts(0))
    }

    @Test
    fun anyOtherCallerIsRefused() {
        // An ordinary app, another app, the system, and a bad value.
        assertFalse(RegistrationPolicy.accepts(10231))
        assertFalse(RegistrationPolicy.accepts(10001))
        assertFalse(RegistrationPolicy.accepts(1000))
        assertFalse(RegistrationPolicy.accepts(1))
        assertFalse(RegistrationPolicy.accepts(-1))
    }

    // --- the version ------------------------------------------------------------------------------

    @Test
    fun theHelperVersionFollowsTheInstallTimeInSeconds() {
        assertEquals(1_700_000_000, HelperVersion.fromLastUpdate(1_700_000_000_000L))
        assertEquals(1_700_000_000, HelperVersion.fromLastUpdate(1_700_000_000_999L))
    }

    @Test
    fun aNewInstallGivesANewHelperVersion() {
        val first = HelperVersion.fromLastUpdate(1_700_000_000_000L)
        val reinstall = HelperVersion.fromLastUpdate(1_700_000_061_000L)
        assertTrue(reinstall != first)
    }

    @Test
    fun theHelperVersionStaysInRange() {
        assertEquals(1, HelperVersion.fromLastUpdate(0L))
        assertEquals(1, HelperVersion.fromLastUpdate(-5L))
        assertEquals(Int.MAX_VALUE, HelperVersion.fromLastUpdate(Long.MAX_VALUE))
    }

    @Test
    fun aHelperWithTheInstalledVersionIsTaken() {
        assertTrue(VersionGate.accepts(1_700_000_000, 1_700_000_000))
    }

    @Test
    fun aHelperFromAnOlderInstallIsRefused() {
        assertFalse(VersionGate.accepts(1_700_000_061, 1_700_000_000))
    }

    @Test
    fun aHelperThatSentNoVersionIsRefused() {
        assertFalse(VersionGate.accepts(1_700_000_000, null))
    }

    @Test
    fun theVersionMustMatchExactlyNotJustNearly() {
        assertFalse(VersionGate.accepts(1_700_000_000, 1_700_000_001))
    }

    @Test
    fun helperInfoCarriesThePidTheUidAndTheVersion() {
        val text = HelperInfoText.format(pid = 4321, uid = 2000, version = 1_700_000_000)
        assertEquals("pid=4321 uid=2000 version=1700000000", text)
        assertEquals(1_700_000_000, HelperInfoText.parseVersion(text))
    }

    @Test
    fun aHelperInfoWithoutAVersionHasNone() {
        // A helper from an older build: the app cannot tell its version, so the gate refuses it.
        assertNull(HelperInfoText.parseVersion("pid=4321 uid=2000"))
        assertNull(HelperInfoText.parseVersion(""))
        assertNull(HelperInfoText.parseVersion(null))
        assertFalse(VersionGate.accepts(1_700_000_000, HelperInfoText.parseVersion("pid=4321 uid=2000")))
    }

    @Test
    fun aBadVersionTextIsNotAVersion() {
        assertNull(HelperInfoText.parseVersion("pid=1 uid=2000 version=abc"))
        assertNull(HelperInfoText.parseVersion("pid=1 uid=2000 version="))
        assertNull(HelperInfoText.parseVersion("pid=1 uid=2000 xversion=5"))
        // Too big for an Int.
        assertNull(HelperInfoText.parseVersion("pid=1 uid=2000 version=99999999999"))
    }

    // --- the helper checks that the provider is the app's ---------------------------------------

    @Test
    fun theHelperHandsItsBinderOnlyToTheAppsOwnProvider() {
        assertTrue(ProviderOwnerPolicy.accepts(providerUid = 10231, appUid = 10231))
        assertFalse(ProviderOwnerPolicy.accepts(providerUid = 10232, appUid = 10231))
    }

    @Test
    fun anUnreadableOwnerOrUnknownAppMeansNo() {
        assertFalse(ProviderOwnerPolicy.accepts(providerUid = null, appUid = 10231))
        assertFalse(ProviderOwnerPolicy.accepts(providerUid = -1, appUid = -1))
        assertFalse(ProviderOwnerPolicy.accepts(providerUid = 10231, appUid = -1))
    }

    // --- how long the helper lives without the app, and how it tries again ------------------------

    @Test
    fun theHelperTriesAgainAfterTwoSecondsThenEveryFiveSeconds() {
        assertEquals(2_000L, HelperLifetime.retryDelayMs(1))
        assertEquals(5_000L, HelperLifetime.retryDelayMs(2))
        assertEquals(5_000L, HelperLifetime.retryDelayMs(3))
        assertEquals(5_000L, HelperLifetime.retryDelayMs(1_000))
    }

    @Test
    fun aBadTryCountDoesNotMakeTheWaitLongerThanTheFirstOne() {
        assertEquals(2_000L, HelperLifetime.retryDelayMs(0))
        assertEquals(2_000L, HelperLifetime.retryDelayMs(-3))
    }

    @Test
    fun theHelperEndsAfterTenMinutesWithoutTheApp() {
        assertEquals(10 * 60 * 1000L, HelperLifetime.DISCONNECT_LIMIT_MS)
        assertFalse(HelperLifetime.shouldEnd(0L))
        assertFalse(HelperLifetime.shouldEnd(9 * 60 * 1000L + 59_999L))
        assertTrue(HelperLifetime.shouldEnd(10 * 60 * 1000L))
        assertTrue(HelperLifetime.shouldEnd(60 * 60 * 1000L))
    }

    @Test
    fun theHelperLooksAtItsStateMoreOftenThanItTriesAgain() {
        assertTrue(HelperLifetime.POLL_MS <= HelperLifetime.FIRST_RETRY_MS)
    }

    // --- the commands the person runs -------------------------------------------------------------

    @Test
    fun theCommandsPushTheScriptAndRunItWithAdbShell() {
        assertEquals("adb push apps/rotep/helper/rotep-helper.sh /data/local/tmp/rotep-helper.sh", LaunchCommand.push())
        assertEquals("adb shell sh /data/local/tmp/rotep-helper.sh", LaunchCommand.run())
        assertEquals("adb shell sh /data/local/tmp/rotep-helper.sh fg", LaunchCommand.foreground())
        assertEquals(LaunchCommand.push() + "\n" + LaunchCommand.run(), LaunchCommand.build())
    }

    @Test
    fun theCommandsHaveNothingThatPowerShellCmdOrBashWouldChange() {
        // The reason for the script: no quoting is needed, so the same text works in every PC shell.
        for (command in listOf(LaunchCommand.push(), LaunchCommand.run(), LaunchCommand.foreground())) {
            for (c in listOf('$', '|', '<', '>', '&', '\'', '"', '`', ';', '(', ')', '*', '?', '%', '^', '\\')) {
                assertFalse("'$command' must not contain '$c'", command.contains(c))
            }
        }
    }

    @Test
    fun noTokenOrSecretIsPartOfTheCommands() {
        // The commands are the same text for every build and are shown on the screen: they hold
        // nothing that grants access (for the later in-app adb: no token in arguments).
        val all = (LaunchCommand.build() + LaunchCommand.foreground()).lowercase()
        for (word in listOf("token", "secret", "key=", "password")) {
            assertFalse("the commands must not contain '$word'", all.contains(word))
        }
    }

    @Test
    fun theScriptInTheRepositoryIsWhereTheCommandSaysItIs() {
        assertTrue(moduleFile("helper/rotep-helper.sh").isFile)
        assertEquals("apps/rotep/helper/rotep-helper.sh", LaunchCommand.SCRIPT_IN_REPO)
    }

    @Test
    fun theScriptRunsTheHelperMainClassAsADetachedAppProcess() {
        val script = moduleFile("helper/rotep-helper.sh").readText()
        assertTrue(script.startsWith("#!/system/bin/sh\n"))
        assertEquals(HelperProtocol.APP_PACKAGE, assignment(script, "PKG"))
        assertEquals(HelperProtocol.MAIN_CLASS, assignment(script, "MAIN"))
        assertEquals(HelperProtocol.PROCESS_NAME, assignment(script, "NAME"))
        // The APK path is looked up on the device, never written into the script.
        assertTrue(script.contains("APK=\$(pm path \"\$PKG\" | grep base.apk | head -n 1 | cut -d: -f2)"))
        assertFalse(script.contains("/data/app"))
        assertTrue(script.contains("[ -z \"\$APK\" ]"))
        assertTrue(
            script.contains(
                "CLASSPATH=\"\$APK\" setsid app_process /system/bin --nice-name=\"\$NAME\" \"\$MAIN\" </dev/null >/dev/null 2>&1 &",
            ),
        )
    }

    @Test
    fun theScriptCanRunTheHelperInTheForegroundToShowItsErrors() {
        val script = moduleFile("helper/rotep-helper.sh").readText()
        assertTrue(script.contains("[ \"\$1\" = \"fg\" ]"))
        assertTrue(script.contains("exec app_process /system/bin --nice-name=\"\$NAME\" \"\$MAIN\""))
    }

    @Test
    fun theScriptHeaderShowsTheSameCommandsAsTheScreen() {
        val script = moduleFile("helper/rotep-helper.sh").readText()
        assertTrue(script.contains(LaunchCommand.push()))
        assertTrue(script.contains(LaunchCommand.run()))
        assertTrue(script.contains(LaunchCommand.foreground()))
        assertTrue(script.contains("pidof ${HelperProtocol.PROCESS_NAME}"))
    }

    @Test
    fun theScriptHasUnixLineEndingsBecauseTheDeviceShellCannotReadWindowsOnes() {
        val bytes = moduleFile("helper/rotep-helper.sh").readBytes()
        assertFalse("the script must not contain a carriage return", bytes.contains('\r'.code.toByte()))
    }

    // --- the constants stay in step with the manifest and the build -----------------------------

    @Test
    fun theAuthorityIsTheAppPackagePlusHelper() {
        assertEquals("io.github.incallagent.rotep.helper", HelperProtocol.AUTHORITY)
    }

    @Test
    fun theManifestDeclaresTheProviderWithTheSameAuthorityAndTheProtectingPermission() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        // Written as ${applicationId}.helper in the manifest; applicationId is checked below.
        assertTrue(manifest.contains("android:name=\".shell.HelperProvider\""))
        assertTrue(manifest.contains("android:authorities=\"\${applicationId}.helper\""))
        assertTrue(manifest.contains("android:permission=\"android.permission.INTERACT_ACROSS_USERS_FULL\""))
        assertTrue(manifest.contains("android:exported=\"true\""))
    }

    @Test
    fun theApplicationIdIsTheFixedPackageTheScriptAndTheAuthorityUse() {
        val gradle = moduleFile("build.gradle.kts").readText()
        val applicationId = Regex("""applicationId\s*=\s*"([^"]+)"""").find(gradle)?.groupValues?.get(1)
        assertEquals(HelperProtocol.APP_PACKAGE, applicationId)
        // A suffix would change the real authority and the package that `pm path` looks up.
        assertFalse(gradle.contains("applicationIdSuffix"))
    }

    @Test
    fun noShizukuDependencyProviderOrQueryIsLeft() {
        val gradle = moduleFile("build.gradle.kts").readText()
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        assertFalse(gradle.contains("dev.rikka.shizuku"))
        assertFalse(manifest.contains("rikka.shizuku"))
        assertFalse(manifest.contains("moe.shizuku"))
        assertFalse(manifest.contains("<queries>"))
        assertFalse(moduleFile("NOTICE").readText().contains("Shizuku-API"))
    }

    @Test
    fun theMainClassExistsAndHasAStaticMainThatAppProcessCanCall() {
        val cls = Class.forName(HelperProtocol.MAIN_CLASS, false, javaClass.classLoader)
        val main = cls.getMethod("main", Array<String>::class.java)
        assertNotNull(main)
        assertTrue("main must be static so app_process can call it", Modifier.isStatic(main.modifiers))
        assertEquals(Void.TYPE, main.returnType)
    }

    /** The value of a line `NAME=value` in a shell script, or null. */
    private fun assignment(script: String, name: String): String? =
        Regex("""^$name=(\S+)$""", RegexOption.MULTILINE).find(script)?.groupValues?.get(1)

    /** Gradle runs unit tests with the module folder (apps/rotep) as the working directory. */
    private fun moduleFile(path: String): File {
        val direct = File(path)
        if (direct.exists()) return direct
        val fromRoot = File("apps/rotep/$path")
        check(fromRoot.exists()) { "cannot find $path (working directory ${File(".").absoluteFile})" }
        return fromRoot
    }
}
