// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.schedule

import io.github.incallagent.rotep.app.ApiKeyStore
import io.github.incallagent.rotep.app.SecretCipher
import io.github.incallagent.rotep.call.SpokenReading
import io.github.incallagent.rotep.practice.PracticePolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate

class ScheduledCallsTest {
    private val today = LocalDate.of(2026, 11, 9)
    private var now: Instant = ScheduleRules.instantOf(today.atTime(10, 0))
    private val fireAt: Instant = ScheduleRules.instantOf(today.atTime(11, 0))
    private val call = ScheduledCall(PracticePolicy.approved(today, now), fireAt, listOf(SpokenReading("鮨", "すし")))

    /** Reversible, and marks the data as not being the plain text. */
    private class FakeCipher : SecretCipher {
        var keyDeleted = 0
        var temporaryFailures = 0

        override fun encrypt(plain: ByteArray) = plain.map { (it + 1).toByte() }.toByteArray()

        override fun decrypt(data: ByteArray): ByteArray {
            if (temporaryFailures > 0) {
                temporaryFailures--
                throw IOException("busy")
            }
            return data.map { (it - 1).toByte() }.toByteArray()
        }

        override fun deleteKey() {
            keyDeleted++
        }
    }

    private class FakeAlarms : ScheduleAlarms {
        var armedNotice: Instant? = null
        var armedFire: Instant? = null
        var refuse: String? = null
        var forgotten = false

        override fun arm(noticeAt: Instant, fireAt: Instant): String? {
            refuse?.let { return it }
            armedNotice = noticeAt
            armedFire = fireAt
            return null
        }

        override fun cancel() {
            armedNotice = null
            armedFire = null
        }

        override fun armed() = armedFire != null && !forgotten
    }

    private lateinit var dir: File
    private val cipher = FakeCipher()
    private val alarms = FakeAlarms()
    private val logs = ArrayList<String>()
    private lateinit var store: ScheduledCallStore
    private lateinit var calls: ScheduledCalls

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("scheduled").toFile()
        store = ScheduledCallStore(dir, cipher) { logs += it }
        calls = ScheduledCalls(store, alarms, { now }) { logs += it }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val file get() = File(dir, ScheduledCallStore.FILE)

    @Test
    fun aScheduledCallIsSavedAndItsAlarmsSet() {
        assertNull(calls.schedule(call))
        assertTrue(calls.exists)
        assertEquals(fireAt.minusSeconds(60), alarms.armedNotice)
        assertEquals(fireAt, alarms.armedFire)
        assertEquals(ScheduledCalls.Info(PracticePolicy.SHOP_NAME, fireAt), calls.info.value)
    }

    @Test
    fun theFileHoldsNothingReadable() {
        calls.schedule(call)
        val raw = file.readBytes().toString(Charsets.UTF_8)
        assertFalse(raw.contains(PracticePolicy.SHOP_NAME))
        assertFalse(raw.contains("0000"))
    }

    @Test
    fun theSameCallComesBackAfterTheAppWasEnded() {
        calls.schedule(call)
        val again = ScheduledCalls(ScheduledCallStore(dir, cipher), alarms, { now })
        assertNull(again.load())
        assertEquals(ScheduledCalls.Info(PracticePolicy.SHOP_NAME, fireAt), again.info.value)
        val back = (again.read() as ScheduledCallStore.Read.Found).call
        assertEquals(call.fireAt, back.fireAt)
        assertEquals(call.policy, back.policy)
        assertEquals(call.readings, back.readings)
    }

    @Test
    fun onlyOneCallAtATime() {
        assertNull(calls.schedule(call))
        assertEquals(ScheduledCalls.ALREADY, calls.schedule(call))
    }

    @Test
    fun whenTheAlarmIsRefusedNothingIsKept() {
        alarms.refuse = "許可がありません"
        assertEquals("許可がありません", calls.schedule(call))
        assertFalse(file.exists())
        assertNull(calls.info.value)
    }

    @Test
    fun cancellingDeletesTheFileAndTheAlarmsAndTheKey() {
        calls.schedule(call)
        calls.cancel()
        assertFalse(file.exists())
        assertFalse(alarms.armed())
        assertTrue(cipher.keyDeleted > 0)
        assertNull(calls.info.value)
        assertTrue(calls.read() is ScheduledCallStore.Read.None)
    }

    @Test
    fun consumingDeletesItBeforeTheCallSoItCannotBeUsedTwice() {
        calls.schedule(call)
        assertTrue(calls.consume())
        assertFalse(file.exists())
        assertNull(calls.info.value)
        assertFalse(calls.consume())
    }

    @Test
    fun aCallTakenBackBeforeItsAlarmIsNotConsumed() {
        calls.schedule(call)
        calls.cancel()
        assertFalse(calls.consume())
    }

