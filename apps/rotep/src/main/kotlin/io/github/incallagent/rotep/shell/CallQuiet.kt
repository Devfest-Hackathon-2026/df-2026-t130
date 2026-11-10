// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager

/** The call's playback on this phone: the earpiece, the loudspeaker, a headset. */
internal interface VoiceCallStream {
    fun isMuted(): Boolean

    /** The volume step. The system answers 0 while the stream is muted. */
    fun index(): Int

    fun mute()

    fun unmute()

    fun setIndex(index: Int)
}

/**
 * Keeps the phone from playing a call while the AI has it, so the user does not hear the shop and
 * the AI. Runs in the helper: muting STREAM_VOICE_CALL needs MODIFY_PHONE_STATE, which the shell
 * user has and an app does not (AudioService.adjustStreamVolume).
 *
 * Only the mute flag is set: the volume step stays as the user left it, so taking the mute off is
 * all that is needed (steps belong to the output in use, so writing one back after the call could
 * change another output's volume). The system does not take it off by itself when a call ends, and does not
 * notice when the helper dies, so every way the AI's part can end has to call [release] (see
 * HelperService). Telecom makes a call audible when it goes active (ensureCallAudible: a muted
 * stream reads as volume 0), so [engage] has to come a moment after the answer.
 *
 * It never takes over a mute it did not set: if the stream is already muted, [engage] leaves it
 * and [release] does nothing.
 */
internal class CallQuiet(
    private val stream: VoiceCallStream,
    private val note: (String) -> Unit,
) {
    private var mutedByUs = false
    private var closed = false
    private var savedIndex = 0

    /** For the tests. */
    val engaged: Boolean @Synchronized get() = mutedByUs

    /** true when the call is now kept quiet by this object. After [release] it does nothing. */
    @Synchronized
    fun engage(): Boolean {
        if (closed || mutedByUs) return mutedByUs
        return try {
            if (stream.isMuted()) {
                note("call quiet: already muted, left as it is")
                return false
            }
            savedIndex = stream.index()
            stream.mute()
            if (!stream.isMuted()) {
                // The system ignores the request without an error when it does not accept the caller
                note("call quiet: the mute did not take")
                return false
            }
            mutedByUs = true
            note("call quiet: on")
            true
        } catch (t: Throwable) {
            note("call quiet: could not mute (${t.javaClass.simpleName})")
            false
        }
    }

    /** Gives the call its sound back. Safe to call more than once and from any thread; a later [engage] does nothing. */
    @Synchronized
    fun release() {
        closed = true
        if (!mutedByUs) return
        mutedByUs = false
        try {
            stream.unmute()
            // Only when the unmute did not take: a step that is not 0 also takes the mute off
            if (stream.isMuted() && savedIndex > 0) stream.setIndex(savedIndex)
            note(if (stream.isMuted()) "call quiet: still muted after the release" else "call quiet: off")
        } catch (t: Throwable) {
            note("call quiet: could not unmute (${t.javaClass.simpleName})")
        }
    }
}

/**
 * One event of the audio bridge, as the helper passes it on. The report goes first: the pipes are
 * closed by now, and the app takes a pipe that ends without a report for a dead helper, however
 * long the system takes to give the sound back. However the bridge ended, the call (if it goes on)
 * is the user's again, also when the report cannot be sent.
 */
internal fun reportThenGiveSoundBack(code: Int, report: () -> Unit, giveSoundBack: () -> Unit) {
    try {
        report()
    } finally {
        if (code != AudioBridgeEvent.STARTED) giveSoundBack()
    }
}

/**
 * The safeguard for [CallQuiet]: on some phones muting the call could also silence the downlink
 * that is captured for the AI (whether it does is up to the audio HAL). Then the AI would hear
 * nothing, and on one reported phone the reads stopped and the call was cut about 2 s later. This
 * watches the capture and calls [onSilent] once when, after the mute, reads bring nothing for
 * [NO_DATA_LIMIT_MS], or the audio stays digital silence (every sample exactly 0):
 * [HEARD_LIMIT_MS] when real audio came before the mute, [NOT_HEARD_LIMIT_MS] when none did (a line
 * can be silent for a while). A wrong call only means the user hears the call, so it errs towards
 * giving the sound back.
 */
internal class SilentDownlinkWatch(
    private val nowMs: () -> Long,
    private val onSilent: () -> Unit,
) {
    @Volatile private var armed = false
    @Volatile private var heardBefore = false
    private var zeroSinceMs = -1L
    private var noDataSinceMs = -1L

    /** The mute is on: from now on, digital silence counts. */
    fun arm() {
        armed = true
    }

    /** For the tests. */
    fun disarm() {
        armed = false
    }

    /** Called on the capture thread with each read. [frames] 0: the read brought nothing. */
    fun feed(pcm: ShortArray, frames: Int) {
        if (frames <= 0) {
            if (!armed) return
            val now = nowMs()
            if (noDataSinceMs < 0) noDataSinceMs = now
            if (now - noDataSinceMs >= NO_DATA_LIMIT_MS) giveBack()
            return
        }
        noDataSinceMs = -1L
        var allZero = true
        for (i in 0 until frames) {
            if (pcm[i] != 0.toShort()) {
                allZero = false
                break
            }
        }
        if (!armed) {
            if (!allZero) heardBefore = true
            zeroSinceMs = -1L
            return
        }
        if (!allZero) {
            zeroSinceMs = -1L
            return
        }
        val now = nowMs()
        if (zeroSinceMs < 0) zeroSinceMs = now
        val limit = if (heardBefore) HEARD_LIMIT_MS else NOT_HEARD_LIMIT_MS
        if (now - zeroSinceMs >= limit) giveBack()
    }

    private fun giveBack() {
        armed = false
        onSilent()
    }

    companion object {
        const val NO_DATA_LIMIT_MS = 800L
        const val HEARD_LIMIT_MS = 2_500L
        const val NOT_HEARD_LIMIT_MS = 8_000L
    }
}

/** [VoiceCallStream] on an AudioManager. Every call runs as the helper, also from an AIDL method. */
internal class AudioManagerVoiceCall(private val audio: AudioManager) : VoiceCallStream {
    override fun isMuted(): Boolean = asSelf { audio.isStreamMute(STREAM) }

    override fun index(): Int = asSelf { audio.getStreamVolume(STREAM) }

    override fun mute() = asSelf { audio.adjustStreamVolume(STREAM, AudioManager.ADJUST_MUTE, 0) }

    override fun unmute() = asSelf { audio.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0) }

    override fun setIndex(index: Int) = asSelf { audio.setStreamVolume(STREAM, index, 0) }

    companion object {
        private const val STREAM = AudioManager.STREAM_VOICE_CALL

        /**
         * An AudioManager that names the shell package. AudioService checks the caller's package
         * against its uid (AppOps) and quietly ignores the volume change when they differ; the
         * helper's own AudioManager would name the system Context's package ("android").
         */
        // The constructor is hidden. This code runs only in the helper, a bare app_process of the
        // shell user, where the hidden-API enforcement is off (see HelperContext).
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi")
        fun audioManagerFor(shell: ShellContext?, fallback: AudioManager): AudioManager {
            if (shell == null) return fallback
            return attempt {
                AudioManager::class.java.getDeclaredConstructor(Context::class.java).newInstance(shell)
            } ?: fallback
        }
    }
}
