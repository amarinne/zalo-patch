package com.ez.zalopatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ZaloPackageReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getData() == null) return;
        String changed = intent.getData().getSchemeSpecificPart();
        Context appContext = context.getApplicationContext();
        // Route the changed package to its own scope. An unregistered rename is ignored; it is
        // never reconciled or mirrored as the original.
        String target = ZaloArtifactState.eventTarget(appContext, changed);
        if (target == null) return;
        Context scoped = HostPackages.scoped(appContext, target);
        ZaloArtifactState.schedule(scoped, true);
        // A Zalo update changes the DexKit scan scope; re-mirror so the hook process
        // sees a fresh budget instead of a stale one. Keep transport IPC off the receiver thread.
        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                DexKitMirror.sync(scoped);
            } finally {
                result.finish();
            }
        }, "dexkit-mirror-sync").start();
    }
}
