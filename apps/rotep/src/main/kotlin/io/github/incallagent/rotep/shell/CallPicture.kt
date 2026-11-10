// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

/**
 * The calls on the phone, boiled down to one number for the app (manual control). The helper reads it from
 * `dumpsys telephony.registry`: the shell user may, the app may not, and the listener for the same
 * states is a system API that an ordinary build cannot compile against.
 *
 * That dump also holds phone numbers and network details. Only the three state lines of each phone
 * are looked at, nothing else leaves this object, and only the number goes to the app (ADR-021).
 * Plain Kotlin, so it is covered by unit tests on the PC.
 */
internal object CallPicture {
    /** The helper could not tell. */
    const val UNKNOWN = -1

    /** No call on the phone. */
    const val NONE = 0

    /** One call, being placed and not answered yet (dialing or alerting). */
    const val DIALING = 1

    /** One call, answered. */
    const val ACTIVE = 2

    /** One call, on its way out. */
    const val ENDING = 3

    /** A call on hold, or more than one call: the app does not operate any of them. */
    const val OTHER = 4

    /** No call except one that rings here. */
    const val RINGING_ONLY = 5

    // android.telephony.PreciseCallState, a system API: the values the dump prints
    private const val NOT_VALID = -1
    private const val IDLE = 0
    private const val STATE_ACTIVE = 1
    private const val STATE_DIALING = 3
    private const val STATE_ALERTING = 4
    private const val DISCONNECTED = 7
    private const val DISCONNECTING = 8

    /** What a slot holds when there is no call in it (DISCONNECTED: the call just went away). */
    private val QUIET = setOf(NOT_VALID, IDLE, DISCONNECTED)

    /** The per-phone part of the dump ends here (TelephonyRegistry.dump). */
    const val END_OF_STATES = "local logs:"

    private const val PHONE = "Phone Id="
    private const val RINGING_LINE = "mRingingCallState="
    private const val FOREGROUND_LINE = "mForegroundCallState="
    private const val BACKGROUND_LINE = "mBackgroundCallState="

    /** The states of one phone (one SIM). Numbers are PreciseCallState values. */
    data class Phone(val ringing: Int, val foreground: Int, val background: Int)

    /**
     * Every phone in the dump, in its order; empty when the dump does not look as expected, so that
     * the app is told "unknown" rather than a guess. [cutOff]: asked at the end of the output, whether
     * something stopped the dump early. A dump cut off before [END_OF_STATES] may have left out the
     * phone with the call; one that ended by itself is read also without that line.
     */
    fun phones(lines: Sequence<String>, cutOff: () -> Boolean = { false }): List<Phone> {
        val found = ArrayList<Phone>()
        var reachedEnd = false
        var open = false
        var ringing: Int? = null
        var foreground: Int? = null
        var background: Int? = null

        fun close(): Boolean {
            if (!open) return true
            found += Phone(ringing ?: return false, foreground ?: return false, background ?: return false)
            return true
        }
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith(PHONE) -> {
                    if (!close()) return emptyList()
                    open = true
                    ringing = null
                    foreground = null
                    background = null
                }
                line == END_OF_STATES -> {
                    reachedEnd = true
                    break
                }
                !open -> {}
                line.startsWith(RINGING_LINE) -> ringing = line.substringAfter('=').toIntOrNull() ?: return emptyList()
                line.startsWith(FOREGROUND_LINE) -> foreground = line.substringAfter('=').toIntOrNull() ?: return emptyList()
                line.startsWith(BACKGROUND_LINE) -> background = line.substringAfter('=').toIntOrNull() ?: return emptyList()
            }
        }
        if (!reachedEnd && cutOff()) return emptyList()
        return if (close()) found else emptyList()
    }

    /** The one number for the app. A call waiting that only rings does not change the placed call's picture. */
    fun of(phones: List<Phone>): Int {
        if (phones.isEmpty()) return UNKNOWN
        var calls = 0
        var picture = NONE
        var rings = false
        for (p in phones) {
            if (p.background !in QUIET) return OTHER
            when (p.foreground) {
                in QUIET -> {}
                STATE_DIALING, STATE_ALERTING -> {
                    calls++
                    picture = DIALING
                }
                STATE_ACTIVE -> {
                    calls++
                    picture = ACTIVE
                }
                DISCONNECTING -> {
                    calls++
                    picture = ENDING
                }
                else -> return OTHER
            }
            if (p.ringing !in QUIET && p.ringing != DISCONNECTING) rings = true
        }
        return when {
            calls > 1 -> OTHER
            calls == 1 -> picture
            rings -> RINGING_ONLY
            else -> NONE
        }
    }

    /**
     * Whether "the one ongoing call" Telecom would end is the only call: exactly one call, being
     * placed or answered, and nothing ringing or on hold. TelecomManager.endCall ends the foreground
     * call, whichever it is, so the helper asks this right before it.
     */
    fun mayEndCall(phones: List<Phone>): Boolean {
        val picture = of(phones)
        if (picture != DIALING && picture != ACTIVE) return false
        return phones.all { it.ringing in QUIET || it.ringing == DISCONNECTING }
    }
}
