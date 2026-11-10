// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.smoke

import io.github.incallagent.agent.live.CallActions
import io.github.incallagent.agent.live.FunctionCall
import io.github.incallagent.agent.live.FunctionResponse
import io.github.incallagent.agent.live.LiveAuth
import io.github.incallagent.agent.live.LiveEndpoint
import io.github.incallagent.agent.live.LiveMessages
import io.github.incallagent.agent.live.LiveSession
import io.github.incallagent.agent.live.LiveTools
import io.github.incallagent.agent.live.ServerEvent
import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.rotep.practice.PracticePolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * PC check of the Gemini Live connection with the real API, before the first try on the phone
 * (docs/live-agent.md "まだ確かめていないこと").
 *
 * Runs only on purpose: the build sets `incallagent.liveSmoke=true` for
 * `./gradlew :apps:rotep:testDebugUnitTest -PliveSmoke`, and GEMINI_API_KEY must be in the
 * environment. Otherwise it is skipped, so CI and normal builds never call the API, even on a PC
 * where the key happens to be set.
 *
 * Steps (about 90 s at most): 1. connect, setup, setupComplete. 2. opening cue, first audio, its
 * rate, "AI" in the output transcript. 3. 2 s of silence through sendAudio. 4. the shop's lines as
 * text: evaluate_offer, report_result, end_call. 5. GoAway and how the connection closed.
 * When the server rejects a message shape, the run retries with [Variant]s and records which one
 * passed (input for an agent-live fix). [SmokeRunTest] runs the same steps against a fake server.
 *
 * The table goes to stdout and to build/live-smoke/result.txt. The key is never printed (every
 * text goes through [Secrets.clean]). The conversation is the fictitious practice booking; only a
 * short piece of the greeting is shown.
 */
class LiveSmokeTest {
    @Test
    fun liveConnection() {
        val enabled = System.getProperty(PROP_ENABLED) == "true"
        val key = System.getenv("GEMINI_API_KEY")?.trim().orEmpty()
        val resultFile = System.getProperty(PROP_RESULT_FILE)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        if (!enabled || key.isEmpty()) {
            val why = if (!enabled) "-PliveSmoke を付けたビルドではない" else "環境変数 GEMINI_API_KEY がない"
            println("SKIPPED: Gemini Live 接続テストは飛ばしました（$why）")
            if (enabled) resultFile?.let { write(it, "SKIPPED: 環境変数 GEMINI_API_KEY がありません。キーを入れてから実行してください。\n") }
            assumeTrue("Gemini Live check skipped (no -PliveSmoke or no GEMINI_API_KEY)", false)
        }

        val model = System.getProperty(PROP_MODEL)?.trim()?.takeIf { it.isNotEmpty() } ?: LiveMessages.DEFAULT_MODEL
        val run = SmokeRun(key, model)
        val report = run.runAndReport()
        println(report)
        resultFile?.let { write(it, report) }
        val problem = run.problem()?.let { run.secrets.clean(it) }
        assertNull("$problem（結果の表を見てください）", problem)
    }

    private fun write(path: Path, text: String) {
        runCatching {
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, text, Charsets.UTF_8)
        }
    }

    companion object {
        const val PROP_ENABLED = "incallagent.liveSmoke"
        const val PROP_RESULT_FILE = "incallagent.liveSmoke.resultFile"
        const val PROP_MODEL = "incallagent.liveSmoke.model"
    }
}

/** How long the steps wait. The defaults are for the real server; tests make them short. */
internal data class SmokeTiming(
    val totalMs: Long = 90_000,
    /** Kept at the end for closing. */
    val closeReserveMs: Long = 4_000,
    val readyMs: Long = 15_000,
    /** Wait for any answer before sending the same text as realtimeInput.text. */
    val noAnswerMs: Long = 8_000,
    val turnMs: Long = 15_000,
    /** No new event for this long after turnComplete: the model's answer is over. */
    val quietMs: Long = 1_500,
    /** 20 ms chunks of silence (100 = 2 s). */
    val silenceChunks: Int = 100,
)

