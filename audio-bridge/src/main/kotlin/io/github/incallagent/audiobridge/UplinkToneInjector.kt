/*
 * SPDX-FileCopyrightText: 2022 Andrew Gunnerson (BCP, method b)
 * SPDX-FileCopyrightText: 2026 incall-agent contributors
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Method (b) follows BCP PlayerThread.playUntilCancelled
 * (https://github.com/chenxiaolong/BCP, commit 57977e5): find the TYPE_TELEPHONY output,
 * build an AudioTrack with USAGE_VOICE_COMMUNICATION and setPreferredDevice() to it.
 * BCP decodes a file; here a generated tone is written instead.
 */

package io.github.incallagent.audiobridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock

/** Ways of putting audio into the call uplink (ADR-003). Method (c) is not available on Pixel. */
enum class UplinkMethod(val label: String) {
    /** (a) AudioManager#getCallUplinkInjectionAudioTrack, needs CALL_AUDIO_INTERCEPTION. */
    INTERCEPTION("a: CALL_AUDIO_INTERCEPTION"),

    /** (b) AudioTrack(USAGE_VOICE_COMMUNICATION) + setPreferredDevice(TYPE_TELEPHONY), BCP. */
    TELEPHONY_OUTPUT("b: TYPE_TELEPHONY (BCP)"),
}

/**
 * Plays a continuous tone into the call uplink on a dedicated URGENT_AUDIO thread until
 * [stop] is called. 16 kHz / 16-bit PCM / mono, 20 ms writes.
 */
class UplinkToneInjector(
    context: Context,
    val method: UplinkMethod,
    private val log: BridgeLog,
    private val listener: Listener,
    val sampleRate: Int = 16_000,
    val frequencyHz: Double = 1_000.0,
    val amplitude: Double = 0.5,
) {
    enum class State { STARTING, PLAYING, FAILED, STOPPED }

    fun interface Listener {
        fun onStateChanged(injector: UplinkToneInjector, state: State, detail: String)
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)

    @Volatile var state: State = State.STARTING
        private set

    /** Device the track is actually routed to (readback, not the requested device). */
    @Volatile var routedDevice: String = "<unknown>"
        private set

    @Volatile private var cancelled = false

    private val thread = Thread(::run, "UplinkToneInjector-${method.name}")

    fun start() {
        thread.start()
    }

    /** Request stop and wait up to [timeoutMs] for the track to be released. */
    fun stop(timeoutMs: Long = 2_000) {
        cancelled = true
        if (Thread.currentThread() != thread) {
            thread.join(timeoutMs)
        }
    }

    val isAlive: Boolean get() = thread.isAlive

    private fun setState(s: State, detail: String) {
        state = s
        log.log("uplink[${method.name}] $s $detail")
        listener.onStateChanged(this, s, detail)
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        setState(State.STARTING, "rate=$sampleRate tone=${frequencyHz}Hz amp=$amplitude")

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val track = try {
            when (method) {
                UplinkMethod.INTERCEPTION -> createInterceptionTrack(format)
                UplinkMethod.TELEPHONY_OUTPUT -> createTelephonyOutputTrack(format)
            }
        } catch (t: Throwable) {
            setState(State.FAILED, "create: ${HiddenApis.describe(t)}")
            return
        }
        AudioResources.trackCreated()

        try {
            track.addOnRoutingChangedListener({ router ->
                routedDevice = AudioNames.device(router.routedDevice)
                log.log("uplink[${method.name}] routing changed: routedDevice=$routedDevice")
            }, null)

            track.play()
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                setState(State.FAILED, "play: playState=${track.playState}")
                return
            }

            playLoop(track)
        } catch (t: Throwable) {
            setState(State.FAILED, "play: ${HiddenApis.describe(t)}")
        } finally {
            try {
                track.stop()
            } catch (_: IllegalStateException) {
            }
            track.release()
            AudioResources.trackReleased()
            if (state != State.FAILED) {
                setState(State.STOPPED, "released")
            }
        }
    }

    private fun createInterceptionTrack(format: AudioFormat): AudioTrack {
        val track = HiddenApis.getCallUplinkInjectionAudioTrack(audioManager, format)
        log.log("uplink[a] track created: format=${track.format} bufferFrames=${track.bufferSizeInFrames}")
        return track
    }

    private fun createTelephonyOutputTrack(format: AudioFormat): AudioTrack {
        val telephonyOutput = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .find { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            ?: throw IllegalStateException("No TYPE_TELEPHONY output device")

        val minBuf = AudioTrack.getMinBufferSize(format.sampleRate, format.channelMask, format.encoding)
        if (minBuf <= 0) {
            throw IllegalStateException("getMinBufferSize failed: $minBuf")
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .build()
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (!track.setPreferredDevice(telephonyOutput)) {
            track.release()
            throw IllegalStateException("setPreferredDevice(TYPE_TELEPHONY) returned false")
        }
        log.log("uplink[b] track created: preferred=${AudioNames.device(telephonyOutput)} " +
            "format=${track.format} minBuf=$minBuf")
        return track
    }

    private fun playLoop(track: AudioTrack) {
        val tone = SineTone(frequencyHz, sampleRate, amplitude)
        val chunk = ShortArray(SineTone.samplesFor(20, sampleRate))
        val startedAt = SystemClock.elapsedRealtime()
        var reportedRouting = false

        setState(State.PLAYING, "started")

        while (!cancelled) {
            tone.fill(chunk)
            val n = track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
            if (n < 0) {
                throw IllegalStateException("AudioTrack.write failed: $n")
            }
            if (!reportedRouting && SystemClock.elapsedRealtime() - startedAt > 300) {
                reportedRouting = true
                routedDevice = AudioNames.device(track.routedDevice)
                log.log("uplink[${method.name}] routedDevice after 300ms: $routedDevice")
            }
        }
    }
}
