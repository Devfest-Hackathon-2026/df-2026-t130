// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.request

import io.github.incallagent.core.policy.DelegationPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

class RelayClientTest {
    private class FakeTransport : RelayTransport {
        val inbox = ArrayList<List<RelayCommand>>()
        val replies = ArrayList<Pair<String, JsonObject>>()
        val states = ArrayList<Pair<String, JsonObject>>()
        val waits = ArrayList<Int>()
        var failing = false

        private fun down() {
            if (failing) throw IOException("down")
        }

        override fun next(instance: String, waitSeconds: Int): List<RelayCommand> {
            down()
            waits += waitSeconds
            return inbox.removeFirstOrNull().orEmpty()
        }

        override fun reply(cmd: String, reply: JsonObject) {
            down()
            replies += cmd to reply
        }

        override fun state(id: String, state: JsonObject) {
            down()
            states += id to state
        }
    }

    private class FakePhone : PhoneRequestHandler.Phone {
        override fun now(): Instant = Instant.parse("2026-11-08T03:00:00Z")
        override fun today(): LocalDate = LocalDate.of(2026, 11, 8)
        override fun callRunning() = false
        override fun dialProblem(policy: DelegationPolicy): String? {
            check(policy.target.phoneNumber != "03-0000-0999")
            return if (policy.target.phoneNumber == "119") "緊急の番号です" else null
        }
        override fun readiness(): String? = null
        override fun practice() = false
        override fun notifyUser() = true
        override fun clearNotice() {}
    }

    private class MemoryLedger : PhoneRequestStore.Ledger {
        var saved: Pair<String, PhoneRequestStore.Status>? = null

        override fun save(id: String, status: PhoneRequestStore.Status): Boolean {
            saved = id to status
            return true
        }

        override fun load() = saved
    }

    private val transport = FakeTransport()
    private val ledger = MemoryLedger()
    private val store = PhoneRequestStore(ledger, { 0L })
    private val handler = PhoneRequestHandler(store, FakePhone())
    private val client = RelayClient(transport, handler, "proc-1")

    private val number = "03-0000-0001"
    private val booking =
        """{"shop_name":"店","phone_number":"$number","windows":[{"date":"2026-11-14","from":"19:00"}],"party_size":2,"reservation_name":"テスト"}"""
    private val id = "12345678-1234-1234-1234-123456789abc"

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun command(args: String = booking, method: String = "create", cmd: String = "c1") = RelayCommand(cmd, method, id, json(args))

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun theRequestGoesThroughTheHandlerAndTheAnswerGoesBack() {
        transport.inbox += listOf(command())
        client.pollOnce(25)
        assertEquals(listOf(25), transport.waits)
        val (cmd, reply) = transport.replies.single()
        assertEquals("c1", cmd)
        assertEquals("waiting_for_approval", reply.text("status"))
        assertEquals(id, reply.text("id"))
        assertEquals(id, store.incoming.value!!.id)
    }

    @Test
    fun theHandlerDecidesWhatToDoWithARequestThatIsNotComplete() {
        transport.inbox += listOf(command("""{"shop_name":"店"}"""))
        client.pollOnce(0)
        assertEquals("needs_info", transport.replies.single().second.text("status"))
        assertNull(store.incoming.value)
    }

    @Test
    fun aNumberThePhoneWillNotCallNeverReachesTheOwner() {
        val emergency = """{"shop_name":"店","phone_number":"119","windows":[{"date":"2026-11-14","from":"19:00"}],"party_size":2,"reservation_name":"テスト"}"""
        transport.inbox += listOf(command(emergency))
        client.pollOnce(0)
        assertEquals("false", transport.replies.single().second.text("ok"))
        assertNull(store.incoming.value)
    }

    @Test
    fun aCommandThatFailsDoesNotStopTheOnesBehindIt() {
        val failing = booking.replace(number, "03-0000-0999")
        transport.inbox += listOf(command(failing), command(cmd = "c2"))
        client.pollOnce(0)
        val (cmd, reply) = transport.replies.single()
        assertEquals("c2", cmd)
        assertEquals("waiting_for_approval", reply.text("status"))
    }

    @Test
    fun theSameCommandDeliveredTwiceIsAnsweredTheSameAndNotAddedAgain() {
        transport.inbox += listOf(command())
        client.pollOnce(0)
        transport.inbox += listOf(command())
        client.pollOnce(0)
        assertEquals(listOf("waiting_for_approval", "waiting_for_approval"), transport.replies.map { it.second.text("status") })
        assertEquals(id, store.incoming.value!!.id)
    }