/**
 * The steps of one run and what they found. Times are ms since the start of the run.
 * [connector] null: the real server over OkHttp.
 */
internal class SmokeRun(
    key: String,
    private val model: String,
    private val connector: SmokeConnector? = null,
    private val timing: SmokeTiming = SmokeTiming(),
) {
    val secrets = Secrets(key)

    /** Contains the key: only given to OkHttp. */
    private val url = LiveEndpoint.url(LiveAuth.ApiKey(key))

    private val t0 = System.nanoTime()
    private val startedAt = ZonedDateTime.now(ZONE)
    private val today: LocalDate = startedAt.toLocalDate()
    val policy = PracticePolicy.approved(today, startedAt.toInstant())
    private val cueText = Variant.textOf(LiveMessages.openingCue())
    private val lines = staffLines(policy)

    private var client: OkHttpClient? = null
    private var platform = "-"
    private val attempts = ArrayList<Conn>()
    private var conn: Conn? = null
    private val notes = ArrayList<String>()

    private var textByRealtimeInput = false
    private var cueFallbackAt: Long? = null
    private var silence: Silence? = null
    private val staff = ArrayList<Staff>()
    private var staffFallbackLine: Int? = null

    private class Silence(val startAt: Long, val endAt: Long, val accepted: Int, val refused: Int, val openAfter: Boolean)
    private class Staff(val index: Int, val at: Long, val realtimeText: Boolean, val ok: Boolean)

    fun ms(): Long = (System.nanoTime() - t0) / 1_000_000

    /** Time left for waiting, keeping a few seconds for closing. */
    private fun left(): Long = timing.totalMs - timing.closeReserveMs - ms()

    private fun cap(ms: Long): Long = minOf(ms, left()).coerceAtLeast(0)

    fun note(text: String) {
        notes += secrets.clean(text).take(400)
    }

    private fun progress(text: String) {
        println(secrets.clean("[%5.1f s] %s".format(Locale.ROOT, ms() / 1000.0, text)))
    }

    /** Runs every step, closes, and returns the report (already cleaned of the key). */
    fun runAndReport(): String {
        try {
            execute()
        } catch (t: Throwable) {
            note("テストの中で止まりました：${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { shutdown() }.onFailure { note("閉じるときのエラー：${it.javaClass.simpleName}: ${it.message}") }
        }
        // The progress lines are already out; a broken table must not hide them
        val text = runCatching { report() }.getOrElse {
            "表を作るときのエラー：${it.javaClass.simpleName}: ${it.message}\n" +
                attempts.mapIndexed { i, a -> "  ${i + 1}. ${a.variant.label}：${runCatching { a.outcome() }.getOrDefault("?")}" }.joinToString("\n") + "\n"
        }
        return secrets.clean(text)
    }

    private fun execute() {
        val connect = connector ?: SmokeSocket.newClient().let { (c, p) ->
            client = c
            platform = p
            SmokeConnector { session, variant, hooks -> SmokeSocket.connect(c, url, session, variant, secrets, hooks) }
        }
        if (connector != null) platform = "（偽物のサーバー）"
        progress("接続します（モデル $model、OkHttp $platform）")

        // 1. connect -> setup -> setupComplete. Other shapes only when the server rejects ours.
        val setupShapes = listOf(Variant(), Variant(languageCode = false), Variant(languageCode = false, modalitiesAtTop = true))
        var shape = 0
        var variant = setupShapes[0]
        while (left() > 20_000) {
            val c = Conn(variant, secrets, ::ms)
            attempts += c
            c.start(connect, policy, today, model)
            c.await(cap(timing.readyMs)) { c.readyAt != null || c.endedAt != null }
            if (c.read { c.readyAt == null }) {
                c.cancel()
                progress("READY になりませんでした（${variant.label}）：${c.outcome()}")
                // A refused handshake (key, network, model) or no answer: another shape will not help
                if (c.read { c.openedAt == null || c.endedAt == null }) break
                if (++shape >= setupShapes.size) break
                variant = setupShapes[shape].copy(textAsRealtimeInput = variant.textAsRealtimeInput)
                continue
            }
            progress("READY（setupComplete）")
            // 2. LiveSession sent the opening cue (clientContent) at READY
            c.await(cap(timing.noAnswerMs)) { c.audio.isNotEmpty() || c.endedAt != null }
            val closedAfterCue = c.read { c.endedAt != null && c.audio.isEmpty() }
            if (closedAfterCue && !variant.textAsRealtimeInput) {
                progress("きっかけのあとで接続が閉じられました：${c.outcome()}")
                variant = variant.copy(textAsRealtimeInput = true)
                textByRealtimeInput = true
                continue
            }
            conn = c
            break
        }
        val c = conn ?: return

        if (c.read { c.audio.isEmpty() } && c.isOpen()) {
            // clientContent may not start a turn on this model: the same cue as realtimeInput.text
            progress("きっかけ（clientContent）に音声の返事がありません。realtimeInput.text で送り直します")
            cueFallbackAt = ms()
            c.sendRaw(Variant.realtimeText(cueText))
            c.await(cap(timing.noAnswerMs)) { c.audio.isNotEmpty() || c.endedAt != null }
            if (c.read { c.audio.isNotEmpty() }) textByRealtimeInput = true
        }
        val firstAudio = c.read { c.audio.firstOrNull()?.at } ?: run {
            progress("AI の音声が来ませんでした")
            return
        }
        progress("最初の音声")
        // The greeting ends with turnComplete; then wait for a quiet moment
        c.await(cap(timing.turnMs)) { (c.turnCompletes.any { it >= firstAudio } && c.quiet(timing.quietMs)) || c.endedAt != null }
        progress("あいさつが終わりました")

        // 3. silence, paced like the microphone: 16 kHz 16-bit mono, 20 ms (640 bytes) a chunk
        if (c.isOpen()) {
            val chunk = ByteArray(640)
            val start = ms()
            var accepted = 0
            var refused = 0
            for (i in 0 until timing.silenceChunks) {
                if (!c.isOpen()) break
                if (c.session.sendAudio(chunk)) accepted++ else refused++
                val wait = start + (i + 1) * 20L - ms()
                if (wait > 0) Thread.sleep(wait)
            }
            val end = ms()
            c.await(cap(1_000)) { c.endedAt != null }
            silence = Silence(start, end, accepted, refused, c.isOpen())
            progress("無音を送りました（$accepted/${timing.silenceChunks}）")
        }

        // 4. the shop's lines as text, each after the model's answer to the previous one
        for ((i, line) in lines.withIndex()) {
            if (!c.isOpen() || c.read { c.endCallAt != null } || left() < timing.noAnswerMs) break
            val at = ms()
            val ok = c.sendRaw(if (textByRealtimeInput) Variant.realtimeText(line) else Variant.clientContentText(line))
            staff += Staff(i, at, textByRealtimeInput, ok)
            progress("店員の発言 ${i + 1}（${if (textByRealtimeInput) "realtimeInput.text" else "clientContent"}）")
            // Times are whole ms, so "at or after": the previous answer ended a quiet moment ago
            val answered = c.await(cap(timing.noAnswerMs)) { c.lastActivityAt >= at || c.endedAt != null }
            if (!answered && !textByRealtimeInput && c.isOpen()) {
                progress("clientContent に返事がありません。realtimeInput.text で送り直します")
                textByRealtimeInput = true
                staffFallbackLine = i
                val again = ms()
                staff += Staff(i, again, true, c.sendRaw(Variant.realtimeText(line)))
                c.await(cap(timing.noAnswerMs)) { c.lastActivityAt >= again || c.endedAt != null }
            }
            c.await(cap(timing.turnMs)) {
                (c.turnCompletes.any { it >= at } && c.quiet(timing.quietMs)) || c.endCallAt != null || c.endedAt != null
            }
        }
        val endCall = c.read { c.endCallAt }
        if (endCall != null) {
            progress("AI が end_call を呼びました")
            // Let the last words arrive
            c.await(cap(4_000)) { (c.turnCompletes.any { it >= endCall } && c.quiet(minOf(800, timing.quietMs))) || c.endedAt != null }
        }
    }

    /** 5. Close what is still open and stop OkHttp. */
    private fun shutdown() {
        conn?.let { c ->
            if (c.isOpen()) {
                c.closeFromClient()
                c.await(3_000) { c.closeCode != null || c.failure != null }
            }
        }
        attempts.forEach { it.cancel() }
        client?.let { SmokeSocket.shutdown(it) }
    }

    /** null when the shapes agent-live sends work; otherwise what went wrong (for the assertion). */
    fun problem(): String? {
        val c = conn ?: return attempts.lastOrNull()?.let { "setupComplete まで進みませんでした：${it.outcome()}" }
            ?: "接続を始められませんでした：${notes.firstOrNull() ?: "-"}"
        return when {
            c.read { c.audio.isEmpty() } -> "AI の音声が来ませんでした"
            c.variant != Variant() -> "agent-live の形のままでは通りませんでした（通った形：${c.variant.label}）"
            cueFallbackAt != null || staffFallbackLine != null -> "clientContent に返事がありませんでした（realtimeInput.text で送り直しました）"
            c.read { c.closedByServer() } -> "サーバーが途中で接続を閉じました：${c.outcome()}"
            c.read { c.silentAfterToolResponse() } -> "toolResponse のあと AI が話しませんでした（toolResponse の形が受け付けられていない可能性）"
            notes.isNotEmpty() -> notes.first()
            else -> null
        }
    }

    // --- report ---

    private fun report(): String = buildString {
        val c = conn
        appendLine("== Gemini Live 接続テスト（PC）==")
        appendLine("日時：${startedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}（日本時間）")
        appendLine("モデル：$model ／ OkHttp：$platform ／ 委任ポリシー：${policy.id}（練習用・架空）／ 上限 ${timing.totalMs / 1000} 秒")
        appendLine()
        appendLine("| 段階 | 確かめたこと | 結果 | 値 |")
        appendLine("| --- | --- | --- | --- |")
        for (r in rows(c)) appendLine("| ${r[0]} | ${r[1]} | ${r[2]} | ${r[3].replace('|', '／')} |")
        appendLine()

        appendLine("setup の試し：")
        if (attempts.isEmpty()) appendLine("  （なし）")
        attempts.forEachIndexed { i, a -> appendLine("  ${i + 1}. ${a.variant.label}：${a.outcome()}") }

        if (c != null) {
            c.read {
                appendLine("店員の発言（テキスト）：")
                if (staff.isEmpty()) appendLine("  （送っていません）")
                for (s in staff) {
                    val next = staff.firstOrNull { it.at > s.at }?.at ?: Long.MAX_VALUE
                    val tool = c.tools.firstOrNull { it.at in s.at until next }
                    val audio = c.audio.firstOrNull { it.at in s.at until next }
                    appendLine(
                        "  ${s.index + 1}. ${if (s.realtimeText) "realtimeInput.text" else "clientContent"}" +
                            (if (s.ok) "" else "（送れませんでした）") +
                            "：ツール ${tool?.let { "${it.name} ${it.at - s.at} ms" } ?: "なし"}" +
                            "、音声 ${audio?.let { "${it.at - s.at} ms" } ?: "なし"}"
                    )
                }
                appendLine("ツールの呼び出し：")
                if (c.tools.isEmpty()) appendLine("  （なし）")
                for (t in c.tools) appendLine("  - %.1f s %s：%s".format(Locale.ROOT, t.at / 1000.0, t.name, t.summary()))
                if (c.dtmf.isNotEmpty()) appendLine("プッシュ音：${c.dtmf.joinToString(" ")}")
                appendLine("送ったメッセージ：${counts(c.sent)}（setup は ${c.setupChars} 文字、送れなかったもの ${c.sendFailures}）")
                appendLine("届いたメッセージ：${counts(c.received)}")
                appendLine("serverContent の項目：${c.serverContentKeys.joinToString(", ").ifEmpty { "-" }}")
                appendLine(
                    "そのほか：turnComplete ${c.turnCompletes.size}、generationComplete ${c.generationCompletes}、" +
                        "interrupted ${c.interrupted}、inputTranscription ${c.inputTranscripts}、toolCallCancellation ${c.cancellations}"
                )
                appendLine("LiveSession の状態：")
                for (s in c.stateLog) appendLine("  - $s")
            }
        }
        if (notes.isNotEmpty()) {
            appendLine("メモ：")
            notes.forEach { appendLine("  - $it") }
        }
        appendLine()
        val p = problem()
        appendLine(if (p == null) "結論：OK（agent-live の形のままで、接続・音声・ツールの返事が通りました）" else "結論：要確認 ― $p")
    }

    private fun rows(c: Conn?): List<List<String>> {
        val out = ArrayList<List<String>>()
        val first = attempts.firstOrNull()
        val opened = c ?: attempts.lastOrNull { a -> a.read { a.openedAt != null } }
        out += listOf(
            "1", "接続（WebSocket）",
            if (opened != null) "OK" else "NG",
            opened?.read { "${opened.openedAt!! - opened.startedAt} ms" } ?: (first?.outcome() ?: notes.firstOrNull() ?: "-"),
        )
        out += listOf(
            "1", "setup → setupComplete（READY）",
            if (c != null) "OK" else "NG",
            c?.read { "${c.readyAt!! - c.openedAt!!} ms（${c.variant.label}）" } ?: (attempts.lastOrNull()?.outcome() ?: "-"),
        )
        if (c == null) return out
        c.read {
            val firstAudio = c.audio.firstOrNull()
            out += listOf(
                "2", "きっかけ → 最初の音声",
                if (firstAudio != null) "OK" else "NG",
                firstAudio?.let { a ->
                    val from = cueFallbackAt ?: c.readyAt!!
                    "${a.at - from} ms（${if (cueFallbackAt != null || c.variant.textAsRealtimeInput) "realtimeInput.text" else "clientContent"}）"
                } ?: "${timing.noAnswerMs / 1000} 秒待っても来ませんでした",
            )
            val rates = c.audio.map { it.rate }.distinct()
            val seconds = c.audio.sumOf { it.bytes } / 2.0 / (rates.singleOrNull() ?: 24_000)
            out += listOf(
                "2", "AI の音声のレート",
                when {
                    rates.isEmpty() -> "－"
                    rates == listOf(24_000) -> "OK"
                    else -> "注意"
                },
                if (rates.isEmpty()) "-" else "${rates.joinToString("/")} Hz（${c.audio.size} 回、合計 %.1f 秒分）".format(Locale.ROOT, seconds),
            )
            // The greeting: what the AI said before the first shop line (transcripts may come late)
            val until = staff.firstOrNull()?.at ?: Long.MAX_VALUE
            val greeting = c.outText.filter { it.first < until }.joinToString("") { it.second }.trim()
            val hasAi = greeting.contains("AI") || greeting.contains("ＡＩ")
            out += listOf(
                "2", "AI の文字起こしに「AI」",
                when {
                    greeting.isEmpty() -> "－"
                    hasAi -> "OK"
                    else -> "NG"
                },
                if (greeting.isEmpty()) "文字起こしが来ませんでした"
                else "「${greeting.take(60)}${if (greeting.length > 60) "…" else ""}」" +
                    if (!hasAi && greeting.contains("エーアイ")) "（「エーアイ」と書かれています）" else "",
            )
            val s = silence
            out += listOf(
                "3", "無音 ${timing.silenceChunks * 20 / 1000.0} 秒（sendAudio、20 ms ごと）",
                when {
                    s == null -> "－"
                    s.accepted == timing.silenceChunks && s.openAfter -> "OK"
                    else -> "NG"
                },
                s?.let {
                    "送信 ${it.accepted}/${timing.silenceChunks}" + (if (it.refused > 0) "（断られた ${it.refused}）" else "") +
                        "、${if (it.openAfter) "接続は続いた" else "接続が切れた"}" +
                        "、その間の AI の音声 ${c.audio.count { a -> a.at in it.startAt..it.endAt + 1_000 }} 回"
                } ?: "-",
            )
            val offer = c.tools.firstOrNull { it.name == LiveTools.EVALUATE_OFFER }
            out += listOf(
                "4", "店員の発言 → evaluate_offer",
                if (offer != null) "OK" else "－",
                offer?.let { o ->
                    val since = staff.lastOrNull { it.at <= o.at }?.at
                    (since?.let { "${o.at - it} ms、" } ?: "") + o.summary()
                } ?: "呼ばれませんでした",
            )
            val afterOffer = offer?.let { o -> c.audio.firstOrNull { it.at > o.at } }
            out += listOf(
                "4", "toolResponse のあと会話が続く",
                when {
                    offer == null -> "－"
                    afterOffer != null -> "OK"
                    else -> "NG"
                },
                when {
                    offer == null -> "-"
                    afterOffer != null -> "次の音声まで ${afterOffer.at - offer.at} ms"
                    else -> "toolResponse のあと音声が来ませんでした"
                },
            )
            val report = c.tools.lastOrNull { it.name == LiveTools.REPORT_RESULT }
            out += listOf(
                "4", "report_result",
                when {
                    report == null -> "－"
                    report.ok == true -> "OK"
                    else -> "注意"
                },
                report?.summary() ?: "呼ばれませんでした",
            )
            val end = c.tools.lastOrNull { it.name == LiveTools.END_CALL }
            out += listOf(
                "4", "end_call",
                when {
                    c.endCallAt != null -> "OK"
                    end != null -> "注意"
                    else -> "－"
                },
                when {
                    c.endCallAt != null -> "受け付けた（電話を切る操作が呼ばれた）"
                    end != null -> end.summary()
                    else -> "呼ばれませんでした"
                },
            )
            out += listOf(
                "5", "GoAway",
                if (c.goAwayAt != null) "来た" else "来なかった",
                c.goAwayAt?.let { "%.1f s に timeLeft=%s".format(Locale.ROOT, it / 1000.0, c.goAway) } ?: "-",
            )
            out += listOf("5", "閉じたとき", if (c.closedByServer()) "サーバーから" else "こちらから", c.closeText())
            val endedAt = c.endedAt ?: ms()
            out += listOf("5", "接続していた時間", "", "%.1f 秒".format(Locale.ROOT, (endedAt - c.openedAt!!) / 1000.0))
        }
        return out
    }

    private fun counts(m: Map<String, Int>) = m.entries.joinToString(", ") { "${it.key} ${it.value}" }.ifEmpty { "-" }

    companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")

        /** What the shop says, in order. The first line is the first choice of the practice policy. */
        fun staffLines(p: DelegationPolicy): List<String> {
            val t = p.request.windows.first().earliestStart
            val day = "${t.monthValue}月${t.dayOfMonth}日（${t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)}）"
            val hour = "${t.hour}時"
            return listOf(
                "${day}の${hour}、2名様で禁煙席をご用意できます。",
                "かしこまりました。ご予約のお名前と、ご連絡先のお電話番号をお願いいたします。",
                "ありがとうございます。${day}の${hour}から2名様、禁煙席で承りました。当日お待ちしております。",
                "ほかにご用件はございますか。",
                "それでは、失礼いたします。",
            )
        }
    }
}

