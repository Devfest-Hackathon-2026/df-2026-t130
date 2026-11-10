// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.incallagent.rotep.app.RotepApp
import io.github.incallagent.rotep.app.RotepApp.Screen
import io.github.incallagent.rotep.ui.component.AitelTitle
import io.github.incallagent.rotep.ui.history.BookingHistoryScreen
import io.github.incallagent.rotep.ui.theme.LocalAitelColors

/** The whole app: a header, a message line and the current screen (no navigation library). */
@Composable
fun RotepUi(activity: Activity, modifier: Modifier = Modifier) {
    val screen by RotepApp.screen.collectAsState()
    val message by RotepApp.message.collectAsState()
    val call by RotepApp.call.collectAsState()
    // With no call behind the result screen, it shows the result kept from an earlier one
    val kept by RotepApp.lastResult.collectAsState()
    val result = if (call == null) kept?.result else call?.session?.state?.collectAsState()?.value?.result
    val verified = result != null && resultIsGood(resultHeadline(result)) && call?.stage != RotepApp.Stage.L0
    val incoming by RotepApp.incoming.collectAsState()
    val scheduled by RotepApp.scheduled.collectAsState()
    // On the first screen, and on the form that is reached from it
    val scheduledBand: (@Composable () -> Unit)? = scheduled?.takeIf { screen == Screen.INTAKE || screen == Screen.REQUEST }?.let { info ->
        { ScheduledCallBanner(info, RotepApp::cancelScheduledCall) }
    }

    BackHandler(enabled = screen == Screen.CONFIRM || screen == Screen.SETTINGS || screen == Screen.RESULT || screen == Screen.REQUEST) {
        when (screen) {
            Screen.CONFIRM -> RotepApp.fixRequest()
            Screen.REQUEST -> RotepApp.openIntake()
            Screen.SETTINGS -> RotepApp.closeSettings()
            Screen.RESULT -> RotepApp.newRequest()
            // HISTORY has its own: back from a call leads to the list
            Screen.INTAKE, Screen.CALL, Screen.HISTORY -> {}
        }
    }

    RotepFrame(
        screen = screen,
        message = message,
        onOpenSettings = { RotepApp.show(Screen.SETTINGS) },
        onDismissMessage = { RotepApp.message.value = null },
        modifier = modifier,
        verifiedResult = verified,
        // Not over a call or its result: opening a request would leave that result behind for good
        banner = bothBanners(
            when {
                incoming == null -> null
                call == null -> incoming?.let { request ->
                    { IncomingRequestBanner(request, RotepApp::openIncoming, RotepApp::declineIncoming) }
                }
                screen == Screen.RESULT -> {
                    { Notice("ほかの AI から依頼が届いています。「最初に戻る」のあとで見られます") }
                }
                else -> null
            },
            scheduledBand,
        ),
    ) {
        when (screen) {
            Screen.REQUEST -> RequestScreen()
            Screen.INTAKE -> IntakeScreen()
            Screen.CONFIRM -> ConfirmScreen()
            Screen.CALL -> CallScreen()
            Screen.RESULT -> ResultScreen()
            Screen.SETTINGS -> SettingsScreen(activity)
            Screen.HISTORY -> BookingHistoryScreen()
        }
    }
}

/**
 * The frame around every screen: the app's title "アイてる!? - AITEL -" and "設定", the message
 * line (an error or a warning), then [content].
 * Each screen shows its own name as its first heading (in its hero, or above its content). The whole
 * page, under the system bars too, takes the screen's color; a result only when [verifiedResult].
 */
@Composable
fun RotepFrame(
    screen: Screen,
    message: String?,
    onOpenSettings: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
    verifiedResult: Boolean = false,
    banner: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = LocalAitelColors.current
    val target = when (screen) {
        Screen.REQUEST, Screen.INTAKE -> colors.pageRequest
        Screen.CONFIRM -> colors.pageConfirm
        Screen.CALL -> colors.pageCall
        Screen.RESULT -> if (verifiedResult) colors.pageGood else colors.pageQuiet
        Screen.SETTINGS, Screen.HISTORY -> colors.pageQuiet
    }
    val page by animateColorAsState(target, MaterialTheme.motionScheme.slowEffectsSpec(), label = "page")
    Column(Modifier.background(page).then(modifier).fillMaxSize().imePadding(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AitelTitle(Modifier.weight(1f))
            // Not on the records either: they can be opened from the settings, and "戻る" there would lead back here
            if (screen != Screen.SETTINGS && screen != Screen.HISTORY) {
                TextButton(onClick = onOpenSettings) { Text("設定") }
            }
        }
        message?.let {
            Box(Modifier.padding(horizontal = 12.dp)) {
                Notice(it, error = true, sorry = true)
            }
            TextButton(onClick = onDismissMessage, Modifier.padding(start = 4.dp)) { Text("閉じる") }
        }
        banner?.let {
            Box(Modifier.padding(horizontal = 12.dp)) { it() }
        }
        Box(Modifier.weight(1f)) {
            content()
        }
    }
}
