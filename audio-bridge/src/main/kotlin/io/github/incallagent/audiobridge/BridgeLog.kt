// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.audiobridge

/**
 * Logging sink supplied by the caller. audio-bridge does not depend on any other module
 * (it is meant to be split into its own repository), so it does not use :core logging.
 * Messages produced here never contain phone numbers or names.
 */
fun interface BridgeLog {
    fun log(message: String)
}
