// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Process

/**
 * A Context that names the shell package as the one that records.
 *
 * Why: the helper is not a normal app process. The Context it can get (see [HelperContext]) is the
 * system Context. The system decides about a recording by the package named in the attribution
 * source, and a package that does not belong to uid 2000 (the app's own package, or "android") makes
 * the recording be refused or silenced. Passing this Context to `AudioRecord.Builder.setContext`
 * makes the system see what is true: package `com.android.shell` with uid 2000.
 *
 * Only the attribution parts are replaced. System services still come from the wrapped Context.
 * [getApplicationContext] returns this wrapper, because the system Context has no application and
 * the audio code may ask for one (the same idea as scrcpy's FakeContext).
 */
internal class ShellContext private constructor(
    base: Context,
    private val source: AttributionSource,
) : ContextWrapper(base) {

    /** The package name that [getAttributionSource] carries (what the system will see). */
    val attributionPackage: String? get() = source.packageName

    /** The uid that [getAttributionSource] carries. */
    val attributionUid: Int get() = source.uid

    override fun getPackageName(): String = ShellIdentity.SHELL_PACKAGE

    override fun getOpPackageName(): String = ShellIdentity.SHELL_PACKAGE

    override fun getAttributionSource(): AttributionSource = source

    // The helper is built on the system Context, whose getApplicationContext() is null. Some audio
    // paths call it, so return this wrapper (the same approach scrcpy's FakeContext takes).
    override fun getApplicationContext(): Context = this

    // The default device, as scrcpy's FakeContext answers. (The audio code reads it on Android 14+.)
    override fun getDeviceId(): Int = 0

    companion object {
        /**
         * Returns null when the helper does not run as the shell user: then there is no package name
         * that would be right, and the helper must not record.
         */
        fun create(base: Context): ShellContext? {
            val uid = Process.myUid()
            val packageName = ShellIdentity.packageFor(uid) ?: return null
            val source = AttributionSource.Builder(uid).setPackageName(packageName).build()
            return ShellContext(base, source)
        }
    }
}
