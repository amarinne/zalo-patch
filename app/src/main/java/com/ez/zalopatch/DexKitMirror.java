package com.ez.zalopatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Property-mirror transport for the DexKit pilot cache and scan budget.
 *
 * <p>On some devices (observed: Xiaomi 14 / HyperOS, Android 16) the Zalo process cannot
 * resolve the module content provider at all: {@code createPackageContext("com.ez.zalopatch")}
 * fails, provider queries return null, and provider calls throw
 * {@code IllegalArgumentException}. The provider path still works where it works (Fold3 /
 * Android 12, shell), so it stays first; this mirror is the fallback transport in both
 * directions:
 *
 * <ul>
 *   <li>Module to Zalo: the validated cache entry plus the scan budget are mirrored as
 *       blobs through {@link SettingsPropertyMirror} (root-gated write, world-readable
 *       read). The Zalo process reads them without touching the provider.</li>
 *   <li>Zalo to module: scan outcomes travel by explicit-component broadcast to
 *       {@code SelfCheckReceiver} (API 34+ shareIdentity, same pattern as the self-check
 *       fallback), where they go through the same {@link DexKitStore} validation as
 *       provider writes, then re-mirror.</li>
 * </ul>
 *
 * <p>Trust: mirror writes require root in the module process, the same gate as the
 * existing settings/artifact/rule mirror. The Zalo side accepts a mirrored entry only
 * when its cheap host-identity fields (versionCode, lastUpdateTime, APK size, query
 * revision, resolver format, module version) match the installed host; the code digest
 * is trusted as module-validated at scan time, and live preflight stays the
 * load-bearing check before any hook installs.
 */
public final class DexKitMirror {
    static final String MIRROR_CACHE_KEY = "dexkit.cache";
    static final String MIRROR_BUDGET_KEY = "dexkit.scan_budget";

    private DexKitMirror() {
    }

    /** Byte-level blob access, so the codec is JVM-tested with a fake. */
    interface BlobIo {
        String read(String key);

        boolean write(String key, String value);
    }

    /** Scan budget for one host/code/query/module generation. */
    public static final class Budget {
        final String scope;
        final int failures;
        final long lastAttemptMs;

        Budget(String scope, int failures, long lastAttemptMs) {
            this.scope = scope == null ? "" : scope;
            this.failures = failures;
            this.lastAttemptMs = lastAttemptMs;
        }
    }

    /** Encodes a budget as three strict lines: scope, failures, lastAttemptMs. */
    static String encodeBudget(Budget budget) {
        if (budget == null || budget.scope.isEmpty() || budget.scope.contains("\n")
                || budget.failures < 0 || budget.lastAttemptMs < 0L) {
            return "";
        }
        return budget.scope + "\n" + budget.failures + "\n" + budget.lastAttemptMs;
    }

