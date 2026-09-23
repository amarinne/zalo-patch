package com.ez.zalopatch;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public final class DexKitFamilyRetryTest {
    private static final String ME = "symbols.me.current_builder_method";
    private static final String INBOX = "symbols.inbox.category_int_field";

    private DexKitCache.Entry entry(Map<String, String> descriptors,
                                    Map<String, DexKitFamilyRetry.State> states) {
        return new DexKitCache.Entry(260901903L, "a".repeat(64), 1, 100,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, 254,
                "", "", true, "no_match", 0, 0, 10, 2, 0, descriptors, states);
    }

    private Map<String, DexKitFamilyRetry.State> settled() {
        Map<String, DexKitFamilyRetry.State> states = new LinkedHashMap<>();
        for (String family : DexKitFamilyRetry.FAMILIES) {
            states.put(family, new DexKitFamilyRetry.State("no_match", 1));
        }
        return states;
    }

    @Test public void serializedOutcomesKeepOnlyTransientFamiliesPending() throws Exception {
        Map<String, DexKitFamilyRetry.State> states = settled();
        states.put("me", new DexKitFamilyRetry.State("resolved", 1));
        states.put("inbox", new DexKitFamilyRetry.State("ambiguous", 1));
        states.put("webview", new DexKitFamilyRetry.State("query_error", 1));
        states.put("chat", new DexKitFamilyRetry.State("preflight_rejected", 1));
        String json = DexKitCache.serialize(entry(Collections.singletonMap(ME, "a"), states));
        DexKitCache.Entry read = DexKitCache.parse(json);
        assertEquals(json, DexKitCache.serialize(read));
        assertEquals(2, DexKitFamilyRetry.pending(read).size());
        assertTrue(DexKitFamilyRetry.pending(read).contains("webview"));
        assertTrue(DexKitFamilyRetry.pending(read).contains("chat"));
        assertEquals("a", read.extended.get(ME));
        assertEquals("ambiguous", read.families.get("inbox").status);
    }

    @Test public void transientBudgetSurvivesRestartsAndStopsAfterThreeScans() throws Exception {
        Map<String, DexKitFamilyRetry.State> states = settled();
        DexKitFamilyRetry.State state = null;
        for (int attempt = 1; attempt <= DexKitFamilyRetry.MAX_ATTEMPTS; attempt++) {
            state = DexKitFamilyRetry.scanned(state, "query_error");
            states.put("me", state);
            DexKitCache.Entry reloaded = DexKitCache.parse(DexKitCache.serialize(entry(null, states)));
            assertEquals(attempt < DexKitFamilyRetry.MAX_ATTEMPTS,
                    DexKitFamilyRetry.pending(reloaded).contains("me"));
            state = reloaded.families.get("me");
        }
    }

    @Test public void warmRejectionRemovesOnlyRejectedDescriptorsWithoutResettingBudget()
            throws Exception {
        Map<String, String> descriptors = new LinkedHashMap<>();
        descriptors.put(ME, "a");
        descriptors.put(INBOX, "f");
        Map<String, DexKitFamilyRetry.State> states = settled();
        states.put("me", new DexKitFamilyRetry.State("resolved", 1));
        states.put("inbox", new DexKitFamilyRetry.State("resolved", 2));
        DexKitCache.Entry checked = DexKitFamilyRetry.preflight(entry(descriptors, states),
                Collections.singletonMap(ME, "a"));
        DexKitCache.Entry reloaded = DexKitCache.parse(DexKitCache.serialize(checked));
        assertEquals(Collections.singletonMap(ME, "a"), reloaded.extended);
        assertEquals("resolved", reloaded.families.get("me").status);
        assertEquals("preflight_rejected", reloaded.families.get("inbox").status);
        assertEquals(2, reloaded.families.get("inbox").attempts);
        assertEquals(Collections.singleton("inbox"), DexKitFamilyRetry.pending(reloaded));
        states = new LinkedHashMap<>(reloaded.families);
        states.put("inbox", DexKitFamilyRetry.scanned(states.get("inbox"), "preflight_rejected"));
        assertTrue(DexKitFamilyRetry.pending(DexKitFamilyRetry.withStates(reloaded, states)).isEmpty());
    }

    @Test public void retryErrorPreservesHealthyDescriptorsAndRecoveryReplacesOnlyItsFamily() {
        Map<String, String> descriptors = new LinkedHashMap<>();
        descriptors.put(ME, "a");
        descriptors.put(INBOX, "f");
        DexKitFamilyRetry.mergeScan(descriptors, Collections.emptyMap(), "inbox", "query_error");
        assertEquals("f", descriptors.get(INBOX));
        DexKitFamilyRetry.mergeScan(descriptors, Collections.singletonMap(INBOX, "g"),
                "inbox", "resolved");
        assertEquals("g", descriptors.get(INBOX));
        assertEquals("a", descriptors.get(ME));
        DexKitFamilyRetry.mergeScan(descriptors, Collections.emptyMap(), "inbox", "ambiguous");
        assertFalse(descriptors.containsKey(INBOX));
        assertEquals("a", descriptors.get(ME));
    }

    @Test public void diagnosticClassificationSeparatesMissAmbiguityAndQueryErrors() {
        assertEquals("no_match", DexKitFamilyRetry.status("me", Collections.emptyMap(),
                Collections.singletonMap("symbols.me.builder", "no_candidates (3 one-param methods)")));
        assertEquals("ambiguous", DexKitFamilyRetry.status("inbox", Collections.emptyMap(),
                Collections.singletonMap("symbols.inbox.category", "ambiguous_category_field")));
        assertEquals("query_error", DexKitFamilyRetry.status("me", Collections.emptyMap(),
                Collections.singletonMap("symbols.me.builder", "IllegalStateException")));
        assertEquals("query_error", DexKitFamilyRetry.status("me", Collections.singletonMap(ME, "a"),
                Collections.singletonMap("symbols.me.query", "query_error")));
        assertEquals("no_match", DexKitFamilyRetry.status("bottom_tabs", Collections.emptyMap(),
                Collections.singletonMap("symbols.bottom_tabs.state", "index_mapping_failed")));
    }

    @Test public void partialClassDumpFailureIsRetryableEvenWhenShapeSelectionFails() {
        Map<String, String> diagnostics = new LinkedHashMap<>();
        diagnostics.put("symbols.bottom_tabs.state", "ambiguous_or_no_shape_match");
        diagnostics.put("symbols.bottom_tabs.dump_errors", "1");
        assertEquals("query_error", DexKitFamilyRetry.status("bottom_tabs",
                Collections.emptyMap(), diagnostics));
    }

    @Test public void unknownOrInvalidPersistedRecoveryStateIsRejected() throws Exception {
        String json = DexKitCache.serialize(entry(null, settled()));
        for (String broken : new String[] {
                json.replace("\"attempts\":1", "\"attempts\":-1"),
                json.replace("\"attempts\":1", "\"attempts\":4"),
                json.replace("\"status\":\"no_match\"", "\"status\":\"oops\""),
                json.replace("\"webview\":{", "\"unknown\":{")}) {
            try {
                DexKitCache.parse(broken);
                fail("invalid recovery state accepted");
            } catch (org.json.JSONException expected) { }
        }
    }

    @Test public void pilotQueryFailureKeepsHealthyAnchorButSemanticMissDoesNot() {
        DexKitCache.Entry old = new DexKitCache.Entry(260901903L, "a".repeat(64), 1, 100,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, 254,
                "ad", "feed", false, "", 1, 1, 10, 2, 0, null, settled());
        DexKitPilotPolicy.ScanInputs inputs = new DexKitPilotPolicy.ScanInputs();
        inputs.adQueryError = "bridge_failed";
        inputs.feedStatus = "resolved";
        inputs.feedSymbol = "newFeed";
        DexKitCache.Entry recovered = DexKitFamilyRetry.pilotResult(inputs, old);
        assertEquals("ad", recovered.adBind);
        assertEquals("newFeed", recovered.feedBind);
        inputs = new DexKitPilotPolicy.ScanInputs();
        inputs.adStatus = "no_candidates";
        inputs.feedQueryError = "bridge_failed";
        recovered = DexKitFamilyRetry.pilotResult(inputs, old);
        assertEquals("", recovered.adBind);
        assertEquals("feed", recovered.feedBind);
        inputs = new DexKitPilotPolicy.ScanInputs();
        inputs.adQueryError = "bridge_failed";
        inputs.feedQueryError = "bridge_failed";
        recovered = DexKitFamilyRetry.pilotResult(inputs, null);
        assertTrue(recovered.negative);
        assertEquals("", recovered.adBind);
        assertEquals("", recovered.feedBind);
    }

    @Test public void descriptorReplacementPreservesRecoveryState() {
        Map<String, DexKitFamilyRetry.State> states = settled();
        states.put("me", new DexKitFamilyRetry.State("query_error", 2));
        DexKitCache.Entry replaced = DexKitCache.replaceExtended(entry(null, states),
                Collections.singletonMap(ME, "a"));
        assertEquals("query_error", replaced.families.get("me").status);
        assertEquals(2, replaced.families.get("me").attempts);
    }
}
