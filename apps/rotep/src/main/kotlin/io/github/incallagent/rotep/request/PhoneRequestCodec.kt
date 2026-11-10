// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.request

import io.github.incallagent.core.policy.BusinessKind
import io.github.incallagent.core.policy.PhoneNumbers
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.ui.RequestForm
import io.github.incallagent.rotep.ui.WindowInput
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Base64

/** The names used over adb and the server. The same in the manifest and in apps/rotep/mcp/aitel_mcp.py (a unit test keeps them in step). */
object PhoneRequestProtocol {
    const val AUTHORITY = "io.github.incallagent.rotep.request"

    const val METHOD_PING = "ping"
    const val METHOD_CREATE = "create"
    const val METHOD_GET = "get"
    const val METHOD_CANCEL = "cancel"

    /** The one key of the Bundle sent back. The PC reads it out of the text `content call` prints. */
    const val KEY_RESULT = "result"
}

/**
 * What crosses adb or comes through the server: base64url of UTF-8 JSON, so that no shell on the way
 * has anything to interpret.
 *
 * A request comes from another AI, so nothing in it is trusted. It can only fill in what the user
 * would type into the request form and then reads on the confirm screen. Whatever gives the AI on
 * the phone more room (an approval, accepting conditions, a budget, what else may be told) is not
 * accepted: an unknown field is refused rather than dropped, so that the sender does not believe
 * it was granted. A field left out stays empty, so the checks of the confirm screen catch it.
 */
object PhoneRequestCodec {
    const val MAX_ARG_CHARS = 16_000

    sealed interface Parsed {
        /** [callAt]: when the sender would like the call to be placed. Only the user decides it, on the confirm screen. */
        data class Ok(val form: RequestForm, val numberSource: String?, val callAt: Instant? = null) : Parsed
        data class Refused(val problems: List<String>) : Parsed
    }

    /** What is missing from a request, for the sender to answer. [field] is the field of the request. */
    data class Question(val field: String, val question: String)

