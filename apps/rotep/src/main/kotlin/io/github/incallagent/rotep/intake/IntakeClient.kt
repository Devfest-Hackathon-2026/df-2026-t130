// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.intake

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Sends one turn of the chat with the user's Claude API key, read again for every turn so that
 * fixing it in the settings takes effect. Logs the status and fixed words only.
 */
class IntakeClient(
    private val apiKey: () -> String?,
    private val log: (String) -> Unit,
    private val client: OkHttpClient = DEFAULT_CLIENT,
    /** Why the key cannot be read, in words that say what to do. */
    private val keyProblem: () -> String? = { null },
) : IntakeTransport {
    /** The account does not take the option that retries a refusal on another model. */
    @Volatile
    private var withoutFallback = false

    override fun send(body: String, done: (IntakeReply) -> Unit): () -> Unit {
        val current = AtomicReference<Call?>(null)
        val stopped = AtomicBoolean(false)
        // Reading the key goes through the Keystore: not on the thread of the tap, which holds the chat's lock
        thread(name = "intake", isDaemon = true) {
            val key = apiKey()
            if (key == null) {
                done(IntakeReply.Failed(keyProblem() ?: "Claude の API キーを読めませんでした。設定を確かめてください"))
                return@thread
            }
            val first = try {
                call(key, body)
            } catch (e: IllegalArgumentException) { // a character a header cannot hold
                log("intake: ${e.javaClass.simpleName}")
                done(IntakeReply.Failed("Claude の API キーの形が正しくありません。設定を確かめてください"))
                return@thread
            }
            current.set(first)
            if (stopped.get()) first.cancel() // stopped before there was anything to cancel
            done(turn(key, body, first, current))
        }
        return {
            stopped.set(true)
            current.get()?.cancel()
        }
    }

    /** Sends nothing of the user's. What comes back is not used. */
    fun warmUp() {
        thread(name = "intake-warm-up", isDaemon = true) {
            val key = apiKey() ?: return@thread
            val request = try {
                Request.Builder()
                    .url(IntakeMessages.URL)
                    .header("x-api-key", key)
                    .header("anthropic-version", IntakeMessages.API_VERSION)
                    .post(IntakeMessages.warmUpBody().toRequestBody(JSON))
                    .build()
            } catch (e: IllegalArgumentException) {
                log("intake: ${e.javaClass.simpleName}")
                return@thread
            }
            try {
                client.newCall(request).execute().use { log("intake: warm up ${it.code}") }
            } catch (e: IOException) {
                log("intake: warm up ${e.javaClass.simpleName}")
            }
        }
    }

    private fun call(key: String, body: String): Call {
        val plain = withoutFallback
        val request = Request.Builder()
            .url(IntakeMessages.URL)
            .header("x-api-key", key)
            .header("anthropic-version", IntakeMessages.API_VERSION)
            .apply { if (!plain) header("anthropic-beta", FALLBACK_BETA) }
            .post((if (plain) IntakeMessages.withoutFallback(body) else body).toRequestBody(JSON))
            .build()
        return client.newCall(request)
    }

    private fun turn(key: String, body: String, first: Call, current: AtomicReference<Call?>): IntakeReply = try {
        var (status, text) = exchange(first)
        if (!withoutFallback && IntakeMessages.fallbackNotAccepted(status, text) && !first.isCanceled()) {
            withoutFallback = true
            val again = call(key, body)
            current.set(again)
            if (first.isCanceled()) again.cancel()
            val answer = exchange(again)
            status = answer.first
            text = answer.second
        }
        IntakeMessages.reply(status, text)
    } catch (e: InterruptedIOException) {
        log("intake: ${e.javaClass.simpleName}")
        IntakeReply.Failed("時間がかかりすぎました。もう一度送ってください")
    } catch (e: IOException) {
        log("intake: ${e.javaClass.simpleName}")
        IntakeReply.Failed("ネットにつながらないので送れませんでした")
    }

    private fun exchange(call: Call): Pair<Int, String> = call.execute().use { response ->
        val text = response.body.string()
        log("intake: ${IntakeMessages.forLog(response.code, text)}")
        response.code to text
    }

    private companion object {
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val TURN_TIMEOUT_SECONDS = 120L
        val JSON = "application/json".toMediaType()
        val DEFAULT_CLIENT by lazy {
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // Nothing arrives until the model is done thinking: callTimeout is the only limit
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .callTimeout(TURN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }
    }
}
