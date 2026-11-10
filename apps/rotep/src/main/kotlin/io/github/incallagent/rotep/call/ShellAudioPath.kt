// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import android.os.ParcelFileDescriptor
import android.os.Process
import io.github.incallagent.audiobridge.BridgeLog
import io.github.incallagent.rotep.shell.AudioBridgeEvent
import io.github.incallagent.rotep.shell.AudioPipe
import io.github.incallagent.rotep.shell.HelperClient
import io.github.incallagent.rotep.shell.UplinkFeeder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Translates one audio-bridge event code (IHelperCallback.onAudioBridge) into the path's event.
 * Pure: no Android class is used, so this one function is checked on a PC without the rest.
 */
internal fun audioBridgeEvent(code: Int, detail: String): AudioPathEvent = when (code) {
    AudioBridgeEvent.STARTED -> AudioPathEvent.Started(detail)
    AudioBridgeEvent.STOPPED -> AudioPathEvent.Stopped(detail)
    AudioBridgeEvent.SILENCED -> AudioPathEvent.Silenced
    else -> AudioPathEvent.Failed(detail.ifEmpty { "code $code" })
}

/**
 * Every real call: the helper (a process that runs as the shell user) captures the
 * downlink and plays the agent's voice into the call by [mode] (shell/CallAudioBridge.kt does the
 * capture, the playback and the resampling). This class is the thin app-side half: it opens the
 * two pipes, asks [HelperClient]
 * to start the bridge, and runs one thread that forwards the downlink and one that sends the
 * agent's voice ([UplinkFeeder] decides what goes out and when).
 *
 * Every way this path can end (stop(), the helper's report, the pipe ending, a failed start)
 * goes through [release], so the listener gets exactly one terminal event.
 */
