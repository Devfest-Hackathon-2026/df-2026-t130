// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.intake

import io.github.incallagent.core.policy.BusinessKind
import io.github.incallagent.core.policy.PhoneNumbers
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.request.PhoneRequestCodec
import io.github.incallagent.rotep.ui.RequestForm
import io.github.incallagent.rotep.ui.WindowInput
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** The user's answers to the fixed questions. Only a [yes] gives the AI that room. */
data class IntakePermissions(
    val yes: Set<IntakeAsk> = emptySet(),
    val answered: Set<IntakeAsk> = emptySet(),
    val budgetYen: Int? = null,
    val partyRange: IntRange? = null,
    val tell: List<IntakeTell> = emptyList(),
    /** What was already put to the user, whatever the answer: a label and a value, since "はい" can answer anything. */
    val tellAsked: Set<IntakeTell> = emptySet(),
) {
    /** [items]: what one card about MAY_TELL listed. A new value for a label replaces the old one even on a no: the old one is out of date. */
    fun answering(ask: IntakeAsk, agreed: Boolean, budget: Int?, range: IntRange? = null, items: List<IntakeTell> = emptyList()) = copy(
        // One thing told is not all of them: MAY_TELL is kept item by item
        yes = if (ask == IntakeAsk.MAY_TELL) yes else if (agreed) yes + ask else yes - ask,
        answered = if (ask == IntakeAsk.MAY_TELL) answered else answered + ask,
        budgetYen = if (ask == IntakeAsk.BUDGET) budget.takeIf { agreed } else budgetYen,
        partyRange = if (ask == IntakeAsk.PARTY_RANGE) range.takeIf { agreed } else partyRange,
        tell = if (ask == IntakeAsk.MAY_TELL) tell.filterNot { old -> items.any { it.label == old.label } } + items.takeIf { agreed }.orEmpty() else tell,
        tellAsked = if (ask == IntakeAsk.MAY_TELL) tellAsked + items else tellAsked,
    )

    fun forgetting(ask: IntakeAsk) = copy(
        yes = yes - ask,
        answered = answered - ask,
        partyRange = if (ask == IntakeAsk.PARTY_RANGE) null else partyRange,
    )
}

/** Whether what the model reports is something the user actually wrote. */
internal object IntakeGrounding {
    private val NUMBER = Regex("[+0-9][0-9+()\\-ー‐−–— ]{8,}")
    private val DASHES = Regex("[ー‐−–—―‒─━.・]")
    private val MORE_SEPARATORS = Regex("[/~〜]")
    private val COUNTRY = Regex("(?<![+0-9])81(?=[- ]?[1-9])")

    fun textSaid(value: String, said: List<String>): Boolean {
        val want = comparable(value)
        return want.isNotEmpty() && said.any { comparable(it).contains(want) }
    }

    fun numberSaid(value: String, said: List<String>): Boolean {
        val want = PhoneNumbers.normalizeJp(forNumber(value)) ?: return false
        return said.any { message -> NUMBER.findAll(forNumber(message)).any { holds(it.value, want) } }
    }

    /** A run can go on past the number ("03-1234-5678 2名"): whole pieces only, so a shorter number does not pass. */
    private fun holds(run: String, want: String): Boolean {
        val pieces = run.trim().split(' ', '(', ')').filter { it.isNotEmpty() }
        return pieces.indices.any { from ->
            (from until pieces.size).any { to -> PhoneNumbers.normalizeJp(pieces.subList(from, to + 1).joinToString(" ")) == want }
        }
    }

    /** The plain form of a number: what the checks of the confirm screen can read. */
    fun forNumber(text: String): String = DASHES.replace(Normalizer.normalize(text, Normalizer.Form.NFKC), "-")

    /** What identifies a number, whatever its spelling. */
    fun numberKey(value: String): String? = PhoneNumbers.normalizeJp(forNumber(value))

    fun sameText(a: String, b: String): Boolean = comparable(a) == comparable(b)

