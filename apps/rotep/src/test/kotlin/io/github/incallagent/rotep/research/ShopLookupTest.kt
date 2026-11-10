// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.research

import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.practice.PracticePolicy
import io.github.incallagent.rotep.ui.LookupUi
import io.github.incallagent.rotep.ui.preview.PreviewFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShopLookupTest {
    private val practice = PreviewFixtures.policy
    private val real = practice.copy(target = practice.target.copy(businessName = "テスト食堂", phoneNumber = "03-1111-2222"))

    // --- what is sent ---

    @Test
    fun theQuestionNamesTheShopAndTheWishedForTimes() {
        val p = ShopLookupMessages.prompt(real)
        assertTrue(p, p.contains("テスト食堂"))
        assertTrue(p, p.contains("03-1111-2222"))
        assertTrue(p, p.contains("希望の日時："))
        assertTrue(p, p.contains("人数：2名"))
        assertTrue("the first answer says whether it fits", p.contains("「合いそうです」「合わないようです」「分かりません」"))
        assertTrue("not another shop of the same name", p.contains("電話番号が合うお店"))
    }

    @Test
    fun nothingAboutThePersonIsSent() {
        val p = ShopLookupMessages.prompt(real)
        real.disclosure.reservationName?.let { assertFalse(it, p.contains(it)) }
        real.disclosure.callbackNumber?.let { assertFalse(it, p.contains(it)) }
        for (item in real.disclosure.mayTell) assertFalse(item.value, p.contains(item.value))
    }

    @Test
    fun whatTheUserWroteInFreeTextIsNotSent() {
        for (purpose in listOf(Purpose.NEW_BOOKING, Purpose.CHANGE, Purpose.INQUIRY)) {
            val p = ShopLookupMessages.prompt(real.copy(purpose = purpose, request = real.request.copy(notesForShop = "目印の文")))
            assertFalse("$purpose", p.contains("目印の文"))
            assertFalse("$purpose", p.contains("聞きたいこと"))
        }
    }

    @Test
    fun withoutAnyTimeItDoesNotAskIfTheTimeFits() {
        val p = ShopLookupMessages.prompt(real.copy(purpose = Purpose.INQUIRY, request = real.request.copy(windows = emptyList())))
        assertFalse(p, p.contains("行けそうか"))
        assertTrue(p, p.contains("1. 営業時間と定休日"))
    }

    @Test
    fun theRequestAsksForAGoogleSearch() {
        val body = Json.parseToJsonElement(ShopLookupMessages.body("質問")).jsonObject
        assertEquals(ShopLookupMessages.MODEL, body["model"]!!.jsonPrimitive.content)
        assertEquals("質問", body["input"]!!.jsonPrimitive.content)
        assertEquals("google_search", body["tools"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
    }

    // --- what comes back ---

    private val answer = """
        {"steps":[
          {"type":"thought","summary":[{"type":"text","text":"考え中"}],"signature":"x"},
          {"type":"google_search_call","arguments":{"queries":["テスト食堂 営業時間"]}},
          {"type":"google_search_result","call_id":"1","result":[{"search_suggestions":"<div>候補</div>"}]},
          {"type":"model_output","content":[{"type":"text","text":"1. 合わないようです。19:00 開店です。\n",
             "annotations":[{"type":"url_citation","url":"https://example.com/a","title":"お店のサイト","start_index":0,"end_index":5},
                            {"type":"url_citation","url":"http://example.com/b","title":"http は開かない"},
                            {"type":"url_citation","url":"https://example.com/a","title":"同じ"},
                            {"type":"place_citation","name":"別の種類","url":"https://example.com/c"}]}]}
        ],"unknown":1}
    """.trimIndent()

    @Test
    fun theAnswerIsKeptAsItCameWithItsSuggestions() {
        val f = ShopLookupMessages.parse(answer)!!
        assertEquals("unchanged, with its line end", "1. 合わないようです。19:00 開店です。\n", f.text)
        assertEquals(listOf("<div>候補</div>"), f.suggestionsHtml)
    }

    @Test
    fun onlyHttpsCitationsAreKeptOnce() {
        val f = ShopLookupMessages.parse(answer)!!
        assertEquals(listOf(ShopFindings.Source("お店のサイト", "https://example.com/a")), f.sources)
    }

    private val search = """{"type":"google_search_result","result":[{"search_suggestions":"<b>s</b>"}]}"""

    @Test
    fun textFromBeforeTheSearchIsNotTheAnswer() {
        val json = """{"steps":[{"type":"model_output","content":[{"type":"text","text":"調べます"}]},$search,
            {"type":"model_output","content":[{"type":"text","text":"最後の"}]},
            {"type":"thought"},
            {"type":"model_output","content":[{"type":"text","text":"答え"}]}]}"""
        assertEquals("最後の答え", ShopLookupMessages.parse(json)!!.text)
    }

    @Test
    fun everySuggestionIsKeptOnceAndAResultMayBeASingleObject() {
        val json = """{"steps":[$search,{"type":"google_search_result","result":{"search_suggestions":"<i>t</i>"}},$search,
            {"type":"model_output","content":[{"type":"text","text":"答え"}]}]}"""
        assertEquals(listOf("<b>s</b>", "<i>t</i>"), ShopLookupMessages.parse(json)!!.suggestionsHtml)
    }

    @Test
    fun withoutSuggestionsThereIsNoResultToShow() {
        assertNull(ShopLookupMessages.parse("""{"steps":[{"type":"model_output","content":[{"type":"text","text":"記憶だけの答え"}]}]}"""))
        assertNull(ShopLookupMessages.parse("""{"steps":[{"type":"google_search_result","result":[{"search_suggestions":"  "}]},
            {"type":"model_output","content":[{"type":"text","text":"答え"}]}]}"""))
    }

    @Test
    fun anAnswerThatWasCutOffIsNotShown() {
        assertNull(ShopLookupMessages.parse("""{"status":"incomplete","steps":[$search,{"type":"model_output","content":[{"type":"text","text":"途中で"}]}]}"""))
    }

    @Test
    fun anAnswerWithoutTextOrInAnotherShapeIsNoAnswer() {
        assertNull(ShopLookupMessages.parse("""{"steps":[{"type":"google_search_call"}]}"""))
        assertNull(ShopLookupMessages.parse("""{"steps":[$search,{"type":"model_output","content":[{"type":"text","text":"  "}]}]}"""))
        assertNull(ShopLookupMessages.parse("""{"other":true}"""))
        assertNull(ShopLookupMessages.parse("not json"))
    }

    @Test
    fun aTitleIsNotRequired() {
        val json = """{"steps":[$search,{"type":"model_output","content":[{"type":"text","text":"答え",
            "annotations":[{"type":"url_citation","url":"https://example.com/x"}]}]}]}"""
        assertEquals("https://example.com/x", ShopLookupMessages.parse(json)!!.sources.single().title)
    }

    // --- when it is used, and what it holds back ---

    @Test
    fun aCancelAndThePracticeShopAreNotLookedUp() {
        assertTrue(ShopLookup.wanted(real))
        assertTrue(ShopLookup.wanted(real.copy(purpose = Purpose.INQUIRY)))
        assertFalse(ShopLookup.wanted(real.copy(purpose = Purpose.CANCEL)))
        assertEquals(PracticePolicy.PHONE_NUMBER, practice.target.phoneNumber)
        assertFalse(ShopLookup.wanted(practice))
        assertFalse("the same number written differently", ShopLookup.wanted(practice.copy(target = practice.target.copy(phoneNumber = "0300000000"))))
    }

    private val findings = ShopFindings("答え", emptyList(), listOf("<b>s</b>"))

    @Test
    fun theApprovalWaitsForTheSearchAndForTheUserToReadIt() {
        assertNotNull(LookupUi.Searching.holdsApproval)
        assertNotNull(LookupUi.Found(findings).holdsApproval)
        assertNull(LookupUi.Found(findings, reviewed = true).holdsApproval)
    }

    @Test
    fun aSearchThatFailedDoesNotStopTheCall() {
        assertNull(LookupUi.NotFound("調べられませんでした").holdsApproval)
        assertNull(LookupUi.None.holdsApproval)
    }

    @Test
    fun nothingIsHeldBackWhileTheUserHasNotAskedForALookUp() {
        assertNull("the button is optional", LookupUi.Available.holdsApproval)
    }

    @Test
    fun theConfirmScreenFollowsTheLookup() {
        val state = PreviewFixtures.confirmL0.copy(blocker = null, problems = emptyList())
        assertTrue(state.canApprove)
        val waiting = state.copy(lookup = LookupUi.Found(findings))
        assertFalse(waiting.canApprove)
        assertEquals(LookupUi.Found(findings).holdsApproval, waiting.disabledReason)
        assertTrue(state.copy(lookup = LookupUi.Found(findings, reviewed = true)).canApprove)
    }
}
