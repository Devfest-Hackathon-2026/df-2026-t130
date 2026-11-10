// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.incallagent.rotep.R
import io.github.incallagent.rotep.ui.component.AitelFace
import io.github.incallagent.rotep.ui.component.AitelMood
import io.github.incallagent.rotep.ui.theme.HeadingLineBreak
import io.github.incallagent.rotep.ui.theme.LocalAitelColors

/**
 * The screen's name as its first heading, for a screen without a hero (設定, and a screen with nothing
 * to show yet): the bar above shows the app's title, not the screen's name.
 */
@Composable
fun ScreenName(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.semantics { heading() })
}

/** A card with a title that screen readers treat as a heading. */
@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = LocalAitelColors.current.card,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/**
 * The few facts that matter most on a screen (what will be called and when; what was booked): the one
 * navy card (cream in the dark theme) on a colored page, with the largest corners. Nothing is hidden
 * in it: every line a screen must show stays on the screen.
 */
@Composable
fun KeyFacts(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/**
 * One fact in [KeyFacts]: a small label (a heading for screen readers) in the screen's [accent] color,
 * then its value in emphasized text. On the cream card of the dark theme the accent would not read, so
 * the label stays navy there.
 */
@Composable
fun KeyFact(label: String, lines: List<String>, accent: Color, big: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (MaterialTheme.colorScheme.inverseSurface.luminance() < 0.5f) accent else MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.semantics { heading() },
        )
        // Bold on purpose: Japanese system fonts have no medium weight, so "emphasized" alone would not show
        val style = if (big) MaterialTheme.typography.titleLargeEmphasized else MaterialTheme.typography.titleMediumEmphasized
        for (line in lines) Text(line, style = style.copy(fontWeight = FontWeight.Bold))
    }
}

/** A screen too low for tall buttons (a phone on its side): the main buttons keep their usual height. */
@Composable
fun isShortScreen(): Boolean = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() < SHORT_SCREEN }

private val SHORT_SCREEN = 480.dp

/** The main action of a screen: navy (cream in the dark theme), taller than other buttons, and its corners change while it is pressed. */
@Composable
fun MainButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val height = if (compact) ButtonDefaults.MinHeight else ButtonDefaults.MediumContainerHeight
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = height),
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ),
        contentPadding = ButtonDefaults.contentPaddingFor(height),
    ) {
        ProvideTextStyle(ButtonDefaults.textStyleFor(height).copy(lineBreak = HeadingLineBreak)) { content() }
    }
}

/** The quieter action beside a [MainButton], at the same height. */
@Composable
fun MainOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val height = if (compact) ButtonDefaults.MinHeight else ButtonDefaults.MediumContainerHeight
    val line = MaterialTheme.colorScheme.onSurface
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = height),
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = line),
        border = BorderStroke(1.5.dp, if (enabled) line else line.copy(alpha = 0.12f)),
        contentPadding = ButtonDefaults.contentPaddingFor(height),
    ) {
        ProvideTextStyle(ButtonDefaults.textStyleFor(height).copy(lineBreak = HeadingLineBreak)) { content() }
    }
}

/**
 * A short notice on a colored card, with an [icon] beside the text (the text carries the meaning).
 * [live]: screen readers read it again when it changes (for what the user must do now; never for
 * the conversation). [sorry]: the app could not do something, and the apologizing bird takes the icon's place.
 */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    live: Boolean = false,
    sorry: Boolean = false,
    @DrawableRes icon: Int = if (error) R.drawable.ic_error else R.drawable.ic_info,
) {
    val colors = if (error) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    }
    val semantics = if (live || error) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
    Card(modifier.fillMaxWidth().then(semantics), colors = colors) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = if (sorry) Alignment.CenterVertically else Alignment.Top,
        ) {
            if (sorry) AitelFace(AitelMood.SORRY, Modifier.size(48.dp)) else SmallIcon(icon)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** An icon beside a text that says the same thing (so screen readers skip the icon). */
@Composable
fun SmallIcon(@DrawableRes id: Int, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(id), contentDescription = null, modifier = modifier.size(20.dp), tint = tint)
}

/**
 * A text field as wide as its place (the whole row, or its weight in a Row). [label] stays short, so it
 * fits on the field's border at large text sizes; [supporting]: what to write, under the field.
 */
@Composable
fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    supporting: String? = null,
    maxLines: Int = Int.MAX_VALUE,
    isError: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = singleLine,
        isError = isError,
        maxLines = if (singleLine) 1 else maxLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth(),
    )
}

/** A switch with its label (and [supporting] text) as one control: the whole row toggles. */
@Composable
fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, supporting: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            supporting?.let { Small(it) }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private val CHOICE_PADDING = 12.dp

/**
 * One choice of [options] as Material's connected toggle buttons, two to a row (a group may be stacked,
 * but must not wrap). When a label would not fit in its button (large text), it falls back to
 * [RadioRows]. Screen readers hear a single choice, as with the rows.
 */
@Composable
fun <T> ChoiceButtons(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val style = MaterialTheme.typography.labelLarge
        val widest = with(LocalDensity.current) { options.maxOf { measurer.measure(label(it), style).size.width }.toDp() }
        val room = (maxWidth - ButtonGroupDefaults.ConnectedSpaceBetween) / 2 - CHOICE_PADDING * 2
        if (widest > room) {
            RadioRows(options, selected, label, onSelect)
        } else {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                for (row in options.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                        row.forEachIndexed { i, o ->
                            ToggleButton(
                                checked = o == selected,
                                onCheckedChange = { onSelect(o) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { role = Role.RadioButton },
                                shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                                // An unchosen button is a pale card: on the pale dialog it needs a line to be seen
                                border = if (o == selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                contentPadding = PaddingValues(horizontal = CHOICE_PADDING),
                            ) { Text(label(o), maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
}

/** One choice of [options]: each row (button and label) is one control. */
@Composable
fun <T> RadioRows(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.selectableGroup()) {
        for (o in options) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = o == selected, role = Role.RadioButton, onClick = { onSelect(o) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = o == selected, onClick = null, modifier = Modifier.padding(horizontal = 14.dp))
                Text(label(o), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * Something is being started and will end by itself (the moments before a call is placed): the bird,
 * walking. Not read aloud: the words beside it say what is happening.
 */
@Composable
fun WaitingMark(modifier: Modifier = Modifier) {
    AitelFace(AitelMood.GOING, modifier.size(32.dp))
}

@Composable
fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
fun Small(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}
