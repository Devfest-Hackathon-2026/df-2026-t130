// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.github.incallagent.rotep.ui.theme.RotepTheme

/**
 * Each sample at the normal text size, at 200 %, sideways at 200 % (the tightest case for the buttons
 * fixed at the bottom), in the dark theme, and on a small phone.
 */
@Preview(name = "1 標準", locale = "ja", showBackground = true, widthDp = 411, heightDp = 914)
@Preview(name = "2 文字 200%", locale = "ja", fontScale = 2f, showBackground = true, widthDp = 411, heightDp = 914)
@Preview(name = "3 横・文字 200%", locale = "ja", fontScale = 2f, showBackground = true, widthDp = 914, heightDp = 411)
@Preview(
    name = "4 暗い",
    locale = "ja",
    showBackground = true,
    widthDp = 411,
    heightDp = 914,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
@Preview(name = "5 狭い", locale = "ja", showBackground = true, widthDp = 320, heightDp = 640)
annotation class RotepPreviews

class IntakeSamples : PreviewParameterProvider<Sample> {
    override val values = Samples.intake.asSequence()
}

class RequestSamples : PreviewParameterProvider<Sample> {
    override val values = Samples.request.asSequence()
}

class ConfirmSamples : PreviewParameterProvider<Sample> {
    override val values = Samples.confirm.asSequence()
}

class CallSamples : PreviewParameterProvider<Sample> {
    override val values = Samples.call.asSequence()
}

class ResultSamples : PreviewParameterProvider<Sample> {
    override val values = Samples.result.asSequence()
}

class SettingsSamples : PreviewParameterProvider<Sample> {
    override val values = Samples.settings.asSequence()
}

@RotepPreviews
@Composable
internal fun IntakePreview(@PreviewParameter(IntakeSamples::class) sample: Sample) = SamplePreview(sample)

@RotepPreviews
@Composable
internal fun RequestPreview(@PreviewParameter(RequestSamples::class) sample: Sample) = SamplePreview(sample)

@RotepPreviews
@Composable
internal fun ConfirmPreview(@PreviewParameter(ConfirmSamples::class) sample: Sample) = SamplePreview(sample)

@RotepPreviews
@Composable
internal fun CallPreview(@PreviewParameter(CallSamples::class) sample: Sample) = SamplePreview(sample)

@RotepPreviews
@Composable
internal fun ResultPreview(@PreviewParameter(ResultSamples::class) sample: Sample) = SamplePreview(sample)

@RotepPreviews
@Composable
internal fun SettingsPreview(@PreviewParameter(SettingsSamples::class) sample: Sample) = SamplePreview(sample)

/** The same wrapping as MainActivity: the theme and a Scaffold. */
@Composable
fun SamplePreview(sample: Sample) {
    RotepTheme {
        Scaffold { padding -> sample.content(Modifier.padding(padding)) }
    }
}
