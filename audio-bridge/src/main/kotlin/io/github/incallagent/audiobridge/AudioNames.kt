// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.audiobridge

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import java.lang.reflect.Modifier

/** Human-readable names for audio constants, for logs and diagnostics. */
object AudioNames {
    private val deviceTypes: Map<Int, String> by lazy { intConstants(AudioDeviceInfo::class.java, "TYPE_") }

    fun deviceType(type: Int): String = "${deviceTypes[type] ?: "TYPE_?"}($type)"

    fun device(info: AudioDeviceInfo?): String =
        if (info == null) "<none>" else "${deviceType(info.type)}#${info.id}"

    fun mode(mode: Int): String = when (mode) {
        AudioManager.MODE_INVALID -> "MODE_INVALID"
        AudioManager.MODE_CURRENT -> "MODE_CURRENT"
        AudioManager.MODE_NORMAL -> "MODE_NORMAL"
        AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
        AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
        AudioManager.MODE_CALL_SCREENING -> "MODE_CALL_SCREENING"
        AudioManager.MODE_CALL_REDIRECT -> "MODE_CALL_REDIRECT"
        AudioManager.MODE_COMMUNICATION_REDIRECT -> "MODE_COMMUNICATION_REDIRECT"
        else -> "MODE_?"
    } + "($mode)"

    fun audioSource(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
        MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
        MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        else -> "SOURCE_?"
    } + "($source)"

    private fun intConstants(cls: Class<*>, prefix: String): Map<Int, String> {
        val map = HashMap<Int, String>()
        for (f in cls.fields) {
            if (f.type == Int::class.javaPrimitiveType && Modifier.isStatic(f.modifiers) &&
                f.name.startsWith(prefix)) {
                // Keep the first name for aliased values
                map.putIfAbsent(f.getInt(null), f.name)
            }
        }
        return map
    }
}
