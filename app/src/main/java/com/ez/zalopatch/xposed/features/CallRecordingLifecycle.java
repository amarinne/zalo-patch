package com.ez.zalopatch.xposed.features;

/** Stable audio-recording decisions derived from ZRTC call callbacks. */
public final class CallRecordingLifecycle {
    static final int UNKNOWN_STATE = Integer.MIN_VALUE;
    static final int CONNECTED_AUDIO_STATE = 32;
    /**
     * Connected-call state carried by {@code onCallState}. Verified on device: a connected
     * one-to-one call emitted {@code onCallState(3)}, {@code (4)}, then {@code (5)}, and the
     * host's own connected predicate is {@code state == 5}. {@code onPreConnectSuccessful}
     * never fired on 260901903, so this is the confirm edge there.
     */
    static final int CONNECTED_CALL_STATE = 5;
    static final int TERMINAL_CALL_STATE = 6;

    private CallRecordingLifecycle() {
    }

    public static boolean observes(String methodName) {
        return "onIncomingCall".equals(methodName)
                || "onMakeCall".equals(methodName)
                || "onCallConfirmed".equals(methodName)
                || "onPreConnectSuccessful".equals(methodName)
                || "onCallAudioState".equals(methodName)
                || "onCallVideoState".equals(methodName)
                || "onCallState".equals(methodName)
                || "onCallEnd".equals(methodName)
                || "onCallErr".equals(methodName)
                || "onCallAutoHangup".equals(methodName);
    }

    static boolean beginsCall(String methodName) {
        return "onIncomingCall".equals(methodName) || "onMakeCall".equals(methodName);
    }

    static boolean confirmsCall(String methodName, int state) {
        // Older ZRTC exposed onCallConfirmed; 26.08.02 used onPreConnectSuccessful; 26.09.01
        // reaches the connected state through onCallState(5) instead. Accept all three so a
        // single confirm edge change cannot silently disable recording.
        return "onCallConfirmed".equals(methodName)
                || "onPreConnectSuccessful".equals(methodName)
                || ("onCallState".equals(methodName) && state == CONNECTED_CALL_STATE);
    }

    static boolean connectsAudio(String methodName, int state) {
        return "onCallAudioState".equals(methodName) && state == CONNECTED_AUDIO_STATE;
    }

    static boolean shouldStartAudio(boolean confirmed, boolean audioConnected) {
        return confirmed && audioConnected;
    }

    static boolean shouldStopAudio(String methodName, int state) {
        return "onCallEnd".equals(methodName)
                || "onCallErr".equals(methodName)
                || "onCallAutoHangup".equals(methodName)
                || ("onCallState".equals(methodName) && state == TERMINAL_CALL_STATE);
    }

    static boolean isVideoState(String methodName) {
        return "onCallVideoState".equals(methodName);
    }

    static boolean isPeerTermination(String methodName) {
        return "zrtc_peer_end_call".equals(methodName)
                || "zrtc_peer_force_stop".equals(methodName)
                || "zrtc_peer_delete".equals(methodName);
    }
}
