package com.ez.zalopatch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RuntimeEnvironmentTest {
    @Test
    public void lspatchMarkerWinsOverSharedLsposedBridge() {
        assertEquals(RuntimeEnvironment.Framework.LSPATCH, RuntimeEnvironment.detect(
                true, true, 102, "/data/adb/lspd/framework/lspd.dex"));
    }

    @Test
    public void lsposedUsesBoundedClassApiAndLoaderEvidence() {
        assertEquals(RuntimeEnvironment.Framework.LSPOSED, RuntimeEnvironment.detect(
                false, true, 82));
        assertEquals(RuntimeEnvironment.Framework.LSPOSED, RuntimeEnvironment.detect(
                false, false, 102));
        assertEquals(RuntimeEnvironment.Framework.LSPOSED, RuntimeEnvironment.detect(
                false, false, 82, "/data/adb/modules/zygisk_lsposed/bin/daemon"));
        assertEquals(RuntimeEnvironment.Framework.UNKNOWN, RuntimeEnvironment.detect(
                false, false, 82, "/data/app/com.ez.zalopatch/base.apk"));
    }

    @Test
    public void resourceObservationIsMonotonicOnlyWithinSameEpoch() {
        RuntimeEnvironment.Snapshot first = snapshot(
                RuntimeEnvironment.Framework.LSPOSED,
                RuntimeEnvironment.ResourceHooks.PENDING, 1, 100L, 1L);
        RuntimeEnvironment.Snapshot observed = snapshot(
                RuntimeEnvironment.Framework.LSPOSED,
                RuntimeEnvironment.ResourceHooks.OBSERVED, 1, 100L, 2L);
        RuntimeEnvironment.Snapshot merged = RuntimeEnvironment.merge(first, observed);
        assertTrue(merged.resourceHooksObserved);

        RuntimeEnvironment.Snapshot laterFalse = snapshot(
                RuntimeEnvironment.Framework.LSPOSED,
                RuntimeEnvironment.ResourceHooks.PENDING, 1, 100L, 3L);
        assertTrue(RuntimeEnvironment.merge(merged, laterFalse).resourceHooksObserved);

        RuntimeEnvironment.Snapshot unavailable = snapshot(
                RuntimeEnvironment.Framework.LSPOSED,
                RuntimeEnvironment.ResourceHooks.UNAVAILABLE, 1, 100L, 4L);
        assertEquals(RuntimeEnvironment.ResourceHooks.UNAVAILABLE,
                RuntimeEnvironment.merge(first, unavailable).resourceHooks);
        assertEquals(RuntimeEnvironment.ResourceHooks.OBSERVED,
                RuntimeEnvironment.merge(unavailable, observed).resourceHooks);
        assertEquals(RuntimeEnvironment.ResourceHooks.OBSERVED,
                RuntimeEnvironment.merge(observed, unavailable).resourceHooks);

        RuntimeEnvironment.Snapshot lspatch = snapshot(
                RuntimeEnvironment.Framework.LSPATCH,
                RuntimeEnvironment.ResourceHooks.UNAVAILABLE, 1, 100L, 5L);
        assertFalse(RuntimeEnvironment.merge(merged, lspatch).resourceHooksObserved);
        assertEquals(RuntimeEnvironment.ResourceHooks.UNAVAILABLE,
                RuntimeEnvironment.merge(merged, lspatch).resourceHooks);
    }

    private static RuntimeEnvironment.Snapshot snapshot(
            RuntimeEnvironment.Framework framework, RuntimeEnvironment.ResourceHooks resources,
            int moduleVersion, long zaloVersion, long updatedAt) {
        return new RuntimeEnvironment.Snapshot(true, framework, resources,
                moduleVersion, zaloVersion, updatedAt);
    }
}
