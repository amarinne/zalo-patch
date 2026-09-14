package com.ez.zalopatch.xposed.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import com.ez.zalopatch.BuildConfig;
import com.ez.zalopatch.DexKitCache;
import com.ez.zalopatch.DexKitPilotPolicy;
import com.ez.zalopatch.DexKitZinstantFingerprint;
import com.ez.zalopatch.FingerprintResolver;
import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DexKit pilot adapter at the symbol/preflight boundary (Zalo process side).
 *
 * <p>Selection order for the pilot family only, decided by {@link DexKitPilotPolicy}: valid
 * exact catalog/bundled mapping first; when it is absent or fails preflight, validated
 * DexKit results before neighbouring profiles. A pending scan disables the pilot instead
 * of leaking neighbouring flags into it; ambiguous or contradictory DexKit evidence leaves
 * the pilot unavailable rather than bypassing it with a neighbouring mapping. When DexKit
 * itself is unavailable, the existing fallback policy applies and the source names it
 * explicitly.
 *
 * <p>Cold scans run on a daemon background thread, never on the UI thread. A fresh scan
 * applies at the next restart; the scan session reports {@code pending} meanwhile. Warm
 * starts read the validated cache before any hook installs. Hook installation stays
 * distinct from observed execution: these rows never claim {@code active}.
 */
final class DexKitZinstantResolver {
    static final String FEATURE_CACHE = "dexkit_cache";
    static final String FEATURE_SCAN = "dexkit_scan";
    private static final String TARGET_PACKAGE = "com.zing.zalo";
    private static final Uri PROVIDER = Uri.parse("content://com.ez.zalopatch.config");
    private static final Uri CACHE_URI = Uri.parse("content://com.ez.zalopatch.config/dexkit_cache");
    private static final AtomicBoolean SCAN_RUNNING = new AtomicBoolean(false);

    private DexKitZinstantResolver() {
    }

    /** Per-anchor pilot selection consumed by {@code MainFeatures}. */
    static final class Pilot {
        final boolean messageCompatible;
        final String messageError;
        final String adBindOverride;
        final boolean feedCompatible;
        final String feedError;
        final String feedBindOverride;
        final String source;

        Pilot(boolean messageCompatible, String messageError, String adBindOverride,
              boolean feedCompatible, String feedError, String feedBindOverride, String source) {
            this.messageCompatible = messageCompatible;
            this.messageError = messageError == null ? "" : messageError;
            this.adBindOverride = adBindOverride == null ? "" : adBindOverride;
            this.feedCompatible = feedCompatible;
            this.feedError = feedError == null ? "" : feedError;
            this.feedBindOverride = feedBindOverride == null ? "" : feedBindOverride;
            this.source = source == null ? "unavailable" : source;
        }

        static Pilot fromDecision(DexKitPilotPolicy.Decision decision) {
            return new Pilot(decision.messageCompatible, decision.messageError,
                    decision.adOverride, decision.feedCompatible, decision.feedError,
                    decision.feedOverride, decision.source);
        }
    }

