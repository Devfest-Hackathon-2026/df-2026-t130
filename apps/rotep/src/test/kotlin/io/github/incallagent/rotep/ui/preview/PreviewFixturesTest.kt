// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.preview

import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.rotep.call.DialCheck
import io.github.incallagent.rotep.practice.PracticePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The made-up states stay made up, and each one shows what its name says. */
class PreviewFixturesTest {
    @Test
    fun everySampleCallsTheFictitiousNumberThatIsNeverDialed() {
        for (p in PreviewFixtures.policies) {
            assertEquals(PracticePolicy.PHONE_NUMBER, p.target.phoneNumber)
            val n = p.target.phoneNumber
            assertTrue(DialCheck.check(n, n) is DialCheck.Result.Refused)
        }
    }

    @Test
    fun eachSampleShowsWhatItIsFor() {
        assertEquals(emptyList<Any>(), PreviewFixtures.confirmL0.problems)
        assertTrue(PreviewFixtures.confirmProblems.problems.isNotEmpty())
        assertNotNull(PreviewFixtures.confirmL2Practice.blocker)
        assertTrue(PreviewFixtures.badFormErrors.isNotEmpty())
        assertEquals(emptyList<Any>(), PreviewFixtures.resultBooked.violations)
        assertTrue(PreviewFixtures.resultBookedOutside.violations.isNotEmpty())
        assertEquals(OutcomeStatus.BOOKED, PreviewFixtures.resultBookedOutside.outcome.status)
        for (r in PreviewFixtures.results.filter { it != PreviewFixtures.resultBookedOutside }) {
            assertEquals(r.outcome.status.name, emptyList<Any>(), r.violations)
        }
    }

    @Test
    fun sampleIdsAreUnique() {
        val ids = Samples.all.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
    }
}
