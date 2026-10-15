package com.ez.zalopatch.xposed.core;

import com.ez.zalopatch.DexKitStoryFingerprint;
import com.ez.zalopatch.DexKitFamilyRetry;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

public final class DexKitStoryResolutionTest {
    private static DexKitBridgeRunner.RawHit request(boolean suggested) {
        // External Java callees lack DEX-defined modifiers. Their library signature is stable.
        DexKitBridgeRunner.RawHit parse = new DexKitBridgeRunner.RawHit("java.lang.Long",
                "parseLong", "long", 1, Collections.singletonList("java.lang.String"), false);
        DexKitBridgeRunner.RawHit write = new DexKitBridgeRunner.RawHit("java.io.ByteArrayOutputStream",
                "write", "void", 1, Collections.singletonList("int"), false);
        return new DexKitBridgeRunner.RawHit("ap.b", suggested ? "v" : "r", "void",
                suggested ? 3 : 2,
                suggested ? Arrays.asList("int", "int", "java.lang.String")
                        : Arrays.asList("int", "java.lang.String"),
                false, Arrays.asList(parse, write));
    }

    private static Map<String, DexKitBridgeRunner.QueryResult> results(
            DexKitBridgeRunner.QueryResult seen, DexKitBridgeRunner.QueryResult suggested) {
        Map<String, DexKitBridgeRunner.QueryResult> results = new LinkedHashMap<>();
        results.put(DexKitStoryFingerprint.QUERY_SEEN, seen);
        results.put(DexKitStoryFingerprint.QUERY_SUGGESTED, suggested);
        return results;
    }

    private static DexKitBridgeRunner.QueryResult query(boolean suggested) {
        DexKitBridgeRunner.QueryResult query = new DexKitBridgeRunner.QueryResult(1, "");
        query.hits.add(request(suggested));
        return query;
    }

    @Test public void coldDiscoverySchedulesBothQualifiedStoryRequests() throws Exception {
        Set<String> pending = DexKitFamilyRetry.pending(null);
        assertTrue(pending.contains("stories"));
        Method familySpecs = DexKitFamilyResolver.class.getDeclaredMethod("familySpecs");
        Method queryFamily = DexKitFamilyResolver.class.getDeclaredMethod("queryFamily", String.class);
        familySpecs.setAccessible(true);
        queryFamily.setAccessible(true);
        @SuppressWarnings("unchecked") List<DexKitBridgeRunner.MethodSpec> specs =
                (List<DexKitBridgeRunner.MethodSpec>) familySpecs.invoke(null);
        Map<String, DexKitBridgeRunner.QueryResult> coldResults = new LinkedHashMap<>();
        for (DexKitBridgeRunner.MethodSpec spec : specs) {
            String family = (String) queryFamily.invoke(null, spec.id);
            if (!pending.contains(family) || !"stories".equals(family)) continue;
            boolean suggested = DexKitStoryFingerprint.QUERY_SUGGESTED.equals(spec.id);
            assertEquals("void", spec.returnType);
            assertEquals(Integer.valueOf(suggested ? 3 : 2), spec.paramCount);
            assertEquals(Integer.valueOf(suggested ? 1356 : 1343), spec.usingNumber);
            assertEquals("storyId", spec.exactString);
            assertTrue(spec.withDetails && spec.withInvokes);
            coldResults.put(spec.id, query(suggested));
        }
        assertEquals(2, coldResults.size());
        assertEquals(4, DexKitFamilyResolver.resolveStoryRequests(
                coldResults, new LinkedHashMap<>()).size());
    }

    @Test public void externalLibraryModifierFlagsDoNotRejectTheExactSerializer() {
        Map<String, String> diagnostics = new LinkedHashMap<>();
        Map<String, String> found = DexKitFamilyResolver.resolveStoryRequests(
                results(query(false), query(true)), diagnostics);
        assertEquals(4, found.size());
        assertEquals("ap.b", found.get(DexKitStoryFingerprint.ANCHOR_SEEN_CLASS));
        assertEquals("r", found.get(DexKitStoryFingerprint.ANCHOR_SEEN_METHOD));
        assertEquals("v", found.get(DexKitStoryFingerprint.ANCHOR_SUGGESTED_METHOD));
        assertEquals("resolved", diagnostics.get(DexKitStoryFingerprint.ANCHOR_SEEN_METHOD));
    }

    @Test public void suggestedQueryFailureKeepsOrdinaryRouteAndReportsTheError() {
        Map<String, String> diagnostics = new LinkedHashMap<>();
        Map<String, String> found = DexKitFamilyResolver.resolveStoryRequests(results(query(false),
                new DexKitBridgeRunner.QueryResult(0, "query failed")), diagnostics);
        assertEquals(2, found.size());
        assertEquals("r", found.get(DexKitStoryFingerprint.ANCHOR_SEEN_METHOD));
        assertFalse(found.containsKey(DexKitStoryFingerprint.ANCHOR_SUGGESTED_CLASS));
        assertEquals("query_error", diagnostics.get(DexKitStoryFingerprint.ANCHOR_SUGGESTED_METHOD));
    }

    @Test public void missingInvokeDetailsFailClosed() {
        DexKitBridgeRunner.QueryResult query = new DexKitBridgeRunner.QueryResult(1, "");
        query.hits.add(new DexKitBridgeRunner.RawHit("host.Request", "r", "void", 2,
                Arrays.asList("int", "java.lang.String"), false));
        Map<String, String> diagnostics = new LinkedHashMap<>();
        assertTrue(DexKitFamilyResolver.resolveStoryRequests(results(query,
                new DexKitBridgeRunner.QueryResult(0, "")), diagnostics).isEmpty());
        assertEquals("no_request", diagnostics.get(DexKitStoryFingerprint.ANCHOR_SEEN_METHOD));
    }

    @Test public void duplicateQualifyingRequestsLeaveOnlyTheOtherRoute() {
        DexKitBridgeRunner.QueryResult ambiguous = new DexKitBridgeRunner.QueryResult(2, "");
        ambiguous.hits.add(request(false));
        ambiguous.hits.add(request(false));
        Map<String, String> diagnostics = new LinkedHashMap<>();
        Map<String, String> found = DexKitFamilyResolver.resolveStoryRequests(
                results(ambiguous, query(true)), diagnostics);
        assertEquals(2, found.size());
        assertFalse(found.containsKey(DexKitStoryFingerprint.ANCHOR_SEEN_CLASS));
        assertEquals("ambiguous_request", diagnostics.get(DexKitStoryFingerprint.ANCHOR_SEEN_METHOD));
    }

    @Test public void truncatedQueryCannotEstablishUniqueness() {
        DexKitBridgeRunner.QueryResult truncated = new DexKitBridgeRunner.QueryResult(2, "");
        truncated.hits.add(request(false));
        Map<String, String> diagnostics = new LinkedHashMap<>();
        assertTrue(DexKitFamilyResolver.resolveStoryRequests(results(truncated, null), diagnostics).isEmpty());
        assertEquals("query_error", diagnostics.get(DexKitStoryFingerprint.ANCHOR_SEEN_METHOD));
    }
}
