// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.incallagent.rotep.R
import io.github.incallagent.rotep.app.RotepApp
import io.github.incallagent.rotep.call.CallPanel
import io.github.incallagent.rotep.call.CallSession.Phase
import io.github.incallagent.rotep.call.CallSession.Who
import io.github.incallagent.rotep.call.UserCue
import io.github.incallagent.rotep.ui.component.AitelCallHeader
import io.github.incallagent.rotep.ui.theme.ClockStyle
import io.github.incallagent.rotep.ui.theme.HeadingLineBreak
import io.github.incallagent.rotep.ui.theme.LocalAitelColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** 通話中：state, elapsed time, what was said (on this screen only), and the hand-over button. */
@Composable
fun CallScreen() {
    val running = RotepApp.call.collectAsState().value ?: run {
        CallContent(state = null, onTakeOver = {}, onAnswered = {}, onHungUp = {}, onShowResult = {})
        return
    }
    val st by running.session.state.collectAsState()
    // The shop and the limit come from the request of the confirm screen: the call does not expose its
    // policy (RunningCall has none), and the approved policy is that request with the approval added.
    val draft by RotepApp.draft.collectAsState()
    val noInstruction = remember { MutableStateFlow<String?>(null) }
    val instruction by (running.manual?.instruction ?: noInstruction).collectAsState()
    val noPanel = remember { MutableStateFlow(CallPanel()) }
    val panel by (running.control?.panel ?: noPanel).collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(running) {
        while (true) {
            delay(500)
            tick++
        }
    }
    val elapsed = tick.let { running.session.elapsedMs() }
    var hangUpAsked by remember(running) { mutableStateOf(false) }

    CallContent(
        state = CallUiState(
            snapshot = st,
            stage = running.stage,
            warning = running.warning,
            instruction = instruction,
            elapsedMs = callElapsedMs(st, elapsed),
            shopName = draft?.target?.businessName,
            limitSeconds = draft?.conversation?.maxCallSeconds,
            panel = panel,
            aiNeedsMic = running.aiNeedsMic,
            quietWhileAi = running.quietWhileAi,
            hangUpAsked = hangUpAsked,
        ),
        onTakeOver = { RotepApp.takeOver() },
        onAnswered = { RotepApp.answered() },
        onHungUp = { RotepApp.hungUp() },
        onShowResult = { RotepApp.show(RotepApp.Screen.RESULT) },
        onSpeaker = { RotepApp.setCallSpeaker(it) },
        onMicOff = { RotepApp.setCallMicOff(it) },
        onKey = { RotepApp.pressCallKey(it) },
        onHangUp = {
            hangUpAsked = true
            RotepApp.hangUpCall()
        },
        onOpenPhone = { RotepApp.openPhoneCallScreen() },
        onUserCue = { RotepApp.sendUserCue(it) },
    )
}

/**
 * The call as it goes, in three parts. Top, fixed: the state with AITEL's face, the time, and why the
 * AI stopped. Middle, scrolls: the shop, the way of calling and the conversation (or the keypad while
 * it is open). Bottom, fixed: what to do by hand (it shrinks first when the screen is short), the row
 * of speaker, microphone, keypad and "電話を切る", "相手が出た" and "切った" where the app cannot see
 * those by itself (L2), and "AI を止めて本人に代わる" (or "結果を見る" once there is a result).
 * [state] null: there is no call.
 */
