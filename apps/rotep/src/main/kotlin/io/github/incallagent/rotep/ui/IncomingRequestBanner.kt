// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.R
import io.github.incallagent.rotep.request.PhoneRequestStore

/**
 * A request from another AI waits for the user, above whatever they are doing. Nothing changes on
 * the screen by itself: what they were reading or about to approve stays until they tap here.
 */
@Composable
fun IncomingRequestBanner(
    request: PhoneRequestStore.Incoming,
    onOpen: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val who = requestSenderName(request.assistant)
    Card(
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallIcon(R.drawable.ic_info)
                Text(incomingTitle(request), style = MaterialTheme.typography.labelLarge)
            }
            Text(incomingLine(request), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDecline, Modifier.semantics { contentDescription = "$who からの依頼を断る" }) { Text("断る") }
                if (!request.opened) {
                    Button(onClick = onOpen, Modifier.semantics { contentDescription = "$who からの依頼の内容を見る" }) { Text("内容を見る") }
                }
            }
        }
    }
}

/** Who sent a request. Only names the app knows: what comes from the server is never shown as it is. */
fun requestSenderName(assistant: String?): String = when (assistant) {
    "grok" -> "Grok"
    "muse" -> "Muse"
    else -> "ほかの AI"
}

fun incomingTitle(request: PhoneRequestStore.Incoming): String {
    val who = requestSenderName(request.assistant)
    return if (request.opened) "$who からの依頼を開いています" else "$who から電話の依頼が届きました"
}

fun incomingLine(request: PhoneRequestStore.Incoming): String {
    val what = when (request.purpose) {
        Purpose.NEW_BOOKING -> "新しい予約"
        Purpose.CHANGE -> "予約の変更"
        Purpose.CANCEL -> "予約の取り消し"
        Purpose.INQUIRY -> "問い合わせ"
    }
    return "${request.shopName}（$what）"
}
