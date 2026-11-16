package com.ez.zalopatch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Repeated reconciles must not bypass a persisted 426 module-too-old deadline. */
public final class CatalogFetchPolicyTest {
    private static final long NOW = 1_760_000_000_000L;
    private static final long INTERVAL = 6L * 60L * 60L * 1000L;
    private static final String HOST_HASH = "a".repeat(64);
    private static final String OTHER_HOST_HASH = "b".repeat(64);
    private static final long HOST_VERSION = 260801903L;
    private static final int MODULE_VERSION = 357;

    @Test
    public void persistedDeadlineHoldsAcrossRepeatedReconciles() {
        String key = CatalogFetchPolicy.cooldownKey(HOST_HASH, HOST_VERSION, MODULE_VERSION);
        // The deadline and key are read from preferences again on every pass, including after a
        // process restart; only the wall clock moves.
        long retryAt = CatalogFetchPolicy.retryAt(NOW, INTERVAL);
        for (int minute = 0; minute < 360; minute++) {
            long now = NOW + minute * 60_000L;
            boolean coolingDown = CatalogFetchPolicy.coolingDown(now, retryAt, key, key);
            assertTrue(coolingDown);
            assertFalse(CatalogFetchPolicy.shouldFetch(
                    false, false, false, false, true, false, coolingDown));
        }
    }

    @Test
    public void expiryPermitsTheNextRequest() {
        String key = CatalogFetchPolicy.cooldownKey(HOST_HASH, HOST_VERSION, MODULE_VERSION);
        long retryAt = CatalogFetchPolicy.retryAt(NOW, INTERVAL);

        assertTrue(CatalogFetchPolicy.coolingDown(NOW + INTERVAL - 1L, retryAt, key, key));
        assertFalse(CatalogFetchPolicy.coolingDown(NOW + INTERVAL, retryAt, key, key));
        assertTrue(CatalogFetchPolicy.shouldFetch(
                false, false, false, true, true, false, false));
    }

    @Test
    public void changedHostArtifactInvalidatesTheCooldown() {
        String stored = CatalogFetchPolicy.cooldownKey(HOST_HASH, HOST_VERSION, MODULE_VERSION);
        String changed = CatalogFetchPolicy.cooldownKey(
                OTHER_HOST_HASH, HOST_VERSION, MODULE_VERSION);

        assertNotEquals(stored, changed);
        assertFalse(CatalogFetchPolicy.coolingDown(
                NOW + 60_000L, NOW + INTERVAL, stored, changed));
        assertTrue(CatalogFetchPolicy.shouldFetch(
                false, true, false, false, true, false, false));
    }

    @Test
    public void changedHostVersionOrModuleBuildInvalidatesTheCooldown() {
        String stored = CatalogFetchPolicy.cooldownKey(HOST_HASH, HOST_VERSION, MODULE_VERSION);
        assertNotEquals(stored, CatalogFetchPolicy.cooldownKey(
                HOST_HASH, HOST_VERSION + 1L, MODULE_VERSION));
        assertNotEquals(stored, CatalogFetchPolicy.cooldownKey(
                HOST_HASH, HOST_VERSION, MODULE_VERSION + 1));

        String nextModule = CatalogFetchPolicy.cooldownKey(
                HOST_HASH, HOST_VERSION, MODULE_VERSION + 1);
        assertFalse(CatalogFetchPolicy.coolingDown(
                NOW + 60_000L, NOW + INTERVAL, stored, nextModule));
        assertTrue(CatalogFetchPolicy.shouldFetch(
                false, false, true, false, true, false, false));
    }

    @Test
    public void explicitRetryAndUnknownArtifactsStillRequest() {
        // checkNow clears the persisted deadline and checked-at marker before reconciling.
        assertFalse(CatalogFetchPolicy.coolingDown(NOW, 0L, "", ""));
        assertTrue(CatalogFetchPolicy.shouldFetch(
                false, false, false, true, true, true, false));
        // A recorded 404 remains a negative answer inside the interval.
        assertFalse(CatalogFetchPolicy.shouldFetch(
                false, false, false, false, true, true, false));
    }

    @Test
    public void instrumentationSuppressionStillWins() {
        assertFalse(CatalogFetchPolicy.shouldFetch(
                true, true, true, true, true, false, false));
    }

    @Test
    public void anUnavailableHostHashCannotCreateABlockingKey() {
        assertEquals("", CatalogFetchPolicy.cooldownKey("", HOST_VERSION, MODULE_VERSION));
        assertEquals("", CatalogFetchPolicy.cooldownKey(null, HOST_VERSION, MODULE_VERSION));
        assertFalse(CatalogFetchPolicy.coolingDown(NOW, NOW + INTERVAL, "", ""));
    }
}
