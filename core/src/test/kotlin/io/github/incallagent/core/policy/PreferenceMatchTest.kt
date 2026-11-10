// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.core.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceMatchTest {
    private val nonSmoking = Preference("禁煙席", required = true)

    @Test
    fun sameOrSlightlyDifferentWordingMatches() {
        assertTrue(PreferenceMatch.isConfirmed(nonSmoking, listOf("禁煙席")))
        assertTrue(PreferenceMatch.isConfirmed(nonSmoking, listOf("禁煙")))
        assertTrue(PreferenceMatch.isConfirmed(nonSmoking, listOf("禁煙席あり")))
        assertTrue(PreferenceMatch.isConfirmed(nonSmoking, listOf("禁煙　席")))
    }

    @Test
    fun otherThingsDoNotMatch() {
        assertFalse(PreferenceMatch.isConfirmed(nonSmoking, emptyList()))
        assertFalse(PreferenceMatch.isConfirmed(nonSmoking, listOf("喫煙席")))
        assertFalse(PreferenceMatch.isConfirmed(nonSmoking, listOf("個室")))
        assertFalse("one character alone is too vague", PreferenceMatch.isConfirmed(nonSmoking, listOf("席")))
        assertFalse(PreferenceMatch.isConfirmed(Preference(" ", required = true), listOf("禁煙席")))
    }

    @Test
    fun whatTheAgentAddsCanTakeTheWishBack() {
        val refusals = listOf(
            "禁煙席なし", "禁煙席はありません", "禁煙席は満席", "禁煙席以外", "禁煙席不可",
            "禁煙席ではなく喫煙席", "禁煙席は確認中", "禁煙席は取れなかった", "禁煙席はご用意できかねます",
            "禁煙席はいっぱいです", "禁煙席は無理です", "禁煙席は取れず", "禁煙席はお断りしています",
        )
        for (said in refusals) assertFalse(said, PreferenceMatch.isConfirmed(nonSmoking, listOf(said)))
    }

    @Test
    fun aPoliteYesIsNotARefusal() {
        for (said in listOf("禁煙席で問題ありません", "禁煙席は問題なくご用意できます", "禁煙席で構いません", "禁煙席をご用意できます")) {
            assertTrue(said, PreferenceMatch.isConfirmed(nonSmoking, listOf(said)))
        }
    }

    @Test
    fun aWishThatIsItselfANegationCanBeConfirmed() {
        val noShrimp = Preference("えびなし", required = true)
        assertTrue(PreferenceMatch.isConfirmed(noShrimp, listOf("えびなし")))
        assertTrue(PreferenceMatch.isConfirmed(noShrimp, listOf("えびなしで対応")))
        assertFalse(PreferenceMatch.isConfirmed(noShrimp, listOf("えびなしはできません")))
    }

    @Test
    fun anyOtherPartOfTheWishIsNotEnough() {
        fun wish(text: String) = Preference(text, required = true)
        assertFalse(PreferenceMatch.isConfirmed(wish("えびアレルギー対応"), listOf("対応")))
        assertFalse(PreferenceMatch.isConfirmed(wish("えびアレルギー対応"), listOf("アレルギー対応")))
        assertFalse(PreferenceMatch.isConfirmed(wish("えび抜き"), listOf("えび")))
        assertFalse(PreferenceMatch.isConfirmed(wish("グルテンフリー"), listOf("グルテン")))
        assertFalse(PreferenceMatch.isConfirmed(wish("2階の個室"), listOf("個室")))
    }

    @Test
    fun aWordThatOnlyNamesTheKindOfThingMayBeLeftOut() {
        fun wish(text: String) = Preference(text, required = true)
        assertTrue(PreferenceMatch.isConfirmed(wish("窓際の席"), listOf("窓際")))
        assertTrue(PreferenceMatch.isConfirmed(wish("カウンター席"), listOf("カウンター")))
        assertTrue(PreferenceMatch.isConfirmed(wish("車いす対応"), listOf("車いす")))
    }
}