    static Pilot selectPilot(Context context, ClassLoader loader, boolean exactValid,
                             SymbolPreflight.Result exactPreflight,
                             SymbolPreflight.Result effective, boolean fallbackAdopted) {
        boolean messageEnabled = HookConfig.isEnabled(Tweaks.KEY_HIDE_MESSAGE_ADS);
        boolean feedEnabled = HookConfig.isEnabled(Tweaks.KEY_HIDE_FEED_ADS);
        DexKitPilotPolicy.Coverage coverage = new DexKitPilotPolicy.Coverage();
        coverage.messageEnabled = messageEnabled;
        coverage.feedEnabled = feedEnabled;
        coverage.exactMessage = exactValid && exactPreflight != null
                && exactPreflight.zinstantMessage;
        coverage.exactFeed = exactValid && exactPreflight != null && exactPreflight.zinstantFeed;
        coverage.exactMessageError = exactPreflight == null ? "no preflight"
                : exactPreflight.reason(exactPreflight.zinstantMessageErrors);
        coverage.exactFeedError = exactPreflight == null ? "no preflight"
                : exactPreflight.reason(exactPreflight.zinstantFeedErrors);
        boolean needMessage = messageEnabled && !coverage.exactMessage;
        boolean needFeed = feedEnabled && !coverage.exactFeed;
        if (!needMessage && !needFeed) {
            comparisonMode(context);
            DexKitPilotPolicy.Decision decision = DexKitPilotPolicy.decide(coverage);
            applyRow(FEATURE_CACHE, decision.cacheRow);
            return Pilot.fromDecision(decision);
        }
        HostIdentity host = hostIdentity(context);
        CacheRead read = readCache(context);
        coverage.cachePresent = read != null && read.entry != null;
        boolean bound = host != null && coverage.cachePresent && cacheBinds(read.entry, host);
        coverage.cacheBound = bound;
        if (bound) {
            DexKitCache.Entry entry = read.entry;
            coverage.cacheQueryRevision = entry.queryRevision;
            coverage.cacheScanDurationMs = entry.scanDurationMs;
            coverage.cacheMatchAd = entry.matchAd;
            coverage.cacheMatchFeed = entry.matchFeed;
            coverage.cacheDigest = entry.codeDigest;
            coverage.cacheNegative = entry.negative;
            coverage.cacheReason = entry.reason;
            coverage.cacheAdPresent = DexKitCache.isMethodName(entry.adBind);
            coverage.cacheFeedPresent = DexKitCache.isMethodName(entry.feedBind);
            coverage.cacheAdName = entry.adBind;
            coverage.cacheFeedName = entry.feedBind;
            coverage.cachePartialAttempts = entry.partialAttempts;
            coverage.cacheAdUsable = coverage.cacheAdPresent
                    && preflights(loader, entry.adBind, "");
            coverage.cacheFeedUsable = coverage.cacheFeedPresent
                    && preflights(loader, "", entry.feedBind);
        }
        if (read == null) {
            coverage.dexkitAvailable = false;
            coverage.unavailableReason = "provider unavailable";
        } else if (host == null) {
            coverage.dexkitAvailable = false;
            coverage.unavailableReason = "apk path unavailable";
        } else {
            coverage.dexkitAvailable = read.scanAllowed;
            coverage.unavailableReason = read.scanReason;
        }
        // Neighbouring coverage is consulted only through the explicit DexKit-unavailable
        // fallback inside the policy — never as the post-adoption aggregate for pending paths.
        coverage.neighborAdopted = fallbackAdopted;
        coverage.neighborMessage = effective != null && effective.zinstantMessage;
        coverage.neighborFeed = effective != null && effective.zinstantFeed;
        coverage.neighborMessageError = effective == null ? "no preflight"
                : effective.reason(effective.zinstantMessageErrors);
        coverage.neighborFeedError = effective == null ? "no preflight"
                : effective.reason(effective.zinstantFeedErrors);
        DexKitPilotPolicy.Decision decision = DexKitPilotPolicy.decide(coverage);
        applyRow(FEATURE_CACHE, decision.cacheRow);
        if (decision.markScanRow) {
            applyRow(FEATURE_SCAN, decision.scanRow);
        }
        if (decision.clearCache) {
            clearCache(context);
        }
        if (decision.kickScan && host != null) {
            int previousAttempts = bound && read != null && read.entry != null
                    ? read.entry.partialAttempts : 0;
            maybeScanInBackground(context, host, previousAttempts);
        }
        return Pilot.fromDecision(decision);
    }

    private static void applyRow(String feature, DexKitPilotPolicy.Row row) {
        if ("disabled".equals(row.status)) {
            SelfCheckRegistry.markDisabled(feature, row.target);
            return;
        }
        SelfCheckRegistry.markStatus(feature, row.status, row.target, row.detail, row.error);
    }

    /**
     * Comparison mode: when the exact profile is active and a valid cache entry exists for the
     * same host code, record agreement without changing hook targets.
     */
    private static void comparisonMode(Context context) {
        try {
            HostIdentity host = hostIdentity(context);
            CacheRead read = readCache(context);
            if (host == null || read == null || read.entry == null
                    || !cacheBinds(read.entry, host)) {
                SelfCheckRegistry.markStatus(FEATURE_SCAN, "ok", "standby",
                        "exact profile active; no comparable dexkit result", "");
                return;
            }
            String schemaAd = SymbolSchema.string(context, "symbols.zinstant.ad_bind_method", "");
            String schemaFeed = SymbolSchema.string(context, "symbols.zinstant.feed_bind_method", "");
            boolean adAgree = read.entry.adBind.equals(schemaAd);
            boolean feedAgree = read.entry.feedBind.equals(schemaFeed);
            String detail = "comparison rev " + read.entry.queryRevision
                    + " ad " + (adAgree ? "agree" : "disagree")
                    + " feed " + (feedAgree ? "agree" : "disagree");
            SelfCheckRegistry.markStatus(FEATURE_SCAN, "ok", "comparison", detail, "");
        } catch (Throwable throwable) {
            SelfCheckRegistry.markStatus(FEATURE_SCAN, "ok", "comparison unavailable", "",
                    throwable.getClass().getSimpleName());
        }
    }

