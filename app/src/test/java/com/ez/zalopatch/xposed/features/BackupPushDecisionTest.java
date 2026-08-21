package com.ez.zalopatch.xposed.features;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class BackupPushDecisionTest {
    @Test
    public void disabledKeepsNativeInterval() {
        assertEquals(86_400_000L,
                BackupPushDecision.scheduledIntervalMillis(false, 1, 86_400_000L));
    }

    @Test
    public void allowedIntervalsConvertToMilliseconds() {
        assertEquals(3_600_000L,
                BackupPushDecision.scheduledIntervalMillis(true, 1, 86_400_000L));
        assertEquals(10_800_000L,
                BackupPushDecision.scheduledIntervalMillis(true, 3, 86_400_000L));
        assertEquals(21_600_000L,
                BackupPushDecision.scheduledIntervalMillis(true, 6, 86_400_000L));
        assertEquals(43_200_000L,
                BackupPushDecision.scheduledIntervalMillis(true, 12, 86_400_000L));
    }

    @Test
    public void invalidIntervalUsesSixHourDefault() {
        assertEquals(21_600_000L,
                BackupPushDecision.scheduledIntervalMillis(true, 0, 86_400_000L));
        assertEquals(21_600_000L,
                BackupPushDecision.scheduledIntervalMillis(true, 24, 86_400_000L));
    }
}
