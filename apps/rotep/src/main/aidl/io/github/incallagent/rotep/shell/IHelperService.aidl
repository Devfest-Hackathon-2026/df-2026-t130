// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell;

import android.os.ParcelFileDescriptor;
import io.github.incallagent.rotep.shell.IHelperCallback;

/**
 * The helper: a process started from a PC with adb that runs as the shell user (uid 2000).
 *
 * Every method checks who is calling (see HelperService). All methods have a fixed number, because
 * AIDL needs either all or none of them numbered. The number of destroy() is kept from the earlier
 * design; nothing depends on its value now.
 */
interface IHelperService {
    /**
     * Ends the helper: it cleans up and ends its process. One-way, so the caller never waits for it.
     * The app calls this for "終了".
     */
    oneway void destroy() = 16777114;

    /**
     * Hands the app-side callback to the helper. The helper links to the death of the callback and
     * ends itself when the app process dies.
     */
    void attach(IHelperCallback callback) = 1;

    /** One line with the helper's pid and uid, for the screen. */
    String helperInfo() = 2;

    /** Environment report as plain text. It never contains phone numbers or names. */
    String environmentReport() = 3;

    /**
     * Starts the downlink level meter (the far end's voice, measured and thrown away).
     * Returns false if it is already running. Results arrive through the callback.
     */
    boolean startDownlinkMeter() = 4;

    /** Stops the meter. The stop report arrives through the callback. */
    void stopDownlinkMeter() = 5;

    /**
     * Starts one uplink test of A3 (test 0 to 4, see UplinkTestKind): the marker tone, after
     * delaySeconds, for playSeconds, with its peak at levelDbfs. The helper keeps the values inside
     * their bounds. Tests 1 to 3 start only during a call (MODE_IN_CALL). Returns false if a test is
     * already running, the call audio runs (startAudioBridge), or it cannot start. Starting the call
     * audio ends a test that still waits or plays. The record arrives through the callback
     * (onUplinkTest).
     */
    boolean startUplinkTest(int test, int delaySeconds, int playSeconds, int levelDbfs) = 6;

    /** Stops the uplink test at once, also while it waits for its delayed start. */
    void stopUplinkTest() = 7;

    /**
     * Starts the call audio bridge (1b): captures the downlink, resamples it to 16 kHz and writes
     * 20 ms chunks (see AudioPipe) to downlinkWriteEnd; reads the agent's voice (24 kHz PCM
     * messages, see AudioPipe) from uplinkReadEnd and plays it by uplinkMode (0 direct,
     * 1 redirection, 2 speaker; the same order as the app's ShellMode). The helper closes both ends
     * when the bridge stops, however it stops. Starts only during a call (MODE_IN_CALL). Returns
     * false only when a bridge is already running or has not yet let go of its audio (or the
     * helper has no callback to report to, or cannot reach the audio service);
     * any other refusal (not in a call, no output for the mode, too old an Android, ...) comes as
     * FAILED with the reason through the callback (onAudioBridge) and this returns true. Progress
     * arrives the same way.
     *
     * keepCallQuiet: while the bridge runs, the phone does not play the call (the user does not
     * hear the shop or the AI). The helper restores the call's sound however the bridge ends, and
     * when it ends itself (see CallQuiet). Ignored for the speaker mode (2), whose voice must be
     * heard.
     */
    boolean startAudioBridge(int uplinkMode, boolean keepCallQuiet, in ParcelFileDescriptor downlinkWriteEnd, in ParcelFileDescriptor uplinkReadEnd) = 8;

    /** Stops the audio bridge, if one is running, and closes both pipe ends. */
    void stopAudioBridge() = 9;

    /**
     * Gives the call its sound back now, if the bridge was keeping it quiet. The app calls this
     * before it alerts the user at a hand-over: the alert plays on the same stream. Not one-way, so
     * the sound is back when this returns.
     */
    void restoreCallSound() = 10;

    /**
     * One look at the calls on the phone (stage L2), as one of the numbers of CallPicture. Reads
     * the telephony registry, so it takes a moment: never call it on the app's main thread. Never
     * holds a number or a name.
     */
    int callPicture() = 11;

    /**
     * Ends the phone's one ongoing call (stage L2), but only when it is the only call: no call on
     * hold, none ringing, and none on another SIM. The helper checks this itself right before ending
     * it. Returns false when it did not end anything.
     */
    boolean endOngoingCall() = 12;

    /** Brings the app's own main screen to the front. Returns false when that could not be asked for. */
    boolean bringAppToFront() = 13;

    /** Shows the phone app's call screen. Returns false when that could not be asked for. */
    boolean showPhoneCallScreen() = 14;
}
