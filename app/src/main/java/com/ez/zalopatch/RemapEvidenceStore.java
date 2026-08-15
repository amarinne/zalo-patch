package com.ez.zalopatch;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Bounded metadata-only runtime discovery evidence retained for diagnostic reporting. */
final class RemapEvidenceStore {
    private static final String PREFS = "remap_evidence_v1";
    private static final String KEY_VERSION = "version_code";
    private static final String KEY_CANDIDATES = "candidates";
    private static final String KEY_SURFACES = "surfaces";
    private static final int MAX_RECORDS = 64;
    private static final int MAX_RECORD_BYTES = 8 * 1024;

    private RemapEvidenceStore() {
    }

    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
    }

    static boolean record(Context context, long versionCode, String kind, String value) {
        if (context == null || versionCode <= 0L || value == null
                || !("candidate".equals(kind) || "surface".equals(kind))) {
            return false;
        }
        String bounded = DiagnosticReportContract.utf8Prefix(value.trim(), MAX_RECORD_BYTES);
        if (bounded.isEmpty()) return false;
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        synchronized (RemapEvidenceStore.class) {
            long storedVersion = preferences.getLong(KEY_VERSION, -1L);
            String key = "candidate".equals(kind) ? KEY_CANDIDATES : KEY_SURFACES;
            LinkedHashSet<String> records = storedVersion == versionCode
                    ? new LinkedHashSet<>(preferences.getStringSet(key, Collections.emptySet()))
                    : new LinkedHashSet<>();
            if (records.size() >= MAX_RECORDS && !records.contains(bounded)) return false;
            records.add(bounded);
            SharedPreferences.Editor editor = preferences.edit();
            if (storedVersion != versionCode) editor.clear();
            return editor.putLong(KEY_VERSION, versionCode)
                    .putStringSet(key, records)
                    .commit();
        }
    }

    static Snapshot load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Snapshot(
                preferences.getLong(KEY_VERSION, -1L),
                sorted(preferences.getStringSet(KEY_CANDIDATES, Collections.emptySet())),
                sorted(preferences.getStringSet(KEY_SURFACES, Collections.emptySet())));
    }

    private static List<String> sorted(Set<String> values) {
        ArrayList<String> result = new ArrayList<>(values);
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }

    static final class Snapshot {
        final long versionCode;
        final List<String> candidates;
        final List<String> surfaces;

        Snapshot(long versionCode, List<String> candidates, List<String> surfaces) {
            this.versionCode = versionCode;
            this.candidates = candidates;
            this.surfaces = surfaces;
        }
    }
}
