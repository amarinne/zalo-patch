package com.ez.zalopatch;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests for exact key isolation and the shared remote-config reader fingerprint. */
public final class DexKitRemoteConfigFingerprintTest {
    @Test
    public void oneSharedExactReaderResolves() {
        DexKitRemoteConfigFingerprint.Callee reader = reader("cfg.Reader", "read");
        DexKitRemoteConfigFingerprint.Resolution resolution =
                DexKitRemoteConfigFingerprint.evaluate(
                        Collections.singletonList(caller(reader)),
                        Collections.singletonList(caller(reader)));
        assertEquals("resolved", resolution.status);
        assertEquals("cfg.Reader#read", resolution.symbol);
    }

    @Test
    public void repeatedCallsToTheSameReaderRemainUnique() {
        DexKitRemoteConfigFingerprint.Callee reader = reader("cfg.Reader", "read");
        DexKitRemoteConfigFingerprint.Resolution resolution =
                DexKitRemoteConfigFingerprint.evaluate(
                        Arrays.asList(caller(reader), caller(reader)),
                        Arrays.asList(caller(reader), caller(reader)));
        assertEquals("resolved", resolution.status);
    }

    @Test
    public void separateReadersDoNotResolveByUnion() {
        DexKitRemoteConfigFingerprint.Resolution resolution =
                DexKitRemoteConfigFingerprint.evaluate(
                        Collections.singletonList(caller(reader("story.Reader", "read"))),
                        Collections.singletonList(caller(reader("community.Reader", "read"))));
        assertEquals("no_shared_reader", resolution.status);
        assertEquals("", resolution.symbol);
    }

    @Test
    public void multipleSharedReadersAreAmbiguous() {
        DexKitRemoteConfigFingerprint.Callee first = reader("cfg.First", "read");
        DexKitRemoteConfigFingerprint.Callee second = reader("cfg.Second", "read");
        List<DexKitRemoteConfigFingerprint.CallerHit> both =
                Collections.singletonList(caller(first, second));
        DexKitRemoteConfigFingerprint.Resolution resolution =
                DexKitRemoteConfigFingerprint.evaluate(both, both);
        assertEquals("ambiguous_shared_readers", resolution.status);
        assertEquals("", resolution.symbol);
    }

    @Test
    public void readerMustBeSharedByEveryExactKeyCaller() {
        DexKitRemoteConfigFingerprint.Callee first = reader("cfg.First", "read");
        DexKitRemoteConfigFingerprint.Callee second = reader("cfg.Second", "read");
        DexKitRemoteConfigFingerprint.Resolution resolution =
                DexKitRemoteConfigFingerprint.evaluate(
                        Arrays.asList(caller(first), caller(second)),
                        Collections.singletonList(caller(first)));
        assertEquals("no_shared_reader", resolution.status);
        assertEquals("", resolution.symbol);
    }

    @Test
    public void aCallerWithoutReaderEvidenceCannotEstablishUniqueness() {
        DexKitRemoteConfigFingerprint.Callee reader = reader("cfg.Reader", "read");
        assertEquals("no_shared_reader", DexKitRemoteConfigFingerprint.evaluate(
                Arrays.asList(caller(reader), caller()),
                Collections.singletonList(caller(reader))).status);
    }

    @Test
    public void readerShapeMustBeStaticIntStringInt() {
        DexKitRemoteConfigFingerprint.Callee wrongReturn = new DexKitRemoteConfigFingerprint.Callee(
                "cfg.Reader", "wrongReturn", "long",
                Arrays.asList("java.lang.String", "int"), true);
        DexKitRemoteConfigFingerprint.Callee wrongParameters = new DexKitRemoteConfigFingerprint.Callee(
                "cfg.Reader", "wrongParameters", "int",
                Arrays.asList("int", "java.lang.String"), true);
        DexKitRemoteConfigFingerprint.Callee instance = new DexKitRemoteConfigFingerprint.Callee(
                "cfg.Reader", "instance", "int",
                Arrays.asList("java.lang.String", "int"), false);
        DexKitRemoteConfigFingerprint.Resolution resolution =
                DexKitRemoteConfigFingerprint.evaluate(
                        Collections.singletonList(caller(wrongReturn, wrongParameters, instance)),
                        Collections.singletonList(caller(wrongReturn, wrongParameters, instance)));
        assertEquals("no_shared_reader", resolution.status);
    }

    @Test
    public void runtimeGuardAllowsOnlyTheTwoExactKeys() {
        assertTrue(DexKitRemoteConfigFingerprint.isSponsoredPlacementKey(
                DexKitRemoteConfigFingerprint.STORY_KEY));
        assertTrue(DexKitRemoteConfigFingerprint.isSponsoredPlacementKey(
                DexKitRemoteConfigFingerprint.COMMUNITY_KEY));
        assertFalse(DexKitRemoteConfigFingerprint.isSponsoredPlacementKey(
                "community.community_ads.group_size"));
        assertFalse(DexKitRemoteConfigFingerprint.isSponsoredPlacementKey(
                "prefix." + DexKitRemoteConfigFingerprint.STORY_KEY));
        assertFalse(DexKitRemoteConfigFingerprint.isSponsoredPlacementKey(
                "COMMUNITY.community_ads.enable"));
        assertFalse(DexKitRemoteConfigFingerprint.isSponsoredPlacementKey(null));
    }

    @Test
    public void readerAnchorsUseTheExistingClassAndMethodVocabulary() {
        assertEquals(DexKitAnchors.Kind.CLASS,
                DexKitAnchors.find(DexKitRemoteConfigFingerprint.ANCHOR_READER_CLASS).kind);
        assertEquals(DexKitAnchors.Kind.METHOD,
                DexKitAnchors.find(DexKitRemoteConfigFingerprint.ANCHOR_READER_METHOD).kind);
        assertTrue(DexKitAnchors.validDescriptor(
                DexKitAnchors.find(DexKitRemoteConfigFingerprint.ANCHOR_READER_CLASS),
                "cfg.Reader"));
        assertTrue(DexKitAnchors.validDescriptor(
                DexKitAnchors.find(DexKitRemoteConfigFingerprint.ANCHOR_READER_METHOD),
                "read"));
    }

    private static DexKitRemoteConfigFingerprint.CallerHit caller(
            DexKitRemoteConfigFingerprint.Callee... callees) {
        return new DexKitRemoteConfigFingerprint.CallerHit(
                "cfg.KeySite", "load", Arrays.asList(callees));
    }

    private static DexKitRemoteConfigFingerprint.Callee reader(String owner, String method) {
        return new DexKitRemoteConfigFingerprint.Callee(owner, method, "int",
                Arrays.asList("java.lang.String", "int"), true);
    }
}
