// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

// What an uplink test is: the kinds, the bounds and the conditions. Plain Kotlin (no Android classes).

/** Phases of an uplink test, as the helper reports them (the int of IHelperCallback.onUplinkTest). */
object UplinkPhase {
    const val IDLE = 0

    /** Waiting for the delayed start. */
    const val WAITING = 1
    const val PLAYING = 2

    /** Over: played to the end, stopped, refused or failed. Sent exactly once per test. */
    const val ENDED = 3
}

/** How a test plays the marker tone. */
enum class UplinkMethod(val label: String) {
    /** No injection: a voice-communication track with no chosen output (the earpiece during a call). */
    DEFAULT_ROUTE("注入しない。出力先を指定しない（通話中は受話口）"),

    /** AudioTrack (USAGE_VOICE_COMMUNICATION) with the TYPE_TELEPHONY output as its device. Needs MODIFY_PHONE_STATE. */
    DIRECT("direct。AudioTrack の出力先を TYPE_TELEPHONY にする"),

    /** AudioManager.getCallUplinkInjectionAudioTrack, a system API. Needs CALL_AUDIO_INTERCEPTION. */
    REDIRECTION("redirection。AudioManager.getCallUplinkInjectionAudioTrack"),

    /** No injection: the phone's loudspeaker, picked up again by its own microphone (the loudspeaker mode). */
    SPEAKER("注入しない。出力先を端末のスピーカーにする"),
}

/** The five uplink tests. [number] is what the screen and the AIDL use. */
enum class UplinkTestKind(
    val number: Int,
    val method: UplinkMethod,
    /** What the procedure says about the phone app's mute button. */
    val micMuted: Boolean,
    /** Starts only while the audio mode is MODE_IN_CALL. */
    val needsCall: Boolean,
    /** Short name for the screen. */
    val title: String,
    /** What to do in the phone app before pressing start. */
    val step: String,
) {
    BASELINE(
        0, UplinkMethod.DEFAULT_ROUTE, micMuted = false, needsCall = false,
        "試験0 基準（注入しない）",
        "マイクはミュートしない。スピーカーにしない。受話口から鳴る（回り込みの大きさの基準）",
    ),
    DIRECT_MUTED(
        1, UplinkMethod.DIRECT, micMuted = true, needsCall = true,
        "試験1 direct・マイクをミュート",
        "電話の画面でマイクをミュートにしてから開始。合否を決める試験",
    ),
    DIRECT_UNMUTED(
        2, UplinkMethod.DIRECT, micMuted = false, needsCall = true,
        "試験2 direct・マイクはそのまま",
        "マイクのミュートを戻してから開始。試験0・1 と比べる",
    ),
    REDIRECTION_MUTED(
        3, UplinkMethod.REDIRECTION, micMuted = true, needsCall = true,
        "試験3 redirection・マイクをミュート",
        "マイクをミュートにしてから開始。2回まで。通話が切れたら、それ以上しない",
    ),
    SPEAKER(
        4, UplinkMethod.SPEAKER, micMuted = false, needsCall = false,
        "試験4 スピーカー（注入しない）",
        "通話をスピーカーにしてから開始。マイクはミュートしない",
    ),
    ;

    companion object {
        fun fromNumber(number: Int): UplinkTestKind? = entries.firstOrNull { it.number == number }
    }
}

/** One test as asked for, with every value inside its bounds (see [UplinkTestRules.request]). */
data class UplinkTestRequest(
    val kind: UplinkTestKind,
    val delaySeconds: Int,
    val playSeconds: Int,
    /** Peak level of the marker tone in dBFS. */
    val levelDbfs: Int,
)

/** Bounds and start conditions of the uplink tests. */
object UplinkTestRules {
    /** AudioManager.MODE_IN_CALL */
    const val MODE_IN_CALL = 2

    const val DEFAULT_DELAY_SECONDS = 0
    const val DEFAULT_PLAY_SECONDS = 20
    const val MAX_DELAY_SECONDS = 60
    const val MAX_PLAY_SECONDS = 30
    const val DEFAULT_LEVEL_DBFS = MarkerTone.DEFAULT_LEVEL_DBFS
    const val MIN_LEVEL_DBFS = -40

    /** Kept well below full scale: the tone must stay quiet. */
    const val MAX_LEVEL_DBFS = -6

    val DELAY_CHOICES: List<Int> = listOf(0, 5, 10, 30)
    val PLAY_CHOICES: List<Int> = listOf(10, 20, 30)
    val LEVEL_CHOICES: List<Int> = listOf(-24, -18, -12, -6)

    /** The request with every value inside its bounds, or null when [test] is not a test number. */
    fun request(test: Int, delaySeconds: Int, playSeconds: Int, levelDbfs: Int): UplinkTestRequest? {
        val kind = UplinkTestKind.fromNumber(test) ?: return null
        return UplinkTestRequest(
            kind = kind,
            delaySeconds = delaySeconds.coerceIn(0, MAX_DELAY_SECONDS),
            playSeconds = playSeconds.coerceIn(1, MAX_PLAY_SECONDS),
            levelDbfs = levelDbfs.coerceIn(MIN_LEVEL_DBFS, MAX_LEVEL_DBFS),
        )
    }

    /** Null when [kind] may start in [audioMode] (null: not known); otherwise why it may not. */
    fun refusal(kind: UplinkTestKind, audioMode: Int?): String? {
        if (!kind.needsCall || audioMode == MODE_IN_CALL) return null
        val now = audioMode?.let(EnvReport::audioModeName) ?: "分からない"
        return "通話中ではない（音声のモード: $now）。試験1〜3 は MODE_IN_CALL のときだけ始める"
    }
}

/** The system's state just before a test plays. Only system values: nothing from the call itself. */
data class UplinkConditions(
    val audioMode: Int?,
    val microphoneMuted: Boolean?,
    val communicationDeviceType: Int?,
    val telephonyOutput: Boolean?,
    /** "now / max" of STREAM_VOICE_CALL. */
    val voiceCallVolume: String?,
    /** Answer of isPstnCallAudioInterceptable(); asked for before test 3 only. */
    val pstnInterceptable: String?,
)

/** How the AudioTrack of a test was made. */
data class UplinkTrackFacts(
    val maker: String,
    val attributes: String,
    /** False when the system makes the track and takes no Context (test 3). */
    val attributionGiven: Boolean,
    val attributionPackage: String?,
    val attributionUid: Int?,
    /** The output asked for with setPreferredDevice, or null when none was asked for. */
    val preferredDeviceType: Int?,
    /** setPreferredDevice's answer (null: not asked, or not known). */
    val preferredDeviceAccepted: Boolean?,
    val sampleRate: Int?,
    val bufferFrames: Int?,
)

/** What the track said just before it was let go. */
data class UplinkEndFacts(val playbackHeadFrames: Long?, val underruns: Int?)