/** One connection attempt: its LiveSession, its link and everything that happened on it. */
internal class Conn(
    val variant: Variant,
    private val secrets: Secrets,
    private val clock: () -> Long,
) : LiveSession.Listener, CallActions, SmokeSocket.Hooks {
    class Audio(val at: Long, val bytes: Int, val rate: Int)

    class Tool(val at: Long, val name: String, val ok: Boolean?, val accept: Boolean?, val info: String) {
        fun summary(): String = listOfNotNull(
            accept?.let { "accept=$it" },
            ok?.let { "ok=$it" },
            info.takeIf { it.isNotEmpty() },
        ).joinToString("、")
    }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    lateinit var session: LiveSession
        private set

    @Volatile
    private var link: SmokeLink? = null

    // All fields below are guarded by [lock]
    var startedAt = 0L
    var openedAt: Long? = null
    var readyAt: Long? = null

    /** Closed or failed, whoever did it. */
    var endedAt: Long? = null
    var clientClosedAt: Long? = null
    var closeCode: Int? = null
    var closeReason = ""
    var failure: String? = null
    var httpCode: Int? = null

    val audio = ArrayList<Audio>()
    val turnCompletes = ArrayList<Long>()
    val tools = ArrayList<Tool>()

    /** Output transcript fragments with their time. */
    val outText = ArrayList<Pair<Long, String>>()
    var interrupted = 0
    var generationCompletes = 0
    var cancellations = 0
    var inputTranscripts = 0
    var goAwayAt: Long? = null
    var goAway: String? = null
    var endCallAt: Long? = null
    val dtmf = ArrayList<String>()
    var lastActivityAt = -1L
    val received = LinkedHashMap<String, Int>()
    val serverContentKeys = LinkedHashSet<String>()
    val sent = LinkedHashMap<String, Int>()
    var sendFailures = 0
    var setupChars = 0
    val stateLog = ArrayList<String>()

    fun start(connector: SmokeConnector, policy: DelegationPolicy, today: LocalDate, model: String) {
        session = LiveSession(policy, today, this, this, model)
        update { startedAt = clock() }
        link = connector.connect(session, variant, this)
    }

    fun <T> read(block: () -> T): T = lock.withLock(block)

    private fun update(block: () -> Unit) = lock.withLock {
        block()
        changed.signalAll()
    }

    /** Waits until [condition] holds (checked at least every 100 ms) or [timeoutMs] passed. */
    fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = clock() + timeoutMs
        return lock.withLock {
            var ok = condition()
            while (!ok) {
                val wait = end - clock()
                if (wait <= 0) break
                changed.await(minOf(wait, 100L), TimeUnit.MILLISECONDS)
                ok = condition()
            }
            ok
        }
    }

    /** Call inside [read] or [await]. */
    fun quiet(ms: Long): Boolean = lastActivityAt >= 0 && clock() - lastActivityAt >= ms

    fun isOpen(): Boolean = read { endedAt == null && clientClosedAt == null }

    /** Call inside [read]. */
    fun closedByServer(): Boolean = clientClosedAt == null && (closeCode != null || failure != null)

    /** Call inside [read]. evaluate_offer was answered, the line stayed open 8 s, and no audio came. */
    fun silentAfterToolResponse(): Boolean {
        val offer = tools.firstOrNull { it.name == LiveTools.EVALUATE_OFFER } ?: return false
        if (audio.any { it.at > offer.at }) return false
        val openUntil = endedAt ?: clientClosedAt ?: clock()
        return openUntil - offer.at >= 8_000
    }

    fun sendRaw(message: JsonObject): Boolean {
        val ok = link?.sendRaw(message) ?: false
        sent(message.toString(), ok)
        return ok
    }

    fun closeFromClient() {
        update { clientClosedAt = clock() }
        session.close()
    }

    fun cancel() {
        link?.cancel()
    }

    /** One line about how this attempt went. */
    fun outcome(): String = read {
        buildString {
            if (openedAt == null) {
                append(failure ?: if (endedAt == null) "待っても接続できませんでした" else "接続できませんでした")
                return@buildString
            }
            append("接続 ${openedAt!! - startedAt} ms")
            readyAt?.let { append(" → READY ${it - openedAt!!} ms") }
            if (closeCode != null || failure != null) {
                append(" → ").append(closeText())
                if (readyAt == null) append("（setupComplete なし）")
            } else if (readyAt == null) {
                append(" → setupComplete が来ませんでした")
            }
        }
    }

    /** Call inside [read]. */
    fun closeText(): String = when {
        failure != null -> "切れた：$failure"
        closeCode != null && clientClosedAt != null -> "こちらから閉じた（1000）。サーバーの返事 code=$closeCode reason=${closeReason.ifEmpty { "-" }}"
        closeCode != null -> "サーバーが閉じた code=$closeCode reason=${closeReason.ifEmpty { "-" }}"
        clientClosedAt != null -> "こちらから閉じた（1000）。サーバーの返事なし"
        else -> "閉じていない"
    }

    // --- LiveSession.Listener (network thread) ---

    override fun onState(state: LiveSession.State, detail: String) = update {
        stateLog += "%.1f s %s %s".format(Locale.ROOT, clock() / 1000.0, state, secrets.clean(detail).take(300))
        when (state) {
            LiveSession.State.READY -> if (readyAt == null) readyAt = clock()
            LiveSession.State.CLOSED, LiveSession.State.FAILED -> if (endedAt == null) endedAt = clock()
            else -> {}
        }
    }

    override fun onEvent(event: ServerEvent) = update {
        val t = clock()
        when (event) {
            is ServerEvent.Audio -> {
                audio += Audio(t, event.pcm.size, event.sampleRate)
                lastActivityAt = t
            }
            is ServerEvent.OutputTranscript -> {
                outText += t to event.text
                lastActivityAt = t
            }
            is ServerEvent.InputTranscript -> inputTranscripts++
            ServerEvent.TurnComplete -> {
                turnCompletes += t
                lastActivityAt = t
            }
            ServerEvent.Interrupted -> interrupted++
            ServerEvent.GenerationComplete -> generationCompletes++
            is ServerEvent.ToolCallCancellation -> cancellations++
            is ServerEvent.GoAway -> if (goAwayAt == null) {
                goAwayAt = t
                goAway = event.timeLeft ?: "?"
            }
            else -> {}
        }
    }

    override fun onToolCall(call: FunctionCall, response: FunctionResponse) = update {
        val r = response.response
        val info = when (call.name) {
            LiveTools.EVALUATE_OFFER -> "start=${str(call.args, "start") ?: "-"}、partySize=${str(call.args, "partySize") ?: "-"}" +
                ((r["reasons"] as? JsonArray)?.firstOrNull()?.let { "、理由「${(it as? JsonPrimitive)?.contentOrNull}」" } ?: "")
            LiveTools.REPORT_RESULT -> "status=${str(call.args, "status") ?: "-"}、problems=${(r["problems"] as? JsonArray)?.size ?: 0}"
            LiveTools.SEND_DTMF -> "digits=${str(call.args, "digits") ?: "-"}"
            else -> ""
        } + (str(r, "error")?.let { "、error「$it」" } ?: "")
        tools += Tool(clock(), call.name, flag(r, "ok"), flag(r, "accept"), secrets.clean(info).take(200))
        lastActivityAt = clock()
    }

    // --- CallActions (called by the tool handler) ---

    override fun endCall(reason: String) = update { if (endCallAt == null) endCallAt = clock() }

    override fun sendDtmf(digits: String) = update { dtmf += digits }

    // --- SmokeSocket.Hooks ---

    override fun opened() = update { if (openedAt == null) openedAt = clock() }

    override fun received(text: String) = update {
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (o == null) {
            received.merge("(JSON ではない)", 1, Int::plus)
        } else {
            for (k in o.keys) received.merge(k, 1, Int::plus)
            (o["serverContent"] as? JsonObject)?.let { serverContentKeys += it.keys }
        }
    }

    override fun sent(text: String, ok: Boolean) = update {
        val k = kind(text)
        sent.merge(k, 1, Int::plus)
        if (!ok) sendFailures++
        if (k == "setup") setupChars = text.length
    }

    override fun closed(code: Int, reason: String, fromServer: Boolean) = update {
        if (closeCode == null) {
            closeCode = code
            closeReason = secrets.clean(reason).take(300)
        }
        if (endedAt == null) endedAt = clock()
    }

    override fun failed(detail: String, httpCode: Int?) = update {
        if (failure == null) {
            failure = secrets.clean(detail).take(500)
            this.httpCode = httpCode
        }
        if (endedAt == null) endedAt = clock()
    }

    private fun str(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
    private fun flag(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.booleanOrNull

    /** "setup", "clientContent", "realtimeInput.audio", "toolResponse"... */
    private fun kind(text: String): String {
        val first = text.removePrefix("{\"").substringBefore('"')
        return if (first == "realtimeInput") "realtimeInput." + text.removePrefix("{\"realtimeInput\":{\"").substringBefore('"') else first
    }
}
