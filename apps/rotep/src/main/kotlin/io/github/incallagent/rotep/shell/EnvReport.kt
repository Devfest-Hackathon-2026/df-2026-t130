// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

/** One permission check. [granted] is null when the check itself failed. */
data class PermissionCheck(val name: String, val granted: Boolean?)

/**
 * Everything the helper learned about itself and the audio system, as plain values.
 * The report is built from these values only: nothing from calls (numbers, names, Call or Uri text)
 * is ever collected, so the report cannot carry any.
 */
data class EnvSnapshot(
    val timeText: String,
    val helperPid: Int,
    val helperUid: Int,
    val manufacturer: String,
    val model: String,
    val release: String,
    val sdkInt: Int,
    val securityPatch: String,
    val buildDisplay: String,
    val permissions: List<PermissionCheck>,
    val audioMode: Int?,
    val microphoneMuted: Boolean?,
    val communicationDeviceType: Int?,
    val outputTypes: List<Int>?,
    val inputTypes: List<Int>?,
    val pstnInterceptable: String,
    val attributionPackage: String?,
    val attributionUid: Int?,
    val meterLine: String?,
)

/** What the helper knows about its downlink recording at one moment (start or stop). */
data class MeterFacts(
    val phase: String,
    val audioSource: Int?,
    val sampleRate: Int?,
    val triedRates: List<String>,
    val routedDeviceType: Int?,
    val configFound: Boolean?,
    val clientAudioSource: Int?,
    val silenced: Boolean?,
    val configDeviceType: Int?,
    val attributionPackage: String?,
    val attributionUid: Int?,
)

/** Builds the texts that the diagnostics screen shows and copies. Pure Kotlin. */
object EnvReport {
    /** AudioDeviceInfo.TYPE_TELEPHONY: the output that carries the call to the far end. */
    const val TYPE_TELEPHONY = 18

    /** MediaRecorder.AudioSource.VOICE_DOWNLINK. For the tests. */
    const val SOURCE_VOICE_DOWNLINK = 3

    /** Put in front of every line that means "this is wrong". The screen looks for it. */
    const val NG_MARK = "【NG】"

    /** Put in front of the line that says the recording is silenced by the system. */
    const val WARN_MARK = "【注意】"

    val PERMISSION_NAMES = listOf(
        "CAPTURE_AUDIO_OUTPUT",
        "MODIFY_PHONE_STATE",
        "CALL_AUDIO_INTERCEPTION",
        "CAPTURE_VOICE_COMMUNICATION_OUTPUT",
        "MODIFY_AUDIO_ROUTING",
        "RECORD_AUDIO",
    )

    fun format(s: EnvSnapshot): String = buildString {
        appendLine("【手伝い役の環境の報告】${s.timeText}")
        appendLine("手伝い役: pid=${s.helperPid} uid=${s.helperUid}${if (s.helperUid == ShellIdentity.SHELL_UID) " (shell)" else ""}")
        appendLine("端末: ${s.manufacturer} ${s.model} / Android ${s.release} (API ${s.sdkInt}) / パッチ ${s.securityPatch}")
        appendLine("ビルド: ${s.buildDisplay}")
        appendLine("権限（手伝い役）:")
        for (p in s.permissions) appendLine("  ${p.name} = ${grantedText(p.granted)}")
        appendLine("音声のモード: ${s.audioMode?.let(::audioModeName) ?: UNKNOWN_TEXT}")
        appendLine("マイクのミュート: ${yesNo(s.microphoneMuted)}")
        appendLine("通話の出力先（communicationDevice）: ${s.communicationDeviceType?.let(::deviceTypeName) ?: "なし"}")
        appendLine("出力の装置: ${typesText(s.outputTypes)}")
        appendLine("入力の装置: ${typesText(s.inputTypes)}")
        appendLine("TYPE_TELEPHONY($TYPE_TELEPHONY) の出力: ${telephonyOutputText(s.outputTypes)}")
        // Free text (answers and exception messages) goes through scrub(), so a phone number can
        // never end up in a report. The device and build lines are system constants and stay as they are.
        appendLine("isPstnCallAudioInterceptable: ${ReportText.scrub(s.pstnInterceptable)}")
        appendLine(attributionLine(s.attributionPackage, s.attributionUid))
        s.meterLine?.let { appendLine(ReportText.scrub(it)) }
    }.trimEnd()

