// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.incallagent.rotep.R
import io.github.incallagent.rotep.app.RotepApp
import io.github.incallagent.rotep.history.BookingHistory
import io.github.incallagent.rotep.intake.IntakeDraft
import io.github.incallagent.rotep.intake.IntakeLine
import io.github.incallagent.rotep.intake.IntakeUi
import io.github.incallagent.rotep.intake.lastSpoken
import io.github.incallagent.rotep.ui.component.AitelFace
import io.github.incallagent.rotep.ui.component.AitelHero
import io.github.incallagent.rotep.ui.component.AitelMood
import io.github.incallagent.rotep.ui.component.HeroKind
import io.github.incallagent.rotep.ui.history.RecordsCard
import io.github.incallagent.rotep.ui.history.showsRecordsCard
import io.github.incallagent.rotep.ui.theme.LocalAitelColors

/** The first screen: a conversation that fills in the request. It ends on the confirm screen, never in a call. */
@Composable
fun IntakeScreen() {
    val chat by RotepApp.intakeChat.collectAsState()
    val settings by RotepApp.settings.collectAsState()
    val kept by RotepApp.lastResult.collectAsState()
    val records by BookingHistory.state.collectAsState()
    LaunchedEffect(Unit) { BookingHistory.refresh() }
    val practice = remember(settings) { RotepApp.stage() == RotepApp.Stage.L0 }
    LaunchedEffect(chat) { if (chat == null) RotepApp.ensureIntakeChat() }
    val current = chat ?: return
    val ui by current.ui.collectAsState()
    // A new conversation starts with nothing open
    key(current) {
        IntakeContent(
            ui = ui,
            hasKey = settings.hasClaudeKey,
            practice = practice,
            lastCall = kept?.let(::lastCallUi),
            onShowLastCall = RotepApp::showLastResult,
            recordsCount = records.entries.size.takeIf { showsRecordsCard(it, RotepApp.callRunning()) } ?: 0,
            onOpenRecords = RotepApp::openHistory,
            onType = current::type,
            onSay = current::say,
            onAnswer = current::answer,
            onRetry = current::retry,
            onStop = current::stop,
            onByHand = { RotepApp.applyIntake(toConfirm = false) },
            onToConfirm = { RotepApp.applyIntake(toConfirm = true) },
            onRestart = RotepApp::restartIntake,
            onOpenSettings = { RotepApp.show(RotepApp.Screen.SETTINGS) },
        )
    }
}

