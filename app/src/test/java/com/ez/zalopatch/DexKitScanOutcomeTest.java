package com.ez.zalopatch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests: query errors are retryable failures and must never be cached (finding 4). */
public final class DexKitScanOutcomeTest {
    @Test
    public void queryErrorOnEitherAnchorRetriesWithoutRecording() {
        DexKitPilotPolicy.ScanInputs inputs = resolved("c", "c");
        inputs.adQueryError = "UnsatisfiedLinkError";
        DexKitPilotPolicy.ScanOutcome outcome =
                DexKitPilotPolicy.resolveScanOutcome(inputs);
        assertFalse(outcome.record);
        assertTrue(outcome.retryReason.startsWith("query_failed:"));
        assertTrue(outcome.retryReason.contains("UnsatisfiedLinkError"));

        DexKitPilotPolicy.ScanInputs feed = resolved("c", "c");
        feed.feedQueryError = "bridge closed";
        DexKitPilotPolicy.ScanOutcome feedOutcome =
                DexKitPilotPolicy.resolveScanOutcome(feed);
        assertFalse(feedOutcome.record);
        assertTrue(feedOutcome.retryReason.contains("bridge closed"));
    }

    @Test
    public void bridgeCreationFailureRetriesWithoutRecording() {
        DexKitPilotPolicy.ScanInputs inputs = resolved("", "");
        inputs.adStatus = "";
        inputs.feedStatus = "";
        inputs.adQueryError = "create failed";
        inputs.feedQueryError = "create failed";
        DexKitPilotPolicy.ScanOutcome outcome =
                DexKitPilotPolicy.resolveScanOutcome(inputs);
        assertFalse(outcome.record);
        assertTrue(outcome.retryReason.contains("create failed"));
    }

    @Test
    public void executedEmptyQueriesRecordNegative() {
        DexKitPilotPolicy.ScanInputs inputs = resolved("", "");
        inputs.adStatus = "no_candidates";
        inputs.feedStatus = "ambiguous_margin";
        DexKitPilotPolicy.ScanOutcome outcome =
                DexKitPilotPolicy.resolveScanOutcome(inputs);
        assertTrue(outcome.record);
        assertTrue(outcome.entry.negative);
        assertEquals("ad:no_candidates feed:ambiguous_margin", outcome.entry.reason);
        assertTrue(DexKitCache.bindsTo(outcome.entry, 260802903L,
                "5736dd272918b73d689d11ff62e9399d80effaad85b30f2180b24ab8809a09ed",
                1726051200000L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, 204));
    }

    @Test
    public void partialScanRecordsPartialPositive() {
        DexKitPilotPolicy.ScanInputs inputs = resolved("c", "");
        inputs.adStatus = "resolved";
        inputs.feedStatus = "ambiguous_margin";
        DexKitPilotPolicy.ScanOutcome outcome =
                DexKitPilotPolicy.resolveScanOutcome(inputs);
        assertTrue(outcome.record);
        assertFalse(outcome.entry.negative);
        assertEquals("c", outcome.entry.adBind);
        assertEquals("", outcome.entry.feedBind);
        assertEquals("ad only; feed ambiguous_margin", outcome.entry.reason);
        assertEquals(1, outcome.entry.partialAttempts);
    }

    @Test
    public void partialRetryCountsUpFromPreviousEntry() {
        DexKitPilotPolicy.ScanInputs inputs = resolved("c", "");
        inputs.adStatus = "resolved";
        inputs.feedStatus = "ambiguous_margin";
        inputs.previousPartialAttempts = 2;
        DexKitPilotPolicy.ScanOutcome outcome =
                DexKitPilotPolicy.resolveScanOutcome(inputs);
        assertTrue(outcome.record);
        assertEquals(3, outcome.entry.partialAttempts);
    }

    @Test
    public void fullScanRecordsPositive() {
        DexKitPilotPolicy.ScanOutcome outcome =
                DexKitPilotPolicy.resolveScanOutcome(resolved("c", "c"));
        assertTrue(outcome.record);
        assertFalse(outcome.entry.negative);
        assertEquals("c", outcome.entry.adBind);
        assertEquals("c", outcome.entry.feedBind);
        assertEquals("", outcome.entry.reason);
        assertEquals(1, outcome.entry.matchAd);
        assertEquals(1, outcome.entry.matchFeed);
        assertTrue(DexKitCache.fullyResolved(outcome.entry));
        assertEquals(0, outcome.entry.partialAttempts);
    }

    private DexKitPilotPolicy.ScanInputs resolved(String ad, String feed) {
        DexKitPilotPolicy.ScanInputs inputs = new DexKitPilotPolicy.ScanInputs();
        inputs.adStatus = ad.isEmpty() ? "no_candidates" : "resolved";
        inputs.adSymbol = ad;
        inputs.feedStatus = feed.isEmpty() ? "no_candidates" : "resolved";
        inputs.feedSymbol = feed;
        inputs.matchAd = ad.isEmpty() ? 0 : 1;
        inputs.matchFeed = feed.isEmpty() ? 0 : 1;
        inputs.versionCode = 260802903L;
        inputs.codeDigest = "5736dd272918b73d689d11ff62e9399d80effaad85b30f2180b24ab8809a09ed";
        inputs.lastUpdateTime = 1726051200000L;
        inputs.apkSize = 68157440L;
        inputs.queryRevision = DexKitZinstantFingerprint.QUERY_REVISION;
        inputs.resolverFormat = DexKitCache.RESOLVER_FORMAT;
        inputs.moduleVersion = 204;
        inputs.durationMs = 812L;
        inputs.scannedAt = 1726051200000L;
        return inputs;
    }
}