    /** The meter's start or stop report. */
    fun formatMeter(f: MeterFacts): String = buildString {
        appendLine("受話の録音（${f.phase}）:")
        appendLine("  録音の元 getAudioSource() = ${f.audioSource?.let(::audioSourceName) ?: UNKNOWN_TEXT}")
        appendLine("  レート getSampleRate() = ${f.sampleRate?.let { "$it Hz" } ?: UNKNOWN_TEXT}")
        if (f.triedRates.isNotEmpty()) {
            appendLine("  試したレート: ${f.triedRates.joinToString(", ") { ReportText.scrub(it) }}")
        }
        appendLine("  出力先 getRoutedDevice() = ${f.routedDeviceType?.let(::deviceTypeName) ?: "なし"}")
        when (f.configFound) {
            null -> appendLine("  システムの録音の一覧: 取得できなかった")
            false -> appendLine("  システムの録音の一覧: 自分の録音が見つからない")
            true -> {
                appendLine("  システムの録音の一覧: 自分の録音あり（元 ${f.clientAudioSource?.let(::audioSourceName) ?: UNKNOWN_TEXT}、装置 ${f.configDeviceType?.let(::deviceTypeName) ?: "なし"}）")
                val silenced = f.silenced
                appendLine(
                    when (silenced) {
                        true -> "  isClientSilenced() = true  $WARN_MARK システムが録音を黙らせている（音は来ない）"
                        false -> "  isClientSilenced() = false"
                        null -> "  isClientSilenced() = $UNKNOWN_TEXT"
                    },
                )
            }
        }
        appendLine("  ${attributionLine(f.attributionPackage, f.attributionUid)}")
    }.trimEnd()

    /** The line about the name given to AudioRecord. It carries [NG_MARK] unless the name is the shell's. */
    fun attributionLine(packageName: String?, uid: Int?): String {
        val shown = "package=${packageName ?: "なし"} uid=${uid ?: "なし"}"
        return if (ShellIdentity.isShell(packageName, uid)) {
            "録音の名義: $shown → 正しい（shell）"
        } else {
            "録音の名義: $shown → $NG_MARK shell（${ShellIdentity.SHELL_PACKAGE}、uid ${ShellIdentity.SHELL_UID}）になっていない。" +
                "受話は断られるか、無音になる"
        }
    }

    fun hasAttributionProblem(text: String): Boolean = text.contains(NG_MARK)

    fun hasSilencedWarning(text: String): Boolean = text.contains(WARN_MARK)

    fun audioModeName(mode: Int): String {
        val name = when (mode) {
            -2 -> "MODE_INVALID"
            -1 -> "MODE_CURRENT"
            0 -> "MODE_NORMAL"
            1 -> "MODE_RINGTONE"
            2 -> "MODE_IN_CALL"
            3 -> "MODE_IN_COMMUNICATION"
            4 -> "MODE_CALL_SCREENING"
            5 -> "MODE_CALL_REDIRECT"
            6 -> "MODE_COMMUNICATION_REDIRECT"
            7 -> "MODE_ASSISTANT_CONVERSATION"
            else -> "MODE_?"
        }
        return "$name ($mode)"
    }