    @Test
    fun onlyCreateAndCancelAreCarriedOut() {
        transport.inbox += listOf(command("{}", method = "get"), command("{}", method = "dial_now", cmd = "c2"))
        client.pollOnce(0)
        assertTrue(transport.replies.isEmpty())
    }

    @Test
    fun whatCouldNotBeSentGoesWithTheNextRound() {
        transport.inbox += listOf(command())
        client.pollOnce(0)
        transport.replies.clear()
        store.decline(store.incoming.value!!.id)
        transport.failing = true
        client.pushState(id)
        assertTrue(transport.states.isEmpty())
        transport.failing = false
        client.pollOnce(0)
        assertEquals("declined", transport.states.single().second.text("status"))
    }

    @Test
    fun aReplyThatCouldNotBeSentIsNotLost() {
        transport.inbox += listOf(command())
        transport.failing = false
        // Taken, then the network goes before the answer is out
        val send = object : RelayTransport by transport {
            var first = true

            override fun reply(cmd: String, reply: JsonObject) {
                if (first) {
                    first = false
                    throw IOException("down")
                }
                transport.reply(cmd, reply)
            }
        }
        val careful = RelayClient(send, handler, "proc-1")
        assertThrows(IOException::class.java) { careful.pollOnce(0) }
        assertTrue(transport.replies.isEmpty())
        careful.pollOnce(0)
        assertEquals("c1", transport.replies.single().first)
    }

    @Test
    fun theFirstRoundOfANewProcessHasAnInstance() {
        val seen = ArrayList<String>()
        val recorder = object : RelayTransport by transport {
            override fun next(instance: String, waitSeconds: Int): List<RelayCommand> {
                seen += instance
                return emptyList()
            }
        }
        RelayClient(recorder, handler, "proc-A").pollOnce(0)
        assertEquals(listOf("proc-A"), seen)
    }

    @Test
    fun theLatestStateIsSentNotAnOldOne() {
        transport.inbox += listOf(command())
        client.pollOnce(0)
        transport.failing = true
        client.pushState(id)
        store.decline(id)
        transport.failing = false
        client.pushState(id)
        assertEquals("declined", transport.states.single().second.text("status"))
    }

    @Test
    fun whatTheStoreHasForgottenIsNotSent() {
        client.pushState("nobody")
        assertTrue(transport.states.isEmpty())
    }

    @Test
    fun theWireFormIsReadOrRefused() {
        val text = """{"commands":[{"cmd":"c1","method":"create","id":"$id","arg":{"shop_name":"店"}},{"cmd":1},{"method":"x"}]}"""
        val commands = RelayWire.commands(text)
        assertEquals(1, commands.size)
        assertEquals("店", commands[0].arg.text("shop_name"))
        assertThrows(IOException::class.java) { RelayWire.commands("not json") }
        assertThrows(IOException::class.java) { RelayWire.commands("""{"other":1}""") }
    }

    @Test
    fun theServerSaysWhoAsked() {
        val text = """{"commands":[{"cmd":"c1","method":"create","id":"$id","arg":{},"assistant":"grok"},{"cmd":"c2","method":"create","id":"$id","arg":{}},{"cmd":"c3","method":"create","id":"$id","arg":{},"assistant":1}]}"""
        assertEquals(listOf("grok", null, null), RelayWire.commands(text).map { it.assistant })
    }

    @Test
    fun whoAskedReachesTheRequestThatWaits() {
        transport.inbox += listOf(RelayCommand("c1", "create", id, json(booking), "muse"))
        client.pollOnce(0)
        assertEquals("muse", store.incoming.value!!.assistant)
    }

    @Test
    fun aRequestTheServerSaysNothingAboutHasNoName() {
        transport.inbox += listOf(command())
        client.pollOnce(0)
        assertNull(store.incoming.value!!.assistant)
    }

    @Test
    fun aCommandWhoseIdIsNotOneTheServerCouldHaveMadeIsLeftOut() {
        val long = "a".repeat(5000)
        val text = """{"commands":[{"cmd":"c1","method":"create","id":"$long","arg":{}},{"cmd":"c2","method":"create","id":"../x","arg":{}},{"cmd":"${"c".repeat(100)}","method":"create","id":"$id","arg":{}}]}"""
        assertTrue(RelayWire.commands(text).isEmpty())
    }

