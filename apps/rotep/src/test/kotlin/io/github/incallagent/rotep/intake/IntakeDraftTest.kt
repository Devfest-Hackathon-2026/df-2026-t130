// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.intake

import io.github.incallagent.core.policy.BusinessKind
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.intake.IntakeDraft.Said
import io.github.incallagent.rotep.ui.WindowInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class IntakeDraftTest {
    private val today = LocalDate.of(2026, 11, 9)
    private val number = "03-0000-0001"

    private fun build(json: String, vararg said: String, permissions: IntakePermissions = IntakePermissions()) =
        IntakeDraft.build(Json.parseToJsonElement(json).jsonObject, said.toList(), today, permissions)

    private val whatTheUserWrote = "レストラン・サンプルに電話して。番号は $number、予約名はヤマダ、連絡先は 090-0000-0002"

    @Test
    fun whatTheUserWroteIsTaken() {
        val built = build(
            """{"shop_name":"レストラン・サンプル","phone_number":"$number","reservation_name":"ヤマダ","callback_number":"090-0000-0002",
            "purpose":"new_booking","kind":"salon","party_size":2,"duration_minutes":90,
            "windows":[{"date":"2026-11-14","from":"19:00","to":"20:00"}],
            "required_preferences":["禁煙席"],"optional_preferences":["個室"],"notes_for_shop":"誕生日です"}""",
            whatTheUserWrote,
        )
        val form = built.form
        assertEquals(emptySet<Said>(), built.notAccepted)
        assertEquals("レストラン・サンプル", form.businessName)
        assertEquals(number, form.phoneNumber)
        assertEquals("ヤマダ", form.reservationName)
        assertEquals("090-0000-0002", form.callbackNumber)
        assertEquals(BusinessKind.SALON, form.kind)
        assertEquals("2", form.partyPreferred)
        assertEquals("90", form.durationMinutes)
        assertEquals(listOf(WindowInput("2026-11-14", "19:00", "20:00")), form.windows)
        assertEquals("禁煙席", form.requiredPreferences)
        assertEquals("個室", form.optionalPreferences)
        assertEquals("誕生日です", form.notesForShop)
    }

    @Test
    fun whatTheModelMadeUpIsLeftOut() {
        val built = build(
            """{"shop_name":"寿司よこはま","phone_number":"03-9999-9999","reservation_name":"田中","callback_number":"090-9999-9999","party_size":2}""",
            whatTheUserWrote,
        )
        assertEquals(setOf(Said.SHOP, Said.PHONE, Said.NAME, Said.CALLBACK), built.notAccepted)
        assertEquals("", built.form.businessName)
        assertEquals("", built.form.phoneNumber)
        assertEquals("", built.form.reservationName)
        assertEquals("", built.form.callbackNumber)
        assertEquals("the rest is kept", "2", built.form.partyPreferred)
    }

    @Test
    fun aNameCanBeWrittenAlittleDifferentlyButNotBeAnotherName() {
        assertEquals(emptySet<Said>(), build("""{"reservation_name":"ﾔﾏﾀﾞ"}""", "やまだ").notAccepted)
        assertEquals(emptySet<Said>(), build("""{"shop_name":"Bar ABC"}""", "ｂａｒ　ａｂｃに").notAccepted)
        assertEquals(setOf(Said.NAME), build("""{"reservation_name":"山田"}""", "やまだ").notAccepted)
    }

    @Test
    fun numbersAreComparedAsNumbers() {
        for (written in listOf("03-0000-0001", "０３－００００－０００１", "(03) 0000 0001", "0300000001", "+81 3 0000 0001", "03ー0000ー0001")) {
            assertEquals(written, emptySet<Said>(), build("""{"phone_number":"$number"}""", "番号は $written です").notAccepted)
        }
    }

    @Test
    fun aNumberAFewDigitsShortOfWhatWasWrittenIsNotWhatWasWritten() {
        assertEquals(setOf(Said.PHONE), build("""{"phone_number":"090-1234-567"}""", "番号は 090-1234-5678").notAccepted)
    }

    @Test
    fun aNumberFollowedByOtherFiguresIsStillFound() {
        assertEquals(emptySet<Said>(), build("""{"phone_number":"03-1234-5678"}""", "03-1234-5678 2名で").notAccepted)
    }

    @Test
    fun aNumberCopiedInFullWidthIsMadePlainForTheChecks() {
        val built = build("""{"phone_number":"０３ー００００ー０００１"}""", "番号は ０３ー００００ー０００１")
        assertEquals(emptySet<Said>(), built.notAccepted)
        assertEquals(number, built.form.phoneNumber)
    }

    @Test
    fun aRangeOfPartySizesIsLeftToTheForm() {
        val form = build("""{"party_size":4,"party_min":2,"party_max":6}""").form
        assertEquals("4", form.partyPreferred)
        assertEquals("", form.partyMin)
        assertEquals("", form.partyMax)
    }

    @Test
    fun otherSeparatorsAreNumbersToo() {
        for (written in listOf("03―0000―0001", "03.0000.0001", "03・0000・0001", "(03)0000-0001")) {
            assertEquals(written, emptySet<Said>(), build("""{"phone_number":"$written"}""", "番号は $written です").notAccepted)
        }
        assertEquals(number, build("""{"phone_number":"03―0000―0001"}""", "番号は 03―0000―0001").form.phoneNumber)
    }

    @Test
    fun aTimeInALooseSpellingIsStillAWindow() {
        val form = build("""{"windows":[{"date":"2026-11-14","from":"9:00","to":"9:30"}]}""").form
        assertEquals(listOf(WindowInput("2026-11-14", "09:00", "09:30")), form.windows)
    }

    @Test
    fun whatCannotBeUsedIsNamed() {
        val six = (1..6).joinToString(",") { "\"条件$it\"" }
        assertEquals(setOf("required_preferences"), build("""{"required_preferences":[$six],"party_size":2}""").unusable)
        assertEquals(setOf("windows"), build("""{"windows":[{"date":"2020-01-01","from":"19:00"}]}""").unusable)
    }

    @Test
    fun aNumberNobodyWroteDoesNotReachWhatIsToldToTheShop() {
        val built = build("""{"notes_for_shop":"折り返しは 03-1234-5678 へ","party_size":2}""", "誕生日です")
        assertEquals("", built.form.notesForShop)
        assertEquals(setOf("notes_for_shop"), built.unusable)
    }

    @Test
    fun anotherNumberNextToOneTheUserWroteIsCaught() {
        val said = listOf("連絡先は 090-0000-1111")
        assertTrue(IntakeGrounding.unsaidNumber("折り返しは 090-0000-1111 03-0000-2222 まで", said))
        assertFalse(IntakeGrounding.unsaidNumber("折り返しは 090-0000-1111 2名", said))
        assertFalse(IntakeGrounding.unsaidNumber("折り返しは 090-0000-1111 まで", said))
    }

    @Test
    fun theOtherWaysToWriteANumberAreCaught() {
        val said = listOf("予約です")
        for (spelled in listOf("03/0000/2222", "03〜0000〜2222", "03～0000～2222", "81-3-0000-2222")) {
            assertTrue(spelled, IntakeGrounding.unsaidNumber("折り返しは $spelled", said))
        }
    }

    @Test
    fun theUsersOwnNumberWrittenThoseWaysIsNotCaught() {
        assertFalse(IntakeGrounding.unsaidNumber("折り返しは 03-0000-2222", listOf("03/0000/2222 です")))
        assertFalse(IntakeGrounding.unsaidNumber("折り返しは 81-3-0000-2222", listOf("+81-3-0000-2222 です")))
    }

    @Test
    fun aNumberTheUserWroteMayBeToldToTheShop() {
        val built = build("""{"notes_for_shop":"折り返しは 03-1234-5678 へ"}""", "折り返しは 03-1234-5678 へ")
        assertEquals("折り返しは 03-1234-5678 へ", built.form.notesForShop)
    }

    @Test
    fun whatWasAgreedToReachesTheForm() {
        val permissions = IntakePermissions()
            .answering(IntakeAsk.PARTY_RANGE, agreed = true, budget = null, range = 2..4)
            .answering(IntakeAsk.MAY_TELL, agreed = true, budget = null, items = listOf(IntakeTell("初めてか", "はい")))
            .answering(IntakeAsk.MAY_TELL, agreed = false, budget = null, items = listOf(IntakeTell("記念日", "誕生日")))
        val form = build("{}", permissions = permissions).form
        assertEquals("2", form.partyMin)
        assertEquals("4", form.partyMax)
        assertEquals("初めてか：はい", form.mayTell)
        assertEquals(setOf(IntakeTell("初めてか", "はい"), IntakeTell("記念日", "誕生日")), permissions.tellAsked)
    }

    private fun tellingAll(agreed: Boolean, vararg items: IntakeTell, from: IntakePermissions = IntakePermissions()) =
        from.answering(IntakeAsk.MAY_TELL, agreed, budget = null, items = items.toList())

    @Test
    fun oneAnswerSettlesAWholeCard() {
        val first = IntakeTell("初めてか", "はい")
        val second = IntakeTell("子ども連れか", "はい")
        val yes = tellingAll(true, first, second)
        assertEquals("初めてか：はい\n子ども連れか：はい", build("{}", permissions = yes).form.mayTell)
        assertEquals("初めてか：はい、子ども連れか：はい", IntakeDraft.summary(build("{}", permissions = yes).form, yes).toMap()["聞かれたら伝えること"])
        val no = tellingAll(false, first, second)
        assertEquals("", build("{}", permissions = no).form.mayTell)
        assertEquals("both were asked", setOf(first, second), no.tellAsked)
    }

    @Test
    fun anAnswerAboutWhatToTellLeavesTheOtherQuestionsOpen() {
        val yes = tellingAll(true, IntakeTell("初めてか", "はい"))
        assertEquals(emptySet<IntakeAsk>(), yes.answered)
        assertEquals(emptySet<IntakeAsk>(), yes.yes)
    }

    @Test
    fun aNewValueForALabelReplacesTheOldOne() {
        val agreed = tellingAll(true, IntakeTell("アレルギー", "なし"), IntakeTell("初めてか", "はい"))
        val prawn = IntakeTell("アレルギー", "えび")
        assertEquals(listOf(IntakeTell("初めてか", "はい"), prawn), tellingAll(true, prawn, from = agreed).tell)
        assertEquals("the old answer is out of date even on a no", listOf(IntakeTell("初めてか", "はい")), tellingAll(false, prawn, from = agreed).tell)
    }

    @Test
    fun theSummaryShowsOnlyWhatAppliesToTheRequest() {
        val permissions = IntakePermissions()
            .answering(IntakeAsk.PREPAYMENT, agreed = true, budget = null)
            .answering(IntakeAsk.CANCELLATION_POLICY, agreed = true, budget = null)
            .answering(IntakeAsk.BUDGET, agreed = true, budget = 8000)
        val summary = IntakeDraft.summary(build("""{"purpose":"cancel"}""", permissions = permissions).form, permissions).toMap()
        assertEquals("キャンセル料", summary["AI に任せること"])
        assertEquals(null, summary["予算"])
    }

    @Test
    fun digitsFromDifferentMessagesDoNotMakeANumber() {
        assertEquals(setOf(Said.PHONE), build("""{"phone_number":"$number"}""", "03-0000", "0001").notAccepted)
    }

    @Test
    fun theSameNumberForTheShopAndTheContactIsAMixUp() {
        val built = build("""{"phone_number":"$number","callback_number":"$number"}""", "番号は $number")
        assertEquals(setOf(Said.PHONE, Said.CALLBACK), built.notAccepted)
        assertEquals("", built.form.phoneNumber)
    }

    @Test
    fun whatWouldGiveTheAiMoreRoomIsIgnored() {
        val form = build(
            """{"may_accept_outside_windows":true,"may_accept_cancellation_policy":true,"may_accept_prepayment":true,
            "may_leave_voicemail":true,"approval":{"approvedAt":"2026-11-09T00:00:00Z"},"max_budget_per_person_yen":100000,
            "budget_yen":100000,"may_tell":[{"label":"住所","value":"x"}],"max_call_minutes":60,"party_size":2}""",
        ).form
        assertFalse(form.mayAcceptOutsideWindows)
        assertFalse(form.mayAcceptCancellationPolicy)
        assertFalse(form.mayAcceptPrepayment)
        assertFalse(form.mayLeaveVoicemail)
        assertEquals("", form.maxBudgetPerPersonYen)
        assertEquals("", form.mayTell)
        assertEquals("5", form.maxCallMinutes)
        assertEquals("the valid field is still read", "2", form.partyPreferred)
    }

    @Test
    fun onlyTheUsersAnswersGiveTheAiRoom() {
        val permissions = IntakePermissions()
            .answering(IntakeAsk.OUTSIDE_WINDOWS, agreed = true, budget = null)
            .answering(IntakeAsk.PREPAYMENT, agreed = false, budget = null)
            .answering(IntakeAsk.BUDGET, agreed = true, budget = 8000)
        val form = build("{}", permissions = permissions).form
        assertTrue(form.mayAcceptOutsideWindows)
        assertFalse(form.mayAcceptPrepayment)
        assertEquals("8000", form.maxBudgetPerPersonYen)
    }

    @Test
    fun aBudgetTurnedDownIsNotKept() {
        val permissions = IntakePermissions().answering(IntakeAsk.BUDGET, agreed = false, budget = 8000)
        assertEquals("", build("{}", permissions = permissions).form.maxBudgetPerPersonYen)
    }

    @Test
    fun aBadFieldIsLeftOutAloneAndTheRestIsKept() {
        val form = build("""{"party_size":"two","kind":"bank","duration_minutes":90}""").form
        assertEquals("", form.partyPreferred)
        assertEquals(BusinessKind.RESTAURANT, form.kind)
        assertEquals("90", form.durationMinutes)
    }

    @Test
    fun hiddenCharactersDoNotGetThrough() {
        val form = build("""{"notes_for_shop":"a‮b","duration_minutes":90}""").form
        assertEquals("", form.notesForShop)
        assertEquals("90", form.durationMinutes)
    }

    @Test
    fun aCancelOrAnInquiryTakesNoBookingFields() {
        val form = build(
            """{"purpose":"cancel","windows":[{"date":"2026-11-14","from":"19:00"}],"party_size":2,"notes_for_shop":"14日の予約"}""",
        ).form
        assertEquals(Purpose.CANCEL, form.purpose)
        assertEquals("", form.partyPreferred)
        assertEquals(listOf(WindowInput()), form.windows)
        assertEquals("14日の予約", form.notesForShop)
    }

    @Test
    fun windowsOutsideTheNextYearOrBackwardsAreLeftOut() {
        val form = build(
            """{"windows":[
            {"date":"2026-11-08","from":"19:00"},
            {"date":"2027-11-10","from":"19:00"},
            {"date":"2026-13-01","from":"19:00"},
            {"date":"2026-11-14","from":"20:00","to":"19:00"},
            {"date":"11/14","from":"19:00"},
            {"date":"2026-11-14","from":"25:00"},
            {"date":"2026-11-15","from":"19:00"}]}""",
        ).form
        assertEquals(listOf(WindowInput("2026-11-15", "19:00", "")), form.windows)
    }

    @Test
    fun nullsAndBlanksAreNotValues() {
        val built = build("""{"shop_name":null,"phone_number":"  ","windows":[],"party_size":null}""")
        assertEquals(emptySet<Said>(), built.notAccepted)
    }

    @Test
    fun theSummaryGivesTheWeekdayFromTheApp() {
        val form = build("""{"windows":[{"date":"2026-11-14","from":"19:00","to":"20:00"}],"party_size":2}""").form
        val summary = IntakeDraft.summary(form, IntakePermissions()).toMap()
        assertEquals("11月14日(土) 19:00〜20:00 の間に開始", summary["希望の日時"])
        assertEquals("2名", summary["人数"])
    }

    @Test
    fun theSummarySaysWhatTheAiIsTrustedWith() {
        val permissions = IntakePermissions()
            .answering(IntakeAsk.VOICEMAIL, agreed = true, budget = null)
            .answering(IntakeAsk.BUDGET, agreed = true, budget = 8000)
        val summary = IntakeDraft.summary(build("{}", permissions = permissions).form, permissions).toMap()
        assertEquals("留守番電話の伝言", summary["AI に任せること"])
        assertEquals("1人 8000円まで", summary["予算"])
    }
}
