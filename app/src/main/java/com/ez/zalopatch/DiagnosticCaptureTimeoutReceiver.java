package com.ez.zalopatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores temporary debug logging when a guided capture expires. */
public final class DiagnosticCaptureTimeoutReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                DiagnosticCaptureManager.expireIfNeeded(appContext);
            } finally {
                pending.finish();
            }
        }, "diagnostic-capture-timeout").start();
    }
}
