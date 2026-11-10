// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call.dual

import io.github.incallagent.rotep.call.SpokenNumbers
import io.github.incallagent.rotep.intake.IntakeMessages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * The thinking side of a call, on the Claude Messages API (ADR-053). It keeps the conversation
 * itself and answers one turn at a time.
 *
 * The history only ever grows at the end, and what the API sent comes back to it as it came, thinking
 * included. A turn that was taken back, refused or lost leaves no exchange in it. Every tool_use is
 * closed by a tool_result in the next message, and a request always ends with the user's message.
 *
 * The words go out sentence by sentence as they arrive, so that the voice can start before the answer
 * is over; that is before anyone knows how the answer ends. Everything else waits for the end: the
 * tools, and what the history keeps. A sentence of a turn that is taken back is not in the history
 * either: whoever took it back knows what was said, and tells the next turn.
 *
 * What was said is sent to the API and nowhere else: the log gets states, counts and times only,
 * and the text of an error is not even read.
 */
class ClaudeCallBrain(
    private val apiKey: String,
    private val system: String,
    private val log: (String) -> Unit,
    private val client: OkHttpClient = DEFAULT_CLIENT,
    private val url: String = IntakeMessages.URL,
) : CallBrain {
    private val tools = ClaudeCallTools.tools()

    /** One turn at a time. The fields below may be touched only while holding it. */
    private val turnInProgress = ReentrantLock(true)
    private var history = emptyList<JsonObject>()

    /** The tool_use ids of the last answer, which the next message has to close. */
    private var openTools = emptyList<String>()

    /**
     * The shop's first words often take the opening back, and the connector goes on with those words.
     * What the opening says holds all the same: it goes with the next message that gets through.
     */
    private var openingOwed = false
    private var turnsAsked = 0

    /** The last turn whose answer went in, in case that answer gets taken back after all. */
    private var lastAnswered: Turn? = null

    /** Off for the rest of the call once a request with it has been refused. */
    private var cacheAtTheTop = true

    override fun warmUp() {
        thread(name = "dual-brain-warm", isDaemon = true) {
            try {
                client.newCall(request(warmUpBody())).execute().use { log("brain: warm up http=${it.code}") }
            } catch (e: Exception) {
                log("brain: warm up failed: ${e.javaClass.simpleName}")
            }
        }
    }

    override fun think(input: BrainInput, onLine: (String) -> Unit, done: (BrainOutput) -> Unit): () -> Unit {
        val turn = Turn(input, onLine, done)
        thread(name = "dual-brain", isDaemon = true) { turn.run() }
        return turn::cancel
    }

    private inner class Turn(val input: BrainInput, val onLine: (String) -> Unit, val done: (BrainOutput) -> Unit) {
        private val state = AtomicInteger(RUNNING)

        @Volatile
        private var call: Call? = null

        @Volatile
        private var worker: Thread? = null

        /** What the conversation was before this turn's answer went in. Only while holding the lock. */
        private var before: Conversation? = null

        private var linesOut = 0
        private var firstLineAt: Long? = null

        /** Whoever asked no longer wants this turn. */
        val cancelled: Boolean get() = state.get().let { it == CANCELLED || it == ANSWER_TAKEN_BACK }

        /** What the conversation goes back to, if this turn's answer was taken back after it went in. */
        val undo: Conversation? get() = before.takeIf { state.get() == ANSWER_TAKEN_BACK }

        /**
         * A turn can also be taken back when its answer is already in the history and on its way to
         * a listener that will now drop it. The next turn takes that answer out again, under the
         * lock: whoever cancels must not have to wait for a turn.
         */
        fun cancel() {
            if (state.compareAndSet(RUNNING, CANCELLED)) {
                call?.cancel()
                worker?.interrupt()
            } else {
                state.compareAndSet(FINISHED, ANSWER_TAKEN_BACK)
            }
        }

        fun run() {
            worker = Thread.currentThread()
            val output = try {
                turnInProgress.withLock {
                    undoAnswerTakenBack()
                    if (input == BrainInput.Opening) openingOwed = true
                    if (cancelled) null else answer()
                }
            } catch (e: Exception) {
                // Nothing here may end the app, and the connector is waiting for an answer
                log("brain: turn failed: ${e.javaClass.simpleName}")
                state.compareAndSet(RUNNING, FINISHED)
                if (cancelled) null else BrainOutput(failed = BrainFailure.OTHER)
            } ?: return
            // Taken back just now: nobody is waiting for it any more
            if (cancelled) return
            try {
                done(output)
            } catch (e: Exception) {
                log("brain: listener failed: ${e.javaClass.simpleName}")
            }
        }

        private fun answer(): BrainOutput? {
            val number = ++turnsAsked
            val started = System.nanoTime()
            val content = userContent(input)
            if (content.isEmpty()) return if (state.compareAndSet(RUNNING, FINISHED)) BrainOutput() else null
            val asked = withUserMessage(content)
            val outcome = askWithOneRetry(number, asked)
            if (outcome == null || !state.compareAndSet(RUNNING, FINISHED)) return null
            val conversation = Conversation(history, openTools, openingOwed)
            val output = when (outcome) {
                is Outcome.Failed -> BrainOutput(failed = outcome.failure)
                is Outcome.Answered -> settled(asked, outcome.reply)
            }
            before = conversation
            lastAnswered = this
            val firstLine = firstLineAt?.let { millisBetween(started, it) }
            log("brain: turn $number ${summary(output, (outcome as? Outcome.Answered)?.reply, started)} lines=$linesOut first_line=$firstLine ms=${millisBetween(started, System.nanoTime())}")
            return output
        }

        private fun lineOut(sentence: String) {
            if (cancelled) return
            linesOut++
            if (firstLineAt == null) firstLineAt = System.nanoTime()
            try {
                onLine(SpokenNumbers.peopleInKana(sentence))
            } catch (e: Exception) {
                log("brain: listener failed: ${e.javaClass.simpleName}")
            }
        }

        /** null: taken back. */
        private fun askWithOneRetry(number: Int, asked: List<JsonObject>): Outcome? {
            val first = askOrAdapt(number, asked)
            // What went out has been said: asking again would say it twice
            if (first !is Outcome.Failed || !first.retry || linesOut > 0) return first
            val wait = maxOf(RETRY_PAUSE_MS, first.retryAfterMs ?: 0)
            if (wait > LONGEST_RETRY_WAIT_MS) return first
            log("brain: turn $number trying once more after ${first.failure}")
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                return null
            }
            return if (cancelled) null else askOrAdapt(number, asked)
        }

        /** A request refused as written, while it has the breakpoint at the top, goes once more without it, once in a call. */
        private fun askOrAdapt(number: Int, asked: List<JsonObject>): Outcome? {
            val outcome = ask(requestBody(asked))
            if (outcome !is Outcome.Failed || !outcome.refusedAsWritten || !cacheAtTheTop) return outcome
            cacheAtTheTop = false
            log("brain: turn $number sending again without the top-level cache_control")
            return if (cancelled) null else ask(requestBody(asked))
        }

        private fun ask(body: String): Outcome? {
            val current = client.newCall(request(body))
            call = current
            // A cancel in the moment before there was a call to cancel
            if (cancelled) return null
            return try {
                current.execute().use { response -> if (response.isSuccessful) readStream(response, ::lineOut) else failureOf(response) }
            } catch (_: InterruptedIOException) {
                if (cancelled) null else Outcome.Failed(BrainFailure.TIMEOUT)
            } catch (_: IOException) {
                if (cancelled) null else Outcome.Failed(BrainFailure.NETWORK)
            }
        }
    }

    private fun undoAnswerTakenBack() {
        val before = lastAnswered?.undo
        lastAnswered = null
        if (before == null) return
        history = before.history
        openTools = before.openTools
        openingOwed = before.openingOwed
        log("brain: the last answer was taken back")
    }

    /** What one turn's answer changes. */
    private class Conversation(val history: List<JsonObject>, val openTools: List<String>, val openingOwed: Boolean)

    private sealed interface Outcome {
        class Failed(
            val failure: BrainFailure,
            val retry: Boolean = false,
            val retryAfterMs: Long? = null,
            val refusedAsWritten: Boolean = false,
        ) : Outcome

        class Answered(val reply: Reply) : Outcome
    }

    /** One block of the answer, put together from its pieces. What is not spelled out here stays as it came. */
    private class Block(private val start: JsonObject) {
        val type: String? = start.text("type")
        private val written = StringBuilder()
        private val partialJson = StringBuilder()
        private var signature: String? = null

        fun add(delta: JsonObject) {
            when (delta.text("type")) {
                "text_delta" -> written.append(delta.text("text").orEmpty())
                "thinking_delta" -> written.append(delta.text("thinking").orEmpty())
                "signature_delta" -> signature = delta.text("signature")
                "input_json_delta" -> partialJson.append(delta.text("partial_json").orEmpty())
            }
        }

        /** The block as it goes back into the history. Throws if a tool's input is not a JSON object. */
        fun finished(): JsonObject = when (type) {
            "text" -> JsonObject(start + ("text" to JsonPrimitive(start.text("text").orEmpty() + written)))
            "thinking" -> JsonObject(
                start + ("thinking" to JsonPrimitive(start.text("thinking").orEmpty() + written)) +
                    ("signature" to JsonPrimitive(signature ?: start.text("signature").orEmpty())),
            )
            "tool_use" -> if (partialJson.isBlank()) start else JsonObject(start + ("input" to Json.parseToJsonElement(partialJson.toString()).jsonObject))
            else -> start
        }
    }

    /** The words of an answer as they come, cut where sentences end. */
    private class Sentences {
        private val waiting = StringBuilder()

        /** What has not gone out yet. */
        val rest: String get() = waiting.toString()

        /** Takes more words, and gives the sentences they finish. A piece too short to stand alone waits for the next. */
        fun add(words: String): List<String> {
            waiting.append(words)
            val finished = ArrayList<String>()
            var start = 0
            var at = 0
            while (at < waiting.length) {
                if (waiting[at++] !in SENTENCE_ENDS) continue
                while (at < waiting.length && waiting[at] in SENTENCE_ENDS) at++
                val sentence = waiting.substring(start, at).trim()
                if (CallBrainPrompt.isNothingToSay(sentence)) {
                    start = at
                } else if (sentence.length >= SHORTEST_SENTENCE) {
                    finished += sentence
                    start = at
                }
            }
            waiting.delete(0, start)
            return finished
        }
    }

    private class Reply {
        val blocks = sortedMapOf<Int, Block>()
        val sentences = Sentences()
        var stopReason: String? = null
        var inputTokens: Int? = null
        var cachedTokens: Int? = null
        var outputTokens: Int? = null

        /** When the first word or tool of the answer came, for the log. */
        var firstOutputAt: Long? = null
            private set

        fun outputBegan() {
            if (firstOutputAt == null) firstOutputAt = System.nanoTime()
        }
    }

    /** The sentences go out as they finish. What the answer comes to is decided after the end: [Reply.stopReason] comes after the blocks. */
    private fun readStream(response: Response, lineOut: (String) -> Unit): Outcome {
        val reply = Reply()
        val source = response.body.source()
        while (true) {
            // Ended without message_stop: the connection went
            val line = source.readUtf8Line() ?: return Outcome.Failed(BrainFailure.NETWORK)
            if (!line.startsWith("data:")) continue
            val event = try {
                Json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject
            } catch (_: IllegalArgumentException) {
                return Outcome.Failed(BrainFailure.OTHER)
            }
            when (event.text("type")) {
                "message_start" -> event.obj("message")?.obj("usage")?.let {
                    reply.inputTokens = it.int("input_tokens")
                    reply.cachedTokens = it.int("cache_read_input_tokens")
                }
                "content_block_start" -> event.int("index")?.let { index ->
                    event.obj("content_block")?.let { block ->
                        if (block.text("type") == "tool_use") reply.outputBegan()
                        reply.blocks[index] = Block(block)
                    }
                }
                "content_block_delta" -> event.int("index")?.let { index ->
                    event.obj("delta")?.let { delta ->
                        if (delta.text("type") == "text_delta") {
                            reply.outputBegan()
                            reply.sentences.add(delta.text("text").orEmpty()).forEach(lineOut)
                        }
                        reply.blocks[index]?.add(delta)
                    }
                }
                "message_delta" -> {
                    event.obj("delta")?.text("stop_reason")?.let { reply.stopReason = it }
                    event.obj("usage")?.int("output_tokens")?.let { reply.outputTokens = it }
                }
                "message_stop" -> return if (reply.stopReason == null) Outcome.Failed(BrainFailure.OTHER) else Outcome.Answered(reply)
                "error" -> return streamFailure(event.obj("error")?.text("type"))
            }
        }
    }

    private fun streamFailure(type: String?): Outcome.Failed {
        log("brain: stream error=$type")
        return when (type) {
            "overloaded_error", "api_error" -> Outcome.Failed(BrainFailure.OVERLOADED, retry = true)
            "rate_limit_error" -> Outcome.Failed(BrainFailure.RATE_LIMITED, retry = true)
            "timeout_error" -> Outcome.Failed(BrainFailure.TIMEOUT, retry = true)
            "authentication_error", "permission_error" -> Outcome.Failed(BrainFailure.KEY_REFUSED)
            else -> Outcome.Failed(BrainFailure.OTHER)
        }
    }

    /** By the status alone: the text of an error can quote the request. */
    private fun failureOf(response: Response): Outcome.Failed {
        log("brain: http=${response.code}")
        val retryAfter = response.header("retry-after")?.toLongOrNull()?.let { it * 1000 }
        return when (response.code) {
            400 -> Outcome.Failed(BrainFailure.OTHER, refusedAsWritten = true)
            401, 403 -> Outcome.Failed(BrainFailure.KEY_REFUSED)
            429 -> Outcome.Failed(BrainFailure.RATE_LIMITED, retry = true, retryAfterMs = retryAfter)
            in 500..599 -> Outcome.Failed(BrainFailure.OVERLOADED, retry = true, retryAfterMs = retryAfter)
            else -> Outcome.Failed(BrainFailure.OTHER)
        }
    }

    /**
     * What the answer comes to, and what of it the history keeps. How it ended is looked at first.
     * Only tool_use says that the calls are whole: one cut off by the limit has half its input.
     */
    private fun settled(asked: List<JsonObject>, reply: Reply): BrainOutput {
        if (reply.stopReason == "refusal") return BrainOutput(failed = BrainFailure.REFUSED)
        val blocks = if (reply.stopReason == "tool_use") reply.blocks.values.toList() else reply.blocks.values.takeWhile { it.type != "tool_use" }
        val finished = try {
            // A text block with nothing in it cannot go back to the API
            blocks.map { it.finished() }.filterNot { it.text("type") == "text" && it.text("text").isNullOrBlank() }
        } catch (_: IllegalArgumentException) {
            return BrainOutput(failed = BrainFailure.OTHER)
        }
        val texts = finished.filter { it.text("type") == "text" }
        val toolUses = finished.filter { it.text("type") == "tool_use" }
        if (reply.stopReason == "max_tokens" && texts.isEmpty()) return BrainOutput(failed = BrainFailure.OTHER)
        val calls = toolUses.mapNotNull(ClaudeCallTools::toFunctionCall)
        // Thinking alone is no message: the next user message joins this one
        history = if (texts.isEmpty() && toolUses.isEmpty()) asked else asked + assistantMessage(finished)
        openTools = toolUses.mapNotNull { it.text("id") }
        openingOwed = false
        return BrainOutput(say = spoken(reply.sentences.rest), calls = calls)
    }

    /** null: nothing (left) to say. */
    private fun spoken(line: String): String? =
        line.takeUnless(CallBrainPrompt::isNothingToSay)?.trim()?.let(SpokenNumbers::peopleInKana)

    /** The tool results first, as the API asks: one for every open tool, in their order. */
    private fun userContent(input: BrainInput): List<JsonObject> {
        val answered = (input as? BrainInput.ToolResults)?.results.orEmpty().associateBy { it.id }
        val opening = BrainInput.Opening.takeIf { openingOwed && input != it }
        return openTools.map { id -> answered[id]?.let(ClaudeCallTools::toolResult) ?: notRun(id) } +
            listOfNotNull(opening, input).mapNotNull { CallBrainPrompt.userText(it)?.let(::textBlock) }
    }

    private fun notRun(id: String) = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", id)
        put("is_error", true)
        put("content", NOT_RUN)
    }

    private fun textBlock(text: String) = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    /** The history and the new message. Two user messages in a row are refused, so after a turn with nothing to keep it joins the last. */
    private fun withUserMessage(content: List<JsonObject>): List<JsonObject> {
        val last = history.lastOrNull()
        if (last?.text("role") != "user") return history + userMessage(content)
        return history.dropLast(1) + userMessage(last.getValue("content").jsonArray + content)
    }

    private fun userMessage(content: List<JsonElement>) = buildJsonObject {
        put("role", "user")
        put("content", JsonArray(content))
    }

    private fun assistantMessage(content: List<JsonObject>) = buildJsonObject {
        put("role", "assistant")
        put("content", JsonArray(content))
    }

    private fun requestBody(messages: List<JsonObject>): String = buildJsonObject {
        common(MAX_TOKENS)
        put("stream", true)
        // Moves along with the conversation; the instruction has its own breakpoint
        if (cacheAtTheTop) putJsonObject("cache_control") { put("type", "ephemeral") }
        put("messages", JsonArray(messages))
    }.toString()

    /** Writes the cache for what every turn starts with, and generates nothing. Must say the same up to the breakpoint. */
    private fun warmUpBody(): String = buildJsonObject {
        common(0)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", "warmup")
            }
        }
    }.toString()

    private fun JsonObjectBuilder.common(maxTokens: Int) {
        put("model", IntakeMessages.MODEL)
        put("max_tokens", maxTokens)
        putJsonObject("thinking") { put("type", "adaptive") }
        putJsonObject("output_config") { put("effort", IntakeMessages.EFFORT) }
        put("tools", tools)
        put(
            "system",
            buildJsonArray {
                addJsonObject {
                    put("type", "text")
                    put("text", system)
                    putJsonObject("cache_control") { put("type", "ephemeral") }
                }
            },
        )
    }

    private fun request(body: String) = Request.Builder()
        .url(url)
        .header("x-api-key", apiKey)
        .header("anthropic-version", IntakeMessages.API_VERSION)
        .post(body.toRequestBody(JSON))
        .build()

    /** States, counts and times only. first: from the start of the turn to the first word or tool of the answer. */
    private fun summary(output: BrainOutput, reply: Reply?, started: Long): String {
        val usage = reply?.let {
            val first = it.firstOutputAt?.let { at -> millisBetween(started, at) }
            " stop=${it.stopReason} in=${it.inputTokens} cache_read=${it.cachedTokens} out=${it.outputTokens} first=$first"
        }.orEmpty()
        return "failed=${output.failed} say=${output.say != null} tools=${output.calls.size}$usage"
    }

    private fun millisBetween(from: Long, to: Long) = (to - from) / 1_000_000

    private companion object {
        const val RUNNING = 0
        const val FINISHED = 1
        const val CANCELLED = 2
        const val ANSWER_TAKEN_BACK = 3
        const val MAX_TOKENS = 1024
        const val SENTENCE_ENDS = "。！？"
        const val SHORTEST_SENTENCE = 4
        const val RETRY_PAUSE_MS = 300L
        const val LONGEST_RETRY_WAIT_MS = 1_000L
        const val CONNECT_TIMEOUT_SECONDS = 5L
        const val READ_TIMEOUT_SECONDS = 8L
        const val WRITE_TIMEOUT_SECONDS = 5L
        const val CALL_TIMEOUT_SECONDS = 25L
        const val NOT_RUN = "実行していません"
        val JSON = "application/json".toMediaType()

        /** Never with logging: the bodies hold what was said, the headers the key. */
        val DEFAULT_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }

        fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

        fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    }
}
