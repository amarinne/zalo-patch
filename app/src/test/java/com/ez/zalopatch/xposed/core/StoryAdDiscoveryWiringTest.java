package com.ez.zalopatch.xposed.core;

import com.ez.zalopatch.DexKitFamilyRetry;
import com.ez.zalopatch.DexKitStoryAdFingerprint;
import com.ez.zalopatch.DexKitZinstantFingerprint;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.*;

public final class StoryAdDiscoveryWiringTest {
    @Test
    @SuppressWarnings("unchecked")
    public void coldDiscoveryRunsTheSemanticQueryInItsOwnRetryFamily() throws Exception {
        Method specsMethod = DexKitFamilyResolver.class.getDeclaredMethod("familySpecs");
        specsMethod.setAccessible(true);
        List<DexKitBridgeRunner.MethodSpec> specs =
                (List<DexKitBridgeRunner.MethodSpec>) specsMethod.invoke(null);
        int found = 0;
        for (DexKitBridgeRunner.MethodSpec spec : specs) {
            if (!DexKitStoryAdFingerprint.QUERY_BIND.equals(spec.id)) continue;
            found++;
            assertEquals("void", spec.returnType);
            assertEquals(DexKitStoryAdFingerprint.MARKERS, spec.exactStrings);
            assertFalse(spec.withInvokes);
            assertEquals("story_ads", DexKitFamilyResolver.queryFamily(spec.id));
        }
        assertEquals(1, found);
        assertTrue(DexKitFamilyRetry.pending(null).contains("story_ads"));
        assertTrue(DexKitZinstantFingerprint.QUERY_REVISION > 19);
    }

    @Test
    public void missingStoryAdRoutePreventsCompleteExactShortCircuit() throws Exception {
        SymbolPreflight.Result checked = new SymbolPreflight.Result();
        for (java.lang.reflect.Field field : SymbolPreflight.Result.class.getDeclaredFields()) {
            if (field.getType() == boolean.class) field.setBoolean(checked, true);
        }
        for (com.ez.zalopatch.DexKitVideoAdsFingerprint.Route route
                : com.ez.zalopatch.DexKitVideoAdsFingerprint.Route.values()) {
            checked.videoAds.put(route, true);
        }
        checked.storyAds = false;
        String profile = com.ez.zalopatch.DexKitOverlay.buildProfile(1L,
                "a".repeat(64), "b".repeat(64), new org.json.JSONObject(), "static-verified").toString();
        Method select = com.ez.zalopatch.SymbolSchema.class.getDeclaredMethod(
                "select", String.class, String.class, long.class);
        select.setAccessible(true);
        com.ez.zalopatch.SymbolSchema.Active exact =
                (com.ez.zalopatch.SymbolSchema.Active) select.invoke(null, profile, "fixture", 1L);
        assertTrue(exact.valid);
        assertFalse(DexKitFamilyResolver.completeExactCoverage(exact, checked));
        checked.storyAds = true;
        assertTrue(DexKitFamilyResolver.completeExactCoverage(exact, checked));
    }
}
