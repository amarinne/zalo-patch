package com.ez.zalopatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ZaloPackageReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && intent.getData() != null
                && SymbolSchema.TARGET_PACKAGE.equals(intent.getData().getSchemeSpecificPart())) {
            Context appContext = context.getApplicationContext();
            ZaloArtifactState.schedule(appContext, true);
            // A Zalo update changes the DexKit scan scope; re-mirror so the hook process
            // sees a fresh budget instead of a stale one. Off the receiver thread: the
            // mirror write shells out to su.
            final PendingResult result = goAsync();
            new Thread(() -> {
                try {
                    DexKitMirror.sync(appContext);
                } finally {
                    result.finish();
                }
            }, "dexkit-mirror-sync").start();
        }
    }
}
