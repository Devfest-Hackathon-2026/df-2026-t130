// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

/**
 * The identity the audio system must see for a recording made by the helper.
 *
 * The helper runs as the shell user (uid 2000, package "com.android.shell"). The system checks a
 * recording against the package that is named in the attribution source, so that name has to be the
 * package of the uid the process really runs as. The app's own package name would be refused or
 * silenced.
 */
object ShellIdentity {
    const val SHELL_UID = 2000
    const val SHELL_PACKAGE = "com.android.shell"

    /** The package that goes with [uid] in an attribution source, or null when the uid is not supported. */
    fun packageFor(uid: Int): String? = if (uid == SHELL_UID) SHELL_PACKAGE else null

    /** True only for the shell package together with the shell uid. */
    fun isShell(packageName: String?, uid: Int?): Boolean =
        packageName == SHELL_PACKAGE && uid == SHELL_UID
}

/** Who may call the helper. The helper holds shell power, so it only listens to its own app. */
object CallerPolicy {
    /**
     * Allowed: the app that owns the helper ([appUid]) and the helper itself ([ownUid]; the helper
     * calls its own `attach()` after the app has taken it). Nobody else, root included. An unknown app
     * uid (negative) never matches a caller.
     */
    fun isAllowed(callingUid: Int, appUid: Int, ownUid: Int): Boolean =
        (appUid >= 0 && callingUid == appUid) || callingUid == ownUid
}

/** Order in which the helper tries sample rates for the downlink recording. */
object RateCandidates {
    private val FALLBACK = listOf(48000, 44100, 32000, 24000, 16000, 8000)

    /** The device's own rate first (when known and plausible), then the usual rates, each once. */
    fun order(nativeRate: Int?): List<Int> {
        val result = ArrayList<Int>(FALLBACK.size + 1)
        if (nativeRate != null && nativeRate in 8000..192000) result.add(nativeRate)
        for (rate in FALLBACK) if (rate !in result) result.add(rate)
        return result
    }
}