class ShellAudioPath(
    private val mode: ShellMode,
    private val log: BridgeLog,
    /** The phone does not play the call while this path runs (the helper keeps it quiet). */
    private val keepCallQuiet: Boolean = false,
    /** Set with [keepCallQuiet]: brings the sound back from here when the helper cannot. */
    private val soundGuard: CallSoundGuard? = null,
) : CallAudioPath {
    override val name = "shell-${mode.name.lowercase()}"

    @Volatile private var listener: CallAudioPath.Listener? = null
    @Volatile private var running = false
    private val released = AtomicBoolean(false)
    private val ended = CountDownLatch(1)
    private val feeder = if (mode == ShellMode.DIRECT) UplinkFeeder.forTelephonyOutput() else UplinkFeeder()

    @Volatile private var downlinkReadLocal: ParcelFileDescriptor? = null
    @Volatile private var uplinkWriteLocal: ParcelFileDescriptor? = null

    private val bridgeListener = HelperClient.BridgeListener { code, detail ->
        if (code == AudioBridgeEvent.STARTED) {
            if (!released.get()) listener?.onEvent(AudioPathEvent.Started("方式 ${mode.name.lowercase()}、受話 16 kHz、AI の声 24 kHz"))
        } else if (release()) {
            listener?.onEvent(audioBridgeEvent(code, detail))
        }
    }

    @Synchronized
    override fun start(listener: CallAudioPath.Listener) {
        if (running || released.get()) return
        this.listener = listener
        val downlinkPipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (t: Throwable) {
            log.log("shell-audio: no pipe (${t.javaClass.simpleName})")
            fail("通話の音声の準備に失敗しました")
            return
        }
        val uplinkPipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (t: Throwable) {
            closeQuietly(downlinkPipe[0])
            closeQuietly(downlinkPipe[1])
            log.log("shell-audio: no pipe (${t.javaClass.simpleName})")
            fail("通話の音声の準備に失敗しました")
            return
        }
        downlinkReadLocal = downlinkPipe[0]
        uplinkWriteLocal = uplinkPipe[1]
        running = true
        // The helper needs a moment to open the audio devices: do not hold the caller for it.
        audioThread("shell-audio-start") { begin(downlinkPipe[0], downlinkPipe[1], uplinkPipe[0], uplinkPipe[1]) }
    }

    override fun play(pcm24k: ByteArray) {
        if (running) feeder.enqueue(pcm24k)
    }

    override fun flush() {
        feeder.flush()
    }

    override fun isPlaying(): Boolean = running && feeder.isPlaying()

    override fun stop() {
        if (release()) listener?.onEvent(AudioPathEvent.Stopped("止めました"))
    }

    override fun restoreCallSound(): Boolean {
        if (!keepCallQuiet) return true
        // Whether the helper could is not in its answer, and a dead one has none: the stream tells
        HelperClient.restoreCallSound()
        return soundGuard?.restoreIfRemembered() ?: true
    }

    private fun begin(downlinkRead: ParcelFileDescriptor, downlinkWrite: ParcelFileDescriptor, uplinkRead: ParcelFileDescriptor, uplinkWrite: ParcelFileDescriptor) {
        val accepted = try {
            if (keepCallQuiet && !released.get()) soundGuard?.remember()
            HelperClient.startAudioBridge(mode.ordinal, keepCallQuiet, downlinkWrite, uplinkRead, bridgeListener)
        } catch (t: Throwable) {
            false
        } finally {
            // Handed to the AIDL call, which duplicates them for the helper. This process's own
            // copies are not needed afterwards, and a copy left open here would keep the far end
            // from ever seeing the pipe closed.
            closeQuietly(downlinkWrite)
            closeQuietly(uplinkRead)
        }
        if (!accepted) {
            fail("手伝い役が動いていないか、別の通話の音声を扱っています")
            return
        }
        if (released.get()) {
            // stop() came while the helper was starting: tell it to go again.
            HelperClient.stopAudioBridge()
            restoreCallSound()
            return
        }
        audioThread("shell-audio-downlink") { readDownlink(downlinkRead) }
        audioThread("shell-audio-uplink") { sendUplink(uplinkWrite) }
    }

    private fun fail(detail: String) {
        if (release()) listener?.onEvent(AudioPathEvent.Failed(detail))
    }

    /** true only the first time: the one place every way of ending goes through. */
    private fun release(): Boolean {
        if (!released.compareAndSet(false, true)) return false
        running = false
        feeder.flush()
        HelperClient.stopAudioBridge()
        // The helper gives the sound back when the bridge ends. Asking it is what tells a live
        // helper from a dead one (the pipe can close before the app hears of the death). Not on
        // this thread: release() also runs on the helper's callback.
        if (keepCallQuiet) thread(name = "ShellAudioPath-sound", isDaemon = true) { restoreCallSound() }
        closeQuietly(downlinkReadLocal)
        closeQuietly(uplinkWriteLocal)
        downlinkReadLocal = null
        uplinkWriteLocal = null
        ended.countDown()
        return true
    }

    private fun readDownlink(readEnd: ParcelFileDescriptor) {
        val input = ParcelFileDescriptor.AutoCloseInputStream(readEnd)
        var failure: Throwable? = null
        try {
            while (running) {
                val message = AudioPipe.readMessage(input) ?: break
                if (message is AudioPipe.Message.Chunk && running) listener?.onDownlink(message.pcm)
            }
        } catch (t: Throwable) {
            failure = t
        } finally {
            runCatching { input.close() }
        }
        if (!running) return
        if (failure != null) {
            log.log("shell-audio: downlink read failed (${failure.javaClass.simpleName})")
            fail("相手の声を受け取れなくなりました")
            return
        }
        // The pipe ended while this path still runs. The helper normally says why through the
        // callback, and the pipe closing can get here first: give the report a moment before guessing.
        if (!ended.await(END_REPORT_GRACE_MS, TimeUnit.MILLISECONDS) && release()) {
            listener?.onEvent(AudioPathEvent.HelperDied)
        }
    }

    private fun sendUplink(writeEnd: ParcelFileDescriptor) {
        val output = ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)
        try {
            while (running) {
                when (val message = feeder.next(POLL_MS) ?: continue) {
                    AudioPipe.Message.Flush -> AudioPipe.writeFlush(output)
                    is AudioPipe.Message.Chunk -> AudioPipe.writeChunk(output, message.pcm)
                }
            }
        } catch (t: Throwable) {
            // After release() the pipe is closed on purpose, and a write that fails then is expected.
            // The helper closes the pipe just before it says why the bridge ended, and with the
            // keep-alive a write is always on its way: give that report a moment, as readDownlink does.
            if (running && !ended.await(END_REPORT_GRACE_MS, TimeUnit.MILLISECONDS)) {
                log.log("shell-audio: uplink write failed (${t.javaClass.simpleName})")
                fail("AI の声を送れなくなりました")
            }
        } finally {
            runCatching { output.close() }
        }
    }

    private fun audioThread(name: String, body: () -> Unit) {
        val t = Thread({
            // The downlink callback and the sending are audio work: an audio priority keeps a busy
            // moment of the app from showing up as a gap in the call.
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            body()
        }, name)
        t.isDaemon = true
        t.start()
    }

    private fun closeQuietly(pfd: ParcelFileDescriptor?) {
        if (pfd != null) runCatching { pfd.close() }
    }

    private companion object {
        const val POLL_MS = 50L

        /** How long the pipe ending is held back for the helper's own report to arrive. */
        const val END_REPORT_GRACE_MS = 500L
    }
}