    private static boolean preflights(ClassLoader loader, String adBind, String feedBind) {
        try {
            List<String> errors = new ArrayList<>();
            return SymbolPreflight.checkZinstantDescriptors(loader, adBind, feedBind, errors);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void maybeScanInBackground(Context context, HostIdentity host,
                                            int previousPartialAttempts) {
        if (!SCAN_RUNNING.compareAndSet(false, true)) {
            SelfCheckRegistry.markStatus(FEATURE_SCAN, "pending", "scan already running",
                    "one discovery session per process", "");
            return;
        }
        final Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        final HostIdentity snapshot = host;
        final int previousAttempts = previousPartialAttempts;
        SelfCheckRegistry.markStatus(FEATURE_SCAN, "pending", "scan started",
                "cold scan off the UI thread; cache applies at the next restart", "");
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runScan(appContext, snapshot, previousAttempts);
                } finally {
                    SCAN_RUNNING.set(false);
                }
            }
        }, "dexkit-scan");
        worker.setDaemon(true);
        worker.start();
    }

    private static void runScan(Context context, HostIdentity host, int previousPartialAttempts) {
        long started = System.nanoTime();
        String scope = DexKitPilotPolicy.budgetScope(host.versionCode, host.lastUpdateTime,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT,
                BuildConfig.VERSION_CODE);
        Bundle claim = providerCall(context, "claim_dexkit_scan",
                String.valueOf(host.versionCode), claimExtras(host));
        if (claim == null || !claim.getBoolean("allowed", false)) {
            String reason = claim == null ? "provider unavailable"
                    : claim.getString("reason", "scan unavailable");
            if (reason.contains("already in progress")) {
                SelfCheckRegistry.markStatus(FEATURE_SCAN, "pending",
                        "scan already claimed", "awaiting sibling result", "");
            } else {
                SelfCheckRegistry.markStatus(FEATURE_SCAN, "stale", reason, "", "");
            }
            return;
        }
        String loadError;
        try {
            loadError = DexKitBridgeRunner.ensureLoaded();
        } catch (Throwable throwable) {
            loadError = throwable.getClass().getSimpleName();
        }
        if (loadError != null) {
            recordFailure(context, host, "native_load_failed: " + loadError);
            SelfCheckRegistry.markStatus(FEATURE_SCAN, "stale", "native_load_failed",
                    loadError + "; existing fallback policy applies", "");
            return;
        }
        String codeDigest;
        long apkSize;
        try {
            codeDigest = sha256(new File(host.sourceDir));
            apkSize = new File(host.sourceDir).length();
        } catch (Throwable throwable) {
            recordFailure(context, host, "apk_hash_failed");
            SelfCheckRegistry.markStatus(FEATURE_SCAN, "stale", "apk_hash_failed",
                    throwable.getClass().getSimpleName(), "");
            return;
        }
        DexKitBridgeRunner.QueryResult[] results;
        try {
            results = DexKitBridgeRunner.scanBaseApk(host.sourceDir);
        } catch (Throwable throwable) {
            recordFailure(context, host, "bridge_failed");
            SelfCheckRegistry.markStatus(FEATURE_SCAN, "failed", "bridge_failed",
                    "", throwable.getClass().getSimpleName());
            return;
        }
        boolean retained = DexKitZinstantFingerprint.expectedDescriptors()
                .containsKey(host.versionCode);
        FingerprintResolver.Resolution ad = DexKitZinstantFingerprint.evaluate(
                DexKitZinstantFingerprint.ANCHOR_AD_BIND, withViewFlags(
                        host.loader, DexKitZinstantFingerprint.ANCHOR_AD_BIND, results[0]),
                codeDigest, codeDigest, retained);
        FingerprintResolver.Resolution feed = DexKitZinstantFingerprint.evaluate(
                DexKitZinstantFingerprint.ANCHOR_FEED_BIND, withViewFlags(
                        host.loader, DexKitZinstantFingerprint.ANCHOR_FEED_BIND, results[1]),
                codeDigest, codeDigest, retained);
        long durationMs = (System.nanoTime() - started) / 1000000L;
        DexKitPilotPolicy.ScanInputs scanInputs = new DexKitPilotPolicy.ScanInputs();
        scanInputs.adStatus = ad.status;
        scanInputs.adSymbol = ad.symbol;
        scanInputs.feedStatus = feed.status;
        scanInputs.feedSymbol = feed.symbol;
        scanInputs.adQueryError = results[0].error;
        scanInputs.feedQueryError = results[1].error;
        scanInputs.matchAd = results[0].matchCount;
        scanInputs.matchFeed = results[1].matchCount;
        scanInputs.versionCode = host.versionCode;
        scanInputs.codeDigest = codeDigest;
        scanInputs.lastUpdateTime = host.lastUpdateTime;
        scanInputs.apkSize = apkSize;
        scanInputs.queryRevision = DexKitZinstantFingerprint.QUERY_REVISION;
        scanInputs.resolverFormat = DexKitCache.RESOLVER_FORMAT;
        scanInputs.moduleVersion = BuildConfig.VERSION_CODE;
        scanInputs.durationMs = durationMs;
        scanInputs.scannedAt = System.currentTimeMillis();
        scanInputs.previousPartialAttempts = previousPartialAttempts;
        DexKitPilotPolicy.ScanOutcome outcome =
                DexKitPilotPolicy.resolveScanOutcome(scanInputs);
        if (!outcome.record) {
            recordFailure(context, host, outcome.retryReason);
            SelfCheckRegistry.markStatus(FEATURE_SCAN, "stale", outcome.retryReason, "", "");
            return;
        }
        DexKitCache.Entry entry = outcome.entry;
        if (!entry.negative) {
            List<String> errors = new ArrayList<>();
            if (!SymbolPreflight.checkZinstantDescriptors(host.loader, entry.adBind,
                    entry.feedBind, errors)) {
                recordFailure(context, host, "preflight_failed");
                SelfCheckRegistry.markStatus(FEATURE_SCAN, "stale", "preflight_failed",
                        String.join("; ", errors), "");
                return;
            }
        }
        Bundle recorded = recordCache(context, DexKitCache.serialize(entry));
        if (recorded != null && recorded.getBoolean("recorded", false)) {
            String detail = (entry.negative ? "confirmed miss " : "cache ready ")
                    + "rev " + entry.queryRevision
                    + " · " + entry.scanDurationMs + "ms · ad " + entry.matchAd
                    + " feed " + entry.matchFeed;
            SelfCheckRegistry.markStatus(FEATURE_SCAN, "pending",
                    entry.negative ? "no_match" : "cache ready",
                    detail + "; applies at the next restart", "");
        } else {
            recordFailure(context, host, "cache_rejected");
            SelfCheckRegistry.markStatus(FEATURE_SCAN, "stale", "cache_rejected",
                    "module process refused the scan result", "");
        }
    }

    private static List<DexKitZinstantFingerprint.MethodHit> withViewFlags(
            ClassLoader loader, String anchor, DexKitBridgeRunner.QueryResult result) {
        List<DexKitZinstantFingerprint.MethodHit> hits = new ArrayList<>();
        if (result == null) {
            return hits;
        }
        String owner = DexKitZinstantFingerprint.expectedOwner(anchor);
        boolean viewBoundary = false;
        boolean hasConstructor = false;
        try {
            Class<?> view = Class.forName(owner, false, loader);
            viewBoundary = android.view.View.class.isAssignableFrom(view);
            hasConstructor = view.getDeclaredConstructors().length > 0;
        } catch (Throwable ignored) {
        }
        for (DexKitBridgeRunner.RawHit raw : result.hits) {
            hits.add(new DexKitZinstantFingerprint.MethodHit(raw.className, raw.methodName,
                    raw.returnTypeName, raw.paramCount, viewBoundary, hasConstructor));
        }
        return hits;
    }

    /** Best-effort skew recovery; see {@link #clearCache}. */
    private static void clearCache(Context context) {
        try {
            context.getContentResolver().call(PROVIDER, "clear_dexkit_cache", null, null);
        } catch (Throwable ignored) {
        }
    }

    private static Bundle claimExtras(HostIdentity host) {
        Bundle extras = new Bundle();
        extras.putLong("version_code", host.versionCode);
        extras.putLong("last_update_time", host.lastUpdateTime);
        return extras;
    }

    private static void recordFailure(Context context, HostIdentity host, String reason) {
        try {
            Bundle extras = new Bundle();
            extras.putString("reason", reason);
            extras.putLong("version_code", host.versionCode);
            extras.putLong("last_update_time", host.lastUpdateTime);
            context.getContentResolver().call(PROVIDER, "record_dexkit_scan_failure", null, extras);
        } catch (Throwable ignored) {
        }
    }

    private static Bundle recordCache(Context context, String json) {
        try {
            Bundle extras = new Bundle();
            extras.putString("json", json);
            return context.getContentResolver().call(PROVIDER, "record_dexkit_cache", null, extras);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bundle providerCall(Context context, String method, String arg, Bundle extras) {
        try {
            return context.getContentResolver().call(PROVIDER, method, arg, extras);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean cacheBinds(DexKitCache.Entry entry, HostIdentity host) {
        // The digest itself is trusted here: the scan wrote it for this exact version,
        // lightweight identity, and file size, and the equality below re-establishes all of
        // those. A same-timestamp replacement of identical size is the accepted residual risk
        // (see DexKitCache.bindsTo); live preflight remains the load-bearing check.
        return DexKitCache.bindsTo(entry, host.versionCode, entry.codeDigest,
                host.lastUpdateTime, host.apkSize,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT,
                BuildConfig.VERSION_CODE);
    }

    private static String sha256(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            }
            return hex.toString();
        }
    }

    private static HostIdentity hostIdentity(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            ApplicationInfo application = info.applicationInfo;
            if (application == null || application.sourceDir == null) {
                return null;
            }
            long versionCode = android.os.Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode;
            long apkSize = new File(application.sourceDir).length();
            return new HostIdentity(versionCode, info.lastUpdateTime, application.sourceDir,
                    apkSize, context.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Reads the cache entry plus the scan budget. Returns null when the provider itself is
     * unreachable, which the policy treats as DexKit-unavailable (neighbouring fallback)
     * rather than as a pending scan.
     */
    private static CacheRead readCache(Context context) {
        try (Cursor cursor = context.getContentResolver().query(CACHE_URI, null, null, null,
                null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            CacheRead read = new CacheRead();
            int jsonIndex = cursor.getColumnIndex("json");
            int validIndex = cursor.getColumnIndex("valid");
            int allowedIndex = cursor.getColumnIndex("scan_allowed");
            int failuresIndex = cursor.getColumnIndex("scan_failures");
            int reasonIndex = cursor.getColumnIndex("scan_reason");
            String json = jsonIndex < 0 ? "" : cursor.getString(jsonIndex);
            boolean valid = validIndex >= 0 && cursor.getInt(validIndex) == 1;
            if (valid && json != null && !json.isEmpty()) {
                try {
                    read.entry = DexKitCache.parse(json);
                } catch (Throwable ignored) {
                }
            }
            read.scanAllowed = allowedIndex >= 0 && cursor.getInt(allowedIndex) == 1;
            read.scanFailures = failuresIndex < 0 ? 0 : cursor.getInt(failuresIndex);
            read.scanReason = reasonIndex < 0 ? "" : cursor.getString(reasonIndex);
            if (read.scanReason == null) {
                read.scanReason = "";
            }
            return read;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class HostIdentity {
        final long versionCode;
        final long lastUpdateTime;
        final String sourceDir;
        final long apkSize;
        final ClassLoader loader;

        HostIdentity(long versionCode, long lastUpdateTime, String sourceDir, long apkSize,
                     ClassLoader loader) {
            this.versionCode = versionCode;
            this.lastUpdateTime = lastUpdateTime;
            this.sourceDir = sourceDir == null ? "" : sourceDir;
            this.apkSize = apkSize;
            this.loader = loader;
        }
    }

    private static final class CacheRead {
        DexKitCache.Entry entry;
        boolean scanAllowed = true;
        int scanFailures;
        String scanReason = "";
    }
}
