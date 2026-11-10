// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.request

import org.junit.Assert.assertEquals
import org.junit.Test

class HidePhoneNumbersTest {
    private val hidden = HIDDEN_NUMBER

    private fun assertBecomes(cases: Map<String, String>) {
        for ((text, expected) in cases) assertEquals(text, expected, hidePhoneNumbers(text))
    }

    private fun assertLeftAlone(texts: List<String>) {
        for (text in texts) assertEquals(text, hidePhoneNumbers(text))
    }

    @Test
    fun everyWayOfWritingAPhoneNumberIsHidden() {
        val numbers = listOf(
            "090-1234-5678",
            "09012345678",
            "090 1234 5678",
            "090　1234　5678",
            "090 - 1234 - 5678",
            "０９０－１２３４－５６７８",
            "090ー1234ー5678",
            "090‐1234‐5678",
            "090−1234−5678",
            "03-1234-5678",
            "0312345678",
            "03(1234)5678",
            "(03)1234-5678",
            "（03）1234-5678",
            "0466-00-0012",
            "0120-123-456",
            "050-0000-5678",
            "+81-90-1234-5678",
            "+81 90 1234 5678",
            "+819012345678",
            "+81(0)90-1234-5678",
            "＋８１９０１２３４５６７８",
            "090-****-5678",
            "090-1234-xxxx",
            "9012345678",
        )
        for (number in numbers) assertEquals(number, "こちらは${hidden}です", hidePhoneNumbers("こちらは${number}です"))
    }

    @Test
    fun theBracketsAroundANumberStay() {
        assertEquals("店（$hidden）", hidePhoneNumbers("店（090-1234-5678）"))
    }

    @Test
    fun everyNumberInATextIsHidden() {
        assertEquals("$hidden と $hidden", hidePhoneNumbers("090-1234-5678 と 03-1234-5678"))
    }

    @Test
    fun digitsSaidToBeTheEndOfANumberAreHidden() {
        assertBecomes(
            mapOf(
                "下4桁は1234です" to "下4桁は${hidden}です",
                "下４桁は１２３４" to "下４桁は$hidden",
                "下四桁は一二三四" to "下四桁は$hidden",
                "下 4 桁 1234" to "下 4 桁 $hidden",
                "下4桁：1234" to "下4桁：$hidden",
                "下4桁が「1234」" to "下4桁が「$hidden」",
                "下4けた1234" to "下4けた$hidden",
                "下4ケタは1234" to "下4ケタは$hidden",
                "下4桁の番号は1234" to "下4桁の番号は$hidden",
                "発信者番号の下4桁（1234）で" to "発信者番号の下4桁（$hidden）で",
                "末尾1234" to "末尾$hidden",
                "末尾は1234" to "末尾は$hidden",
                "番号の末尾が「1234」" to "番号の末尾が「$hidden」",
                "末尾4桁は1234" to "末尾4桁は$hidden",
                "最後の4桁は1234" to "最後の4桁は$hidden",
                "後ろ4桁は1234" to "後ろ4桁は$hidden",
                "ラスト4桁は1234" to "ラスト4桁は$hidden",
                "下4桁は1、2、3、4です" to "下4桁は${hidden}です",
                "下4桁は1 2 3 4" to "下4桁は$hidden",
                "下4桁は1-2-3-4" to "下4桁は$hidden",
                "1234で終わる番号" to "${hidden}で終わる番号",
                "1234（下4桁）" to "$hidden（下4桁）",
                "1234が下4桁" to "${hidden}が下4桁",
            ),
        )
    }

    @Test
    fun digitsNamedAsAPhoneNumberAreHidden() {
        assertBecomes(
            mapOf(
                "電話番号は1234-5678です" to "電話番号は${hidden}です",
                "お電話番号 12345678" to "お電話番号 $hidden",
                "連絡先：123-4567" to "連絡先：$hidden",
                "携帯は5678" to "携帯は$hidden",
                "TEL 1234-5678" to "TEL $hidden",
                "折り返し先は「1234」" to "折り返し先は「$hidden」",
            ),
        )
    }

    @Test
    fun amountsYearsDatesTimesAndPeopleAreLeftAlone() {
        assertLeftAlone(
            listOf(
                "コースは5000円です",
                "5,000円",
                "1,000,000円",
                "10000円～15000円",
                "3000-5000円",
                "¥5,000",
                "1000000000円",
                "2026年11月14日",
                "2026-11-14",
                "2026/11/14",
                "2026.11.14",
                "2026-11-14 19:00",
                "2026-11-14T19:00",
                "2026-01-15 19:00",
                "11月14日(土)",
                "11/14",
                "20261114",
                "19:00",
                "19時",
                "19:00-21:00",
                "19:00〜21:00",
                "18時半",
                "0時まで",
                "09:00",
                "09:00-10:00",
                "2時間制",
                "90分",
                "2名",
                "2人",
                "4名様",
                "12名",
            ),
        )
    }

    @Test
    fun onlyTheNumberGoesFromASentence() {
        assertBecomes(
            mapOf(
                "下4桁は1234、料金は5000円、2026年11月14日の19時に2名" to "下4桁は$hidden、料金は5000円、2026年11月14日の19時に2名",
                "090-1234-5678 の方は2026-11-14 19:00に2名" to "$hidden の方は2026-11-14 19:00に2名",
                "2026-11-14 090-1234-5678" to "2026-11-14 $hidden",
                "下4桁は1234 19時" to "下4桁は$hidden 19時",
                "下4桁は1234 5000円" to "下4桁は$hidden 5000円",
                "下4桁は1234、2名" to "下4桁は$hidden、2名",
            ),
        )
    }

    @Test
    fun wordsAboutANumberDoNotHideWhatComesAfterThem() {
        assertLeftAlone(
            listOf(
                "末尾は5000円",
                "末尾の12名",
                "下2桁が26年",
                "電話番号は後ほど。19時に2名",
                "折り返しは11月15日の12時以降",
                "折り返し 14:00",
                "連絡先は本人の携帯です。予約は19時、2名",
                "連絡先 2026-11-14",
                "連絡先 2026/11/14",
                "電話は2回",
                "5000円で終わる",
                "19時で終わる",
            ),
        )
    }

    @Test
    fun numbersThatAreNotPhoneNumbersAreLeftAlone() {
        assertLeftAlone(listOf("予約番号は1234", "受付番号 5678", "Hotel 1234", "〒150-0001", "3番テーブル"))
    }
}
