/*
 * SPDX-FileCopyrightText: 2022-2026 Andrew Gunnerson (BCR, redactTruncate)
 * SPDX-FileCopyrightText: 2026 incall-agent contributors
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * [Redact.truncate] is adapted from BCR OutputFilenameGenerator.redactTruncate
 * (https://github.com/chenxiaolong/BCR, commit 8c218fa).
 */

package io.github.incallagent.core

/**
 * Helpers for keeping phone numbers and names out of logs.
 *
 * Rule for this project: never log a `Call`, `Call.Details`, a handle `Uri`, a caller display
 * name or a contact name directly. Pass them through one of these functions first.
 */
object Redact {
    private const val KEEP = 2

    /**
     * Keep only the first and last [KEEP] characters (BCR style). Strings of length
     * `2 * KEEP` or shorter are replaced entirely.
     */
    fun truncate(msg: String): String = buildString {
        if (msg.length > 2 * KEEP) {
            append(msg.take(KEEP))
        }
        append("<...>")
        if (msg.length > 2 * KEEP) {
            append(msg.substring(msg.length - KEEP))
        }
    }

    /** Mask a phone number. `null` becomes `<none>`. */
    fun phoneNumber(number: String?): String =
        if (number.isNullOrEmpty()) "<none>" else truncate(number)

    /**
     * Mask a call handle given as scheme + scheme-specific part (e.g. `tel` + `0312345678`).
     * The scheme is kept because it is useful for debugging (`tel` vs `sip`).
     */
    fun handle(scheme: String?, schemeSpecificPart: String?): String =
        "${scheme ?: "?"}:${phoneNumber(schemeSpecificPart)}"

    /** Names are never partially shown: only whether one was present. */
    fun name(name: String?): String =
        if (name.isNullOrEmpty()) "<none>" else "<name len=${name.length}>"
}
