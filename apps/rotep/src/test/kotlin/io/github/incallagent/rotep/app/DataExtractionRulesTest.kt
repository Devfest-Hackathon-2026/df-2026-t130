// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DataExtractionRulesTest {
    private fun moduleFile(path: String) = listOf(File(path), File("apps/rotep", path)).first { it.isFile }

    private val rules = moduleFile("src/main/res/xml/data_extraction_rules.xml").readText()

    private fun section(name: String) = rules.substringAfter("<$name>", "").substringBefore("</$name>")

    @Test
    fun theManifestUsesTheRulesAndKeepsTheBackupOff() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }

    @Test
    fun nothingGoesToABackupOrToAnotherPhone() {
        // A section left out counts as everything allowed
        for (name in listOf("cloud-backup", "device-transfer")) {
            val rulesOfSection = section(name)
            assertFalse(name, rulesOfSection.contains("<include"))
            // The system goes through each domain by itself: root alone would not cover the others
            for (domain in DOMAINS) {
                assertTrue("$name $domain", rulesOfSection.contains("<exclude domain=\"$domain\" path=\".\""))
            }
        }
    }

    private companion object {
        val DOMAINS = listOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
    }
}
