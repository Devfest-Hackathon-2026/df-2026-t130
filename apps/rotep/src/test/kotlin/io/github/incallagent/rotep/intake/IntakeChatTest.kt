// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.intake

import io.github.incallagent.rotep.call.SpokenReading
import io.github.incallagent.rotep.research.ShopFindings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class IntakeChatTest {
    private class FakeTransport : IntakeTransport {
        val bodies = ArrayList<String>()
        val pending = ArrayList<(IntakeReply) -> Unit>()
        var stopped = 0

        override fun send(body: String, done: (IntakeReply) -> Unit): () -> Unit {
            bodies += body
            pending += done
            return { stopped++ }
        }

        fun prompt(): String =
            Json.parseToJsonElement(bodies.last()).jsonObject.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content
    }

    private val transport = FakeTransport()
    private class FakeSearch : IntakeSearch {
        val queries = ArrayList<ChatQuery>()
        val pending = ArrayList<(IntakeSearchResult) -> Unit>()

        override fun search(query: ChatQuery, done: (IntakeSearchResult) -> Unit): () -> Unit {
            queries += query
            pending += done
            return {}
        }
    }

    private val finder = FakeSearch()
    private val chat = IntakeChat({ LocalDate.of(2026, 11, 9) }, { Instant.parse("2026-11-09T03:00:00Z") }, transport, search = finder)
    private val found = IntakeSearchResult.Found(ShopFindings("検索の本文", emptyList(), emptyList()))
    private val cafe = """"shop_name":"カフェ・サンプル","kind":"restaurant","windows":[{"date":"2026-11-14","from":"19:00"}],"party_size":2"""
    private val cafeWritten = "カフェ・サンプル 土曜19時 2名"
    private val ui get() = chat.ui.value

    private val number = "03-0000-0001"
    private val everything =
        """"shop_name":"レストラン・サンプル","phone_number":"$number","windows":[{"date":"2026-11-14","from":"19:00"}],"party_size":2,"reservation_name":"ヤマダ""""
    private val written = "レストラン・サンプル $number 予約名はヤマダ"

    private fun arrive(answer: String) {
        val text = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(answer))
        transport.pending.removeLast()(IntakeReply.Arrived("""{"stop_reason":"end_turn","content":[{"type":"thinking","thinking":"","signature":"x"},{"type":"text","text":$text}]}"""))
    }

    private fun model(fields: String = "", ask: String = "none", reply: String = "はい", extra: String = "") =
        arrive("""{"reply":"$reply","fields":{$fields},"ask":"$ask"$extra}""")

    private fun filled() {
        chat.say(written)
        model(everything)
    }

    private fun tells(vararg items: Pair<String, String>) =
        items.joinToString(",", ""","may_tell":[""", "]") { (label, value) -> """{"label":"$label","value":"$value"}""" }

    @Test
    fun aMessageGoesOutAndTheAnswerComesIntoTheChat() {
        assertTrue(chat.say("  土曜に2名  "))
        assertTrue(ui.waiting)
        assertTrue(transport.prompt().contains("本人：土曜に2名"))
        model(""""party_size":2""", reply = "お店はどちらですか")
        assertFalse(ui.waiting)
        assertEquals(listOf(IntakeLine.Who.USER, IntakeLine.Who.AI), ui.lines.map { it.who })
        assertEquals("お店はどちらですか", ui.lines.last().text)
        assertEquals("2", ui.form!!.partyPreferred)
    }

    @Test
    fun whenTheChatStartsNothingIsSent() {
        assertTrue(transport.bodies.isEmpty())
    }

    @Test
    fun onlyOneRequestAtATime() {
        chat.say("一つ目")
        assertFalse(chat.say("二つ目"))
        assertEquals(1, transport.bodies.size)
    }

    @Test
    fun emptyAndTooLongMessagesAreNotSent() {
        assertFalse(chat.say("   "))
        assertFalse(chat.say("あ".repeat(501)))
        assertEquals("1 回に 500 字までです", ui.error)
        assertTrue(transport.bodies.isEmpty())
    }

    @Test
    fun aFailureKeepsWhatWasWrittenAndCanBeTriedAgain() {
        chat.say("土曜に2名")
        transport.pending.removeLast()(IntakeReply.Failed("ネットにつながらないので送れませんでした"))
        assertFalse(ui.waiting)
        assertEquals("ネットにつながらないので送れませんでした", ui.error)
        assertEquals(1, ui.lines.size)

        chat.retry()
        assertTrue(ui.waiting)
        assertNull(ui.error)
        assertEquals(2, transport.bodies.size)
        assertTrue(transport.prompt().contains("本人：土曜に2名"))
    }

    @Test
    fun anAnswerThatCannotBeReadIsAFailure() {
        chat.say("土曜")
        arrive("ただの文です")
        assertEquals("AI の返事を読めませんでした。もう一度送ってください", ui.error)
        assertFalse(ui.waiting)
    }

    @Test
    fun anAnswerAfterStoppingIsDropped() {
        chat.say("土曜")
        val late = transport.pending.removeLast()
        chat.stop()
        assertEquals(1, transport.stopped)
        assertFalse(ui.waiting)
        late(IntakeReply.Arrived("""{"steps":[{"type":"model_output","content":[{"type":"text","text":"{\"reply\":\"遅い\",\"fields\":{},\"ask\":\"none\"}"}]}]}"""))
        assertEquals(1, ui.lines.size)
    }

    @Test
    fun anAnswerAfterClosingIsDroppedAndNothingIsKept() {
        filled()
        chat.say("もう一言")
        val late = transport.pending.removeLast()
        chat.close()
        assertEquals(IntakeUi(), ui)
        late(IntakeReply.Failed("遅い"))
        assertEquals(IntakeUi(), ui)
    }

    @Test
    fun whatIsStillMissingIsListedUntilItCanBeConfirmed() {
        chat.say("土曜")
        model()
        assertTrue(ui.missing.isNotEmpty())
        chat.say(written)
        model(everything)
        assertEquals(emptyList<String>(), ui.missing)
    }

    @Test
    fun whatTheModelLeavesOutLaterIsNotForgotten() {
        filled()
        chat.say("禁煙席で")
        model(""""required_preferences":["禁煙席"]""")
        assertEquals("レストラン・サンプル", ui.form!!.businessName)
        assertEquals("禁煙席", ui.form!!.requiredPreferences)
    }

    @Test
    fun aPermissionIsAskedWithTheAppsOwnWordsAndNeedsAButton() {
        filled()
        chat.say("進めて")
        model(everything, ask = "prepayment", reply = "前払いを勝手に受けますね")
        assertEquals(IntakeAsk.PREPAYMENT, ui.question!!.ask)
        assertEquals("前払い・予約金に、AI が同意してよいですか（金額の上限はありません）", ui.question!!.text)
        assertFalse("not before the button", ui.form!!.mayAcceptPrepayment)

        chat.answer(true)
        assertTrue(ui.form!!.mayAcceptPrepayment)
        assertTrue("the answer goes on to the next question", ui.waiting)
        assertTrue(transport.prompt().contains("本人の答え（任せる範囲）：前払い・予約金：はい"))
    }

    @Test
    fun noLeavesItOff() {
        filled()
        chat.say("進めて")
        model(everything, ask = "voicemail")
        chat.answer(false)
        assertFalse(ui.form!!.mayLeaveVoicemail)
        assertTrue(transport.prompt().contains("留守番電話の伝言：いいえ"))
    }

    @Test
    fun aQuestionAlreadyAnsweredIsNotShownAgain() {
        filled()
        chat.say("進めて")
        model(everything, ask = "voicemail")
        chat.answer(false)
        model(everything, ask = "voicemail")
        assertNull(ui.question)
    }

    @Test
    fun aBudgetIsShownInTheQuestionAndSetOnlyByTheButton() {
        filled()
        chat.say("1人8千円まで")
        model(everything, ask = "budget", extra = ""","budget_yen":8000""")
        assertEquals("1人あたり 8000円までなら、AI が受けてよいですか", ui.question!!.text)
        assertEquals("", ui.form!!.maxBudgetPerPersonYen)
        chat.answer(true)
        assertEquals("8000", ui.form!!.maxBudgetPerPersonYen)
    }

    @Test
    fun aBudgetQuestionWithoutAnAmountIsNotAsked() {
        filled()
        chat.say("進めて")
        model(everything, ask = "budget")
        assertNull(ui.question)
    }

    @Test
    fun aQuestionThatDoesNotFitThePurposeIsNotAsked() {
        chat.say("$written の問い合わせ")
        model(""""purpose":"inquiry","notes_for_shop":"営業時間"""", ask = "prepayment")
        assertNull(ui.question)
    }

    @Test
    fun aCancelAsksAboutTheFeeInItsOwnWords() {
        chat.say("$written を取り消して")
        model(""""purpose":"cancel","notes_for_shop":"14日の予約"""", ask = "cancellation_policy")
        assertEquals("キャンセル料がかかっても、AI が取り消してよいですか（金額の上限はありません）", ui.question!!.text)
    }

    @Test
    fun agreeingToTermsDoesNotCarryOverToCancellingAtAnyFee() {
        filled()
        chat.say("進めて")
        model(everything, ask = "cancellation_policy")
        chat.answer(true)
        assertTrue(ui.form!!.mayAcceptCancellationPolicy)

        model(everything)
        chat.say("やっぱり取り消しで")
        model(""""purpose":"cancel","notes_for_shop":"14日の予約"""", ask = "cancellation_policy")
        assertFalse(ui.form!!.mayAcceptCancellationPolicy)
        assertEquals("asked again in its own words", IntakeAsk.CANCELLATION_POLICY, ui.question!!.ask)
    }

    @Test
    fun theButtonsAreNotWordsTheUserWrote() {
        filled()
        chat.say("進めて")
        model(everything, ask = "voicemail")
        chat.answer(true)
        // "留守番電話の伝言：はい" is on the chat, but it is not something the user typed
        model(""""shop_name":"留守番電話の伝言"""")
        assertEquals("レストラン・サンプル", ui.form!!.businessName)
    }

    @Test
    fun aRefusedShopBringsItsNumberBackWithIt() {
        filled()
        chat.say("別の店 03-0000-0002 に変えて")
        model(""""shop_name":"作った店名","phone_number":"03-0000-0002"""")
        assertEquals("レストラン・サンプル", ui.form!!.businessName)
        assertEquals("the old pair stays together", number, ui.form!!.phoneNumber)
    }

    @Test
    fun aFirstNumberIsNotLostWhenOnlyTheShopIsRefused() {
        chat.say("番号は $number です")
        model(""""shop_name":"作った店名","phone_number":"$number"""")
        assertEquals("", ui.form!!.businessName)
        assertEquals(number, ui.form!!.phoneNumber)
    }

    @Test
    fun anotherShopDoesNotKeepTheLastOnesNumber() {
        filled()
        chat.say("やっぱりカフェ・サンプルにして")
        model(""""shop_name":"カフェ・サンプル"""")
        assertEquals("カフェ・サンプル", ui.form!!.businessName)
        assertEquals("", ui.form!!.phoneNumber)
    }

    @Test
    fun nullTakesAValueBack() {
        chat.say("$written 連絡先は 090-0000-0002")
        model("""$everything,"callback_number":"090-0000-0002"""")
        assertEquals("090-0000-0002", ui.form!!.callbackNumber)
        chat.say("連絡先は伝えないで")
        model(""""callback_number":null""")
        assertEquals("", ui.form!!.callbackNumber)
        assertEquals("the rest stays", "レストラン・サンプル", ui.form!!.businessName)
    }

    @Test
    fun aShopTakenBackTakesItsNumberWithIt() {
        filled()
        chat.say("その店はやめます")
        model(extra = ""","cleared":["shop_name"]""")
        assertEquals("", ui.form!!.businessName)
        assertEquals("", ui.form!!.phoneNumber)
    }

    @Test
    fun aNumberOfAnotherShopComesBackOnlyIfWrittenAgain() {
        filled()
        chat.say("やっぱりカフェ・サンプルにして")
        model(""""shop_name":"カフェ・サンプル"""")
        assertEquals("", ui.form!!.phoneNumber)
        chat.say("予約名はヤマダ")
        model(""""shop_name":"カフェ・サンプル","phone_number":"$number"""")
        assertEquals("the old number is in the chat, but it was another shop's", "", ui.form!!.phoneNumber)
        chat.say("電話番号は $number で合っています")
        model(""""shop_name":"カフェ・サンプル","phone_number":"$number"""")
        assertEquals(number, ui.form!!.phoneNumber)
    }

    @Test
    fun aNumberSentBackAfterARefusedShopIsStillHeld() {
        filled()
        chat.say("別の店 03-0000-0002 に変えて")
        model(""""shop_name":"作った店名","phone_number":"03-0000-0002"""")
        assertEquals(number, ui.form!!.phoneNumber)
        chat.say("進めて")
        model("""$everything""")
        assertEquals("the shop never moved", number, ui.form!!.phoneNumber)
    }

    @Test
    fun aFirstNumberGivenWithAnotherShopIsKept() {
        chat.say("レストラン・サンプル")
        model(""""shop_name":"レストラン・サンプル"""")
        chat.say("カフェ・サンプルにして、番号は $number")
        model(""""shop_name":"カフェ・サンプル","phone_number":"$number"""")
        assertEquals(number, ui.form!!.phoneNumber)
    }

    @Test
    fun theSameShopWrittenAlittleDifferentlyKeepsItsNumber() {
        filled()
        chat.say("レストラン サンプルです")
        model(""""shop_name":"レストラン サンプル"""")
        assertEquals(number, ui.form!!.phoneNumber)
    }

    @Test
    fun theFirstStatedPurposeIsAMoveFromTheDefault() {
        chat.say("$written 誕生日です")
        model("""$everything,"notes_for_shop":"誕生日です"""")
        chat.say("取り消しで")
        model(""""purpose":"cancel","notes_for_shop":"誕生日です"""")
        assertEquals("", ui.form!!.notesForShop)
    }

    @Test
    fun aFirstNoteGivenWithAnotherPurposeIsKept() {
        filled()
        chat.say("14日の予約を取り消して")
        model(""""purpose":"cancel","notes_for_shop":"14日の予約"""")
        assertEquals("14日の予約", ui.form!!.notesForShop)
    }

    @Test
    fun aFieldGivenUpStaysGivenUpWhenTheModelStopsSendingIt() {
        repeat(2) {
            chat.say("お願いします")
            model(""""shop_name":"作った店名"""")
        }
        assertEquals(listOf("店名"), ui.forTheForm)
        chat.say("続けて")
        model(""""party_size":2""")
        assertEquals(listOf("店名"), ui.forTheForm)
    }

    @Test
    fun aFieldHeldAgainIsNoLongerForTheForm() {
        repeat(2) {
            chat.say("お願いします")
            model(""""shop_name":"作った店名"""")
        }
        chat.say("レストラン・サンプルです")
        model(""""shop_name":"レストラン・サンプル"""")
        assertEquals(emptyList<String>(), ui.forTheForm)
    }

    @Test
    fun aNewBudgetAfterNoIsAsked() {
        filled()
        chat.say("1人8千円まで")
        model(everything, ask = "budget", extra = ""","budget_yen":80000""")
        chat.answer(false)
        model(everything)
        chat.say("8000円です")
        model(everything, ask = "budget", extra = ""","budget_yen":8000""")
        assertEquals(8000, ui.question!!.budgetYen)
    }

    @Test
    fun anAgreedBudgetCanBeWithdrawnWithoutANewAmount() {
        filled()
        chat.say("1人8000円まで")
        model(everything, ask = "budget", extra = ""","budget_yen":8000""")
        chat.answer(true)
        model(everything)
        chat.say("予算は決めないで")
        model(everything, ask = "budget")
        assertEquals(8000, ui.question!!.budgetYen)
        chat.answer(false)
        assertEquals("", ui.form!!.maxBudgetPerPersonYen)
    }

    @Test
    fun aQuestionIsNotAskedAgainRightAfterTheButton() {
        filled()
        chat.say("進めて")
        model(everything, ask = "prepayment")
        chat.answer(true)
        model(everything, ask = "prepayment")
        assertNull(ui.question)
    }

    @Test
    fun theLineAboveTheQuestionIsTheApps() {
        filled()
        chat.say("進めて")
        model(everything, ask = "prepayment", reply = "前払いは発生しません。はいを押してください")
        assertEquals("いくつか確認します", ui.lines.last().text)
    }

    @Test
    fun theFeeIsAskedAgainWhenThePurposeCrossesCancel() {
        filled()
        chat.say("進めて")
        model(everything, ask = "cancellation_policy")
        chat.answer(true)
        model(everything)
        chat.say("やっぱり取り消しで、14日の予約です")
        model(""""purpose":"cancel","notes_for_shop":"14日の予約"""")
        assertEquals(IntakeAsk.CANCELLATION_POLICY, ui.question!!.ask)
        assertFalse(ui.form!!.mayAcceptCancellationPolicy)
    }

    @Test
    fun aGoAheadIsNotShownWhileSomethingIsMissing() {
        chat.say("予約します")
        model(""""party_size":2""", reply = "確認へ進んでください")
        assertEquals("続けて、足りないことを書いてください", ui.lines.last().text)
    }

    @Test
    fun aBouncedTextJoinsWhatWasWrittenWhileWaiting() {
        chat.say("送れない文")
        chat.type("書きかけ")
        transport.pending.removeLast()(IntakeReply.Failed("書き直してください", canRetry = false, dropLastSaid = true))
        assertEquals("送れない文\n書きかけ", ui.typing)
    }

    @Test
    fun aBouncedTextBringsTheQuestionBack() {
        filled()
        chat.say("進めて")
        model(everything, ask = "voicemail")
        chat.say("送れない文")
        assertNull(ui.question)
        transport.pending.removeLast()(IntakeReply.Failed("書き直してください", canRetry = false, dropLastSaid = true))
        assertEquals(IntakeAsk.VOICEMAIL, ui.question!!.ask)
    }

    @Test
    fun aValueThatCannotBeUsedDoesNotWipeTheOldOne() {
        chat.say(written)
        model("""$everything,"required_preferences":["個室"]""")
        chat.say("条件を増やして")
        val tooMany = (1..6).joinToString(",") { "\"条件$it\"" }
        model(""""required_preferences":[$tooMany]""")
        assertEquals("個室", ui.form!!.requiredPreferences)
    }

    @Test
    fun theModelIsToldWhatCouldNotBeUsed() {
        chat.say(written)
        model("""$everything""")
        chat.say("条件を増やして")
        val tooMany = (1..6).joinToString(",") { "\"条件$it\"" }
        model(""""required_preferences":[$tooMany]""")
        chat.say("進めて")
        assertTrue(transport.prompt().contains("形や長さが合わず使えなかった項目（直して入れ直すか、本人に聞いてください）：required_preferences"))
    }

    @Test
    fun aDefaultNobodyStatedIsNotToldToTheModel() {
        chat.say("レストラン・サンプル")
        model(""""shop_name":"レストラン・サンプル"""")
        chat.say("続けて")
        assertFalse(transport.prompt().contains("用件："))
        assertFalse(transport.prompt().contains("種類："))
    }

    @Test
    fun aRefusedShopReportsTheNumberThatWentWithIt() {
        chat.say("レストラン・サンプル")
        model(""""shop_name":"レストラン・サンプル"""")
        chat.say("番号は $number")
        model(""""shop_name":"作った店名","phone_number":"$number"""")
        assertEquals("", ui.form!!.phoneNumber)
        chat.say("はい")
        assertTrue(transport.prompt().contains("店名、電話番号"))
    }

    @Test
    fun aRefusalAfterAButtonDoesNotAskForARewrite() {
        filled()
        chat.say("進めて")
        model(everything, ask = "voicemail")
        chat.answer(true)
        transport.pending.removeLast()(IntakeReply.Failed("書き直してください", canRetry = false, dropLastSaid = true))
        assertEquals(IntakeLine.Who.ANSWER, ui.lines.last().who)
        assertEquals("", ui.typing)
        assertFalse(ui.error!!.contains("書き直して"))
        assertTrue(ui.form!!.mayLeaveVoicemail)
    }

    @Test
    fun stoppingSaysTheLastMessageIsNotIn() {
        filled()
        chat.say("予約名を変えて")
        chat.stop()
        assertTrue(ui.error!!.contains("まだ入っていません"))
        assertTrue(ui.retryable)
    }

    @Test
    fun theSameNumberInAnotherSpellingIsNotANewNumberForTheNewShop() {
        filled()
        chat.say("カフェ・サンプルにして")
        model(""""shop_name":"カフェ・サンプル","phone_number":"0300000001"""")
        assertEquals("", ui.form!!.phoneNumber)
    }

    @Test
    fun aNumberWrittenInTheMessageThatCorrectsTheShopStays() {
        filled()
        chat.say("店名はカフェ・サンプルでした。番号は 0300000001 のままです")
        model(""""shop_name":"カフェ・サンプル","phone_number":"0300000001"""")
        assertEquals("0300000001", ui.form!!.phoneNumber)
        chat.say("予約名はヤマダ")
        model(""""shop_name":"カフェ・サンプル","phone_number":"0300000001"""")
        assertEquals("0300000001", ui.form!!.phoneNumber)
    }

    @Test
    fun anOldNumberComingBackDoesNotWipeTheOneHeld() {
        filled()
        chat.say("カフェ・サンプルにして、番号は 03-0000-0002")
        model(""""shop_name":"カフェ・サンプル","phone_number":"03-0000-0002"""")
        assertEquals("03-0000-0002", ui.form!!.phoneNumber)
        chat.say("続けて")
        model(""""phone_number":"$number"""")
        assertEquals("03-0000-0002", ui.form!!.phoneNumber)
    }

    @Test
    fun aRefusedReplacementDoesNotBringBackWhatWasWithdrawn() {
        filled()
        chat.say("その店はやめて、別の店にします")
        model(""""phone_number":"03-9999-9999"""", extra = ""","cleared":["shop_name"]""")
        assertEquals("", ui.form!!.businessName)
        assertEquals("", ui.form!!.phoneNumber)
    }

    @Test
    fun aChangeThatWasNotTakenIsNotPassedOffAsDone() {
        filled()
        chat.say("番号を変えて")
        model(""""phone_number":"03-9999-9999"""", reply = "確認へ進んでください")
        assertEquals(number, ui.form!!.phoneNumber)
        assertEquals("変更できなかった項目があります。いまの内容を見て、もう一度書いてください", ui.lines.last().text)
    }

    @Test
    fun theFeeComesBeforeWhatTheModelAsksOnTheTurnThePurposeCrosses() {
        filled()
        chat.say("進めて")
        model(everything, ask = "cancellation_policy")
        chat.answer(true)
        model(everything)
        chat.say("やっぱり取り消しで、14日の予約です")
        model(""""purpose":"cancel","notes_for_shop":"14日の予約"""", ask = "voicemail")
        assertEquals(IntakeAsk.CANCELLATION_POLICY, ui.question!!.ask)
    }

    @Test
    fun aNoIsNotChangedByWordsAndTheChatSaysSo() {
        filled()
        chat.say("進めて")
        model(everything, ask = "prepayment")
        chat.answer(false)
        model(everything)
        chat.say("やっぱり前払いもいいです")
        model(everything, ask = "prepayment")
        assertNull(ui.question)
        assertTrue(ui.lines.last().text.contains("いいえ」のままです"))
        assertFalse(ui.form!!.mayAcceptPrepayment)
    }

    @Test
    fun aFieldWithdrawnIsNoLongerForTheForm() {
        repeat(2) {
            chat.say("お願いします")
            model(""""reservation_name":"作った名前"""")
        }
        assertEquals(listOf("予約名"), ui.forTheForm)
        chat.say("予約名はいいです")
        model(extra = ""","cleared":["reservation_name"]""")
        assertEquals(emptyList<String>(), ui.forTheForm)
    }

    @Test
    fun theModelIsToldTheQuestionOnTheScreen() {
        filled()
        chat.say("進めて")
        model(everything, ask = "voicemail")
        chat.say("はい")
        assertTrue(transport.prompt().contains("画面に出ていた質問（まだボタンの答えはありません）：留守番電話に"))
    }

    @Test
    fun theSameNumberTwiceIsToldAsThatAndNotAsHowToWriteIt() {
        chat.say("レストラン・サンプル 番号は $number 連絡先も同じ")
        model(""""shop_name":"レストラン・サンプル","phone_number":"$number","callback_number":"$number"""")
        chat.say("はい")
        assertTrue(transport.prompt().contains("店の番号と連絡先の番号が同じ"))
        assertFalse(transport.prompt().contains("番号全部を 1 通の中に"))
    }

    @Test
    fun aMissingNumberIsSaidToBeMissing() {
        chat.say("レストラン・サンプル")
        model(""""shop_name":"レストラン・サンプル"""")
        assertTrue(ui.missing.toString(), ui.missing.contains("電話番号がまだ書かれていません"))
    }

    @Test
    fun aShopAndATimeStartASearchThatDoesNotCarryThePhoneNumber() {
        chat.say("$cafeWritten 番号は 03-0000-0009")
        model("""$cafe,"phone_number":"03-0000-0009"""")
        val query = finder.queries.single()
        assertEquals("カフェ・サンプル", query.shop)
        assertEquals("2", query.party)
        assertTrue(query.windows.single().contains("11月14日"))
        assertFalse(query.toString().contains("0009"))
        assertTrue(ui.searching)
    }

    @Test
    fun aBookingIsNotLookedUpUntilItsTimeIsKnown() {
        chat.say("カフェ・サンプル")
        model(""""shop_name":"カフェ・サンプル"""")
        assertEquals(0, finder.queries.size)
    }

    @Test
    fun aNameWithNoStatedKindIsNotLookedUp() {
        chat.say("田中さん 土曜19時 2名")
        model(""""shop_name":"田中さん","windows":[{"date":"2026-11-14","from":"19:00"}],"party_size":2""")
        assertTrue(finder.queries.isEmpty())
    }

    @Test
    fun aButtonPressChangesNoFieldWhateverTheModelReadSince() {
        chat.say(cafeWritten)
        model(cafe, ask = "voicemail")
        finder.pending.removeLast()(found)
        chat.answer(true)
        model(""""party_size":8,"notes_for_shop":"ウェブの文"""")
        assertEquals("2", ui.form!!.partyPreferred)
        assertEquals("", ui.form!!.notesForShop)
    }

    @Test
    fun whatWasRefusedIsStillSaidAfterAButton() {
        chat.say("$written 連絡先はあとで")
        model(everything + ""","callback_number":"090-0000-1111"""", ask = "voicemail")
        chat.answer(false)
        assertTrue(transport.prompt().contains("受け付けられなかった項目"))
        model(everything)
        chat.say("進めて")
        assertTrue(transport.prompt().contains("受け付けられなかった項目"))
    }

    @Test
    fun aCallStartingLeavesNothingRunningBehindIt() {
        chat.say(cafeWritten)
        model(cafe)
        val late = finder.pending.removeLast()
        chat.holdStill()
        assertFalse(ui.searching)
        late(found)
        assertTrue(transport.pending.isEmpty())
        assertFalse(ui.waiting)
    }

    @Test
    fun aCallStartingStopsATurnStartedByAResult() {
        chat.say(cafeWritten)
        model(cafe)
        finder.pending.removeLast()(found)
        assertTrue(ui.waiting)
        chat.holdStill()
        assertFalse(ui.waiting)
        assertEquals(null, ui.error)
    }

    @Test
    fun aReadingIsNotASentenceOrANumber() {
        chat.say("$written 山田です")
        model(everything, extra = ""","readings":[{"text":"山田","kana":"ぜろさんいちにさんよんごろくななはち"}]""")
        assertTrue(ui.form!!.readings.isEmpty())
    }

    @Test
    fun aShortNameMayHaveALongReading() {
        chat.say("$written 志です")
        model(everything, extra = ""","readings":[{"text":"志","kana":"こころざし"}]""")
        assertEquals(listOf(SpokenReading("志", "こころざし")), ui.form!!.readings)
    }

    @Test
    fun aLabelThatCarriesANumberIsNotOffered() {
        chat.say("$written はい")
        model(everything, ask = "may_tell", extra = tells("連絡先03-0000-2222" to "はい"))
        assertNull(ui.question)
    }

    @Test
    fun anItemWithAnUnseenCharacterIsNotOffered() {
        chat.say("$written 初診です")
        model(everything, ask = "may_tell", extra = tells("初めてか\u200b" to "初診"))
        assertNull(ui.question)
    }

    @Test
    fun aCardNumberInFullWidthIsNotOffered() {
        val card = "４１１１－１１１１－１１１１－１１１１"
        chat.say("$written 会員番号は $card です")
        model(everything, ask = "may_tell", extra = tells("会員番号" to card))
        assertNull(ui.question)
    }

    @Test
    fun anInquiryIsLookedUpWithoutATime() {
        chat.say("カフェ・サンプルに営業時間を聞いて")
        model(""""shop_name":"カフェ・サンプル","kind":"restaurant","purpose":"inquiry","notes_for_shop":"営業時間"""")
        assertEquals(1, finder.queries.size)
    }

    @Test
    fun aCancelAndThePracticeShopAreNotLookedUp() {
        chat.say("カフェ・サンプルの予約を取り消して")
        model(""""shop_name":"カフェ・サンプル","purpose":"cancel","notes_for_shop":"予約"""")
        assertEquals(0, finder.queries.size)
        filled()
        assertEquals("レストラン・サンプル is the practice shop", 0, finder.queries.size)
    }

    @Test
    fun aSearchRunsAgainOnlyWhenTheShopOrTheTimeChanges() {
        chat.say(cafeWritten)
        model(cafe)
        finder.pending.removeLast()(found)
        model()
        chat.say("予約名はヤマダ")
        model("""$cafe,"reservation_name":"ヤマダ"""")
        assertEquals("the same shop and time", 1, finder.queries.size)
        chat.say("15日にして")
        model(""""windows":[{"date":"2026-11-15","from":"19:00"}]""")
        assertEquals(2, finder.queries.size)
    }

    @Test
    fun aChatSearchesAtMostThreeTimes() {
        chat.say(cafeWritten)
        model(cafe)
        for (day in 15..18) {
            finder.pending.removeLastOrNull()?.let {
                it(found)
                model()
            }
            chat.say("${day}日にして")
            model(""""windows":[{"date":"2026-11-$day","from":"19:00"}]""")
        }
        assertEquals(3, finder.queries.size)
    }

    @Test
    fun aResultIsShownAndTheModelLooksAtItByItself() {
        chat.say(cafeWritten)
        model(cafe)
        finder.pending.removeLast()(found)
        assertFalse(ui.searching)
        assertEquals("検索の本文", ui.searches.single().findings.text)
        assertEquals(IntakeLine.Who.SEARCH, ui.lines.last().who)
        assertTrue(ui.waiting)
        assertTrue(transport.prompt().contains("アプリの下調べ：「検索の本文"))
        assertTrue(transport.prompt().contains("いま下調べが届きました"))
    }

    @Test
    fun aTurnStartedByAResultOnlySpeaks() {
        chat.say(cafeWritten)
        model(cafe)
        finder.pending.removeLast()(found)
        model(""""party_size":8,"notes_for_shop":"ウェブの文"""", ask = "prepayment", reply = "その時間は開店前のようです")
        assertEquals("2", ui.form!!.partyPreferred)
        assertEquals("", ui.form!!.notesForShop)
        assertNull(ui.question)
        assertEquals("その時間は開店前のようです", ui.lines.last().text)
    }

    @Test
    fun aRetryOfATurnStartedByAResultStillOnlySpeaks() {
        chat.say(cafeWritten)
        model(cafe)
        finder.pending.removeLast()(found)
        transport.pending.removeLast()(IntakeReply.Failed("もう一度", canRetry = true))
        chat.retry()
        assertTrue(transport.prompt().contains("いま下調べが届きました"))
        model(""""party_size":8""")
        assertEquals("2", ui.form!!.partyPreferred)
    }

    @Test
    fun aResultForEarlierTimesIsNotCommentedOnAfterTheyChanged() {
        chat.say(cafeWritten)
        model(cafe)
        chat.say("15日にして")
        finder.pending.removeLast()(found)
        model(""""windows":[{"date":"2026-11-15","from":"19:00"}]""")
        assertFalse(ui.waiting)
    }

    @Test
    fun theModelIsToldWhatTheResultWasAbout() {
        chat.say(cafeWritten)
        model(cafe)
        finder.pending.removeLast()(found)
        assertTrue(transport.prompt().contains("カフェ・サンプル"))
        assertTrue(transport.prompt().contains("について調べたものです"))
    }

    @Test
    fun aRefusedPromptWithAnUnsentResultIsNotBlamedOnTheUser() {
        chat.say(cafeWritten)
        model(cafe, ask = "voicemail")
        finder.pending.removeLast()(found)
        chat.say("進めて")
        transport.pending.removeLast()(IntakeReply.Failed("書き直してください", canRetry = false, dropLastSaid = true))
        assertEquals("the words stay", "進めて", ui.lines.last { it.who == IntakeLine.Who.USER }.text)
        assertTrue(ui.retryable)
        chat.retry()
        assertFalse(transport.prompt().contains("検索の本文"))
    }

    @Test
    fun aStockLeadInWithNoQuestionIsNotShown() {
        chat.say(cafeWritten)
        model(cafe)
        finder.pending.removeLast()(found)
        model(everything, ask = "voicemail", reply = "いくつか確認します")
        assertEquals("調べた内容を見て、変えたいことがあれば書いてください", ui.lines.last().text)
    }

    @Test
    fun aPracticeDoesNotArgueAboutAMadeUpContactNumberEither() {
        val practice = IntakeChat({ LocalDate.of(2026, 11, 9) }, { Instant.parse("2026-11-09T03:00:00Z") }, transport, search = finder, practice = { true })
        practice.say("レストラン・サンプル 連絡先 090-0000-00")
        arrive("""{"reply":"はい","fields":{"shop_name":"レストラン・サンプル","callback_number":"090-0000-00"},"ask":"none"}""")
        practice.say("続けて")
        assertFalse(transport.prompt().contains("受け付けられなかった項目"))
    }

    @Test
    fun aResultDuringATurnIsReadAfterIt() {
        chat.say(cafeWritten)
        model(cafe)
        chat.say("予約名はヤマダ")
        finder.pending.removeLast()(found)
        assertFalse(transport.prompt().contains("いま下調べが届きました"))
        model(cafe)
        assertTrue(ui.waiting)
        assertTrue(transport.prompt().contains("いま下調べが届きました"))
    }

    @Test
    fun aResultDoesNotBreakInOnAQuestion() {
        chat.say(cafeWritten)
        model(cafe, ask = "voicemail")
        finder.pending.removeLast()(found)
        assertFalse(ui.waiting)
        assertEquals(IntakeAsk.VOICEMAIL, ui.question!!.ask)
    }

    @Test
    fun aResultDoesNotBreakInOnWriting() {
        chat.say(cafeWritten)
        model(cafe)
        chat.type("書きかけ")
        finder.pending.removeLast()(found)
        assertFalse(ui.waiting)
    }

    @Test
    fun aRefusalCausedByWhatCameFromTheWebDoesNotEndTheChat() {
        chat.say(cafeWritten)
        model(cafe)
        finder.pending.removeLast()(found)
        transport.pending.removeLast()(IntakeReply.Failed("書き直してください", canRetry = false, dropLastSaid = true))
        assertEquals(null, ui.error)
        assertFalse(ui.waiting)
        chat.say("続けて")
        assertFalse(transport.prompt().contains("検索の本文"))
    }

    @Test
    fun aNumberNobodyWroteIsNotShownInTheReply() {
        chat.say(cafeWritten)
        model(cafe, reply = "番号は 03-1234-5678 のようです")
        assertFalse(ui.lines.last().text.contains("03-1234-5678"))
    }

    @Test
    fun theUsersOwnNumberMayBeInTheReply() {
        filled()
        chat.say("確認して")
        model(everything, reply = "番号は $number ですね")
        assertTrue(ui.lines.last().text.contains(number))
    }

    @Test
    fun aPracticeNeedsNoNumberAndDoesNotArgueAboutOne() {
        var practising = true
        val practice = IntakeChat({ LocalDate.of(2026, 11, 9) }, { Instant.parse("2026-11-09T03:00:00Z") }, transport, search = finder, practice = { practising })
        practice.say("レストラン・サンプル 土曜19時 2名 予約名はヤマダ 03-0000-001")
        assertTrue(transport.bodies.last().contains("いまは練習です"))
        arrive("""{"reply":"はい","fields":{"shop_name":"レストラン・サンプル","phone_number":"03-0000-001","windows":[{"date":"2026-11-14","from":"19:00"}],"party_size":2,"reservation_name":"ヤマダ"},"ask":"none"}""")
        assertEquals(emptyList<String>(), practice.ui.value.missing)
        practice.say("続けて")
        assertFalse(transport.prompt().contains("受け付けられなかった項目"))
        practising = false
        practice.refresh()
        assertTrue(practice.ui.value.missing.toString(), practice.ui.value.missing.contains("電話番号がまだ書かれていません"))
    }

    @Test
    fun aResultLandingBetweenAMessageAndItsAnswerChangesNothing() {
        chat.say(cafeWritten)
        model(cafe)
        chat.say("送れない文")
        finder.pending.removeLast()(found)
        transport.pending.removeLast()(IntakeReply.Failed("書き直してください", canRetry = false, dropLastSaid = true))
        assertEquals("送れない文", ui.typing)
        assertFalse(ui.lines.any { it.text == "送れない文" })
        assertEquals(1, ui.lines.count { it.who == IntakeLine.Who.SEARCH })
    }

    @Test
    fun aQuestionStaysPossibleWhenAResultLandsFirst() {
        chat.say(cafeWritten)
        model(cafe, ask = "voicemail")
        chat.answer(true)
        model(cafe)
        chat.say("留守電はやめて")
        finder.pending.removeLast()(found)
        model(cafe, ask = "voicemail")
        assertEquals("a yes can be asked again after words", IntakeAsk.VOICEMAIL, ui.question!!.ask)
    }

    @Test
    fun aResultForAShopTheChatLeftIsDropped() {
        chat.say(cafeWritten)
        model(cafe)
        val late = finder.pending.removeLast()
        chat.say("バー・サンプルにして")
        model(""""shop_name":"バー・サンプル"""")
        late(found)
        assertTrue(ui.searches.isEmpty())
        assertEquals("the new shop is looked up instead", 2, finder.queries.size)
    }

    @Test
    fun aFailedSearchDoesNotStopTheChat() {
        chat.say(cafeWritten)
        model(cafe)
        finder.pending.removeLast()(IntakeSearchResult.Failed("調べられませんでした"))
        assertFalse(ui.searching)
        assertEquals("調べられませんでした", ui.searchNote)
        assertTrue(chat.say("続けて"))
    }

    @Test
    fun aShopThatIsNotFoundIsAQuietLine() {
        chat.say(cafeWritten)
        model(cafe)
        finder.pending.removeLast()(IntakeSearchResult.NotFound("見つかりませんでした"))
        assertFalse(ui.searching)
        assertFalse(ui.waiting)
        assertEquals("見つかりませんでした", ui.searchNote)
        assertTrue(ui.searches.isEmpty())
    }

    @Test
    fun aNameThatHoldsANumberIsNotSearched() {
        chat.say("カフェ 03-0000-0009 土曜19時 2名")
        model(""""shop_name":"カフェ 03-0000-0009","windows":[{"date":"2026-11-14","from":"19:00"}],"party_size":2""")
        assertEquals(0, finder.queries.size)
    }

    @Test
    fun anythingThatMayBeAPersonIsNotSearched() {
        chat.say("田中さんに土曜19時 2名")
        model(""""shop_name":"田中さん","kind":"other","windows":[{"date":"2026-11-14","from":"19:00"}],"party_size":2""")
        assertEquals(0, finder.queries.size)
    }

    @Test
    fun aSearchThatFailedCanRunAgain() {
        chat.say(cafeWritten)
        model(cafe)
        finder.pending.removeLast()(IntakeSearchResult.Failed("調べられませんでした"))
        chat.say("続けて")
        model(cafe)
        assertEquals(2, finder.queries.size)
    }

    @Test
    fun aShopThatIsNotFoundIsNotSearchedAgain() {
        chat.say(cafeWritten)
        model(cafe)
        finder.pending.removeLast()(IntakeSearchResult.NotFound("見つかりませんでした"))
        chat.say("続けて")
        model(cafe)
        assertEquals(1, finder.queries.size)
    }

    @Test
    fun aSearchRemembersWhichTimesItWasFor() {
        chat.say(cafeWritten)
        model(cafe)
        finder.pending.removeLast()(found)
        assertTrue(ui.searches.single().windows.single().contains("11月14日"))
    }

    @Test
    fun anItemAgreedToCanBeTakenBack() {
        chat.say("$written 初めて伺います")
        model(everything, ask = "may_tell", extra = tells("初めてか" to "初めて"))
        chat.answer(true)
        model(everything)
        chat.say("初めてと言わないで")
        model(everything, ask = "may_tell", extra = tells("初めてか" to "初めて"))
        assertEquals(IntakeAsk.MAY_TELL, ui.question!!.ask)
        chat.answer(false)
        assertEquals("", ui.form!!.mayTell)
    }

    @Test
    fun aNoOnAnItemNamesTheItemToTheModel() {
        chat.say("$written 初めて伺います")
        model(everything, ask = "may_tell", extra = tells("初めてか" to "初めて"))
        chat.answer(false)
        assertTrue(transport.prompt().contains("（初めてか：初めて）：いいえ"))
    }

    @Test
    fun aNumberLikeACardIsNotOffered() {
        chat.say("$written 会員番号は 12345678901234 です")
        model(everything, ask = "may_tell", extra = tells("会員番号" to "12345678901234"))
        assertNull(ui.question)
    }

    @Test
    fun aRangeThatStillHoldsIsKept() {
        filled()
        chat.say("2人から4人でも")
        model(everything, ask = "party_range", extra = ""","party_min":2,"party_max":4""")
        chat.answer(true)
        model(everything)
        chat.say("3人にして")
        model(""""party_size":3""")
        assertEquals("2", ui.form!!.partyMin)
        assertEquals("4", ui.form!!.partyMax)
    }

    @Test
    fun aRangeLostIsSaid() {
        filled()
        chat.say("2人から4人でも")
        model(everything, ask = "party_range", extra = ""","party_min":2,"party_max":4""")
        chat.answer(true)
        model(everything)
        chat.say("6人で")
        model(""""party_size":6""")
        assertTrue(ui.lines.last().text.contains("人数の幅は"))
    }

    @Test
    fun aRangeOfPartySizesIsAskedWithAButton() {
        filled()
        chat.say("2人から4人でもいいです")
        model(everything, ask = "party_range", extra = ""","party_min":2,"party_max":4""")
        assertEquals(2..4, ui.question!!.partyRange)
        assertEquals("nothing until the button", "", ui.form!!.partyMin)
        chat.answer(true)
        assertEquals("2", ui.form!!.partyMin)
        assertEquals("4", ui.form!!.partyMax)
    }

    @Test
    fun aRangeWithoutTheWishedNumberIsNotAsked() {
        filled()
        chat.say("3人から4人でも")
        model(everything, ask = "party_range", extra = ""","party_min":3,"party_max":4""")
        assertNull(ui.question)
    }

    @Test
    fun aRangeIsForgottenWhenTheWishedNumberChanges() {
        filled()
        chat.say("2人から4人でも")
        model(everything, ask = "party_range", extra = ""","party_min":2,"party_max":4""")
        chat.answer(true)
        model(everything)
        chat.say("やっぱり6人で")
        model(""""party_size":6""")
        assertEquals("", ui.form!!.partyMin)
        assertEquals("", ui.form!!.partyMax)
    }

    @Test
    fun whatTheShopMayBeToldWaitsForTheButtonAndIsNotAskedTwice() {
        chat.say("$written 初めて伺います")
        model(everything, ask = "may_tell", extra = tells("初めてか" to "初めて"))
        assertEquals(listOf(IntakeTell("初めてか", "初めて")), ui.question!!.tells)
        assertEquals("お店に聞かれたら、次のことを AI が伝えてよいですか\n初めてか：初めて", ui.question!!.text)
        assertEquals("", ui.form!!.mayTell)
        chat.answer(true)
        assertEquals("初めてか：初めて", ui.form!!.mayTell)
        model(everything, ask = "may_tell", extra = tells("初めてか" to "初めて"))
        assertNull("not asked twice", ui.question)
    }

    private val twoItems = tells("初めてか" to "初めて", "子ども連れか" to "いません")
    private val writtenWithBoth = "$written 初めて伺います。子どもはいません"

    @Test
    fun everythingToTellIsOneCardWithOneAnswer() {
        chat.say(writtenWithBoth)
        model(everything, ask = "may_tell", extra = twoItems)
        assertEquals(listOf(IntakeTell("初めてか", "初めて"), IntakeTell("子ども連れか", "いません")), ui.question!!.tells)
        assertEquals("お店に聞かれたら、次のことを AI が伝えてよいですか（一部だけなら、文で書いてください）\n初めてか：初めて\n子ども連れか：いません", ui.question!!.text)
        assertEquals("nothing until the button", "", ui.form!!.mayTell)
        chat.answer(true)
        assertEquals("初めてか：初めて\n子ども連れか：いません", ui.form!!.mayTell)
        assertTrue(transport.prompt().contains("（初めてか：初めて、子ども連れか：いません）：はい"))
    }

    @Test
    fun aNoTurnsDownTheWholeCard() {
        chat.say(writtenWithBoth)
        model(everything, ask = "may_tell", extra = twoItems)
        chat.answer(false)
        assertEquals("", ui.form!!.mayTell)
        model(everything, ask = "may_tell", extra = twoItems)
        assertNull("the one answer settled them all", ui.question)
    }

    @Test
    fun anItemThatDoesNotPassIsLeftOffTheCard() {
        chat.say("$written 初めて伺います。住所は東京都です。会員番号は 12345678901234")
        model(everything, ask = "may_tell", extra = tells("初めてか" to "初めて", "記念日" to "誕生日", "住所" to "東京都", "会員番号" to "12345678901234"))
        assertEquals(listOf(IntakeTell("初めてか", "初めて")), ui.question!!.tells)
        chat.answer(true)
        assertEquals("初めてか：初めて", ui.form!!.mayTell)
    }

    @Test
    fun noCardIsShownWhenNothingPasses() {
        filled()
        chat.say("進めて")
        model(everything, ask = "may_tell", extra = tells("初めてか" to "初めて", "住所" to "東京都"))
        assertNull(ui.question)
        assertEquals("内容を確かめて、「確認へ」を押してください", ui.lines.last().text)
    }

    @Test
    fun aCardHoldsFiveAndTheBadOnesTakeNoPlace() {
        chat.say("$written a1 b2 c3 d4 e5 f6")
        val items = tells("住所" to "a1", "a" to "a1", "b" to "b2", "c" to "c3", "d" to "d4", "e" to "e5", "f" to "f6")
        model(everything, ask = "may_tell", extra = items)
        assertEquals(listOf("a1", "b2", "c3", "d4", "e5"), ui.question!!.tells.map { it.value })
    }

    @Test
    fun theSameItemTwiceIsOneLine() {
        chat.say("$written 初めて伺います")
        model(everything, ask = "may_tell", extra = tells("初めてか" to "初めて", "初めてか" to "初めて"))
        assertEquals(listOf(IntakeTell("初めてか", "初めて")), ui.question!!.tells)
    }

    @Test
    fun whatWasAskedIsNotOnTheNextCard() {
        chat.say("$written 初めて伺います")
        model(everything, ask = "may_tell", extra = tells("初めてか" to "初めて"))
        chat.answer(true)
        model(everything)
        chat.say("子どもはいません")
        model(everything, ask = "may_tell", extra = twoItems)
        assertEquals("only what is new", listOf(IntakeTell("子ども連れか", "いません")), ui.question!!.tells)
        chat.answer(true)
        assertEquals("初めてか：初めて\n子ども連れか：いません", ui.form!!.mayTell)
    }

    @Test
    fun theSameAnswerToAnotherQuestionIsAnotherItem() {
        chat.say("$written はい")
        model(everything, ask = "may_tell", extra = tells("初めてか" to "はい"))
        chat.answer(true)
        model(everything)
        chat.say("子ども連れもはい")
        model(everything, ask = "may_tell", extra = tells("子ども連れか" to "はい"))
        assertEquals(listOf(IntakeTell("子ども連れか", "はい")), ui.question!!.tells)
        chat.answer(true)
        assertEquals("初めてか：はい\n子ども連れか：はい", ui.form!!.mayTell)
    }

    @Test
    fun aCardCanBeNarrowedInWords() {
        chat.say(writtenWithBoth)
        model(everything, ask = "may_tell", extra = twoItems)
        chat.say("子どものことは言わないで")
        assertTrue("the model sees the card it is narrowing", transport.prompt().contains("初めてか：初めて、子ども連れか：いません"))
        model(everything, ask = "may_tell", extra = tells("初めてか" to "初めて"))
        assertEquals(listOf(IntakeTell("初めてか", "初めて")), ui.question!!.tells)
        chat.answer(true)
        assertEquals("初めてか：初めて", ui.form!!.mayTell)
    }

    @Test
    fun onlyTheItemBroughtUpIsTakenBack() {
        chat.say(writtenWithBoth)
        model(everything, ask = "may_tell", extra = twoItems)
        chat.answer(true)
        model(everything)
        chat.say("初めてと言わないで")
        model(everything, ask = "may_tell", extra = tells("初めてか" to "初めて"))
        chat.answer(false)
        assertEquals("子ども連れか：いません", ui.form!!.mayTell)
    }

    @Test
    fun anItemTurnedDownStaysDownAndTheChatSaysSo() {
        chat.say("$written 初めて伺います")
        model(everything, ask = "may_tell", extra = tells("初めてか" to "初めて"))
        chat.answer(false)
        model(everything)
        chat.say("やっぱり初めてと伝えていいです")
        model(everything, ask = "may_tell", extra = tells("初めてか" to "初めて"))
        assertNull(ui.question)
        assertTrue(ui.lines.last().text.contains("「いいえ」のままです"))
    }

    @Test
    fun anotherQuestionCarriesNoItems() {
        chat.say("$written 初めて伺います")
        model(everything, ask = "voicemail", extra = tells("初めてか" to "初めて"))
        assertEquals(emptyList<IntakeTell>(), ui.question!!.tells)
        chat.answer(true)
        assertEquals("", ui.form!!.mayTell)
        assertTrue(transport.prompt().contains("本人の答え（任せる範囲）：留守番電話の伝言：はい"))
    }

    @Test
    fun whatTheShopWillAskIsAnOrdinaryReplyThatStaysInTheChat() {
        filled()
        chat.say("進めて")
        val asked = "コースかお席だけか、アレルギーや苦手な食材、お子さま連れかどうかを、分かるものだけ教えてください"
        model(everything, reply = asked)
        assertNull(ui.question)
        assertEquals(asked, ui.lines.last().text)
        chat.say("席だけです")
        assertTrue("the model can see it was asked", transport.prompt().contains("受付：$asked"))
    }

    @Test
    fun theAnswersReachWhatTheCallUses() {
        filled()
        chat.say("個室は絶対です。えびアレルギーがあります。席だけで大丈夫です")
        model("""$everything,"required_preferences":["個室"],"notes_for_shop":"えびアレルギーがあります"""", ask = "may_tell", extra = tells("コースか席だけか" to "席だけ"))
        chat.answer(true)
        assertEquals("個室", ui.form!!.requiredPreferences)
        assertEquals("えびアレルギーがあります", ui.form!!.notesForShop)
        assertEquals("コースか席だけか：席だけ", ui.form!!.mayTell)
    }

    @Test
    fun whatTheUserDidNotWriteOrMustNotTellIsNotOffered() {
        chat.say(written)
        model(everything, ask = "may_tell", extra = tells("初めてか" to "初めて"))
        assertNull("not written", ui.question)
        chat.say("住所は東京都です")
        model(everything, ask = "may_tell", extra = tells("住所" to "東京都"))
        assertNull("never told", ui.question)
    }

    @Test
    fun readingsAreKeptOnlyForWhatTheUserWroteInKanji() {
        chat.say("$written 山田太郎です")
        model(everything, extra = ""","readings":[{"text":"山田","kana":"やまだ"},{"text":"佐藤","kana":"さとう"},{"text":"太郎","kana":"tarou"},{"text":"です","kana":"です"}]""")
        assertEquals(listOf(SpokenReading("山田", "やまだ")), ui.form!!.readings)
    }

    @Test
    fun readingsSurviveAButton() {
        chat.say("$written 山田です")
        model(everything, ask = "voicemail", extra = ""","readings":[{"text":"山田","kana":"やまだ"}]""")
        chat.answer(true)
        assertEquals(listOf(SpokenReading("山田", "やまだ")), ui.form!!.readings)
    }

    @Test
    fun clearedTakesAValueBack() {
        chat.say("$written 連絡先は 090-0000-0002")
        model("""$everything,"callback_number":"090-0000-0002"""")
        chat.say("連絡先は伝えないで")
        model(extra = ""","cleared":["callback_number"]""")
        assertEquals("", ui.form!!.callbackNumber)
        assertEquals("the rest stays", "レストラン・サンプル", ui.form!!.businessName)
    }

    @Test
    fun clearedWinsOverAValueSentWithIt() {
        chat.say("$written 連絡先は 090-0000-0002")
        model("""$everything,"callback_number":"090-0000-0002"""", extra = ""","cleared":["callback_number"]""")
        assertEquals("", ui.form!!.callbackNumber)
    }

    @Test
    fun anEmptyListTakesConditionsBack() {
        chat.say(written)
        model("""$everything,"required_preferences":["個室"]""")
        assertEquals("個室", ui.form!!.requiredPreferences)
        chat.say("個室はいいです")
        model(""""required_preferences":[]""")
        assertEquals("", ui.form!!.requiredPreferences)
    }

    @Test
    fun anotherPurposeDoesNotKeepTheLastNote() {
        chat.say("$written の問い合わせ")
        model(""""shop_name":"レストラン・サンプル","phone_number":"$number","purpose":"inquiry","notes_for_shop":"営業時間"""")
        assertEquals("営業時間", ui.form!!.notesForShop)
        chat.say("やっぱり取り消しで")
        model(""""purpose":"cancel"""")
        assertEquals("", ui.form!!.notesForShop)
    }

    @Test
    fun somethingAgreedToCanBeAskedAgainAndTakenBack() {
        filled()
        chat.say("進めて")
        model(everything, ask = "prepayment")
        chat.answer(true)
        model(everything)
        chat.say("やっぱり前払いはやめて")
        model(everything, ask = "prepayment")
        assertEquals(IntakeAsk.PREPAYMENT, ui.question!!.ask)
        chat.answer(false)
        assertFalse(ui.form!!.mayAcceptPrepayment)
    }

    @Test
    fun aBudgetCanBeChanged() {
        filled()
        chat.say("1人8000円まで")
        model(everything, ask = "budget", extra = ""","budget_yen":8000""")
        chat.answer(true)
        model(everything)
        chat.say("5000円にして")
        model(everything, ask = "budget", extra = ""","budget_yen":5000""")
        chat.answer(true)
        assertEquals("5000", ui.form!!.maxBudgetPerPersonYen)
    }

    @Test
    fun anAskThatCannotBeShownDoesNotLeaveAHollowLine() {
        filled()
        chat.say("進めて")
        model(everything, ask = "budget", reply = "いくつか確認します")
        assertEquals("内容を確かめて、「確認へ」を押してください", ui.lines.last().text)
    }

    @Test
    fun whatIsBeingWrittenStaysUntilItIsSent() {
        chat.type("書きかけ")
        assertEquals("書きかけ", ui.typing)
        chat.say("書きかけ")
        assertEquals("", ui.typing)
    }

    @Test
    fun aTextThatCannotBeSentGoesBackToTheInput() {
        chat.say("送れない文")
        transport.pending.removeLast()(IntakeReply.Failed("書き直してください", canRetry = false, dropLastSaid = true))
        assertEquals(emptyList<IntakeLine>(), ui.lines)
        assertEquals("送れない文", ui.typing)
        assertFalse(ui.retryable)
        chat.retry()
        assertEquals(1, transport.bodies.size)
    }

    @Test
    fun aTextThatIsTooLongOffersNoRetry() {
        filled()
        chat.say("あ".repeat(501))
        assertFalse(ui.retryable)
        chat.retry()
        assertEquals(1, transport.bodies.size)
    }

    @Test
    fun nothingUnderstoodMeansNothingToTakeToTheForm() {
        chat.say("こんにちは")
        model()
        assertFalse(ui.accepted)
        chat.say(written)
        model(everything)
        assertTrue(ui.accepted)
    }

    @Test
    fun aValueRefusedNowDoesNotWipeOutOneAcceptedBefore() {
        filled()
        chat.say("続き")
        model(""""shop_name":"作った店名","phone_number":"03-9999-9999"""")
        assertEquals("レストラン・サンプル", ui.form!!.businessName)
        assertEquals(number, ui.form!!.phoneNumber)
        chat.say("続き")
        assertTrue(transport.prompt().contains("受け付けられなかった項目"))
    }

    @Test
    fun aRefusedFieldIsToldToTheModelAndGivenUpAfterTwice() {
        chat.say("$number に")
        model(""""shop_name":"作った店名"""")
        chat.say("続き")
        assertTrue(transport.prompt().contains("受け付けられなかった項目（本人が書いた文字のとおりに入れ直すか、本人に聞いてください）：店名"))
        model(""""shop_name":"作った店名"""")
        assertEquals(listOf("店名"), ui.forTheForm)
        chat.say("続き")
        assertTrue(transport.prompt().contains("もう聞かない項目（本人が手で入れます）：店名"))
    }

    @Test
    fun aFieldTheUserThenWritesIsTakenAfterAll() {
        chat.say("最初")
        model(""""shop_name":"レストラン"""")
        assertEquals("", ui.form!!.businessName)
        chat.say("店はレストランです")
        model(""""shop_name":"レストラン"""")
        assertEquals("レストラン", ui.form!!.businessName)
        assertEquals(emptyList<String>(), ui.forTheForm)
    }

    @Test
    fun aLongChatEnds() {
        repeat(30) {
            assertTrue(chat.say("その $it"))
            model()
        }
        assertFalse(chat.say("もう一つ"))
        assertTrue(ui.full)
    }
}
