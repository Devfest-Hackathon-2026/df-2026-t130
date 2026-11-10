// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvReportTest {
    private val sample = EnvSnapshot(
        timeText = "2026-11-07 21:03:12 UTC+09:00",
        helperPid = 4321,
        helperUid = 2000,
        manufacturer = "Nothing",
        model = "A142",
        release = "15",
        sdkInt = 35,
        securityPatch = "2026-10-05",
        buildDisplay = "Example_V1.0-000000-0000",
        permissions = listOf(
            PermissionCheck("CAPTURE_AUDIO_OUTPUT", true),
            PermissionCheck("MODIFY_PHONE_STATE", true),
            PermissionCheck("CALL_AUDIO_INTERCEPTION", false),
            PermissionCheck("CAPTURE_VOICE_COMMUNICATION_OUTPUT", null),
        ),
        audioMode = 2,
        microphoneMuted = true,
        communicationDeviceType = 1,
        outputTypes = listOf(2, 1, 18, 2),
        inputTypes = listOf(15),
        pstnInterceptable = "true",
        attributionPackage = "com.android.shell",
        attributionUid = 2000,
        meterLine = "受話の音量の計測: 止まっている",
    )

    private val facts = MeterFacts(
        phase = "開始",
        audioSource = 3,
        sampleRate = 48000,
        triedRates = listOf("48000: 使えた"),
        routedDeviceType = 15,
        configFound = true,
        clientAudioSource = 3,
        silenced = false,
        configDeviceType = 15,
        attributionPackage = "com.android.shell",
        attributionUid = 2000,
    )

    @Test
    fun theReportShowsTheHelperTheDeviceAndEveryPermission() {
        val text = EnvReport.format(sample)
        assertTrue(text, text.contains("手伝い役: pid=4321 uid=2000 (shell)"))
        assertTrue(text, text.contains("Android 15 (API 35)"))
        assertTrue(text, text.contains("ビルド: Example_V1.0-000000-0000"))
        assertTrue(text, text.contains("CAPTURE_AUDIO_OUTPUT = あり"))
        assertTrue(text, text.contains("CALL_AUDIO_INTERCEPTION = なし"))
        assertTrue(text, text.contains("CAPTURE_VOICE_COMMUNICATION_OUTPUT = 確認できなかった"))
        assertTrue(text, text.contains("音声のモード: MODE_IN_CALL (2)"))
        assertTrue(text, text.contains("マイクのミュート: はい"))
        assertTrue(text, text.contains("受話の音量の計測: 止まっている"))
    }

    @Test
    fun deviceTypesAreSortedWithoutDuplicates() {
        val text = EnvReport.format(sample)
        assertTrue(text, text.contains("出力の装置: BUILTIN_EARPIECE(1), BUILTIN_SPEAKER(2), TELEPHONY(18)"))
        assertTrue(text, text.contains("入力の装置: BUILTIN_MIC(15)"))
    }

    @Test
    fun theTelephonyOutputIsReportedEitherWay() {
        assertTrue(EnvReport.format(sample).contains("TYPE_TELEPHONY(18) の出力: あり"))
        val without = EnvReport.format(sample.copy(outputTypes = listOf(1, 2)))
        assertTrue(without, without.contains("TYPE_TELEPHONY(18) の出力: なし"))
        val unknown = EnvReport.format(sample.copy(outputTypes = null))
        assertTrue(unknown, unknown.contains("TYPE_TELEPHONY(18) の出力: 不明"))
        assertTrue(unknown, unknown.contains("出力の装置: 取得できなかった"))
    }

    @Test
    fun aMissingAnswerIsShownAsUnknownNotInvented() {
        val text = EnvReport.format(sample.copy(audioMode = null, microphoneMuted = null, communicationDeviceType = null))
        assertTrue(text, text.contains("音声のモード: 不明"))
        assertTrue(text, text.contains("マイクのミュート: 取得できなかった"))
        assertTrue(text, text.contains("通話の出力先（communicationDevice）: なし"))
    }

    @Test
    fun theShellNameIsReportedAsCorrect() {
        val text = EnvReport.format(sample)
        assertTrue(text, text.contains("録音の名義: package=com.android.shell uid=2000 → 正しい（shell）"))
        assertFalse(EnvReport.hasAttributionProblem(text))
    }

    @Test
    fun theAppsNameWithTheShellUidIsMarkedNg() {
        val wrong = sample.copy(attributionPackage = "io.github.incallagent.rotep")
        val text = EnvReport.format(wrong)
        assertTrue(text, text.contains("package=io.github.incallagent.rotep uid=2000"))
        assertTrue(text, text.contains(EnvReport.NG_MARK))
        assertTrue(EnvReport.hasAttributionProblem(text))
    }

    @Test
    fun noNameAtAllIsMarkedNgToo() {
        val text = EnvReport.format(sample.copy(attributionPackage = null, attributionUid = null))
        assertTrue(text, text.contains("package=なし uid=なし"))
        assertTrue(EnvReport.hasAttributionProblem(text))
    }

    @Test
    fun aHelperThatIsNotTheShellUserIsNotCalledShell() {
        val text = EnvReport.format(sample.copy(helperUid = 0))
        assertTrue(text, text.contains("手伝い役: pid=4321 uid=0"))
        assertFalse(text, text.contains("uid=0 (shell)"))
    }

    @Test
    fun audioModeNamesCoverTheKnownModes() {
        assertEquals("MODE_NORMAL (0)", EnvReport.audioModeName(0))
        assertEquals("MODE_RINGTONE (1)", EnvReport.audioModeName(1))
        assertEquals("MODE_IN_CALL (2)", EnvReport.audioModeName(2))
        assertEquals("MODE_IN_COMMUNICATION (3)", EnvReport.audioModeName(3))
        assertEquals("MODE_CALL_SCREENING (4)", EnvReport.audioModeName(4))
        assertEquals("MODE_CALL_REDIRECT (5)", EnvReport.audioModeName(5))
        assertEquals("MODE_COMMUNICATION_REDIRECT (6)", EnvReport.audioModeName(6))
        assertEquals("MODE_? (99)", EnvReport.audioModeName(99))
    }

    @Test
    fun deviceTypeNamesIncludeTelephonyAndFallBackForUnknownTypes() {
        assertEquals("TELEPHONY(18)", EnvReport.deviceTypeName(18))
        assertEquals("BUILTIN_EARPIECE(1)", EnvReport.deviceTypeName(1))
        assertEquals("BUILTIN_MIC(15)", EnvReport.deviceTypeName(15))
        assertEquals("TYPE_99(99)", EnvReport.deviceTypeName(99))
        assertEquals(EnvReport.TYPE_TELEPHONY, 18)
    }

    @Test
    fun audioSourceNamesIncludeTheDownlink() {
        assertEquals("VOICE_DOWNLINK(3)", EnvReport.audioSourceName(EnvReport.SOURCE_VOICE_DOWNLINK))
        assertEquals("VOICE_UPLINK(2)", EnvReport.audioSourceName(2))
        assertEquals("SOURCE_77(77)", EnvReport.audioSourceName(77))
    }

    @Test
    fun theMeterReportShowsSourceRateDeviceAndSilencedFlag() {
        val text = EnvReport.formatMeter(facts)
        assertTrue(text, text.contains("受話の録音（開始）"))
        assertTrue(text, text.contains("getAudioSource() = VOICE_DOWNLINK(3)"))
        assertTrue(text, text.contains("getSampleRate() = 48000 Hz"))
        assertTrue(text, text.contains("getRoutedDevice() = BUILTIN_MIC(15)"))
        assertTrue(text, text.contains("isClientSilenced() = false"))
        assertTrue(text, text.contains("録音の名義: package=com.android.shell uid=2000 → 正しい（shell）"))
        assertFalse(EnvReport.hasSilencedWarning(text))
        assertFalse(EnvReport.hasAttributionProblem(text))
    }

    @Test
    fun aSilencedRecordingIsWarnedAbout() {
        val text = EnvReport.formatMeter(facts.copy(silenced = true, phase = "停止"))
        assertTrue(text, text.contains("isClientSilenced() = true"))
        assertTrue(EnvReport.hasSilencedWarning(text))
        assertTrue(text, text.contains("受話の録音（停止）"))
    }

    @Test
    fun aRecordingTheSystemDoesNotListIsSaidSo() {
        val text = EnvReport.formatMeter(facts.copy(configFound = false))
        assertTrue(text, text.contains("自分の録音が見つからない"))
        assertFalse(text, text.contains("isClientSilenced"))
        val unknown = EnvReport.formatMeter(facts.copy(configFound = null))
        assertTrue(unknown, unknown.contains("取得できなかった"))
    }

    @Test
    fun aFailedMeterShowsWhatWasTriedAndNoRate() {
        val text = EnvReport.formatMeter(
            facts.copy(
                phase = "失敗",
                audioSource = null,
                sampleRate = null,
                triedRates = listOf("48000: SecurityException", "16000: UnsupportedOperationException"),
                routedDeviceType = null,
                configFound = null,
            ),
        )
        assertTrue(text, text.contains("試したレート: 48000: SecurityException, 16000: UnsupportedOperationException"))
        assertTrue(text, text.contains("getSampleRate() = 不明"))
        assertTrue(text, text.contains("getRoutedDevice() = なし"))
    }

    // --- Phone numbers must never get into a report -------------------------------------------

    @Test
    fun aPhoneNumberInFreeTextIsMaskedInTheEnvironmentReport() {
        val text = EnvReport.format(
            sample.copy(
                pstnInterceptable = "取得できなかった（SecurityException: call 090-1234-5678）",
                meterLine = "メモ 03 1234 5678",
            ),
        )
        assertFalse(text, text.contains("090-1234-5678"))
        assertFalse(text, text.contains("1234 5678"))
        assertTrue(text, text.contains(ReportText.NUMBER_MASK))
    }

    @Test
    fun aPhoneNumberInATriedRateIsMaskedInTheMeterReport() {
        val text = EnvReport.formatMeter(facts.copy(triedRates = listOf("48000: failed for +81 90 1234 5678")))
        assertFalse(text, text.contains("1234 5678"))
        assertTrue(text, text.contains(ReportText.NUMBER_MASK))
    }

    @Test
    fun theReportHoldsNoPhoneLikeDigitRunsOutsideTheFixedSystemLines() {
        val phoneLike = Regex("""\+?\d[\d\-\s().]{7,}\d""")
        // The time, device and build lines carry dates and build ids by design; everything else must be clean.
        val skipped = listOf("【手伝い役の環境の報告】", "端末:", "ビルド:")
        val body = EnvReport.format(sample).lines().filter { line -> skipped.none { line.startsWith(it) } }
        for (line in body) assertFalse(line, phoneLike.containsMatchIn(line))
        for (line in EnvReport.formatMeter(facts).lines()) assertFalse(line, phoneLike.containsMatchIn(line))
    }

    @Test
    fun scrubMasksPhoneNumbersInTheCommonWrittenForms() {
        assertEquals("<number>", ReportText.scrub("090-1234-5678"))
        assertEquals("<number>", ReportText.scrub("+81 90 1234 5678"))
        assertEquals("tel:<number>", ReportText.scrub("tel:0312345678"))
        assertEquals("<number>", ReportText.scrub("(03) 1234-5678"))
        assertEquals("a <number> b", ReportText.scrub("a 09012345678 b"))
    }

    @Test
    fun scrubLeavesShortNumbersAlone() {
        assertEquals("uid=2000 pid=12345", ReportText.scrub("uid=2000 pid=12345"))
        assertEquals("code 110 and 117", ReportText.scrub("code 110 and 117"))
        assertEquals("rate 48000", ReportText.scrub("rate 48000"))
    }

    @Test
    fun scrubKeepsMessagesShortAndOnOneLine() {
        val long = "x".repeat(500)
        val out = ReportText.scrub(long)
        assertEquals(161, out.length)
        assertTrue(out.endsWith("…"))
        assertEquals("a b c", ReportText.scrub("a\nb\r\nc").replace("  ", " "))
    }

    @Test
    fun exceptionLineNamesTheClassAndScrubsTheMessage() {
        assertEquals(
            "SecurityException: denied for <number>",
            ReportText.exceptionLine(SecurityException("denied for 090-1234-5678")),
        )
        assertEquals("IllegalStateException", ReportText.exceptionLine(IllegalStateException()))
        assertEquals("IllegalStateException", ReportText.exceptionLine(IllegalStateException("  ")))
    }
}
