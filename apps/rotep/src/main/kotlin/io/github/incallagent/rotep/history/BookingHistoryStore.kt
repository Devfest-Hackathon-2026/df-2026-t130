// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.history

import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.core.policy.PhoneNumbers
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.app.ApiKeyStore
import io.github.incallagent.rotep.app.SecretCipher
import io.github.incallagent.rotep.call.CallSession
import io.github.incallagent.rotep.ui.headlineText
import io.github.incallagent.rotep.ui.resultHeadline
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * One real call kept by 「電話の記録」 (ADR-045, ADR-050). It is for the user to read: never for the AI's
 * instructions on a later call (ADR-024), and never for a log, so [toString] says nothing of it.
 */
data class Entry(
    val id: String,
    /** When the call ended: the 90 days count from here. */
    val at: Instant,
    val shopName: String,
    val phoneNumber: String,
    /** The number's [NumberHmac], which finds this entry by number. null: it could not be made. */
    val numberKey: String? = null,
    val purpose: Purpose,
    val bookedAt: LocalDateTime? = null,
    val partySize: Int? = null,
    val reservationName: String? = null,
    /** What the result screen said of this call, in its words. */
    val headline: String,
    val summary: String,
    /** What the shop and the AI said, as text. */
    val transcript: List<CallSession.Line> = emptyList(),
) {
    override fun toString() = "Entry(<hidden>)"
}

/** Makes the key a phone number is looked up by: [KeystoreNumberHmac] in the app, a fake in tests. */
interface NumberHmac {
    /** Throws when it cannot. */
    fun of(normalizedNumber: String): String

    /** Deletes the key; the next [of] makes a new one. */
    fun forget()
}

/**
 * [inner], trying a decryption once more before it counts as failed. ApiKeyStore deletes what it
 * cannot read, and below API 33 a failure cannot say that it will pass. An API key can be pasted
 * again; 50 kept calls cannot.
 */
class RetryingCipher(
    private val inner: SecretCipher,
    private val pause: () -> Unit = { Thread.sleep(RETRY_PAUSE_MS) },
) : SecretCipher by inner {
    override fun decrypt(data: ByteArray): ByteArray = try {
        inner.decrypt(data)
    } catch (_: Exception) {
        pause()
        inner.decrypt(data)
    }

    private companion object {
        const val RETRY_PAUSE_MS = 500L
    }
}

/** An [Entry] from a call that ended. */
object BookingEntries {
    /** Far above a real call (the longest one allowed is minutes): this only bounds the size of the file. */
    const val MAX_TRANSCRIPT_CHARS = 20_000
    const val MAX_SUMMARY_CHARS = 2_000

    fun of(id: String, policy: DelegationPolicy, result: CallSession.Result, lines: List<CallSession.Line>): Entry {
        val booking = result.outcome.booking
        return Entry(
            id = id,
            at = result.outcome.endedAt,
            shopName = policy.target.businessName,
            phoneNumber = policy.target.phoneNumber,
            purpose = policy.purpose,
            bookedAt = booking?.start,
            partySize = booking?.partySize,
            reservationName = booking?.nameUsed,
            // Not the status alone: a booking that failed the check must not read as booked
            headline = headlineText(resultHeadline(result)),
            summary = result.outcome.summary.take(MAX_SUMMARY_CHARS),
            transcript = spoken(lines),
        )
    }

    /** What was said, from the start of the call: no tool calls, no notes of the app. */
    private fun spoken(lines: List<CallSession.Line>): List<CallSession.Line> {
        var room = MAX_TRANSCRIPT_CHARS
        return lines
            .filter { it.who == CallSession.Who.SHOP || it.who == CallSession.Who.AI }
            .takeWhile { room > 0 }
            .map { line ->
                val text = line.text.take(room)
                room -= text.length
                line.copy(text = text)
            }
    }
}

/** The form of the file's contents, without any file. By hand: this module has no serialization plugin. */
object HistoryJson {
    private const val VERSION = 1

    fun encode(entries: List<Entry>): String = buildJsonObject {
        put("version", VERSION)
        put("entries", buildJsonArray { entries.forEach { add(entryJson(it)) } })
    }.toString()

