// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

/**
 * Where the call audio comes from and where the agent's voice goes.
 *
 * - LocalAudioPath: the phone's own microphone and speaker, no call (the practice)
 * - ShellAudioPath: the helper (shell uid, started from a PC with adb) captures the downlink and
 *   plays the agent's voice into the uplink or on the loudspeaker (every real call)
 *
 * [start], [play], [flush], [isPlaying] and [stop] may be called from any thread. Listener
 * callbacks come from an audio thread: hand the data on quickly and never block.
 */
interface CallAudioPath {
    /** Short name for the log, e.g. "local" or "shell-direct". */
    val name: String

    /** Starts capture and prepares playback. Reports [AudioPathEvent.Started] or [AudioPathEvent.Failed]. */
    fun start(listener: Listener)

    /** Queues agent audio: 24 kHz, 16-bit little-endian, mono. Ignored before [start] and after [stop]. */
    fun play(pcm24k: ByteArray)

    /** Drops queued agent audio that has not been played yet (the agent was interrupted). */
    fun flush()

    /** true while queued agent audio has not finished playing. */
    fun isPlaying(): Boolean

    /** Stops capture and playback and releases everything. Safe to call more than once. */
    fun stop()

    /**
     * The user gets the call: if this path keeps the call quiet on the phone, give it its sound
     * back before returning (the alert that follows plays on the same stream). Safe to call more
     * than once, and after [stop]. false: the sound could not be given back.
     */
    fun restoreCallSound(): Boolean = true

    interface Listener {
        /** Far-end audio (the local microphone in the practice): 16 kHz, 16-bit little-endian, mono, 20 ms per chunk. */
        fun onDownlink(pcm16k: ByteArray)

        fun onEvent(event: AudioPathEvent)
    }
}

/** What happened to an audio path. Texts are for the log and the screen: never a number or a name. */
sealed interface AudioPathEvent {
    /** Capture and playback are running. */
    data class Started(val detail: String = "") : AudioPathEvent

    /** Stopped by [CallAudioPath.stop] or by the platform. */
    data class Stopped(val reason: String) : AudioPathEvent

    /** The helper process died or its connection was lost. The path cannot be used any more. */
    data object HelperDied : AudioPathEvent

    /** The platform silenced the capture (AudioRecordingConfiguration.isClientSilenced). */
    data object Silenced : AudioPathEvent

    /** Could not start, or broke while running. The path cannot be used any more. */
    data class Failed(val detail: String) : AudioPathEvent
}
