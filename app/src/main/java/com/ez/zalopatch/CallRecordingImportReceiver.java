package com.ez.zalopatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.util.concurrent.atomic.AtomicBoolean;

/** Acknowledges a Binder recording only after its private recovery copy is complete. */
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
        PendingResult pending = goAsync();
        AtomicBoolean finished = new AtomicBoolean();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable finishBroadcast = () -> {
            if (finished.compareAndSet(false, true) && pending != null) {
                pending.finish();
            }
        };
        // A stalled disk must not cause a broadcast ANR. Releasing this lifetime grant
        // never acknowledges the import; a killed worker still leaves the source intact.
        handler.postDelayed(finishBroadcast, 8_000L);
        AtomicBoolean completed = new AtomicBoolean();
        CallRecordingImportProtocol.Completion completion = accepted -> {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            try {
                CallRecordingImportProtocol.complete(sourceBinder, accepted);
            } catch (Throwable ignored) {
                // The copied WAV remains recoverable if the sender's Binder has died.
            } finally {
                handler.removeCallbacks(finishBroadcast);
                finishBroadcast.run();
            }
        };
        ParcelFileDescriptor source = null;
        boolean queued = false;
        try {
            CallRecordingImportProtocol.OpenedSource opened =
                    CallRecordingImportProtocol.open(sourceBinder);
            source = opened == null ? null : opened.descriptor;
            if (source != null
                    && CallRecordingImportProtocol.callerAllowed(context, opened.sourceUid)) {
                queued = CallRecordingStore.enqueueImport(context.getApplicationContext(),
                        source,
                        extras.getString(EXTRA_PENDING_NAME, ""),
                        extras.getString(EXTRA_DISPLAY_NAME, "Zalo contact"),
                        extras.getString(EXTRA_PHONE_NUMBER, ""), completion);
                if (queued) {
                    source = null;
                }
            }
        } catch (Throwable ignored) {
            queued = false;
        } finally {
            closeQuietly(source);
            if (!queued) {
                completion.onComplete(false);
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
