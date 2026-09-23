package com.ez.zalopatch;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests for the inbox category fingerprint. */
public final class DexKitInboxFingerprintTest {
    private static final String CONVERSATION = DexKitInboxFingerprint.CONVERSATION_CLASS;

    @Test
    public void categoryFieldResolvesThroughNumberLinkage() {
        List<DexKitInboxFingerprint.MethodHit> users = Arrays.asList(
                new DexKitInboxFingerprint.MethodHit("o00.a", "isOa",
                        Arrays.asList(CONVERSATION + "#f")));
        DexKitInboxFingerprint.Resolution resolution =
                DexKitInboxFingerprint.evaluate(users, Arrays.asList("e", "f", "g"));
        assertTrue(resolution.resolved());
        assertEquals("f", resolution.field);
    }

    @Test
    public void nonIntConversationFieldsIgnored() {
        List<DexKitInboxFingerprint.MethodHit> users = Arrays.asList(
                new DexKitInboxFingerprint.MethodHit("o00.a", "isOa",
                        Arrays.asList(CONVERSATION + "#c", CONVERSATION + "#f")));
        DexKitInboxFingerprint.Resolution resolution =
                DexKitInboxFingerprint.evaluate(users, Arrays.asList("e", "f", "g"));
        assertTrue(resolution.resolved());
        assertEquals("f", resolution.field);
    }

    @Test
    public void ambiguousCategoryStaysUnavailable() {
        List<DexKitInboxFingerprint.MethodHit> users = Arrays.asList(
                new DexKitInboxFingerprint.MethodHit("o00.a", "isOa",
                        Arrays.asList(CONVERSATION + "#e", CONVERSATION + "#g")));
        DexKitInboxFingerprint.Resolution resolution =
                DexKitInboxFingerprint.evaluate(users, Arrays.asList("e", "f", "g"));
        assertFalse(resolution.resolved());
        assertEquals("ambiguous_category_field", resolution.status);
    }

    @Test
    public void knownLetterDoesNotBreakTies() {
        // Ambiguity must fail closed: a historical obfuscated letter must not select a
        // role that a later release could have moved (Decision 18).
        List<DexKitInboxFingerprint.MethodHit> users = Arrays.asList(
                new DexKitInboxFingerprint.MethodHit("o00.a", "isOa",
                        Arrays.asList(CONVERSATION + "#e", CONVERSATION + "#f")));
        DexKitInboxFingerprint.Resolution resolution =
                DexKitInboxFingerprint.evaluate(users, Arrays.asList("e", "f", "g"));
        assertFalse(resolution.resolved());
        assertEquals("ambiguous_category_field", resolution.status);
    }

    @Test
    public void missingProfileLetterStaysUnavailable() {
        List<DexKitInboxFingerprint.MethodHit> users = Arrays.asList(
                new DexKitInboxFingerprint.MethodHit("o00.a", "isOa",
                        Arrays.asList(CONVERSATION + "#e", CONVERSATION + "#h")));
        DexKitInboxFingerprint.Resolution resolution =
                DexKitInboxFingerprint.evaluate(users, Arrays.asList("e", "g", "h"));
        assertFalse(resolution.resolved());
        assertEquals("ambiguous_category_field", resolution.status);
    }

    @Test
    public void missingCategoryStaysUnavailable() {
        List<DexKitInboxFingerprint.MethodHit> users = Arrays.asList(
                new DexKitInboxFingerprint.MethodHit("o00.a", "other",
                        Arrays.asList(CONVERSATION + "#c")));
        DexKitInboxFingerprint.Resolution resolution =
                DexKitInboxFingerprint.evaluate(users, Arrays.asList("e", "g", "h"));
        assertFalse(resolution.resolved());
        assertEquals("no_category_field", resolution.status);
        assertFalse(DexKitInboxFingerprint.evaluate(null, Arrays.asList("e")).resolved());
    }

