// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

/** The lines of a test's record. Free texts (exception messages) always go through [ReportText.scrub]. */
object UplinkReport {
    const val TITLE = "【送話の試験】"

    /** Put in front of what does not match the procedure or the request. */
    const val CHECK_MARK = "【要確認】"

    private const val UNKNOWN = "不明"

    fun header(r: UplinkTestRequest, timeText: String): List<String> {
        val playMs = r.playSeconds * 1000L
        val markers = MarkerTone.markersIn(playMs)
        val markerText = if (markers > 0) {
            "この試験で $markers 回（始めてから ${MarkerTone.markerStartSeconds(playMs).joinToString("・")} 秒）"
        } else {
            "この長さでは、3つの音が最後まで鳴らない"
        }
        return listOf(
            "$TITLE 試験${r.kind.number}: ${r.kind.method.label}",
            "手順: ${r.kind.step}",
            "設定: 遅らせる ${r.delaySeconds} 秒 / 鳴らす ${r.playSeconds} 秒 / 音量 ${r.levelDbfs} dBFS（ピーク）",
            "目印の音: ${MarkerTone.FREQUENCIES_HZ.joinToString("・")} Hz を1秒ずつ、${MarkerTone.CYCLE_MS / 1000} 秒ごと。$markerText",
            "頼んだ時刻: $timeText",
        )
    }

    fun conditionLines(kind: UplinkTestKind, c: UplinkConditions, redirectionRun: Int?): List<String> = buildList {
        add("始める前の状態:")
        add("  音声のモード: ${c.audioMode?.let(EnvReport::audioModeName) ?: UNKNOWN}")
        add("  マイクのミュート: ${muteText(c.microphoneMuted, kind.micMuted)}")
        add("  通話の出力先（communicationDevice）: ${c.communicationDeviceType?.let(EnvReport::deviceTypeName) ?: "なし"}")
        add("  TYPE_TELEPHONY(${EnvReport.TYPE_TELEPHONY}) の出力: ${presentText(c.telephonyOutput)}")
        add("  通話の音量（STREAM_VOICE_CALL）: ${c.voiceCallVolume?.let { ReportText.scrub(it) } ?: UNKNOWN}")
        if (kind.method == UplinkMethod.REDIRECTION) {
            add("  isPstnCallAudioInterceptable: ${c.pstnInterceptable?.let { ReportText.scrub(it) } ?: UNKNOWN}")
            if (redirectionRun != null) add("  試験3 の回数（この手伝い役で）: $redirectionRun 回目（手順では2回まで）")
        }
    }

    fun trackLines(f: UplinkTrackFacts): List<String> = listOf(
        "AudioTrack:",
        "  作り方: ${f.maker}",
        "  属性: ${f.attributes}",
        "  形式: ${f.sampleRate?.let { "$it Hz" } ?: UNKNOWN}・モノラル・16bit / バッファ ${f.bufferFrames?.let { "$it フレーム" } ?: UNKNOWN}",
        "  ${attributionLine(f)}",
        "  出力先の指定 setPreferredDevice: " + when (val type = f.preferredDeviceType) {
            null -> "しない"
            else -> "${EnvReport.deviceTypeName(type)} → ${f.preferredDeviceAccepted ?: UNKNOWN}"
        },
    )

    fun attributionLine(f: UplinkTrackFacts): String = when {
        !f.attributionGiven -> "名義: 渡せない（AudioManager の中で作られる。システムの既定の名義）"
        ShellIdentity.isShell(f.attributionPackage, f.attributionUid) ->
            "名義: package=${f.attributionPackage} uid=${f.attributionUid} → shell"
        else -> "名義: package=${f.attributionPackage ?: "なし"} uid=${f.attributionUid ?: "なし"} → $CHECK_MARK shell ではない"
    }

    /** The routed device; marked when an output was asked for and the track went elsewhere. */
    fun routeLine(type: Int?, preferred: Int?): String {
        val shown = type?.let(EnvReport::deviceTypeName) ?: "なし（決まっていない）"
        val differs = preferred != null && type != preferred
        return "出力先 getRoutedDevice() = $shown${if (differs) "  $CHECK_MARK 指定と違う" else ""}"
    }

    fun routeChangeLine(timeText: String, from: Int?, to: Int?): String =
        "$timeText 出力先が変わった: ${from?.let(EnvReport::deviceTypeName) ?: "なし"} → ${to?.let(EnvReport::deviceTypeName) ?: "なし"}"

    fun endLine(framesWritten: Long, end: UplinkEndFacts?): String =
        "書いたフレーム: $framesWritten / 再生した位置: ${end?.playbackHeadFrames ?: UNKNOWN} / アンダーラン: ${end?.underruns ?: UNKNOWN}"

    fun reasonLine(reason: String): String = "止まった理由: ${ReportText.scrub(reason, 240)}"

    /** The whole record of a test that was refused before it could start. */
    fun refusedText(request: UplinkTestRequest?, timeText: String, reason: String): String {
        val lines = if (request != null) header(request, timeText) else listOf("$TITLE 試験の番号が違う", "頼んだ時刻: $timeText")
        return (lines + reasonLine(reason)).joinToString("\n")
    }

    /** Names of the error codes of AudioTrack.write. */
    fun writeErrorName(code: Int): String = when (code) {
        -1 -> "ERROR"
        -2 -> "ERROR_BAD_VALUE"
        -3 -> "ERROR_INVALID_OPERATION"
        -6 -> "ERROR_DEAD_OBJECT"
        else -> "code $code"
    }

    private fun muteText(muted: Boolean?, expected: Boolean): String {
        val step = if (expected) "手順: ミュートする" else "手順: ミュートしない"
        if (muted == null) return "$UNKNOWN（$step）"
        val shown = "${yesNo(muted)}（$step）"
        return if (muted == expected) shown else "$shown $CHECK_MARK 手順と違う"
    }

    private fun yesNo(value: Boolean): String = if (value) "はい" else "いいえ"

    private fun presentText(value: Boolean?): String = when (value) {
        true -> "あり"
        false -> "なし"
        null -> UNKNOWN
    }
}
