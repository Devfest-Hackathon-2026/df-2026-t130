// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell;

/**
 * Messages from the helper to the app. Every call is one-way, so the helper never waits for the app
 * (a slow or frozen app must not stall the audio thread).
 */
oneway interface IHelperCallback {
    /**
     * Level of one 100 ms window of the downlink, in dBFS (-120 means digital silence).
     * totalFrames counts all samples received so far, so the screen can tell "silent" from "no data".
     */
    void onLevel(float peakDbfs, float rmsDbfs, long totalFrames) = 1;

    /** The meter is recording. The report holds source, rate, routed device and the silenced flag. */
    void onMeterStarted(String report) = 2;

    /** The meter ended (stop button, 60 s limit or a failure). The report is taken just before stopping. */
    void onMeterStopped(String report, String reason) = 3;

    /** The helper is about to end its process. Best effort: the app may already be gone. */
    void onHelperExiting(String reason) = 4;

    /**
     * The uplink test moved on. phase (see UplinkPhase): 1 waiting for the delayed start, 2 playing,
     * 3 ended (exactly once per test). log is the whole record of the test so far; it never holds
     * phone numbers or names.
     */
    void onUplinkTest(int phase, String log) = 5;

    /**
     * The audio bridge (1b) moved on. event: 1 started (detail may be empty), 2 stopped (detail:
     * the reason), 3 silenced (the system silenced the downlink recording; detail empty), 4 failed
     * (detail: the cause). 1 is sent at most once, near the start; exactly one of 2/3/4 is sent
     * once, at the end. detail never holds a phone number or a name.
     */
    void onAudioBridge(int event, String detail) = 6;
}
