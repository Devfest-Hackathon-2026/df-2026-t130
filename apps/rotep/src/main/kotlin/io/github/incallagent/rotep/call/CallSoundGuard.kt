// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import android.content.Context
import android.media.AudioManager
import androidx.core.content.edit

/** The call's playback as the app can see it. */
internal interface CallSoundDevice {
    fun isMuted(): Boolean

    fun step(): Int

    fun setStep(step: Int)

    /** The volume-up key: takes the mute off also where a step does not (a Bluetooth call). */
    fun raise()
}

/** One number kept across restarts of the app. */
internal interface CallSoundMemo {
    var step: Int?
}

/**
 * The app's side of keeping a call quiet: the helper normally gives the call its sound back, but
 * when the helper dies nothing else does, and the system keeps the stream muted. An app cannot
 * mute this stream but it can take the mute off, by setting a volume step that is not 0
 * (AudioService). So the step is written down before a quiet call and set again when the helper
 * could not do it, also at the next start of the app when both had died.
 *
 * It is one number in the app's private preferences: never a number from a call, nothing that
 * identifies anyone.
 */
class CallSoundGuard internal constructor(
    private val device: CallSoundDevice,
    private val memo: CallSoundMemo,
) {
    /** Before a call is kept quiet. Keeps an earlier value: that one is the user's own step. */
    fun remember() {
        if (memo.step != null) return
        val step = runCatching { device.step() }.getOrNull() ?: return
        if (step > 0) memo.step = step
    }

    /**
     * Takes off a mute that was left behind, by setting the step written down. Writes nothing when
     * the call is not muted: the user may have changed the volume since, and steps belong to the
     * output in use. false: it is still muted, or that cannot be told, and the step stays written
     * down for the next try.
     */
    fun restoreIfRemembered(): Boolean {
        val step = memo.step ?: return true
        val muted = runCatching { device.isMuted() }.getOrNull() ?: return false
        if (!muted) {
            forget()
            return true
        }
        runCatching { device.setStep(step) }
        if (runCatching { device.isMuted() }.getOrDefault(true)) {
            runCatching { device.raise() }
            runCatching { device.setStep(step) }
        }
        val stillMuted = runCatching { device.isMuted() }.getOrDefault(true)
        if (!stillMuted) forget()
        return !stillMuted
    }

    /** The sound is back: nothing to do later. */
    private fun forget() {
        memo.step = null
    }

    companion object {
        private const val KEY_STEP = "voice_call_step"
        private const val STREAM = AudioManager.STREAM_VOICE_CALL

        fun create(context: Context): CallSoundGuard {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("call_sound", Context.MODE_PRIVATE)
            val audio = app.getSystemService(AudioManager::class.java)
            val device = object : CallSoundDevice {
                override fun isMuted() = audio.isStreamMute(STREAM)

                override fun step() = audio.getStreamVolume(STREAM)

                override fun setStep(step: Int) = audio.setStreamVolume(STREAM, step, 0)

                override fun raise() = audio.adjustStreamVolume(STREAM, AudioManager.ADJUST_RAISE, 0)
            }
            val memo = object : CallSoundMemo {
                override var step: Int?
                    get() = prefs.getInt(KEY_STEP, 0).takeIf { it > 0 }
                    set(value) = prefs.edit { if (value != null) putInt(KEY_STEP, value) else remove(KEY_STEP) }
            }
            return CallSoundGuard(device, memo)
        }
    }
}
