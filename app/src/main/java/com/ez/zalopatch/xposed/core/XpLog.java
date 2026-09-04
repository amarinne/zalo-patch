package com.ez.zalopatch.xposed.core;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * Framework logging for hook-side code.
 *
 * <p>Replaces {@code XposedBridge.log} from the legacy API 82 contract. The LibXposed API 102
 * entry attaches its {@link XposedInterface} once per module generation; every feature logs
 * through this facade so no feature class touches the framework type directly. When no
 * interface is attached (plain JVM unit tests, module-process calls) the message falls back
 * to logcat instead of throwing.
 */
public final class XpLog {
    private static final String DEFAULT_TAG = "ZaloPatch";
    private static volatile XposedInterface api;

    private XpLog() {
    }

    public static void attach(XposedInterface framework) {
        api = framework;
    }

    public static void i(String message) {
        log(Log.INFO, DEFAULT_TAG, message, null);
    }

    public static void i(String tag, String message) {
        log(Log.INFO, tag, message, null);
    }

    public static void w(String message) {
        log(Log.WARN, DEFAULT_TAG, message, null);
    }

    public static void e(String message, Throwable throwable) {
        log(Log.ERROR, DEFAULT_TAG, message, throwable);
    }

    public static void log(int priority, String tag, String message) {
        log(priority, tag, message, null);
    }

    public static void log(int priority, String tag, String message, Throwable throwable) {
        XposedInterface framework = api;
        String safeTag = tag == null ? DEFAULT_TAG : tag;
        String safeMessage = message == null ? "" : message;
        if (framework != null) {
            try {
                if (throwable == null) {
                    framework.log(priority, safeTag, safeMessage);
                } else {
                    framework.log(priority, safeTag, safeMessage, throwable);
                }
                return;
            } catch (Throwable ignored) {
            }
        }
        if (throwable == null) {
            Log.println(priority, safeTag, safeMessage);
        } else {
            Log.println(priority, safeTag,
                    safeMessage + '\n' + Log.getStackTraceString(throwable));
        }
    }
}
