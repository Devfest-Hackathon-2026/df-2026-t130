// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.incallagent.rotep.diag.DiagnosticsScreen
import io.github.incallagent.rotep.diag.NoPcCard
import io.github.incallagent.rotep.diag.TryPrepareForCall
import io.github.incallagent.rotep.shell.HelperPhase
import io.github.incallagent.rotep.ui.setup.GuideStatus
import io.github.incallagent.rotep.ui.setup.SetupGuideContent
import io.github.incallagent.rotep.ui.theme.LocalAitelColors
import io.github.incallagent.rotep.ui.theme.RotepTheme

/** Halfway through the setup: Wi-Fi and the developer options are done, the rest is not. */
private val HALFWAY = GuideStatus(developerOptions = true, wifi = true, usbDebugging = false, wirelessDebugging = false, paired = false)

@RotepPreviews
@Composable
internal fun SetupGuidePreview() = Page(quietPage = true) { SetupGuideContent(HALFWAY, helperRunning = false, it) }

@RotepPreviews
@Composable
internal fun NoPcCardPreview() = Page { Column(it.padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) { NoPcCard(HelperPhase.STOPPED) } }

@RotepPreviews
@Composable
internal fun TryPrepareForCallPreview() = Page { Column(it.padding(12.dp)) { TryPrepareForCall(busy = false, onTry = {}) } }

@RotepPreviews
@Composable
internal fun DiagnosticsPreview() = Page { DiagnosticsScreen(it) }

/** As the two activities wrap their screens. */
@Composable
private fun Page(quietPage: Boolean = false, content: @Composable (Modifier) -> Unit) {
    RotepTheme {
        Scaffold(containerColor = if (quietPage) LocalAitelColors.current.pageQuiet else MaterialTheme.colorScheme.background) { padding ->
            content(Modifier.padding(padding))
        }
    }
}
