// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import io.github.incallagent.core.policy.PhoneNumbers

/**
 * Emergency numbers as the phone knows them (TelephonyManager.isEmergencyNumber: the SIM, the
 * network, the modem and the country's list), on top of [DialCheck], which already refuses 110,
 * 118, 119 and every short number. Checked in every stage, the practice included, although the practice dials nothing.
 *
 * isEmergencyNumber needs no permission. It throws when the phone has no telephony or the
 * telephony process is not running: then real calls are not dialed, and the practice goes on.
 * Reasons never contain the number.
 */
object EmergencyNumbers {
    sealed interface Answer {
        data object No : Answer

        data object Yes : Answer

        /** The phone could not tell ([why]: the exception's class name). */
        data class Unknown(val why: String) : Answer
    }

    /**
     * The forms the phone is asked about: as typed without spaces and hyphens, and as domestic
     * digits. isEmergencyNumber only knows dialable characters.
     */
    fun forms(number: String): List<String> = listOfNotNull(
        number.filterNot { it in SEPARATORS }.takeIf { it.isNotEmpty() },
        PhoneNumbers.normalizeJp(number),
    ).distinct()

    fun check(number: String, isEmergency: (String) -> Boolean): Answer = try {
        if (forms(number).any(isEmergency)) Answer.Yes else Answer.No
    } catch (t: Throwable) {
        Answer.Unknown(t.javaClass.simpleName)
    }

    /** null, or why [number] must not be used. [dials]: the stage places a real call. */
    fun problem(number: String, isEmergency: (String) -> Boolean, dials: Boolean): String? =
        when (val a = check(number, isEmergency)) {
            Answer.Yes -> "緊急の番号です。このアプリでは、緊急の番号を使いません"
            is Answer.Unknown -> if (dials) "緊急の番号かどうかを、この端末で確かめられませんでした（${a.why}）。電話をかけません" else null
            Answer.No -> null
        }

    /** The phone's answer for one form of the number. Throws like isEmergencyNumber. */
    fun of(context: Context): (String) -> Boolean {
        val app = context.applicationContext
        return { form ->
            val calling = if (Build.VERSION.SDK_INT >= 33) PackageManager.FEATURE_TELEPHONY_CALLING else PackageManager.FEATURE_TELEPHONY
            if (!app.packageManager.hasSystemFeature(calling)) throw UnsupportedOperationException("no telephony")
            app.getSystemService(TelephonyManager::class.java).isEmergencyNumber(form)
        }
    }

    private const val SEPARATORS = "- ()　."
}
