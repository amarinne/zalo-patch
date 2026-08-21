package com.ez.zalopatch.xposed.features;

/** Dependency-free decisions for the scheduled interval and explicit manual trigger. */
public final class BackupPushDecision {
    private static final long HOUR_MS = 60L * 60L * 1000L;
    private static final int DEFAULT_INTERVAL_HOURS = 6;

    private BackupPushDecision() {
    }

    public static long scheduledIntervalMillis(boolean enabled, int intervalHours,
                                               long nativeIntervalMillis) {
        if (!enabled) {
            return nativeIntervalMillis;
        }
        int hours = intervalHours == 1 || intervalHours == 3
                || intervalHours == 6 || intervalHours == 12
                ? intervalHours : DEFAULT_INTERVAL_HOURS;
        return hours * HOUR_MS;
    }
}
