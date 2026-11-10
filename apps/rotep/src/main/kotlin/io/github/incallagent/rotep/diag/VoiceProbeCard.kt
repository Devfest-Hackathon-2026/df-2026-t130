// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.diag

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.incallagent.agent.live.LiveAuth
import io.github.incallagent.rotep.app.ApiKeyStore
import io.github.incallagent.rotep.app.RotepLog
import io.github.incallagent.rotep.app.SettingsStore
import io.github.incallagent.rotep.app.UserMessages
import io.github.incallagent.rotep.call.ExecutorScheduler
import io.github.incallagent.rotep.call.LocalAudioPath
import io.github.incallagent.rotep.call.dual.DualProbe
import io.github.incallagent.rotep.call.dual.DualProbePhase
import io.github.incallagent.rotep.call.dual.DualProbeState
import io.github.incallagent.rotep.call.dual.OkHttpVoiceSockets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One try at a time. Lives outside the card, which leaves the composition when the list scrolls past it.
 * After [start], everything but the state runs on the try's own thread.
 */
internal object VoiceProbeRunner {
    private val mutableState = MutableStateFlow(DualProbeState())
    val state: StateFlow<DualProbeState> = mutableState.asStateFlow()

    @Volatile
    private var scheduler: ExecutorScheduler? = null
    private var probe: DualProbe? = null

    /** On the main thread. */
    fun start(context: Context) {
        if (scheduler != null) return
        val app = context.applicationContext
        mutableState.value = DualProbeState(phase = DualProbePhase.CONNECTING)
        val next = ExecutorScheduler("voice-probe", ::failed)
        scheduler = next
        // The key store can be slow: not on the main thread
        next.post { begin(app, next) }
    }

    fun cancel() {
        scheduler?.post { probe?.cancel() }
    }

    private fun begin(app: Context, on: ExecutorScheduler) {
        val store = SettingsStore(app)
        val key = store.readApiKey() as? ApiKeyStore.Read.Key
        if (key == null) {
            end(DualProbeState(phase = DualProbePhase.DONE, problem = store.keyProblem ?: UserMessages.API_KEY_MISSING))
            return
        }
        probe = DualProbe(
            OkHttpVoiceSockets(),
            LiveAuth.ApiKey(key.value),
            store.load().voice.voiceName,
            LocalAudioPath(app, loudspeaker = true, RotepLog.audio),
            on,
            { RotepLog.i("dual", it) },
            { if (it.phase == DualProbePhase.DONE) end(it) else mutableState.value = it },
        ).also { it.start() }
    }

    private fun failed(t: Throwable) {
        RotepLog.w("dual", "probe error ${t.javaClass.simpleName}")
        val running = probe
        if (running != null) {
            running.cancel()
        } else {
            end(DualProbeState(phase = DualProbePhase.DONE, problem = "試せませんでした（${t.javaClass.simpleName}）"))
        }
    }

    private fun end(last: DualProbeState) {
        probe = null
        scheduler?.shutdown()
        scheduler = null
        // Last: this brings the button back
        mutableState.value = last
    }
}

/** Tries the ears and the mouth of the voice model before anything else of a call is used. */
@Composable
internal fun VoiceProbeCard() {
    val context = LocalContext.current
    val state by VoiceProbeRunner.state.collectAsState()
    // Without the permission the try still opens the sockets, and then says that the microphone is the problem
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        VoiceProbeRunner.start(context)
    }
    val free = state.phase == DualProbePhase.IDLE || state.phase == DualProbePhase.DONE
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("声の接続を試す", style = MaterialTheme.typography.titleSmall)
            Text(
                "電話はかけません。保存してある Gemini のキーで耳と口につなぎ、スピーカーから 1 文を読み上げたあと、" +
                    "8 秒の間マイクの音を耳に流します。話した文字は、試している間だけ出して、残しません。",
                fontSize = 13.sp,
            )
            Button(
                onClick = {
                    val mic = Manifest.permission.RECORD_AUDIO
                    if (context.checkSelfPermission(mic) == PackageManager.PERMISSION_GRANTED) {
                        VoiceProbeRunner.start(context)
                    } else {
                        askMicrophone.launch(mic)
                    }
                },
                enabled = free,
            ) { Text("声の接続を試す") }
            phaseWords(state.phase)?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (state.liveText.isNotEmpty()) Text(state.liveText, fontSize = 14.sp)
            state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.phase != DualProbePhase.IDLE && state.problem == null) {
                state.result.screenLines().forEach { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            }
            if (state.phase == DualProbePhase.DONE && state.problem == null) {
                Text("読み上げの一致は、文字が漢字や数字で返ると下がります。読み上げは耳でも確かめてください。", fontSize = 12.sp)
            }
        }
    }
}

private fun phaseWords(phase: DualProbePhase): String? = when (phase) {
    DualProbePhase.IDLE -> null
    DualProbePhase.CONNECTING -> "耳と口につないでいます…"
    DualProbePhase.SPEAKING -> "読み上げています。スピーカーを聞いてください"
    DualProbePhase.LISTENING -> "いま話しかけてください"
    DualProbePhase.WAITING_FOR_TEXT -> "文字になるのを待っています…"
    DualProbePhase.DONE -> "終わりました"
}
