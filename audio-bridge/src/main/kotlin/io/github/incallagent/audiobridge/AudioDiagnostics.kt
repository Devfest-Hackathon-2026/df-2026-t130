// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.audiobridge

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Audio-side facts that decide which uplink method can work on this device.
 * Product names of devices are omitted on purpose (Bluetooth names can contain a person's name).
 */
object AudioDiagnostics {
    fun collect(context: Context): List<Pair<String, String>> {
        val am = context.getSystemService(AudioManager::class.java)
        val out = ArrayList<Pair<String, String>>()

        out += "audio.mode" to AudioNames.mode(am.mode)
        out += "audio.isMicrophoneMute" to am.isMicrophoneMute.toString()
        out += "audio.isCallScreeningModeSupported" to runCatching { am.isCallScreeningModeSupported.toString() }
            .getOrElse { HiddenApis.describe(it) }
        out += "audio.isPstnCallAudioInterceptable" to runCatching { HiddenApis.isPstnCallAudioInterceptable(am).toString() }
            .getOrElse { HiddenApis.describe(it) }
        out += "audio.hiddenApiExemption" to HiddenApis.exemptionResult.toString()
        out += "audio.property.outputSampleRate" to
            (am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "<null>")
        out += "audio.property.outputFramesPerBuffer" to
            (am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "<null>")

        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        out += "audio.hasTelephonyOutput" to outputs.any { it.type == AudioDeviceInfo.TYPE_TELEPHONY }.toString()
        out += "audio.hasTelephonyInput" to inputs.any { it.type == AudioDeviceInfo.TYPE_TELEPHONY }.toString()
        outputs.forEachIndexed { i, d -> out += "audio.output[$i]" to describe(d) }
        inputs.forEachIndexed { i, d -> out += "audio.input[$i]" to describe(d) }

        return out
    }

    private fun describe(d: AudioDeviceInfo): String = buildString {
        append(AudioNames.device(d))
        append(" rates=")
        append(if (d.sampleRates.isEmpty()) "any" else d.sampleRates.joinToString(","))
        append(" channels=")
        append(if (d.channelCounts.isEmpty()) "any" else d.channelCounts.joinToString(","))
        if (d.type == AudioDeviceInfo.TYPE_TELEPHONY) {
            append(" address=").append(d.address.ifEmpty { "<empty>" })
        }
    }
}
