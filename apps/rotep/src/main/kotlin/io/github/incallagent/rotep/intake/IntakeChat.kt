// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.intake

import io.github.incallagent.core.policy.BusinessKind
import io.github.incallagent.core.policy.PolicyProblem
import io.github.incallagent.core.policy.PolicyValidator
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.call.SpokenReading
import io.github.incallagent.rotep.practice.PracticePolicy
import io.github.incallagent.rotep.request.PhoneRequestCodec
import io.github.incallagent.rotep.research.ShopFindings
import io.github.incallagent.rotep.ui.RequestForm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

sealed interface IntakeReply {
    data class Arrived(val json: String) : IntakeReply

    /**
     * [reason] is for the screen. [canRetry]: sending the same again can work.
     * [dropLastSaid]: this text cannot be sent at all, so it goes back to the input.
     */
    data class Failed(val reason: String, val canRetry: Boolean = true, val dropLastSaid: Boolean = false) : IntakeReply
}

/** Sends one request. [done] may come on any thread. Returns what stops it. */
fun interface IntakeTransport {
    fun send(body: String, done: (IntakeReply) -> Unit): () -> Unit
}

/** Looks the shop up during the chat. [done] may come on any thread. */
fun interface IntakeSearch {
    fun search(query: ChatQuery, done: (IntakeSearchResult) -> Unit): () -> Unit
}

/** A fixed question with buttons: the app's words, never the model's. [tells]: what its one answer settles, all together. */
data class IntakeQuestion(val ask: IntakeAsk, val text: String, val budgetYen: Int?, val partyRange: IntRange? = null, val tells: List<IntakeTell> = emptyList())

/** What a search found, shown as it came. The model reads it too, as one line of the chat: it can inform a question, never decide anything. */
data class ChatSearch(val shop: String, val windows: List<String>, val findings: ShopFindings)

private fun noSearch(done: (IntakeSearchResult) -> Unit): () -> Unit {
    done(IntakeSearchResult.NotFound(""))
    return {}
}

/** The last line of the conversation: a search result can land between a message and its answer. */
internal fun List<IntakeLine>.lastSpoken(): IntakeLine? = lastOrNull { it.who != IntakeLine.Who.SEARCH }

data class IntakeUi(
    val lines: List<IntakeLine> = emptyList(),
    /** What the app has accepted so far, or null before the first answer. */
    val form: RequestForm? = null,
    /** Something was understood: there is more than an empty form to take to the form screen. */
    val accepted: Boolean = false,
    val permissions: IntakePermissions = IntakePermissions(),
    val waiting: Boolean = false,
    val error: String? = null,
    /** Sending the last message again can work. */
    val retryable: Boolean = false,
    val question: IntakeQuestion? = null,
    /** Why the request could not be confirmed yet. */
    val missing: List<String> = emptyList(),
    /** Fields the chat gave up on: the user enters them on the form. */
    val forTheForm: List<String> = emptyList(),
    val full: Boolean = false,
    /** What the user is writing: kept here so that a visit to the settings does not lose it. */
    val typing: String = "",
    val searching: Boolean = false,
    val searches: List<ChatSearch> = emptyList(),
    /** Why the shop could not be looked up. The chat goes on without it. */
    val searchNote: String? = null,
)

/**
 * The chat that fills in the request. It holds no more than this screen needs, in memory, and can
 * only produce a [RequestForm]: nothing here can start a call or approve one. One request at a
 * time; an answer that comes after the chat moved on is dropped.
 */
