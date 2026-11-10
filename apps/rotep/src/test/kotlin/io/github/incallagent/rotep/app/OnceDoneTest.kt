// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OnceDoneTest {
    @Test
    fun aStepThatWentThroughIsNotRunAgain() {
        val once = OnceDone()
        var runs = 0
        repeat(3) { once.run { runs++ } }
        assertEquals(1, runs)
    }

    @Test
    fun aStepThatFailedIsRunAgainByTheNextCaller() {
        val once = OnceDone()
        var runs = 0
        val failure = runCatching {
            once.run {
                runs++
                error("half way")
            }
        }
        assertEquals("the first caller hears of it", "half way", failure.exceptionOrNull()?.message)
        once.run { runs++ }
        once.run { runs++ }
        assertEquals(2, runs)
    }
}
