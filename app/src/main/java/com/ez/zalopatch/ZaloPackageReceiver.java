package com.ez.zalopatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ZaloPackageReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && intent.getData() != null
                && SymbolSchema.TARGET_PACKAGE.equals(intent.getData().getSchemeSpecificPart())) {
            ZaloArtifactState.schedule(context.getApplicationContext(), true);
        }
    }
}
