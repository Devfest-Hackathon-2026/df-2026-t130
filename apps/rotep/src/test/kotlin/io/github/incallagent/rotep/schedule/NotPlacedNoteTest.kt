// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotPlacedNoteTest {
    private val note = NotPlacedNote(object : NotPlacedNote.Memo {
        override var text: String? = null
    })

    @Test
    fun aNoteIsToldOnce() {
        note.write("ネットワークにつながっていなかったので、電話をかけませんでした")
        assertEquals("ネットワークにつながっていなかったので、電話をかけませんでした", note.take())
        assertNull(note.take())
    }

    @Test
    fun withoutANoteThereIsNothingToTell() {
        assertNull(note.take())
    }

    @Test
    fun theLastOneIsTheOneThatCounts() {
        note.write("一つ目")
        note.write("二つ目")
        assertEquals("二つ目", note.take())
    }
}
