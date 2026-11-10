// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import io.github.incallagent.agent.live.LiveTools
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.incallagent.core.policy.Purpose
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.text.Normalizer

/** How a call the user had declined ended, in Japanese for the screen. */
internal const val DECLINED_BY_USER = "本人の指示で、断って電話を終えました"

/** Said when something may have been agreed before the user declined: the app cannot know that the shop took it back. */
internal const val AGREED_BEFORE_DECLINE = "断る前にお店と話がまとまっていたかもしれません。お店に、予約や変更が残っていないか確かめてください"

/** What the user can tell the AI from the call screen (ADR-046). [label]: the words on the button. */
enum class UserCue(val label: String) {
    ASK_TO_WAIT("少し待ってもらう"),
    DECLINE_AND_END("断って終える"),
}

/**
 * Whether the speaking agent said that it is an AI (ADR-024: the introduction must say so), read
 * from the transcript of what it said, and the cue that asks it to say so now.
 */
object AiDisclosure {
    /**
     * "AI", "ＡＩ", "A.I.", but not the letters inside a word such as "MAIL";
     * エーアイ, エイアイ, えーあい, エー・アイ; 人工知能.
     */
    private val WORD = Regex("(?<![A-Z])A[.・ ]?I(?![A-Z])|[エえ][ーイい][・ ]?[アあ][イい]|人工知能")

    /** How many letters on each side of "AI" tell the shop's name from the word. */
    private const val NEXT_TO = 2

    /**
     * Whether [text] says that the speaker is an AI. An "AI" that is part of [shopName] does not
     * count ("〇〇AI さんでしょうか" only names the shop); it can be cut short or read aloud, so the
     * letters next to it are compared, not the whole name. Full-width and half-width forms are
     * folded first (NFKC).
     */
    fun mentionsAi(text: CharSequence, shopName: String = ""): Boolean {
        val said = fold(text)
        val shop = fold(shopName)
        val inTheName = WORD.findAll(shop).map { NextTo(plain(shop.substring(0, it.range.first)).takeLast(NEXT_TO), plain(shop.substring(it.range.last + 1)).take(NEXT_TO)) }.toList()
        return WORD.findAll(said).any { m ->
            val before = plain(said.substring(0, m.range.first))
            val after = plain(said.substring(m.range.last + 1))
            inTheName.none { it.fits(before, after) }
        }
    }

    private fun fold(text: CharSequence) = Normalizer.normalize(text, Normalizer.Form.NFKC).uppercase()

    /** For comparing: spaces and separators can be written or left out. */
    private fun plain(text: String) = text.filterNot { it.isWhitespace() || it == '・' || it == '.' }

    /** What stands next to one "AI" in the shop's name. */
    private class NextTo(private val before: String, private val after: String) {
        /** The transcript comes in pieces: what follows may not be there yet, and then it can still become the name. */
        fun fits(saidBefore: String, saidAfter: String): Boolean =
            (before.isNotEmpty() && saidBefore.endsWith(before)) ||
                (after.isNotEmpty() && saidAfter.take(after.length) == after.take(saidAfter.length))
    }

    /**
     * `clientContent` in the same form as agent-live's opening cue, asking the agent to introduce
     * itself as an AI with the policy's [disclosure] line before it goes on.
     */
    fun cue(disclosure: String): String = clientContent(
        "（まだ、AI であることを伝えていません。いますぐ「$disclosure」と言って、" +
            "AI による代理の電話であることを伝えてから、続けてください。）",
    )

    /**
     * Nobody is at the phone to take the call over: the agent is asked to end it with the policy's
     * [fallback] line, in the same form as [cue]. It reports the result as usual, so that the call ends
     * with what it can say about it. [named]: it has already said that it is an AI.
     */
    fun callbackCue(fallback: String, named: Boolean): String = clientContent(
        "（これ以上、通話を続けられません。" +
            (if (named) "" else "AI による代理の電話であることを伝えてから、") +
            "いますぐ「$fallback」と伝えて、お礼を言ってください。" +
            "そのあと ${LiveTools.REPORT_RESULT}（status は NEEDS_USER_DECISION、pendingItems に続けられなかったこと）を呼び、" +
            "${LiveTools.END_CALL} を呼んでください。）",
    )

    /** The other side hung up before the agent reported how it went: it is asked for the report, in the same form as [cue]. */
    fun lastReportCue(): String = clientContent(
        "（相手が電話を切りました。ここまでの会話から ${LiveTools.REPORT_RESULT} で結果を報告してください。本文は書かないでください。）",
    )

    /**
     * The user's instruction from the call screen, in the same form as [cue] (ADR-046). The words are
     * the app's, nothing typed goes in, and none of them lets the AI accept more than it was allowed
     * to. What it says to the shop is in kana, like [saidInKana]. [named]: it has already said that it is an AI.
     */
    fun userCue(kind: UserCue, named: Boolean, purpose: Purpose): String {
        val first = if (named) "" else "AI による代理の電話であることを伝えてから、"
        return clientContent(
            "（本人からの指示です。" + when (kind) {
                UserCue.ASK_TO_WAIT ->
                    "本人が少し確かめています。${first}お店の方に「もうしわけありません、しょうしょうお待ちいただけますでしょうか」と短く伝えてください。" +
                        "受ける、断るの返事は、まだしないでください。そのあとは黙って待ち、お店の方が話しかけてきたら「お待たせしております」と言って、用件の続きに戻ってください。"
                UserCue.DECLINE_AND_END ->
                    "この件は受けずに、電話を終えます。まず ${LiveTools.REPORT_RESULT}（status は UNAVAILABLE、summary は「$DECLINED_BY_USER」）を呼んでください。" +
                        "次に、${first}お店の方に「${declineWords(purpose)}」と伝えて、お礼を言ってください。" +
                        takeBackWords(purpose)?.let { "すでに受ける返事をしていたら、「$it」と伝えてください。" }.orEmpty() +
                        "そのあと ${LiveTools.END_CALL} を呼んでください。"
            } + "）",
        )
    }

    /** By errand: in a change or a cancellation, a plain "取り消し" could be taken for the wrong thing. */
    private fun declineWords(purpose: Purpose): String = when (purpose) {
        Purpose.NEW_BOOKING -> "もうしわけありません、今回はみおくらせていただきます"
        Purpose.CHANGE -> "もうしわけありません、今回は変更をみおくらせていただきます"
        Purpose.CANCEL -> "もうしわけありません、今回はとりけしをみおくらせていただきます"
        Purpose.INQUIRY -> "もうしわけありません、今回はこれでしつれいいたします"
    }

    /** What takes back a yes that was already given. An inquiry gives none. */
    private fun takeBackWords(purpose: Purpose): String? = when (purpose) {
        Purpose.NEW_BOOKING -> "もうしわけありません、やはりとりけしをお願いします"
        Purpose.CHANGE -> "もうしわけありません、やはり変更はやめて、もとの予約のままでお願いします"
        Purpose.CANCEL -> "もうしわけありません、やはりとりけしはやめて、予約はそのままでお願いします"
        Purpose.INQUIRY -> null
    }

    private fun clientContent(text: String): String = buildJsonObject {
        putJsonObject("clientContent") {
            putJsonArray("turns") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(buildJsonObject {
                            put("text", text)
                        })
                    }
                })
            }
            put("turnComplete", true)
        }
    }.toString()
}
