// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.audiobridge

/**
 * audio-bridge: call audio capture (downlink) and injection (uplink).
 *
 * This module must not depend on any other incall-agent module, so that it can later be split
 * into its own repository. Logging goes through [BridgeLog].
 */
object AudioBridge
