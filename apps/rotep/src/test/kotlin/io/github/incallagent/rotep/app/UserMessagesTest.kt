// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import android.Manifest
import io.github.incallagent.rotep.app.UserMessages.LiveEnd
import io.github.incallagent.rotep.call.AudioPathEvent
import io.github.incallagent.rotep.call.LocalAudioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class UserMessagesTest {
    // Fictitious; must never come out in a message or a log line
    private val apiKey = "AIzaSyFAKE-KEY-0000"
    private val number = "0300000000"
    private val said = "こちらは山田です"

    /** As the server wrote it when the prepayment ran out (close code 1011). */
    private val billingReason =
        "Your prepayment credits are depleted. Please go to AI Studio at https://ai.studio/projects to manage your project and billing."

    // LiveSession's detail for a close, and RotepLive's for a failure
    private fun closed(code: Int, reason: String) = "closed code=$code reason=$reason"

    @Test
    fun aThinkingModelThatGaveNoAnswerIsNotCalledGeminis() {
        assertEquals(LiveEnd.BRAIN, UserMessages.classifyLive(closed(1011, "brain failed")))
        val text = UserMessages.aiConnection(LiveEnd.BRAIN, connected = true)
        assertTrue(text, text.contains("Claude"))
        assertFalse(text, text.contains("Gemini"))
    }

    @Test
    fun closesAreToldApartByCodeAndReason() {
        assertEquals(LiveEnd.BILLING, UserMessages.classifyLive(closed(1011, billingReason)))
        assertEquals(LiveEnd.SERVER, UserMessages.classifyLive(closed(1011, "Internal error encountered.")))
        assertEquals(LiveEnd.SERVER, UserMessages.classifyLive(closed(1013, "Try again later")))
        assertEquals(LiveEnd.BAD_KEY, UserMessages.classifyLive(closed(1007, "API key not valid. Please pass a valid API key.")))
        assertEquals(LiveEnd.NETWORK, UserMessages.classifyLive(closed(1006, "-")))
        assertEquals(LiveEnd.CLOSED, UserMessages.classifyLive(closed(1000, "-")))
        assertEquals(LiveEnd.OTHER, UserMessages.classifyLive(closed(1008, "Request contains an invalid argument.")))
        // A status code inside a close reason is not an HTTP status
        assertEquals(LiveEnd.OTHER, UserMessages.classifyLive(closed(1008, "see http=500")))
    }

    @Test
    fun failuresAreToldApartByExceptionAndHttpStatus() {
        val host = "UnknownHostException: Unable to resolve host \"generativelanguage.googleapis.com\": No address associated with hostname"
        assertEquals(LiveEnd.NETWORK, UserMessages.classifyLive(host))
        assertEquals(LiveEnd.NETWORK, UserMessages.classifyLive("ConnectException: Failed to connect to generativelanguage.googleapis.com/0.0.0.0:443"))
        assertEquals(LiveEnd.NETWORK, UserMessages.classifyLive("SocketTimeoutException: timeout"))
        assertEquals(LiveEnd.NETWORK, UserMessages.classifyLive("SocketException: Connection reset"))
        assertEquals(LiveEnd.BAD_KEY, UserMessages.classifyLive("ProtocolException: Expected HTTP 101 response but was '403 Forbidden' http=403 body={}"))
        assertEquals(
            LiveEnd.BAD_KEY,
            UserMessages.classifyLive("ProtocolException: Expected HTTP 101 http=400 body={\"error\":{\"message\":\"API key not valid.\",\"status\":\"INVALID_ARGUMENT\"}}"),
        )
        // A help URL with "billing" in a refusal is not the billing case
        assertEquals(
            LiveEnd.BAD_KEY,
            UserMessages.classifyLive("ProtocolException: http=403 body={\"error\":{\"message\":\"Permission denied. See https://ai.studio/billing\"}}"),
        )
        assertEquals(
            LiveEnd.BILLING,
            UserMessages.classifyLive("ProtocolException: http=429 body=You exceeded your current quota, please check your plan and billing details."),
        )
        assertEquals(LiveEnd.BILLING, UserMessages.classifyLive("ProtocolException: Expected HTTP 101 http=402 body={}"))
        assertEquals(LiveEnd.BILLING, UserMessages.classifyLive("ProtocolException: http=429 body=$billingReason"))
        assertEquals(LiveEnd.SERVER, UserMessages.classifyLive("ProtocolException: http=429 body=Resource has been exhausted"))
        assertEquals(LiveEnd.SERVER, UserMessages.classifyLive("ProtocolException: http=503 body=unavailable"))
        assertEquals(LiveEnd.OTHER, UserMessages.classifyLive("IllegalStateException: broken"))
    }

    @Test
    fun exceptionsFromTheConnectorAreToldApartByType() {
        assertTrue(UserMessages.aiConnectFailed(UnknownHostException("x")).contains("Wi-Fi"))
        assertEquals(
            UserMessages.aiConnection(LiveEnd.OTHER, connected = false),
            UserMessages.aiConnectFailed(IllegalArgumentException("x")),
        )
        assertEquals(LiveEnd.OTHER, UserMessages.classifyLive("${IOException::class.java.simpleName}:"))
    }

    @Test
    fun theLogGetsTheShortFormOnly() {
        assertEquals("code=1011 billing", UserMessages.liveLog(closed(1011, billingReason)))
        assertEquals("UnknownHostException network", UserMessages.liveLog("UnknownHostException: Unable to resolve host \"x\""))
        assertEquals("ProtocolException http=403 bad-key", UserMessages.liveLog("ProtocolException: Expected HTTP 101 http=403 body=API key not valid"))
    }

    @Test
    fun theTextsSayWhatHappenedAndWhatToDo() {
        assertEquals(
            "Gemini の前払いの残高が足りないかもしれません。AI Studio の Billing を見てください",
            UserMessages.aiConnection(LiveEnd.BILLING, connected = false),
        )
        assertTrue(UserMessages.aiConnection(LiveEnd.BAD_KEY, connected = false).contains("貼り直して"))
        assertTrue(UserMessages.aiConnection(LiveEnd.NETWORK, connected = true).contains("Wi-Fi"))
        assertEquals(
            "手伝い役が止まりました。設定の「困ったとき」から診断の画面を開いて、起動し直してください",
            UserMessages.audioPath(AudioPathEvent.HelperDied, local = false),
        )
        val mic = UserMessages.missingPermissions(listOf(Manifest.permission.RECORD_AUDIO))
        assertTrue(mic, mic.contains("マイク") && mic.contains("許可してください"))
        assertTrue(mic, mic.contains("「${UserMessages.APP_NAME}」を選び"))
        assertEquals(mic, UserMessages.audioPath(AudioPathEvent.Failed(LocalAudioPath.NO_MIC_PERMISSION), local = true))
        assertTrue(UserMessages.missingPermissions(listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)).contains("電話"))
        assertTrue(UserMessages.apiKeyUnreadable().contains("貼り直して"))
        for (t in allTexts()) {
            assertTrue(t, t.count { it == '。' } in 1..2)
            assertFalse("Shizuku is not used any more: $t", t.contains("Shizuku"))
            assertFalse("words of the developers: $t", t.contains("音声の通り道"))
            assertFalse("no exception names: $t", t.contains("Exception"))
        }
    }

    @Test
    fun noNumberKeyOrWordsFromTheCallComeOut() {
        val details = listOf(
            closed(1011, "$billingReason key=$apiKey"),
            closed(1007, "API key not valid: $apiKey"),
            closed(1000, said),
            "ProtocolException: Expected HTTP 101 http=403 body=$apiKey $number $said",
            "UnknownHostException: $number",
            "IllegalStateException: $said $apiKey",
        )
        for (d in details) {
            val end = UserMessages.classifyLive(d)
            for (out in listOf(UserMessages.liveLog(d), UserMessages.aiConnection(end, true), UserMessages.aiConnection(end, false))) {
                for (secret in listOf(apiKey, number, said, "prepayment", "AIzaSy")) {
                    assertFalse("$secret in $out", out.contains(secret))
                }
            }
        }
        for (t in allTexts()) {
            assertFalse("no long digit runs (numbers): $t", Regex("\\d{4,}").containsMatchIn(t))
        }
    }

    private fun allTexts(): List<String> =
        LiveEnd.entries.flatMap { listOf(UserMessages.aiConnection(it, true), UserMessages.aiConnection(it, false)) } +
            listOfNotNull(
                UserMessages.audioPath(AudioPathEvent.HelperDied, local = false),
                UserMessages.audioPath(AudioPathEvent.Silenced, local = true),
                UserMessages.audioPath(AudioPathEvent.Stopped("platform"), local = false),
                UserMessages.audioPath(AudioPathEvent.Stopped("platform"), local = true),
                UserMessages.audioPath(AudioPathEvent.Failed("broken"), local = false),
                UserMessages.audioPath(AudioPathEvent.Failed("broken"), local = true),
                UserMessages.AUDIO_START_FAILED,
                UserMessages.missingPermissions(listOf(Manifest.permission.RECORD_AUDIO)),
                UserMessages.API_KEY_MISSING,
                UserMessages.API_KEY_NOT_SAVED,
                UserMessages.apiKeyNotReadableNow(),
                UserMessages.apiKeyUnreadable(),
            )
}
