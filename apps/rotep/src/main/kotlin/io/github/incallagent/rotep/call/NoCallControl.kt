// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

/**
 * The practice: no call at all. [dial] reports [CallEvent.Active] at once (the user plays the shop
 * staff at the phone's own microphone), [hangUp] reports [CallEvent.Ended] ("local") once.
 * Nothing is dialed, so the number is not used.
 */
class NoCallControl : CallControl {
    @Volatile private var listener: ((CallEvent) -> Unit)? = null
    private var dialed = false
    private var ended = false

    override fun setListener(listener: (CallEvent) -> Unit) {
        this.listener = listener
    }

    @Synchronized
    override fun dial(number: String) {
        if (dialed) return
        dialed = true
        listener?.invoke(CallEvent.Active)
    }

    @Synchronized
    override fun hangUp() {
        if (ended) return
        ended = true
        listener?.invoke(CallEvent.Ended(DisconnectReasons.LOCAL))
    }

    override fun sendDtmf(digits: String) {}

    override fun setMuted(muted: Boolean) {}

    override fun close() {
        listener = null
    }
}
