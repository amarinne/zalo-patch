package com.ez.zalopatch.xposed.features;

/** Stable audio-recording decisions derived from ZRTC call callbacks. */
final class CallRecordingLifecycle {
    static final int UNKNOWN_STATE = Integer.MIN_VALUE;
    static final int CONNECTED_AUDIO_STATE = 32;
    static final int TERMINAL_CALL_STATE = 6;

    private CallRecordingLifecycle() {
    }

    static boolean observes(String methodName) {
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

    static boolean confirmsCall(String methodName) {
        // Current ZRTC callback has no onCallConfirmed method. Its confirmed-call edge is
        // onPreConnectSuccessful; retain the older name for versions that still expose it.
        return "onCallConfirmed".equals(methodName)
                || "onPreConnectSuccessful".equals(methodName);
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
