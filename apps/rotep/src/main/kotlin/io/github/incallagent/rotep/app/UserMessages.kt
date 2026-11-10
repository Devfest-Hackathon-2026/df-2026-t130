// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import android.Manifest
import io.github.incallagent.rotep.call.AudioPathEvent
import io.github.incallagent.rotep.call.LocalAudioPath

/**
 * What the app tells the user when something went wrong, in one place: what happened, then what
 * to do next, in one or two sentences.
 *
 * The texts never carry a phone number, the API key or what was said. What comes from outside
 * (the reason the Gemini server gave for closing, an error message) is only read to pick a text:
 * it is never shown, and the log only gets the short form ([liveLog]).
 */
object UserMessages {
    // --- the Gemini Live connection ---

    /** Why the Gemini Live connection ended. [code] is the short form for the log. */
    enum class LiveEnd(val code: String) {
        /** The prepayment balance ran out: close code 1011 with "Your prepayment credits are depleted". */
        BILLING("billing"),

        /** The API key was refused: "API key not valid", or HTTP 401/403 on the handshake. */
        BAD_KEY("bad-key"),

        /** No network: the name could not be looked up, no connection, a timeout, a dropped connection. */
        NETWORK("network"),

        /** An error on Gemini's side: 1011 without the billing text, 1013, HTTP 429 or 5xx. */
        SERVER("server"),

        /** The thinking model gave no answer (ADR-053). */
        BRAIN("brain"),

        /** Gemini closed the connection normally (1000, 1001). */
        CLOSED("closed"),
        OTHER("other"),
    }

    // LiveSession's detail: "closed code=1011 reason=<server's text>" (agent-live), or
    // "<exception class>: <message> http=403 body=<text>" (RotepLive's failures)
    private val CLOSE = Regex("^closed code=(\\d+) reason=")
    private val HTTP = Regex("\\bhttp=(\\d{3})\\b")
    private val EXCEPTION = Regex("^([A-Za-z_][A-Za-z0-9_$]*)(:|$)")
    // Phrases, not the bare word "billing": help URLs in other errors can contain it
    /** The reason the connector of the thinking model closes with (ADR-053). Lower case, as the text is compared. */
    private const val BRAIN_FAILED = "brain failed"

    private val BILLING_WORDS = listOf(
        "prepayment", "prepaid", "credits are depleted", "billing details", "billing account", "plan and billing",
    )
    private val KEY_WORDS = listOf("api key not valid", "api_key_invalid", "api key expired", "invalid api key", "api key is invalid")
    private val NETWORK_ERRORS = setOf(
        "UnknownHostException", "ConnectException", "NoRouteToHostException", "PortUnreachableException",
        "SocketTimeoutException", "InterruptedIOException", "SocketException", "EOFException",
        "SSLException", "SSLHandshakeException", "SSLProtocolException", "ConnectionShutdownException",
    )

    fun classifyLive(detail: String): LiveEnd {
        val text = detail.lowercase()
        val code = closeCode(detail)
        val http = httpStatus(detail)
        val exception = exceptionName(detail)
        return when {
            BRAIN_FAILED in text -> LiveEnd.BRAIN
            BILLING_WORDS.any { it in text } || http == 402 -> LiveEnd.BILLING
            KEY_WORDS.any { it in text } || http == 401 || http == 403 -> LiveEnd.BAD_KEY
            exception in NETWORK_ERRORS || code == 1006 -> LiveEnd.NETWORK
            code == 1011 || code == 1013 || http == 429 || (http != null && http >= 500) -> LiveEnd.SERVER
            code == 1000 || code == 1001 -> LiveEnd.CLOSED
            else -> LiveEnd.OTHER
        }
    }

    /** For the log: the close code or the exception's class, the HTTP status, and the kind. Never the reason or a message. */
    fun liveLog(detail: String): String = listOfNotNull(
        closeCode(detail)?.let { "code=$it" },
        exceptionName(detail),
        httpStatus(detail)?.let { "http=$it" },
        classifyLive(detail).code,
    ).joinToString(" ")

    /** The Gemini Live connection ended while the AI was needed. [connected]: it had been set up. */
    fun aiConnection(end: LiveEnd, connected: Boolean): String {
        val lost = if (connected) "AI との接続が切れました" else "AI に接続できませんでした"
        return when (end) {
            LiveEnd.BILLING -> "Gemini の前払いの残高が足りないかもしれません。AI Studio の Billing を見てください"
            LiveEnd.BAD_KEY -> "Gemini に API キーを受け付けてもらえず、$lost。設定で、有料枠のキーを貼り直してください"
            LiveEnd.NETWORK -> "ネットにつながらず、$lost。Wi-Fi かモバイルデータを確かめてから、試し直してください"
            LiveEnd.SERVER -> "Gemini 側のエラーで、$lost。少し待ってから、試し直してください"
            LiveEnd.BRAIN -> "考える役の Claude から答えをもらえなかったので、AI を止めました。Claude の API キーと残高を確かめて、少し待ってから試し直してください"
            LiveEnd.CLOSED, LiveEnd.OTHER -> "$lost。少し待ってから、試し直してください"
        }
    }