@Composable
fun IntakeContent(
    ui: IntakeUi,
    hasKey: Boolean,
    practice: Boolean,
    onType: (String) -> Unit,
    onSay: (String) -> Boolean,
    onAnswer: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    onByHand: () -> Unit,
    onToConfirm: () -> Unit,
    onRestart: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    lastCall: LastCallUi? = null,
    onShowLastCall: () -> Unit = {},
    recordsCount: Int = 0,
    onOpenRecords: () -> Unit = {},
    detailsOpen: Boolean = false,
) {
    val list = rememberLazyListState()
    // The newest is at the bottom, item 0: anything new brings it into view
    LaunchedEffect(ui.lines.size, ui.waiting, ui.searching, ui.question, ui.error, ui.full) { list.animateScrollToItem(0) }
    val summary = ui.form?.takeIf { ui.accepted }?.let { IntakeDraft.summary(it, ui.permissions) }.orEmpty()
    val hasDetails = summary.isNotEmpty() || ui.forTheForm.isNotEmpty() || ui.missing.isNotEmpty()
    var showDetails by rememberSaveable { mutableStateOf(detailsOpen) }
    val unsent = ui.typing.isNotBlank() && !ui.full
    val notApplied = !ui.waiting && ui.lines.lastSpoken()?.who == IntakeLine.Who.USER
    val complete = ui.accepted && ui.missing.isEmpty() && !ui.waiting
    // One bird beside the conversation: at the AI's latest words, or thinking of its next ones
    val birdAt = if (ui.waiting) NO_BIRD else ui.lines.indexOfLast { it.who == IntakeLine.Who.AI }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val detailsMax = minOf(DETAILS_MAX_HEIGHT.dp, maxHeight / 2)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, reverseLayout = true, verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
                    if (ui.full) {
                        item { InAiColumn { Notice("会話が長くなりました。ここからは「手で入力する」で入れてください") } }
                    }
                    ui.error?.let { error ->
                        item {
                            InAiColumn {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Notice(error, error = true, live = true)
                                    if (ui.retryable) TextButton(onClick = onRetry) { Text("もう一度送る") }
                                }
                            }
                        }
                    }
                    ui.searchNote?.let { note -> item { InAiColumn { Small(note) } } }
                    if (ui.waiting) item(key = "waiting") { Thinking(onStop) }
                    if (ui.searching) item(key = "searching") { Searching() }
                    ui.question?.let { question ->
                        item(key = "question") {
                            InAiColumn {
                                Section("AI に任せますか") {
                                    QuestionText(question.text)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        MainOutlinedButton(onClick = { onAnswer(false) }, Modifier.weight(1f), enabled = !ui.waiting, compact = true) { Text("いいえ") }
                                        MainOutlinedButton(onClick = { onAnswer(true) }, Modifier.weight(1f), enabled = !ui.waiting, compact = true) { Text("はい") }
                                    }
                                }
                            }
                        }
                    }
                    if (hasKey && ui.lines.isEmpty() && !ui.waiting && ui.typing.isBlank()) {
                        item(key = "starters") { Starters(onType) }
                    }
                    items(ui.lines.size, key = { ui.lines.size - 1 - it }) { i ->
                        val index = ui.lines.size - 1 - i
                        val line = ui.lines[index]
                        val found = line.searchIndex?.let { ui.searches.getOrNull(it) }
                        when {
                            found != null -> InAiColumn { Section("お店を調べました") { SearchFindings(found.findings) } }
                            line.who == IntakeLine.Who.AI -> AiBubble(line.text, bird = index == birdAt)
                            line.who != IntakeLine.Who.SEARCH -> MyBubble(line.text)
                        }
                    }
                    item(key = "first") { AiBubble(FIRST_QUESTION, bird = birdAt == NOT_SAID_YET) }
                    if (ui.lines.isEmpty()) {
                        // One card at most: the records hold every call. The last one stands in only when there are none (they are off)
                        if (recordsCount > 0) {
                            item { RecordsCard(recordsCount, onOpenRecords) }
                        } else {
                            lastCall?.let { call ->
                                item {
                                    InAiColumn {
                                        Section("前回の電話") {
                                            Text(call.headline, style = MaterialTheme.typography.bodyLarge)
                                            Small(call.where)
                                            TextButton(onClick = onShowLastCall) { Text("結果を見る") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item(key = "hero") {
                        val hero = requestHero(practice)
                        AitelHero(HeroKind.REQUEST, "依頼", hero.first, hero.second, hero.mood)
                    }
                }

                if (showDetails && hasDetails) {
                    Column(Modifier.heightIn(max = detailsMax).verticalScroll(rememberScrollState())) {
                        KeyFacts("いまの内容") {
                            for ((label, value) in summary) KeyFact(label, listOf(value), LocalAitelColors.current.heroRequest, big = false)
                            if (ui.forTheForm.isNotEmpty()) {
                                Text("${ui.forTheForm.joinToString("、")}は、「手で入力する」で入れてください", style = MaterialTheme.typography.bodySmall)
                            }
                            if (ui.missing.isNotEmpty()) {
                                Text("まだ足りないこと：", style = MaterialTheme.typography.bodySmall)
                                for (m in ui.missing) Text("・$m", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        if (complete && unsent) Small("書きかけの文があります。送るか、消してから進んでください")
        if (complete && notApplied) Small("最後の文は、まだ内容に入っていません")
        if (complete && !unsent && !notApplied) {
            MainButton(onClick = onToConfirm, Modifier.fillMaxWidth()) { Text("確認へ") }
        }

        if (hasKey) {
            Composer(
                text = ui.typing,
                canSend = !ui.waiting && ui.typing.isNotBlank() && !ui.full,
                onChange = onType,
                onSend = { onSay(ui.typing) },
            ) {
                MoreMenu(
                    waiting = ui.waiting,
                    hasDetails = hasDetails,
                    showingDetails = showDetails,
                    canRestart = ui.lines.isNotEmpty(),
                    onByHand = onByHand,
                    onToggleDetails = { showDetails = !showDetails },
                    onRestart = onRestart,
                )
            }
        } else {
            Notice("会話で依頼を作るには、Claude の API キーが要ります。設定で貼り付けてください")
            MainButton(onClick = onOpenSettings, Modifier.fillMaxWidth(), compact = true) { Text("設定を開く") }
            TextButton(onClick = onByHand, Modifier.padding(bottom = 4.dp)) { Text("手で入力する") }
        }
    }
}

/** Where the user writes: one rounded piece with the menu, the words and the send button. */
@Composable
private fun Composer(text: String, canSend: Boolean, onChange: (String) -> Unit, onSend: () -> Unit, menu: @Composable RowScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = LocalAitelColors.current.card,
        contentColor = scheme.onSurface,
    ) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.Bottom) {
            Row(Modifier.padding(bottom = COMPOSER_BUTTON_LIFT)) { menu() }
            TextField(
                value = text,
                onValueChange = onChange,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyLarge,
                placeholder = { Text("ここに書く") },
                maxLines = INPUT_MAX_LINES,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            FilledIconButton(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier.padding(bottom = COMPOSER_BUTTON_LIFT).size(SEND_BUTTON),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = scheme.inverseSurface,
                    contentColor = scheme.inverseOnSurface,
                    disabledContainerColor = scheme.onSurface.copy(alpha = 0.08f),
                    disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
                ),
            ) {
                Icon(painterResource(R.drawable.ic_send), contentDescription = "送る")
            }
        }
    }
}

/** What is not the conversation itself. */
@Composable
private fun MoreMenu(
    waiting: Boolean,
    hasDetails: Boolean,
    showingDetails: Boolean,
    canRestart: Boolean,
    onByHand: () -> Unit,
    onToggleDetails: () -> Unit,
    onRestart: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, Modifier.semantics { contentDescription = "そのほかの操作" }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("手で入力する") }, enabled = !waiting, onClick = {
                open = false
                onByHand()
            })
            if (hasDetails) {
                DropdownMenuItem(text = { Text(if (showingDetails) "いまの内容を閉じる" else "いまの内容") }, onClick = {
                    open = false
                    onToggleDetails()
                })
            }
            if (canRestart) {
                DropdownMenuItem(text = { Text("やり直す") }, enabled = !waiting, onClick = {
                    open = false
                    onRestart()
                })
            }
        }
    }
}

/** The rest of the conversation lines up with the AI's words. */
@Composable
private fun InAiColumn(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(start = AI_COLUMN)) { content() }
}

/** The AI's side: a place for the bird, then [content]. */
@Composable
private fun AiRow(bird: (@Composable () -> Unit)?, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AVATAR_GAP)) {
        Box(Modifier.size(AVATAR), contentAlignment = Alignment.Center) { bird?.invoke() }
        content()
    }
}

