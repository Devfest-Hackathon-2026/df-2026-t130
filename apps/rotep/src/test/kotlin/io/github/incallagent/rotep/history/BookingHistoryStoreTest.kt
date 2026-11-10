// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.history

import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.app.ApiKeyStore
import io.github.incallagent.rotep.app.SecretCipher
import io.github.incallagent.rotep.call.CallSession.Line
import io.github.incallagent.rotep.call.CallSession.Who
import io.github.incallagent.rotep.history.BookingHistoryStore.Read
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
import java.time.temporal.ChronoUnit

class BookingHistoryStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeCipher : SecretCipher {
        var busy = false
        var damaged = false
        var cannotEncrypt = false
        var keysDeleted = 0

        override fun encrypt(plain: ByteArray): ByteArray {
            check(!cannotEncrypt)
            return plain.map { (it + 1).toByte() }.toByteArray()
        }

        override fun decrypt(data: ByteArray): ByteArray {
            if (busy) throw IllegalStateException("busy")
            if (damaged) throw IllegalArgumentException("damaged")
            return data.map { (it - 1).toByte() }.toByteArray()
        }

        override fun deleteKey() {
            keysDeleted++
        }

        override fun isTemporary(failure: Throwable) = failure is IllegalStateException
    }

    private class FakeHmac : NumberHmac {
        var broken = false
        var made = 0
        var forgotten = 0

        override fun of(normalizedNumber: String): String {
            check(!broken)
            made++
            return "hmac-" + normalizedNumber.reversed()
        }

        override fun forget() {
            forgotten++
        }
    }

    private var now: Instant = Instant.parse("2026-11-09T10:00:00Z")
    private val cipher = FakeCipher()
    private val hmac = FakeHmac()

    private fun newStore() = BookingHistoryStore(tmp.root, cipher, hmac, { now })
    private val store by lazy { newStore() }
    private fun file() = File(tmp.root, BookingHistoryStore.FILE)

    // Everything is fictitious
    private fun entry(id: String = "a", at: Instant = now, number: String = "03-0000-0001", shop: String = "テスト食堂") = Entry(
        id = id,
        at = at,
        shopName = shop,
        phoneNumber = number,
        purpose = Purpose.NEW_BOOKING,
        headline = "予約できました（第一希望）",
        summary = "11月14日19時に2名で予約しました",
    )

    private fun entries(s: BookingHistoryStore = store) = (s.read() as Read.Found).entries
    private fun ids(s: BookingHistoryStore = store) = entries(s).map { it.id }

    @Test
    fun nothingKeptIsAnEmptyListAndNoFile() {
        assertEquals(emptyList<Entry>(), entries())
        assertFalse(file().exists())
    }

    @Test
    fun everyFieldComesBackAfterTheAppStartsAgain() {
        val full = entry().copy(
            purpose = Purpose.CHANGE,
            bookedAt = LocalDateTime.of(2026, 11, 14, 19, 0),
            partySize = 2,
            reservationName = "テスト",
            transcript = listOf(Line(Who.SHOP, "はい、テスト食堂です"), Line(Who.AI, "AI が代理でお電話しています")),
        )
        store.add(full)
        assertEquals(full.copy(numberKey = "hmac-1000000030"), entries(newStore()).single())
    }

    @Test
    fun fieldsLeftEmptyStayEmpty() {
        store.add(entry())
        val back = entries(newStore()).single()
        assertNull(back.bookedAt)
        assertNull(back.partySize)
        assertNull(back.reservationName)
        assertTrue(back.transcript.isEmpty())
    }

    @Test
    fun theNewestComesFirst() {
        store.add(entry(id = "older", at = now.minusSeconds(60)))
        store.add(entry(id = "newer"))
        assertEquals(listOf("newer", "older"), ids())
    }

    @Test
    fun theFileCannotBeReadWithoutTheKey() {
        store.add(entry().copy(transcript = listOf(Line(Who.SHOP, "はい、お待ちしております"))))
        val written = file().readBytes().toString(Charsets.UTF_8)
        for (secret in listOf("テスト食堂", "03-0000-0001", "お待ちしております", "hmac-")) {
            assertFalse(secret, written.contains(secret))
        }
    }

    @Test
    fun onlyTheOneFileIsWritten() {
        store.add(entry())
        assertEquals(listOf(BookingHistoryStore.FILE), tmp.root.list()!!.toList())
    }

    @Test
    fun theOtherKeysPlainFileIsLeftAlone() {
        val other = File(tmp.root, ApiKeyStore.PLAIN_FILE).apply { writeText("the-gemini-key-in-an-old-install") }
        assertEquals(emptyList<Entry>(), entries())
        assertTrue(other.exists())
    }

    // --- 50 entries, 90 days ---

    @Test
    fun theFiftyFirstPushesTheOldestOut() {
        repeat(51) { i -> store.add(entry(id = "$i", at = now.plusSeconds(i.toLong()))) }
        val kept = ids()
        assertEquals(50, kept.size)
        assertEquals("50", kept.first())
        assertEquals("1", kept.last())
    }

    @Test
    fun ninetyDaysAreKeptAndNotASecondMore() {
        store.add(entry())
        now = now.plus(90, ChronoUnit.DAYS)
        assertEquals(1, entries().size)
        now = now.plusSeconds(1)
        assertTrue(entries().isEmpty())
    }

    @Test
    fun whatIsOverItsTimeIsGoneFromTheFileToo() {
        store.add(entry(id = "old", at = now.minus(80, ChronoUnit.DAYS)))
        store.add(entry(id = "new"))
        val then = now
        now = now.plus(20, ChronoUnit.DAYS)
        entries()
        // With the clock put back, only what is still in the file can show
        now = then
        assertEquals(listOf("new"), ids(newStore()))
    }

    @Test
    fun whenTheLastOneIsOverItsTimeTheFileAndTheKeysGoToo() {
        store.add(entry())
        now = now.plus(91, ChronoUnit.DAYS)
        entries()
        assertFalse(file().exists())
        assertEquals(1, cipher.keysDeleted)
        assertEquals(1, hmac.forgotten)
    }

    @Test
    fun anAddedEntryAlreadyOverItsTimeIsNotKept() {
        store.add(entry(at = now.minus(91, ChronoUnit.DAYS)))
        assertTrue(entries().isEmpty())
        assertFalse(file().exists())
    }

    // --- deleting ---

    @Test
    fun deletingOneLeavesTheOthers() {
        store.add(entry(id = "a", at = now.minusSeconds(1)))
        store.add(entry(id = "b"))
        assertTrue(store.delete("a"))
        assertEquals(listOf("b"), ids(newStore()))
    }

    @Test
    fun deletingWhatIsNotThereChangesNothing() {
        store.add(entry())
        assertTrue(store.delete("not-there"))
        assertEquals(listOf("a"), ids())
    }

    @Test
    fun deletingTheLastOneTakesTheFileAndTheKeysWithIt() {
        store.add(entry())
        assertTrue(store.delete("a"))
        assertFalse(file().exists())
        assertEquals(1, cipher.keysDeleted)
        assertEquals(1, hmac.forgotten)
    }

    @Test
    fun deletingAllTakesTheFileAndTheKeysWithIt() {
        store.add(entry(id = "a", at = now.minusSeconds(1)))
        store.add(entry(id = "b"))
        store.deleteAll()
        assertFalse(file().exists())
        assertEquals(1, cipher.keysDeleted)
        assertEquals(1, hmac.forgotten)
        assertTrue(entries().isEmpty())
    }

    @Test
    fun deletingAllWorksWhileTheKeyStoreIsBusy() {
        store.add(entry())
        cipher.busy = true
        store.deleteAll()
        cipher.busy = false
        assertFalse(file().exists())
        assertTrue(entries().isEmpty())
    }

    @Test
    fun deletingOneWhileTheKeyStoreIsBusySaysSoAndKeepsIt() {
        store.add(entry())
        cipher.busy = true
        assertFalse(store.delete("a"))
        cipher.busy = false
        assertEquals(listOf("a"), ids())
    }

    // --- finding a number ---

    @Test
    fun anyWayOfWritingTheNumberFindsTheCall() {
        store.add(entry(number = "03-0000-0001"))
        for (spelled in listOf("0300000001", "+81 3 0000 0001", "+81300000001", "(03) 0000 0001", "03-0000-0001")) {
            assertEquals(spelled, "a", store.findByNumber(spelled)?.id)
        }
    }

    @Test
    fun theNewestCallToTheNumberIsFound() {
        store.add(entry(id = "older", at = now.minusSeconds(60)))
        store.add(entry(id = "other", number = "03-0000-0002"))
        store.add(entry(id = "newer", at = now.minusSeconds(30)))
        assertEquals("newer", store.findByNumber("0300000001")?.id)
    }

    @Test
    fun anotherNumberOrNoNumberFindsNothing() {
        store.add(entry())
        assertNull(store.findByNumber("0300000002"))
        assertNull(store.findByNumber("非通知"))
        assertNull(store.findByNumber(""))
    }

    @Test
    fun withNothingKeptNoKeyIsMadeForTheNumber() {
        assertNull(store.findByNumber("0300000001"))
        assertEquals(0, hmac.made)
    }

    @Test
    fun whatExpiredIsNotFound() {
        store.add(entry())
        now = now.plus(91, ChronoUnit.DAYS)
        assertNull(store.findByNumber("0300000001"))
    }

    @Test
    fun withoutAKeyForTheNumberTheCallIsKeptButNotFoundByNumber() {
        hmac.broken = true
        store.add(entry())
        hmac.broken = false
        assertEquals(listOf("a"), ids())
        assertNull(entries().single().numberKey)
        assertNull(store.findByNumber("0300000001"))
    }

    @Test
    fun theLatestCallToAShopIsFoundByItsName() {
        store.add(entry(id = "older", at = now.minusSeconds(60)))
        store.add(entry(id = "newer", at = now.minusSeconds(30)))
        store.add(entry(id = "other", shop = "別の店"))
        assertEquals("newer", store.latestForShop("テスト食堂")?.id)
        assertNull(store.latestForShop("知らない店"))
    }

    // --- when the file cannot be read or written ---

    @Test
    fun aBusyKeyStoreLosesNothing() {
        store.add(entry(id = "older", at = now.minusSeconds(60)))
        cipher.busy = true
        assertEquals(Read.NotNow, store.add(entry(id = "newer")))
        assertEquals(Read.NotNow, store.read())
        cipher.busy = false
        assertEquals(listOf("newer", "older"), ids())
        assertEquals(listOf("newer", "older"), ids(newStore()))
    }

    @Test
    fun whatCouldNotBeWrittenIsShownAndWrittenLater() {
        cipher.cannotEncrypt = true
        store.add(entry())
        assertEquals(listOf("a"), ids())
        assertFalse(file().exists())
        cipher.cannotEncrypt = false
        assertEquals(listOf("a"), ids())
        assertEquals(listOf("a"), ids(newStore()))
    }

    @Test
    fun whatWasKeptStaysWhenTheNewOneCannotBeWritten() {
        store.add(entry(id = "kept", at = now.minusSeconds(60)))
        cipher.cannotEncrypt = true
        store.add(entry(id = "new"))
        assertEquals(listOf("kept"), ids(newStore()))
    }

    @Test
    fun deletedBeforeItCouldBeWrittenStaysDeleted() {
        cipher.cannotEncrypt = true
        store.add(entry())
        assertTrue(store.delete("a"))
        cipher.cannotEncrypt = false
        assertTrue(entries().isEmpty())
        assertFalse(file().exists())
    }

    @Test
    fun aFileThatCannotBeReadIsDeletedAndSaidOnce() {
        store.add(entry())
        cipher.damaged = true
        val first = store.read() as Read.Found
        assertTrue(first.lost)
        assertTrue(first.entries.isEmpty())
        assertFalse(file().exists())
        cipher.damaged = false
        assertFalse((store.read() as Read.Found).lost)
    }

    @Test
    fun theNextCallIsKeptAfterAFileThatCouldNotBeRead() {
        store.add(entry(id = "gone"))
        cipher.damaged = true
        store.read()
        cipher.damaged = false
        store.add(entry(id = "next"))
        assertEquals(listOf("next"), ids(newStore()))
    }

    private fun saveRaw(text: String) = ApiKeyStore(tmp.root, cipher, fileName = BookingHistoryStore.FILE, plainFileName = "none").save(text)

    @Test
    fun aFileFromAnotherVersionIsKept() {
        saveRaw("""{"version":99,"entries":[]}""")
        assertEquals(Read.NotNow, store.read())
        assertTrue(file().exists())
    }

    @Test
    fun aCallThatEndsMeanwhileDoesNotReplaceAFileFromAnotherVersion() {
        saveRaw("""{"version":99,"entries":[]}""")
        assertEquals(Read.NotNow, store.add(entry()))
        assertEquals(Read.NotNow, store.read())
        assertTrue(file().exists())
    }

    @Test
    fun aFileThatIsNotOursIsDeleted() {
        saveRaw("not the file")
        val read = store.read() as Read.Found
        assertTrue(read.lost)
        assertFalse(file().exists())
    }

    @Test
    fun oneEntryThatCannotBeUnderstoodDoesNotCostTheOthers() {
        saveRaw(HistoryJson.encode(listOf(entry("a"), entry("b"))).replaceFirst("\"purpose\":\"NEW_BOOKING\"", "\"purpose\":\"NO_SUCH\""))
        val read = store.read() as Read.Found
        assertEquals(listOf("b"), read.entries.map { it.id })
        assertTrue(read.partly)
        assertFalse(read.lost)
        assertTrue(file().exists())
    }

    @Test
    fun theNextCallKeepsWhatCouldBeRead() {
        saveRaw(HistoryJson.encode(listOf(entry("a"), entry("b"))).replaceFirst("\"purpose\":\"NEW_BOOKING\"", "\"purpose\":\"NO_SUCH\""))
        val read = store.add(entry("c", at = now.plusSeconds(60))) as Read.Found
        assertEquals(listOf("c", "b"), read.entries.map { it.id })
        val again = newStore().read() as Read.Found
        assertEquals(listOf("c", "b"), again.entries.map { it.id })
        assertFalse(again.partly)
    }

    private class FailsFirst(var failures: Int) : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(data: ByteArray): ByteArray {
            if (failures-- > 0) throw IllegalStateException("busy")
            return data
        }

        override fun deleteKey() {}
    }

    @Test
    fun aDecryptionIsTriedOnceMoreAfterAPause() {
        var pauses = 0
        val once = RetryingCipher(FailsFirst(1)) { pauses++ }
        assertEquals("x", once.decrypt("x".toByteArray()).toString(Charsets.UTF_8))
        assertEquals(1, pauses)
    }

    @Test
    fun aSecondFailureOfADecryptionCounts() {
        val twice = RetryingCipher(FailsFirst(2)) {}
        assertTrue(runCatching { twice.decrypt("x".toByteArray()) }.isFailure)
    }

    @Test
    fun aDecryptionThatWorksDoesNotWait() {
        var pauses = 0
        RetryingCipher(FailsFirst(0)) { pauses++ }.decrypt("x".toByteArray())
        assertEquals(0, pauses)
    }

    // --- never in a log ---

    @Test
    fun printingShowsNothingOfTheCall() {
        val e = entry().copy(reservationName = "テスト", transcript = listOf(Line(Who.SHOP, "はい")))
        val texts = listOf(e.toString(), Read.Found(listOf(e)).toString(), BookingHistory.State(listOf(e)).toString())
        for (text in texts) {
            for (secret in listOf("テスト", "03-0000", "はい", "予約")) assertFalse("$secret in $text", text.contains(secret))
        }
    }

    @Test
    fun nothingOfTheCallIsLogged() {
        val logged = ArrayList<String>()
        val s = BookingHistoryStore(tmp.root, cipher, hmac, { now }, { logged += it })
        cipher.cannotEncrypt = true
        hmac.broken = true
        s.add(entry().copy(reservationName = "テスト", transcript = listOf(Line(Who.SHOP, "はい"))))
        cipher.cannotEncrypt = false
        hmac.broken = false
        s.read()
        cipher.damaged = true
        s.read()
        assertTrue(logged.isNotEmpty())
        for (line in logged) {
            for (secret in listOf("テスト", "03-0000", "0300000001", "はい")) assertFalse("$secret in $line", line.contains(secret))
        }
    }
}
