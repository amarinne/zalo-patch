package com.ez.zalopatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

/** Receives a Binder capability from Zalo and queues its recording for private conversion. */
public final class CallRecordingImportReceiver extends BroadcastReceiver {
    public static final String EXTRA_SOURCE_BINDER = "source_binder";
    public static final String EXTRA_PENDING_NAME = "pending_name";
    public static final String EXTRA_DISPLAY_NAME = "display_name";
    public static final String EXTRA_PHONE_NUMBER = "phone_number";
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return;
        }
        IBinder sourceBinder = extras.getBinder(EXTRA_SOURCE_BINDER);
        if (sourceBinder == null) {
            return;
        }
        ParcelFileDescriptor source = null;
        boolean accepted = false;
        try {
            CallRecordingImportProtocol.OpenedSource opened =
                    CallRecordingImportProtocol.open(sourceBinder);
            source = opened == null ? null : opened.descriptor;
            if (source != null
                    && CallRecordingImportProtocol.callerAllowed(context, opened.sourceUid)) {
                accepted = CallRecordingStore.enqueueImport(context.getApplicationContext(),
                        source,
                        extras.getString(EXTRA_PENDING_NAME, ""),
                        extras.getString(EXTRA_DISPLAY_NAME, "Zalo contact"),
                        extras.getString(EXTRA_PHONE_NUMBER, ""));
                if (accepted) {
                    source = null;
                }
            }
        } catch (Throwable ignored) {
            accepted = false;
        } finally {
            closeQuietly(source);
            try {
                CallRecordingImportProtocol.complete(sourceBinder, accepted);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (Exception ignored) {
        }
    }
}
