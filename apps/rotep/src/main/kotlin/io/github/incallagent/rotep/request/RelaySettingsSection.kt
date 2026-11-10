// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.request

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.incallagent.rotep.ui.ErrorText
import io.github.incallagent.rotep.ui.Field
import io.github.incallagent.rotep.ui.MainButton
import io.github.incallagent.rotep.ui.Section
import io.github.incallagent.rotep.ui.Small

/** The server that lets other AIs (Muse, Grok) ask this phone for a call. Empty: nothing happens. */
@Composable
fun RelaySettingsSection() {
    val state by PhoneRelay.state.collectAsState()
    var url by remember { mutableStateOf(PhoneRelay.url.orEmpty()) }
    var token by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(PhoneRelay.hasToken) }
    var problem by remember { mutableStateOf<String?>(null) }
    // A new address needs the token typed again: the old one must not go to another server
    val keepsToken = saved && normalizedRelayUrl(url) == PhoneRelay.url
    Section("Grok・Muse からの依頼（サーバー）") {
        Small("Grok や Muse に電話を頼めるようにします。頼んだ内容は、自分のサーバーを通してこのスマホに届きます。設定しなければ何もしません。届いても、確認の画面で承認するまで電話はかかりません。")
        Field("サーバーの URL", url, keyboard = KeyboardType.Uri, supporting = "https:// で始まる URL を入れます") { url = it }
        if (keepsToken) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("端末のトークンは保存済みです", Modifier.weight(1f))
                TextButton(
                    onClick = {
                        problem = null
                        PhoneRelay.clear {
                            saved = false
                            url = ""
                        }
                    },
                ) { Text("消す") }
            }
        } else {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("端末のトークンを貼り付け") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        MainButton(
            onClick = {
                problem = null
                val typed = token
                PhoneRelay.save(url, typed) { why ->
                    problem = why
                    if (why == null) {
                        token = ""
                        saved = saved || typed.isNotBlank()
                    }
                }
            },
            enabled = url.isNotBlank() && (keepsToken || token.isNotBlank()),
            compact = true,
        ) { Text("保存") }
        problem?.let { ErrorText(it) }
        when (val s = state) {
            RelayState.Off -> {}
            RelayState.Ready -> Small("設定しました。アプリを開いている間と、15 分ごとに取りに行きます")
            RelayState.Connected -> Small("サーバーにつながっています")
            is RelayState.Failed -> ErrorText(s.reason)
        }
    }
}
