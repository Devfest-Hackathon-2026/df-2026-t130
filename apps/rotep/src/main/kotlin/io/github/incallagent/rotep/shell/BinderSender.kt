// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import java.lang.reflect.Method

/**
 * Hands the helper's binder to the app's content provider from the shell process.
 *
 * **This is written for Android 16 (API 36) only.** The names and argument lists below were checked
 * against the AOSP `android16-release` sources (IActivityManager.aidl, IContentProvider.java,
 * ContentProviderHolder.java). A few older shapes are kept as fallbacks, but they are not tested.
 *
 * It is the path the `content` command uses, and the one scrcpy's wrappers (ActivityManager,
 * ContentProvider; Apache-2.0, see NOTICE) use for the settings provider: ask ActivityManager for the
 * provider with getContentProviderExternal, call it, then release it with removeContentProviderExternal.
 * The external reference pins the app process, so it is released in every case (see [register]);
 * otherwise the app could never be stopped by the system.
 *
 * All of the hidden-API reflection lives here. The helper process is not subject to the hidden-API
 * blocklist, so these calls are allowed. The IContentProvider and IActivityManager methods are looked
 * up on the public interfaces, because the proxy classes behind them are not public.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal object BinderSender {
    private const val TAG = "RotepHelper"
    private const val USER_ID = 0
    private const val TAG_FOR_AM = "rotep-helper"

    /**
     * Registers [service] with the app and says what came of it ([Handoff]):
     *  - the app's answer ([Handoff.Accepted], which carries the app's callback binder, or [Handoff.Refused]);
     *  - [Handoff.NotReached] when the app could not be reached (not installed, provider missing, a call failed);
     *  - [Handoff.AnotherApp] when the provider belongs to another app than the one with uid [appUid]. That
     *    is the app installed again with another uid; the helper then ends and does not come back (see
     *    [LinkPolicy]), instead of reaching for the new install every few seconds.
     *
     * An owner that cannot be read is not [Handoff.AnotherApp]: nothing is known then, so the helper
     * tries again later.
     */
    fun register(service: IHelperService, version: Int, appUid: Int): Handoff {
        val activityManager = activityManager() ?: run {
            Log.w(TAG, "handoff: no ActivityManager")
            return Handoff.NotReached
        }
        val token: IBinder = Binder()
        var holder: Any? = null
        try {
            holder = getContentProviderExternal(activityManager, token)
            if (holder == null) {
                Log.w(TAG, "handoff: provider not found (is the app installed?)")
                return Handoff.NotReached
            }
            val providerUid = ownerUid(holder)
            ProviderOwnerPolicy.handoffFor(providerUid, appUid)?.let { notTheApp ->
                if (notTheApp is Handoff.AnotherApp) {
                    Log.e(TAG, "handoff: the provider does not belong to the app (provider uid=$providerUid, app uid=$appUid)")
                } else {
                    Log.w(TAG, "handoff: the owner of the provider could not be read (provider uid=$providerUid, app uid=$appUid)")
                }
                return notTheApp
            }
            val provider = holder.javaClass.getField("provider").get(holder) ?: run {
                Log.w(TAG, "handoff: the holder has no provider")
                return Handoff.NotReached
            }
            val extras = Bundle().apply {
                putBinder(HelperProtocol.KEY_SERVICE, service.asBinder())
                putInt(HelperProtocol.KEY_VERSION, version)
                putInt(HelperProtocol.KEY_PID, android.os.Process.myPid())
                putInt(HelperProtocol.KEY_UID, android.os.Process.myUid())
            }
            return toHandoff(callProvider(provider, extras))
        } catch (t: Throwable) {
            Log.w(TAG, "handoff: register failed: ${ReportText.exceptionLine(t)}")
            return Handoff.NotReached
        } finally {
            if (holder != null) releaseContentProviderExternal(activityManager, token)
        }
    }

    /** The provider's reply (see [HelperProvider]) as a [Handoff]. A refusal without a retry flag counts as "try again". */
    private fun toHandoff(reply: Bundle?): Handoff {
        if (reply == null) {
            Log.w(TAG, "handoff: the provider gave no reply")
            return Handoff.NotReached
        }
        if (!reply.getBoolean(HelperProtocol.KEY_ACCEPTED, false)) {
            return Handoff.Refused(
                reason = reply.getString(HelperProtocol.KEY_REASON).orEmpty(),
                retry = reply.getBoolean(HelperProtocol.KEY_RETRY, true),
            )
        }
        val callback = reply.getBinder(HelperProtocol.KEY_CALLBACK)
        if (callback == null) {
            Log.w(TAG, "register accepted but no callback came")
            return Handoff.NotReached
        }
        return Handoff.Accepted(callback)
    }

    private fun activityManager(): Any? =
        runCatching { Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null) }.getOrNull()
            // Old shim that scrcpy still uses.
            ?: runCatching { Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null) }.getOrNull()

    private fun getContentProviderExternal(activityManager: Any, token: IBinder): Any? {
        val am = Class.forName("android.app.IActivityManager")
        // Android 10 and newer take a tag; the shorter form is kept as a fallback.
        val withTag = findMethod(
            am, "getContentProviderExternal",
            String::class.java, Int::class.javaPrimitiveType!!, IBinder::class.java, String::class.java,
        )
        if (withTag != null) {
            return withTag.invoke(activityManager, HelperProtocol.AUTHORITY, USER_ID, token, TAG_FOR_AM)
        }
        val plain = findMethod(
            am, "getContentProviderExternal",
            String::class.java, Int::class.javaPrimitiveType!!, IBinder::class.java,
        ) ?: throw NoSuchMethodException("IActivityManager.getContentProviderExternal not found")
        return plain.invoke(activityManager, HelperProtocol.AUTHORITY, USER_ID, token)
    }

    /** The uid of the app that owns the provider (ContentProviderHolder.info.applicationInfo.uid), or null. */
    private fun ownerUid(holder: Any): Int? = runCatching {
        val info = holder.javaClass.getField("info").get(holder) as? ProviderInfo
        info?.applicationInfo?.uid
    }.getOrNull()

    /** Calls IContentProvider.call(AttributionSource, authority, method, arg, extras) (Android 12 and newer). */
    private fun callProvider(provider: Any, extras: Bundle): Bundle? {
        val attribution = AttributionSource.Builder(ShellIdentity.SHELL_UID)
            .setPackageName(ShellIdentity.SHELL_PACKAGE)
            .build()
        val iface = Class.forName("android.content.IContentProvider")
        val call = iface.methods.firstOrNull {
            it.name == "call" && it.parameterTypes.size == 5 && it.parameterTypes[0] == AttributionSource::class.java
        } ?: throw NoSuchMethodException("IContentProvider.call(AttributionSource, …) not found")
        return call.invoke(
            provider,
            attribution,
            HelperProtocol.AUTHORITY,
            HelperProtocol.METHOD_REGISTER,
            null,
            extras,
        ) as? Bundle
    }

    private fun releaseContentProviderExternal(activityManager: Any, token: IBinder) {
        try {
            val am = Class.forName("android.app.IActivityManager")
            val plain = findMethod(am, "removeContentProviderExternal", String::class.java, IBinder::class.java)
            if (plain != null) {
                plain.invoke(activityManager, HelperProtocol.AUTHORITY, token)
                return
            }
            val asUser = findMethod(
                am, "removeContentProviderExternalAsUser",
                String::class.java, IBinder::class.java, Int::class.javaPrimitiveType!!,
            )
            if (asUser != null) {
                asUser.invoke(activityManager, HelperProtocol.AUTHORITY, token, USER_ID)
            } else {
                Log.w(TAG, "handoff: no way to release the provider")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "handoff: release failed: ${ReportText.exceptionLine(t)}")
        }
    }

    private fun findMethod(cls: Class<*>, name: String, vararg types: Class<*>): Method? =
        cls.methods.firstOrNull { it.name == name && it.parameterTypes.contentEquals(types) }
}