    @Test
    fun anAnswerTooLongIsRefused() {
        val fits = "x".repeat(100)
        assertEquals(fits, RelayWire.readAll(fits.byteInputStream(), 100))
        assertThrows(IOException::class.java) { RelayWire.readAll("x".repeat(101).byteInputStream(), 100) }
    }

    @Test
    fun aNewAddressNeedsTheTokenAgain() {
        // The old token must not go to another server
        assertEquals(RELAY_TOKEN_NEEDED, relaySaveProblem("https://old.example", true, "https://new.example", ""))
        assertNull(relaySaveProblem("https://old.example", true, "https://new.example", "new-token"))
        assertNull(relaySaveProblem("https://old.example", true, "https://old.example", ""))
        assertNull(relaySaveProblem("https://old.example", true, " https://old.example/ ", ""))
        assertEquals(RELAY_TOKEN_NEEDED, relaySaveProblem(null, false, "https://new.example", ""))
        assertNull(relaySaveProblem(null, false, "https://new.example", "t"))
    }

    @Test
    fun anAddressThatIsNotHttpsCannotBeSaved() {
        assertTrue(relaySaveProblem(null, false, "http://new.example", "t")!!.contains("https"))
    }

    @Test
    fun anAddressMustBeHttps() {
        assertEquals("https://relay.example", normalizedRelayUrl(" https://relay.example/ "))
        assertNull(normalizedRelayUrl("http://relay.example"))
        assertNull(normalizedRelayUrl("https://"))
    }

    // --- the five steps, with the real handler and store ---

    @Test
    fun theWholeWayRoundOnThePhone() {
        // 2. The details Muse has
        transport.inbox += listOf(command("""{"shop_name":"店","phone_number":"$number","call_at":"2026-11-09T12:00:00+09:00"}"""))
        client.pollOnce(25)

        // 4. AITEL asks for what is missing; nothing waits for the owner yet
        val asked = transport.replies.removeAt(0).second
        assertEquals("needs_info", asked.text("status"))
        assertEquals(listOf("windows", "party_size", "reservation_name"), asked.getValue("questions").jsonArray.map { it.jsonObject.text("field") })
        assertNull(store.incoming.value)

        // Muse sends it again with the answers in it
        transport.inbox += listOf(
            command(
                """{"shop_name":"店","phone_number":"$number","windows":[{"date":"2026-11-14","from":"19:00"}],"party_size":2,"reservation_name":"テスト","call_at":"2026-11-09T12:00:00+09:00"}""",
                cmd = "c2",
            ),
        )
        client.pollOnce(25)
        assertEquals("waiting_for_approval", transport.replies.removeAt(0).second.text("status"))
        val incoming = store.incoming.value!!
        assertEquals(Instant.parse("2026-11-09T03:00:00Z"), incoming.callAt)
        // The answers are on the form the owner reads
        assertEquals("テスト", store.open(id)!!.reservationName)

        // 3. The owner approves on the phone for that time, and the time comes
        assertTrue(store.scheduled(id, incoming.callAt!!, changedOnPhone = false))
        client.pushState(id)
        assertEquals("scheduled", transport.states.removeAt(0).second.text("status"))
        assertEquals(PhoneRequestStore.ScheduledStart.Request(id, false), store.scheduledCallStarts(id))
        client.pushState(id)
        assertEquals("calling", transport.states.removeAt(0).second.text("status"))

        // 5. The call ends and the result goes to the server
        store.finished(id, buildJsonObject { put("outcome", "booked"); put("summary", "予約できました") })
        client.pushState(id)
        val finished = transport.states.removeAt(0).second
        assertEquals("finished", finished.text("status"))
        assertEquals("booked", finished.getValue("result").jsonObject.text("outcome"))
        assertFalse(finished.toString().contains("0000-0001"))
    }

    @Test
    fun theOwnerIsTheOnlyOneWhoCanApprove() {
        transport.inbox += listOf(command(booking))
        client.pollOnce(0)
        // Nothing the server sends can turn a waiting request into a call: only the store's own calls can
        transport.inbox += listOf(command("""{"approved":true}""", cmd = "c2"))
        client.pollOnce(0)
        assertEquals("waiting_for_approval", store.get(id)!!.status.wire)
        assertEquals("waiting_for_approval", transport.replies.last().second.text("status"))
    }
}
