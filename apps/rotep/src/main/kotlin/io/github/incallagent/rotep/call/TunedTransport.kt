// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import io.github.incallagent.agent.live.LiveTransport
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [LiveTransport] that passes the first message through [tune] and sends everything after it
 * untouched. LiveSession's first message is always the setup; the later ones include audio every
 * 20 ms, which must not be parsed. If [tune] fails, the setup does not go out at all: it would lack
 * the app's safety rules, and the call is better handed to the user than to that AI.
 */
class TunedTransport(
    private val inner: LiveTransport,
    private val tune: (String) -> String,
) : LiveTransport {
    private val first = AtomicBoolean(true)

    override fun send(text: String): Boolean {
        if (!first.getAndSet(false)) return inner.send(text)
        val tuned = runCatching { tune(text) }.getOrNull()
        if (tuned == null) {
            inner.close(CANNOT_TUNE, "setup not ready")
            return false
        }
        return inner.send(tuned)
    }

    override fun close(code: Int, reason: String) = inner.close(code, reason)

    private companion object {
        /** WebSocket close code: the endpoint hit an internal error. */
        const val CANNOT_TUNE = 1011
    }
}