@Composable
fun CallContent(
    state: CallUiState?,
    onTakeOver: () -> Unit,
    onAnswered: () -> Unit,
    onHungUp: () -> Unit,
    onShowResult: () -> Unit,
    modifier: Modifier = Modifier,
    onSpeaker: (Boolean) -> Unit = {},
    onMicOff: (Boolean) -> Unit = {},
    onKey: (Char) -> Unit = {},
    onHangUp: () -> Unit = {},
    onOpenPhone: () -> Unit = {},
    keypadInitiallyOpen: Boolean = false,
    onUserCue: (UserCue) -> Unit = {},
) {
    if (state == null) {
        Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenName("通話中")
            Text("通話はありません")
        }
        return
    }
    val st = state.snapshot
    val motion = MaterialTheme.motionScheme

    BoxWithConstraints(modifier.fillMaxSize()) {
        // A short screen (landscape) gets the small header, so the buttons at the bottom keep their room
        val compact = maxHeight < 480.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Top, fixed
            AitelCallHeader(
                screenName = "通話中",
                phase = phaseLabel(st.phase),
                mood = callMood(st),
                compact = compact,
                // The line under the header says the same thing, or is not shown: see below
                announce = st.handover == null && callStatusLine(st) == null,
                below = { Text(clockText(state.clock), style = ClockStyle, maxLines = 1) },
            )
            // Under it: the status, or why the AI stopped; nothing when the status only repeats the header.
            // The swap fades and the height follows; a change of the status itself is not animated.
            // Screen readers get it from the outside: during the swap both are on screen
            val stopped = st.handover?.let { "AI を止めました：$it" + if (state.inCall) "。ご自分で話してください" else "" }
            val line = stopped ?: callStatusLine(st)
            if (line != null) {
                AnimatedContent(
                    targetState = TopLine(line, stopped = stopped != null),
                    modifier = Modifier.fillMaxWidth().semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = line
                    },
                    transitionSpec = {
                        fadeIn(motion.defaultEffectsSpec())
                            .togetherWith(fadeOut(motion.fastEffectsSpec()))
                            .using(SizeTransform { _, _ -> motion.fastSpatialSpec() })
                    },
                    contentKey = { it.stopped },
                    label = "stopped",
                ) { shown ->
                    if (shown.stopped) {
                        Notice(shown.text, Modifier.clearAndSetSemantics {}, error = true)
                    } else {
                        Text(
                            shown.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                }
            }
            // Up here, not in the list below: on a short screen the list can have no room at all
            state.warning?.let { Notice(it, error = true) }

            // Middle, scrolls; follows the conversation while the user is at its end
            val list = rememberLazyListState()
            val shownList = visibleCallLines(st.lines)
            val lineCount = shownList.size
            // Lines already seen stay as they are: when the screen opens, or when they scroll back into view
            var shownLines by remember { mutableIntStateOf(lineCount) }
            LaunchedEffect(lineCount) {
                if (lineCount < shownLines) shownLines = lineCount
                val last = list.layoutInfo.totalItemsCount - 1
                val shown = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (last > 0 && shown >= last - 2) list.animateScrollToItem(last)
            }
            // The last line grows as the speech is written out: while it is in view, its end must not
            // slip behind the buttons below
            val lastLineLength = shownList.lastOrNull()?.text?.length ?: 0
            LaunchedEffect(lastLineLength) {
                val last = list.layoutInfo.totalItemsCount - 1
                if (last > 0 && list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == last) list.animateScrollToItem(last)
            }
            var keypadOpen by remember { mutableStateOf(keypadInitiallyOpen) }
            if (keypadOpen && state.panel.keypadAvailable) {
                CallKeypad(onKey, Modifier.weight(1f).fillMaxWidth(), compact)
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    state.shopName?.let { Text("相手：$it", style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    // Only the practice needs saying: this must not be taken for a real call
                    if (!compact && !state.inCall) Small("電話のしかた：${state.stage.label}")
                }
                item {
                    Text(
                        stringResource(R.string.transcript_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
                    )
                    Small(stringResource(R.string.transcript_caveat))
                }
                // No live region on the conversation: screen readers would never stop reading.
                // A new line fades in where it stands. The key is the line's place, never its words: the
                // same words can come twice, and the last line keeps growing as the speech is written out
                itemsIndexed(shownList, key = { i, _ -> "line-$i" }) { i, line ->
                    val fade = remember { Animatable(if (i >= shownLines) 0f else 1f) }
                    LaunchedEffect(Unit) {
                        shownLines = maxOf(shownLines, i + 1)
                        fade.animateTo(1f, motion.slowEffectsSpec())
                    }
                    // A note of the app ("本人の指示：…") has no speaker to name
                    val who = when (line.who) {
                        Who.SHOP -> if (state.inCall) "店員：" else "店員（あなた）："
                        Who.AI -> "AI："
                        Who.TOOL -> "ツール："
                        Who.INFO -> ""
                    }
                    val aside = line.who == Who.TOOL || line.who == Who.INFO
                    if (aside) {
                        Text(
                            who + line.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = if (line.who == Who.TOOL) FontFamily.Monospace else FontFamily.Default,
                            modifier = Modifier.padding(horizontal = 14.dp).graphicsLayer { alpha = fade.value },
                        )
                    } else {
                        // What was said: one card per turn, the speaker in small letters before the words
                        Surface(
                            color = LocalAitelColors.current.card,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = fade.value },
                        ) {
                            Text(
                                buildAnnotatedString {
                                    withStyle(
                                        SpanStyle(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = MaterialTheme.typography.labelMedium.fontSize,
                                        ),
                                    ) { append(who) }
                                    append(line.text)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }

            // Bottom, fixed: the line marks where the scrolling part ends
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // What to do by hand opens and closes in about 0.2 s, so the eye goes to it. It keeps its
                // last words while it closes, and it shrinks first when the screen is short (the weight)
                val instruction = state.instruction
                var shownInstruction by remember { mutableStateOf(instruction) }
                if (instruction != null) shownInstruction = instruction
                AnimatedVisibility(
                    visible = instruction != null,
                    // On a short screen it may not take the whole middle: the conversation keeps some room
                    modifier = Modifier.weight(1f, fill = false).heightIn(max = if (compact) INSTRUCTION_MAX_COMPACT else Dp.Infinity),
                    enter = expandVertically(motion.fastSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                    exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
                    label = "instruction",
                ) {
                    Notice(
                        "手でしてください：$shownInstruction",
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        live = true,
                        icon = R.drawable.ic_touch_app,
                    )
                }
                if (state.showPanel) {
                    CallPanelRow(
                        state = state,
                        compact = compact,
                        keypadOpen = keypadOpen,
                        onKeypad = { keypadOpen = !keypadOpen },
                        onSpeaker = onSpeaker,
                        onMicOff = onMicOff,
                        onHangUp = onHangUp,
                        onOpenPhone = onOpenPhone,
                    )
                }
                if (state.showManualButtons) {
                    val buttonHeight = if (compact) ButtonDefaults.MinHeight else ButtonDefaults.MediumContainerHeight
                    // Evenly tall: one label may take two lines at large text
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.showAnswerButton) {
                            FilledTonalButton(
                                onClick = onAnswered,
                                enabled = state.canAnswer,
                                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = buttonHeight),
                                shapes = ButtonDefaults.shapes(),
                                contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight),
                            ) {
                                // The same text style as the main buttons beside it
                                ProvideTextStyle(ButtonDefaults.textStyleFor(buttonHeight).copy(lineBreak = HeadingLineBreak)) {
                                    IconText(R.drawable.ic_call, stringResource(R.string.answered))
                                }
                            }
                        }
                        if (state.showHungUpButton) {
                            MainOutlinedButton(onClick = onHungUp, modifier = Modifier.weight(1f).fillMaxHeight(), enabled = state.canHangUp, compact = compact) {
                                IconText(R.drawable.ic_call_end, stringResource(R.string.hung_up))
                            }
                        }
                    }
                }
                if (state.showUserCues) {
                    // Evenly tall: one label may take two lines at large text
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (cue in UserCue.entries) {
                            MainOutlinedButton(onClick = { onUserCue(cue) }, modifier = Modifier.weight(1f).fillMaxHeight(), compact = true) { Text(cue.label) }
                        }
                    }
                }
                if (state.showResultButton) {
                    MainButton(onClick = onShowResult, modifier = Modifier.fillMaxWidth(), compact = compact) {
                        IconText(R.drawable.ic_fact_check, stringResource(R.string.show_result))
                    }
                } else if (state.aiOn) {
                    // The stop is a safe action: the main color, no confirmation, never the error color
                    MainButton(onClick = onTakeOver, modifier = Modifier.fillMaxWidth(), compact = compact) {
                        IconText(R.drawable.ic_voice_over_off, stringResource(R.string.stop_ai))
                    }
                }
            }
        }
    }
}

