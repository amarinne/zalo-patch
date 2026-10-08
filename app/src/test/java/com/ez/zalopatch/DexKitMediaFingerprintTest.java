package com.ez.zalopatch;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests for the chat big-file expiry state fingerprint. */
public final class DexKitMediaFingerprintTest {
    private static DexKitMediaFingerprint.Candidate candidate(String cls, String... states) {
        return new DexKitMediaFingerprint.Candidate(cls, Arrays.asList(states));
    }

    private static DexKitMediaFingerprint.ClassifierCandidate classifier(
            String owner, String method, String... states) {
        return new DexKitMediaFingerprint.ClassifierCandidate(owner, method, Arrays.asList(states));
    }

    @Test
    public void resolvesTheUniqueStateEnum() {
        List<DexKitMediaFingerprint.Candidate> candidates = Arrays.asList(
                candidate("ab1.c", "BIG_FILE_EXPIRED", "BIG_FILE_NOT_EXPIRED", "OTHER"),
                candidate("zz0.a", "SOMETHING_ELSE"));
        DexKitMediaFingerprint.Resolution resolution =
                DexKitMediaFingerprint.evaluate(candidates);
        assertTrue(resolution.resolved());
        assertEquals("ab1.c", resolution.className);
    }

    @Test
    public void ambiguousStateEnumsStayUnavailable() {
        List<DexKitMediaFingerprint.Candidate> candidates = Arrays.asList(
                candidate("ab1.c", "BIG_FILE_EXPIRED", "BIG_FILE_NOT_EXPIRED"),
                candidate("xy2.d", "BIG_FILE_EXPIRED", "BIG_FILE_NOT_EXPIRED"));
        DexKitMediaFingerprint.Resolution resolution =
                DexKitMediaFingerprint.evaluate(candidates);
        assertFalse(resolution.resolved());
        assertEquals("ambiguous_state_enum", resolution.status);
    }

    @Test
    public void enumsWithoutBothStatesStayUnavailable() {
        assertFalse(DexKitMediaFingerprint.evaluate(
                Arrays.asList(candidate("ab1.c", "BIG_FILE_EXPIRED"))).resolved());
        assertEquals("no_expiry_states",
                DexKitMediaFingerprint.evaluate(
                        Arrays.asList(candidate("ab1.c", "BIG_FILE_EXPIRED"))).status);
        assertFalse(DexKitMediaFingerprint.evaluate(null).resolved());
        assertFalse(DexKitMediaFingerprint.evaluate(new ArrayList<>()).resolved());
    }

    @Test
    public void resolvesTheUniqueStateClassifier() {
        List<DexKitMediaFingerprint.ClassifierCandidate> candidates = Arrays.asList(
                classifier("ab1.e", "f", "BIG_FILE_EXPIRED", "BIG_FILE_NOT_EXPIRED"),
                classifier("zz0.a", "g", "BIG_FILE_EXPIRED"));
        DexKitMediaFingerprint.ClassifierResolution resolution =
                DexKitMediaFingerprint.evaluateClassifier(candidates);
        assertTrue(resolution.resolved());
        assertEquals("ab1.e", resolution.ownerClass);
        assertEquals("f", resolution.methodName);
    }

    @Test
    public void ambiguousClassifiersStayUnavailable() {
        List<DexKitMediaFingerprint.ClassifierCandidate> candidates = Arrays.asList(
                classifier("ab1.e", "f", "BIG_FILE_EXPIRED", "BIG_FILE_NOT_EXPIRED"),
                classifier("xy2.g", "h", "BIG_FILE_EXPIRED", "BIG_FILE_NOT_EXPIRED"));
        assertFalse(DexKitMediaFingerprint.evaluateClassifier(candidates).resolved());
        assertEquals("ambiguous_state_classifier",
                DexKitMediaFingerprint.evaluateClassifier(candidates).status);
    }
}
