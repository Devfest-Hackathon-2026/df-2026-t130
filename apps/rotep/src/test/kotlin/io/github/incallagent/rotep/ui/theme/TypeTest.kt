// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.theme

import androidx.compose.material3.Typography
import org.junit.Assert.assertEquals
import org.junit.Test

class TypeTest {
    @Test
    fun runningTextBreaksByThePhraseAndTheStrictRules() {
        val t = Typography().withJapaneseLineBreaks()
        for (style in listOf(t.bodyLarge, t.bodyMedium, t.bodySmall, t.labelLarge, t.labelMedium, t.titleMedium, t.titleLargeEmphasized)) {
            assertEquals(BodyLineBreak, style.lineBreak)
        }
        // Only the line breaking changes: sizes stay Material's
        assertEquals(Typography().bodyMedium.fontSize, t.bodyMedium.fontSize)
    }
}
