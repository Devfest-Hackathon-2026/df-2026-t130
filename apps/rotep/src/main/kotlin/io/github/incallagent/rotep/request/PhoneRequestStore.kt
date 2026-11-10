// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.request

import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.ui.RequestForm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

/**
 * The requests from other AIs and what became of them. One at a time: a second one while another
 * waits, is scheduled or is being called is refused, and so is a burst of them, so that nothing can
 * keep interrupting the user. Called from binder threads, the relay's threads and the main thread.
 *
 * Here the content (number, names, the result) only stays in memory; what the user may read again
 * of a real call is the app's own, in LastResultStore. [Ledger] keeps the last request's
 * id and status on disk, so that a process that died can still say whether a call may have been
 * made, and a request is not sent again for a shop that may already have been booked.
 *
 * [listener] hears about every change of a request that came through the server, so that the
 * server can be told.
 */
class PhoneRequestStore(
    private val ledger: Ledger,
    private val nowMs: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    enum class Status(val wire: String) {
        WAITING("waiting_for_approval"),

        /** The user approved it for a later time (ADR-040, ADR-042). */
        SCHEDULED("scheduled"),
        CALLING("calling"),
        FINISHED("finished"),
        DECLINED("declined"),
        CANCELLED("cancelled"),
        EXPIRED("expired"),

        /** The time came and the call could not be placed. */
        NOT_PLACED("not_placed"),

        /** A call was started, and this process no longer knows how it ended. */
        UNKNOWN("unknown"),
    }

    interface Ledger {
        /** False if it could not be written. */
        fun save(id: String, status: Status): Boolean

        fun load(): Pair<String, Status>?

        /** What was last written about [id], if it is one of the last few. */
        fun find(id: String): Status? = load()?.takeIf { it.first == id }?.second
    }

    /** What the frame shows about the request that waits. [opened]: the user is looking at it. [callAt]: the time the sender would like. */
    data class Incoming(
        val id: String,
        val shopName: String,
        val purpose: Purpose,
        val numberSource: String?,
        val opened: Boolean,
        val callAt: Instant? = null,
        val assistant: String? = null,
    )

    data class Seen(val status: Status, val report: JsonObject?, val scheduledFor: Instant? = null, val reason: String? = null)

    sealed interface Added {
        data class Ok(val id: String) : Added
        data class Busy(val id: String) : Added
        data object TooMany : Added

        /** The server sent this id again, and it was already dealt with before this process started. */
        data class AlreadyHandled(val status: Status) : Added
    }

    enum class Withdrawn { DONE, TOO_LATE, NOT_FOUND }

    /** What the alarm of a scheduled call has to know about the request behind it. */
    sealed interface ScheduledStart {
        /** The user's own scheduled call: no other AI waits for its result. */
        data object NoRequest : ScheduledStart

        /** [changedOnPhone] null: this process no longer knows it. */
        data class Request(val id: String, val changedOnPhone: Boolean?) : ScheduledStart

        /** It could not be written down, so it must not be dialed. */
        data object NotWritten : ScheduledStart
    }

    private class Entry(
        val id: String,
        val form: RequestForm,
        val numberSource: String?,
        val createdAt: Long,
        val callAt: Instant? = null,
        /** Came through the server: its changes are reported there. */
        val relayed: Boolean = false,
        val assistant: String? = null,
    ) {
        var status = Status.WAITING
        var opened = false
        var report: JsonObject? = null
        var endedAt: Long? = null
        var scheduledFor: Instant? = null
        var changedOnPhone: Boolean? = null
        var reason: String? = null
    }

    @Volatile
    var listener: ((String) -> Unit)? = null

    private val entries = LinkedHashMap<String, Entry>()
    private val createdTimes = ArrayDeque<Long>()
    private var latestId: String? = null

    private val _incoming = MutableStateFlow<Incoming?>(null)
    val incoming: StateFlow<Incoming?> = _incoming

    private val reported = HashMap<String, Status>()

    private inline fun <T> tidied(block: () -> T): T {
        var changed: List<String>
        val result = synchronized(this) {
            sweep()
            val value = block()
            _incoming.value = entries.values.firstOrNull { it.status == Status.WAITING }
                ?.let { Incoming(it.id, it.form.businessName, it.form.purpose, it.numberSource, it.opened, it.callAt, it.assistant) }
            changed = entries.values.filter { it.relayed && reported[it.id] != it.status }.map { it.id }
            for (id in changed) reported[id] = entries.getValue(id).status
            reported.keys.retainAll(entries.keys)
            value
        }
        // Outside the lock: the listener hands it on to another thread
        listener?.let { hear -> changed.forEach(hear) }
        return result
    }

    /**
     * What this process knows of a request the server sent, or null if it never got it. The server
     * sends a request again when it did not hear back: the same id is the same request.
     */
    fun delivered(id: String): Status? = tidied { entries[id]?.status }

    /** [requestId]: the id the server gave it, kept as it is so that both sides mean the same request. */
    fun add(form: RequestForm, numberSource: String?, callAt: Instant? = null, requestId: String? = null, assistant: String? = null): Added = tidied {
        requestId?.let { entries[it] }?.let { return@tidied Added.AlreadyHandled(it.status) }
        entries.values.firstOrNull { it.status == Status.WAITING || it.status == Status.SCHEDULED || it.status == Status.CALLING }?.let {
            return@tidied Added.Busy(it.id)
        }
        // A request delivered again after this phone's process changed, and that was approved or
        // otherwise dealt with before: it must not come back as a new request to approve, whatever came after it
        requestId?.let(::written)?.takeIf { it !in DELIVERABLE_AGAIN }?.let {
            return@tidied Added.AlreadyHandled(afterProcessDeath(it))
        }
        val saved = saved()
        // Waiting for its time since before this process started: still one at a time
        if (saved?.status == Status.SCHEDULED) return@tidied Added.Busy(saved.id)
        val now = nowMs()
        while (createdTimes.isNotEmpty() && now - createdTimes.first() >= RATE_WINDOW_MS) createdTimes.removeFirst()
        if (createdTimes.size >= MAX_PER_WINDOW) return@tidied Added.TooMany
        createdTimes.addLast(now)
        val entry = Entry(requestId ?: newId(), form, numberSource, now, callAt, relayed = requestId != null, assistant = assistant)
        entries[entry.id] = entry
        latestId = entry.id
        ledger.save(ledgerKey(entry), Status.WAITING)
        Added.Ok(entry.id)
    }

    /** null: nothing is known about [id] any more. */
    fun get(id: String): Seen? = tidied {
        entries[id]?.let { return@tidied Seen(it.status, it.report, it.scheduledFor, it.reason) }
        written(id)?.let { Seen(afterProcessDeath(it), null) }
    }

    /** The id of the latest request, or null. */
    fun latest(): String? = synchronized(this) { latestId ?: saved()?.id }

    /**
     * The request whose end a process that died (or a restart) could not tell the server, if any.
     * Only one that came through the server: a request over adb is nobody's business there.
     */
    fun leftoverForServer(): String? = synchronized(this) {
        saved()?.takeIf { it.relayed && it.id !in entries && afterProcessDeath(it.status) in TOLD_AFTER_PROCESS_DEATH }?.id
    }

    /** The form to show, or null if [id] no longer waits. */
    fun open(id: String): RequestForm? = tidied {
        waiting(id)?.let {
            it.opened = true
            it.form
        }
    }

    /** Whether the user can still approve [id]. */
    fun isWaiting(id: String): Boolean = tidied { waiting(id) != null }

    /** Looks at the clock now: nothing else notices that a request expired. */
    fun refresh() = tidied { }

    /** In a new process: a request that waited when the last one died is gone. True if there was one. */
    fun expireLeftover(): Boolean = tidied {
        val saved = saved() ?: return@tidied false
        if (saved.status != Status.WAITING || saved.id in entries) return@tidied false
        ledger.save(saved.key, Status.EXPIRED)
        true
    }

    /** "断る". */
    fun decline(id: String) = tidied {
        waiting(id)?.let { move(it, Status.DECLINED) }
        Unit
    }

    /** The sender takes it back: only before the call starts. */
    fun withdraw(id: String): Withdrawn = tidied {
        val entry = entries[id] ?: return@tidied Withdrawn.NOT_FOUND
        if (entry.status != Status.WAITING) return@tidied Withdrawn.TOO_LATE
        move(entry, Status.CANCELLED)
        Withdrawn.DONE
    }

    /** The user approved. False if the request no longer waits, or the call could not be written down before dialing. */
    fun calling(id: String): Boolean = tidied {
        val entry = waiting(id) ?: return@tidied false
        entry.status = Status.CALLING
        entry.reason = null
        val written = record(entry)
        if (!written) entry.status = Status.WAITING
        written
    }

    /** The user approved [id] for a later time. False if it no longer waits, or that could not be written down. */
    fun scheduled(id: String, fireAt: Instant, changedOnPhone: Boolean): Boolean = tidied {
        val entry = waiting(id) ?: return@tidied false
        entry.status = Status.SCHEDULED
        if (!record(entry)) {
            entry.status = Status.WAITING
            return@tidied false
        }
        entry.scheduledFor = fireAt
        entry.changedOnPhone = changedOnPhone
        true
    }

    /** It could not be scheduled after all: the user can still approve it. */
    fun notScheduled(id: String): Unit = tidied {
        val entry = entries[id]?.takeIf { it.status == Status.SCHEDULED } ?: return@tidied
        entry.status = Status.WAITING
        entry.scheduledFor = null
        entry.changedOnPhone = null
        record(entry)
    }

    /**
     * The alarm came: the request behind the call (if any) goes on to be called. [policyId] is the
     * saved call's policy: it holds the request's id when a request is behind the call, so that a
     * leftover in the ledger can never be taken for the user's own call.
     */
    fun scheduledCallStarts(policyId: String): ScheduledStart = tidied {
        val entry = scheduledEntry(policyId) ?: return@tidied ScheduledStart.NoRequest
        entry.status = Status.CALLING
        if (!record(entry)) {
            entry.status = Status.SCHEDULED
            return@tidied ScheduledStart.NotWritten
        }
        ScheduledStart.Request(entry.id, entry.changedOnPhone)
    }

    /** The call could not get ready (ADR-049): [id] still waits for the user, who can try again, and the sender can read why. */
    fun couldNotPrepare(id: String, reason: String): Unit = tidied {
        val entry = waiting(id) ?: return@tidied
        entry.reason = reason
        // The status stays and only the words change: the server's copy has to hear of that as well
        reported.remove(id)
    }

    /** The call of [id] had started to be placed and was not after all (the audio could not be set up, no dial happened). */
    fun callNotPlaced(id: String, reason: String): Unit = tidied {
        val entry = entries[id]?.takeIf { it.status == Status.CALLING } ?: return@tidied
        entry.reason = reason
        move(entry, Status.NOT_PLACED)
    }

    /** The scheduled call was given up: the user took it back ([Status.CANCELLED]), or it could not be placed ([Status.NOT_PLACED]). */
    fun scheduledEnded(status: Status, reason: String?): Unit = tidied {
        val entry = scheduledEntry() ?: return@tidied
        entry.reason = reason
        move(entry, status)
    }

    /** The approved call did not start after all: the user can still approve it. */
    fun notStarted(id: String): Unit = tidied {
        val entry = entries[id]?.takeIf { it.status == Status.CALLING } ?: return@tidied
        entry.status = Status.WAITING
        record(entry)
    }

    fun finished(id: String, report: JsonObject) = tidied {
        val entry = entries[id]?.takeIf { it.status == Status.CALLING } ?: return@tidied
        entry.report = report
        move(entry, Status.FINISHED)
    }

    private fun waiting(id: String) = entries[id]?.takeIf { it.status == Status.WAITING }

    /**
     * The one request that waits for its time. Its entry may be gone with an earlier process; the
     * ledger still has its id, and the alarm comes in a new process, so it is made anew here.
     */
    private fun scheduledEntry(id: String? = null): Entry? {
        entries.values.firstOrNull { it.status == Status.SCHEDULED && (id == null || it.id == id) }?.let { return it }
        val saved = saved() ?: return null
        if (saved.status != Status.SCHEDULED || saved.id in entries || (id != null && saved.id != id)) return null
        return Entry(saved.id, RequestForm(), null, nowMs(), relayed = saved.relayed).also {
            it.status = Status.SCHEDULED
            entries[saved.id] = it
            latestId = saved.id
        }
    }

    private fun move(entry: Entry, status: Status) {
        entry.status = status
        entry.endedAt = nowMs()
        record(entry)
    }

    private fun record(entry: Entry): Boolean = entry.id != latestId || ledger.save(ledgerKey(entry), entry.status)

    /** The ledger holds one name and one status; a mark on the name says that the request came through the server. */
    private fun ledgerKey(entry: Entry) = if (entry.relayed) RELAYED_MARK + entry.id else entry.id

    private class Saved(val key: String, val status: Status) {
        val id: String get() = key.removePrefix(RELAYED_MARK)
        val relayed: Boolean get() = key.startsWith(RELAYED_MARK)
    }

    private fun saved(): Saved? = ledger.load()?.let { (key, status) -> Saved(key, status) }

    /** What the ledger last says about [id], with or without the mark. */
    private fun written(id: String): Status? = ledger.find(RELAYED_MARK + id) ?: ledger.find(id)

    /** Expired requests go; ended ones are kept for a while, so that the sender can still read them. */
    private fun sweep() {
        val now = nowMs()
        entries.values.filter { it.status == Status.WAITING && now - it.createdAt >= WAITING_MS }.forEach { move(it, Status.EXPIRED) }
        entries.values.removeAll { e -> e.endedAt?.let { now - it >= KEPT_MS } == true }
        val ended = entries.values.filter { it.endedAt != null }
        ended.take((ended.size - MAX_KEPT).coerceAtLeast(0)).forEach { entries.remove(it.id) }
    }

    private fun afterProcessDeath(status: Status): Status = when (status) {
        Status.CALLING, Status.FINISHED, Status.UNKNOWN -> Status.UNKNOWN
        Status.WAITING, Status.EXPIRED -> Status.EXPIRED
        Status.DECLINED, Status.CANCELLED, Status.NOT_PLACED -> status
        // The saved call and its alarm may still be there: only the time will tell
        Status.SCHEDULED -> Status.SCHEDULED
    }

    companion object {
        const val WAITING_MS = 30 * 60_000L
        const val KEPT_MS = 60 * 60_000L
        const val MAX_KEPT = 5
        const val RATE_WINDOW_MS = 60 * 60_000L
        const val MAX_PER_WINDOW = 10

        /** What a request delivered again may have been before this process started: nothing was decided about it. */
        private val DELIVERABLE_AGAIN = setOf(Status.WAITING, Status.EXPIRED)

        /** What the server may not have heard. A request that only waited is sent again by the server; one that waits for its time is not over. */
        private val TOLD_AFTER_PROCESS_DEATH = setOf(Status.UNKNOWN, Status.DECLINED, Status.CANCELLED, Status.NOT_PLACED)

        private const val RELAYED_MARK = "relay:"
    }
}

/** The last few requests, as the text the ledger keeps: one id and status to a line, newest first. */
internal object RecentRequests {
    private const val KEPT = 10
    private const val SEPARATOR = '\t'

    fun added(saved: String?, id: String, status: PhoneRequestStore.Status): String =
        (listOf("$id$SEPARATOR${status.name}") + lines(saved).filterNot { it.substringBefore(SEPARATOR) == id })
            .take(KEPT)
            .joinToString("\n")

    fun find(saved: String?, id: String): PhoneRequestStore.Status? =
        lines(saved).firstOrNull { it.substringBefore(SEPARATOR) == id }
            ?.substringAfter(SEPARATOR)
            ?.let { name -> PhoneRequestStore.Status.entries.find { it.name == name } }

    private fun lines(saved: String?) = saved?.lines().orEmpty().filter { it.isNotEmpty() }
}

/**
 * What a restart took away: a call that waited for its time is over, and nothing is left to tell the
 * rest of the store. Keeps the ledger true without starting the app's state (the boot path).
 */
fun PhoneRequestStore.Ledger.scheduledCallLost() {
    val (id, status) = load() ?: return
    if (status == PhoneRequestStore.Status.SCHEDULED) save(id, PhoneRequestStore.Status.NOT_PLACED)
}
