// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

/**
 * A step that runs until it has gone through once. A step that threw has not happened: the next caller
 * runs it again, instead of going on with what was only half set up.
 */
internal class OnceDone {
    @Volatile
    private var done = false

    @Synchronized
    fun run(step: () -> Unit) {
        if (done) return
        step()
        done = true
    }
}