    @Test
    public void fieldMatchesResolveUniqueField() {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        counts.put("f", 3);
        counts.put("h", 0);
        DexKitInboxFingerprint.Resolution resolution =
                DexKitInboxFingerprint.resolveFieldMatches(counts, Arrays.asList("f", "h"));
        assertTrue(resolution.resolved());
        assertEquals("f", resolution.field);
    }

    @Test
    public void fieldMatchesStayUnavailableOnMultipleMatches() {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        counts.put("f", 10);
        counts.put("h", 1);
        DexKitInboxFingerprint.Resolution resolution =
                DexKitInboxFingerprint.resolveFieldMatches(counts, Arrays.asList("f", "h"));
        assertFalse(resolution.resolved());
        assertEquals("ambiguous_category_field", resolution.status);
    }

    @Test
    public void fieldMatchesStayUnavailableWithoutEvidence() {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        counts.put("g", 1);
        counts.put("h", 1);
        DexKitInboxFingerprint.Resolution resolution =
                DexKitInboxFingerprint.resolveFieldMatches(counts, Arrays.asList("f", "g", "h"));
        assertFalse(resolution.resolved());
        assertEquals("ambiguous_category_field", resolution.status);
    }

    private static DexKitInboxFingerprint.RowMethodHit accessor(String name, String returnType,
                                                               List<String> usedFields) {
        return new DexKitInboxFingerprint.RowMethodHit(name, returnType, usedFields);
    }

    @Test
    public void uidFieldResolvesThroughRowAccessor() {
        List<DexKitInboxFingerprint.RowMethodHit> hits = Arrays.asList(
                accessor("e", "java.lang.String", Arrays.asList(CONVERSATION + "#b")),
                accessor("e", "java.lang.String", Arrays.asList(CONVERSATION + "#b")),
                accessor("h", "boolean", Arrays.asList(CONVERSATION + "#b")));
        DexKitInboxFingerprint.RowResolution resolution =
                DexKitInboxFingerprint.resolveUidField(hits, Arrays.asList("b", "c", "d"));
        assertTrue(resolution.resolved());
        assertEquals("e", resolution.uidMethod);
        assertEquals("b", resolution.uidField);
    }

    @Test
    public void uidFieldIgnoresSentinelAccessors() {
        // Box-row siblings return fixed literals and read no Conversation field.
        List<DexKitInboxFingerprint.RowMethodHit> hits = Arrays.asList(
                accessor("e", "java.lang.String", new ArrayList<String>()),
                accessor("e", "java.lang.String", Arrays.asList(CONVERSATION + "#c")),
                accessor("e", "java.lang.String", new ArrayList<String>()));
        DexKitInboxFingerprint.RowResolution resolution =
                DexKitInboxFingerprint.resolveUidField(hits, Arrays.asList("b", "c"));
        assertTrue(resolution.resolved());
        assertEquals("c", resolution.uidField);
    }

    @Test
    public void uidFieldStaysUnavailableOnDisagreeingAccessors() {
        List<DexKitInboxFingerprint.RowMethodHit> hits = Arrays.asList(
                accessor("e", "java.lang.String", Arrays.asList(CONVERSATION + "#b")),
                accessor("e2", "java.lang.String", Arrays.asList(CONVERSATION + "#c")));
        DexKitInboxFingerprint.RowResolution resolution =
                DexKitInboxFingerprint.resolveUidField(hits, Arrays.asList("b", "c"));
        assertFalse(resolution.resolved());
        assertEquals("ambiguous_uid_field", resolution.status);
    }

    @Test
    public void uidFieldStaysUnavailableWithoutConversationUse() {
        List<DexKitInboxFingerprint.RowMethodHit> hits = Arrays.asList(
                accessor("e", "java.lang.String", Arrays.asList("h20.d#g")),
                accessor("m", "boolean", Arrays.asList(CONVERSATION + "#b")));
        DexKitInboxFingerprint.RowResolution resolution =
                DexKitInboxFingerprint.resolveUidField(hits, Arrays.asList("b", "c"));
        assertFalse(resolution.resolved());
        assertEquals("no_uid_field", resolution.status);
        assertFalse(DexKitInboxFingerprint.resolveUidField(null, Arrays.asList("b")).resolved());
    }
}
