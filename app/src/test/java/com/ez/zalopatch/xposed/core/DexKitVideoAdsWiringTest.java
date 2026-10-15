package com.ez.zalopatch.xposed.core;

import com.ez.zalopatch.DexKitVideoAdsFingerprint;
import com.ez.zalopatch.DexKitOverlay;
import com.ez.zalopatch.SymbolSchema;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.EnumSet;

import static org.junit.Assert.*;

public final class DexKitVideoAdsWiringTest {
    @Test public void coldDiscoveryQueriesEveryIndependentRoute() {
        EnumSet<DexKitVideoAdsFingerprint.Route> seen = EnumSet.noneOf(DexKitVideoAdsFingerprint.Route.class);
        for (DexKitBridgeRunner.MethodSpec spec : DexKitFamilyResolver.familySpecs()) {
            for (DexKitVideoAdsFingerprint.Route route : DexKitVideoAdsFingerprint.Route.values()) {
                if (!spec.id.equals(route.query())) continue;
                assertTrue(seen.add(route));
                assertEquals(route.family(), DexKitFamilyResolver.queryFamily(spec.id));
                assertEquals(route.returnType, spec.returnType);
                assertEquals(Integer.valueOf(route.parameters.size()), spec.paramCount);
                assertEquals(route.semanticMethod, spec.methodName);
                assertEquals(DexKitVideoAdsFingerprint.strings(route), spec.exactStrings);
            }
        }
        assertEquals(EnumSet.allOf(DexKitVideoAdsFingerprint.Route.class), seen);
    }

    @Test public void partialExactProfileLeavesNewRoutesEligibleForDiscovery() throws Exception {
        String digest = "0000000000000000000000000000000000000000000000000000000000000000";
        SymbolSchema.Active exact = SymbolSchema.dexkitOverlayForHooks(
                DexKitOverlay.buildProfile(1, digest, digest, new JSONObject(), "static-verified"), 1);
        assertNotNull(exact);
        SymbolPreflight.Result checked = new SymbolPreflight.Result();
        for (Field field : SymbolPreflight.Result.class.getDeclaredFields()) {
            if (field.getType() == boolean.class) field.setBoolean(checked, true);
        }
        assertFalse(DexKitFamilyResolver.completeExactCoverage(exact, checked));
        for (DexKitVideoAdsFingerprint.Route route : DexKitVideoAdsFingerprint.Route.values()) {
            checked.videoAds.put(route, true);
        }
        assertTrue(DexKitFamilyResolver.completeExactCoverage(exact, checked));
        checked.videoAds.put(DexKitVideoAdsFingerprint.Route.NATIVE, false);
        assertFalse(DexKitFamilyResolver.completeExactCoverage(exact, checked));
    }
}
