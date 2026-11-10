// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import android.content.pm.PackageManager
import android.os.Build

/**
 * What the package manager says about the installed app: its uid and its APK update time.
 *
 * The helper reads this at its start (its own view of "the installed app"); the app reads it again
 * when the helper registers, and the two versions must match ([VersionGate]).
 *
 * The uid is the app's own, from the package manager. It is not `context.applicationInfo.uid`: the
 * helper's Context is the system Context, whose application is "android".
 */
internal class InstalledApp(val uid: Int, val lastUpdateTime: Long) {
    /** [HelperVersion] of this install. */
    val version: Int get() = HelperVersion.fromLastUpdate(lastUpdateTime)

    companion object {
        /** Null when the package manager cannot answer (the package is not installed, or no access). */
        fun read(pm: PackageManager, packageName: String): InstalledApp? = try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            val uid = info.applicationInfo?.uid
            if (uid == null) null else InstalledApp(uid, info.lastUpdateTime)
        } catch (_: Throwable) {
            null
        }
    }
}
