package com.ez.zalopatch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for the pilot-selection policy (review findings 1-3).
 *
 * <p>Each test pins one production branch: pending scans must disable the pilot instead of
 * leaking neighbouring flags, neighbouring coverage applies only through the explicit
 * DexKit-unavailable fallback, and partial entries never partially activate.
 */
public final class DexKitPilotDecisionTest {
    @Test
    public void coldMissWithScanAllowedDisablesPilotAndKicksScan() {
        DexKitPilotPolicy.Coverage coverage = base();
        coverage.cachePresent = false;
        coverage.dexkitAvailable = true;
        coverage.neighborAdopted = true;
        coverage.neighborMessage = true;
        coverage.neighborFeed = true;
        DexKitPilotPolicy.Decision decision = DexKitPilotPolicy.decide(coverage);
        assertFalse(decision.messageCompatible);
        assertFalse(decision.feedCompatible);
        assertTrue(decision.messageError.contains("pending"));
        assertEquals("dexkit_pending", decision.source);
        assertTrue(decision.kickScan);
        assertFalse(decision.clearCache);
        assertEquals("", decision.adOverride);
    }

    @Test
    public void coldMissWithBudgetExhaustedFallsBackToNeighbouring() {
        DexKitPilotPolicy.Coverage coverage = base();
        coverage.cachePresent = false;
        coverage.dexkitAvailable = false;
        coverage.unavailableReason = "retry budget exhausted";
        coverage.neighborAdopted = true;
        coverage.neighborMessage = true;
        coverage.neighborFeed = false;
        coverage.neighborFeedError = "class missing: q00.a";
        DexKitPilotPolicy.Decision decision = DexKitPilotPolicy.decide(coverage);
        assertTrue(decision.messageCompatible);
        assertFalse(decision.feedCompatible);
        assertEquals("q00.a", decision.feedError.substring(
                decision.feedError.length() - "q00.a".length()));
        assertEquals("neighbouring", decision.source);
        assertFalse(decision.kickScan);
        assertTrue(decision.markScanRow);
        assertEquals("retry budget exhausted", decision.scanRow.target);
    }

    @Test
    public void coldMissWithoutFallbackIsUnavailable() {
        DexKitPilotPolicy.Coverage coverage = base();
        coverage.cachePresent = false;
        coverage.dexkitAvailable = false;
        coverage.neighborAdopted = false;
        DexKitPilotPolicy.Decision decision = DexKitPilotPolicy.decide(coverage);
        assertFalse(decision.messageCompatible);
        assertFalse(decision.feedCompatible);
        assertEquals("unavailable", decision.source);
        assertFalse(decision.kickScan);
    }

    @Test
    public void negativeCacheWithholdsPilotWithoutNeighbouringBypass() {
        DexKitPilotPolicy.Coverage coverage = base();
        coverage.cacheBound = true;
        coverage.cacheNegative = true;
        coverage.cacheReason = "ad:no_candidates feed:ambiguous_margin";
        coverage.neighborAdopted = true;
        coverage.neighborMessage = true;
        coverage.neighborFeed = true;
        DexKitPilotPolicy.Decision decision = DexKitPilotPolicy.decide(coverage);
        assertFalse(decision.messageCompatible);
        assertFalse(decision.feedCompatible);
        assertEquals("dexkit_contradicted", decision.source);
        assertTrue(decision.messageError.contains("bypass withheld"));
        assertFalse(decision.kickScan);
        assertFalse(decision.clearCache);
        assertEquals("ad:no_candidates feed:ambiguous_margin", decision.cacheRow.target);
    }

    @Test
    public void partialEntryWithBothAnchorsEnabledRejectsEntirePilot() {
        DexKitPilotPolicy.Coverage coverage = base();
        coverage.cacheBound = true;
        coverage.cacheAdPresent = true;
        coverage.cacheAdUsable = true;
        coverage.cacheAdName = "c";
        coverage.cacheFeedPresent = false;
        coverage.neighborAdopted = true;
        coverage.neighborMessage = true;
        coverage.neighborFeed = true;
        DexKitPilotPolicy.Decision decision = DexKitPilotPolicy.decide(coverage);
        assertFalse(decision.messageCompatible);
        assertFalse(decision.feedCompatible);
        assertEquals("", decision.adOverride);
        assertEquals("dexkit_contradicted", decision.source);
        assertEquals("partial_result", decision.cacheRow.target);
        assertFalse(decision.clearCache);
    }

    @Test
    public void partialEntryBelowRetryCapKicksRescanWhileWithheld() {
        DexKitPilotPolicy.Coverage coverage = base();
        coverage.cacheBound = true;
        coverage.cacheAdPresent = true;
        coverage.cacheAdUsable = true;
        coverage.cacheAdName = "c";
        coverage.cacheFeedPresent = false;
        coverage.cachePartialAttempts = 1;
        DexKitPilotPolicy.Decision decision = DexKitPilotPolicy.decide(coverage);
        assertFalse(decision.messageCompatible);
        assertFalse(decision.feedCompatible);
        assertEquals("dexkit_contradicted", decision.source);
        assertEquals("partial_result", decision.cacheRow.target);
        assertTrue(decision.kickScan);
        assertTrue(decision.cacheRow.detail.contains("rescan 2/3"));
    }

