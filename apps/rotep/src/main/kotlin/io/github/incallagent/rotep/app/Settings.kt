// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import android.content.Context
import androidx.core.content.edit
import io.github.incallagent.rotep.call.AgentVoice
import io.github.incallagent.rotep.call.ShellMode

/**
 * Where the call audio goes. [shell] null: the phone's own microphone and speaker (no call).
 * Settings keep it by name ([readSavedAudio] reads what earlier versions saved). The helper's other
 * ways to reach the call (ShellMode) remain for the tests of the diagnostics screen.
 */
enum class AudioChoice(val shell: ShellMode?) {
    LOCAL(null),
    SHELL_DIRECT(ShellMode.DIRECT),
}

/** Who places, answers and ends the call when there is one. */
enum class ControlChoice { MANUAL, AUTO }

/** 「電話のしかた」on the settings screen: what the audio and the control come to, in the user's words. */
enum class CallChoice(val label: String, val audio: AudioChoice, val control: ControlChoice) {
    PRACTICE("練習（電話はかけません）", AudioChoice.LOCAL, ControlChoice.MANUAL),
    CALL("電話をかける（相手が出たら AI が話し始めます）", AudioChoice.SHELL_DIRECT, ControlChoice.MANUAL),
    AUTO_CALL("全自動で電話をかける（時間を指定した電話に使います）", AudioChoice.SHELL_DIRECT, ControlChoice.AUTO),
}

/** What the user has to keep in mind with [Settings.keepMicOn]: under the switch and on the confirm screen. */
const val KEEP_MIC_ON_NOTE = "静かな部屋で使ってください。AI が話す間、あなたは話さないでください。電話の画面でも、端末のマイクを止めないでください（止めると AI の声が届きません）"

data class Settings(
    val hasApiKey: Boolean = false,
    /** The key of the thinking model, for the chat that fills in the request. */
    val hasClaudeKey: Boolean = false,
    val audio: AudioChoice = AudioChoice.LOCAL,
    val control: ControlChoice = ControlChoice.MANUAL,
    /** The practice: stop the microphone while the AI is speaking (when the AI hears itself). */
    val halfDuplex: Boolean = false,
    /** The practice: the loudspeaker (off: the earpiece or a headset). */
    val loudspeaker: Boolean = true,
    /** The speaking agent's voice (added to the setup by RotepLive; DEFAULT names none). */
    val voice: AgentVoice = AgentVoice.DEFAULT,
    /** Ask the speaking agent to talk slowly (a line in rotep's speaking rules). */
    val slowSpeech: Boolean = false,
    /**
     * In a real call: let the phone play the call while the AI talks, so the user hears the
     * shop and the AI. Off (the default): the phone keeps the call quiet while the AI has it; the
     * sound comes back when the AI hands the call over or the call ends.
     */
    val playCallAudio: Boolean = false,
    /** Put a booking the call made in the phone's Google calendar (ADR-039). Turning it on asks for the calendar permissions. */
    val addToCalendar: Boolean = false,
    /** Keep 「電話の記録」 of the calls placed from now on (ADR-045, ADR-050). On until the user turns it off. */
    val keepBookingHistory: Boolean = true,
    /**
     * In a call: leave the phone's own microphone on while the AI talks. For a phone where the AI's
     * voice does not reach the far end with it stopped (Telecom's mute can stop the injected voice too).
     */
    val keepMicOn: Boolean = false,
    /**
     * In a call, Claude decides what is said and Gemini Live only hears and speaks (ADR-053). Off: Gemini
     * alone, as before.
     */
    val dualBrain: Boolean = true,
    /** Say so when a shop an earlier call reached may be calling back (ADR-047). Turning it on asks the phone for the call-screening role. */
    val notifyCallback: Boolean = false,
) {
    val callChoice: CallChoice
        get() = when {
            audio.shell == null -> CallChoice.PRACTICE
            control == ControlChoice.AUTO -> CallChoice.AUTO_CALL
            else -> CallChoice.CALL
        }

    fun with(choice: CallChoice) = copy(audio = choice.audio, control = choice.control)

    /** [KEEP_MIC_ON_NOTE] when it applies: a practice has no call to keep the microphone on for. */
    val micNote: String? get() = KEEP_MIC_ON_NOTE.takeIf { keepMicOn && audio.shell != null }
}

/** The audio choice as saved, once read: see [readSavedAudio]. */
internal data class SavedAudio(val audio: AudioChoice, val keepMicOn: Boolean)

/**
 * The saved name of the audio choice. Earlier versions offered more: the ways taken out of the
 * settings read as the one left, and "direct with the microphone on" is now that one with the switch.
 */
