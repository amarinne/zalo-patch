package com.ez.zalopatch;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM guards for Adtima query intersection, method shape, and uniqueness. */
public final class DexKitAdtimaFingerprintTest {
    private DexKitAdtimaFingerprint.MethodHit method(String owner, String name) {
        return new DexKitAdtimaFingerprint.MethodHit(owner, name, "boolean",
                Collections.<String>emptyList(), true);
    }

    private DexKitAdtimaFingerprint.AnchorResolution resolveWindow(
            DexKitAdtimaFingerprint.MethodHit... candidates) {
        return DexKitAdtimaFingerprint.resolveOfflineWindow(
                Arrays.asList(candidates), Arrays.asList(candidates),
                Arrays.asList(candidates), Arrays.asList(candidates), true);
    }

    @Test
    public void offlineWindowRequiresIntersectionOfAllFourQualifiedQueries() {
        DexKitAdtimaFingerprint.MethodHit valid = method("x.a", "b");
        assertTrue(resolveWindow(valid).resolved());

        DexKitAdtimaFingerprint.MethodHit differentMarkerHit = method("x.c", "d");
        DexKitAdtimaFingerprint.MethodHit noStaticShape = new DexKitAdtimaFingerprint.MethodHit(
                "x.e", "f", "boolean", Collections.<String>emptyList(), false);
        DexKitAdtimaFingerprint.MethodHit missingNoArg = new DexKitAdtimaFingerprint.MethodHit(
                "x.g", "h", "boolean", Collections.singletonList("int"), true);
        assertEquals("no_offline_window", DexKitAdtimaFingerprint.resolveOfflineWindow(
                Collections.singletonList(valid), Collections.singletonList(valid),
                Collections.singletonList(differentMarkerHit), Collections.singletonList(valid),
                true).status);
        assertEquals("no_offline_window", DexKitAdtimaFingerprint.resolveOfflineWindow(
                Collections.singletonList(noStaticShape), Collections.singletonList(valid),
                Collections.singletonList(valid), Collections.singletonList(valid), true).status);
        assertEquals("no_offline_window", DexKitAdtimaFingerprint.resolveOfflineWindow(
                Collections.singletonList(missingNoArg), Collections.singletonList(valid),
                Collections.singletonList(valid), Collections.singletonList(valid), true).status);
    }

    @Test
    public void offlineWindowRequiresCompleteAndUniqueQueryResults() {
        DexKitAdtimaFingerprint.MethodHit one = method("x.a", "b");
        DexKitAdtimaFingerprint.MethodHit two = method("x.c", "d");
        assertEquals("query_error", DexKitAdtimaFingerprint.resolveOfflineWindow(
                Collections.singletonList(one), Collections.singletonList(one),
                Collections.singletonList(one), Collections.singletonList(one), false).status);
        assertEquals("no_offline_window", DexKitAdtimaFingerprint.resolveOfflineWindow(
                Collections.<DexKitAdtimaFingerprint.MethodHit>emptyList(),
                Collections.<DexKitAdtimaFingerprint.MethodHit>emptyList(),
                Collections.<DexKitAdtimaFingerprint.MethodHit>emptyList(),
                Collections.<DexKitAdtimaFingerprint.MethodHit>emptyList(), true).status);
        assertEquals("ambiguous_offline_window", DexKitAdtimaFingerprint.resolveOfflineWindow(
                Arrays.asList(one, two), Arrays.asList(one, two), Arrays.asList(one, two),
                Arrays.asList(one, two), true).status);
        assertEquals("ambiguous_offline_window", DexKitAdtimaFingerprint.resolveOfflineWindow(
                Arrays.asList(one, one), Collections.singletonList(one),
                Collections.singletonList(one), Collections.singletonList(one), true).status);
    }

    @Test
    public void trackingGateRequiresExactCallerQueryAndDistinctStaticNoArgBoolean() {
        DexKitAdtimaFingerprint.MethodRef window = new DexKitAdtimaFingerprint.MethodRef(
                "x.a", "b");
        DexKitAdtimaFingerprint.MethodHit tracking = method("x.a", "c");
        assertTrue(DexKitAdtimaFingerprint.resolveTrackingGate(window,
                Collections.singletonList(tracking), true).resolved());

        DexKitAdtimaFingerprint.MethodHit sameMethod = method("x.a", "b");
        DexKitAdtimaFingerprint.MethodHit wrongShape = new DexKitAdtimaFingerprint.MethodHit(
                "x.d", "e", "boolean", Collections.singletonList("int"), true);
        DexKitAdtimaFingerprint.MethodHit instanceGate = new DexKitAdtimaFingerprint.MethodHit(
                "x.f", "g", "boolean", Collections.<String>emptyList(), false);
        assertEquals("no_tracking_gate", DexKitAdtimaFingerprint.resolveTrackingGate(window,
                Arrays.asList(sameMethod, wrongShape, instanceGate), true).status);
        assertEquals("query_error", DexKitAdtimaFingerprint.resolveTrackingGate(window,
                Collections.singletonList(tracking), false).status);
        assertEquals("ambiguous_tracking_gate", DexKitAdtimaFingerprint.resolveTrackingGate(window,
                Arrays.asList(tracking, method("x.h", "i")), true).status);
    }

    @Test
    public void familyDescriptorsAreAllOrNothingAndNeverCollapseDistinctGates() {
        DexKitAdtimaFingerprint.MethodHit windowHit = method("x.a", "b");
        DexKitAdtimaFingerprint.AnchorResolution window = resolveWindow(windowHit);
        DexKitAdtimaFingerprint.AnchorResolution tracking =
                DexKitAdtimaFingerprint.resolveTrackingGate(window.method,
                        Collections.singletonList(method("x.a", "c")), true);
        Map<String, String> found = DexKitAdtimaFingerprint.descriptors(window, tracking);
        assertEquals(4, found.size());
        assertEquals("x.a", found.get(DexKitAdtimaFingerprint.OFFLINE_WINDOW_CLASS));
        assertEquals("b", found.get(DexKitAdtimaFingerprint.OFFLINE_WINDOW_METHOD));
        assertEquals("x.a", found.get(DexKitAdtimaFingerprint.TRACKING_GATE_CLASS));
        assertEquals("c", found.get(DexKitAdtimaFingerprint.TRACKING_GATE_METHOD));

        DexKitAdtimaFingerprint.AnchorResolution same =
                DexKitAdtimaFingerprint.resolveTrackingGate(window.method,
                        Collections.singletonList(windowHit), true);
        assertTrue(DexKitAdtimaFingerprint.descriptors(window, same).isEmpty());
        assertFalse(DexKitAdtimaFingerprint.descriptors(window,
                new DexKitAdtimaFingerprint.AnchorResolution(null, "no_tracking_gate"))
                .containsKey(DexKitAdtimaFingerprint.OFFLINE_WINDOW_METHOD));
    }
}
