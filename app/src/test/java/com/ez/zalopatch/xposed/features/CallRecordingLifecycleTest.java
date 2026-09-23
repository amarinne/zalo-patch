package com.ez.zalopatch.xposed.features;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CallRecordingLifecycleTest {
    @Test
    public void confirmationArmsButAudioConnectionStartsCapture() {
        assertTrue(CallRecordingLifecycle.confirmsCall("onCallConfirmed", 0));
        assertTrue(CallRecordingLifecycle.confirmsCall("onPreConnectSuccessful", 0));
        assertTrue(CallRecordingLifecycle.confirmsCall("onCallState",
                CallRecordingLifecycle.CONNECTED_CALL_STATE));
        assertFalse(CallRecordingLifecycle.confirmsCall("onCallState", 3));
        assertFalse(CallRecordingLifecycle.confirmsCall("onCallAudioState",
                CallRecordingLifecycle.CONNECTED_AUDIO_STATE));
        assertTrue(CallRecordingLifecycle.connectsAudio("onCallAudioState", 32));
        assertFalse(CallRecordingLifecycle.shouldStartAudio(true, false));
        assertFalse(CallRecordingLifecycle.shouldStartAudio(false, true));
        assertTrue(CallRecordingLifecycle.shouldStartAudio(true, true));
    }

    @Test
    public void videoStateNeverStartsOrStopsAudioRecording() {
        for (int state = 0; state <= 20; state++) {
            assertFalse(CallRecordingLifecycle.connectsAudio("onCallVideoState", state));
            assertFalse(CallRecordingLifecycle.shouldStopAudio("onCallVideoState", state));
        }
    }

    @Test
    public void holdAndUnholdKeepOneAudioRecordingSession() {
        assertFalse(CallRecordingLifecycle.connectsAudio("onCallAudioState", 0));
        assertFalse(CallRecordingLifecycle.shouldStopAudio("onCallAudioState", 0));
        assertFalse(CallRecordingLifecycle.shouldStopAudio("onCallAudioState", 1));
        assertFalse(CallRecordingLifecycle.shouldStopAudio("onCallAudioState", 2));
    }

    @Test
    public void terminalCallbacksStopAudioRecording() {
        assertTrue(CallRecordingLifecycle.shouldStopAudio("onCallEnd",
                CallRecordingLifecycle.UNKNOWN_STATE));
        assertTrue(CallRecordingLifecycle.shouldStopAudio("onCallErr", -1));
        assertTrue(CallRecordingLifecycle.shouldStopAudio("onCallAutoHangup",
                CallRecordingLifecycle.UNKNOWN_STATE));
        assertTrue(CallRecordingLifecycle.shouldStopAudio("onCallState", 6));
        assertFalse(CallRecordingLifecycle.shouldStopAudio("onCallState", 5));
    }

    @Test
    public void nativePeerTeardownIsAnEarlyStopBoundary() {
        assertTrue(CallRecordingLifecycle.isPeerTermination("zrtc_peer_end_call"));
        assertTrue(CallRecordingLifecycle.isPeerTermination("zrtc_peer_force_stop"));
        assertTrue(CallRecordingLifecycle.isPeerTermination("zrtc_peer_delete"));
        assertFalse(CallRecordingLifecycle.isPeerTermination("zrtc_peer_hold_audio"));
    }
}
