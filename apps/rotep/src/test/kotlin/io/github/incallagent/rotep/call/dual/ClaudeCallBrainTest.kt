// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call.dual

import io.github.incallagent.agent.live.FunctionResponse
import io.github.incallagent.rotep.intake.IntakeMessages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ClaudeCallBrainTest {
    private class Answer(
        val code: Int = 200,
        val body: String = "",
        val retryAfter: String? = null,
        val failure: IOException? = null,
        val waitForCancel: Boolean = false,
    )

    private val requests = Collections.synchronizedList(ArrayList<Request>())
    private val answers = java.util.concurrent.ConcurrentLinkedQueue<Answer>()
    private val logs = Collections.synchronizedList(ArrayList<String>())
    private val secret = "test-key-0000"

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request()
        val answer = answers.poll() ?: throw IOException("no answer scripted")
        answer.failure?.let { throw it }
        if (answer.waitForCancel) {
            while (!chain.call().isCanceled()) Thread.sleep(5)
            throw IOException("Canceled")
        }
        val builder = Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(answer.code)
            .message("x")
            .body(answer.body.toResponseBody("text/event-stream".toMediaType()))
        answer.retryAfter?.let { builder.header("retry-after", it) }
        builder.build()
    }.build()

    private val brain = ClaudeCallBrain(secret, "決まりの文", { logs += it }, http, "https://claude.test/v1/messages")

    // --- the stream, as the API sends it ---

    private fun event(json: String) = "event: x\ndata: $json\n\n"

    private fun thinking(index: Int, signature: String) =
        event("""{"type":"content_block_start","index":$index,"content_block":{"type":"thinking","thinking":"","signature":""}}""") +
            event("""{"type":"content_block_delta","index":$index,"delta":{"type":"signature_delta","signature":"$signature"}}""") +
            event("""{"type":"content_block_stop","index":$index}""")

    private fun text(index: Int, vararg parts: String) =
        event("""{"type":"content_block_start","index":$index,"content_block":{"type":"text","text":""}}""") +
            parts.joinToString("") { event("""{"type":"content_block_delta","index":$index,"delta":{"type":"text_delta","text":"$it"}}""") } +
            event("""{"type":"content_block_stop","index":$index}""")

    private fun tool(index: Int, id: String, name: String, vararg json: String) =
        event("""{"type":"content_block_start","index":$index,"content_block":{"type":"tool_use","id":"$id","name":"$name","input":{}}}""") +
            json.joinToString("") { event("""{"type":"content_block_delta","index":$index,"delta":{"type":"input_json_delta","partial_json":${JsonPrimitive(it)}}}""") } +
            event("""{"type":"content_block_stop","index":$index}""")

    private fun stream(stop: String, vararg blocks: String) =
        event("""{"type":"message_start","message":{"usage":{"input_tokens":10,"cache_read_input_tokens":7}}}""") +
            event("""{"type":"ping"}""") +
            blocks.joinToString("") +
            event("""{"type":"message_delta","delta":{"stop_reason":"$stop"},"usage":{"output_tokens":12}}""") +
            event("""{"type":"message_stop"}""")

    private fun says(line: String) = stream("end_turn", thinking(0, "sig"), text(1, line))

    private fun answer(body: String) {
        answers += Answer(body = body)
    }

    // --- asking ---

    private fun think(input: BrainInput, on: ClaudeCallBrain = brain): BrainOutput {
        var output: BrainOutput? = null
        val latch = CountDownLatch(1)
        on.think(input) {
            output = it
            latch.countDown()
        }
        assertTrue("no answer came", latch.await(5, TimeUnit.SECONDS))
        return output!!
    }

    private fun shop(text: String = "はい") = BrainInput.ShopSaid(text)

    private fun awaitRequests(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (requests.size < count) {
            assertTrue("no request came", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }

    private fun sent(index: Int = requests.size - 1) =
        Json.parseToJsonElement(Buffer().also { requests[index].body!!.writeTo(it) }.readUtf8()).jsonObject

    private fun JsonObject.messages() = getValue("messages").jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.blocks() = getValue("content").jsonArray.map { it.jsonObject }

    private fun result(id: String, ok: Boolean = true) = FunctionResponse(id, "end_call", buildJsonObject { put("ok", ok) })

    // --- the request ---

    @Test
    fun theRequestIsTheOneThePlanAsksFor() {
        answer(says("はい"))
        think(shop())
        val body = sent()
        assertEquals(IntakeMessages.MODEL, body.text("model"))
        assertEquals(1024, body.getValue("max_tokens").jsonPrimitive.int)
        assertEquals("true", body.getValue("stream").jsonPrimitive.content)
        assertEquals("adaptive", body.getValue("thinking").jsonObject.text("type"))
        assertEquals("low", body.getValue("output_config").jsonObject.text("effort"))
        assertEquals("ephemeral", body.getValue("cache_control").jsonObject.text("type"))
        assertEquals(ClaudeCallTools.tools(), body.getValue("tools"))
        val system = body.getValue("system").jsonArray.single().jsonObject
        assertEquals("決まりの文", system.text("text"))
        assertEquals("ephemeral", system.getValue("cache_control").jsonObject.text("type"))
        val request = requests.single()
        assertEquals("https://claude.test/v1/messages", request.url.toString())
        assertEquals(secret, request.header("x-api-key"))
        assertEquals(IntakeMessages.API_VERSION, request.header("anthropic-version"))
    }

    @Test
    fun nothingForcesAToolOrTurnsThinkingOff() {
        answer(says("はい"))
        think(shop())
        assertFalse("tool_choice" in sent())
    }

    @Test
    fun eachInputGoesInWithTheMarksTheInstructionExplains() {
        val inputs = listOf(
            BrainInput.Opening,
            BrainInput.ShopSaid("十九時ですね"),
            BrainInput.ShopSaid("ちょっと待って", cutOff = "ご予約を"),
            BrainInput.Note("時間です"),
        )
        for (input in inputs) {
            answer(says("はい"))
            think(input, ClaudeCallBrain(secret, "決まりの文", { logs += it }, http, "https://claude.test/v1/messages"))
            assertEquals(CallBrainPrompt.userText(input), sent().messages().single().blocks().single().text("text"))
        }
    }

    // --- the answer ---

    @Test
    fun theLineToSayIsWhatCameInPieces() {
        answer(stream("end_turn", thinking(0, "sig"), text(1, "はい、", "お願いします")))
        assertEquals(BrainOutput(say = "はい、お願いします"), think(shop()))
    }

    @Test
    fun partySizesAreWrittenAsTheyAreSaid() {
        answer(says("2名でお願いします"))
        assertEquals("にめいでお願いします", think(shop()).say)
    }

    @Test
    fun anEllipsisMeansNothingToSay() {
        for (nothing in listOf("…", "……", " … ", "...")) {
            answer(says(nothing))
            assertNull(nothing, think(shop()).say)
        }
    }

    @Test
    fun toolsComeInTheOrderGiven() {
        answer(
            stream(
                "tool_use",
                thinking(0, "sig"),
                text(1, "承知しました"),
                tool(2, "t1", "first", """{"a":""", """1}"""),
                tool(3, "t2", "second"),
            ),
        )
        val output = think(shop())
        assertEquals("承知しました", output.say)
        assertEquals(listOf("t1", "t2"), output.calls.map { it.id })
        assertEquals(listOf("first", "second"), output.calls.map { it.name })
        assertEquals(1, output.calls[0].args.getValue("a").jsonPrimitive.int)
        assertEquals(JsonObject(emptyMap()), output.calls[1].args)
    }

    @Test
    fun aToolWhoseInputIsNotJsonRunsNothing() {
        answer(stream("tool_use", tool(0, "t1", "first", """{"a":""")))
        assertEquals(BrainOutput(failed = BrainFailure.OTHER), think(shop()))
    }

    @Test
    fun aRefusalIsAFailureAndRunsNothing() {
        answer(stream("refusal", tool(0, "t1", "first", "{}")))
        assertEquals(BrainOutput(failed = BrainFailure.REFUSED), think(shop()))
    }

    @Test
    fun aToolCutOffByTheLimitDoesNotRun() {
        answer(stream("max_tokens", text(0, "承知しました"), tool(1, "t1", "first", """{"a":""")))
        val output = think(shop())
        assertEquals("承知しました", output.say)
        assertTrue(output.calls.isEmpty())
    }

    @Test
    fun cutOffWithNothingToSayIsAFailure() {
        answer(stream("max_tokens", thinking(0, "sig")))
        assertEquals(BrainOutput(failed = BrainFailure.OTHER), think(shop()))
    }

    @Test
    fun aTurnWithNothingInItSaysNothing() {
        answer(stream("end_turn", thinking(0, "sig")))
        assertEquals(BrainOutput(), think(shop()))
    }

    // --- sentence by sentence ---

    private class Heard(val lines: List<String>, val output: BrainOutput)

    private fun thinkAndListen(input: BrainInput): Heard {
        val lines = Collections.synchronizedList(ArrayList<String>())
        var output: BrainOutput? = null
        val latch = CountDownLatch(1)
        brain.think(input, { lines += it }) {
            output = it
            latch.countDown()
        }
        assertTrue("no answer came", latch.await(5, TimeUnit.SECONDS))
        return Heard(lines.toList(), output!!)
    }

    @Test
    fun aSentenceGoesOutWhenItEnds() {
        answer(stream("end_turn", thinking(0, "sig"), text(1, "承知", "しました。ご", "予約は", "十九時です。")))
        val heard = thinkAndListen(shop())
        assertEquals(listOf("承知しました。", "ご予約は十九時です。"), heard.lines)
        assertNull(heard.output.say)
    }

    @Test
    fun whatHasNotEndedComesWithTheAnswer() {
        answer(says("承知しました。お待ちください"))
        val heard = thinkAndListen(shop())
        assertEquals(listOf("承知しました。"), heard.lines)
        assertEquals("お待ちください", heard.output.say)
    }

    @Test
    fun everyEndOfASentenceCounts() {
        answer(says("本当ですか？すごいです！はい、承知です。"))
        assertEquals(listOf("本当ですか？", "すごいです！", "はい、承知です。"), thinkAndListen(shop()).lines)
    }

    @Test
    fun marksTogetherStayTogether() {
        answer(says("本当ですか！？お願いします。"))
        assertEquals(listOf("本当ですか！？", "お願いします。"), thinkAndListen(shop()).lines)
    }

    @Test
    fun aCommaDoesNotEndASentence() {
        answer(says("はい、承知、しました"))
        val heard = thinkAndListen(shop())
        assertTrue(heard.lines.isEmpty())
        assertEquals("はい、承知、しました", heard.output.say)
    }

    @Test
    fun aShortPieceWaitsForTheNextSentence() {
        answer(says("はい。お願いします。"))
        val heard = thinkAndListen(shop())
        assertEquals(listOf("はい。お願いします。"), heard.lines)
        assertNull(heard.output.say)
    }

    @Test
    fun aShortPieceAtTheEndComesWithTheAnswer() {
        answer(says("承知しました。はい。"))
        val heard = thinkAndListen(shop())
        assertEquals(listOf("承知しました。"), heard.lines)
        assertEquals("はい。", heard.output.say)
    }

    @Test
    fun fourCharactersAreEnoughForALine() {
        answer(says("はいよ。"))
        assertEquals(listOf("はいよ。"), thinkAndListen(shop()).lines)
    }

    @Test
    fun anEllipsisIsNeverALine() {
        for (nothing in listOf("…", "……", "…。", "…………。")) {
            answer(says(nothing))
            val heard = thinkAndListen(shop())
            assertTrue(nothing, heard.lines.isEmpty())
            assertNull(nothing, heard.output.say)
        }
    }

    @Test
    fun anEllipsisBeforeASentenceIsLeftOut() {
        answer(says("…。お願いします。"))
        assertEquals(listOf("お願いします。"), thinkAndListen(shop()).lines)
    }

    @Test
    fun partySizesAreWrittenAsTheyAreSaidInALine() {
        answer(stream("end_turn", text(0, "2", "名です。お願いします")))
        val heard = thinkAndListen(shop())
        assertEquals(listOf("にめいです。"), heard.lines)
        assertEquals("お願いします", heard.output.say)
    }

    @Test
    fun theRestIsWrittenAsItIsSaidToo() {
        answer(says("承知しました。2名でお願いします"))
        assertEquals("にめいでお願いします", thinkAndListen(shop()).output.say)
    }

    @Test
    fun theHistoryHoldsTheWholeTextAsItCame() {
        answer(says("2名です。お願いします。"))
        thinkAndListen(shop())
        answer(says("次"))
        think(shop())
        assertEquals("2名です。お願いします。", sent().messages()[1].blocks()[1].text("text"))
    }

    @Test
    fun aSentenceComesWithTheToolsOfItsAnswer() {
        answer(stream("tool_use", text(0, "承知しました。"), tool(1, "t1", "first", "{}")))
        val heard = thinkAndListen(shop())
        assertEquals(listOf("承知しました。"), heard.lines)
        assertNull(heard.output.say)
        assertEquals(listOf("t1"), heard.output.calls.map { it.id })
    }

    @Test
    fun noLineGoesOutAfterATakeBack() {
        answer(says("承知しました。ご予約は十九時です。"))
        val lines = Collections.synchronizedList(ArrayList<String>())
        val cancelReady = CountDownLatch(1)
        var heard = false
        var cancel: () -> Unit = {}
        cancel = brain.think(shop("あ"), {
            cancelReady.await(5, TimeUnit.SECONDS)
            lines += it
            cancel()
        }) { heard = true }
        cancelReady.countDown()
        awaitRequests(1)
        answer(says("次"))
        think(shop("い"))
        assertEquals(listOf("承知しました。"), lines.toList())
        assertFalse(heard)
        assertEquals(1, sent().messages().size)
    }

    @Test
    fun whatWentOutIsNotAskedForAgain() {
        answer(event("""{"type":"message_start","message":{}}""") + text(0, "承知しました。") + event("""{"type":"error","error":{"type":"overloaded_error","message":"x"}}"""))
        val heard = thinkAndListen(shop())
        assertEquals(listOf("承知しました。"), heard.lines)
        assertEquals(BrainOutput(failed = BrainFailure.OVERLOADED), heard.output)
        assertEquals(1, requests.size)
    }

    @Test
    fun anotherTryStartsAfresh() {
        answer(event("""{"type":"message_start","message":{}}""") + text(0, "承知") + event("""{"type":"error","error":{"type":"overloaded_error","message":"x"}}"""))
        answer(says("お願いします。"))
        val heard = thinkAndListen(shop())
        assertEquals(listOf("お願いします。"), heard.lines)
        assertEquals(2, requests.size)
    }

    @Test
    fun anErrorFromTheLineListenerDoesNotStopTheAnswer() {
        answer(says("承知しました。"))
        var output: BrainOutput? = null
        val latch = CountDownLatch(1)
        brain.think(shop(), { error("from the listener") }) {
            output = it
            latch.countDown()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(BrainOutput(), output)
    }

    @Test
    fun theLogCountsTheLinesAndNothingOfWhatTheySay() {
        answer(says("承知しました。お願いします。"))
        thinkAndListen(shop())
        val line = logs.single { "ms=" in it }
        assertTrue(line, "lines=2" in line)
        assertTrue(line, Regex("first_line=\\d+ ").containsMatchIn(line))
        assertFalse(line, "承知" in line)
    }

    // --- the history ---

    @Test
    fun theHistoryGrowsOnlyAtTheEnd() {
        answer(says("一"))
        think(shop("あ"))
        answer(says("二"))
        think(shop("い"))
        answer(says("三"))
        think(shop("う"))
        val first = sent(1).messages()
        val second = sent(2).messages()
        assertEquals(3, first.size)
        assertEquals(5, second.size)
        assertEquals(first, second.take(3))
        assertEquals(listOf("user", "assistant", "user", "assistant", "user"), second.map { it.text("role") })
    }

    @Test
    fun thinkingGoesBackUnchanged() {
        answer(
            stream(
                "end_turn",
                event("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""") +
                    event("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"考え"}}""") +
                    event("""{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-1"}}""") +
                    event("""{"type":"content_block_stop","index":0}"""),
                event("""{"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"secret-data"}}""") +
                    event("""{"type":"content_block_stop","index":1}"""),
                text(2, "はい"),
            ),
        )
        think(shop())
        answer(says("次"))
        think(shop())
        val blocks = sent().messages()[1].blocks()
        assertEquals(listOf("thinking", "redacted_thinking", "text"), blocks.map { it.text("type") })
        assertEquals("考え", blocks[0].text("thinking"))
        assertEquals("sig-1", blocks[0].text("signature"))
        assertEquals("secret-data", blocks[1].text("data"))
        assertEquals("はい", blocks[2].text("text"))
    }

    @Test
    fun anEllipsisStaysInTheHistoryAsItCame() {
        answer(says("…"))
        think(shop())
        answer(says("次"))
        think(shop())
        assertEquals("…", sent().messages()[1].blocks()[1].text("text"))
    }

    @Test
    fun aTurnWithNothingToKeepJoinsTheNextMessage() {
        answer(stream("end_turn", thinking(0, "sig")))
        think(shop("あ"))
        answer(says("次"))
        think(shop("い"))
        val message = sent().messages().single()
        assertEquals("user", message.text("role"))
        val texts = message.blocks().map { it.text("text") }
        assertEquals(2, texts.size)
        assertTrue("あ" in texts[0] && "い" in texts[1])
    }

    @Test
    fun theRolesKeepTakingTurnsAfterAnEmptyAnswerToATool() {
        answer(stream("tool_use", tool(0, "t1", "first", "{}")))
        think(shop("あ"))
        answer(stream("end_turn", thinking(0, "sig")))
        think(BrainInput.ToolResults(listOf(result("t1"))))
        answer(says("次"))
        think(shop("い"))
        val messages = sent().messages()
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.text("role") })
        assertEquals(listOf("tool_result", "text"), messages.last().blocks().map { it.text("type") })
    }

    @Test
    fun anEmptyTextBlockDoesNotGoBack() {
        answer(stream("tool_use", thinking(0, "sig"), text(1), tool(2, "t1", "first", "{}")))
        think(shop())
        answer(says("次"))
        think(BrainInput.ToolResults(listOf(result("t1"))))
        assertEquals(listOf("thinking", "tool_use"), sent().messages()[1].blocks().map { it.text("type") })
    }

    @Test
    fun toolResultsCloseTheToolsAndComeFirst() {
        answer(stream("tool_use", tool(0, "t1", "first", "{}"), tool(1, "t2", "second", "{}")))
        think(shop())
        answer(says("次"))
        think(BrainInput.ToolResults(listOf(result("t1"), result("t2")), shopSaid = "もしもし"))
        val blocks = sent().messages().last().blocks()
        assertEquals(listOf("tool_result", "tool_result", "text"), blocks.map { it.text("type") })
        assertEquals(listOf("t1", "t2"), blocks.take(2).map { it.text("tool_use_id") })
        assertTrue("もしもし" in blocks[2].text("text"))
    }

    @Test
    fun toolsNobodyAnsweredAreClosedAsNotRun() {
        answer(stream("tool_use", tool(0, "t1", "first", "{}")))
        think(shop())
        answer(says("次"))
        think(shop("割り込み"))
        val blocks = sent().messages().last().blocks()
        assertEquals(listOf("tool_result", "text"), blocks.map { it.text("type") })
        assertEquals("t1", blocks[0].text("tool_use_id"))
        assertEquals("true", blocks[0].getValue("is_error").jsonPrimitive.content)
    }

    @Test
    fun onlySomeResultsStillCloseEveryTool() {
        answer(stream("tool_use", tool(0, "t1", "first", "{}"), tool(1, "t2", "second", "{}")))
        think(shop())
        answer(says("次"))
        think(BrainInput.ToolResults(listOf(result("t2"))))
        val blocks = sent().messages().last().blocks()
        assertEquals(listOf("t1", "t2"), blocks.map { it.text("tool_use_id") })
        assertEquals("true", blocks[0].getValue("is_error").jsonPrimitive.content)
        assertFalse("is_error" in blocks[1])
    }

    @Test
    fun aResultForNoOpenToolIsLeftOut() {
        answer(stream("tool_use", tool(0, "t1", "first", "{}")))
        think(shop())
        answer(says("次"))
        think(BrainInput.ToolResults(listOf(result("t1"), result("elsewhere"))))
        assertEquals(listOf("t1"), sent().messages().last().blocks().map { it.text("tool_use_id") })
    }

    @Test
    fun toolsAreClosedOnlyOnce() {
        answer(stream("tool_use", tool(0, "t1", "first", "{}")))
        think(shop())
        answer(says("次"))
        think(BrainInput.ToolResults(listOf(result("t1"))))
        answer(says("その次"))
        think(shop())
        assertEquals(listOf("text"), sent().messages().last().blocks().map { it.text("type") })
    }

    @Test
    fun aToolCutOffByTheLimitLeavesWhatWasSaidAndNothingToClose() {
        answer(stream("max_tokens", text(0, "はい"), tool(1, "t1", "first", """{"a":""")))
        think(shop())
        answer(says("次"))
        think(shop())
        val messages = sent().messages()
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.text("role") })
        assertEquals(listOf("text"), messages[1].blocks().map { it.text("type") })
        assertEquals(listOf("text"), messages.last().blocks().map { it.text("type") })
    }

    @Test
    fun aToolOutsideAToolUseStopDoesNotRun() {
        answer(stream("end_turn", text(0, "はい"), tool(1, "t1", "first", "{}")))
        val output = think(shop())
        assertEquals("はい", output.say)
        assertTrue(output.calls.isEmpty())
        answer(says("次"))
        think(shop())
        assertEquals(listOf("text"), sent().messages()[1].blocks().map { it.text("type") })
        assertEquals(listOf("text"), sent().messages().last().blocks().map { it.text("type") })
    }

    @Test
    fun whenThereIsNothingToSendNothingIsAsked() {
        assertEquals(BrainOutput(), think(BrainInput.ToolResults(listOf(result("elsewhere")))))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun aRefusedTurnLeavesNothingInTheHistory() {
        answer(stream("refusal"))
        think(shop("あ"))
        answer(says("次"))
        think(shop("い"))
        assertEquals(1, sent().messages().size)
    }

    @Test
    fun aFailedTurnLeavesNothingInTheHistory() {
        answers += Answer(code = 404)
        think(shop("あ"))
        answer(says("次"))
        think(shop("い"))
        assertEquals(1, sent().messages().size)
    }

    // --- taking a turn back ---

    @Test
    fun aCancelledTurnSaysNothingAndLeavesNothing() {
        answers += Answer(waitForCancel = true)
        var heard = false
        val cancel = brain.think(shop("あ")) { heard = true }
        awaitRequests(1)
        cancel()
        answer(says("次"))
        think(shop("い"))
        assertFalse(heard)
        assertEquals(1, sent().messages().size)
        assertTrue("い" in sent().messages().single().blocks().single().text("text"))
    }

    private fun takeBack(input: BrainInput) {
        val before = requests.size
        answers += Answer(waitForCancel = true)
        val cancel = brain.think(input) {}
        awaitRequests(before + 1)
        cancel()
    }

    @Test
    fun anOpeningTakenBackGoesWithTheShopsFirstWords() {
        takeBack(BrainInput.Opening)
        answer(says("はい"))
        think(shop("もしもし"))
        val texts = sent().messages().single().blocks().map { it.text("text") }
        assertEquals(listOf(CallBrainPrompt.userText(BrainInput.Opening), CallBrainPrompt.userText(shop("もしもし"))), texts)
    }

    @Test
    fun anOpeningAskedAgainIsSaidOnce() {
        takeBack(BrainInput.Opening)
        answer(says("はい"))
        think(BrainInput.Opening)
        assertEquals(listOf(CallBrainPrompt.userText(BrainInput.Opening)), sent().messages().single().blocks().map { it.text("text") })
    }

    @Test
    fun anOpeningThatGotThroughIsNotSaidAgain() {
        answer(says("はい"))
        think(BrainInput.Opening)
        answer(says("次"))
        think(shop("あ"))
        assertEquals(listOf(CallBrainPrompt.userText(shop("あ"))), sent().messages().last().blocks().map { it.text("text") })
    }

    @Test
    fun resultsAskedAgainAfterATakeBackCloseTheTools() {
        answer(stream("tool_use", tool(0, "t1", "first", "{}")))
        think(shop())
        takeBack(BrainInput.ToolResults(listOf(result("t1"))))
        answer(says("次"))
        think(BrainInput.ToolResults(listOf(result("t1")), shopSaid = "割り込み"))
        val blocks = sent().messages().last().blocks()
        assertEquals(listOf("tool_result", "text"), blocks.map { it.text("type") })
        assertFalse("is_error" in blocks[0])
    }

    /** The answer is in, and on its way to a listener that drops it because the shop said more. */
    private fun takeBackAfterItsAnswer(input: BrainInput) {
        val answered = CountDownLatch(1)
        val cancel = brain.think(input) { answered.countDown() }
        assertTrue(answered.await(5, TimeUnit.SECONDS))
        cancel()
    }

    @Test
    fun anAnswerTakenBackLeavesNothing() {
        answer(says("一"))
        takeBackAfterItsAnswer(shop("あ"))
        answer(says("二"))
        think(shop("あ、い"))
        assertEquals(listOf(CallBrainPrompt.userText(shop("あ、い"))), sent().messages().single().blocks().map { it.text("text") })
    }

    @Test
    fun whatCameBeforeAnAnswerTakenBackStays() {
        answer(says("一"))
        think(shop("あ"))
        answer(says("二"))
        takeBackAfterItsAnswer(shop("い"))
        answer(says("三"))
        think(shop("う"))
        val messages = sent().messages()
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.text("role") })
        assertEquals(listOf(CallBrainPrompt.userText(shop("う"))), messages.last().blocks().map { it.text("text") })
    }

    @Test
    fun aTurnTakenBackWhileRunningLeavesTheAnswerBeforeIt() {
        answer(says("一"))
        think(shop("あ"))
        takeBack(shop("い"))
        answer(says("二"))
        think(shop("う"))
        assertEquals(listOf("user", "assistant", "user"), sent().messages().map { it.text("role") })
    }

    @Test
    fun theToolsOfAnAnswerTakenBackAreNotLeftOpen() {
        answer(stream("tool_use", tool(0, "t1", "first", "{}")))
        takeBackAfterItsAnswer(shop("あ"))
        answer(says("次"))
        think(shop("あ、い"))
        assertEquals(listOf("text"), sent().messages().single().blocks().map { it.text("type") })
    }

    @Test
    fun theToolsAResultClosedAreOpenAgainIfItsAnswerIsTakenBack() {
        answer(stream("tool_use", tool(0, "t1", "first", "{}")))
        think(shop())
        answer(says("二"))
        takeBackAfterItsAnswer(BrainInput.ToolResults(listOf(result("t1"))))
        answer(says("三"))
        think(BrainInput.ToolResults(listOf(result("t1")), shopSaid = "い"))
        val messages = sent().messages()
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.text("role") })
        assertEquals(listOf("tool_result", "text"), messages.last().blocks().map { it.text("type") })
        assertFalse("is_error" in messages.last().blocks()[0])
    }

    @Test
    fun anOpeningWhoseAnswerWasTakenBackIsStillOwed() {
        answer(says("一"))
        takeBackAfterItsAnswer(BrainInput.Opening)
        answer(says("二"))
        think(shop("もしもし"))
        val texts = sent().messages().single().blocks().map { it.text("text") }
        assertEquals(listOf(CallBrainPrompt.userText(BrainInput.Opening), CallBrainPrompt.userText(shop("もしもし"))), texts)
    }

    @Test
    fun aJoinedMessageIsPutBackAsItWas() {
        answer(stream("end_turn", thinking(0, "sig")))
        think(shop("あ"))
        answer(says("二"))
        takeBackAfterItsAnswer(shop("い"))
        answer(says("三"))
        think(shop("う"))
        val texts = sent().messages().single().blocks().map { it.text("text") }
        assertEquals(listOf(CallBrainPrompt.userText(shop("あ")), CallBrainPrompt.userText(shop("う"))), texts)
    }

    @Test
    fun aTurnCancelledDuringThePauseBeforeARetryDoesNotGoAgain() {
        answers += Answer(code = 529)
        var heard = false
        val cancel = brain.think(shop()) { heard = true }
        awaitRequests(1)
        cancel()
        Thread.sleep(600)
        assertEquals(1, requests.size)
        assertFalse(heard)
    }

    @Test
    fun theNextTurnWaitsForTheOneBeforeIt() {
        answer(says("一"))
        think(shop("あ"))
        answer(says("二"))
        val first = CountDownLatch(1)
        brain.think(shop("い")) { first.countDown() }
        assertTrue(first.await(5, TimeUnit.SECONDS))
        answer(says("三"))
        think(shop("う"))
        assertEquals(5, sent().messages().size)
    }

    // --- failing, and one more try ---

    @Test
    fun aBusyServerGetsOneMoreTry() {
        for (code in listOf(429, 500, 503, 529)) {
            requests.clear()
            answers += Answer(code = code)
            answer(says("はい"))
            assertEquals("$code", "はい", think(shop()).say)
            assertEquals("$code", 2, requests.size)
        }
    }

    @Test
    fun anErrorInTheStreamGetsOneMoreTry() {
        for (type in listOf("overloaded_error", "api_error", "rate_limit_error", "timeout_error")) {
            requests.clear()
            answer(event("""{"type":"message_start","message":{}}""") + event("""{"type":"error","error":{"type":"$type","message":"x"}}"""))
            answer(says("はい"))
            assertEquals(type, "はい", think(shop()).say)
            assertEquals(type, 2, requests.size)
        }
    }

    @Test
    fun oneMoreTryIsAllThereIs() {
        answers += Answer(code = 529)
        answers += Answer(code = 529)
        assertEquals(BrainOutput(failed = BrainFailure.OVERLOADED), think(shop()))
        assertEquals(2, requests.size)
    }

    @Test
    fun aRequestRefusedAsWrittenGoesOnceMoreWithoutTheBreakpointAtTheTop() {
        answers += Answer(code = 400)
        answer(says("はい"))
        assertEquals("はい", think(shop()).say)
        assertEquals(2, requests.size)
        assertTrue("cache_control" in sent(0))
        assertFalse("cache_control" in sent(1))
        assertEquals(sent(0).getValue("messages"), sent(1).getValue("messages"))
        val system = sent(1).getValue("system").jsonArray.single().jsonObject
        assertEquals("ephemeral", system.getValue("cache_control").jsonObject.text("type"))
    }

    @Test
    fun theBreakpointAtTheTopStaysOffForTheRestOfTheCall() {
        answers += Answer(code = 400)
        answer(says("はい"))
        think(shop())
        answer(says("次"))
        think(shop())
        assertEquals(3, requests.size)
        assertFalse("cache_control" in sent())
    }

    @Test
    fun aSecondRefusalIsFinal() {
        answers += Answer(code = 400)
        answers += Answer(code = 400)
        assertEquals(BrainOutput(failed = BrainFailure.OTHER), think(shop()))
        assertEquals(2, requests.size)
    }

    @Test
    fun onlyARefusalAsWrittenDropsTheBreakpoint() {
        answers += Answer(code = 529)
        answer(says("はい"))
        think(shop())
        assertTrue("cache_control" in sent())
    }

    @Test
    fun aBusyServerAfterTheRefusalStillGetsItsOneMoreTry() {
        answers += Answer(code = 400)
        answers += Answer(code = 529)
        answer(says("はい"))
        assertEquals("はい", think(shop()).say)
        assertEquals(3, requests.size)
        assertFalse("cache_control" in sent())
    }

    @Test
    fun aLongRetryAfterIsNotWaitedFor() {
        answers += Answer(code = 429, retryAfter = "30")
        assertEquals(BrainOutput(failed = BrainFailure.RATE_LIMITED), think(shop()))
        assertEquals(1, requests.size)
    }

    @Test
    fun aShortRetryAfterIsWaitedFor() {
        answers += Answer(code = 429, retryAfter = "1")
        answer(says("はい"))
        val started = System.nanoTime()
        assertEquals("はい", think(shop()).say)
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 900)
    }

    @Test
    fun whatAnotherTryCannotHelpIsNotTriedAgain() {
        val cases = mapOf(401 to BrainFailure.KEY_REFUSED, 403 to BrainFailure.KEY_REFUSED, 404 to BrainFailure.OTHER)
        for ((code, failure) in cases) {
            requests.clear()
            answers += Answer(code = code)
            assertEquals("$code", BrainOutput(failed = failure), think(shop()))
            assertEquals("$code", 1, requests.size)
        }
    }

    @Test
    fun anErrorInTheStreamIsNamed() {
        val cases = mapOf(
            "authentication_error" to BrainFailure.KEY_REFUSED,
            "invalid_request_error" to BrainFailure.OTHER,
        )
        for ((type, failure) in cases) {
            requests.clear()
            answer(event("""{"type":"error","error":{"type":"$type","message":"x"}}"""))
            assertEquals(type, BrainOutput(failed = failure), think(shop()))
            assertEquals(type, 1, requests.size)
        }
    }

    @Test
    fun aReadThatTimesOutIsATimeout() {
        answers += Answer(failure = SocketTimeoutException("timeout"))
        assertEquals(BrainOutput(failed = BrainFailure.TIMEOUT), think(shop()))
        assertEquals(1, requests.size)
    }

    @Test
    fun theWholeCallTakingTooLongIsATimeout() {
        answers += Answer(failure = InterruptedIOException("timeout"))
        assertEquals(BrainOutput(failed = BrainFailure.TIMEOUT), think(shop()))
    }

    @Test
    fun aStreamThatNeverSaysHowItEndedIsNotTrusted() {
        answer(event("""{"type":"message_start","message":{}}""") + text(0, "途中") + event("""{"type":"message_stop"}"""))
        assertEquals(BrainOutput(failed = BrainFailure.OTHER), think(shop()))
    }

    @Test
    fun aLostConnectionIsANetworkFailure() {
        answers += Answer(failure = IOException("gone"))
        assertEquals(BrainOutput(failed = BrainFailure.NETWORK), think(shop()))
    }

    @Test
    fun aStreamThatStopsHalfWayIsANetworkFailure() {
        answer(event("""{"type":"message_start","message":{}}""") + text(0, "途中"))
        assertEquals(BrainOutput(failed = BrainFailure.NETWORK), think(shop()))
    }

    // --- getting the instruction cached ---

    @Test
    fun warmingUpWritesTheCacheAndReadsNothing() {
        answers += Answer(body = """{"content":[],"stop_reason":"max_tokens"}""")
        brain.warmUp()
        awaitRequests(1)
        val body = sent()
        assertEquals(0, body.getValue("max_tokens").jsonPrimitive.int)
        assertFalse("stream" in body)
        assertFalse("the breakpoint stays on what real turns share", "cache_control" in body)
        assertEquals("adaptive", body.getValue("thinking").jsonObject.text("type"))
        assertEquals("low", body.getValue("output_config").jsonObject.text("effort"))
        assertEquals(ClaudeCallTools.tools(), body.getValue("tools"))
        val system = body.getValue("system").jsonArray.single().jsonObject
        assertEquals("ephemeral", system.getValue("cache_control").jsonObject.text("type"))
    }

    @Test
    fun warmingUpLeavesNoTraceInTheConversation() {
        answers += Answer(body = "{}")
        brain.warmUp()
        awaitRequests(1)
        answer(says("はい"))
        think(shop())
        assertEquals(1, sent().messages().size)
    }

    @Test
    fun warmingUpMayFail() {
        answers += Answer(failure = IOException("gone"))
        brain.warmUp()
        awaitRequests(1)
        answer(says("はい"))
        assertEquals("はい", think(shop()).say)
    }

    // --- nothing of the call in the log ---

    @Test
    fun theLogTellsHowLongItTookAndWhatTheCacheGave() {
        answer(says("はい"))
        think(shop())
        val line = logs.single { "ms=" in it }
        assertTrue(line, Regex("first=\\d+ ").containsMatchIn(line))
        assertTrue(line, Regex(" ms=\\d+$").containsMatchIn(line))
        assertTrue(line, "cache_read=7" in line)
    }

    @Test
    fun theLogNamesTheStatusOfARefusedRequest() {
        answers += Answer(code = 400, body = "本文")
        answers += Answer(code = 400, body = "本文")
        think(shop())
        assertEquals(2, logs.count { "http=400" in it })
        assertTrue(logs.any { "without the top-level cache_control" in it })
    }

    @Test
    fun theLogNamesTheKindOfAnErrorInTheStream() {
        answer(event("""{"type":"error","error":{"type":"invalid_request_error","message":"x"}}"""))
        think(shop())
        assertTrue(logs.any { "error=invalid_request_error" in it })
    }

    @Test
    fun theFirstOutputCanBeATool() {
        answer(stream("tool_use", thinking(0, "sig"), tool(1, "t1", "first", "{}")))
        think(shop())
        assertTrue(Regex("first=\\d+ ").containsMatchIn(logs.single { "ms=" in it }))
    }

    @Test
    fun theLogHoldsNeitherTheKeyNorAnythingSaid() {
        val words = "お店の言葉の秘密"
        answer(stream("tool_use", thinking(0, "sig-secret"), text(1, words), tool(2, "t1", "first", """{"name":"$words"}""")))
        think(BrainInput.ShopSaid(words))
        answers += Answer(code = 429, body = """{"error":{"message":"$words"}}""", retryAfter = "30")
        think(BrainInput.ShopSaid(words))
        answer(event("""{"type":"error","error":{"type":"api_error","message":"$words"}}"""))
        answer(event("""{"type":"error","error":{"type":"api_error","message":"$words"}}"""))
        think(BrainInput.Note(words))
        answers += Answer(failure = IOException(words))
        think(BrainInput.Note(words))
        answers += Answer(code = 400, body = """{"error":{"message":"$words"}}""")
        answers += Answer(code = 400, body = """{"error":{"message":"$words"}}""")
        think(BrainInput.Note(words))
        assertTrue(logs.isNotEmpty())
        for (line in logs) {
            assertFalse(line, secret in line)
            assertFalse(line, words in line)
            assertFalse(line, "sig-secret" in line)
        }
    }

    @Test
    fun anErrorFromDoneDoesNotEscape() {
        answer(says("はい"))
        val latch = CountDownLatch(1)
        brain.think(shop()) {
            latch.countDown()
            error("from the listener")
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        answer(says("次"))
        assertEquals("次", think(shop()).say)
    }

    @Test
    fun theAnswerStillComesIfSomethingBreaks() {
        val breaking = ClaudeCallBrain(secret, "x", { if ("ms=" in it) error("from the log") }, http, "https://claude.test/v1/messages")
        answer(says("はい"))
        assertEquals(BrainOutput(failed = BrainFailure.OTHER), think(shop(), on = breaking))
    }
}