    /** The JSON object in [arg], or null if it is too long or is not one. */
    fun decode(arg: String?): JsonObject? {
        if (arg == null || arg.length > MAX_ARG_CHARS) return null
        return try {
            Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(arg), Charsets.UTF_8)) as? JsonObject
        } catch (e: IllegalArgumentException) { // not base64
            null
        } catch (e: SerializationException) { // not JSON
            null
        }
    }

    fun encode(reply: JsonObject): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(reply.toString().toByteArray(Charsets.UTF_8))

    /** The string in [key], or null if there is none. */
    fun stringField(from: JsonObject, key: String): String? =
        (from[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun parse(request: JsonObject): Parsed {
        val problems = ArrayList<String>()
        val fields = Fields(request, problems)
        refuseUnknown(request, REQUEST_FIELDS, problems)

        val callback = fields.text("callback_number", "連絡先の電話番号", 24)
        if (callback != null && PhoneNumbers.normalizeJp(callback) == null) problems += "連絡先の電話番号の形が正しくありません"
        val party = fields.integer("party_size", "人数", PARTY_RANGE)
        val purpose = fields.choice("purpose", "用件", PURPOSES) ?: Purpose.NEW_BOOKING
        if (!RequestForm(purpose = purpose).takesBooking) {
            // The form would drop these without a word
            for (name in BOOKING_ONLY_FIELDS.filter { it in request }) problems += "「$name」は、この用件では使えません。notes_for_shop に書いてください"
        }
        val form = RequestForm(
            businessName = fields.text("shop_name", "電話先の名前", 60, plain = true).orEmpty(),
            phoneNumber = fields.text("phone_number", "電話番号", 24).orEmpty(),
            kind = fields.choice("kind", "電話先の種類", KINDS) ?: BusinessKind.RESTAURANT,
            purpose = purpose,
            windows = windows(request["windows"], problems).ifEmpty { listOf(WindowInput()) },
            partyPreferred = party?.toString().orEmpty(),
            durationMinutes = fields.integer("duration_minutes", "時間（分）", 1..1440)?.toString().orEmpty(),
            requiredPreferences = fields.texts("required_preferences", "必須の条件").joinToString("、"),
            optionalPreferences = fields.texts("optional_preferences", "希望").joinToString("、"),
            notesForShop = fields.text("notes_for_shop", "お店に伝えること", 300, flatten = true).orEmpty(),
            reservationName = fields.text("reservation_name", "予約名", 40, plain = true).orEmpty(),
            callbackNumber = callback.orEmpty(),
        )
        val source = fields.text("phone_number_source", "番号の出どころ", 200, flatten = true)
        val callAt = callAt(request["call_at"], problems)
        return if (problems.isEmpty()) Parsed.Ok(form, source?.ifBlank { null }, callAt) else Parsed.Refused(problems)
    }

    /**
     * What the sender still has to tell, in the order it reads best. Empty when nothing is missing:
     * the rest of the checks then apply. A request with something missing is not kept, so nothing
     * of it waits on the phone; the sender sends it again with the answers in it.
     */
    fun missing(form: RequestForm): List<Question> = buildList {
        if (form.businessName.isBlank()) add(Question("shop_name", "電話先のお店の名前は何ですか"))
        if (form.phoneNumber.isBlank()) add(Question("phone_number", "電話先の電話番号は何ですか（出どころの URL は phone_number_source に書いてください）"))
        if (form.takesBooking) {
            if (form.windows.none { it.date.isNotBlank() || it.from.isNotBlank() }) add(Question("windows", "希望の日時はいつですか（候補を優先順に）"))
            if (form.partyPreferred.isBlank()) add(Question("party_size", "何人ですか"))
        }
        if (form.purpose != Purpose.INQUIRY && form.reservationName.isBlank()) add(Question("reservation_name", "予約名は何ですか"))
        if (form.notesForShop.isBlank()) {
            when (form.purpose) {
                Purpose.CHANGE -> add(Question("notes_for_shop", "もとの予約と、変えたいことを教えてください"))
                Purpose.CANCEL -> add(Question("notes_for_shop", "取り消す予約の日時などを教えてください"))
                Purpose.INQUIRY -> add(Question("notes_for_shop", "お店に聞きたいことは何ですか"))
                Purpose.NEW_BOOKING -> {}
            }
        }
    }

    private fun callAt(value: JsonElement?, problems: MutableList<String>): Instant? {
        if (value == null) return null
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= MAX_CALL_AT_CHARS }
        val parsed = text?.let {
            try {
                OffsetDateTime.parse(it).toInstant()
            } catch (e: DateTimeParseException) {
                null
            }
        }
        if (parsed == null) problems += "電話をかける時刻（call_at）は、時差つきの ISO-8601（例 2026-11-10T11:00:00+09:00）で渡してください"
        return parsed
    }

    private fun windows(value: JsonElement?, problems: MutableList<String>): List<WindowInput> {
        if (value == null) return emptyList()
        val array = value as? JsonArray ?: run {
            problems += "希望の日時は配列で渡してください"
            return emptyList()
        }
        if (array.size > MAX_WINDOWS) {
            problems += "希望の日時は $MAX_WINDOWS 件までです"
            return emptyList()
        }
        return array.mapIndexedNotNull { i, item ->
            val label = "希望の日時 ${i + 1}"
            val window = item as? JsonObject ?: run {
                problems += "$label：形が違います"
                return@mapIndexedNotNull null
            }
            refuseUnknown(window, WINDOW_FIELDS, problems)
            val fields = Fields(window, problems)
            WindowInput(
                date = fields.matching("date", "$label の日付", ISO_DATE, "YYYY-MM-DD").orEmpty(),
                from = fields.matching("from", "$label の開始", CLOCK_TIME, "HH:MM").orEmpty(),
                to = fields.matching("to", "$label の終わり", CLOCK_TIME, "HH:MM").orEmpty(),
            )
        }
    }

    private fun refuseUnknown(given: JsonObject, known: Set<String>, problems: MutableList<String>) {
        for (name in (given.keys - known).sorted().take(MAX_NAMED_UNKNOWN)) {
            problems += "「${name.take(40)}」は受け付けません（任せる範囲や承認は、本人がスマホで決めます）"
        }
        if ((given.keys - known).size > MAX_NAMED_UNKNOWN) problems += "受け付けない項目がほかにもあります"
    }

    /** Reads typed fields, and records what is wrong with them in [problems]. */
    private class Fields(private val from: JsonObject, private val problems: MutableList<String>) {
        fun text(key: String, label: String, max: Int, flatten: Boolean = false, plain: Boolean = false): String? =
            readText(from[key] ?: return null, label, max, flatten, plain)

        /** Only one exact form: the loose forms the request screen takes guess at what the user meant. */
        fun matching(key: String, label: String, form: Regex, shown: String): String? {
            val value = text(key, label, MAX_WINDOW_FIELD_CHARS) ?: return null
            if (!form.matches(value)) {
                problems += "${label}は $shown の形で渡してください"
                return null
            }
            return value
        }

        fun texts(key: String, label: String): List<String> {
            val value = from[key] ?: return emptyList()
            val array = value as? JsonArray ?: run {
                problems += "${label}は文字列の配列で渡してください"
                return emptyList()
            }
            if (array.size > MAX_PREFERENCES) {
                problems += "${label}は $MAX_PREFERENCES 件までです"
                return emptyList()
            }
            return array.mapNotNull { readText(it, label, MAX_PREFERENCE_CHARS, flatten = false) }
        }

        fun integer(key: String, label: String, range: IntRange): Int? {
            val value = (from[key] ?: return null) as? JsonPrimitive
            val number = value?.takeUnless { it.isString }?.intOrNull
            if (number == null || number !in range) {
                problems += "${label}は ${range.first}〜${range.last} の整数で渡してください"
                return null
            }
            return number
        }

        fun <T> choice(key: String, label: String, options: Map<String, T>): T? {
            val value = (from[key] ?: return null) as? JsonPrimitive
            val found = value?.takeIf { it.isString }?.contentOrNull?.let(options::get)
            if (found == null) problems += "${label}は ${options.keys.joinToString("、")} のどれかで渡してください"
            return found
        }

        private fun readText(value: JsonElement, label: String, max: Int, flatten: Boolean, plain: Boolean = false): String? {
            val raw = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: run {
                problems += "${label}は文字列で渡してください"
                return null
            }
            val text = (if (flatten) raw.replace(NEWLINES, " ") else raw).trim()
            if (text.length > max) {
                problems += "${label}は $max 文字までです"
                return null
            }
            if (hasHidden(text)) {
                problems += "${label}に、画面に見えない文字が入っています"
                return null
            }
            if (plain && text.any { it in STRUCTURE }) {
                // Names go into the instructions of the voice model as they are: no quotes, no heading marks
                problems += "${label}に 「」『』 と # は使えません"
                return null
            }
            return text
        }
    }

    private val PARTY_RANGE = 1..50
    private const val MAX_WINDOWS = 5
    private const val MAX_WINDOW_FIELD_CHARS = 16
    private const val MAX_PREFERENCES = 5
    private const val MAX_PREFERENCE_CHARS = 40
    private const val MAX_NAMED_UNKNOWN = 10
    private const val MAX_CALL_AT_CHARS = 40
    private const val STRUCTURE = "「」『』#"

    private val NEWLINES = Regex("[\\r\\n]+")
    private val ISO_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val CLOCK_TIME = Regex("\\d{1,2}:\\d{2}")

    /** Characters that do not show on the screen, but are read by a model. */
    internal fun hasHidden(text: String): Boolean = text.codePoints().anyMatch { it in HIDDEN_CODE_POINTS || Character.getType(it).toByte() in HIDDEN }

    /** Variation selectors, and characters of letter or symbol classes that show as nothing at all. */
    private val HIDDEN_CODE_POINTS = ((0xFE00..0xFE0F) + (0xE0100..0xE01EF) + listOf(0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800)).toSet()

    private val HIDDEN = setOf(
        Character.CONTROL,
        Character.FORMAT,
        Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR,
        Character.PRIVATE_USE,
        Character.SURROGATE,
        Character.UNASSIGNED,
    )

    private val PURPOSES = mapOf(
        "new_booking" to Purpose.NEW_BOOKING,
        "change" to Purpose.CHANGE,
        "cancel" to Purpose.CANCEL,
        "inquiry" to Purpose.INQUIRY,
    )

    private val KINDS = mapOf(
        "restaurant" to BusinessKind.RESTAURANT,
        "salon" to BusinessKind.SALON,
        "clinic" to BusinessKind.CLINIC,
        "other" to BusinessKind.OTHER,
    )

    private val WINDOW_FIELDS = setOf("date", "from", "to")

    private val BOOKING_ONLY_FIELDS = listOf(
        "windows",
        "party_size",
        "duration_minutes",
        "required_preferences",
        "optional_preferences",
    )

    private val REQUEST_FIELDS = setOf(
        "shop_name",
        "phone_number",
        "phone_number_source",
        "purpose",
        "kind",
        "windows",
        "party_size",
        "duration_minutes",
        "required_preferences",
        "optional_preferences",
        "notes_for_shop",
        "reservation_name",
        "callback_number",
        "call_at",
    )
}
