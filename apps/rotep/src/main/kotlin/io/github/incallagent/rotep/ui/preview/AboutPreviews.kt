// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.preview

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.incallagent.rotep.ui.about.AboutContent
import io.github.incallagent.rotep.ui.about.LicenseDocument
import io.github.incallagent.rotep.ui.about.LicensesContent
import io.github.incallagent.rotep.ui.about.licenseParagraphs
import io.github.incallagent.rotep.ui.theme.LocalAitelColors
import io.github.incallagent.rotep.ui.theme.RotepTheme

/** Made-up, in the shape of the real texts: a heading with a rule, running text cut to a page's width, a list. */
private const val SAMPLE_TEXT = """Third-party notices
===================

The parts below are included in the app under their own licenses. The full text of each
license that they use follows on this screen, in the same order as they are named here.

  com.example:library
  com.example:other    (pulled in by library)

Source: https://example.com/
License: Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0"""

private val SAMPLE_DOCUMENTS = listOf(
    LicenseDocument("使っている部品と素材", licenseParagraphs(SAMPLE_TEXT)),
    LicenseDocument("Apache License 2.0", emptyList()),
)

@RotepPreviews
@Composable
internal fun AboutPreview() = Page { AboutContent("0.9.0", {}, {}, {}, it) }

@RotepPreviews
@Composable
internal fun LicensesPreview() = Page { LicensesContent(SAMPLE_DOCUMENTS, {}, it) }

/** As the activity wraps its screen. */
@Composable
private fun Page(content: @Composable (Modifier) -> Unit) {
    RotepTheme {
        Scaffold(containerColor = LocalAitelColors.current.pageQuiet) { padding -> content(Modifier.padding(padding)) }
    }
}
