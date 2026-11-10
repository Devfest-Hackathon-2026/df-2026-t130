// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.history

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.incallagent.rotep.app.RotepApp
import io.github.incallagent.rotep.history.BookingHistory
import io.github.incallagent.rotep.history.Entry
import io.github.incallagent.rotep.ui.ErrorText
import io.github.incallagent.rotep.ui.MainButton
import io.github.incallagent.rotep.ui.MainOutlinedButton
import io.github.incallagent.rotep.ui.ScreenName
import io.github.incallagent.rotep.ui.Section
import io.github.incallagent.rotep.ui.Small
import io.github.incallagent.rotep.ui.SwitchRow
import io.github.incallagent.rotep.ui.isShortScreen
import io.github.incallagent.rotep.ui.theme.LocalAitelColors

/** The section on the settings screen: the switch of 「電話の記録」, and one of the two ways in (ADR-045, ADR-050). */
@Composable
fun BookingHistorySection(keep: Boolean, onKeep: (Boolean) -> Unit) {
    val state by BookingHistory.state.collectAsState()
    LaunchedEffect(Unit) { BookingHistory.refresh() }
    BookingHistorySectionContent(keep, state.entries.size, state.problem, onKeep, RotepApp::openHistory)
}

@Composable
fun BookingHistorySectionContent(keep: Boolean, count: Int, problem: String?, onKeep: (Boolean) -> Unit, onOpen: () -> Unit) {
    Section("電話の記録") {
        SwitchRow("電話の記録を端末に残す", keep, onChange = onKeep)
        Small("オンのときは、実際にかけた電話の店名、電話番号、用件、取れた日時、人数、予約名、結果、要約と、お店と AI の会話の文字を、暗号化してこの端末の中だけに残します。お店の人の発言の文字も残ります。残したくないときはオフにしてください。")
        Small("音声と練習は残しません。次の電話の AI には渡しません。90 日を過ぎたものと、50 件を超えた古いものは、アプリを開いたときなどに自動で消します。オフにしても、このほかの記録は消えません。")
        problem?.let { ErrorText(it) }
        when {
            count > 0 -> MainOutlinedButton(onClick = onOpen, compact = true) { Text("記録を見る・消す（$count 件）") }
            keep -> Small("記録はまだありません")
        }
    }
}

/** The low, full-width card on the first screen. The other way in, besides the settings. */
@Composable
fun RecordsCard(count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.medium,
        colors = recordColors(),
    ) {
        Text(
            recordsCardText(count),
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** The list of kept calls and, when one is open, that call. Back from a call leads to the list. */
@Composable
fun BookingHistoryScreen() {
    val state by BookingHistory.state.collectAsState()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { BookingHistory.refresh() }
    val open = state.entries.firstOrNull { it.id == openId }
    BackHandler { if (open != null) openId = null else RotepApp.closeHistory() }
    if (open == null) {
        HistoryListContent(
            entries = state.entries,
            problem = state.problem,
            onOpen = { openId = it.id },
            onCopy = { copyRecord(context, it) },
            onDeleteAll = BookingHistory::deleteAll,
            onBack = RotepApp::closeHistory,
            listState = listState,
        )
    } else {
        HistoryDetailContent(
            entry = open,
            onCopy = { copyRecord(context, open) },
            onDelete = {
                BookingHistory.delete(open.id)
                openId = null
            },
            onBack = { openId = null },
        )
    }
}

/**
 * The whole call, as text, on the clipboard. Marked sensitive: it holds a phone number and what
 * people said, and the system then keeps it out of its previews.
 */
// EXTRA_IS_SENSITIVE is a constant compiled in. Android 12 and 12L, where it is new, ignore the flag
@SuppressLint("InlinedApi")
private fun copyRecord(context: Context, entry: Entry) {
    val clip = ClipData.newPlainText("電話の記録", copyText(entry))
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    val copied = runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip) }.isSuccess
    when {
        !copied -> Toast.makeText(context, "コピーできませんでした", Toast.LENGTH_SHORT).show()
        showsOwnCopyNotice(Build.VERSION.SDK_INT) -> Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun HistoryListContent(
    entries: List<Entry>,
    problem: String?,
    onOpen: (Entry) -> Unit,
    onCopy: (Entry) -> Unit,
    onDeleteAll: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    var confirming by remember { mutableStateOf(false) }
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 12.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenName("電話の記録") }
        problem?.let { item { ErrorText(it) } }
        if (entries.isEmpty()) item { Small("記録はありません") }
        items(entries, key = { it.id }) { entry -> EntryCard(entry, onOpen = { onOpen(entry) }, onCopy = { onCopy(entry) }) }
        if (entries.isNotEmpty()) item { TextButton(onClick = { confirming = true }) { Text("全部消す") } }
        item { MainButton(onClick = onBack, Modifier.fillMaxWidth().padding(bottom = 24.dp), compact = isShortScreen()) { Text("戻る") } }
    }
    if (confirming) {
        ConfirmDelete(
            title = "記録を全部消しますか？",
            text = "${entries.size} 件の記録を、この端末から消します。元に戻せません。",
            onConfirm = {
                confirming = false
                onDeleteAll()
            },
            onDismiss = { confirming = false },
        )
    }
}

