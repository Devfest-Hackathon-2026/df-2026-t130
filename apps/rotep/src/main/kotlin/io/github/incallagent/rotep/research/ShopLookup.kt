// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.research

import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.core.policy.TimeWindow
import io.github.incallagent.rotep.practice.PracticePolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * What a web search found about the shop before the call, as the model wrote it.
 *
 * Grounding with Google Search has terms for how the result is used: it is shown to the user who
 * asked, unchanged, together with its Search Suggestions. So [text] is shown as it is and nothing
 * in this app reads meaning out of it, and it does not go to the speaking agent (web pages are
 * also not to be trusted as instructions for something that can operate a phone call).
 */
data class ShopFindings(
    val text: String,
    val sources: List<Source>,
    /** The HTML of the Search Suggestions, at least one, to be shown with [text] as they came. */
    val suggestionsHtml: List<String>,
) {
    data class Source(val title: String, val url: String)
}

/** The request and the response of the search, without any network, so unit tests see them. */
object ShopLookupMessages {
    const val MODEL = "gemini-3.8-flash"
    private const val MAX_SOURCES = 5
    private const val MAX_SUGGESTIONS = 5

    /**
     * Only the shop and the wished-for times and party size: never the reservation name, a contact
     * number, or anything the user wrote in a free-text field.
     */
    fun prompt(policy: DelegationPolicy): String = buildString {
        val t = policy.target
        appendLine("次のお店について、Google 検索で最新の情報を調べて、日本語で短く答えてください。記号（* や #）は使わないでください。")
        appendLine("お店：${t.businessName}（電話番号 ${t.phoneNumber}）")
        val r = policy.request
        if (r.windows.isNotEmpty()) appendLine("希望の日時：" + r.windows.joinToString("、") { window(it) })
        r.partySize?.let { appendLine("人数：${it.preferred}名") }
        appendLine("次の順に、短い箇条書きで答えてください。見つからないことは「分かりません」と書き、推測しないでください。")
        var n = 1
        if (r.windows.isNotEmpty()) {
            appendLine("${n++}. 希望の日時に行けそうか（「合いそうです」「合わないようです」「分かりません」のどれかで始めて、理由を1文で）")
        }
        appendLine("${n++}. 営業時間と定休日")
        appendLine("${n++}. 電話で予約できるか（ネットだけ、予約を受けない、など）")
        appendLine("$n. 予約の前に知っておくとよいこと（時間制、キャンセル料など）")
        append("同じ名前の別のお店と間違えないように、電話番号が合うお店の情報だけを使ってください。合うお店が見つからなければ、そう書いてください。")
    }

    fun body(prompt: String): String = buildJsonObject {
        put("model", MODEL)
        put("input", prompt)
        // The interaction is not kept for later turns. The search itself keeps prompts for a while (its terms)
        put("store", false)
        put("tools", buildJsonArray { add(buildJsonObject { put("type", "google_search") }) })
    }.toString()

