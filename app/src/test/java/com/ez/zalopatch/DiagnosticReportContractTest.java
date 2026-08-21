package com.ez.zalopatch;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DiagnosticReportContractTest {
    @Test
    public void reportIdUsesCrockfordEncodingOf128Bits() {
        byte[] bytes = new byte[16];
        bytes[15] = 1;
        String reportId = DiagnosticReportContract.reportIdFromBytes(bytes);

        assertEquals("R1-00000000000000000000000001", reportId);
        assertTrue(DiagnosticReportContract.validReportId(reportId));
        assertFalse(DiagnosticReportContract.validReportId(
                "R1-0000000000000000000000000I"));
    }

    @Test
    public void utf8DescriptionBoundNeverSplitsCodePoint() {
        String bounded = DiagnosticReportContract.utf8Prefix("a🙂b", 5);

        assertEquals("a🙂", bounded);
        assertEquals(5, bounded.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    public void truncationKeepsOldestPrefixAndNewestWholeLines() {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < 40; index++) {
            value.append("line-").append(index).append('\n');
        }

        DiagnosticReportContract.BoundedText result =
                DiagnosticReportContract.truncateLines(value.toString(), 96);

        assertTrue(result.truncated);
        assertTrue(result.text.contains("TRUNCATED"));
        assertTrue(DiagnosticReportContract.utf8Bytes(result.text) <= 96);
        assertFalse(result.text.endsWith("line-3"));
    }

    @Test
    public void sanitizerRedactsUrisCredentialsAndThrowableMessages() {
        String value = "url=https://example.test/private token=secret "
                + "java.lang.IllegalStateException: private text";

        String sanitized = DiagnosticReportContract.redactLine(value);

        assertFalse(sanitized.contains("example.test"));
        assertFalse(sanitized.contains("secret"));
        assertFalse(sanitized.contains("private text"));
        assertTrue(sanitized.contains("<uri redacted>"));
        assertTrue(sanitized.contains("token=<redacted>"));
        assertTrue(sanitized.contains("IllegalStateException: <message redacted>"));
    }

    @Test
    public void crashFilterKeepsOnlyAllowedProcessesAndDropsMessages() {
        String crash = "FATAL EXCEPTION: main\n"
                + "Process: com.other.app, PID: 1\n"
                + "java.lang.IllegalStateException: other private\n"
                + "FATAL EXCEPTION: main\n"
                + "Process: com.zing.zalo, PID: 2\n"
                + "java.lang.IllegalStateException: zalo private\n"
                + "    at com.zing.zalo.Example.run(Example.java:1)\n";

        String filtered = DiagnosticCaptureCollector.filterAllowedCrashBlocks(crash);

        assertFalse(filtered.contains("com.other.app"));
        assertFalse(filtered.contains("zalo private"));
        assertTrue(filtered.contains("Process: com.zing.zalo"));
        assertTrue(filtered.contains("IllegalStateException: <message redacted>"));
    }

    @Test
    public void moduleLogFilterDropsKnownIdentityAndTitleSnapshots() {
        String logs = "ZaloPatch: [Inbox] ROW uid=123 | title=Private chat\n"
                + "ZaloPatch: [Inbox] Tap row -> uid=123 title=Private chat\n"
                + "ZaloPatch: [MeCleanup] TabMe item snapshot title=Private\n"
                + "ZaloPatch: [Inbox] Category histogram: group=4\n";

        String filtered = DiagnosticCaptureCollector.filterSafeModuleLines(logs);

        assertFalse(filtered.contains("Private"));
        assertFalse(filtered.contains("uid=123"));
        assertTrue(filtered.contains("Category histogram"));
    }

    @Test
    public void moduleLogFilterKeepsRemapShapesButDropsLiveDiscoveryValues() {
        String logs = "ZaloPatch: [RuntimeDiscovery] CANDIDATE inbox_adapter=ab.c\n"
                + "ZaloPatch: [RuntimeDiscovery] VIEW com.zing.zalo.ui.maintab.MainTabView\n"
                + "ZaloPatch: [RuntimeDiscovery]   fields -> A:int, B:ab.c\n"
                + "ZaloPatch: [RuntimeDiscovery]     field x -> ab.c ints=[a=4, b=12345]\n";

        String filtered = DiagnosticCaptureCollector.filterSafeModuleLines(logs);

        assertTrue(filtered.contains("CANDIDATE inbox_adapter=ab.c"));
        assertTrue(filtered.contains("fields -> A:int"));
        assertFalse(filtered.contains("b=12345"));
    }

    @Test
    public void uploaderPreservesDistinctFailureClasses() {
        assertEquals(DiagnosticUploader.Kind.INVALID_REPORT,
                DiagnosticUploader.mapStatus(400));
        assertEquals(DiagnosticUploader.Kind.REPORT_ID_COLLISION,
                DiagnosticUploader.mapStatus(409));
        assertEquals(DiagnosticUploader.Kind.REQUEST_TOO_LARGE,
                DiagnosticUploader.mapStatus(413));
        assertEquals(DiagnosticUploader.Kind.RATE_LIMITED,
                DiagnosticUploader.mapStatus(429));
        assertEquals(DiagnosticUploader.Kind.STORAGE_UNAVAILABLE,
                DiagnosticUploader.mapStatus(503));
        assertEquals(DiagnosticUploader.Kind.REDIRECT_REJECTED,
                DiagnosticUploader.mapStatus(302));
        assertEquals(DiagnosticUploader.Kind.SERVER_ERROR,
                DiagnosticUploader.mapStatus(500));
    }

    @Test
    public void mappedHealthyCompatibilityReportDoesNotRequireGuidedCapture() {
        assertFalse(DiagnosticReportActivity.requiresGuidedCapture(
                "compatibility", true, true, true, 0, 0));
    }

    @Test
    public void unhealthyCompatibilityReportRequiresGuidedCapture() {
        assertTrue(DiagnosticReportActivity.requiresGuidedCapture(
                "compatibility", true, true, true, 0, 1));
        assertTrue(DiagnosticReportActivity.requiresGuidedCapture(
                "compatibility", false, true, true, 0, 0));
    }

    @Test
    public void behaviorReportRecommendsGuidedCapture() {
        assertTrue(DiagnosticReportActivity.requiresGuidedCapture(
                "hook_behavior", true, true, true, 0, 0));
    }

    @Test
    public void remapLogExtractionKeepsMetadataOnlyDiscoveryLines() {
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> surfaces = new java.util.LinkedHashSet<>();

        DiagnosticReportFactory.collectRemapLinesForTest(
                "ZaloPatch: [RuntimeDiscovery] CANDIDATE inbox_adapter=ab.c\n"
                        + "ZaloPatch: [RuntimeDiscovery] VIEW com.zing.zalo.ui.maintab.MainTabView\n"
                        + "ZaloPatch: [Inbox] ROW uid=private title=private",
                candidates, surfaces);

        assertEquals(1, candidates.size());
        assertEquals(1, surfaces.size());
        assertFalse(candidates.toString().contains("private"));
    }

    @Test
    public void uploadRequiresReviewOfTheExactDraft() {
        assertFalse(DiagnosticReportActivity.canUpload("R1-A", null));
        assertFalse(DiagnosticReportActivity.canUpload("R1-A", "R1-B"));
        assertTrue(DiagnosticReportActivity.canUpload("R1-A", "R1-A"));
    }

    @Test
    public void rootlessCaptureProducesExplicitMetadataOnlyEnvelope() {
        DiagnosticCaptureCollector.CapturedData data =
                new DiagnosticCaptureCollector(new DiagnosticRootProcessRunner())
                        .collect(123L, RootAccess.State.ABSENT);

        assertEquals("metadata_only_root_denied", data.outcome);
        assertEquals("error", data.rootAccessStatus);
        assertEquals(java.util.Collections.singletonList("root_access"),
                data.commandFailures);
        assertEquals("", data.logs);
        assertEquals("", data.crashExcerpt);
        assertEquals("", data.lsposedLines);
    }
}