    sealed interface Decoded {
        /** [skipped]: entries that could not be understood and were left out. The others do not suffer for them. */
        class Entries(val entries: List<Entry>, val skipped: Int) : Decoded

        /** Another version of the app wrote it: not damaged. */
        data object OtherVersion : Decoded

        data object Damaged : Decoded
    }

    fun decode(text: String): Decoded {
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return Decoded.Damaged
        val version = runCatching { o.getValue("version").jsonPrimitive.int }.getOrNull() ?: return Decoded.Damaged
        if (version != VERSION) return Decoded.OtherVersion
        val items = runCatching { o.getValue("entries").jsonArray }.getOrNull() ?: return Decoded.Damaged
        val entries = items.mapNotNull { item -> runCatching { entryOf(item.jsonObject) }.getOrNull() }
        return Decoded.Entries(entries, items.size - entries.size)
    }

    private fun entryJson(e: Entry): JsonElement = buildJsonObject {
        put("id", e.id)
        put("at", e.at.toString())
        put("shopName", e.shopName)
        put("phoneNumber", e.phoneNumber)
        e.numberKey?.let { put("numberKey", it) }
        put("purpose", e.purpose.name)
        e.bookedAt?.let { put("bookedAt", it.toString()) }
        e.partySize?.let { put("partySize", it) }
        e.reservationName?.let { put("reservationName", it) }
        put("headline", e.headline)
        put("summary", e.summary)
        put(
            "transcript",
            buildJsonArray {
                for (line in e.transcript) {
                    add(
                        buildJsonObject {
                            put("who", line.who.name)
                            put("text", line.text)
                        },
                    )
                }
            },
        )
    }

    private fun entryOf(o: JsonObject) = Entry(
        id = o.text("id"),
        at = Instant.parse(o.text("at")),
        shopName = o.text("shopName"),
        phoneNumber = o.text("phoneNumber"),
        numberKey = o.optional("numberKey"),
        purpose = Purpose.valueOf(o.text("purpose")),
        bookedAt = o.optional("bookedAt")?.let(LocalDateTime::parse),
        partySize = o["partySize"]?.jsonPrimitive?.int,
        reservationName = o.optional("reservationName"),
        headline = o.text("headline"),
        summary = o.text("summary"),
        transcript = o.getValue("transcript").jsonArray.map {
            val line = it.jsonObject
            CallSession.Line(CallSession.Who.valueOf(line.text("who")), line.text("text"))
        },
    )

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.optional(name: String): String? = get(name)?.jsonPrimitive?.content
}

/**
 * The kept calls, newest first, encrypted with [cipher] in one file under [dir] (noBackupFilesDir in
 * the app: app-private, never part of a backup). 90 days and 50 entries at most: what is over goes
 * at every read and every add. When nothing is left, the file and the keys go too.
 *
 * It is only a convenience, so nothing here throws or stops a call. Reads and decrypts a file: not
 * for the main thread. The log only gets what happened and the exception's class (ADR-021).
 */
