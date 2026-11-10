// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import android.content.Context
import io.github.incallagent.audiobridge.BridgeLog
import io.github.incallagent.rotep.shell.HelperClient

/** How the helper (a process running as the shell user) sends the agent's voice. */
enum class ShellMode { DIRECT, REDIRECTION, SPEAKER }

/**
 * Where the app gets its [ShellAudioPath], the audio path that goes through the helper.
 *
 * [factory] is what the screens ask before a call ("is the audio path there?") and what the call
 * asks for when it starts. While a helper is connected it is the [ShellAudioPath] factory; while
 * none is, it is null, so the confirm screen says the path is not there yet instead of dialing a
 * call whose voice could not be played. A factory set from outside (a test, or another way of
 * reaching the helper) is returned as it is.
 */
object ShellAudioPaths {
    fun interface Factory {
        /** [keepCallQuiet]: the phone does not play the call while the path runs. */
        fun create(context: Context, mode: ShellMode, log: BridgeLog, keepCallQuiet: Boolean): CallAudioPath
    }

    @Volatile
    private var custom: Factory? = null

    private val helper = Factory { context, mode, log, quiet ->
        ShellAudioPath(mode, log, quiet, if (quiet) CallSoundGuard.create(context) else null)
    }

    var factory: Factory?
        get() = custom ?: if (HelperClient.isHelperRunning) helper else null
        set(value) {
            custom = value
        }
}
