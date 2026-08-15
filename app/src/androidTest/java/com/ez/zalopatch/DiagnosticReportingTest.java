package com.ez.zalopatch;

import android.test.AndroidTestCase;

import org.json.JSONArray;
import org.json.JSONObject;

public final class DiagnosticReportingTest extends AndroidTestCase {
    public void testMetadataEnvelopeUsesSharedIntakeContractWithoutPrivatePayloadFields()
            throws Exception {
        DiagnosticReportFactory.Draft draft = DiagnosticReportFactory.createMetadataOnly(
                getContext(), "compatibility", "Metadata envelope test");
        JSONObject root = new JSONObject(draft.json);

        assertTrue(DiagnosticReportContract.validDraftJson(draft.json));
        assertEquals(1, root.getInt("envelopeVersion"));
        assertEquals("zalo_patch", root.getString("product"));
        assertEquals(1, root.getInt("productReportVersion"));
        assertEquals("compatibility", root.getString("category"));
        assertTrue(BuildConfig.DIAGNOSTIC_INTAKE_URL.startsWith("https://"));
        assertTrue(BuildConfig.DIAGNOSTIC_INTAKE_URL.endsWith("/v1/reports"));

        JSONObject product = root.getJSONObject("productMetadata");
        JSONObject remap = product.getJSONObject("remapEvidence");
        assertFalse(remap.getBoolean("requestedForReport"));
        assertTrue(remap.has("exactBundledProfileMapped"));
        assertTrue(remap.has("installedZaloVersionCode"));
        assertTrue(remap.has("artifact"));
        assertTrue(remap.has("candidates"));
        assertTrue(remap.has("stableSurfaceMetadata"));
        assertTrue(remap.has("structuredEvidenceTruncated"));
        JSONObject setup = product.getJSONObject("setupChecks");
        assertTrue(setup.has("zaloPackagePresent"));
        assertTrue(setup.has("symbolSchemaValid"));
        assertTrue(setup.has("runtimeSelfCheckPresent"));
        assertTrue(setup.has("internetPermissionGranted"));
        assertEquals("not_checked", setup.getString("rootAccessStatus"));

        JSONObject raw = root.getJSONObject("rawDiagnostics");
        assertEquals("", raw.getString("diagnosticEventsAndLogs"));
        assertEquals("", raw.getString("crashExcerpt"));
        assertEquals("", raw.getString("lsposedModuleLines"));
        assertNotNull(raw.getJSONObject("runtimeSettings"));

        JSONArray selfCheck = product.getJSONArray("selfCheckRows");
        for (int index = 0; index < selfCheck.length(); index++) {
            JSONObject row = selfCheck.getJSONObject(index);
            assertFalse(row.has("detail"));
            assertFalse(row.has("error"));
            assertFalse(row.has("uid"));
            assertFalse(row.has("title"));
        }
        assertNotNull(product.getJSONArray("runtimeStatusTrace"));

        String encoded = draft.json.toLowerCase(java.util.Locale.US);
        assertFalse(encoded.contains("notificationhistory"));
        assertFalse(encoded.contains("recallmessages"));
        assertFalse(encoded.contains("notificationrules"));
        assertFalse(encoded.contains("messagebody"));
        assertFalse(encoded.contains("callrecordings"));
    }

    public void testRuntimeStatusTracePersistsSanitizedFailureMetadata() throws Exception {
        String previous = RuntimeStatusTraceStore.raw(getContext());
        try {
            RuntimeStatusTraceStore.restore(getContext(), "[]");
            android.content.ContentValues values = new android.content.ContentValues();
            values.put("status", "failed");
            values.put("target", "NotificationManager#notifyAsUser");
            values.put("error", "IllegalArgumentException: Unknown authority com.ez.zalopatch.config");
            values.put("updated_at", 123L);
            values.put("artifact_generation", "artifact");
            values.put("run_id", "run");
            RuntimeStatusTraceStore.record(getContext(), "notifications.history", values);

            java.util.List<JSONObject> events = RuntimeStatusTraceStore.load(getContext());
            assertEquals(1, events.size());
            JSONObject event = events.get(0);
            assertEquals("notifications.history", event.getString("feature"));
            assertEquals("IllegalArgumentException", event.getString("errorType"));
            assertEquals("unknown_authority", event.getString("errorCode"));
            assertFalse(event.toString().contains("com.ez.zalopatch.config"));
        } finally {
            RuntimeStatusTraceStore.restore(getContext(), previous);
        }
    }

    public void testRuntimeDiscoveryEvidenceStorePersistsStructuredMetadata() {
        RemapEvidenceStore.clear(getContext());
        try {
            assertTrue(RemapEvidenceStore.record(getContext(), 260801903L,
                    "candidate", "CANDIDATE inbox_adapter=of1.h1"));
            assertTrue(RemapEvidenceStore.record(getContext(), 260801903L,
                    "surface", "VIEW com.zing.zalo.ui.maintab.msg.MessagesView"));

            RemapEvidenceStore.Snapshot snapshot = RemapEvidenceStore.load(getContext());
            assertEquals(260801903L, snapshot.versionCode);
            assertTrue(snapshot.candidates.contains("CANDIDATE inbox_adapter=of1.h1"));
            assertTrue(snapshot.surfaces.contains(
                    "VIEW com.zing.zalo.ui.maintab.msg.MessagesView"));
        } finally {
            RemapEvidenceStore.clear(getContext());
        }
    }

    public void testRuntimeDiscoveryReceiverFallbackPersistsEvidenceAndCompletion() {
        RemapEvidenceStore.clear(getContext());
        long previousCompleted = DiagnosticsState.runtimeDiscoveryLastVersionCode(getContext());
        try {
            android.content.Intent evidence = new android.content.Intent(
                    SelfCheckReceiver.ACTION_RECORD_RUNTIME_DISCOVERY_EVIDENCE)
                    .putExtra("version_code", 260801903L)
                    .putExtra("kind", "candidate")
                    .putExtra("value", "CANDIDATE inbox_adapter=of1.h1");
            SelfCheckReceiver.handleAllowed(getContext(), evidence);

            RemapEvidenceStore.Snapshot snapshot = RemapEvidenceStore.load(getContext());
            assertEquals(260801903L, snapshot.versionCode);
            assertTrue(snapshot.candidates.contains("CANDIDATE inbox_adapter=of1.h1"));

            android.content.Intent completion = new android.content.Intent(
                    SelfCheckReceiver.ACTION_COMPLETE_RUNTIME_DISCOVERY)
                    .putExtra("version_code", 260801903L);
            SelfCheckReceiver.handleAllowed(getContext(), completion);
            assertEquals(260801903L,
                    DiagnosticsState.runtimeDiscoveryLastVersionCode(getContext()));
        } finally {
            RemapEvidenceStore.clear(getContext());
            TweakStore.preferences(getContext()).edit()
                    .putLong(DiagnosticsState.KEY_RUNTIME_DISCOVERY_LAST_VERSION_CODE,
                            previousCompleted)
                    .commit();
            TweakStore.initialize(getContext());
        }
    }
}