    /** A phone-like number in [text] that is not in what the user wrote: it came from somewhere else. */
    fun unsaidNumber(text: String, said: List<String>): Boolean {
        val written = said.flatMap { message -> NUMBER.findAll(spelledOut(message)).map { it.value } }
        return NUMBER.findAll(spelledOut(text)).any { run -> holdsAnUnsaidNumber(run.value, written) }
    }

    /** From the left: a number the user wrote is passed over whole; any other number that starts is what is looked for. */
    private fun holdsAnUnsaidNumber(run: String, written: List<String>): Boolean {
        val pieces = run.trim().split(' ', '(', ')').filter { it.isNotEmpty() }
        var from = 0
        while (from < pieces.size) {
            val ends = (from until pieces.size).mapNotNull { to ->
                PhoneNumbers.normalizeJp(pieces.subList(from, to + 1).joinToString(" "))?.let { to to it }
            }
            val lastSaid = ends.filter { (_, key) -> written.any { holds(it, key) } }.maxOfOrNull { it.first }
            when {
                lastSaid != null -> from = lastSaid + 1
                ends.isNotEmpty() -> return true
                else -> from++
            }
        }
        return false
    }

    /** Spellings a voice would still read out as a number. For [unsaidNumber] alone: it can only find more. */
    private fun spelledOut(text: String): String = COUNTRY.replace(MORE_SEPARATORS.replace(forNumber(text), "-"), "+81")

    /** Written digits that look like a number, however they are separated. */
    fun holdsNumber(text: String): Boolean = NUMBER.containsMatchIn(forNumber(text))

    /** [part] is in [whole], however either is spelled. */
    fun mentions(whole: String, part: String): Boolean = comparable(part).let { it.isNotEmpty() && it in comparable(whole) }

    /** A shop's name, however it is spelled: for telling whether it has been looked up already. */
    fun searchKey(shop: String): String = comparable(shop)

    /** Full width and half width, hiragana and katakana, and spacing and punctuation do not count. */
    private fun comparable(text: String) = buildString {
        for (c in Normalizer.normalize(text, Normalizer.Form.NFKC)) {
            if (!c.isLetterOrDigit()) continue
            append(if (c in 'ぁ'..'ゖ') c + 0x60 else c.lowercaseChar())
        }
    }
}

/**
 * Turns what the model reports into the request form. The model is not trusted: a value is taken
 * only in a shape the app accepts, and what must come from the user only if the user wrote it.
 * Bad fields are left out one by one, so one mistake does not lose the rest. What the AI on the
 * phone may decide comes from [IntakePermissions] alone.
 */
object IntakeDraft {
    enum class Said(val key: String, val label: String) {
        SHOP("shop_name", "店名"),
        PHONE("phone_number", "電話番号"),
        NAME("reservation_name", "予約名"),
        CALLBACK("callback_number", "連絡先の電話番号"),
    }

    /** [unusable]: keys given in a shape the app cannot take (too long, too many): what was accepted before stays. */
    data class Built(val form: RequestForm, val notAccepted: Set<Said>, val unusable: Set<String> = emptySet(), val sameNumber: Boolean = false)

