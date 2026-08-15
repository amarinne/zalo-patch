package com.ez.zalopatch;

import android.app.Application;
import android.content.Context;

public final class ZpApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(UiSettings.localizedContext(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        RuntimeStatusTraceStore.archiveCurrent(this);
        UiSettings.ensureDefaultLanguage(this);
    }
}