    /** Parses {@link #encodeBudget} output, or null when malformed. */
    static Budget decodeBudget(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String[] lines = value.split("\n", -1);
        if (lines.length != 3 || lines[0].isEmpty()) {
            return null;
        }
        try {
            int failures = Integer.parseInt(lines[1]);
            long lastAttempt = Long.parseLong(lines[2]);
            if (failures < 0 || lastAttempt < 0L) {
                return null;
            }
            return new Budget(lines[0], failures, lastAttempt);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * True when the mirrored entry describes the given host without recomputing the base
     * APK digest (kept off the Zalo startup path). The digest itself is trusted as
     * module-validated at scan time; a same-timestamp same-size replacement stays the
     * accepted residual risk, and live preflight remains load-bearing.
     */
    public static boolean mirrorBinds(DexKitCache.Entry entry, long versionCode, long lastUpdateTime,
                               long apkSize, int queryRevision, int resolverFormat,
                               int moduleVersion) {
        return entry != null
                && DexKitCache.isSha256(entry.codeDigest)
                && entry.versionCode == versionCode
                && entry.lastUpdateTime == lastUpdateTime
                && entry.apkSize == apkSize
                && entry.queryRevision == queryRevision
                && entry.resolverFormat == resolverFormat
                && entry.moduleVersion == moduleVersion;
    }

    /** Reads the mirrored cache entry, or null when absent or unparsable. */
    public static DexKitCache.Entry readCacheEntry() {
        return readCacheEntry(new BlobIo() {
            @Override
            public String read(String key) {
                return SettingsPropertyMirror.readBlob(key);
            }

            @Override
            public boolean write(String key, String value) {
                return SettingsPropertyMirror.writeBlob(key, value);
            }
        });
    }

    static DexKitCache.Entry readCacheEntry(BlobIo io) {
        try {
            String json = io.read(MIRROR_CACHE_KEY);
            if (json == null || json.isEmpty()) {
                return null;
            }
            return DexKitCache.parse(json);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Reads the mirrored scan budget, or null when absent or malformed. */
    public static Budget readBudget() {
        return decodeBudget(SettingsPropertyMirror.readBlob(MIRROR_BUDGET_KEY));
    }

    static Budget readBudget(BlobIo io) {
        try {
            return decodeBudget(io.read(MIRROR_BUDGET_KEY));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Scan-budget decision for the Zalo process when the provider is unreachable. */
    public static final class Claim {
        public final boolean allowed;
        public final int failures;
        public final String reason;

        public Claim(boolean allowed, int failures, String reason) {
            this.allowed = allowed;
            this.failures = failures;
            this.reason = reason == null ? "" : reason;
        }
    }

    /**
     * Evaluates the mirrored budget for one scope. No slot claim is possible without
     * the provider, so the slot is passed empty: one scan per Zalo process is enforced
     * by the caller, and cross-process backoff comes from the mirrored counters.
     */
    public static Claim claimFromMirror(Budget budget, String scope, long now) {
        String stored = budget == null ? "" : budget.scope;
        int failures = budget == null ? 0 : budget.failures;
        long lastAttempt = budget == null ? 0L : budget.lastAttemptMs;
        DexKitStore.ScanState state = DexKitPilotPolicy.evaluateBudget(
                stored, failures, lastAttempt, 0L, scope, now);
        return new Claim(state.allowed, state.failures, state.reason);
    }

    /**
     * Module-process sync: mirrors the current cache entry and scan budget for the
     * installed host. Best-effort; both writes are root-gated like the existing
     * settings mirror.
     *
     * <p>The budget is seeded for the currently installed host scope (not the stored
     * scope, which is empty before the first scan), so the hook process can authorize
     * one scan from the mirror alone. Stored failure counters survive only when their
     * scope still matches; a host, query, or module change is a fresh budget.
     */
    static void sync(Context context) {
        if (context == null) {
            return;
        }
        try {
            DexKitCache.Entry entry = DexKitStore.load(context);
            // Mirror absence too: a cleared cache must not leave a stale entry for the
            // hook process that reads the property mirror instead of the provider.
            if (!SettingsPropertyMirror.writeBlob(MIRROR_CACHE_KEY,
                    entry == null ? "" : DexKitCache.serialize(entry))) {
                Log.i("ZaloPatch", "DexKit mirror cache write failed");
            }
            SharedPreferences preferences = TweakStore.preferences(context);
            Budget budget = seedBudget(
                    preferences.getString(DexKitStore.KEY_SCAN_SCOPE, ""),
                    preferences.getInt(DexKitStore.KEY_SCAN_FAILURES, 0),
                    preferences.getLong(DexKitStore.KEY_SCAN_LAST_ATTEMPT_MS, 0L),
                    currentScope(context));
            SettingsPropertyMirror.writeBlob(MIRROR_BUDGET_KEY, encodeBudget(budget));
        } catch (Throwable throwable) {
            Log.i("ZaloPatch", "DexKit mirror sync failed "
                    + throwable.getClass().getSimpleName());
        }
    }

    /** True when the property mirror holds no usable cache entry. */
    static boolean cacheMirrorEmpty() {
        try {
            String json = SettingsPropertyMirror.readBlob(MIRROR_CACHE_KEY);
            return json == null || json.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Resets failure counters when the host scope moved; otherwise keeps them. */
    static Budget seedBudget(String storedScope, int failures, long lastAttemptMs,
                             String currentScope) {
        if (currentScope == null || currentScope.isEmpty()) {
            return new Budget(storedScope == null ? "" : storedScope,
                    Math.max(0, failures), Math.max(0L, lastAttemptMs));
        }
        if (currentScope.equals(storedScope)) {
            return new Budget(currentScope, Math.max(0, failures), Math.max(0L, lastAttemptMs));
        }
        return new Budget(currentScope, 0, 0L);
    }

    private static String currentScope(Context context) {
        try {
            ZaloArtifactIdentity current = ZaloArtifactIdentity.capture(context, false);
            if (current.versionCode <= 0L || current.lastUpdateTime <= 0L) {
                return "";
            }
            return DexKitPilotPolicy.budgetScope(current.versionCode, current.lastUpdateTime,
                    DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT,
                    BuildConfig.VERSION_CODE);
        } catch (Throwable ignored) {
            return "";
        }
    }
}
