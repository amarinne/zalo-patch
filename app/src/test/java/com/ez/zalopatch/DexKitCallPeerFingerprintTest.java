package com.ez.zalopatch;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests for the active-peer manager fingerprint. */
public final class DexKitCallPeerFingerprintTest {
    private static DexKitCallPeerFingerprint.Candidate candidate(
            String cls, String accessor, String container, String handle) {
        return new DexKitCallPeerFingerprint.Candidate(cls, accessor, container, handle);
    }

    @Test
    public void resolvesTheCompleteQuadruple() {
        List<DexKitCallPeerFingerprint.Candidate> candidates = Arrays.asList(
                candidate("lj.a", "a", "a", "e"),
                candidate("other.b", "", "", ""));
        DexKitCallPeerFingerprint.Resolution resolution =
                DexKitCallPeerFingerprint.evaluate(candidates);
        assertTrue(resolution.resolved());
        assertEquals("lj.a", resolution.className);
        assertEquals("a", resolution.accessor);
        assertEquals("a", resolution.containerField);
        assertEquals("e", resolution.handleField);
    }

    @Test
    public void incompleteShapesStayUnavailable() {
        List<DexKitCallPeerFingerprint.Candidate> candidates = Arrays.asList(
                candidate("lj.a", "a", "", "e"),
                candidate("other.b", "", "", ""));
        DexKitCallPeerFingerprint.Resolution resolution =
                DexKitCallPeerFingerprint.evaluate(candidates);
        assertFalse(resolution.resolved());
        assertEquals("no_peer_handle_shape", resolution.status);
    }

    @Test
    public void ambiguousManagersStayUnavailable() {
        List<DexKitCallPeerFingerprint.Candidate> candidates = Arrays.asList(
                candidate("lj.a", "a", "a", "e"),
                candidate("other.b", "c", "d", "f"));
        DexKitCallPeerFingerprint.Resolution resolution =
                DexKitCallPeerFingerprint.evaluate(candidates);
        assertFalse(resolution.resolved());
        assertEquals("ambiguous_peer_manager", resolution.status);
        assertFalse(DexKitCallPeerFingerprint.evaluate(null).resolved());
        assertFalse(DexKitCallPeerFingerprint.evaluate(new ArrayList<>()).resolved());
    }
}
