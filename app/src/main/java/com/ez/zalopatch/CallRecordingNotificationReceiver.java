package com.ez.zalopatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

/** Receives privacy-safe recording state changes from the Zalo process. */
public final class CallRecordingNotificationReceiver extends BroadcastReceiver {
    public static final String EXTRA_IDENTITY_BINDER = "identity_binder";
    public static final String EXTRA_STATE = "state";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_FINALIZING = "finalizing";
    public static final String STATE_FAILED = "failed";
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return;
        }
        IBinder identity = extras.getBinder(EXTRA_IDENTITY_BINDER);
        if (identity == null) {
            return;
        }
        try {
            int sourceUid = CallRecordingImportProtocol.attest(identity);
            if (!CallRecordingImportProtocol.callerAllowed(context, sourceUid)) {
                return;
            }
            String state = extras.getString(EXTRA_STATE, "");
            if (STATE_RUNNING.equals(state)) {
                CallRecordingNotifier.showRunning(context);
            } else if (STATE_FINALIZING.equals(state)) {
                CallRecordingNotifier.showFinalizing(context);
            } else if (STATE_FAILED.equals(state)) {
                CallRecordingNotifier.finishFailed(context);
            }
        } catch (Throwable ignored) {
        }
    }

}