/**
 * The phone app's buttons on this screen. Only what the app can really do on this call is there
 * (CallPanel): stage L2 has the microphone and "電話を切る", stage L3 also the speaker and the keypad.
 * Words: 「端末のマイクを止める」, not "mute", which does not say which microphone.
 */
@Composable
private fun CallPanelRow(
    state: CallUiState,
    compact: Boolean,
    keypadOpen: Boolean,
    onKeypad: () -> Unit,
    onSpeaker: (Boolean) -> Unit,
    onMicOff: (Boolean) -> Unit,
    onHangUp: () -> Unit,
    onOpenPhone: () -> Unit,
) {
    val panel = state.panel
    val keys = buildList {
        if (panel.speakerAvailable) {
            val on = panel.speakerOn
            add(CallKeyWords(R.drawable.ic_volume_up, "スピーカー", state = if (on) "オン" else null, spoken = "スピーカー", enabled = !state.panelLocked, checked = on) { onSpeaker(!on) })
        }
        if (panel.micAvailable) {
            val off = panel.micOff
            add(
                CallKeyWords(
                    if (off) R.drawable.ic_mic_off else R.drawable.ic_mic,
                    if (off) "マイクを\n戻す" else "マイクを\n止める",
                    // A switch that is on when the microphone is stopped: read the words on the button, then which microphone
                    spoken = (if (off) "マイクを戻す" else "マイクを止める") + "。端末のマイクです",
                    enabled = !state.panelLocked,
                    checked = off,
                ) { onMicOff(!off) },
            )
        }
        if (panel.keypadAvailable) add(CallKeyWords(R.drawable.ic_dialpad, "キーパッド", checked = keypadOpen, onClick = onKeypad))
        if (panel.openPhoneOffered) add(CallKeyWords(R.drawable.ic_open_in_new, "電話の\n画面", onClick = onOpenPhone))
        if (panel.hangUpAvailable) {
            add(CallKeyWords(R.drawable.ic_call_end, "電話を\n切る", spoken = stringResource(R.string.hang_up), danger = true, onClick = onHangUp))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.panelNote?.let { Small(it) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val measurer = rememberTextMeasurer()
            val style = MaterialTheme.typography.labelMedium
            val density = LocalDensity.current
            fun widest(lines: List<String>) = with(density) { (lines.maxOfOrNull { measurer.measure(it, style).size.width } ?: 0).toDp() }
            fun room(columns: Int) = (maxWidth - CALL_KEY_GAP * (columns - 1)) / columns - CALL_KEY_PADDING * 2
            // On a short screen one line of words each, if that fits: every line of height counts there
            val oneLine = compact && widest(keys.map { it.oneLine }) <= room(keys.size)
            val need = widest(if (oneLine) keys.map { it.oneLine } else keys.flatMap { it.lines })
            // Fewer to a row before a word is broken (large text), and the last row stretches
            val columns = listOf(keys.size, (keys.size + 1) / 2, 2).firstOrNull { it in 1..keys.size && need <= room(it) } ?: minOf(2, keys.size)
            Column(verticalArrangement = Arrangement.spacedBy(CALL_KEY_GAP)) {
                for (row in keys.chunked(columns.coerceAtLeast(1))) {
                    // Evenly tall within the row, also when one label takes two lines
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(CALL_KEY_GAP)) {
                        for (key in row) CallKey(key, Modifier.weight(1f), compact, oneLine)
                    }
                }
            }
        }
    }
}

