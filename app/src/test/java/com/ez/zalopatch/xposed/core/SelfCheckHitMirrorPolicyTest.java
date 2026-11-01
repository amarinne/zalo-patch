package com.ez.zalopatch.xposed.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class SelfCheckHitMirrorPolicyTest {
    private static final long INTERVAL = SelfCheckHitMirrorPolicy.INTERVAL_MS;

    @Test public void firstFlushIsImmediate() {
        assertEquals(0L, SelfCheckHitMirrorPolicy.delayMillis(5_000L, 0L, INTERVAL));
    }

    @Test public void hitInsideWindowWaitsForTheRemainder() {
        assertEquals(400L, SelfCheckHitMirrorPolicy.delayMillis(5_600L, 5_000L, INTERVAL));
    }

    @Test public void hitAtOrAfterWindowFlushesImmediately() {
        assertEquals(0L, SelfCheckHitMirrorPolicy.delayMillis(6_000L, 5_000L, INTERVAL));
        assertEquals(0L, SelfCheckHitMirrorPolicy.delayMillis(61_000L, 5_000L, INTERVAL));
    }

    @Test public void backwardsClockDoesNotStallReporting() {
        assertEquals(0L, SelfCheckHitMirrorPolicy.delayMillis(4_000L, 5_000L, INTERVAL));
    }

    @Test public void intervalStaysAtOneSecondPerFeature() {
        assertEquals(1_000L, INTERVAL);
    }
}