    @Test
    public void partialEntryAtRetryCapPinsWithoutRescan() {
        DexKitPilotPolicy.Coverage coverage = base();
        coverage.cacheBound = true;
        coverage.cacheAdPresent = true;
        coverage.cacheAdUsable = true;
        coverage.cacheAdName = "c";
        coverage.cacheFeedPresent = false;
        coverage.cachePartialAttempts = 3;
        DexKitPilotPolicy.Decision decision = DexKitPilotPolicy.decide(coverage);
        assertFalse(decision.messageCompatible);
        assertEquals("dexkit_contradicted", decision.source);
        assertFalse(decision.kickScan);
        assertTrue(decision.cacheRow.detail.contains("retries exhausted"));
    }

    @Test
    public void singleEnabledAnchorUsesItsOwnResolution() {
        DexKitPilotPolicy.Coverage coverage = base();
        coverage.feedEnabled = false;
        coverage.cacheBound = true;
        coverage.cacheAdPresent = true;
        coverage.cacheAdUsable = true;
        coverage.cacheAdName = "c";
        coverage.cacheFeedPresent = false;
        DexKitPilotPolicy.Decision decision = DexKitPilotPolicy.decide(coverage);
        assertTrue(decision.messageCompatible);
        assertEquals("c", decision.adOverride);
        assertFalse(decision.feedCompatible);
        assertEquals("dexkit_cache", decision.source);
    }

    @Test
    public void skewedEntryIsClearedForRescan() {
        DexKitPilotPolicy.Coverage coverage = base();
        coverage.cacheBound = true;
        coverage.cacheAdPresent = true;
        coverage.cacheAdUsable = false;
        coverage.cacheFeedPresent = true;
        coverage.cacheFeedUsable = true;
        coverage.cacheFeedName = "c";
        DexKitPilotPolicy.Decision decision = DexKitPilotPolicy.decide(coverage);
        assertFalse(decision.messageCompatible);
        assertFalse(decision.feedCompatible);
        assertEquals("dexkit_contradicted", decision.source);
        assertEquals("preflight_failed", decision.cacheRow.target);
        assertTrue(decision.clearCache);
        assertTrue(decision.cacheRow.detail.contains("rescan at next restart"));
    }

    @Test
    public void exactCoverageNeverTouchesScanOrCache() {
        DexKitPilotPolicy.Coverage coverage = base();
        coverage.exactMessage = true;
        coverage.exactFeed = true;
        coverage.exactMessageError = "";
        coverage.cacheBound = true;
        coverage.neighborAdopted = true;
        DexKitPilotPolicy.Decision decision = DexKitPilotPolicy.decide(coverage);
        assertTrue(decision.messageCompatible);
        assertTrue(decision.feedCompatible);
        assertEquals("exact", decision.source);
        assertFalse(decision.kickScan);
        assertFalse(decision.clearCache);
        assertFalse(decision.markScanRow);
        assertEquals("standby", decision.cacheRow.target);
    }

    @Test
    public void mixedExactAndPendingKeepsExactAnchorLive() {
        DexKitPilotPolicy.Coverage coverage = base();
        coverage.exactMessage = true;
        coverage.exactMessageError = "";
        coverage.cachePresent = false;
        coverage.dexkitAvailable = true;
        DexKitPilotPolicy.Decision decision = DexKitPilotPolicy.decide(coverage);
        assertTrue(decision.messageCompatible);
        assertFalse(decision.feedCompatible);
        assertTrue(decision.feedError.contains("pending"));
        assertEquals("dexkit_pending", decision.source);
        assertTrue(decision.kickScan);
    }

    @Test
    public void disabledTogglesDisableBothRows() {
        DexKitPilotPolicy.Coverage coverage = base();
        coverage.messageEnabled = false;
        coverage.feedEnabled = false;
        DexKitPilotPolicy.Decision decision = DexKitPilotPolicy.decide(coverage);
        assertEquals("disabled", decision.source);
        assertEquals("disabled", decision.cacheRow.status);
        assertFalse(decision.kickScan);
    }

    private DexKitPilotPolicy.Coverage base() {
        DexKitPilotPolicy.Coverage coverage = new DexKitPilotPolicy.Coverage();
        coverage.messageEnabled = true;
        coverage.feedEnabled = true;
        coverage.cacheQueryRevision = DexKitZinstantFingerprint.QUERY_REVISION;
        coverage.cacheScanDurationMs = 812L;
        coverage.cacheMatchAd = 1;
        coverage.cacheMatchFeed = 1;
        coverage.cacheDigest = "5736dd272918b73d689d11ff62e9399d80effaad85b30f2180b24ab8809a09ed";
        return coverage;
    }
}
