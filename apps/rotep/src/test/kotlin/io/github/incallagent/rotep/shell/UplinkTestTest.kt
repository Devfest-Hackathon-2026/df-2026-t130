// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class UplinkTestTest {
    private val inCall = UplinkTestRules.MODE_IN_CALL
    private val normal = 0
    private val rate = MarkerTone.SAMPLE_RATE

    /** Phone-number-like runs, the same shape that ReportText masks. */
    private val phoneLike = Regex("""[(+]?\d[\d\-\s().]{7,}\d""")

    private fun facts(kind: UplinkTestKind, accepted: Boolean? = true): UplinkTrackFacts {
        val preferred = when (kind.method) {
            UplinkMethod.DIRECT -> EnvReport.TYPE_TELEPHONY
            UplinkMethod.SPEAKER -> 2
            else -> null
        }
        val given = kind.method != UplinkMethod.REDIRECTION
        return UplinkTrackFacts(
            maker = "AudioTrack.Builder",
            attributes = "USAGE_VOICE_COMMUNICATION・CONTENT_TYPE_SPEECH",
            attributionGiven = given,
            attributionPackage = if (given) ShellIdentity.SHELL_PACKAGE else null,
            attributionUid = if (given) ShellIdentity.SHELL_UID else null,
            preferredDeviceType = preferred,
            preferredDeviceAccepted = if (preferred == null) null else accepted,
            sampleRate = rate,
            bufferFrames = 4800,
        )
    }

    /** A clock that moves only when the test sleeps or writes sound. */
    private inner class FakeEnv(
        var mode: (elapsedMs: Long) -> Int? = { inCall },
        var micMuted: Boolean? = null,
        var openFailure: Throwable? = null,
        var accepted: Boolean? = true,
        var route: (elapsedMs: Long) -> Int? = { 18 },
    ) : UplinkEnv {
        val start = 1_000_000L
        var time = start
        var onSleep: () -> Unit = {}
        var onOpen: (FakeOutput) -> Unit = {}
        var opened = 0
        var openedAtMs = -1L
        var output: FakeOutput? = null

        val elapsed: Long get() = time - start

        override fun nowMs(): Long = time

        override fun sleepMs(ms: Long) {
            time += ms
            onSleep()
        }

        override fun clockText(): String = "21:00:00"

        override fun audioMode(): Int? = mode(elapsed)

        override fun conditions(kind: UplinkTestKind) = UplinkConditions(
            audioMode = mode(elapsed),
            microphoneMuted = micMuted ?: kind.micMuted,
            communicationDeviceType = 1,
            telephonyOutput = true,
            voiceCallVolume = "4 / 7",
            pstnInterceptable = if (kind.method == UplinkMethod.REDIRECTION) "true" else null,
        )

        override fun open(kind: UplinkTestKind, sampleRate: Int): UplinkOutput {
            opened++
            openedAtMs = elapsed
            openFailure?.let { throw it }
            return FakeOutput(this, facts(kind, accepted)).also {
                output = it
                onOpen(it)
            }
        }
    }

    private inner class FakeOutput(private val env: FakeEnv, override val facts: UplinkTrackFacts) : UplinkOutput {
        var played = false
        var closed = 0
        var writes = 0
        var frames = 0L
        val sound = ShortArray(UplinkTestRules.MAX_PLAY_SECONDS * rate)
        var onWrite: (count: Int) -> Int = { it }

        override fun play() {
            played = true
        }

        override fun routedDeviceType(): Int? = env.route(env.elapsed)

        override fun write(buffer: ShortArray, count: Int): Int {
            writes++
            val written = onWrite(count)
            if (written > 0) {
                buffer.copyInto(sound, frames.toInt(), 0, written)
                frames += written
                env.time += written * 1000L / rate
            }
            return written
        }

        override fun close(): UplinkEndFacts {
            closed++
            return UplinkEndFacts(frames, 0)
        }
    }

    private class Sink : UplinkTestRun.Sink {
        val phases = ArrayList<Int>()
        var last = ""

        override fun onPhase(phase: Int, log: String) {
            phases += phase
            last = log
        }
    }

    private fun request(test: Int, delay: Int = 0, play: Int = 20, level: Int = -12) =
        UplinkTestRules.request(test, delay, play, level)!!

    // --- The course of a test ---------------------------------------------------------------------

    @Test
    fun stopsByItselfAfterTwentySeconds() {
        val env = FakeEnv(mode = { normal })
        val sink = Sink()
        UplinkTestRun(request(0), env, sink).run()
        val out = env.output!!
        assertTrue(out.played)
        assertEquals(1, out.closed)
        assertEquals(20L * rate, out.frames)
        assertEquals(20_000L, env.elapsed)
        assertEquals(UplinkPhase.PLAYING, sink.phases.first())
        assertEquals(UplinkPhase.ENDED, sink.phases.last())
        assertEquals(1, sink.phases.count { it == UplinkPhase.ENDED })
        assertTrue(sink.last, sink.last.contains("止まった理由: 20 秒で自動停止"))
        assertTrue(sink.last.contains("書いたフレーム: 960000 / 再生した位置: 960000"))
    }

    @Test
    fun theDefaultIsTwentySecondsWithTwoMarkers() {
        assertEquals(20, UplinkTestRules.DEFAULT_PLAY_SECONDS)
        assertEquals(-12, UplinkTestRules.DEFAULT_LEVEL_DBFS)
        assertEquals(2, MarkerTone.markersIn(UplinkTestRules.DEFAULT_PLAY_SECONDS * 1000L))
        val header = UplinkReport.header(request(1), "21:00:00").joinToString("\n")
        assertTrue(header, header.contains("この試験で 2 回（始めてから 0・10 秒）"))
    }

    @Test
    fun theSoundIsTheMarkerCycle() {
        val env = FakeEnv(mode = { normal })
        UplinkTestRun(request(0), env, Sink()).run()
        val sound = env.output!!.sound
        val pattern = MarkerTone.pattern(-12.0)
        val cycle = MarkerTone.cycleFrames()
        for (c in 0 until 2) {
            val base = c * cycle
            assertArrayEquals("marker $c", pattern, sound.copyOfRange(base, base + pattern.size))
            for (i in base + pattern.size until base + cycle) assertEquals(0, sound[i].toInt())
        }
    }

    @Test
    fun theLevelReachesTheSound() {
        val env = FakeEnv(mode = { normal })
        UplinkTestRun(request(0, play = 3, level = -24), env, Sink()).run()
        val sound = env.output!!.sound.copyOf(env.output!!.frames.toInt())
        assertEquals(-24f, LevelMeter.measure(sound).peakDbfs, 0.1f)
    }

    @Test
    fun stopButtonStopsTheSoundAtOnce() {
        val env = FakeEnv()
        val sink = Sink()
        val run = UplinkTestRun(request(1), env, sink)
        // Pressed after 2 s of sound (100 writes of 20 ms).
        env.onOpen = { out ->
            out.onWrite = { count ->
                if (out.writes >= 100) run.requestStop()
                count
            }
        }
        run.run()
        val out = env.output!!
        assertEquals(1, out.closed)
        assertEquals(100L * MarkerTone.frames(UplinkTestRun.CHUNK_MS), out.frames)
        assertTrue(sink.last, sink.last.contains("止まった理由: ${UplinkTestRun.REASON_STOP_BUTTON}"))
        assertEquals(1, sink.phases.count { it == UplinkPhase.ENDED })
    }

    @Test
    fun stopDuringTheDelayPlaysNothing() {
        val env = FakeEnv()
        val sink = Sink()
        val run = UplinkTestRun(request(1, delay = 10), env, sink)
        env.onSleep = { if (env.elapsed >= 3_000) run.requestStop() }
        run.run()
        assertEquals(0, env.opened)
        assertEquals(listOf(UplinkPhase.WAITING, UplinkPhase.ENDED), sink.phases)
        assertTrue(sink.last, sink.last.contains("止まった理由: ${UplinkTestRun.REASON_STOPPED_BEFORE}"))
        assertTrue(env.elapsed < 10_000)
    }

    @Test
    fun theSoundStartsAfterTheDelay() {
        val env = FakeEnv(mode = { normal })
        val sink = Sink()
        UplinkTestRun(request(0, delay = 5), env, sink).run()
        assertEquals(5_000L, env.openedAtMs)
        assertEquals(UplinkPhase.WAITING, sink.phases.first())
        assertTrue(sink.phases.contains(UplinkPhase.PLAYING))
        assertEquals(UplinkPhase.ENDED, sink.phases.last())
        assertEquals(25_000L, env.elapsed)
    }

    // --- Start conditions -------------------------------------------------------------------------

    @Test
    fun testsOneToThreeStartOnlyDuringACall() {
        for (test in 1..3) {
            for (mode in listOf<Int?>(normal, 1, 3, null)) {
                val env = FakeEnv(mode = { mode })
                val sink = Sink()
                UplinkTestRun(request(test), env, sink).run()
                assertEquals("test $test, mode $mode", 0, env.opened)
                assertEquals(listOf(UplinkPhase.ENDED), sink.phases)
                assertTrue(sink.last, sink.last.contains("通話中ではない"))
            }
            val env = FakeEnv(mode = { inCall })
            UplinkTestRun(request(test), env, Sink()).run()
            assertEquals("test $test in a call", 1, env.opened)
        }
    }

    @Test
    fun testsZeroAndFourNeedNoCall() {
        for (test in listOf(0, 4)) {
            val env = FakeEnv(mode = { normal })
            UplinkTestRun(request(test), env, Sink()).run()
            assertEquals("test $test", 1, env.opened)
            assertTrue(env.output!!.played)
        }
    }

    @Test
    fun aCallThatEndsDuringTheDelayStopsTheTest() {
        val env = FakeEnv(mode = { elapsed -> if (elapsed < 2_000) inCall else normal })
        val sink = Sink()
        UplinkTestRun(request(1, delay = 5), env, sink).run()
        assertEquals(0, env.opened)
        assertTrue(sink.last, sink.last.contains("通話中ではない（音声のモード: MODE_NORMAL (0)）"))
    }

    @Test
    fun aCallThatEndsWhilePlayingStopsTheSound() {
        val env = FakeEnv(mode = { elapsed -> if (elapsed < 5_000) inCall else normal })
        val sink = Sink()
        UplinkTestRun(request(2), env, sink).run()
        val out = env.output!!
        assertEquals(1, out.closed)
        assertTrue(out.frames < 6L * rate)
        assertTrue(out.frames >= 5L * rate)
        assertTrue(sink.last, sink.last.contains("音声のモードが MODE_NORMAL (0) になった"))
    }

    @Test
    fun rulesKeepEveryValueInsideItsBounds() {
        val low = UplinkTestRules.request(1, -5, 0, -3)!!
        assertEquals(0, low.delaySeconds)
        assertEquals(1, low.playSeconds)
        assertEquals(UplinkTestRules.MAX_LEVEL_DBFS, low.levelDbfs)
        val high = UplinkTestRules.request(1, 1_000, 1_000, -100)!!
        assertEquals(UplinkTestRules.MAX_DELAY_SECONDS, high.delaySeconds)
        assertEquals(UplinkTestRules.MAX_PLAY_SECONDS, high.playSeconds)
        assertEquals(UplinkTestRules.MIN_LEVEL_DBFS, high.levelDbfs)
        assertNull(UplinkTestRules.request(5, 0, 20, -12))
        assertNull(UplinkTestRules.request(-1, 0, 20, -12))
        assertTrue(UplinkTestRules.LEVEL_CHOICES.all { it <= UplinkTestRules.MAX_LEVEL_DBFS })
        assertTrue(UplinkTestRules.PLAY_CHOICES.all { it <= UplinkTestRules.MAX_PLAY_SECONDS })
        assertTrue(UplinkTestRules.DELAY_CHOICES.containsAll(listOf(0, 5, 10)))
    }

    @Test
    fun theFiveTests() {
        assertEquals(listOf(0, 1, 2, 3, 4), UplinkTestKind.entries.map { it.number })
        assertEquals(listOf(1, 2, 3), UplinkTestKind.entries.filter { it.needsCall }.map { it.number })
        assertEquals(listOf(1, 3), UplinkTestKind.entries.filter { it.micMuted }.map { it.number })
        assertEquals(UplinkMethod.DEFAULT_ROUTE, UplinkTestKind.fromNumber(0)!!.method)
        assertEquals(UplinkMethod.DIRECT, UplinkTestKind.fromNumber(1)!!.method)
        assertEquals(UplinkMethod.DIRECT, UplinkTestKind.fromNumber(2)!!.method)
        assertEquals(UplinkMethod.REDIRECTION, UplinkTestKind.fromNumber(3)!!.method)
        assertEquals(UplinkMethod.SPEAKER, UplinkTestKind.fromNumber(4)!!.method)
        assertNull(UplinkTestRules.refusal(UplinkTestKind.DIRECT_MUTED, inCall))
        assertNull(UplinkTestRules.refusal(UplinkTestKind.BASELINE, normal))
        assertNull(UplinkTestRules.refusal(UplinkTestKind.SPEAKER, null))
        assertNotNull(UplinkTestRules.refusal(UplinkTestKind.REDIRECTION_MUTED, null))
    }

    // --- Failures ---------------------------------------------------------------------------------

    @Test
    fun aTrackThatCannotBeMadeIsRecordedWithClassAndMessage() {
        val env = FakeEnv(openFailure = UnsupportedOperationException(" PSTN Call audio not accessible "))
        val sink = Sink()
        UplinkTestRun(request(3), env, sink, redirectionRun = 1).run()
        assertTrue(sink.last, sink.last.contains("作れなかった: UnsupportedOperationException:  PSTN Call audio not accessible"))
        assertTrue(sink.last.contains("止まった理由: ${UplinkTestRun.REASON_OPEN_FAILED}"))
        assertEquals(listOf(UplinkPhase.ENDED), sink.phases)
    }

    @Test
    fun reflectionErrorsShowTheRealCause() {
        val env = FakeEnv(openFailure = InvocationTargetException(SecurityException("CALL_AUDIO_INTERCEPTION is needed")))
        val sink = Sink()
        UplinkTestRun(request(3), env, sink, redirectionRun = 1).run()
        assertTrue(sink.last, sink.last.contains("SecurityException: CALL_AUDIO_INTERCEPTION is needed"))
        assertFalse(sink.last.contains("InvocationTargetException"))
    }

    @Test
    fun aRefusedOutputPlaysNothing() {
        val env = FakeEnv(accepted = false)
        val sink = Sink()
        UplinkTestRun(request(1), env, sink).run()
        val out = env.output!!
        assertFalse(out.played)
        assertEquals(1, out.closed)
        assertTrue(sink.last, sink.last.contains("TELEPHONY(18) → false"))
        assertTrue(sink.last.contains("にできなかったので、鳴らさない"))
    }

    @Test
    fun aWriteErrorEndsTheTest() {
        val env = FakeEnv()
        val sink = Sink()
        // The system releases the track after 10 writes (test 3 when the call ends).
        env.onOpen = { out -> out.onWrite = { count -> if (out.writes > 10) -3 else count } }
        UplinkTestRun(request(3), env, sink, redirectionRun = 1).run()
        val out = env.output!!
        assertEquals(1, out.closed)
        assertTrue(sink.last, sink.last.contains("書き込みの失敗（ERROR_INVALID_OPERATION）"))
    }

    // --- The record -------------------------------------------------------------------------------

    @Test
    fun theRecordHoldsWhatThePlanAsksFor() {
        val env = FakeEnv()
        val sink = Sink()
        UplinkTestRun(request(3), env, sink, redirectionRun = 2).run()
        val log = sink.last
        assertTrue(log, log.startsWith("${UplinkReport.TITLE} 試験3: redirection"))
        assertTrue(log.contains("頼んだ時刻: 21:00:00"))
        assertTrue(log.contains("始めた時刻: 21:00:00"))
        assertTrue(log.contains("止めた時刻: 21:00:00"))
        assertTrue(log.contains("音声のモード: MODE_IN_CALL (2)"))
        assertTrue(log.contains("isPstnCallAudioInterceptable: true"))
        assertTrue(log.contains("試験3 の回数（この手伝い役で）: 2 回目"))
        assertTrue(log.contains("出力先 getRoutedDevice() = TELEPHONY(18)"))
        assertTrue(log.contains("名義: 渡せない"))
        assertTrue(log.contains("マイクのミュート: はい（手順: ミュートする）"))
        assertFalse(log.contains(UplinkReport.CHECK_MARK))
    }

    @Test
    fun theShellIdentityIsShownForTheTracksTheHelperMakes() {
        val env = FakeEnv()
        val sink = Sink()
        UplinkTestRun(request(1), env, sink).run()
        assertTrue(sink.last, sink.last.contains("名義: package=com.android.shell uid=2000 → shell"))
        assertTrue(sink.last.contains("出力先の指定 setPreferredDevice: TELEPHONY(18) → true"))
    }

    @Test
    fun whatDiffersFromTheProcedureIsMarked() {
        // Mute forgotten in test 1, and the track went to the earpiece instead of TELEPHONY.
        val env = FakeEnv(micMuted = false, route = { 1 })
        val sink = Sink()
        UplinkTestRun(request(1), env, sink).run()
        assertTrue(sink.last, sink.last.contains("マイクのミュート: いいえ（手順: ミュートする） ${UplinkReport.CHECK_MARK} 手順と違う"))
        assertTrue(sink.last.contains("出力先 getRoutedDevice() = BUILTIN_EARPIECE(1)  ${UplinkReport.CHECK_MARK} 指定と違う"))
    }

    @Test
    fun aRouteChangeWhilePlayingIsRecorded() {
        val env = FakeEnv(route = { elapsed -> if (elapsed < 5_000) 18 else 1 })
        val sink = Sink()
        UplinkTestRun(request(2), env, sink).run()
        assertTrue(sink.last, sink.last.contains("出力先が変わった: TELEPHONY(18) → BUILTIN_EARPIECE(1)"))
    }

    @Test
    fun aRouteThatNeverComesIsRecordedAsNone() {
        val env = FakeEnv(route = { null })
        val sink = Sink()
        UplinkTestRun(request(2), env, sink).run()
        assertTrue(sink.last, sink.last.contains("出力先 getRoutedDevice() = なし（決まっていない）"))
    }

    @Test
    fun theRecordNeverHoldsAPhoneNumber() {
        val env = FakeEnv(openFailure = IllegalStateException("cannot route to 090-1234-5678 or +81 90 1234 5678"))
        val sink = Sink()
        UplinkTestRun(request(1), env, sink).run()
        assertTrue(sink.last, sink.last.contains(ReportText.NUMBER_MASK))
        assertFalse(sink.last.contains("1234"))
        assertNull(phoneLike.find(sink.last))
        // A whole test that played to the end has nothing number-like either.
        val ok = Sink()
        UplinkTestRun(request(3, delay = 5), FakeEnv(), ok, redirectionRun = 1).run()
        assertNull(ok.last, phoneLike.find(ok.last))
        // The refusal record, too, even with an absurd test number.
        val refused = UplinkReport.refusedText(null, "21:00:00", "試験の番号が違う（123456789012）")
        assertNull(refused, phoneLike.find(refused))
    }

    @Test
    fun refusedRecordHasTheHeaderAndTheReason() {
        val text = UplinkReport.refusedText(request(1), "21:00:00", "Android 14（API 34）以上が要る")
        assertTrue(text, text.startsWith("${UplinkReport.TITLE} 試験1: direct"))
        assertTrue(text.endsWith("止まった理由: Android 14（API 34）以上が要る"))
        assertTrue(UplinkReport.refusedText(null, "21:00:00", "x").contains("試験の番号が違う"))
    }
}
