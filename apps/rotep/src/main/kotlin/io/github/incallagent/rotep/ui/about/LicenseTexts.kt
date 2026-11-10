// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.about

/** The folder in the APK's assets where the build puts the notices and the license texts (build.gradle.kts). */
internal const val LICENSE_ASSETS = "licenses"

/** One text on the screen of the licenses. [file]: its name under [LICENSE_ASSETS]. */
internal class LicenseFile(val title: String, val file: String)

/** In the order shown: what the app uses first, then the full texts that it points to. */
internal val LICENSE_FILES = listOf(
    LicenseFile("使っている部品と素材", "NOTICE"),
    LicenseFile("このアプリのライセンス（GPL-3.0）", "LICENSE"),
    LicenseFile("Apache License 2.0", "Apache-2.0.txt"),
    LicenseFile("SIL Open Font License（Dela Gothic One）", "OFL-DelaGothicOne.txt"),
    LicenseFile("SIL Open Font License（源暎きわみゴ）", "OFL-GenEiKiwamiGo.txt"),
)

/** A license text as read. [paragraphs] empty: it could not be read. */
internal class LicenseDocument(val title: String, val paragraphs: List<String>)

private val BLANK_LINE = Regex("\n[ \t\u000C]*\n")
private val RULE = Regex("[-=_*]{3,}")

/** Shorter lines than this are a list or an address, not running text cut to the width of a page. */
private const val PROSE_LINE = 45

/** The plain text of a license, in paragraphs for a narrow screen. */
internal fun licenseParagraphs(text: String): List<String> =
    text.replace("\r\n", "\n").split(BLANK_LINE).filter { it.isNotBlank() }.map(::reflowed)

/** Running text is joined, so that it wraps at the phone's width. A heading with a rule, a list or anything indented stays as it is. */
private fun reflowed(block: String): String {
    val all = block.trim('\n', '\u000C').lines().filter { it.isNotBlank() }
    val indent = all.minOf { line -> line.indexOfFirst { !it.isWhitespace() } }
    val lines = all.map { it.drop(indent).trimEnd() }
    val prose = lines.none { it.first().isWhitespace() || RULE.matches(it) } && lines.dropLast(1).all { it.length >= PROSE_LINE }
    return lines.joinToString(if (prose) " " else "\n")
}