    /** The connection could not even be started (an exception from the connector). */
    fun aiConnectFailed(t: Throwable): String =
        aiConnection(classifyLive("${t.javaClass.simpleName}:"), connected = false)

    private fun closeCode(detail: String) = CLOSE.find(detail)?.groupValues?.get(1)?.toIntOrNull()

    private fun httpStatus(detail: String) =
        if (closeCode(detail) != null) null else HTTP.find(detail)?.groupValues?.get(1)?.toIntOrNull()

    private fun exceptionName(detail: String) =
        if (closeCode(detail) != null) null else EXCEPTION.find(detail)?.groupValues?.get(1)

    // --- the audio path ---

    const val HELPER_DIED = "手伝い役が止まりました。設定の「困ったとき」から診断の画面を開いて、起動し直してください"

    const val CALL_NEEDS_ANDROID_14 = "この端末は Android 13 以前なので、電話の音声を扱えません。電話を任せるには Android 14 以上が要ります。設定の「電話のしかた」を「練習」にすると、この端末でも試せます"

    const val NO_NETWORK_FOR_CALL = "インターネットにつながっていないので、電話をかけませんでした。Wi-Fi かモバイルデータを確かめてから、もう一度押してください"

    const val HELPER_NOT_RUNNING = "手伝い役が動いていません。設定の「困ったとき」から診断の画面を開いて起動してください"

    /** null for [AudioPathEvent.Started]. [local]: the phone's own microphone and speaker (the practice). */
    fun audioPath(e: AudioPathEvent, local: Boolean): String? = when (e) {
        is AudioPathEvent.Started -> null
        AudioPathEvent.HelperDied -> HELPER_DIED
        AudioPathEvent.Silenced ->
            "録音が止められました（ほかのアプリが音を使っているかもしれません）。ほかのアプリを閉じてから、試し直してください"
        is AudioPathEvent.Stopped ->
            if (local) {
                "端末のマイクとスピーカーが止まりました（${e.reason}）。試し直してください"
            } else {
                "通話の音声が止まりました（${e.reason}）。設定の「困ったとき」から診断の画面を開いて、手伝い役が動いているかを確かめてください"
            }
        is AudioPathEvent.Failed -> when {
            e.detail == LocalAudioPath.NO_MIC_PERMISSION -> missingPermissions(listOf(Manifest.permission.RECORD_AUDIO))
            local -> "端末のマイクとスピーカーを使えませんでした（${e.detail}）。ほかのアプリが音を使っていないかを確かめてから、試し直してください"
            else -> "通話の音声を使えません（${e.detail}）。設定の「困ったとき」から診断の画面を開いて、手伝い役が動いているかを確かめてください"
        }
    }

    /** The audio path could not be started (an exception from it). */
    const val AUDIO_START_FAILED = "通話の音声を始められませんでした。設定の「困ったとき」から診断の画面を開いて、手伝い役が動いているかを確かめてから、試し直してください"

    // --- permissions ---

    /** The name the phone's settings show for this app (app_name; StringsXmlTest keeps the two the same). */
    const val APP_NAME = "アイてる!?"

    /** The runtime permissions the call cannot do without ([permissions]: Manifest.permission names). */
    fun missingPermissions(permissions: List<String>): String {
        val mic = Manifest.permission.RECORD_AUDIO in permissions
        val phone = permissions.any { it == Manifest.permission.CALL_PHONE || it == Manifest.permission.READ_PHONE_STATE }
        val what = when {
            mic && phone -> "マイクと電話"
            mic -> "マイク"
            phone -> "電話"
            else -> permissions.joinToString("、") { it.substringAfterLast('.') }
        }
        return "${what}の許可がないので、始められません。端末の設定の「アプリ」で「$APP_NAME」を選び、「権限」で${what}を許可してください"
    }

    // --- the Gemini API key ---

    const val API_KEY_MISSING = "Gemini の API キーがありません。設定で、有料枠のキーを貼り付けてください"

    const val CLAUDE_KEY_MISSING = "Claude の API キーがありません。設定で、キーを貼り付けてください"

    const val API_KEY_NOT_SAVED =
        "API キーを保存できませんでした（端末の鍵の保管庫で暗号化できません）。もう一度貼り付けてください"

    /** The saved API key could not be read and was deleted. */
    fun apiKeyUnreadable(who: String = "") = "保存していた ${who}API キーを読めませんでした。設定で貼り直してください"

    /** The saved API key could not be read this time and is still there. */
    fun apiKeyNotReadableNow(who: String = "") =
        "保存している ${who}API キーを、いま読めませんでした。少し待ってから試してください。続くときは、設定で貼り直してください"
}