class IntakeChat(
    private val today: () -> LocalDate,
    private val now: () -> Instant,
    private val transport: IntakeTransport,
    private val log: (String) -> Unit = {},
    private val search: IntakeSearch = IntakeSearch { _, done -> noSearch(done) },
    /** No call will be made: the shop need not exist and no number is needed. */
    private val practice: () -> Boolean = { false },
) {
    private val _ui = MutableStateFlow(IntakeUi())
    val ui: StateFlow<IntakeUi> = _ui

    private var run = 0
    private var stopSending: (() -> Unit)? = null
    private var knownFields = JsonObject(emptyMap())
    private var notAccepted: Set<IntakeDraft.Said> = emptySet()
    private var unusable: Set<String> = emptySet()
    private var sameNumber = false
    private val refused = HashMap<IntakeDraft.Said, Int>()

    /** A number that belonged to another shop, and the message that moved away from it. */
    private val retired = HashMap<String, Int>()

    /** The question the text being sent replaced: it comes back if the text cannot be sent. */
    private var questionBeforeSaying: IntakeQuestion? = null

    /** How the AI says what the user wrote. */
    private val readings = LinkedHashMap<String, String>()

    private var searchRun = 0
    private var stopSearching: (() -> Unit)? = null
    private var searchedFor: String? = null
    private var searchingShop: String? = null
    private var searchCount = 0

    /** A search result arrived while the model was busy: it is read once the model is done. */
    private var resultUnread = false

    /** The turn on the way was started by a search result, not by anything the user wrote. */
    private var autoTurn = false

    /** How many lines the turn on the way carried, and how many the last answered turn did. */
    private var sentLines = 0
    private var answeredLines = 0

    @Synchronized
    fun type(text: String) {
        _ui.value = _ui.value.copy(typing = text)
    }

    /** false when it was not taken (still waiting, empty, too long, or the chat is full). */
    @Synchronized
    fun say(text: String): Boolean {
        val words = text.trim()
        val state = _ui.value
        if (state.waiting || words.isEmpty()) return false
        if (words.length > MAX_CHARS) {
            _ui.value = state.copy(error = "1 回に $MAX_CHARS 字までです", retryable = false)
            return false
        }
        if (state.lines.count { it.who == IntakeLine.Who.USER } >= MAX_TURNS) {
            _ui.value = state.copy(full = true)
            return false
        }
        questionBeforeSaying = state.question
        _ui.value = state.copy(lines = state.lines + IntakeLine(IntakeLine.Who.USER, words), question = null, typing = "")
        request()
        return true
    }

    /** The user pressed [はい] or [いいえ] on the question. */
    @Synchronized
    fun answer(agreed: Boolean) {
        val state = _ui.value
        val question = state.question ?: return
        if (state.waiting) return
        val permissions = state.permissions.answering(question.ask, agreed, question.budgetYen, question.partyRange, question.tells)
        val items = question.tells.takeIf { it.isNotEmpty() }?.joinToString("、", "（", "）") { "${it.label}：${it.value}" }.orEmpty()
        _ui.value = state.copy(
            lines = state.lines + IntakeLine(IntakeLine.Who.ANSWER, "${question.ask.label}$items：${if (agreed) "はい" else "いいえ"}"),
            permissions = permissions,
            form = state.form?.let { IntakeDraft.applying(it, permissions) },
            question = null,
        )
        request()
    }

    @Synchronized
    fun retry() {
        val state = _ui.value
        // A retry is the same kind of turn: one that no words of the user's led to stays so
        if (!state.waiting && state.retryable) request(reactToSearch = autoTurn)
    }

    /** Stops waiting for the answer. */
    @Synchronized
    fun stop() {
        cancelSending()
        val state = _ui.value
        val notApplied = state.lines.lastSpoken()?.who == IntakeLine.Who.USER
        _ui.value = state.copy(waiting = false, error = if (notApplied) "送るのをやめました。最後の文はまだ入っていません" else null, retryable = notApplied)
    }

    /** The chat is over: nothing of it is kept. */
    @Synchronized
    fun close() {
        cancelSending()
        cancelSearch()
        knownFields = JsonObject(emptyMap())
        retired.clear()
        readings.clear()
        _ui.value = IntakeUi()
    }

    private fun cancelSending() {
        run++
        stopSending?.invoke()
        stopSending = null
    }

    private fun request(reactToSearch: Boolean = false) {
        resultUnread = false
        autoTurn = reactToSearch
        val turn = ++run
        val state = _ui.value
        sentLines = state.lines.size
        _ui.value = state.copy(waiting = true, error = null, retryable = false, question = null, full = false)
        val known = state.form?.takeIf { state.accepted }?.let { IntakeDraft.stated(IntakeDraft.summary(it, state.permissions), knownFields) }.orEmpty()
        val leave = givenUp()
        // The fixed question is not in the chat's lines: the user may be answering it in words
        val shown = questionBeforeSaying?.takeIf { state.lines.lastSpoken()?.who == IntakeLine.Who.USER }?.text
        val prompt = IntakeMessages.prompt(state.lines, known, (notAccepted - leave).map { it.label }, leave.map { it.label }, state.missing, unusable.toList(), sameNumber, shown, reactToSearch)
        log("intake: send turn ${state.lines.size}")
        stopSending = transport.send(IntakeMessages.body(IntakeMessages.systemInstruction(today(), practice()), prompt)) { reply -> onReply(turn, reply) }
    }

    /** A field stays given up only while no value for it is held. */
    private fun givenUp() = refused.filter { (field, count) -> count >= REFUSALS_BEFORE_THE_FORM && field.key !in knownFields }.keys

    @Synchronized
    private fun onReply(turn: Int, reply: IntakeReply) {
        if (turn != run) return
        stopSending = null
        val state = _ui.value
        val answer = when (reply) {
            is IntakeReply.Failed -> {
                _ui.value = failed(state, reply)
                return
            }
            is IntakeReply.Arrived -> IntakeMessages.parse(reply.json)
        }
        if (answer == null) {
            log("intake: answer not readable")
            _ui.value = state.copy(waiting = false, error = "AI の返事を読めませんでした。もう一度送ってください", retryable = true)
            return
        }
        answeredLines = sentLines
        applyAnswer(state, if (autoTurn) answer.onlyReply() else answer)
    }

    /** The stage may have changed: what a practice does not need, a real call does. */
    @Synchronized
    fun refresh() {
        val state = _ui.value
        state.form?.let { _ui.value = state.copy(missing = missingFrom(it)) }
    }

    private fun failed(state: IntakeUi, reply: IntakeReply.Failed): IntakeUi {
        val last = state.lines.lastSpoken()
        val giveBack = reply.dropLastSaid && last?.who == IntakeLine.Who.USER
        // What came from the web, and was in no prompt that got an answer, is the likely cause: the chat goes on without it
        val unproven = state.lines.subList(minOf(answeredLines, sentLines), minOf(sentLines, state.lines.size))
        if (reply.dropLastSaid && unproven.any { it.who == IntakeLine.Who.SEARCH && it.text != IntakeMessages.SEARCH_WITHHELD }) {
            return state.copy(
                lines = state.lines.map { if (it.who == IntakeLine.Who.SEARCH) it.copy(text = IntakeMessages.SEARCH_WITHHELD) else it },
                waiting = false,
                error = if (giveBack) "もう一度送ってください" else null,
                retryable = giveBack,
                searchNote = "調べた内容は AI に渡せませんでした。このまま進められます",
            )
        }
        return state.copy(
            lines = if (giveBack) state.lines.filterIndexed { i, _ -> i != state.lines.lastIndexOf(last) } else state.lines,
            // Added to what was written while waiting, not over it
            typing = if (giveBack) listOf(last!!.text, state.typing).filter { it.isNotBlank() }.joinToString("\n") else state.typing,
            question = if (giveBack) questionBeforeSaying else state.question,
            waiting = false,
            error = if (reply.dropLastSaid && !giveBack) "AI がこの会話を続けられませんでした。「手で入力する」から入れてください" else reply.reason,
            retryable = reply.canRetry,
        )
    }

    private fun applyAnswer(state: IntakeUi, given: IntakeAnswer) {
        val practicing = practice()
        val said = state.lines.filter { it.who == IntakeLine.Who.USER }.map { it.text }
        val afterWords = state.lines.lastSpoken()?.who == IntakeLine.Who.USER
        // Only the user's words change the request. The model may have read the web since they were written
        val fromWords = if (afterWords) given else given.copy(fields = JsonObject(emptyMap()), cleared = emptySet(), readings = emptyList())
        // A made-up number in a practice is not something to refuse or to discuss
        val answer = if (practicing) fromWords.copy(fields = JsonObject(fromWords.fields.filterNot { (key, value) -> key in NUMBER_FIELDS && unreadableNumber(value) })) else fromWords
        val merged = merged(answer, said, afterWords, state.form?.purpose)
        var permissions = state.permissions
        val firstTry = IntakeDraft.build(merged.fields, said, today(), permissions)
        // Twice: putting an accepted value back can make a pair of its own (one number for both the shop and the contact)
        val secondTry = IntakeDraft.build(keepingAccepted(merged, firstTry.notAccepted, firstTry.unusable), said, today(), permissions)
        val refusedNow = firstTry.notAccepted + secondTry.notAccepted
        val shopRefused = IntakeDraft.Said.SHOP in refusedNow || "shop_name" in firstTry.unusable
        val numberLost = answer.fields["phone_number"]?.let(IntakeDraft::hasValue) == true &&
            shopRefused && "phone_number" !in knownFields && "shop_name" in knownFields
        knownFields = keepingAccepted(merged, refusedNow, firstTry.unusable)
        var built = IntakeDraft.build(knownFields, said, today(), permissions)
        // "OK with the shop's cancellation terms" is not "cancel whatever the fee" (RequestForm.withPurpose)
        val before = state.form?.purpose
        var feeForgotten = false
        if (before != null && (before == Purpose.CANCEL) != (built.form.purpose == Purpose.CANCEL)) {
            feeForgotten = IntakeAsk.CANCELLATION_POLICY in permissions.answered
            permissions = permissions.forgetting(IntakeAsk.CANCELLATION_POLICY)
            built = IntakeDraft.build(knownFields, said, today(), permissions)
        }
        // A range that no longer holds the number wished for would stop the request at the validator
        val rangeLost = permissions.partyRange?.let { range -> built.form.partyPreferred.toIntOrNull()?.let { it in range } != true } == true
        if (rangeLost) {
            permissions = permissions.forgetting(IntakeAsk.PARTY_RANGE)
            built = IntakeDraft.build(knownFields, said, today(), permissions)
        }
        // A turn without new words has nothing new to refuse: what the model was last told still holds
        if (afterWords) {
            notAccepted = (if (numberLost) refusedNow + IntakeDraft.Said.PHONE else refusedNow) - if (practicing) setOf(IntakeDraft.Said.PHONE, IntakeDraft.Said.CALLBACK) else emptySet()
            unusable = firstTry.unusable
            sameNumber = !practicing && (firstTry.sameNumber || secondTry.sameNumber)
            for (field in IntakeDraft.Said.entries) {
                when {
                    field in notAccepted -> refused.merge(field, 1, Int::plus)
                    field.key in answer.cleared || answer.fields[field.key] == JsonNull -> refused.remove(field)
                    answer.fields[field.key]?.let(IntakeDraft::hasValue) == true -> refused.remove(field)
                }
            }
        }
        log("intake: answer fields=${answer.fields.size} refused=${notAccepted.size}")
        val missing = missingFrom(built.form)
        val amount = answer.budgetYen?.takeIf { it in 1..MAX_BUDGET_YEN }
        val range = answer.partyMin?.let { min -> answer.partyMax?.takeIf { it > min }?.let { min..it } }
        val offer = Offer(amount ?: permissions.budgetYen, range ?: permissions.partyRange, tellsToOffer(answer.tells, permissions, said, afterWords))
        val question = IntakeAsk.CANCELLATION_POLICY.takeIf { feeForgotten }?.let { questionFor(it, built.form, Offer()) }
            ?: answer.ask?.takeIf { it == IntakeAsk.MAY_TELL || canAsk(it, permissions, amount, range, afterWords) }?.let { questionFor(it, built.form, offer) }
        val stayedNo = answer.ask?.takeIf { it in permissions.answered && it !in permissions.yes && afterWords }
            ?: IntakeAsk.MAY_TELL.takeIf { answer.ask == it && afterWords && answer.tells.any { t -> t in permissions.tellAsked && t !in permissions.tell } }
        val keepWriting = givenUp().takeIf { it.isNotEmpty() }?.let { up -> up.joinToString("、") { it.label } + "は「手で入力する」から入れてください" } ?: KEEP_WRITING
        val reply = when {
            // The sentence read just before the button is the app's too
            question != null -> "いくつか確認します"
            rangeLost -> "人数の幅は、新しい人数に合わないので外しました。必要なら、もう一度書いてください"
            IntakeGrounding.unsaidNumber(answer.reply, said) -> "調べた内容を見て、変えたいことがあれば書いてください"
            stayedNo != null -> "${stayedNo.label}は「いいえ」のままです。変えるときは「手で入力する」から変えてください"
            // The model does not know yet that what it reported was not taken
            (notAccepted.map { it.key } + unusable).any { it in answer.fields && it in knownFields } -> "変更できなかった項目があります。いまの内容を見て、もう一度書いてください"
            missing.isNotEmpty() && "確認へ" in answer.reply -> keepWriting
            answer.ask == null -> answer.reply
            missing.isEmpty() -> "内容を確かめて、「確認へ」を押してください"
            else -> keepWriting
        }
        learnReadings(answer.readings, said)
        _ui.value = state.copy(
            lines = state.lines + IntakeLine(IntakeLine.Who.AI, reply),
            form = built.form.copy(readings = readings.map { SpokenReading(it.key, it.value) }),
            accepted = built.form != IntakeDraft.build(JsonObject(emptyMap()), said, today(), permissions).form,
            permissions = permissions,
            waiting = false,
            error = null,
            retryable = false,
            question = question,
            missing = missing,
            forTheForm = givenUp().map { it.label },
        )
        searchIfUseful(built.form)
        if (resultUnread) {
            resultUnread = false
            readSearchIfDue()
        }
    }

    private fun unreadableNumber(value: JsonElement?) = textOf(value)?.let { IntakeGrounding.numberKey(it) == null } == true

    /** The model looks at a new result by itself, unless the user is in the middle of something. */
    private fun readSearchIfDue() {
        val state = _ui.value
        // The conversation may have moved on: this result is not about what is being asked for now
        val last = state.searches.lastOrNull()
        val form = state.form
        if (last == null || form == null || !IntakeGrounding.sameText(last.shop, form.businessName)) return
        if (last.windows != (if (form.takesBooking) IntakeDraft.windowsText(form) else emptyList())) return
        if (!state.waiting && state.question == null && state.typing.isBlank() && !state.full && state.error == null) request(reactToSearch = true)
    }

    /** Only words the user wrote, with kanji in them, read in kana. */
    private fun learnReadings(given: List<SpokenReading>, said: List<String>) {
        for ((text, kana) in given) {
            if (text !in readings && readings.size >= MAX_READINGS) break
            // A reading is as long as a reading. What goes in the kana is said on the phone, as written
            if (text.length <= MAX_READING_CHARS && kana.length <= minOf(MAX_READING_CHARS, maxOf(MIN_READING_ROOM, text.length * KANA_PER_CHAR)) &&
                KANJI.containsMatchIn(text) && KANA.matches(kana) && IntakeGrounding.textSaid(text, said)
            ) {
                readings[text] = kana
            }
        }
    }

    /** Once the app knows whom and, for a booking, when. Again when either changes, a few times at most. */
    private fun searchIfUseful(form: RequestForm) {
        val shop = form.businessName
        searchingShop?.takeUnless { IntakeGrounding.sameText(it, shop) }?.let {
            cancelSearch()
            searchedFor = null
            _ui.value = _ui.value.copy(searching = false)
        }
        if (shop.isBlank() || form.purpose == Purpose.CANCEL || isPractice(form) || mayBeAPerson(form)) return
        val windows = if (form.takesBooking) IntakeDraft.windowsText(form) else emptyList()
        if (form.takesBooking && windows.isEmpty()) return
        val wanted = IntakeGrounding.searchKey(shop) + windows
        if (wanted == searchedFor || searchCount >= MAX_SEARCHES) return
        cancelSearch()
        searchedFor = wanted
        searchCount++
        searchingShop = shop
        val turn = ++searchRun
        _ui.value = _ui.value.copy(searching = true, searchNote = null)
        val party = form.partyPreferred.takeIf { form.takesBooking && it.isNotBlank() }
        stopSearching = search.search(ChatQuery(shop, windows, party)) { result -> onSearched(turn, shop, windows, result) }
    }

    private fun isPractice(form: RequestForm) = IntakeGrounding.sameText(form.businessName, PracticePolicy.SHOP_NAME)

    /** The search runs by itself, so nothing that can identify a private person goes out with it. */
    private fun mayBeAPerson(form: RequestForm): Boolean {
        val shop = form.businessName
        // Without a stated kind the form says "restaurant" by default: that says nothing about who it is
        return "kind" !in knownFields || form.kind == BusinessKind.OTHER || IntakeGrounding.holdsNumber(shop) ||
            listOf(form.phoneNumber, form.callbackNumber, form.reservationName).any { IntakeGrounding.mentions(shop, it) }
    }

    /** A call is starting: nothing here may run behind it. What was already looked up stays looked up. */
    @Synchronized
    fun holdStill() {
        cancelSending()
        cancelSearch()
        resultUnread = false
        _ui.value = _ui.value.copy(waiting = false, searching = false)
    }

    private fun cancelSearch() {
        searchRun++
        stopSearching?.invoke()
        stopSearching = null
        searchingShop = null
    }

    @Synchronized
    private fun onSearched(turn: Int, shop: String, windows: List<String>, result: IntakeSearchResult) {
        if (turn != searchRun) return
        stopSearching = null
        searchingShop = null
        val state = _ui.value
        _ui.value = when (result) {
            is IntakeSearchResult.Found -> state.copy(
                searching = false,
                searchNote = null,
                lines = state.lines + IntakeLine(IntakeLine.Who.SEARCH, IntakeMessages.searchLine(result.findings.text, shop, windows), state.searches.size),
                searches = state.searches + ChatSearch(shop, windows, result.findings),
            )
            is IntakeSearchResult.NotFound -> state.copy(searching = false, searchNote = result.note.ifBlank { null })
            is IntakeSearchResult.Failed -> {
                searchedFor = null
                state.copy(searching = false, searchNote = result.reason)
            }
        }
        if (result is IntakeSearchResult.Found) {
            if (_ui.value.waiting) resultUnread = true else readSearchIfDue()
        }
    }

    /** Not asked twice, unless the user has since written something that reopens it. Never straight after a button. */
    private fun canAsk(ask: IntakeAsk, permissions: IntakePermissions, amount: Int?, range: IntRange?, afterWords: Boolean): Boolean {
        if (ask !in permissions.answered) return true
        if (!afterWords) return false
        return ask in permissions.yes || (ask == IntakeAsk.BUDGET && amount != null) || (ask == IntakeAsk.PARTY_RANGE && range != null)
    }

    /**
     * What is known after this answer. The model is asked for everything each time, so what it
     * leaves out is not forgotten; `cleared` (or an empty list) takes a value back.
     */
    private fun merged(answer: IntakeAnswer, said: List<String>, afterWords: Boolean, purposeBefore: Purpose?): Merged {
        val new = answer.fields.filterValues(IntakeDraft::hasValue)
        val taken = answer.cleared + answer.fields.filterValues { it == JsonNull || (it is JsonArray && it.isEmpty()) }.keys
        val result = (knownFields + new - taken).toMutableMap()
        // Another shop: the number written for the last one is not this one's
        val moved = shopMoved(new, taken)
        if (moved) {
            val oldKey = textOf(knownFields["phone_number"])?.let(IntakeGrounding::numberKey)
            val reported = textOf(new["phone_number"])
            // "The shop's name is X, the number is the same": written in the very message that moves the shop
            val writtenAgain = oldKey != null && afterWords && reported != null &&
                IntakeGrounding.numberKey(reported) == oldKey && IntakeGrounding.numberSaid(reported, said.takeLast(1))
            if (!writtenAgain) {
                oldKey?.let { retired[it] = said.size }
                if (!supplied("phone_number", new)) result.remove("phone_number")
            }
        }
        // ... and it does not come back with a later answer, unless the user writes it again
        val number = textOf(result["phone_number"])
        val movedAwayAt = number?.let(IntakeGrounding::numberKey)?.let(retired::get)
        val held = knownFields["phone_number"]
        val stillHeld = number?.let(IntakeGrounding::numberKey) == textOf(held)?.let(IntakeGrounding::numberKey)
        if (number != null && movedAwayAt != null && !stillHeld && !IntakeGrounding.numberSaid(number, said.drop(movedAwayAt))) {
            // The same shop keeps the number it has; a new one has none yet
            if (!moved && held != null) result["phone_number"] = held else result.remove("phone_number")
        }
        // Another purpose: the note means something else for each one (RequestForm.withPurpose)
        val purpose = textOf(new["purpose"])
        val purposeMoved = purpose != null && purposeBefore != null && purpose != purposeBefore.name.lowercase(Locale.ROOT)
        if (purposeMoved && !supplied("notes_for_shop", new)) result.remove("notes_for_shop")
        val noRestore = HashSet(taken)
        if ("shop_name" in taken) noRestore += "phone_number"
        if (purposeMoved) noRestore += "notes_for_shop"
        return Merged(JsonObject(result), noRestore)
    }

    /** [noRestore]: what the user has moved away from, so a refused replacement does not bring it back. */
    private class Merged(val fields: JsonObject, val noRestore: Set<String>)

    private fun shopMoved(new: Map<String, JsonElement>, taken: Set<String>): Boolean {
        val old = textOf(knownFields["shop_name"]) ?: return false
        if ("shop_name" in taken) return true
        val now = textOf(new["shop_name"]) ?: return false
        return !IntakeGrounding.sameText(old, now)
    }

    /** Given in this answer, and not just repeated from before. */
    private fun supplied(key: String, new: Map<String, JsonElement>): Boolean {
        val value = new[key] ?: return false
        if (key == "phone_number") {
            // Spelled differently is still the same number
            val now = textOf(value)?.let(IntakeGrounding::numberKey)
            if (now != null && now == textOf(knownFields[key])?.let(IntakeGrounding::numberKey)) return false
        }
        return value != knownFields[key]
    }

    private fun textOf(value: JsonElement?): String? = (value as? JsonPrimitive)?.contentOrNull

    /** A value refused now does not wipe out one accepted before. A shop and its number go back together. */
    private fun keepingAccepted(merged: Merged, refusedNow: Set<IntakeDraft.Said>, notUsable: Set<String>): JsonObject {
        val keys = refusedNow.mapTo(HashSet()) { it.key }
        keys += notUsable
        if (SHOP_AND_NUMBER.any { it in keys } && SHOP_AND_NUMBER.any { it in knownFields }) keys += SHOP_AND_NUMBER
        return JsonObject(merged.fields.filterKeys { it !in keys } + knownFields.filterKeys { it in keys && it !in merged.noRestore })
    }

    /**
     * What goes on the card. Each item stands or falls by itself, and the bad ones use up no places.
     * What was asked stays answered; what was agreed to can be taken back once the user has written
     * something, unless there is something new to ask about first.
     */
    private fun tellsToOffer(tells: List<IntakeTell>, permissions: IntakePermissions, said: List<String>, afterWords: Boolean): List<IntakeTell> {
        val usable = tells.distinct().filter { tellable(it, said) }
        val fresh = usable.filter { it !in permissions.tellAsked }.take(maxOf(0, MAX_TELL - permissions.tell.size))
        return fresh.ifEmpty { usable.filter { afterWords && it in permissions.tell } }
    }

    /** null when the question does not apply to this request. */
    private fun questionFor(ask: IntakeAsk, form: RequestForm, offer: Offer): IntakeQuestion? {
        val cancel = form.purpose == Purpose.CANCEL
        val text = when (ask) {
            IntakeAsk.OUTSIDE_WINDOWS -> "希望の範囲の外の日時でも、AI が受けてよいですか".takeIf { form.takesBooking }
            IntakeAsk.CANCELLATION_POLICY -> when {
                cancel -> "キャンセル料がかかっても、AI が取り消してよいですか（金額の上限はありません）"
                form.takesBooking -> "キャンセル料などの条件に、AI が同意してよいですか（金額の上限はありません）"
                else -> null
            }
            IntakeAsk.PREPAYMENT -> "前払い・予約金に、AI が同意してよいですか（金額の上限はありません）".takeIf { form.takesBooking }
            IntakeAsk.VOICEMAIL -> "留守番電話に、AI が伝言を残してよいですか"
            IntakeAsk.BUDGET -> offer.budgetYen?.takeIf { form.takesBooking && it in 1..MAX_BUDGET_YEN }?.let { "1人あたり ${it}円までなら、AI が受けてよいですか" }
            IntakeAsk.PARTY_RANGE -> offer.partyRange?.takeIf { form.takesBooking && fits(it, form) }?.let { "人数が ${it.first}〜${it.last} 名の範囲なら、AI が受けてよいですか" }
            IntakeAsk.MAY_TELL -> offer.tells.takeIf { it.isNotEmpty() }?.let { items ->
                "お店に聞かれたら、次のことを AI が伝えてよいですか" + (if (items.size > 1) "（一部だけなら、文で書いてください）" else "") + items.joinToString("") { "\n${it.label}：${it.value}" }
            }
        } ?: return null
        return IntakeQuestion(ask, text, offer.budgetYen.takeIf { ask == IntakeAsk.BUDGET }, offer.partyRange.takeIf { ask == IntakeAsk.PARTY_RANGE }, offer.tells.takeIf { ask == IntakeAsk.MAY_TELL }.orEmpty())
    }

    /** A long run of digits that is not a phone number: a card, an account, a member or insurance number. */
    private fun looksLikeAnIdNumber(value: String): Boolean =
        LONG_DIGITS.containsMatchIn(IntakeGrounding.forNumber(value).filterNot { it == ' ' || it == '-' }) && IntakeGrounding.numberKey(value) == null

    /** What the question can be about. */
    private class Offer(val budgetYen: Int? = null, val partyRange: IntRange? = null, val tells: List<IntakeTell> = emptyList())

    private fun fits(range: IntRange, form: RequestForm): Boolean {
        val wished = form.partyPreferred.toIntOrNull() ?: return false
        return range.first >= 1 && range.last <= MAX_PARTY && wished in range
    }

    private fun tellable(item: IntakeTell, said: List<String>): Boolean {
        val whole = item.label + item.value
        val normalized = Normalizer.normalize(whole, Normalizer.Form.NFKC)
        return item.label.length <= MAX_TELL_LABEL && item.value.length <= MAX_TELL_VALUE && item.label.none { it == '：' || it == ':' } &&
            whole.none { it == '\n' || it == '\r' } && !PhoneRequestCodec.hasHidden(whole) && NEVER_TOLD.none { it in normalized } &&
            !looksLikeAnIdNumber(item.value) && !IntakeGrounding.unsaidNumber(item.label, said) && IntakeGrounding.textSaid(item.value, said)
    }

    private fun missingFrom(form: RequestForm): List<String> = when (val built = form.toPolicy(CHECK_ID, now(), today())) {
        is RequestForm.Built.Errors -> built.messages
        is RequestForm.Built.Ok -> PolicyValidator.validate(built.policy, now())
            .filterNot { practice() && isShopNumber(it) }
            .map { if (isShopNumber(it) && form.phoneNumber.isBlank()) "電話番号がまだ書かれていません" else it.message }
    }

    private fun isShopNumber(p: PolicyProblem) = p.code == PolicyProblem.Code.INVALID_PHONE_NUMBER && p.field == "target.phoneNumber"

    private companion object {
        const val MAX_CHARS = 500
        const val MAX_TURNS = 30
        const val MAX_BUDGET_YEN = 1_000_000
        const val REFUSALS_BEFORE_THE_FORM = 2
        const val CHECK_ID = "intake"
        const val KEEP_WRITING = "続けて、足りないことを書いてください"
        const val MAX_SEARCHES = 3
        const val MAX_READINGS = 10
        const val MAX_READING_CHARS = 40
        const val MAX_TELL = 5
        const val MAX_TELL_LABEL = 20
        const val MAX_TELL_VALUE = 100
        const val MAX_PARTY = 50
        val KANJI = Regex("\\p{IsHan}")
        val KANA = Regex("[ぁ-ゖァ-ヺー・ ]+")
        val NEVER_TOLD = listOf("カード", "口座", "住所", "生年月日", "パスワード", "暗証", "マイナンバー", "個人番号", "保険証", "病状", "症状", "持病", "健康")
        val LONG_DIGITS = Regex("[0-9]{8,}")
        val SHOP_AND_NUMBER = listOf("shop_name", "phone_number")
        val NUMBER_FIELDS = setOf("phone_number", "callback_number")
        const val MIN_READING_ROOM = 8
        const val KANA_PER_CHAR = 4
    }
}
