package com.ez.zalopatch.xposed.core;

import android.util.Log;



/** Hook logging through Android logcat; root access is not required. */
public final class XpLog {
    private static final String DEFAULT_TAG = "ZaloPatch";

    private XpLog() {
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
        String safeTag = tag == null ? DEFAULT_TAG : tag;
        String safeMessage = message == null ? "" : message;
        if (throwable == null) {
            Log.println(priority, safeTag, safeMessage);
        } else {
            Log.println(priority, safeTag,
                    safeMessage + '\n' + Log.getStackTraceString(throwable));
        }
    }
}
