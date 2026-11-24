package com.ez.zalopatch;

import android.app.Application;
import android.content.Context;
import android.os.Build;

public final class ZpApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(isRecordingWorker(base) ? base : UiSettings.localizedContext(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (isRecordingWorker(this)) return;
        FrameworkConfig.initialize(this);
        RuntimeStatusTraceStore.archiveCurrent(this);
        UiSettings.ensureDefaultLanguage(this);
    }

    private static boolean isRecordingWorker(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && (context.getPackageName() + CallRecordingFinalizationJob.PROCESS_SUFFIX)
                        .equals(Application.getProcessName());
    }
}