class BookingHistoryStore(
    dir: File,
    cipher: SecretCipher,
    private val hmac: NumberHmac,
    private val now: () -> Instant = Instant::now,
    private val log: (String) -> Unit = {},
) {
    sealed interface Read {
        /** [lost]: what was kept could not be read and was deleted. [partly]: some entries could not be read and are left out. */
        class Found(val entries: List<Entry>, val lost: Boolean = false, val partly: Boolean = false) : Read {
            override fun toString() = "Found(<${entries.size} entries>, lost=$lost, partly=$partly)"
        }

        /** It cannot be read just now and was kept: the next read tries again. */
        data object NotNow : Read
    }

    private val secrets = ApiKeyStore(dir, cipher, log, fileName = FILE, plainFileName = NO_PLAIN_FILE)
    private val file = File(dir, FILE)

    /** Not in the file yet (the key store was busy, or the disk full). Every read tries again; lost when the app ends. */
    private val unsaved = ArrayList<Entry>()

    /** Without decrypting anything. */
    val exists: Boolean
        @Synchronized get() = file.exists() || unsaved.isNotEmpty()

    @Synchronized
    fun read(): Read {
        var lost = false
        var partly = false
        val stored: List<Entry> = when (val r = secrets.read()) {
            is ApiKeyStore.Read.Key -> when (val decoded = HistoryJson.decode(r.value)) {
                is HistoryJson.Decoded.Entries -> {
                    partly = decoded.skipped > 0
                    if (partly) log("booking history: ${decoded.skipped} entries not understood, left out")
                    decoded.entries
                }
                HistoryJson.Decoded.OtherVersion -> {
                    log("booking history: written by another version, kept")
                    return Read.NotNow
                }
                HistoryJson.Decoded.Damaged -> {
                    log("booking history: not in a known form, deleted")
                    secrets.clear()
                    lost = true
                    emptyList()
                }
            }
            ApiKeyStore.Read.None -> emptyList()
            is ApiKeyStore.Read.Unreadable -> {
                lost = true
                emptyList()
            }
            is ApiKeyStore.Read.NotNow -> return Read.NotNow
        }
        return Read.Found(settled(stored), lost, partly)
    }

    /** What was kept before stays. When [entry] cannot be written now, the next [read] writes it, while the app runs. */
    @Synchronized
    fun add(entry: Entry): Read {
        unsaved += entry.copy(numberKey = keyOf(entry.phoneNumber))
        if (unsaved.size > MAX_ENTRIES) unsaved.removeAt(0)
        return read()
    }

    /** false when it could not be deleted now: it is still there. */
    @Synchronized
    fun delete(id: String): Boolean {
        // What was never written is not in the file either
        if (unsaved.removeAll { it.id == id }) return true
        val entries = (read() as? Read.Found)?.entries ?: return false
        val left = entries.filterNot { it.id == id }
        if (left.size == entries.size) return true
        if (left.isEmpty()) {
            wipe()
            return true
        }
        return secrets.save(HistoryJson.encode(left))
    }

    /** Works also while the file cannot be read: the file and its key are simply deleted. */
    @Synchronized
    fun deleteAll() = wipe()

    /** The newest kept call to [number], however it is written, or null. */
    @Synchronized
    fun findByNumber(number: String): Entry? {
        if (!exists) return null
        val key = keyOf(number) ?: return null
        return (read() as? Read.Found)?.entries?.firstOrNull { it.numberKey == key }
    }

    /** The newest kept call to the shop called [shopName], or null. */
    @Synchronized
    fun latestForShop(shopName: String): Entry? {
        if (!exists) return null
        return (read() as? Read.Found)?.entries?.firstOrNull { it.shopName == shopName }
    }

    /** What stays, written to the file if that is not what the file holds. */
    private fun settled(stored: List<Entry>): List<Entry> {
        val kept = retained(stored + unsaved)
        when {
            kept == stored -> unsaved.clear()
            kept.isEmpty() -> wipe()
            secrets.save(HistoryJson.encode(kept)) -> unsaved.clear()
        }
        return kept
    }

    private fun retained(entries: List<Entry>): List<Entry> {
        val oldest = now().minus(KEEP_DAYS, ChronoUnit.DAYS)
        return entries
            .filterNot { it.at.isBefore(oldest) }
            .distinctBy { it.id }
            .sortedByDescending { it.at }
            .take(MAX_ENTRIES)
    }

    private fun wipe() {
        unsaved.clear()
        secrets.clear()
        try {
            hmac.forget()
        } catch (t: Throwable) {
            log("booking history: number key not deleted (${t.javaClass.simpleName})")
        }
    }

    private fun keyOf(number: String): String? {
        val normalized = PhoneNumbers.normalizeJp(number) ?: return null
        return try {
            hmac.of(normalized)
        } catch (t: Throwable) {
            log("booking history: number key not made (${t.javaClass.simpleName})")
            null
        }
    }

    companion object {
        const val FILE = "booking_history.enc"
        const val KEEP_DAYS = 90L
        const val MAX_ENTRIES = 50

        /** Never written: without its own name, ApiKeyStore would look at the plaintext file of the Gemini key. */
        private const val NO_PLAIN_FILE = "booking_history.plain"
    }
}