/** One call. The result in words of its own: a booking that failed the check does not read as booked. */
@Composable
private fun EntryCard(entry: Entry, onOpen: () -> Unit, onCopy: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = recordColors()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Small(callTime(entry.at))
            Text(entry.shopName, style = MaterialTheme.typography.titleMedium)
            Text(entry.headline, style = MaterialTheme.typography.bodyMedium)
            if (entry.summary.isNotBlank()) {
                Text(
                    entry.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CopyButton(entry, onCopy, Modifier.align(Alignment.End))
        }
    }
}

@Composable
private fun CopyButton(entry: Entry, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    val description = copyDescription(entry)
    TextButton(onClick = onCopy, modifier = modifier.semantics { contentDescription = description }) { Text("コピー") }
}

@Composable
private fun recordColors() = CardDefaults.cardColors(
    containerColor = LocalAitelColors.current.card,
    contentColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
fun HistoryDetailContent(entry: Entry, onCopy: () -> Unit, onDelete: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var confirming by remember { mutableStateOf(false) }
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenName(entry.shopName) }
        // Selectable: a part of it can be copied too
        item {
            SelectionContainer {
                Section("内容") {
                    for (row in historyRows(entry)) Fact(row)
                }
            }
        }
        if (entry.transcript.isNotEmpty()) {
            item {
                SelectionContainer {
                    Section("会話") {
                        Small("聞き取りの結果です。実際の言葉と違うことがあります")
                        for (line in entry.transcript) Text(transcriptLine(line), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item {
            val description = copyDescription(entry)
            MainOutlinedButton(onClick = onCopy, Modifier.fillMaxWidth().semantics { contentDescription = description }, compact = isShortScreen()) { Text("コピー") }
        }
        item { TextButton(onClick = { confirming = true }) { Text("この記録を消す") } }
        item { MainButton(onClick = onBack, Modifier.fillMaxWidth().padding(bottom = 24.dp), compact = isShortScreen()) { Text("一覧に戻る") } }
    }
    if (confirming) {
        ConfirmDelete(
            title = "この記録を消しますか？",
            text = "${entry.shopName} の記録を、この端末から消します。元に戻せません。",
            onConfirm = {
                confirming = false
                onDelete()
            },
            onDismiss = { confirming = false },
        )
    }
}

/** A label and its value, read as one by screen readers. */
@Composable
private fun Fact(row: HistoryRow) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Small(row.label)
        Text(row.value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ConfirmDelete(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text) },
        confirmButton = { MainButton(onClick = onConfirm, compact = true) { Text("消す") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}