/** A bubble whose top corner on the speaker's side is nearly square, so it points at the speaker. */
@Composable
private fun aiShape(): Shape = MaterialTheme.shapes.large.copy(topStart = CornerSize(BUBBLE_POINT))

@Composable
private fun myShape(): Shape = MaterialTheme.shapes.large.copy(topEnd = CornerSize(BUBBLE_POINT))

/** A question, line by line. A wrapped 「・」 line stays right of its mark; read aloud as one text. */
@Composable
private fun QuestionText(text: String) {
    val style = MaterialTheme.typography.bodyLarge
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (line in text.lines()) {
            Text(line, style = if (line.startsWith("・")) style.copy(textIndent = TextIndent(restLine = 1.em)) else style)
        }
    }
}

/** Ways to begin, under the first question. A tap only writes the words in the box: nothing is sent. */
@Composable
private fun Starters(onPick: (String) -> Unit) {
    InAiColumn {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (starter in STARTERS) {
                MainOutlinedButton(onClick = { onPick(starter) }, compact = true) { Text(starter, maxLines = 1) }
            }
        }
    }
}

/** So that it is plain that an answer is on its way. */
@Composable
private fun Thinking(onStop: () -> Unit) {
    AiRow(bird = { AitelFace(AitelMood.ASKING, Modifier.size(AVATAR)) }) {
        Surface(Modifier.weight(1f, fill = false), shape = aiShape(), color = LocalAitelColors.current.card, contentColor = MaterialTheme.colorScheme.onSurface) {
            Row(Modifier.padding(start = BUBBLE_PADDING_X, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("考えています…", Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onStop) { Text("やめる") }
            }
        }
    }
}

@Composable
private fun Searching() {
    AiRow(bird = { WaitingMark() }) {
        Surface(Modifier.weight(1f, fill = false), shape = aiShape(), color = LocalAitelColors.current.card, contentColor = MaterialTheme.colorScheme.onSurface) {
            Text("お店をウェブ検索で調べています", Modifier.padding(horizontal = BUBBLE_PADDING_X, vertical = BUBBLE_PADDING_Y), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The AI's side. The bird is the still picture: only the one that is thinking moves. */
@Composable
private fun AiBubble(text: String, bird: Boolean) {
    val face: (@Composable () -> Unit)? = if (bird) {
        {
            Image(painterResource(R.drawable.aitel_bird_explain_still), contentDescription = null, Modifier.size(AVATAR).clearAndSetSemantics {})
        }
    } else {
        null
    }
    AiRow(bird = face) {
        Surface(Modifier.weight(1f, fill = false), shape = aiShape(), color = LocalAitelColors.current.card, contentColor = MaterialTheme.colorScheme.onSurface) {
            Text(text, Modifier.padding(horizontal = BUBBLE_PADDING_X, vertical = BUBBLE_PADDING_Y), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The user's side: the color of the hero above, at the width of what was said. A long press selects the words, to copy them. */
@Composable
private fun MyBubble(text: String) {
    val colors = LocalAitelColors.current
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(1f - BUBBLE_WIDTH))
        Box(Modifier.weight(BUBBLE_WIDTH), contentAlignment = Alignment.CenterEnd) {
            Surface(shape = myShape(), color = colors.heroRequest, contentColor = colors.ink) {
                SelectionContainer {
                    Text(text, Modifier.padding(horizontal = BUBBLE_PADDING_X, vertical = BUBBLE_PADDING_Y), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private const val BUBBLE_WIDTH = 0.85f
private const val INPUT_MAX_LINES = 5
private const val DETAILS_MAX_HEIGHT = 240
private const val FIRST_QUESTION = "お店の名前と、してほしいことを教えてください"
private val STARTERS = listOf("予約したい", "変更したい", "取り消したい", "問い合わせたい")

/** No bird in the conversation: the one that is thinking has its own row. */
private const val NO_BIRD = -2

/** The result of indexOfLast with no AI line yet: the bird is at the first question. */
private const val NOT_SAID_YET = -1
private val AVATAR = 44.dp
private val AVATAR_GAP = 8.dp
private val AI_COLUMN = AVATAR + AVATAR_GAP
private val LINE_GAP = 10.dp
private val BUBBLE_POINT = 6.dp
private val BUBBLE_PADDING_X = 14.dp
private val BUBBLE_PADDING_Y = 10.dp
private val SEND_BUTTON = 48.dp
private val COMPOSER_BUTTON_LIFT = 4.dp
