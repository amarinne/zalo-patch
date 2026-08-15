package com.ez.zalopatch.xposed.core;

import android.util.Log;

import com.ez.zalopatch.HookConfig;

import de.robv.android.xposed.XposedBridge;

public abstract class Feature {
    protected final ClassLoader classLoader;

    protected Feature(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public abstract String getFeatureName();

    public abstract void doHook() throws Throwable;

    protected final void runGuarded(String label, String feature, String target,
                                    ThrowingRunnable body) {
        try {
            body.run();
            SelfCheckRegistry.markInstalled(feature, target, 1);
            log(label + " installed");
        } catch (Throwable throwable) {
            SelfCheckRegistry.markStale(feature, target,
                    throwable.getClass().getSimpleName() + " " + throwable.getMessage());
            log(label + " skipped: " + throwable.getClass().getSimpleName() + " "
                    + throwable.getMessage());
        }
    }

    protected interface ThrowingRunnable {
        void run() throws Throwable;
    }

    protected final void log(String message) {
        String fullMessage = "ZaloPatch: [" + getFeatureName() + "] " + message;
        XposedBridge.log(fullMessage);
        if (HookConfig.isDebugEnabled()) {
            Log.i("ZaloPatch", fullMessage);
        }
    }
}