    fun deviceTypeName(type: Int): String {
        val name = when (type) {
            0 -> "UNKNOWN"
            1 -> "BUILTIN_EARPIECE"
            2 -> "BUILTIN_SPEAKER"
            3 -> "WIRED_HEADSET"
            4 -> "WIRED_HEADPHONES"
            5 -> "LINE_ANALOG"
            6 -> "LINE_DIGITAL"
            7 -> "BLUETOOTH_SCO"
            8 -> "BLUETOOTH_A2DP"
            9 -> "HDMI"
            10 -> "HDMI_ARC"
            11 -> "USB_DEVICE"
            12 -> "USB_ACCESSORY"
            13 -> "DOCK"
            14 -> "FM"
            15 -> "BUILTIN_MIC"
            16 -> "FM_TUNER"
            17 -> "TV_TUNER"
            18 -> "TELEPHONY"
            19 -> "AUX_LINE"
            20 -> "IP"
            21 -> "BUS"
            22 -> "USB_HEADSET"
            23 -> "HEARING_AID"
            24 -> "BUILTIN_SPEAKER_SAFE"
            25 -> "REMOTE_SUBMIX"
            26 -> "BLE_HEADSET"
            27 -> "BLE_SPEAKER"
            29 -> "HDMI_EARC"
            30 -> "BLE_BROADCAST"
            31 -> "DOCK_ANALOG"
            32 -> "MULTICHANNEL_GROUP"
            33 -> "BLE_HEARING_AID"
            34 -> "BLE_CENTRAL"
            35 -> "BLE_CENTRAL_BROADCAST"
            else -> "TYPE_$type"
        }
        return "$name($type)"
    }

    fun audioSourceName(source: Int): String {
        val name = when (source) {
            0 -> "DEFAULT"
            1 -> "MIC"
            2 -> "VOICE_UPLINK"
            3 -> "VOICE_DOWNLINK"
            4 -> "VOICE_CALL"
            5 -> "CAMCORDER"
            6 -> "VOICE_RECOGNITION"
            7 -> "VOICE_COMMUNICATION"
            8 -> "REMOTE_SUBMIX"
            9 -> "UNPROCESSED"
            10 -> "VOICE_PERFORMANCE"
            else -> "SOURCE_$source"
        }
        return "$name($source)"
    }

    /** Distinct device types, sorted, with names. */
    fun typesText(types: List<Int>?): String = when {
        types == null -> "取得できなかった"
        types.isEmpty() -> "なし"
        else -> types.distinct().sorted().joinToString(", ") { deviceTypeName(it) }
    }

    fun telephonyOutputText(outputTypes: List<Int>?): String = when {
        outputTypes == null -> UNKNOWN_TEXT
        TYPE_TELEPHONY in outputTypes -> "あり"
        else -> "なし"
    }

    private fun grantedText(granted: Boolean?): String = when (granted) {
        true -> "あり"
        false -> "なし"
        null -> "確認できなかった"
    }

    private fun yesNo(value: Boolean?): String = when (value) {
        true -> "はい"
        false -> "いいえ"
        null -> "取得できなかった"
    }

    private const val UNKNOWN_TEXT = "不明"
}

/** Keeps free text (exception messages) short and free of phone-number-like digit runs. */
object ReportText {
    // Nine or more characters that start and end with a digit and hold only digits and the usual
    // separators in between (an opening "(" or "+" in front is taken too): phone numbers such as
    // 090-1234-5678, (03) 1234-5678 or +81 90 1234 5678.
    private val PHONE_LIKE = Regex("""[(+]?\d[\d\-\s().]{7,}\d""")

    const val NUMBER_MASK = "<number>"

    fun scrub(text: String, maxLength: Int = 160): String {
        val masked = PHONE_LIKE.replace(text.replace('\n', ' ').replace('\r', ' '), NUMBER_MASK)
        return if (masked.length <= maxLength) masked else masked.take(maxLength) + "…"
    }

    /** "SecurityException: message" with the message scrubbed. Never prints a stack trace. */
    fun exceptionLine(t: Throwable): String {
        val name = t.javaClass.simpleName.ifEmpty { "Throwable" }
        val message = t.message?.takeIf { it.isNotBlank() } ?: return name
        return "$name: ${scrub(message)}"
    }
}
