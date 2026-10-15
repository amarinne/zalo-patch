package com.ez.zalopatch.xposed.core;

import com.ez.zalopatch.DexKitAdtimaFingerprint;
import com.ez.zalopatch.DexKitFamilyRetry;
import com.ez.zalopatch.DexKitOverlay;
import com.ez.zalopatch.SymbolSchema;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/** The first scan must execute every predicate needed to qualify the offline gate. */
public final class AdtimaDiscoveryWiringTest {
    @Test
    @SuppressWarnings("unchecked")
    public void coldScanIncludesEveryOfflinePredicateInOneRetryFamily() throws Exception {
        Method specsMethod = DexKitFamilyResolver.class.getDeclaredMethod("familySpecs");
        specsMethod.setAccessible(true);
        Map<String, DexKitBridgeRunner.MethodSpec> queries = new LinkedHashMap<>();
        for (DexKitBridgeRunner.MethodSpec spec :
                (List<DexKitBridgeRunner.MethodSpec>) specsMethod.invoke(null)) {
            if (spec.id.startsWith("adtima.")) {
                assertNull(queries.put(spec.id, spec));
                assertEquals("boolean", spec.returnType);
                assertEquals(Integer.valueOf(0), spec.paramCount);
                assertFalse(spec.withInvokes);
            }
        }
        assertEquals(4, queries.size());
        assertEquals("n", queries.get(DexKitAdtimaFingerprint.QUERY_OFFLINE_WINDOW).exactString);
        assertEquals(DexKitAdtimaFingerprint.ADTIMA_OWNER,
                queries.get(DexKitAdtimaFingerprint.QUERY_ADTIMA_CALL).invokeOwnerClass);
        assertEquals(DexKitAdtimaFingerprint.CURRENT_TIME_DESCRIPTOR,
                queries.get(DexKitAdtimaFingerprint.QUERY_CURRENT_TIME).invokeDescriptor);
        assertEquals(DexKitAdtimaFingerprint.LONG_VALUE_DESCRIPTOR,
                queries.get(DexKitAdtimaFingerprint.QUERY_LONG_VALUE).invokeDescriptor);
        Method family = DexKitFamilyResolver.class.getDeclaredMethod("queryFamily", String.class);
        family.setAccessible(true);
        for (String id : queries.keySet()) assertEquals("adtima", family.invoke(null, id));
        assertEquals("adtima", family.invoke(null, DexKitAdtimaFingerprint.QUERY_TRACKING_GATE));
        assertTrue(DexKitFamilyRetry.pending(null).contains("adtima"));
    }

    @Test
    public void partialExactProfileCannotSkipAdtimaDiscovery() throws Exception {
        SymbolPreflight.Result checked = new SymbolPreflight.Result();
        for (java.lang.reflect.Field field : SymbolPreflight.Result.class.getDeclaredFields()) {
            if (field.getType() == boolean.class) field.setBoolean(checked, true);
        }
        Method select = SymbolSchema.class.getDeclaredMethod(
                "select", String.class, String.class, long.class);
        select.setAccessible(true);
        SymbolSchema.Active exact = (SymbolSchema.Active) select.invoke(null,
                DexKitOverlay.buildProfile(1L, "a".repeat(64), "b".repeat(64),
                        new JSONObject(), "static-verified").toString(), "fixture", 1L);
        assertTrue(exact.valid);
        for (com.ez.zalopatch.DexKitVideoAdsFingerprint.Route route
                : com.ez.zalopatch.DexKitVideoAdsFingerprint.Route.values()) {
            checked.videoAds.put(route, true);
        }
        checked.adtima = false;
        assertFalse(DexKitFamilyResolver.completeExactCoverage(exact, checked));
        checked.adtima = true;
        assertTrue(DexKitFamilyResolver.completeExactCoverage(exact, checked));
    }
}
