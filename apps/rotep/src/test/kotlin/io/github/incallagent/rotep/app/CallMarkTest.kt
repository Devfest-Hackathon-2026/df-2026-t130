// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallMarkTest {
    private class Memory : CallMark.Memo {
        override var shop: String? = null
    }

    private val memo = Memory()
    private val mark = CallMark(memo)

    @Test
    fun nothingWasPlacedSoThereIsNothingToSay() {
        assertNull(mark.takeLeftBehind())
    }

    @Test
    fun aCallThatEndedLeavesNothing() {
        mark.placed("テスト食堂")
        mark.ended()
        assertNull(mark.takeLeftBehind())
    }

    @Test
    fun theMarkOfACallOfThisProcessIsNotOneLeftBehind() {
        // The call shows as over a moment before it is reported, and the screen can come back in between
        mark.placed("テスト食堂")
        assertNull(mark.takeLeftBehind())
        assertEquals("kept until the call is reported", "テスト食堂", memo.shop)
        mark.ended()
        assertNull(memo.shop)
    }

    @Test
    fun aMarkFromBeforeThisProcessIsToldAlsoWhenTheProcessHasBeenRunningForAWhile() {
        CallMark(memo).placed("テスト食堂")
        assertTrue(mark.takeLeftBehind()!!.contains("テスト食堂"))
    }

    @Test
    fun aCallLeftBehindIsToldOnceAndSaysToAskTheShop() {
        mark.placed("テスト食堂")
        val said = CallMark(memo).takeLeftBehind()!!
        assertTrue(said, said.contains("テスト食堂"))
        assertTrue(said, said.contains("結果が分かりません"))
        assertTrue(said, said.contains("お店に確かめてください"))
        assertNull("once", CallMark(memo).takeLeftBehind())
        assertEquals(null, memo.shop)
    }
}
