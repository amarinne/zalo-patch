package com.ez.zalopatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Module-process persistence for the DexKit pilot cache.
 *
 * <p>Descriptors live in an app-private file written through {@link AtomicFile}, never in
 * settings prefs (they are not user settings and must not leak into backups). The Zalo
 * process never touches this file directly: it reads through the {@code dexkit_cache}
 * provider query and writes through provider calls, so the module process serializes
 * concurrent process starts.
 *
 * <p>Threading: every file operation holds {@link #FILE_LOCK}, because provider queries
 * and calls run on concurrent Binder threads and {@code AtomicFile} alone cannot stop a
 * {@code clear()} racing a {@code save()} or a torn read. The byte-level file access is
 * behind {@link StoreIo} so the locking is JVM-tested with a fake.
 *
 * <p>Retry budget: failure counters are scoped to one host/code/query/module generation
 * ({@link DexKitPilotPolicy#budgetScope}). Three failures on an old build or query
 * revision never block a corrected module or a newly installed host; a scope change is a
 * fresh budget.
 */
final class DexKitStore {
    static final String FILE_NAME = "dexkit-zinstant-cache-v1.json";
    static final String KEY_SCAN_FAILURES = "dexkit.scan_failure_count";
    static final String KEY_SCAN_LAST_ATTEMPT_MS = "dexkit.scan_last_attempt_ms";
    static final String KEY_SCAN_SLOT_MS = "dexkit.scan_in_progress_ms";
    static final String KEY_SCAN_SCOPE = "dexkit.scan_scope";
    static final int MAX_FAILURES = 3;
    static final long FAILURE_BACKOFF_MS = 24L * 60L * 60L * 1000L;
    static final long SCAN_MARKER_TTL_MS = 5L * 60L * 1000L;
    static final Object FILE_LOCK = new Object();

    private DexKitStore() {
    }

    /** Byte-level file access, so locking is testable without Android. */
    interface StoreIo {
        byte[] read() throws Exception;

        void write(byte[] data) throws Exception;

        void delete() throws Exception;

        boolean exists();
    }

    /** Loads the cached entry, or null when absent, oversized, or unparsable. */
    static DexKitCache.Entry load(Context context) {
        if (context == null) {
            return null;
        }
        return loadLocked(storeIo(context));
    }

    static DexKitCache.Entry loadLocked(StoreIo io) {
        synchronized (FILE_LOCK) {
            return loadUnsynchronized(io);
        }
    }

    private static DexKitCache.Entry loadUnsynchronized(StoreIo io) {
        try {
            if (!io.exists()) {
                return null;
            }
            byte[] raw = io.read();
            if (raw.length > DexKitCache.MAX_ENTRY_BYTES) {
                return null;
            }
            return DexKitCache.parse(new String(raw, "UTF-8"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Validates and atomically stores an entry from the Zalo-process scan. Resets the
     * failure budget to the entry's scope on success. Returns false without touching the
     * previous cache on invalid input.
     */
    static boolean save(Context context, String json) {
        if (context == null || json == null) {
            return false;
        }
        return saveLocked(storeIo(context), context, json);
    }

    static boolean saveLocked(StoreIo io, Context context, String json) {
        synchronized (FILE_LOCK) {
            return saveUnsynchronized(io, context, json);
        }
    }

    /**
     * True when the entry describes the currently installed host. The digest is excluded:
     * recomputing it on every save would defeat the lightweight check, and version plus
     * update time plus file size already identify the installed file. A pathological
     * same-everything replacement stays an accepted residual risk (see DexKitCache.bindsTo).
     */
    static boolean hostMatches(DexKitCache.Entry entry, long versionCode, long lastUpdateTime,
                               long apkSize, int queryRevision, int resolverFormat,
                               int moduleVersion) {
        return entry != null
                && entry.versionCode == versionCode
                && entry.lastUpdateTime == lastUpdateTime
                && entry.apkSize == apkSize
                && entry.queryRevision == queryRevision
                && entry.resolverFormat == resolverFormat
                && entry.moduleVersion == moduleVersion;
    }

    private static boolean saveUnsynchronized(StoreIo io, Context context, String json) {
        try {
            DexKitCache.Entry entry = DexKitCache.parse(json);
            if (entry.versionCode <= 0L || entry.moduleVersion <= 0) {
                return false;
            }
            if (context != null && !hostMatchesCurrent(context, entry)) {
                releaseScanSlot(context);
                return false;
            }
            io.write(json.getBytes("UTF-8"));
            if (context != null) {
                TweakStore.preferences(context).edit()
                        .putString(KEY_SCAN_SCOPE, DexKitPilotPolicy.budgetScope(
                                entry.versionCode, entry.lastUpdateTime, entry.queryRevision,
                                entry.resolverFormat, entry.moduleVersion))
                        .putInt(KEY_SCAN_FAILURES, 0)
                        .putLong(KEY_SCAN_LAST_ATTEMPT_MS, 0L)
                        .putLong(KEY_SCAN_SLOT_MS, 0L)
                        .commit();
            }
            SymbolSchema.invalidate();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Deletes a skewed entry so the next restart rescans. Counters are left untouched. */
    static boolean clear(Context context) {
        if (context == null) {
            return false;
        }
        return clearLocked(storeIo(context));
    }

    static boolean clearLocked(StoreIo io) {
        synchronized (FILE_LOCK) {
            return clearUnsynchronized(io);
        }
    }

    private static boolean clearUnsynchronized(StoreIo io) {
        try {
            io.delete();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Read-only scan budget state for one scope. */
    static ScanState scanState(Context context, long now, String scope) {
        if (context == null) {
            return new ScanState(false, 0, "context unavailable");
        }
        SharedPreferences preferences = TweakStore.preferences(context);
        return DexKitPilotPolicy.evaluateBudget(
                preferences.getString(KEY_SCAN_SCOPE, ""),
                preferences.getInt(KEY_SCAN_FAILURES, 0),
                preferences.getLong(KEY_SCAN_LAST_ATTEMPT_MS, 0L),
                preferences.getLong(KEY_SCAN_SLOT_MS, 0L),
                scope, now);
    }

    /**
     * Atomically claims the scan slot when the scope budget allows. A scope change resets
     * the counters first. The caller must record the outcome ({@link #save} /
     * {@link #recordFailure}) or let the TTL release the slot.
     */
    static ScanState claimScanSlot(Context context, long now, String scope) {
        if (context == null) {
            return new ScanState(false, 0, "context unavailable");
        }
        if (scope == null || scope.isEmpty()) {
            return new ScanState(false, 0, "host identity unavailable");
        }
        synchronized (DexKitStore.class) {
            SharedPreferences preferences = TweakStore.preferences(context);
            String storedScope = preferences.getString(KEY_SCAN_SCOPE, "");
            if (!scope.equals(storedScope)) {
                // A new scope gets a fresh budget. Resetting the failure count alone is not
                // enough: the previous scope's live slot and attempt timestamp would then be
                // evaluated against the new scope, so the claim below would be rejected as
                // "already in progress" for the whole TTL, and the process has already
                // recorded its one attempt.
                preferences.edit()
                        .putString(KEY_SCAN_SCOPE, scope)
                        .putInt(KEY_SCAN_FAILURES, 0)
                        .putLong(KEY_SCAN_LAST_ATTEMPT_MS, 0L)
                        .putLong(KEY_SCAN_SLOT_MS, 0L)
                        .commit();
                storedScope = scope;
            }
            long[] carryOver = DexKitPilotPolicy.budgetCarryOver(storedScope, scope,
                    preferences.getLong(KEY_SCAN_SLOT_MS, 0L),
                    preferences.getLong(KEY_SCAN_LAST_ATTEMPT_MS, 0L));
            SharedPreferences.Editor normalized = preferences.edit();
            boolean dirty = false;
            if (carryOver[0] != preferences.getLong(KEY_SCAN_SLOT_MS, 0L)) {
                normalized.putLong(KEY_SCAN_SLOT_MS, carryOver[0]);
                dirty = true;
            }
            if (carryOver[1] != preferences.getLong(KEY_SCAN_LAST_ATTEMPT_MS, 0L)) {
                normalized.putLong(KEY_SCAN_LAST_ATTEMPT_MS, carryOver[1]);
                dirty = true;
            }
            if (dirty) {
                normalized.commit();
            }
            ScanState state = scanState(context, now, scope);
            if (!state.allowed) {
                return state;
            }
            preferences.edit()
                    .putLong(KEY_SCAN_LAST_ATTEMPT_MS, now)
                    .putLong(KEY_SCAN_SLOT_MS, now)
                    .commit();
            return new ScanState(true, state.failures, "");
        }
    }

    static void releaseScanSlot(Context context) {
        if (context == null) {
            return;
        }
        TweakStore.preferences(context).edit().putLong(KEY_SCAN_SLOT_MS, 0L).commit();
    }

    static void recordFailure(Context context, long now, String scope) {
        if (context == null) {
            return;
        }
        synchronized (DexKitStore.class) {
            SharedPreferences preferences = TweakStore.preferences(context);
            int failures = preferences.getInt(KEY_SCAN_FAILURES, 0);
            if (scope != null && !scope.isEmpty()
                    && !scope.equals(preferences.getString(KEY_SCAN_SCOPE, ""))) {
                preferences.edit().putString(KEY_SCAN_SCOPE, scope).commit();
                failures = 0;
            }
            preferences.edit()
                    .putInt(KEY_SCAN_FAILURES, failures + 1)
                    .putLong(KEY_SCAN_LAST_ATTEMPT_MS, now)
                    .putLong(KEY_SCAN_SLOT_MS, 0L)
                    .commit();
        }
    }

    /**
     * Rejects scan results for an artifact that is no longer installed — e.g. Zalo updated
     * while a background scan was running. Rejection touches neither the stored cache nor
     * the retry budget: the scan did not fail, the host moved.
     */
    private static boolean hostMatchesCurrent(Context context, DexKitCache.Entry entry) {
        try {
            ZaloArtifactIdentity current = ZaloArtifactIdentity.capture(context, false);
            long apkSize = current.sourceDir == null ? -1L
                    : new File(current.sourceDir).length();
            return hostMatches(entry, current.versionCode, current.lastUpdateTime, apkSize,
                    DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT,
                    BuildConfig.VERSION_CODE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static StoreIo storeIo(Context context) {
        final AtomicFile file = new AtomicFile(new File(context.getFilesDir(), FILE_NAME));
        return new StoreIo() {
            @Override
            public byte[] read() throws Exception {
                return readBounded(file.openRead(), DexKitCache.MAX_ENTRY_BYTES + 1);
            }

            @Override
            public void write(byte[] data) throws Exception {
                FileOutputStream output = file.startWrite();
                try {
                    output.write(data);
                    output.flush();
                    file.finishWrite(output);
                } catch (Throwable throwable) {
                    file.failWrite(output);
                    throw throwable;
                }
            }

            @Override
            public void delete() {
                File base = file.getBaseFile();
                if (base.exists() && !base.delete()) {
                    throw new IllegalStateException("dexkit cache delete failed");
                }
            }

            @Override
            public boolean exists() {
                return file.getBaseFile().exists();
            }
        };
    }

    private static byte[] readBounded(InputStream input, int limit) throws Exception {
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > limit) {
                    throw new IllegalArgumentException("dexkit cache entry too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    static final class ScanState {
        final boolean allowed;
        final int failures;
        final String reason;

        ScanState(boolean allowed, int failures, String reason) {
            this.allowed = allowed;
            this.failures = failures;
            this.reason = reason == null ? "" : reason;
        }
    }
}
