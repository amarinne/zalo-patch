package com.ez.zalopatch;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests for the deleted-group store fingerprint. */
public final class DexKitDeletedGroupFingerprintTest {
    private static DexKitDeletedGroupFingerprint.Candidate candidate(
            String cls, String field, String check) {
        return new DexKitDeletedGroupFingerprint.Candidate(cls, field, check);
    }

    @Test
    public void resolvesTheSingletonWithMembershipCheck() {
        List<DexKitDeletedGroupFingerprint.Candidate> candidates = Arrays.asList(
                candidate("eb0.c", "a", "b"),
                candidate("bk0.q", "", ""));
        DexKitDeletedGroupFingerprint.Resolution resolution =
                DexKitDeletedGroupFingerprint.evaluate(candidates);
        assertTrue(resolution.resolved());
        assertEquals("eb0.c", resolution.className);
        assertEquals("a", resolution.singletonField);
        assertEquals("b", resolution.checkMethod);
    }

    @Test
    public void ambiguousStoresStayUnavailable() {
        List<DexKitDeletedGroupFingerprint.Candidate> candidates = Arrays.asList(
                candidate("eb0.c", "a", "b"),
                candidate("zz0.d", "a", "b"));
        DexKitDeletedGroupFingerprint.Resolution resolution =
                DexKitDeletedGroupFingerprint.evaluate(candidates);
        assertFalse(resolution.resolved());
        assertEquals("ambiguous_membership_store", resolution.status);
    }

    @Test
    public void tableUsersWithoutMembershipStayUnavailable() {
        List<DexKitDeletedGroupFingerprint.Candidate> candidates = Arrays.asList(
                candidate("bk0.q", "", ""));
        DexKitDeletedGroupFingerprint.Resolution resolution =
                DexKitDeletedGroupFingerprint.evaluate(candidates);
        assertFalse(resolution.resolved());
        assertEquals("no_membership_store", resolution.status);
        assertFalse(DexKitDeletedGroupFingerprint.evaluate(null).resolved());
        assertFalse(DexKitDeletedGroupFingerprint.evaluate(new ArrayList<>()).resolved());
    }
}