internal fun readSavedAudio(name: String?, keepMicOn: Boolean): SavedAudio = when (name) {
    "SHELL_DIRECT_MIC_ON" -> SavedAudio(AudioChoice.SHELL_DIRECT, keepMicOn = true)
    "SHELL_REDIRECTION", "SHELL_SPEAKER" -> SavedAudio(AudioChoice.SHELL_DIRECT, keepMicOn)
    else -> SavedAudio(AudioChoice.entries.firstOrNull { it.name == name } ?: AudioChoice.LOCAL, keepMicOn)
}

/**
 * Settings in the app's private preferences. The Gemini API key is kept apart ([ApiKeyStore]):
 * encrypted with a key that stays in AndroidKeyStore, in a file under noBackupFilesDir
 * (app-private, never part of a cloud backup or a device-to-device transfer). It is never put in
 * the repository, the APK or a log (ADR-026).
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keys = ApiKeyStore(context.noBackupFilesDir, KeystoreCipher(), { RotepLog.i("settings", it) }, who = "Gemini の ")

    /** Apart from the Gemini key, with its own keystore entry: clearing one must not break the other. */
    private val claudeKeys = ApiKeyStore(
        context.noBackupFilesDir,
        KeystoreCipher("rotep_claude_api_key"),
        { RotepLog.i("settings", "claude: $it") },
        fileName = "claude_api_key.enc",
        plainFileName = "claude_api_key",
        who = "Claude の ",
    )

    fun load(): Settings {
        val saved = readSavedAudio(prefs.getString("audio", null), prefs.getBoolean("keepMicOn", false))
        return Settings(
            // From the files: this runs on the main thread, and the key store can be slow. The call reads the key when it starts
            hasApiKey = apiKeySaved,
            hasClaudeKey = claudeKeySaved,
            audio = saved.audio,
            control = enumValue(prefs.getString("control", null), ControlChoice.MANUAL),
            halfDuplex = prefs.getBoolean("halfDuplex", false),
            loudspeaker = prefs.getBoolean("loudspeaker", true),
            voice = enumValue(prefs.getString("voice", null), AgentVoice.DEFAULT),
            slowSpeech = prefs.getBoolean("slowSpeech", false),
            playCallAudio = prefs.getBoolean("playCallAudio", false),
            addToCalendar = prefs.getBoolean("addToCalendar", false),
            keepBookingHistory = prefs.getBoolean("keepBookingHistory", Settings().keepBookingHistory),
            keepMicOn = saved.keepMicOn,
            dualBrain = prefs.getBoolean("dualBrain", true),
            notifyCallback = prefs.getBoolean(NOTIFY_CALLBACK, false),
        )
    }

    fun save(s: Settings) {
        prefs.edit {
            putString("audio", s.audio.name)
            putString("control", s.control.name)
            putBoolean("halfDuplex", s.halfDuplex)
            putBoolean("loudspeaker", s.loudspeaker)
            putString("voice", s.voice.name)
            putBoolean("slowSpeech", s.slowSpeech)
            putBoolean("playCallAudio", s.playCallAudio)
            putBoolean("addToCalendar", s.addToCalendar)
            putBoolean("keepBookingHistory", s.keepBookingHistory)
            putBoolean("keepMicOn", s.keepMicOn)
            putBoolean("dualBrain", s.dualBrain)
            putBoolean(NOTIFY_CALLBACK, s.notifyCallback)
        }
    }

    /** Saved, whether or not it can be read right now. Decrypts nothing. */
    val apiKeySaved: Boolean get() = keys.exists

    val claudeKeySaved: Boolean get() = claudeKeys.exists

    /** The saved API key, and why it could not be read if so. */
    fun readApiKey(): ApiKeyStore.Read = keys.read()

    /** The API key, or null (none saved, or it could not be read: then see [keyProblem]). */
    fun apiKey(): String? = (keys.read() as? ApiKeyStore.Read.Key)?.value

    /** Why the saved API key cannot be used, in words that say what to do, or null. */
    val keyProblem: String? get() = keys.problem

    /** false when it could not be saved (the API key is then not kept anywhere). */
    fun saveApiKey(key: String): Boolean = keys.save(key)

    fun clearApiKey() = keys.clear()

    fun readClaudeKey(): ApiKeyStore.Read = claudeKeys.read()

    fun claudeKey(): String? = (claudeKeys.read() as? ApiKeyStore.Read.Key)?.value

    val claudeKeyProblem: String? get() = claudeKeys.problem

    fun saveClaudeKey(key: String): Boolean = claudeKeys.save(key)

    fun clearClaudeKey() = claudeKeys.clear()

    private inline fun <reified T : Enum<T>> enumValue(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    companion object {
        private const val PREFS = "settings"
        private const val NOTIFY_CALLBACK = "notifyCallback"

        /** The one setting an incoming call needs, read alone: a call must not wait for the keystore (the API keys). */
        fun notifyCallback(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(NOTIFY_CALLBACK, false)
    }
}
