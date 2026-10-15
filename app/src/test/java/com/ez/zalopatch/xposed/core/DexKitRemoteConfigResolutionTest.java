package com.ez.zalopatch.xposed.core;

import com.ez.zalopatch.DexKitRemoteConfigFingerprint;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public final class DexKitRemoteConfigResolutionTest {
    private static DexKitBridgeRunner.QueryResult query(int count) {
        DexKitBridgeRunner.QueryResult query = new DexKitBridgeRunner.QueryResult(count, "");
        DexKitBridgeRunner.RawHit reader = new DexKitBridgeRunner.RawHit("cfg.Reader", "read",
                "int", 2, Arrays.asList("java.lang.String", "int"), true);
        query.hits.add(new DexKitBridgeRunner.RawHit("cfg.KeySite", "load", "int", 0,
                Collections.emptyList(), true, Collections.singletonList(reader)));
        return query;
    }

    private static Map<String, DexKitBridgeRunner.QueryResult> queries(int count) {
        Map<String, DexKitBridgeRunner.QueryResult> queries = new LinkedHashMap<>();
        queries.put(DexKitRemoteConfigFingerprint.QUERY_STORY_CALLERS, query(count));
        queries.put(DexKitRemoteConfigFingerprint.QUERY_COMMUNITY_CALLERS, query(1));
        return queries;
    }

    @Test public void completeCallerQueriesResolveBothReaderAnchors() {
        Map<String, String> diagnostics = new LinkedHashMap<>();
        Map<String, String> anchors = DexKitFamilyResolver.resolveRemoteConfig(queries(1), diagnostics);
        assertEquals(2, anchors.size());
        assertEquals("cfg.Reader", anchors.get(DexKitRemoteConfigFingerprint.ANCHOR_READER_CLASS));
        assertEquals("read", anchors.get(DexKitRemoteConfigFingerprint.ANCHOR_READER_METHOD));
        assertEquals("resolved", diagnostics.get(DexKitRemoteConfigFingerprint.ANCHOR_READER_METHOD));
    }

    @Test public void truncatedCallerQueryCannotEstablishSharedReader() {
        Map<String, String> diagnostics = new LinkedHashMap<>();
        assertTrue(DexKitFamilyResolver.resolveRemoteConfig(queries(2), diagnostics).isEmpty());
        assertEquals("query_error", diagnostics.get(DexKitRemoteConfigFingerprint.ANCHOR_READER_METHOD));
    }
}
