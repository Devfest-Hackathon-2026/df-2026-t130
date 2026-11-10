// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.about

import android.content.res.AssetManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.incallagent.rotep.BuildConfig
import io.github.incallagent.rotep.R
import io.github.incallagent.rotep.ui.ErrorText
import io.github.incallagent.rotep.ui.MainButton
import io.github.incallagent.rotep.ui.MainOutlinedButton
import io.github.incallagent.rotep.ui.ScreenName
import io.github.incallagent.rotep.ui.Section
import io.github.incallagent.rotep.ui.Small
import io.github.incallagent.rotep.ui.component.AitelTitle
import io.github.incallagent.rotep.ui.isShortScreen
import io.github.incallagent.rotep.ui.theme.LocalAitelColors
import io.github.incallagent.rotep.ui.theme.RotepTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SOURCE_URL = "https://github.com/Devfest-Hackathon-2026/df-2026-t130"
private const val PRIVACY_URL = "$SOURCE_URL/blob/main/docs/privacy.md"

/**
 * 「このアプリについて」: the app's name and version, its license, the two links that leave the app,
 * and the license texts. Opened from the settings only. A browser opens only when a link is pressed.
 */
class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // System bar icons that follow the light/dark theme
        enableEdgeToEdge()
        setContent {
            RotepTheme {
                Scaffold(containerColor = LocalAitelColors.current.pageQuiet) { padding -> AboutScreen(::finish, Modifier.padding(padding)) }
            }
        }
    }
}

@Composable
private fun AboutScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    var reading by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = reading) { reading = false }
    if (reading) {
        val assets = LocalContext.current.assets
        val documents by produceState<List<LicenseDocument>?>(null, assets) {
            value = withContext(Dispatchers.IO) { readLicenses(assets) }
        }
        LicensesContent(documents, onBack = { reading = false }, modifier)
    } else {
        val links = LocalUriHandler.current
        // No browser on the phone: nothing happens
        AboutContent(BuildConfig.VERSION_NAME, onOpenLink = { url -> runCatching { links.openUri(url) } }, onReadLicenses = { reading = true }, onClose, modifier)
    }
}

private fun readLicenses(assets: AssetManager): List<LicenseDocument> = LICENSE_FILES.map { license ->
    val text = runCatching { assets.open("$LICENSE_ASSETS/${license.file}").bufferedReader().use { it.readText() } }.getOrNull()
    LicenseDocument(license.title, text?.let(::licenseParagraphs).orEmpty())
}

/** What the screen shows. Apart from the activity, so that a preview can show it. */
@Composable
internal fun AboutContent(
    version: String,
    onOpenLink: (String) -> Unit,
    onReadLicenses: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenName("このアプリについて") }
        item {
            Section("アプリ") {
                AitelTitle()
                Small("版：$version")
            }
        }
        item {
            Section("ライセンス") {
                Text("このアプリは GNU General Public License v3.0（GPL-3.0）で公開しています。", style = MaterialTheme.typography.bodyMedium)
                Small(SOURCE_URL)
                LinkButton("ソースコードを見る") { onOpenLink(SOURCE_URL) }
            }
        }
        item {
            Section("プライバシー") {
                Small("何をどこへ送り、何をスマホに残すかをまとめたページです。")
                LinkButton("プライバシーのページを開く") { onOpenLink(PRIVACY_URL) }
            }
        }
        item {
            Section("オープンソースのライセンス") {
                Small("このアプリが使っている部品と素材のライセンスの全文を、この画面で読めます。")
                MainOutlinedButton(onClick = onReadLicenses, Modifier.fillMaxWidth(), compact = true) { Text("全文を読む") }
            }
        }
        item {
            MainButton(onClick = onBack, Modifier.fillMaxWidth().padding(bottom = 24.dp), compact = isShortScreen()) { Text("戻る") }
        }
    }
}

/** Opens a browser: the icon and the spoken words say so. */
@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    MainOutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label。ブラウザが開きます" },
        compact = true,
    ) {
        Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(label)
    }
}

/** The license texts, one after the other. [documents] null: still being read. */
@Composable
internal fun LicensesContent(documents: List<LicenseDocument>?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ScreenName("オープンソースのライセンス") }
        if (documents == null) item { Small("読み込んでいます") }
        documents?.forEach { document ->
            item {
                Text(document.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
            }
            if (document.paragraphs.isEmpty()) item { ErrorText("読み込めませんでした") }
            items(document.paragraphs) { Small(it) }
        }
        item {
            MainButton(onClick = onBack, Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp), compact = isShortScreen()) { Text("戻る") }
        }
    }
}
