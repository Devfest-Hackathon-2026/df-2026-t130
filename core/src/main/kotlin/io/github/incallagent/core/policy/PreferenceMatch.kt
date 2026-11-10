// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.core.policy

/**
 * Whether a required [Preference] is among what the shop confirmed, as reported by the speaking
 * agent. After white space is removed, the agent's wording may differ from the policy text in two
 * ways only:
 *  - it says more ("禁煙席あり" for "禁煙席"), unless what it adds takes the wish back ("禁煙席なし");
 *  - it leaves out a word that only names the kind of thing ("禁煙" for "禁煙席").
 *
 * Any other part of the wish is not enough: "対応" says nothing about "えびアレルギー対応", and
 * "グルテン" is the opposite of "グルテンフリー". A wrong no only means that the AI calls back or that
 * the result asks the user to check; a wrong yes is reported as a booking that meets the wish.
 */
object PreferenceMatch {
    fun isConfirmed(pref: Preference, confirmed: List<String>): Boolean {
        val want = normalize(pref.text)
        if (want.isEmpty()) return false
        return confirmed.any { matches(want, normalize(it)) }
    }

    private fun matches(want: String, got: String): Boolean =
        if (got.contains(want)) {
            val added = YES_IN_NEGATIVE_FORM.fold(got.replace(want, "")) { rest, phrase -> rest.replace(phrase, "") }
            NEGATIONS.none { it in added }
        } else {
            // One character alone is too vague ("席")
            got.length >= 2 && want.startsWith(got) && want.removePrefix(got) in KIND_WORDS
        }

    private fun normalize(s: String) = s.filterNot { it.isWhitespace() }

    /** Endings of a wish that only name the kind of thing. */
    private val KIND_WORDS = setOf("席", "の席", "シート", "対応", "可", "あり")

    /** Polite ways of saying yes that are built on a negative: not taken for a refusal. */
    private val YES_IN_NEGATIVE_FORM = listOf("問題ありません", "問題ない", "問題なく", "構いません", "かまいません", "差し支えありません")

    /** In what the agent added to the wish: the shop said no, or has not said yes yet. */
    private val NEGATIONS = listOf(
        "なし", "無し", "ない", "無い", "なく", "無く", "なかった", "無かった", "ません", "できず",
        "不可", "不能", "非対応", "未対応", "対応外", "対象外", "以外", "除く",
        "満席", "いっぱい", "埋ま", "売り切れ", "売切", "難し", "無理", "取れず", "お断り", "かねます", "だめ", "ダメ", "NG",
        "未確認", "確認中", "要確認", "不明", "未定",
    )
}
