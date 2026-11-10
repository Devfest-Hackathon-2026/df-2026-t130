// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.audiobridge

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The phone's own microphone, for trying the speaking agent without a call
 * (decisions.md "通話なしで試す仕組み"). Later the call downlink takes its place.
 *
 * VOICE_COMMUNICATION + AcousticEchoCanceler so the model does not hear its own voice from the
 * loudspeaker. Delivers 16-bit little-endian mono PCM chunks on a URGENT_AUDIO thread.
 */
class LocalMic(
    private val log: BridgeLog,
    val sampleRate: Int = 16_000,
    val chunkMs: Int = 40,
    /** Called once from the mic thread when the microphone cannot start or stops reading. */
    private val onError: ((detail: String) -> Unit)? = null,
    private val onChunk: (pcm16le: ByteArray, peak: Int) -> Unit,
) {
    @Volatile private var cancelled = false
    private val thread = Thread(::run, "LocalMic")

    fun start() = thread.start()

    fun stop(timeoutMs: Long = 1_000) {
        cancelled = true
        if (Thread.currentThread() != thread) thread.join(timeoutMs)
    }

    private fun fail(detail: String) {
        log.log("mic: $detail")
        if (!cancelled) onError?.invoke(detail)
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the caller
    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            fail("getMinBufferSize=$minBuf")
            return
        }
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        } catch (t: Throwable) {
            fail("AudioRecord failed: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            fail("AudioRecord not initialized")
            record.release()
            return
        }
        AudioResources.recordCreated()
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        val samples = ShortArray(sampleRate * chunkMs / 1000)
        try {
            aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId) else null
            ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId) else null
            aec?.enabled = true
            ns?.enabled = true
            log.log("mic: started rate=$sampleRate chunk=${chunkMs}ms echoCanceler=${aec?.enabled ?: "unavailable"} " +
                "noiseSuppressor=${ns?.enabled ?: "unavailable"}")

            record.startRecording()
            // A microphone that did not start returns nothing from read(): say so instead of waiting
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                fail("not recording (recordingState=${record.recordingState})")
                return
            }
            while (!cancelled) {
                val n = record.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    fail("read error $n")
                    break
                }
                if (n == 0) continue
                var peak = 0
                val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) {
                    val s = samples[i]
                    bytes.putShort(s)
                    val a = abs(s.toInt())
                    if (a > peak) peak = a
                }
                onChunk(bytes.array(), peak)
            }
        } catch (t: Throwable) {
            // From the platform or from onChunk: uncaught here, it would end the whole app
            fail("${t.javaClass.simpleName}: ${t.message}")
        } finally {
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
            aec?.release()
            ns?.release()
            record.release()
            AudioResources.recordReleased()
            log.log("mic: stopped")
        }
    }
}
