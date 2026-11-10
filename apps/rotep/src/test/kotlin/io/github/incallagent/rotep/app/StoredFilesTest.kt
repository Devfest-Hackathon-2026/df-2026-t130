// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class StoredFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val target get() = File(tmp.root, "saved")
    private val temp get() = File(tmp.root, "saved.tmp")

    @Test
    fun theNewContentsTakeThePlaceOfTheOld() {
        target.writeText("old")
        replaceFile(temp, target, "new".toByteArray())
        assertEquals("new", target.readText())
        assertFalse("nothing is left beside it", temp.exists())
    }

    @Test
    fun aFirstSaveMakesTheFile() {
        replaceFile(temp, target, "first".toByteArray())
        assertEquals("first", target.readText())
    }

    @Test
    fun whenTheNewFileCannotBeMadeTheOldOneStays() {
        target.writeText("old")
        temp.mkdir()
        val failed = try {
            replaceFile(temp, target, "new".toByteArray())
            false
        } catch (_: IOException) {
            true
        }
        assertTrue(failed)
        assertEquals("old", target.readText())
    }
}
