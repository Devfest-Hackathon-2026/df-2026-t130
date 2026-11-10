// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.audiobridge

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException

/**
 * Access to non-SDK (@SystemApi) audio APIs through AndroidHiddenApiBypass (ADR-009).
 *
 * A priv-app that is not platform-signed is still subject to the non-SDK API restrictions,
 * so a plain reflection call would fail with NoSuchMethodException.
 */
object HiddenApis {
    @Volatile
    private var exempted: Boolean? = null

    /** Call once from Application.onCreate. Returns whether the exemption was applied. */
    fun exemptAll(): Boolean {
        val result = try {
            HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (e: Throwable) {
            false
        }
        exempted = result
        return result
    }

    val exemptionResult: Boolean? get() = exempted

    /** `AudioManager#isPstnCallAudioInterceptable()` (@SystemApi, API 31, CALL_AUDIO_INTERCEPTION). */
    fun isPstnCallAudioInterceptable(am: AudioManager): Boolean =
        invoke(am, "isPstnCallAudioInterceptable") as Boolean

    /** `AudioManager#getCallUplinkInjectionAudioTrack(AudioFormat)` (@SystemApi, API 31). */
    fun getCallUplinkInjectionAudioTrack(am: AudioManager, format: AudioFormat): AudioTrack =
        invoke(am, "getCallUplinkInjectionAudioTrack", format) as AudioTrack

    /** `AudioManager#getCallDownlinkExtractionAudioRecord(AudioFormat)` (@SystemApi, API 31). */
    fun getCallDownlinkExtractionAudioRecord(am: AudioManager, format: AudioFormat): AudioRecord =
        invoke(am, "getCallDownlinkExtractionAudioRecord", format) as AudioRecord

    private fun invoke(am: AudioManager, name: String, vararg args: Any): Any? = try {
        HiddenApiBypass.invoke(AudioManager::class.java, am, name, *args)
    } catch (e: InvocationTargetException) {
        // Surface the real exception (SecurityException, UnsupportedOperationException, ...)
        throw e.targetException ?: e
    }

    /** "ClassName: message" for logs, unwrapping reflection wrappers. */
    fun describe(t: Throwable): String {
        val real = if (t is InvocationTargetException) t.targetException ?: t else t
        return "${real.javaClass.name}: ${real.message}"
    }
}
