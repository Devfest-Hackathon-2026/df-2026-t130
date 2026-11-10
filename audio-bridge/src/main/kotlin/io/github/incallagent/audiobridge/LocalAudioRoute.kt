// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.audiobridge

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Audio mode and output for trying the speaking agent without a call.
 * MODE_IN_COMMUNICATION enables the platform echo canceller path; the output is the
 * loudspeaker (so another person can hear) or the earpiece/headset.
 */
class LocalAudioRoute(context: Context, private val log: BridgeLog) {
    private val am = context.getSystemService(AudioManager::class.java)
    private var previousMode: Int? = null

    fun enter(loudspeaker: Boolean) {
        if (previousMode == null) previousMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (loudspeaker) {
            val speaker = am.availableCommunicationDevices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val ok = speaker != null && am.setCommunicationDevice(speaker)
            log.log("route: mode=${AudioNames.mode(am.mode)} loudspeaker=$ok")
        } else {
            am.clearCommunicationDevice()
            log.log("route: mode=${AudioNames.mode(am.mode)} device=${AudioNames.device(am.communicationDevice)}")
        }
    }

    fun exit() {
        am.clearCommunicationDevice()
        previousMode?.let { am.mode = it }
        previousMode = null
        log.log("route: restored mode=${AudioNames.mode(am.mode)}")
    }
}
