// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import io.github.incallagent.core.policy.CallOutcome
import io.github.incallagent.core.policy.ConfirmedBooking
import io.github.incallagent.core.policy.OutcomeCheck
import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.call.CallSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.LocalDateTime

class LastResultStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = LastResultStore(tmp.root)
    private fun file() = File(tmp.root, LastResultStore.FILE)

    // Everything is fictitious
    private fun saved(summary: String = "予約できました", talkMs: Long? = 95_000) = SavedResult(
        result = CallSession.Result(
            outcome = CallOutcome(
                policyId = "policy",
                status = OutcomeStatus.BOOKED,
                booking = ConfirmedBooking(
                    start = LocalDateTime.of(2026, 11, 14, 19, 0),
                    partySize = 2,
                    nameUsed = "ヤマダ",
                    confirmedPreferences = listOf("禁煙席"),
                    shopConditions = listOf("2時間制"),
                ),
                pendingItems = listOf("至急：折り返してください"),
                summary = summary,
                endedAt = Instant.parse("2026-11-08T10:05:00Z"),
            ),
            violations = listOf(OutcomeCheck.Violation("booking.partySize", "許可した人数の範囲外です：4")),
            reportedByAi = true,
            endReason = CallSession.EndReason.AI_HUNG_UP,
            detail = "AI が用件を終えて電話を切りました",
            answered = true,
            talkMs = talkMs,
            purpose = Purpose.CHANGE,
        ),
        stage = RotepApp.Stage.L3,
        shopName = "レストラン・サンプル",
    )

    @Test
    fun whatWasSavedComesBackWhole() {
        assertTrue(store().save(saved()))
        // Another start of the app reads it too
        assertEquals(saved(), store().read())
    }

    @Test
    fun aCallThatWasNeverAnsweredHasNoTalkTime() {
        store().save(saved(talkMs = null))
        assertNull(store().read()!!.result.talkMs)
    }

    @Test
    fun onlyTheLastOneIsKept() {
        val s = store()
        s.save(saved("前の電話"))
        s.save(saved("次の電話"))
        assertEquals("次の電話", s.read()!!.result.outcome.summary)
        assertEquals(listOf(LastResultStore.FILE), tmp.root.list()!!.toList())
    }

    @Test
    fun nothingSavedReadsAsNothing() {
        assertNull(store().read())
    }

    @Test
    fun aDamagedFileIsGivenUp() {
        file().writeText("{ this is not the file")
        assertNull(store().read())
        assertFalse(file().exists())
    }

    @Test
    fun aFormFromAnotherVersionIsKept() {
        file().writeText(SavedResultJson.encode(saved()).replace("\"version\":1", "\"version\":99"))
        assertNull(store().read())
        assertTrue(file().exists())
    }

    @Test
    fun aFailedSaveLeavesWhatWasKept() {
        val s = store()
        s.save(saved("前の電話"))
        // The new file cannot be made where a folder of its name is in the way
        File(tmp.root, "${LastResultStore.FILE}.tmp").mkdir()
        assertFalse(s.save(saved("次の電話")))
        assertEquals("前の電話", s.read()!!.result.outcome.summary)
    }

    @Test
    fun clearLeavesNothing() {
        val s = store()
        s.save(saved())
        s.clear()
        assertNull(s.read())
        assertTrue(tmp.root.list()!!.isEmpty())
    }
}