/**
 * What one button of [CallPanelRow] shows. [text]: "\n" where its two lines part, so a word is never
 * broken. [state]: a word for the second line. [spoken]: what screen readers say in place of the
 * words. [checked] null: not a switch but a plain button.
 */
private class CallKeyWords(
    @DrawableRes val icon: Int,
    val text: String,
    val state: String? = null,
    val spoken: String? = null,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit,
) {
    val lines: List<String> get() = text.split('\n') + listOfNotNull(state)
    val oneLine: String get() = text.replace("\n", "") + state?.let { " $it" }.orEmpty()
}

/** One button of [CallPanelRow]: an icon over its words. The icons of a row stay level. */
@Composable
private fun CallKey(key: CallKeyWords, modifier: Modifier, compact: Boolean, oneLine: Boolean) {
    val colors = MaterialTheme.colorScheme
    // The strongest of the row: filled red. In the dark theme the bright red is the pale one
    val light = colors.surface.luminance() > 0.5f
    val container = when {
        key.danger -> if (light) colors.error else colors.errorContainer
        key.checked == true -> colors.inverseSurface
        else -> LocalAitelColors.current.card
    }
    val content = when {
        key.danger -> if (light) colors.onError else colors.onErrorContainer
        key.checked == true -> colors.inverseOnSurface
        else -> colors.onSurface
    }
    val sized = modifier
        .fillMaxHeight()
        .heightIn(min = if (compact) CALL_KEY_MIN_COMPACT else CALL_KEY_MIN)
        .alpha(if (key.enabled) 1f else DISABLED_ALPHA)
    val words = if (oneLine) key.oneLine else key.lines.joinToString("\n")
    val face: @Composable () -> Unit = {
        Column(
            Modifier.padding(horizontal = CALL_KEY_PADDING, vertical = if (compact) 4.dp else 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(painterResource(key.icon), contentDescription = null, modifier = Modifier.size(if (compact) 18.dp else 22.dp))
            Text(words, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
    val checked = key.checked
    val role = if (checked != null) Role.Switch else Role.Button
    val described = sized.semantics {
        this.role = role
        key.spoken?.let { contentDescription = it }
    }
    if (checked != null) {
        Surface(
            checked = checked,
            onCheckedChange = { key.onClick() },
            modifier = described,
            enabled = key.enabled,
            shape = MaterialTheme.shapes.large,
            color = container,
            contentColor = content,
            content = face,
        )
    } else {
        Surface(
            onClick = key.onClick,
            modifier = described,
            enabled = key.enabled,
            shape = MaterialTheme.shapes.large,
            color = container,
            contentColor = content,
            content = face,
        )
    }
}

/**
 * The keypad, in the place of the conversation (so nothing below it moves away). What was pressed is
 * shown here and nowhere else: it can be a code the far end asks for.
 */
@Composable
private fun CallKeypad(onKey: (Char) -> Unit, modifier: Modifier, compact: Boolean) {
    var sent by remember { mutableStateOf("") }
    // At large text the full-size keys would push the last row out of reach
    val smallKeys = compact || LocalDensity.current.fontScale >= LARGE_TEXT
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // On a short screen the keys need the room: the sentence that explains them goes, what was sent stays
        if (!compact || sent.isNotEmpty()) {
            Text(if (sent.isEmpty()) "押すと、プッシュ音を相手に送ります" else "送ったプッシュ音：$sent", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyVerticalGrid(
            // Sideways the twelve keys make two rows of six: all of them in view, the digits readable
            columns = GridCells.Fixed(if (compact) 6 else 3),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(KEYPAD_KEYS) { key ->
                FilledTonalButton(
                    onClick = {
                        sent = (sent + key).takeLast(KEYPAD_SHOWN)
                        onKey(key)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (smallKeys) KEYPAD_KEY_MIN_COMPACT else KEYPAD_KEY_MIN)
                        .semantics { KEYPAD_SPOKEN[key]?.let { contentDescription = it } },
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = LocalAitelColors.current.card,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    contentPadding = if (smallKeys) PaddingValues(0.dp) else ButtonDefaults.ContentPadding,
                ) { Text(key.toString(), style = if (smallKeys) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge) }
            }
        }
    }
}

private val KEYPAD_KEYS = "123456789*0#".toList()
private const val KEYPAD_SHOWN = 24
private val KEYPAD_SPOKEN = mapOf('*' to "アスタリスク", '#' to "シャープ")
private val KEYPAD_KEY_MIN = 64.dp
private val KEYPAD_KEY_MIN_COMPACT = 40.dp
private const val LARGE_TEXT = 1.5f

private val INSTRUCTION_MAX_COMPACT = 72.dp
private val CALL_KEY_MIN = 64.dp
private val CALL_KEY_MIN_COMPACT = 48.dp
private val CALL_KEY_GAP = 8.dp
private val CALL_KEY_PADDING = 4.dp
private const val DISABLED_ALPHA = 0.38f

/** The line under the call's state: the status, or ([stopped]) why the AI stopped. */
private data class TopLine(val text: String, val stopped: Boolean)

/** The content of a button: an icon, then its words (the words are what screen readers read). */
@Composable
private fun IconText(@DrawableRes icon: Int, text: String) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text(text)
}

fun phaseLabel(p: Phase) = when (p) {
    Phase.IDLE -> "準備しています"
    Phase.DIALING -> "電話をかけています"
    Phase.RINGING -> "相手が出るのを待っています"
    Phase.TALKING -> "AI が会話しています"
    Phase.CLOSING -> "電話を終えるところです"
    Phase.HANDED_OVER -> "本人に代わりました"
    Phase.ENDED -> "電話が終わりました"
}
