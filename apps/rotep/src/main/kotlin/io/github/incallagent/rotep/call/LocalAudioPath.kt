// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import io.github.incallagent.audiobridge.BridgeLog
import io.github.incallagent.audiobridge.LocalAudioRoute
import io.github.incallagent.audiobridge.LocalMic
import io.github.incallagent.audiobridge.LocalSpeaker
import kotlin.concurrent.thread

/**
 * The practice: the phone's own microphone (16 kHz, 20 ms chunks) and speaker (24 kHz), no call.
 * audio-bridge's LocalMic, LocalSpeaker and LocalAudioRoute (MODE_IN_COMMUNICATION, echo
 * canceller, loudspeaker or earpiece).
 *
 * A microphone that cannot start or stops reading (LocalMic's onError), and a speaker that
 * cannot play any more (LocalSpeaker's onError), are reported as [AudioPathEvent.Failed] at once,
 * also in the middle of the conversation. A microphone that opened but gives no audio within
 * [NO_AUDIO_MS] after [start] is reported the same way.
 */
class LocalAudioPath(
    context: Context,
    private val loudspeaker: Boolean,
    private val log: BridgeLog,
) : CallAudioPath {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override val name = NAME

    @Volatile private var listener: CallAudioPath.Listener? = null
    @Volatile private var running = false
    @Volatile private var chunks = 0L
    private var mic: LocalMic? = null
    private var speaker: LocalSpeaker? = null
    private var route: LocalAudioRoute? = null
    private var started = false
    private var released = false

    @Synchronized
    override fun start(listener: CallAudioPath.Listener) {
        if (started) return
        started = true
        this.listener = listener
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(NO_MIC_PERMISSION)
            return
        }
        try {
            route = LocalAudioRoute(appContext, log).also { it.enter(loudspeaker) }
            speaker = LocalSpeaker(
                log,
                // Called on the speaker thread; LocalSpeaker.stop() does not wait for its own thread.
                // Without this, the AI's voice would be dropped and the screen would not say why.
                onError = { detail -> fail("スピーカーが使えません（$detail）") },
            ).also { it.start() }
            running = true
            mic = LocalMic(
                log,
                chunkMs = CHUNK_MS,
                // Called on the mic thread (LocalMic.stop() does not wait for its own thread)
                onError = { detail -> fail("マイクが使えません（$detail）") },
            ) { pcm, _ ->
                chunks++
                if (running) this.listener?.onDownlink(pcm)
            }.also { it.start() }
        } catch (t: Throwable) {
            fail("始められませんでした（${t.javaClass.simpleName}）")
            return
        }
        listener.onEvent(AudioPathEvent.Started("マイク 16 kHz、${if (loudspeaker) "スピーカー" else "受話口"} 24 kHz"))
        main.postDelayed({
            // fail() waits for the audio threads to end (up to a second each): not on the main thread
            if (running && chunks == 0L) {
                thread(name = "LocalAudioPath-watchdog") { fail("マイクの音が ${NO_AUDIO_MS / 1000.0} 秒来ません") }
            }
        }, NO_AUDIO_MS)
    }

    override fun play(pcm24k: ByteArray) {
        if (running) speaker?.enqueue(pcm24k)
    }

    override fun flush() {
        speaker?.flush()
    }

    override fun isPlaying(): Boolean = running && speaker?.isPlaying() == true

    override fun stop() {
        if (release()) listener?.onEvent(AudioPathEvent.Stopped("止めました"))
    }

    private fun fail(detail: String) {
        if (release()) listener?.onEvent(AudioPathEvent.Failed(detail))
    }

    /** true only the first time. */
    @Synchronized
    private fun release(): Boolean {
        if (released) return false
        released = true
        running = false
        main.removeCallbacksAndMessages(null)
        runCatching { mic?.stop() }
        runCatching { speaker?.stop() }
        runCatching { route?.exit() }
        mic = null
        speaker = null
        route = null
        return true
    }

    companion object {
        const val CHUNK_MS = 20
        const val NO_AUDIO_MS = 1_500L
        const val NAME = "local"

        /** [AudioPathEvent.Failed] detail without RECORD_AUDIO (UserMessages tells what to do). */
        const val NO_MIC_PERMISSION = "マイクの許可がありません"
    }
}
