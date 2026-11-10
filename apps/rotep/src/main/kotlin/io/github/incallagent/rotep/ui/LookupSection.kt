// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.incallagent.rotep.app.RotepLog
import io.github.incallagent.rotep.research.ShopFindings

/** The look-up before the call, on the confirm screen. See [io.github.incallagent.rotep.research.ShopLookup]. */
sealed interface LookupUi {
    /** Not offered (a cancel, the practice shop, no API key). */
    data object None : LookupUi

    /** Offered: nothing is sent until the user presses the button. */
    data object Available : LookupUi

    data object Searching : LookupUi

    /** It could not be looked up: the call can still go ahead. */
    data class NotFound(val reason: String) : LookupUi

    /** [reviewed]: the user has read it and pressed "確かめた". */
    data class Found(val findings: ShopFindings, val reviewed: Boolean = false) : LookupUi

    /** Why "承認" has to wait, or null. */
    val holdsApproval: String?
        get() = when (this) {
            Searching -> "お店のことを調べています（待たないときは「待たずに進む」）"
            is Found -> if (reviewed) null else "調べたことを確かめて、「確かめた」か「依頼を直す」を押してください"
            None, Available, is NotFound -> null
        }
}

@Composable
fun LookupSection(lookup: LookupUi, onLookUp: () -> Unit, onReviewed: () -> Unit, onSkip: () -> Unit, onFix: () -> Unit) {
    when (lookup) {
        LookupUi.None -> {}
        LookupUi.Available -> Section("電話の前の下調べ") {
            Small(
                "お店の営業時間や予約の受け方を、Google 検索で調べられます。押すと、お店の名前と番号、希望の日時と人数を Google に送ります（Google 側に一定の期間残ります）。お店ではない番号では押さないでください",
            )
            OutlinedButton(onClick = onLookUp) { Text("お店を調べる") }
        }
        LookupUi.Searching -> Section("電話の前の下調べ") {
            Small("Google 検索で、お店の営業時間や予約の受け方を調べています")
            TextButton(onClick = onSkip) { Text("待たずに進む") }
        }
        is LookupUi.NotFound -> Section("電話の前の下調べ") { Small(lookup.reason) }
        is LookupUi.Found -> Section("電話の前に調べたこと") {
            SearchFindings(lookup.findings)
            if (lookup.reviewed) {
                Small("確かめました")
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MainOutlinedButton(onClick = onFix, compact = true) { Text("依頼を直す") }
                    MainButton(onClick = onReviewed, compact = true) { Text("確かめた") }
                }
            }
        }
    }
}

/** What the search found, as the model wrote it (the terms of the search do not allow changing it), with its Search Suggestions. */
@Composable
fun SearchFindings(findings: ShopFindings) {
    Small(if (findings.suggestionsHtml.isEmpty()) "ウェブ検索の結果です。古いことや、別のお店のことがあります" else "Google 検索の結果です。古いことや、別のお店のことがあります")
    Text(findings.text, style = MaterialTheme.typography.bodyMedium)
    val open = LocalUriHandler.current
    for (s in findings.sources) {
        TextButton(onClick = { runCatching { open.openUri(s.url) } }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(s.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    for (html in findings.suggestionsHtml) SearchSuggestions(html)
}

/**
 * The Search Suggestions as Google sent them. No script runs here and nothing on the device can be
 * reached from the page; a link the user taps opens in their browser, with nothing in between.
 */
@Composable
private fun SearchSuggestions(html: String) {
    var gone by remember(html) { mutableStateOf(false) }
    if (gone) {
        Small("検索の候補を表示できませんでした")
        return
    }
    AndroidView(
        factory = { context ->
            try {
                WebView(context).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    setBackgroundColor(0)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (request.isForMainFrame && request.hasGesture()) openInBrowser(view.context, request.url)
                            return true
                        }

                        // Without this, a crash of the page's renderer takes the whole app with it
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            gone = true
                            return true
                        }
                    }
                }
            } catch (t: Throwable) {
                // The phone's WebView can be missing, or in the middle of an update
                RotepLog.w("lookup", "no web view: ${t.javaClass.simpleName}")
                gone = true
                View(context)
            }
        },
        update = { (it as? WebView)?.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) },
        onRelease = { view ->
            (view as? WebView)?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            }
        },
        modifier = Modifier.fillMaxWidth().height(72.dp),
    )
}

private fun openInBrowser(context: Context, uri: Uri) {
    if (uri.scheme != "https") return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