    @Test
    fun aFileThatCannotBeUnderstoodIsDropped() {
        calls.schedule(call)
        val again = ScheduledCalls(ScheduledCallStore(dir, object : SecretCipher {
            override fun encrypt(plain: ByteArray) = plain

            override fun decrypt(data: ByteArray) = "not json".toByteArray()

            override fun deleteKey() {}
        }), alarms, { now })
        assertEquals(ScheduledCalls.NOT_READABLE, again.load())
        assertFalse(file.exists())
        assertFalse(alarms.armed())
    }

    @Test
    fun whenTheAlarmStartedTheProcessTheFileIsLeftForTheRunnerToJudge() {
        // The runner says that it could not read the call; a file already dropped looks like a call taken back
        calls.schedule(call)
        val again = ScheduledCalls(ScheduledCallStore(dir, object : SecretCipher {
            override fun encrypt(plain: ByteArray) = plain

            override fun decrypt(data: ByteArray) = "not json".toByteArray()

            override fun deleteKey() {}
        }), alarms, { now })
        assertNull(again.load(alarmCame = true))
        assertTrue(file.exists())
        assertTrue(again.read() is ScheduledCallStore.Read.Unreadable)
    }

    @Test
    fun aFileThatCannotBeReadJustNowIsKept() {
        calls.schedule(call)
        cipher.temporaryFailures = 1
        val again = ScheduledCalls(ScheduledCallStore(dir, cipher), alarms, { now })
        assertNull(again.load())
        assertTrue(file.exists())
        assertNull(again.info.value)
        // The next look finds it
        assertNull(again.tellIfLost())
        assertNotNull(again.info.value)
    }

    @Test
    fun aRestartBeforeTheTimeIsToldWhenTheAppOpens() {
        calls.schedule(call)
        alarms.forgotten = true
        val told = calls.tellIfLost()
        assertTrue(told!!.contains("かけられませんでした"))
        assertTrue(told.contains(PracticePolicy.SHOP_NAME))
        assertFalse(file.exists())
    }

    @Test
    fun anAlarmStillSetIsNotLost() {
        calls.schedule(call)
        assertNull(calls.tellIfLost())
        assertTrue(file.exists())
    }

    @Test
    fun aTimeLongPastWithTheFileStillThereIsLost() {
        calls.schedule(call)
        now = fireAt.plusSeconds(301)
        assertTrue(calls.tellIfLost()!!.contains("かけられませんでした"))
    }

    @Test
    fun afterARestartAnAlarmThatIsNotSetWillNotComeEvenAroundTheTime() {
        calls.schedule(call)
        alarms.forgotten = true
        now = fireAt.plusSeconds(100)
        assertTrue(calls.tellIfLost(afterRestart = true)!!.contains("かけられませんでした"))
        assertFalse(file.exists())
    }

    @Test
    fun aRestartThatLeftTheAlarmSetDoesNotLoseTheCall() {
        calls.schedule(call)
        now = fireAt.plusSeconds(100)
        assertNull(calls.tellIfLost(afterRestart = true))
        assertTrue(file.exists())
    }

    @Test
    fun aroundTheTimeNothingIsJudgedTheAlarmMayBeOnItsWay() {
        calls.schedule(call)
        alarms.forgotten = true
        now = fireAt.plusSeconds(100)
        assertNull(calls.tellIfLost())
        assertTrue(file.exists())
    }

    @Test
    fun theLogSaysWhatHappenedNotWhatTheCallIs() {
        calls.schedule(call)
        calls.cancel()
        for (line in logs) {
            assertFalse(line, line.contains(PracticePolicy.SHOP_NAME))
            assertFalse(line, line.contains("0000"))
        }
    }

    @Test
    fun thePlainTextFileOfTheApiKeyIsNotTakenForACall() {
        File(dir, ApiKeyStore.PLAIN_FILE).writeText("not a call")
        assertTrue(store.read() is ScheduledCallStore.Read.None)
        assertFalse(file.exists())
        assertTrue(File(dir, ApiKeyStore.PLAIN_FILE).exists())
    }

    @Test
    fun itsTextHidesTheCall() {
        assertEquals("ScheduledCall(<hidden>)", call.toString())
    }

    private fun saveRaw(text: String) = ApiKeyStore(dir, cipher, fileName = ScheduledCallStore.FILE, plainFileName = "none").save(text)

    @Test
    fun aCallSavedByAnotherVersionIsKept() {
        saveRaw("""{"version":99}""")
        assertEquals(ScheduledCallStore.Read.NotNow, store.read())
        assertTrue(store.exists)
    }

    @Test
    fun aFileThatIsNotACallIsDeleted() {
        saveRaw("not the file")
        assertEquals(ScheduledCallStore.Read.Unreadable, store.read())
        assertFalse(store.exists)
    }

    @Test
    fun anUnknownFormIsNotTakenForACall() {
        assertNull(ScheduledCallJson.decode("""{"version":2}"""))
        assertNull(ScheduledCallJson.decode("not json"))
    }
}
