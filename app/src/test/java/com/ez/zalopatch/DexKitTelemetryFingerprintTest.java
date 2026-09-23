package com.ez.zalopatch;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests for the telemetry DAO table-linkage fingerprint. */
public final class DexKitTelemetryFingerprintTest {
    @Test
    public void tableLinkageResolvesAllFourAccessors() {
        List<DexKitTelemetryFingerprint.AccessorHit> accessors = Arrays.asList(
                accessor("z", "bl.g"), accessor("A", "bl.l"),
                accessor("B", "bl.n"), accessor("C", "bl.p"));
        Map<String, Set<String>> users = tableUsers();
        Map<String, String> resolved =
                DexKitTelemetryFingerprint.evaluate(accessors, users);
        assertEquals(4, resolved.size());
        assertEquals("z",
                resolved.get(DexKitTelemetryFingerprint.ANCHOR_EVENT_ACCESSOR));
        assertEquals("A",
                resolved.get(DexKitTelemetryFingerprint.ANCHOR_SCREEN_ACCESSOR));
        assertEquals("B",
                resolved.get(DexKitTelemetryFingerprint.ANCHOR_SESSION_ACCESSOR));
        assertEquals("C",
                resolved.get(DexKitTelemetryFingerprint.ANCHOR_VIEW_ACCESSOR));
    }

    @Test
    public void descriptorReturnTypesNormalize() {
        List<DexKitTelemetryFingerprint.AccessorHit> accessors = Arrays.asList(
                accessor("z", "Lbl/g;"));
        Map<String, Set<String>> users = new HashMap<>();
        users.put("events", set("bl.g"));
        users.put("screens", set("bl.l"));
        users.put("sessions", set("bl.n"));
        users.put("views", set("bl.p"));
        Map<String, String> resolved =
                DexKitTelemetryFingerprint.evaluate(accessors, users);
        assertTrue(resolved.isEmpty());
    }

    @Test
    public void ambiguousTableUsersResolveNothing() {
        List<DexKitTelemetryFingerprint.AccessorHit> accessors = Arrays.asList(
                accessor("z", "bl.g"), accessor("A", "bl.g"));
        Map<String, Set<String>> users = tableUsers();
        Map<String, String> resolved =
                DexKitTelemetryFingerprint.evaluate(accessors, users);
        assertTrue(resolved.isEmpty());
    }

    @Test
    public void missingTableResolvesNothing() {
        List<DexKitTelemetryFingerprint.AccessorHit> accessors = Arrays.asList(
                accessor("z", "bl.g"));
        Map<String, Set<String>> users = new HashMap<>();
        users.put("events", set("bl.g"));
        Map<String, String> resolved =
                DexKitTelemetryFingerprint.evaluate(accessors, users);
        assertTrue(resolved.isEmpty());
    }

    @Test
    public void diagnoseReportsPerLabelStatus() {
        List<DexKitTelemetryFingerprint.AccessorHit> accessors = Arrays.asList(
                accessor("z", "bl.g"), accessor("A", "bl.l"),
                accessor("B", "bl.n"), accessor("C", "bl.p"));
        Map<String, String> diagnosis =
                DexKitTelemetryFingerprint.diagnose(accessors, tableUsers());
        assertEquals("resolved:com.zing.zalo.analytics.db.AnalyticsRoomDatabase_Impl#z",
                diagnosis.get("event"));
        assertEquals("resolved:com.zing.zalo.analytics.db.AnalyticsRoomDatabase_Impl#A",
                diagnosis.get("screen"));
        assertEquals("resolved:com.zing.zalo.analytics.db.AnalyticsRoomDatabase_Impl#B",
                diagnosis.get("session"));
        assertEquals("resolved:com.zing.zalo.analytics.db.AnalyticsRoomDatabase_Impl#C",
                diagnosis.get("view"));
    }

    @Test
    public void diagnoseReportsAmbiguousAndMissing() {
        List<DexKitTelemetryFingerprint.AccessorHit> accessors = Arrays.asList(
                accessor("z", "bl.g"), accessor("A", "bl.g"));
        Map<String, String> diagnosis =
                DexKitTelemetryFingerprint.diagnose(accessors, tableUsers());
        assertEquals("ambiguous", diagnosis.get("event"));
        Map<String, Set<String>> partial = new HashMap<>();
        partial.put("events", set("bl.g"));
        Map<String, String> missing =
                DexKitTelemetryFingerprint.diagnose(accessors, partial);
        assertEquals("missing_table", missing.get("screen"));
        assertEquals("missing_table", missing.get("session"));
        assertEquals("missing_table", missing.get("view"));
    }

    @Test
    public void diagnoseReportsDuplicateAccessor() {
        List<DexKitTelemetryFingerprint.AccessorHit> accessors = Arrays.asList(
                accessor("z", "bl.g"));
        Map<String, Set<String>> users = new HashMap<>();
        users.put("events", set("bl.g"));
        users.put("screens", set("bl.g"));
        users.put("sessions", set("bl.g"));
        users.put("views", set("bl.g"));
        Map<String, String> diagnosis =
                DexKitTelemetryFingerprint.diagnose(accessors, users);
        assertEquals("duplicate", diagnosis.get("event"));
        assertEquals("duplicate", diagnosis.get("view"));
        assertTrue(DexKitTelemetryFingerprint.evaluate(accessors, users).isEmpty());
    }

    @Test
    public void frameworkReturnTypesRejected() {        assertFalse(DexKitTelemetryFingerprint.isDaoReturnType("void"));
        assertFalse(DexKitTelemetryFingerprint.isDaoReturnType("int"));
        assertFalse(DexKitTelemetryFingerprint.isDaoReturnType("java.lang.String"));
        assertFalse(DexKitTelemetryFingerprint.isDaoReturnType("android.os.Bundle"));
        assertTrue(DexKitTelemetryFingerprint.isDaoReturnType("bl.g"));
        assertEquals("bl.g", DexKitTelemetryFingerprint.normalize("Lbl/g;"));
    }

    private DexKitTelemetryFingerprint.AccessorHit accessor(String name, String returnType) {
        return new DexKitTelemetryFingerprint.AccessorHit(
                DexKitTelemetryFingerprint.OWNER_DB_IMPL, name, returnType);
    }

    private Map<String, Set<String>> tableUsers() {
        Map<String, Set<String>> users = new HashMap<>();
        users.put("events", set("bl.g"));
        users.put("screens", set("bl.l"));
        users.put("sessions", set("bl.n"));
        users.put("views", set("bl.p"));
        return users;
    }

    private Set<String> set(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }
}
