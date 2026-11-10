// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.callback

import io.github.incallagent.rotep.history.Entry

/** The one place to cut the store-callback notice (ADR-047). */
internal object CallbackFeature {
    /**
     * false: the switch is not on the settings screen, and the screening service only lets the call
     * through. The service stays in the manifest: no call reaches it unless the role was given.
     */
    const val ENABLED: Boolean = true
}

/**
 * An earlier call found for an incoming number: what the notice names. [dateText]: the day of the
 * booking as it is read out ("11月12日"), null when that call made none.
 */
data class CallbackMatch(val shopName: String, val dateText: String?)

/**
 * The way from an incoming number to an earlier call. The number comes as the phone gives it (with or
 * without the country code): making it comparable is the job of whoever keeps the calls. Called off
 * the main thread.
 */
fun interface CallbackLookup {
    fun findByNumber(number: String): CallbackMatch?
}

/** What a notice may name of a kept call: the shop and the day. Nothing of what was said (ADR-045). */
internal fun callbackMatchOf(entry: Entry): CallbackMatch =
    CallbackMatch(entry.shopName, entry.bookedAt?.let { "${it.monthValue}月${it.dayOfMonth}日" })

/** What the screening needs of a call. [number]: null when the caller withheld it. */
internal data class ScreenedCall(val incoming: Boolean, val number: String?)

/** The words of the notice. Only the phone's unlocked screen may show [text] (see CallbackNotices). */
internal data class CallbackNotice(val title: String, val text: String)

internal fun callbackNotice(match: CallbackMatch): CallbackNotice {
    val shop = match.shopName.trim().ifEmpty { "お店" }
    val about = match.dateText?.trim()?.takeIf { it.isNotEmpty() }?.let { "（${it}の予約の件）" }.orEmpty()
    return CallbackNotice("お店からの折り返しかもしれません", "前に電話した${shop}からの着信かもしれません$about")
}

/**
 * What happens when the phone shows this app a call. The call comes first: it is let through before
 * anything else is read, always with the answer that changes nothing, and everything after that runs
 * off the calling thread, so that neither a failure nor a slow lookup can hold up or change the ring.
 * Apart from Android so that a unit test sees it.
 */
internal class CallbackScreener(
    private val settingOn: () -> Boolean,
    private val callRunning: () -> Boolean,
    private val lookup: CallbackLookup,
    /** Shows the notice; the Boolean: no pop-up, a call of the AI is going on. */
    private val show: (CallbackNotice, Boolean) -> Unit,
    private val inBackground: (() -> Unit) -> Unit,
    private val log: (String) -> Unit = {},
    private val enabled: Boolean = CallbackFeature.ENABLED,
) {
    fun screen(readCall: () -> ScreenedCall, letThrough: () -> Unit) {
        guarded("let through", letThrough)
        if (!enabled) return
        guarded("start") {
            val call = readCall()
            log("call: incoming=${call.incoming} number=${!call.number.isNullOrBlank()}")
            val number = call.number?.takeIf { call.incoming && it.isNotBlank() } ?: return
            inBackground { guarded("check") { noticeIfKnown(number) } }
        }
    }

    private fun noticeIfKnown(number: String) {
        if (!settingOn()) return
        val match = lookup.findByNumber(number)
        log("found=${match != null}")
        if (match != null) show(callbackNotice(match), callRunning())
    }

    /** A failure stops nothing else, and is logged by its kind only: its message could hold the number. */
    private inline fun guarded(step: String, job: () -> Unit) {
        try {
            job()
        } catch (t: Throwable) {
            log("$step failed: ${t.javaClass.simpleName}")
        }
    }
}

/** What turning the switch means. */
internal enum class CallbackSwitchStep { TURN_OFF, TURN_ON, ASK_FOR_ROLE, NOT_ON_THIS_PHONE }

internal fun callbackSwitchStep(turnOn: Boolean, roleAvailable: Boolean, roleHeld: Boolean): CallbackSwitchStep = when {
    !turnOn -> CallbackSwitchStep.TURN_OFF
    roleHeld -> CallbackSwitchStep.TURN_ON
    roleAvailable -> CallbackSwitchStep.ASK_FOR_ROLE
    else -> CallbackSwitchStep.NOT_ON_THIS_PHONE
}

internal const val CALLBACK_NOT_ON_THIS_PHONE = "この端末では、着信の番号をアプリに見せる設定が使えないため、知らせられません"

internal const val CALLBACK_ROLE_REFUSED = "許可されなかったので、オフのままです。使うときは、もう一度オンにするか、端末の設定の「デフォルトのアプリ」で、このアプリを発信者番号と迷惑電話のアプリに選んでください"

internal const val CALLBACK_ROLE_MISSING = "このアプリが発信者番号と迷惑電話のアプリから外れているので、知らせられません。切ってからもう一度オンにしてください"

internal const val CALLBACK_NO_NOTIFICATIONS = "このアプリの通知が許可されていないので、知らせられません。端末の設定で通知を許可してください"

/** Why a switch that is on cannot do its work, or null. */
internal fun callbackProblem(on: Boolean, roleHeld: Boolean, canNotify: Boolean): String? = when {
    !on -> null
    !roleHeld -> CALLBACK_ROLE_MISSING
    !canNotify -> CALLBACK_NO_NOTIFICATIONS
    else -> null
}
