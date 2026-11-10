// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.request

/** Put in the place of a number in what goes to the AI that sent the request. */
internal const val HIDDEN_NUMBER = "[番号]"

private const val DIGIT = """\p{Nd}"""
private const val LEFT_OUT = "*＊xXｘＸ×●"
private const val KANJI_DIGITS = "〇零一二三四五六七八九"
private const val ANY_DIGIT = """[\p{Nd}$KANJI_DIGITS]"""
private const val DASHES = """\-‐‑‒–—―−－ーｰ"""

// A whole number. Its digits are counted afterwards: 03-11-14 has the shape and not the length

private const val GROUP_GAP = """[ 　$DASHES・.．()（）]{0,3}"""
private const val DIGIT_OR_LEFT_OUT = """[\p{Nd}$LEFT_OUT]"""
private const val LAST_GROUPS = """$GROUP_GAP$DIGIT_OR_LEFT_OUT{1,4}$GROUP_GAP$DIGIT_OR_LEFT_OUT{3,4}(?!$DIGIT)"""
private const val INTERNATIONAL = """[+＋][ 　]?$DIGIT{1,3}(?:$GROUP_GAP[(（][0０][)）])?$GROUP_GAP$DIGIT{1,4}$LAST_GROUPS"""
private const val DOMESTIC = """(?:[(（][0０]$DIGIT{1,4}[)）]|[0０]$DIGIT{1,4})$LAST_GROUPS"""
private const val ONE_RUN = """$DIGIT{10,15}(?!$DIGIT)(?![ 　]?(?:円|えん|万|億))"""
private const val MIN_DIGITS_OF_A_WHOLE_NUMBER = 10

private val WHOLE_NUMBER = Regex("""(?<![\p{Nd}+＋])(?:$INTERNATIONAL|$DOMESTIC|$ONE_RUN)""")

// Digits that the words around them call a phone number or the end of one

private const val UNIT = """[ 　]?(?:円|えん|万|千|百|億|年|月|日|時|分|秒|週|名|人|歳|才|席|個|枚|階|回|件|本|つ|ヶ月|か月|カ月|箇月|%|％)"""

/** Not digits that go on, that have a unit, or that are half of a time, a date or a range. */
private const val END_OF_THE_DIGITS = """(?!$ANY_DIGIT|$UNIT|[/／:：～〜~$DASHES]$ANY_DIGIT)"""

/** As an automatic voice reads them: 1、2、3、4. */
private const val ONE_BY_ONE = """$ANY_DIGIT(?:[ 　$DASHES・、,]$ANY_DIGIT){1,14}"""

/** Not a date: its dashes look the same. */
private const val IN_GROUPS = """(?!$DIGIT{4}[$DASHES/.]$DIGIT{1,2}[$DASHES/.]$DIGIT{1,2})$ANY_DIGIT{2,}(?:[ 　$DASHES・]$ANY_DIGIT{2,}(?!$ANY_DIGIT|$UNIT))*"""
private const val DIGITS = """(?:$ONE_BY_ONE|$IN_GROUPS)$END_OF_THE_DIGITS"""

private const val HOW_MANY_DIGITS = """[\p{Nd}一二三四五六七八九][ 　]?(?:桁|けた|ケタ)"""
private const val END_OF_A_NUMBER = """(?:(?:下|末尾|最後|後ろ|うしろ|終わり|おわり|ラスト)の?[ 　]?$HOW_MANY_DIGITS|末尾)"""
private const val PHONE_WORDS = """(?:(?:電話|携帯|発信者?|着信|連絡先|折り?返し先?)(?:番号)?|(?<![A-Za-z])(?:TEL|Tel|tel)(?![A-Za-z])|℡|☎)"""

/** Up to the digits, in the same sentence. */
private const val UP_TO_THE_DIGITS = """[^\p{Nd}$KANJI_DIGITS。．！？!?\n\]]{0,8}"""
private const val SAID_TO_END_A_NUMBER = """[ 　]?(?:で終わ|[がは](?:末尾|下)|[（(](?:末尾|下))"""
private const val MIN_DIGITS_NAMED_AS_A_NUMBER = 4

private val AFTER_THE_END_OF_A_NUMBER = Regex("""($END_OF_A_NUMBER$UP_TO_THE_DIGITS)$DIGITS""")
private val AFTER_PHONE_WORDS = Regex("""($PHONE_WORDS$UP_TO_THE_DIGITS)($DIGITS)""")
private val BEFORE_THE_END_OF_A_NUMBER = Regex("""(?<!$ANY_DIGIT)$DIGITS(?=$SAID_TO_END_A_NUMBER)""")

private fun digitsIn(text: String) = text.count { it.isDigit() || it in KANJI_DIGITS || it in LEFT_OUT }

/**
 * A text of a call report as it may go to the AI that sent the request: without phone numbers, and
 * without digits that the words around them call a part of one. Amounts, years, dates, times and
 * numbers of people stay. [io.github.incallagent.core.Redact] does not fit: it is for a string
 * known to be a number, and keeps the ends of it.
 */
internal fun hidePhoneNumbers(text: String): String {
    val noWholeNumbers = WHOLE_NUMBER.replace(text) {
        if (digitsIn(it.value) >= MIN_DIGITS_OF_A_WHOLE_NUMBER) HIDDEN_NUMBER else it.value
    }
    val noEnds = AFTER_THE_END_OF_A_NUMBER.replace(noWholeNumbers) { it.groupValues[1] + HIDDEN_NUMBER }
    val noneNamed = AFTER_PHONE_WORDS.replace(noEnds) {
        if (digitsIn(it.groupValues[2]) >= MIN_DIGITS_NAMED_AS_A_NUMBER) it.groupValues[1] + HIDDEN_NUMBER else it.value
    }
    return BEFORE_THE_END_OF_A_NUMBER.replace(noneNamed) { HIDDEN_NUMBER }
}