    /**
     * null when the answer has no text, was cut off, or came without Search Suggestions: the
     * terms have the result shown with them, and without a search there is no result to show.
     * Unknown fields are ignored. Only https links are kept: they are opened from the screen.
     */
    fun parse(json: String): ShopFindings? {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        if (root.string("status") == "incomplete") return null
        val steps = (root["steps"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        // The answer is the last run of output: text from before a search is not the answer
        val finalTurn = steps
            .dropLastWhile { it.type() != "model_output" || it.textBlocks().isEmpty() }
            .takeLastWhile { it.type() == "model_output" || it.type() == "thought" }
        val blocks = finalTurn.filter { it.type() == "model_output" }.flatMap { it.textBlocks() }
        val text = blocks.joinToString("") { it.string("text").orEmpty() }
        if (text.isBlank()) return null
        val sources = blocks
            .flatMap { (it["annotations"] as? JsonArray).orEmpty().mapNotNull { a -> a as? JsonObject } }
            .filter { it.type() == "url_citation" }
            .mapNotNull { a ->
                val url = a.string("url")?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
                ShopFindings.Source(a.string("title")?.takeIf { it.isNotBlank() } ?: url, url)
            }
            .distinctBy { it.url }
            .take(MAX_SOURCES)
        val suggestions = steps.filter { it.type() == "google_search_result" }
            .flatMap { step ->
                when (val r = step["result"]) {
                    is JsonArray -> r.mapNotNull { it as? JsonObject }
                    is JsonObject -> listOf(r)
                    else -> emptyList()
                }
            }
            .mapNotNull { it.string("search_suggestions")?.takeIf { html -> html.isNotBlank() } }
            .distinct()
            .take(MAX_SUGGESTIONS)
        if (suggestions.isEmpty()) return null
        return ShopFindings(text, sources, suggestions)
    }

    private fun JsonObject.textBlocks(): List<JsonObject> =
        (this["content"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { it.type() == "text" }

    private fun JsonObject.type(): String? = string("type")

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private fun window(w: TimeWindow): String {
        val start = dateTime(w.earliestStart)
        return if (w.latestStart == w.earliestStart) start else "$start〜${time(w.latestStart)}"
    }

    private fun dateTime(t: LocalDateTime) =
        "${t.monthValue}月${t.dayOfMonth}日(${t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)}) ${time(t)}"

    private fun time(t: LocalDateTime) = "%d:%02d".format(Locale.ROOT, t.hour, t.minute)
}

/** Looks the shop up on the web with the user's Gemini API key. Blocking: call it off the main thread. */
class ShopLookup(
    private val apiKey: String,
    private val log: (String) -> Unit,
    private val client: OkHttpClient = DEFAULT_CLIENT,
) {
    sealed interface Result {
        data class Found(val findings: ShopFindings) : Result

        /** [reason] is for the screen. */
        data class Failed(val reason: String) : Result
    }

    fun fetch(policy: DelegationPolicy): Result {
        val request = Request.Builder()
            .url(URL)
            .header("x-goog-api-key", apiKey)
            .header("Api-Revision", API_REVISION)
            .post(ShopLookupMessages.body(ShopLookupMessages.prompt(policy)).toRequestBody(JSON))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                log("lookup: http ${response.code}")
                when {
                    response.code == 401 || response.code == 403 -> Result.Failed("API キーが使えないので、調べられませんでした")
                    response.code == 429 -> Result.Failed("検索の回数の上限に当たったので、調べられませんでした")
                    !response.isSuccessful -> Result.Failed("調べられませんでした。少し待ってから、もう一度試してください")
                    else -> ShopLookupMessages.parse(response.body.string())?.let { Result.Found(it) }
                        ?: Result.Failed("調べましたが、Google 検索の結果を受け取れませんでした")
                }
            }
        } catch (e: InterruptedIOException) {
            log("lookup: ${e.javaClass.simpleName}")
            Result.Failed("時間がかかりすぎたので、調べられませんでした")
        } catch (e: IOException) {
            log("lookup: ${e.javaClass.simpleName}")
            Result.Failed("ネットにつながらないので、調べられませんでした")
        }
    }

    companion object {
        private const val URL = "https://generativelanguage.googleapis.com/v1beta/interactions"
        private const val API_REVISION = "2026-05-20"
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val SEARCH_TIMEOUT_SECONDS = 40L
        private val JSON = "application/json".toMediaType()
        private val DEFAULT_CLIENT by lazy {
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // Nothing arrives until the search is done: callTimeout is the only limit
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .callTimeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Whether to look this request up: not a cancel (the booking exists, there is nothing to
         * check) and not the made-up practice shop.
         */
        fun wanted(policy: DelegationPolicy): Boolean =
            policy.purpose != Purpose.CANCEL && digits(policy.target.phoneNumber) != digits(PracticePolicy.PHONE_NUMBER)

        private fun digits(number: String) = number.filter { it.isDigit() }
    }
}
