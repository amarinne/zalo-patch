package com.ez.zalopatch;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests for the Me TabMe-builder fingerprint. */
public final class DexKitMeFingerprintTest {
    @Test
    public void uniqueBuilderResolves() {
        List<DexKitMeFingerprint.MethodHit> hits = Arrays.asList(
                hit("onCreate", "void", 1),
                hit("z6", "java.util.ArrayList", 1),
                hit("setTitle", "void", 1));
        DexKitMeFingerprint.Resolution resolution = DexKitMeFingerprint.evaluate(hits);
        assertTrue(resolution.resolved());
        assertEquals("z6", resolution.symbol);
    }

    @Test
    public void listReturnAlsoQualifies() {
        List<DexKitMeFingerprint.MethodHit> hits = new ArrayList<>();
        hits.add(hit("z6", "java.util.List", 1));
        DexKitMeFingerprint.Resolution resolution = DexKitMeFingerprint.evaluate(hits);
        assertTrue(resolution.resolved());
        assertEquals("z6", resolution.symbol);
    }

    @Test
    public void sameNameOverloadsAreOneCandidate() {
        List<DexKitMeFingerprint.MethodHit> hits = Arrays.asList(
                hit("z6", "java.util.ArrayList", 1),
                new DexKitMeFingerprint.MethodHit(
                        DexKitMeFingerprint.OWNER_TAB_ME, "z6", "void", 0));
        DexKitMeFingerprint.Resolution resolution = DexKitMeFingerprint.evaluate(hits);
        assertTrue(resolution.resolved());
    }

    @Test
    public void ambiguousBuildersStayUnavailable() {
        List<DexKitMeFingerprint.MethodHit> hits = Arrays.asList(
                hit("z6", "java.util.ArrayList", 1),
                hit("y6", "java.util.List", 1));
        DexKitMeFingerprint.Resolution resolution = DexKitMeFingerprint.evaluate(hits);
        assertFalse(resolution.resolved());
        assertEquals("ambiguous", resolution.status);
    }

    @Test
    public void noBuilderStaysUnavailable() {
        List<DexKitMeFingerprint.MethodHit> hits = Arrays.asList(
                hit("onCreate", "void", 1));
        DexKitMeFingerprint.Resolution resolution = DexKitMeFingerprint.evaluate(hits);
        assertFalse(resolution.resolved());
        assertEquals("no_candidates", resolution.status);
        assertFalse(DexKitMeFingerprint.evaluate(null).resolved());
    }

    @Test
    public void foreignOwnerIgnored() {
        List<DexKitMeFingerprint.MethodHit> hits = Arrays.asList(
                hit("z6", "java.util.ArrayList", 1),
                new DexKitMeFingerprint.MethodHit(
                        "com.other.View", "z6", "java.util.ArrayList", 1));
        DexKitMeFingerprint.Resolution resolution = DexKitMeFingerprint.evaluate(hits);
        assertTrue(resolution.resolved());
        assertEquals("z6", resolution.symbol);
    }

    private DexKitMeFingerprint.MethodHit hit(String name, String returns, int params) {
        return new DexKitMeFingerprint.MethodHit(
                DexKitMeFingerprint.OWNER_TAB_ME, name, returns, params);
    }
}
