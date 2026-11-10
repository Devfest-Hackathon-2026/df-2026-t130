// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The license texts as the app shows them, and that they exist to be put in the APK. */
class LicenseTextsTest {
    private val long = "x".repeat(60)

    @Test
    fun runningTextIsJoinedAcrossTheCutsOfThePage() {
        assertEquals(listOf("$long $long end"), licenseParagraphs("$long\n$long\nend"))
    }

    @Test
    fun theCommonIndentGoesSoThatIndentedLicensesAreJoinedToo() {
        assertEquals(listOf("$long end"), licenseParagraphs("  $long\n  end"))
    }

    @Test
    fun paragraphsStayApartAlsoWithWindowsLineEndsAndSpacesOnTheBlankLine() {
        assertEquals(listOf("one", "two", "three"), licenseParagraphs("one\r\n\r\ntwo\n  \nthree\n"))
    }

    @Test
    fun aHeadingWithARuleAListAndAnIndentedLineStayAsTheyAre() {
        assertEquals(listOf("$long\n-------"), licenseParagraphs("$long\n-------"))
        assertEquals(listOf("com.example:one\ncom.example:two"), licenseParagraphs("  com.example:one\n  com.example:two"))
        assertEquals(listOf("$long\n  https://example.com/"), licenseParagraphs("$long\n  https://example.com/"))
    }

    @Test
    fun everyTextOnTheScreenIsThereToBeCopiedIntoTheApk() {
        // Unit tests run in the module's directory; the sources are the ones build.gradle.kts names
        for (license in LICENSE_FILES) {
            val source = when (license.file) {
                "NOTICE" -> File("NOTICE")
                "LICENSE" -> File("../../LICENSE")
                else -> File("licenses", license.file)
            }
            assertTrue(license.file, source.isFile)
        }
    }
}
