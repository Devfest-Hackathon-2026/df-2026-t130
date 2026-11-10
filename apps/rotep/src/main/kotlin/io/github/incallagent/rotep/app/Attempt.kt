// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

/** [step], whose failure stays here: the steps after it still happen. [log] gets [what] and the exception's class only. */
internal inline fun attempt(what: String, log: (String) -> Unit, step: () -> Unit) {
    try {
        step()
    } catch (t: Throwable) {
        log("$what failed: ${t.javaClass.simpleName}")
    }
}