    fun build(fields: JsonObject, said: List<String>, today: LocalDate, permissions: IntakePermissions): Built {
        val given = fields.filter { (key, value) -> key in ACCEPTED && hasValue(value) }.toMutableMap()
        val notAccepted = LinkedHashSet<Said>()
        for (field in Said.entries) {
            val value = (given[field.key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val isNumber = field == Said.PHONE || field == Said.CALLBACK
            val saidIt = if (isNumber) IntakeGrounding.numberSaid(value, said) else IntakeGrounding.textSaid(value, said)
            when {
                !saidIt -> {
                    given.remove(field.key)
                    notAccepted += field
                }
                isNumber -> given[field.key] = JsonPrimitive(IntakeGrounding.forNumber(value))
            }
        }
        val sameNumber = sameNumber(given[Said.PHONE.key], given[Said.CALLBACK.key])
        if (sameNumber) {
            given.remove(Said.PHONE.key)
            given.remove(Said.CALLBACK.key)
            notAccepted += Said.PHONE
            notAccepted += Said.CALLBACK
        }
        val unusable = LinkedHashSet<String>()
        // These are said to the shop. The model has read the web: a number the user did not write has no place here
        for (key in SAID_TO_THE_SHOP) {
            val value = given[key] ?: continue
            val texts = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: listOfNotNull((value as? JsonPrimitive)?.contentOrNull)
            if (texts.any { IntakeGrounding.unsaidNumber(it, said) }) {
                given.remove(key)
                unusable += key
            }
        }
        given["windows"]?.let { windows ->
            val usable = usableWindows(windows, today)
            if (usable == null) {
                given.remove("windows")
                unusable += "windows"
            } else {
                given["windows"] = usable
            }
        }
        val valid = given.filter { (key, value) -> PhoneRequestCodec.parse(JsonObject(mapOf(key to value))) is PhoneRequestCodec.Parsed.Ok }.toMutableMap()
        unusable += given.keys - valid.keys
        val purpose = (valid["purpose"] as? JsonPrimitive)?.contentOrNull
        if (purpose == "cancel" || purpose == "inquiry") BOOKING_ONLY.forEach(valid::remove)
        val parsed = PhoneRequestCodec.parse(JsonObject(valid)) as? PhoneRequestCodec.Parsed.Ok
        return Built(applying(parsed?.form ?: RequestForm(partyPreferred = ""), permissions), notAccepted, unusable, sameNumber)
    }

    fun applying(form: RequestForm, permissions: IntakePermissions): RequestForm = form.copy(
        mayAcceptOutsideWindows = IntakeAsk.OUTSIDE_WINDOWS in permissions.yes,
        mayAcceptCancellationPolicy = IntakeAsk.CANCELLATION_POLICY in permissions.yes,
        mayAcceptPrepayment = IntakeAsk.PREPAYMENT in permissions.yes,
        mayLeaveVoicemail = IntakeAsk.VOICEMAIL in permissions.yes,
        maxBudgetPerPersonYen = permissions.budgetYen?.toString().orEmpty(),
        partyMin = permissions.partyRange?.first?.toString().orEmpty(),
        partyMax = permissions.partyRange?.last?.toString().orEmpty(),
        mayTell = permissions.tell.joinToString("\n") { "${it.label}：${it.value}" },
    )

    /** The wished-for times as the app writes them (the weekday comes from the app). */
    fun windowsText(form: RequestForm): List<String> = form.windows.mapNotNull(::windowText)

    /** What is settled so far, for the screen and for telling the model what counts. */
    fun summary(form: RequestForm, permissions: IntakePermissions): List<Pair<String, String>> = buildList {
        fun line(label: String, text: String) {
            if (text.isNotBlank()) add(label to text)
        }
        line("店名", form.businessName)
        line("電話番号", form.phoneNumber)
        line(KIND_LABEL, kindText(form.kind))
        line(PURPOSE_LABEL, purposeText(form.purpose))
        if (form.takesBooking) {
            line("希望の日時", form.windows.mapNotNull(::windowText).joinToString("、"))
            if (form.partyPreferred.isNotBlank()) line("人数", "${form.partyPreferred}名")
            if (form.durationMinutes.isNotBlank()) line("時間", "${form.durationMinutes}分")
            line("必須の条件", form.requiredPreferences)
            line("できれば", form.optionalPreferences)
        }
        line(if (form.takesBooking) "お店に伝えること" else "内容", form.notesForShop)
        line("予約名", form.reservationName)
        line("連絡先", form.callbackNumber)
        val applies = when {
            form.takesBooking -> IntakeAsk.entries.toSet()
            form.purpose == Purpose.CANCEL -> setOf(IntakeAsk.CANCELLATION_POLICY, IntakeAsk.VOICEMAIL)
            else -> setOf(IntakeAsk.VOICEMAIL)
        }
        line("AI に任せること", permissions.yes.filter { it != IntakeAsk.BUDGET && it != IntakeAsk.PARTY_RANGE && it in applies }.joinToString("、") { it.label })
        if (form.takesBooking) permissions.budgetYen?.let { line("予算", "1人 ${it}円まで") }
        if (form.takesBooking) permissions.partyRange?.let { line("人数の幅", "${it.first}〜${it.last}名") }
        line("聞かれたら伝えること", permissions.tell.joinToString("、") { "${it.label}：${it.value}" })
    }

    /** For the model: a default that nobody stated is not a fact. */
    fun stated(summary: List<Pair<String, String>>, known: JsonObject): List<Pair<String, String>> = summary.filterNot { (label, _) ->
        (label == KIND_LABEL && "kind" !in known) || (label == PURPOSE_LABEL && "purpose" !in known)
    }

    private fun kindText(kind: BusinessKind) = when (kind) {
        BusinessKind.RESTAURANT -> "飲食店"
        BusinessKind.SALON -> "美容院など"
        BusinessKind.CLINIC -> "病院・医院"
        BusinessKind.OTHER -> "そのほか"
    }

    private fun purposeText(purpose: Purpose) = when (purpose) {
        Purpose.NEW_BOOKING -> "新しい予約"
        Purpose.CHANGE -> "予約の変更"
        Purpose.CANCEL -> "予約の取り消し"
        Purpose.INQUIRY -> "問い合わせ"
    }

    /** The weekday comes from the app, not from the model. */
    private fun windowText(window: WindowInput): String? {
        val date = dateOf(window.date) ?: return null
        val day = "${date.monthValue}月${date.dayOfMonth}日(${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)})"
        return "$day ${window.from}" + if (window.to.isNotBlank() && window.to != window.from) "〜${window.to} の間に開始" else ""
    }

    private fun usableWindows(value: JsonElement, today: LocalDate): JsonElement? {
        val kept = (value as? JsonArray).orEmpty().mapNotNull { item ->
            val window = item as? JsonObject ?: return@mapNotNull null
            val dateText = text(window, "date") ?: return@mapNotNull null
            val fromText = text(window, "from") ?: return@mapNotNull null
            val toText = text(window, "to")
            val date = dateOf(dateText) ?: return@mapNotNull null
            if (date.isBefore(today) || date.isAfter(today.plusYears(1))) return@mapNotNull null
            val from = RequestForm.parseTime(fromText) ?: return@mapNotNull null
            val to = toText?.let { RequestForm.parseTime(it) ?: return@mapNotNull null }
            if (to != null && to.isBefore(from)) return@mapNotNull null
            // The app's own spelling of the time: what the codec accepts
            buildJsonObject {
                put("date", dateText)
                put("from", from.toString())
                to?.let { put("to", it.toString()) }
            }
        }
        // After the bad ones are gone, so that they do not use up the places
        return kept.take(MAX_WINDOWS).takeIf { it.isNotEmpty() }?.let(::JsonArray)
    }

    private fun sameNumber(phone: JsonElement?, callback: JsonElement?): Boolean {
        val a = (phone as? JsonPrimitive)?.contentOrNull?.let(PhoneNumbers::normalizeJp) ?: return false
        return a == (callback as? JsonPrimitive)?.contentOrNull?.let(PhoneNumbers::normalizeJp)
    }

    private fun dateOf(text: String): LocalDate? = try {
        LocalDate.parse(text)
    } catch (e: DateTimeException) {
        null
    }

    private fun text(from: JsonObject, key: String): String? =
        (from[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    internal fun hasValue(value: JsonElement): Boolean = when (value) {
        JsonNull -> false
        is JsonPrimitive -> !value.isString || value.content.isNotBlank()
        is JsonArray -> value.isNotEmpty()
        is JsonObject -> value.isNotEmpty()
    }

    private const val MAX_WINDOWS = 5
    private val SAID_TO_THE_SHOP = listOf("notes_for_shop", "required_preferences", "optional_preferences")
    private const val KIND_LABEL = "種類"
    private const val PURPOSE_LABEL = "用件"

    private val BOOKING_ONLY = listOf(
        "windows",
        "party_size",
        "duration_minutes",
        "required_preferences",
        "optional_preferences",
    )

    private val ACCEPTED = BOOKING_ONLY.toSet() + setOf(
        "shop_name",
        "phone_number",
        "purpose",
        "kind",
        "notes_for_shop",
        "reservation_name",
        "callback_number",
    )

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
