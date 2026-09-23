package com.ez.zalopatch;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests for the ZRTC call callback fingerprint. */
public final class DexKitCallFingerprintTest {
    private static DexKitCallFingerprint.Candidate candidate(String cls, String... callbacks) {
        return new DexKitCallFingerprint.Candidate(cls, Arrays.asList(callbacks));
    }

    @Test
    public void resolvesTheUniqueOverridingSubclass() {
        List<DexKitCallFingerprint.Candidate> candidates = Arrays.asList(
                candidate("lb2.q1", "onCallAudioState", "onCallState"),
                candidate("zz0.a"));
        DexKitCallFingerprint.Resolution resolution =
                DexKitCallFingerprint.evaluate(candidates);
        assertTrue(resolution.resolved());
        assertEquals("lb2.q1", resolution.className);
    }

    @Test
    public void ambiguousImplementationsStayUnavailable() {
        List<DexKitCallFingerprint.Candidate> candidates = Arrays.asList(
                candidate("lb2.q1", "onCallAudioState"),
                candidate("zz0.a", "onCallState"));
        DexKitCallFingerprint.Resolution resolution =
                DexKitCallFingerprint.evaluate(candidates);
        assertFalse(resolution.resolved());
        assertEquals("ambiguous_callback_subclass", resolution.status);
    }

    @Test
    public void subclassesWithoutObservedCallbacksStayUnavailable() {
        assertFalse(DexKitCallFingerprint.evaluate(
                Arrays.asList(candidate("zz0.a"))).resolved());
        assertEquals("no_overriding_subclass",
                DexKitCallFingerprint.evaluate(Arrays.asList(candidate("zz0.a"))).status);
        assertFalse(DexKitCallFingerprint.evaluate(null).resolved());
        assertFalse(DexKitCallFingerprint.evaluate(new ArrayList<>()).resolved());
    }
}
