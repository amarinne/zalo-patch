package com.ez.zalopatch.xposed.core;

import android.content.Context;

import com.ez.zalopatch.BuildConfig;
import com.ez.zalopatch.DexKitCache;
import com.ez.zalopatch.DexKitFamilyRetry;
import com.ez.zalopatch.DexKitMirror;
import com.ez.zalopatch.DexKitOverlay;
import com.ez.zalopatch.DexKitAnchors;
import com.ez.zalopatch.DexKitBottomTabsFingerprint;
import com.ez.zalopatch.DexKitCallFingerprint;
import com.ez.zalopatch.DexKitCallPeerFingerprint;
import com.ez.zalopatch.DexKitChatFingerprint;
import com.ez.zalopatch.DexKitDeletedGroupFingerprint;
import com.ez.zalopatch.DexKitInboxFingerprint;
import com.ez.zalopatch.DexKitMeFingerprint;
import com.ez.zalopatch.DexKitMediaFingerprint;
import com.ez.zalopatch.DexKitPasscodeFingerprint;
import com.ez.zalopatch.DexKitPilotPolicy;
import com.ez.zalopatch.DexKitTelemetryFingerprint;
import com.ez.zalopatch.DexKitWebviewFingerprint;
import com.ez.zalopatch.DexKitZinstantFingerprint;
import com.ez.zalopatch.FingerprintResolver;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.xposed.features.CallRecordingLifecycle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Extended DexKit family resolver: resolves every anchor family with a fingerprint
 * definition (starting with WebView, plus the Zinstant pilot anchors), validates the
 * descriptors with the same structural preflight the schema path uses, and adopts a
 * merged overlay profile for the Zalo process only.
 *
 * <p>Precedence per anchor is exact profile, then validated DexKit descriptors, then
 * the neighbouring adopted profile. Features read symbols through
 * {@code SymbolSchema} unchanged; the overlay lands in the same hook-process cache
 * the neighbouring fallback uses. Nothing is persisted from the overlay: the cache
 * entry (descriptors plus binding) is the only thing written, through the same
 * provider-first, broadcast-and-mirror transport as the pilot.
 *
 * <p>Scanning is fail-closed per family: an anchor resolves only on exactly one
 * qualifying candidate, and a family arms only when its preflight passes on the
 * merged overlay. Ambiguous families stay stale.
 */
final class DexKitFamilyResolver {
    static final String FEATURE_OVERLAY = "dexkit_overlay";
    private static final int CLAIM_RETRIES = 2;
    private static final long CLAIM_RETRY_WAIT_MS = 45_000L;
    /** Terminal lifecycle row for the family scan; must never stay pending. */
    static final String FEATURE_SCAN = "dexkit_scan";
    private static final String TARGET_PACKAGE = "com.zing.zalo";
    private static final AtomicBoolean SCAN_KICKED = new AtomicBoolean(false);
    private static final AtomicBoolean SCAN_RUNNING = new AtomicBoolean(false);

    private DexKitFamilyResolver() {
    }

    /** Overlay outcome consumed by {@code MainFeatures} for family gating. */
    static final class Result {
        final boolean adopted;
        final SymbolPreflight.Result preflight;
        final FamilyStates families;
        final Map<String, String> resolved;

        Result(boolean adopted, SymbolPreflight.Result preflight, FamilyStates families,
               Map<String, String> resolved) {
            this.adopted = adopted;
            this.preflight = preflight;
            this.families = families == null ? new FamilyStates() : families;
            this.resolved = resolved == null
                    ? new LinkedHashMap<String, String>() : resolved;
        }

        static Result empty() {
            return new Result(false, null, new FamilyStates(),
                    new LinkedHashMap<String, String>());
        }
    }

    /** Per-family arming computed from one overlay; single source of truth. */
    static final class FamilyStates {
        boolean webview;
        boolean passcode;
        boolean backup;
        boolean telemetry;
        boolean bottomTabs;
        boolean me;
        boolean inboxCategories;
        boolean statusPrivacy;
        boolean zinstantMessage;
        boolean zinstantFeed;
    }

    /**
     * Computes per-family arming for an overlay. Passcode arms on the full setter
     * check, or on the reader alone when no setter resolved: the setter is
     * preflight-only, and the hook's runtime key-equality guard is the remaining
     * semantic check beside the live reader shape.
     */
    static FamilyStates familyStates(SymbolSchema.Active overlay, ClassLoader loader,
                                      Map<String, String> extended) {
        FamilyStates states = new FamilyStates();
        if (overlay == null) {
            return states;
        }
        try {
            SymbolPreflight.Result checked = SymbolPreflight.inspect(overlay, loader);
            states.webview = checked.webviewExternalize;
            states.statusPrivacy = checked.statusPrivacy;
            states.backup = checked.backupScheduled;
            states.telemetry = checked.telemetryDao;
            states.bottomTabs = checked.bottomTabs && tabsLiveChecks(loader, extended);
            states.zinstantMessage = checked.zinstantMessage;
            states.zinstantFeed = checked.zinstantFeed;
            if (checked.me) {
                states.me = true;
            } else if (extended != null) {
                java.util.List<String> builderErrors = new java.util.ArrayList<>();
                states.me = SymbolPreflight.checkDexkitMeBuilder(loader,
                        extended.get(DexKitMeFingerprint.ANCHOR_BUILDER), builderErrors);
            }
            if (checked.inboxCategories) {
                states.inboxCategories = true;
            } else if (extended != null) {
                java.util.List<String> categoryErrors = new java.util.ArrayList<>();
                states.inboxCategories = SymbolPreflight.checkDexkitInboxCategory(loader,
                        extended.get(DexKitInboxFingerprint.ANCHOR_CATEGORY_FIELD),
                        categoryErrors);
            }
            if (checked.passcodeGrace) {
                states.passcode = true;
            } else if (extended != null
                    && (!extended.containsKey(
                            DexKitPasscodeFingerprint.ANCHOR_SETTER_CLASS)
                    || !extended.containsKey(
                            DexKitPasscodeFingerprint.ANCHOR_SETTER_METHOD))) {
                // Reader-only arming: the reader was validated by shape at scan time
                // (preflightFilter) and the cache binds to the exact APK, so re-checking
                // the live class here adds no signal; the hook's runtime key guard plus
                // runGuarded are the remaining safety. A missing reader still fails.
                String readerClass = extended.get(
                        DexKitPasscodeFingerprint.ANCHOR_READER_CLASS);
                String readerMethod = extended.get(
                        DexKitPasscodeFingerprint.ANCHOR_READER_METHOD);
                XpLog.i("ZaloPatch: DexKit passcode branch checked=" + checked.passcodeGrace
                        + " readerClass=" + readerClass + " readerMethod=" + readerMethod);
                states.passcode = readerClass != null && !readerClass.isEmpty()
                        && readerMethod != null && !readerMethod.isEmpty();
            }
        } catch (Throwable ignored) {
        }
        return states;
    }

    /** True once this process kicked a family scan; the pilot consults this. */
    static boolean scanKickedThisProcess() {
        return SCAN_KICKED.get();
    }

    static Result resolve(Context context, ClassLoader loader, SymbolSchema.Active exact,
                          SymbolPreflight.Result exactPreflight, SymbolSchema.Active fallback) {
        if (completeExactCoverage(exact, exactPreflight)) {
            // Profile syntax alone does not prove coverage. Partial exact profiles
            // leave the remaining families eligible for bound DexKit discovery.
            SelfCheckRegistry.markStatus(FEATURE_OVERLAY, "disabled", "exact profile in use",
                    "DexKit overlay idle while structural preflight covers every feature family", "");
            return Result.empty();
        }
        DexKitZinstantResolver.HostIdentity host = DexKitZinstantResolver.hostIdentity(context);
        if (host == null) {
            SelfCheckRegistry.markStatus(FEATURE_OVERLAY, "stale", "host unavailable", "", "");
            return Result.empty();
        }
        DexKitZinstantResolver.CacheRead read = DexKitZinstantResolver.readCache(context, host);
        boolean bound = read != null && read.entry != null
                && DexKitZinstantResolver.cacheBinds(read.entry, host);
        if (!bound) {
            SelfCheckRegistry.markStatus(FEATURE_OVERLAY, "stale",
                    read == null ? "no transport" : "no cache",
                    read == null || read.entry != null ? ""
                            : "no dexkit result for this host code yet", "");
            if (read != null && read.scanAllowed) {
                maybeScanInBackground(context, host, null, exact, fallback);
            }
            return Result.empty();
        }
        DexKitCache.Entry checked = DexKitFamilyRetry.preflight(read.entry,
                preflightFilter(context, host, loader, read.entry, exact, fallback));
        boolean adOk = checked.adBind.isEmpty()
                || DexKitZinstantResolver.preflights(loader, checked.adBind, "");
        boolean feedOk = checked.feedBind.isEmpty()
                || DexKitZinstantResolver.preflights(loader, "", checked.feedBind);
        if (!adOk || !feedOk) {
            Map<String, DexKitFamilyRetry.State> states = new LinkedHashMap<>(checked.families);
            DexKitFamilyRetry.State old = states.get("zinstant");
            states.put("zinstant", new DexKitFamilyRetry.State("preflight_rejected",
                    old == null ? 0 : old.attempts));
            checked = new DexKitCache.Entry(checked.versionCode, checked.codeDigest,
                    checked.lastUpdateTime, checked.apkSize, checked.queryRevision,
                    checked.resolverFormat, checked.moduleVersion,
                    adOk ? checked.adBind : "", feedOk ? checked.feedBind : "", false,
                    "preflight_rejected", checked.matchAd, checked.matchFeed,
                    checked.scanDurationMs, checked.scannedAt, checked.partialAttempts,
                    checked.extended, states);
        }
        Result active = activate(context, loader, host, checked, exact, fallback);
        // Preserve healthy families while recovery runs. The existing provider/mirror
        // claim and global failure backoff still guard every native scan.
        if (read.scanAllowed && !DexKitFamilyRetry.pending(checked).isEmpty()) {
            maybeScanInBackground(context, host, checked, exact, fallback);
        }
        return active;
    }

    static boolean completeExactCoverage(SymbolSchema.Active exact,
                                                 SymbolPreflight.Result checked) {
        if (exact == null || !exact.valid || checked == null) return false;
        return checked.inboxMedia && checked.inboxCategories && checked.me
                && checked.bottomTabs && checked.zinstantMessage && checked.zinstantFeed
                && checked.statusPrivacy && checked.passcodeGrace && checked.backupScheduled
                && checked.webviewExternalize && checked.telemetryDao && checked.callRecording
                && checked.inboxRows && checked.bottomTabsSymbols && checked.chatReaction
                && checked.zinstantSymbols;
    }

    private static JSONObject composeSymbols(SymbolSchema.Active exact,
            SymbolSchema.Active fallback, Map<String, String> descriptors,
            String adBind, String feedBind) {
        JSONObject exactSymbols = exact != null && exact.valid && exact.root != null
                ? exact.root.optJSONObject("symbols") : null;
        JSONObject neighborSymbols = fallback != null && fallback.valid && fallback.root != null
                ? fallback.root.optJSONObject("symbols") : null;
        return DexKitOverlay.compose(exactSymbols, neighborSymbols, descriptors, adBind, feedBind);
    }

    private static Result activate(Context context, ClassLoader loader,
                                   DexKitZinstantResolver.HostIdentity host,
                                   DexKitCache.Entry entry, SymbolSchema.Active exact,
                                   SymbolSchema.Active fallback) {
        JSONObject merged = composeSymbols(exact, fallback, entry.extended,
                entry.adBind, entry.feedBind);
        String signer = signerSha256(context);
        SymbolSchema.Active overlay = SymbolSchema.dexkitOverlayForHooks(
                DexKitOverlay.buildProfile(host.versionCode, entry.codeDigest, signer, merged,
                        "static-verified"),
                host.versionCode);
        if (overlay == null) {
            SelfCheckRegistry.markStatus(FEATURE_OVERLAY, "stale", "overlay invalid", "", "");
            return Result.empty();
        }
        SymbolPreflight.Result preflight = SymbolPreflight.inspect(overlay, loader);
        FamilyStates families = familyStates(overlay, loader, entry.extended);
        // Reader-only adoption fallback: the scan-time resolver validated the reader
        // shape and the cache binds it to this exact APK. If the family gate stayed
        // shut for any reason while reader anchors are present, arm on the reader;
        // the hook's exact-shape lookup plus runGuarded remains the final safety.
        if (!families.passcode && entry.extended != null
                && entry.extended.containsKey(
                        DexKitPasscodeFingerprint.ANCHOR_READER_CLASS)
                && entry.extended.containsKey(
                        DexKitPasscodeFingerprint.ANCHOR_READER_METHOD)) {
            families.passcode = true;
        }
        XpLog.i("ZaloPatch: DexKit activate families passcode=" + families.passcode
                + " me=" + families.me + " inboxCategories=" + families.inboxCategories
                + " readerInExtended=" + (entry.extended != null
                        && entry.extended.containsKey(
                                DexKitPasscodeFingerprint.ANCHOR_READER_CLASS))
                + " setterInExtended=" + (entry.extended != null
                        && (entry.extended.containsKey(
                                DexKitPasscodeFingerprint.ANCHOR_SETTER_CLASS)
                        || entry.extended.containsKey(
                                DexKitPasscodeFingerprint.ANCHOR_SETTER_METHOD))));
        if (preflight.resolved() == 0 && !families.passcode) {
            SelfCheckRegistry.markStatus(FEATURE_OVERLAY, "stale", "overlay resolved nothing",
                    preflight.breakdown(), "");
            return Result.empty();
        }
        SymbolSchema.adoptForHooks(overlay, host.versionCode);
        int resolved = DexKitOverlay.countResolved(merged, entry.extended);
        SelfCheckRegistry.markStatus(FEATURE_OVERLAY, "ok",
                preflight.resolved() + "/" + preflight.total() + " families, "
                        + resolved + " dexkit anchors",
                preflight.breakdown(), "");
        Map<String, String> resolvedMap = new LinkedHashMap<>(entry.extended);
        return new Result(true, preflight, families, resolvedMap);
    }

    private static void maybeScanInBackground(Context context,
                                              DexKitZinstantResolver.HostIdentity host,
                                              DexKitCache.Entry previous, SymbolSchema.Active exact,
                                              SymbolSchema.Active fallback) {
        if (!SCAN_KICKED.compareAndSet(false, true)) {
            return;
        }
        if (!SCAN_RUNNING.compareAndSet(false, true)) {
            return;
        }
        final Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        final DexKitZinstantResolver.HostIdentity snapshot = host;
        final DexKitCache.Entry previousEntry = previous;
        final SymbolSchema.Active exactSnapshot = exact;
        final SymbolSchema.Active fallbackSnapshot = fallback;
        SelfCheckRegistry.markStatus(FEATURE_OVERLAY, "pending", "family scan started",
                "cold scan off the UI thread; applies at the next restart", "");
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runScan(appContext, snapshot, previousEntry, exactSnapshot, fallbackSnapshot);
                } catch (Throwable throwable) {
                    // An uncaught worker error must be visible as a terminal state, not a
                    // scan that looks like it is still running.
                    SelfCheckRegistry.markStatus(FEATURE_SCAN, "failed",
                            "family scan threw",
                            throwable.getClass().getSimpleName()
                                    + (throwable.getMessage() == null ? ""
                                    : " " + throwable.getMessage()), "");
                    SelfCheckRegistry.markStatus(FEATURE_OVERLAY, "failed",
                            "family scan threw",
                            throwable.getClass().getSimpleName(), "");
                } finally {
                    SCAN_RUNNING.set(false);
                }
            }
        }, "dexkit-family-scan");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Debug-only comparison entry. When {@code debug.zalopatch.dexkit_compare_apk} names a
     * retained APK and {@code debug.zalopatch.dexkit_compare_version} its versionCode, resolve
     * every anchor against that APK and diff it against the bundled exact profile for that
     * release. Returns true when it ran so the caller skips normal arming. Never touches the
     * cache, the artifact, or hook targets.
     */
    static boolean maybeRunComparison(Context context) {
        String apkPath = systemProperty("debug.zalopatch.dexkit_compare_apk");
        if (apkPath == null || apkPath.isEmpty()) {
            return false;
        }
        long versionCode;
        try {
            versionCode = Long.parseLong(
                    systemProperty("debug.zalopatch.dexkit_compare_version").trim());
        } catch (Throwable ignored) {
            versionCode = 0L;
        }
        try {
            compareAgainstBundledProfile(context, apkPath, versionCode);
        } catch (Throwable throwable) {
            XpLog.e("ZaloPatch: DexKit compare failed", throwable);
        }
        return true;
    }

    static void compareAgainstBundledProfile(Context context, String apkPath, long versionCode) {
        String loadError = DexKitBridgeRunner.ensureLoaded();
        if (loadError != null) {
            XpLog.i("ZaloPatch: DexKit compare unavailable: native_load_failed " + loadError);
            return;
        }
        File apk = new File(apkPath);
        if (!apk.isFile()) {
            XpLog.i("ZaloPatch: DexKit compare unavailable: apk missing " + apkPath);
            return;
        }
        String codeDigest;
        try {
            codeDigest = sha256(apk);
        } catch (Throwable throwable) {
            codeDigest = "";
        }
        ClassLoader targetLoader = context.getClassLoader();
        try {
            File optimized = new File(context.getCodeCacheDir(), "dexkit-compare");
            optimized.mkdirs();
            final ClassLoader parent = context.getClassLoader();
            // Child-first for app code: obfuscated names (e.g. tf1.w) exist in both the
            // installed and the retained release with different members; parent-first would
            // silently reflect the installed class. Framework and shared libs stay parent-first.
            targetLoader = new dalvik.system.DexClassLoader(
                    apkPath, optimized.getAbsolutePath(), null, parent) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve)
                        throws ClassNotFoundException {
                    synchronized (this) {
                        Class<?> cached = findLoadedClass(name);
                        if (cached != null) {
                            return cached;
                        }
                        boolean shared = name.startsWith("java.")
                                || name.startsWith("javax.")
                                || name.startsWith("android.")
                                || name.startsWith("androidx.")
                                || name.startsWith("kotlin.")
                                || name.startsWith("kotlinx.")
                                || name.startsWith("com.google.");
                        if (!shared) {
                            try {
                                Class<?> found = findClass(name);
                                if (resolve) {
                                    resolveClass(found);
                                }
                                return found;
                            } catch (ClassNotFoundException ignored) {
                            }
                        }
                        return super.loadClass(name, resolve);
                    }
                }
            };
        } catch (Throwable ignored) {
        }
        DexKitZinstantResolver.HostIdentity host = new DexKitZinstantResolver.HostIdentity(
                versionCode, 0L, apkPath, apk.length(), targetLoader);
        Map<String, DexKitBridgeRunner.QueryResult> results;
        Map<String, DexKitBridgeRunner.QueryResult> stringResults;
        try {
            results = DexKitBridgeRunner.scanMethods(apkPath, familySpecs());
            stringResults = DexKitBridgeRunner.scanStrings(apkPath, stringSpecs());
        } catch (Throwable throwable) {
            XpLog.e("ZaloPatch: DexKit compare scan failed", throwable);
            return;
        }
        boolean retained = DexKitZinstantFingerprint.isRetainedVersion(versionCode);
        Map<String, String> resolved = new LinkedHashMap<>();
        Map<String, String> anchorStatus = new LinkedHashMap<>();
        resolved.putAll(resolveWebview(host, codeDigest, results, retained, anchorStatus));
        resolved.putAll(resolvePasscode(codeDigest, stringResults, retained, anchorStatus));
        resolved.putAll(resolveTelemetry(results, stringResults, anchorStatus));
        resolved.putAll(resolveBottomTabs(context, host, anchorStatus, false));
        resolved.putAll(resolveMe(results, anchorStatus));
        resolved.putAll(resolveInbox(host, anchorStatus));
        resolved.putAll(resolveChat(host, results, anchorStatus));
        JSONObject symbols = SymbolSchema.bundledSymbolsForVersion(context, versionCode);
        int agree = 0;
        int disagree = 0;
        int missing = 0;
        int unprofiled = 0;
        for (DexKitAnchors.Anchor anchor : DexKitAnchors.all()) {
            String actual = resolved.get(anchor.path);
            if (actual == null || actual.isEmpty()) {
                missing++;
                continue;
            }
            String expected = expectedAnchorValue(symbols, anchor.path);
            if (expected.isEmpty()) {
                unprofiled++;
                XpLog.i("ZaloPatch: DexKit compare " + anchor.path
                        + " = " + actual + " (no profile value)");
            } else if (expected.equals(actual) || expected.equals(simpleName(actual))) {
                agree++;
            } else {
                disagree++;
                XpLog.i("ZaloPatch: DexKit compare " + anchor.path
                        + " dexkit=" + actual + " profile=" + expected);
            }
        }
        XpLog.i("ZaloPatch: DexKit compare summary version=" + versionCode
                + " anchors=" + DexKitAnchors.all().size()
                + " agree=" + agree + " disagree=" + disagree
                + " missing=" + missing + " unprofiled=" + unprofiled);
        XpLog.i("ZaloPatch: DexKit compare status " + anchorStatusForLog(anchorStatus));
    }

    /** Ground-truth value for one anchor, mapping flat bottom-tabs leaves onto the nested profile. */
    private static String expectedAnchorValue(JSONObject symbols, String path) {
        if (symbols == null || path == null) {
            return "";
        }
        if (path.startsWith("symbols.bottom_tabs.")) {
            JSONObject bottom = symbols.optJSONObject("bottom_tabs");
            if (bottom == null) {
                return "";
            }
            String leaf = path.substring("symbols.bottom_tabs.".length());
            JSONArray array = bottom.optJSONArray("current_tab_symbols");
            JSONObject definition = array != null && array.length() > 0
                    ? array.optJSONObject(0) : null;
            if (definition != null) {
                if (definition.has(leaf)) {
                    return definition.optString(leaf, "");
                }
                if (leaf.endsWith("_index_field")) {
                    JSONObject index = definition.optJSONObject("index_fields");
                    String role = leaf.substring(0, leaf.length() - "_index_field".length());
                    if (index != null && index.has(role)) {
                        return index.optString(role, "");
                    }
                }
                if (leaf.endsWith("_enabled_field")) {
                    JSONObject enabled = definition.optJSONObject("enabled_fields");
                    String role = leaf.substring(0, leaf.length() - "_enabled_field".length());
                    if (enabled != null && enabled.has(role)) {
                        return enabled.optString(role, "");
                    }
                }
            }
            JSONObject methods = bottom.optJSONObject("current_methods");
            if (methods != null && leaf.endsWith("_method")) {
                String role = leaf.substring(0, leaf.length() - "_method".length());
                if (methods.has(role)) {
                    return methods.optString(role, "");
                }
            }
            return "";
        }
        String[] parts = path.split("\\.");
        Object node = symbols;
        for (int index = 1; index < parts.length; index++) {
            if (!(node instanceof JSONObject)) {
                return "";
            }
            Object value = ((JSONObject) node).opt(parts[index]);
            if (index == parts.length - 1) {
                return value == null ? "" : String.valueOf(value);
            }
            node = value;
        }
        return "";
    }

    /** Method part of a {@code Class#method} descriptor, for profile values that store the name only. */
    private static String simpleName(String descriptor) {
        if (descriptor == null) {
            return "";
        }
        int hash = descriptor.lastIndexOf('#');
        return hash < 0 ? descriptor : descriptor.substring(hash + 1);
    }

    private static String systemProperty(String key) {        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Object value = systemProperties.getMethod("get", String.class, String.class)
                    .invoke(null, key, "");
            return value == null ? "" : value.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void runScan(Context context, DexKitZinstantResolver.HostIdentity host,
                                DexKitCache.Entry previous, SymbolSchema.Active exact,
                                SymbolSchema.Active fallback) {
        Set<String> pending = DexKitFamilyRetry.pending(previous);
        long started = System.nanoTime();
        android.os.Bundle claim = DexKitZinstantResolver.claimScan(context, host);
        for (int retry = 0; claim != null && !claim.getBoolean("allowed", false)
                && claim.getString("reason", "").contains("already in progress")
                && retry < CLAIM_RETRIES; retry++) {
            // A sibling process may have died holding the slot. The TTL releases it, so a
            // bounded wait-and-retry is enough; without it this process has already spent its
            // single attempt and would report pending forever.
            try {
                Thread.sleep(CLAIM_RETRY_WAIT_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            claim = DexKitZinstantResolver.claimScan(context, host);
        }
        if (claim == null || !claim.getBoolean("allowed", false)) {
            String reason = claim == null ? "scan unavailable"
                    : claim.getString("reason", "scan unavailable");
            boolean inProgress = reason.contains("already in progress");
            SelfCheckRegistry.markStatus(FEATURE_OVERLAY, inProgress ? "stale" : "stale",
                    inProgress ? "scan slot held beyond retry" : reason, "", "");
            SelfCheckRegistry.markStatus(FEATURE_SCAN, "stale",
                    inProgress ? "scan slot held beyond retry" : reason, "", "");
            return;
        }
        String loadError;
        try {
            loadError = DexKitBridgeRunner.ensureLoaded();
        } catch (Throwable throwable) {
            loadError = throwable.getClass().getSimpleName();
        }
        if (loadError != null) {
            DexKitZinstantResolver.recordFailure(context, host, "native_load_failed: " + loadError);
            SelfCheckRegistry.markStatus(FEATURE_OVERLAY, "stale", "native_load_failed",
                    loadError, "");
            return;
        }
        String codeDigest;
        try {
            codeDigest = sha256(new File(host.sourceDir));
        } catch (Throwable throwable) {
            DexKitZinstantResolver.recordFailure(context, host, "apk_hash_failed");
            SelfCheckRegistry.markStatus(FEATURE_OVERLAY, "stale", "apk_hash_failed",
                    throwable.getClass().getSimpleName(), "");
            return;
        }
        if (previous != null && !codeDigest.equals(previous.codeDigest)) {
            previous = null;
            pending = DexKitFamilyRetry.pending(null);
        }
        Map<String, DexKitBridgeRunner.QueryResult> results;
        try {
            List<DexKitBridgeRunner.MethodSpec> specs = new ArrayList<>();
            for (DexKitBridgeRunner.MethodSpec spec : familySpecs()) {
                if (pending.contains(queryFamily(spec.id))) specs.add(spec);
            }
            results = specs.isEmpty() ? new LinkedHashMap<>()
                    : DexKitBridgeRunner.scanMethods(host.sourceDir, specs);
        } catch (Throwable throwable) {
            results = new LinkedHashMap<>();
            for (DexKitBridgeRunner.MethodSpec spec : familySpecs()) {
                if (pending.contains(queryFamily(spec.id))) {
                    results.put(spec.id, new DexKitBridgeRunner.QueryResult(0, "query_error"));
                }
            }
        }
        Map<String, DexKitBridgeRunner.QueryResult> stringResults;
        try {
            List<DexKitBridgeRunner.StringSpec> specs = new ArrayList<>();
            for (DexKitBridgeRunner.StringSpec spec : stringSpecs()) {
                String family = queryFamily(spec.id);
                if (pending.contains(family)) specs.add(spec);
            }
            stringResults = specs.isEmpty() ? new LinkedHashMap<>()
                    : DexKitBridgeRunner.scanStrings(host.sourceDir, specs);
        } catch (Throwable throwable) {
            stringResults = new LinkedHashMap<>();
            for (DexKitBridgeRunner.StringSpec spec : stringSpecs()) {
                stringResults.put(spec.id, new DexKitBridgeRunner.QueryResult(0, "query_error"));
            }
        }
        XpLog.i("ZaloPatch: DexKit family scan matches "
                + matchSummary(results, stringResults));
        boolean retained = DexKitZinstantFingerprint.isRetainedVersion(host.versionCode);
        Map<String, DexKitFamilyRetry.State> states = previous == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(previous.families);
        DexKitCache.Entry pilotEntry = previous;
        if (pending.contains("zinstant")) {
            DexKitPilotPolicy.ScanInputs inputs = zinstantInputs(host, codeDigest, results,
                    previous == null ? 0 : previous.partialAttempts, started);
            boolean queryError = !inputs.adQueryError.isEmpty() || !inputs.feedQueryError.isEmpty();
            // Cache transient state explicitly, alongside unrelated healthy families.
            // A failed query retains only its previously preflight-approved descriptor.
            pilotEntry = DexKitFamilyRetry.pilotResult(inputs, previous);
            String status = queryError ? "query_error"
                    : (inputs.adStatus.startsWith("ambiguous")
                    || inputs.feedStatus.startsWith("ambiguous")) ? "ambiguous"
                    : DexKitCache.fullyResolved(pilotEntry) ? "resolved" : "no_match";
            states.put("zinstant", DexKitFamilyRetry.scanned(states.get("zinstant"), status));
        }
        Map<String, String> extended = previous == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(previous.extended);
        Map<String, String> anchorStatus = new LinkedHashMap<>();
        for (String family : pending) {
            if ("zinstant".equals(family)) continue;
            Map<String, String> found = new LinkedHashMap<>();
            try {
                switch (family) {
                    case "webview":
                        found = resolveWebview(host, codeDigest, results, retained, anchorStatus);
                        break;
                    case "passcode":
                    case "backup":
                        found = resolvePasscode(codeDigest, stringResults, retained, anchorStatus);
                        break;
                    case "telemetry":
                        found = resolveTelemetry(results, stringResults, anchorStatus);
                        break;
                    case "bottom_tabs":
                        found = resolveBottomTabs(context, host, anchorStatus, true);
                        break;
                    case "me": found = resolveMe(results, anchorStatus); break;
                    case "inbox": found = resolveInbox(host, anchorStatus); break;
                    case "call_recording":
                        found = resolveCallCallback(host, anchorStatus);
                        break;
                    case "media":
                        found = resolveMediaState(host, anchorStatus);
                        break;
                    case "chat": found = resolveChat(host, results, anchorStatus); break;
                    default: break;
                }
            } catch (Throwable error) {
                anchorStatus.put("symbols." + family + ".query", "query_error");
            }
            String status = DexKitFamilyRetry.status(family, found, anchorStatus);
            states.put(family, DexKitFamilyRetry.scanned(states.get(family), status));
            DexKitFamilyRetry.mergeScan(extended, found, family, status);
        }
        DexKitCache.Entry entry = DexKitFamilyRetry.withStates(
                DexKitCache.replaceExtended(pilotEntry, extended), states);
        // A negative pilot does not exempt independent families from live validation.
        entry = DexKitFamilyRetry.preflight(entry,
                preflightFilter(context, host, loaderOf(host), entry, exact, fallback));
        XpLog.i("ZaloPatch: DexKit anchor status " + anchorStatusForLog(anchorStatus));
        android.os.Bundle recorded = DexKitZinstantResolver.recordCache(
                context, DexKitCache.serialize(entry));
        String summary = describe(entry, stringResults, anchorStatus);
        if (recorded != null && recorded.getBoolean("recorded", false)) {
            SelfCheckRegistry.markStatus(FEATURE_OVERLAY, "pending", "cache ready",
                    summary + "; applies at the next restart", "");
            // Terminal scan lifecycle: the family worker owns the scan row, so pollers can
            // distinguish "running" from "done" instead of reading a kick-time snapshot.
            SelfCheckRegistry.markStatus(FEATURE_SCAN, "ok", "family scan complete",
                    summary + "; recorded, applies at the next restart", "");
        } else if (DexKitZinstantResolver.reportCacheFallback(
                context, DexKitCache.serialize(entry))) {
            SelfCheckRegistry.markStatus(FEATURE_OVERLAY, "pending", "fallback reported",
                    summary + "; sent by broadcast, applies after the module records it"
                            + " and Zalo restarts", "");
            SelfCheckRegistry.markStatus(FEATURE_SCAN, "ok", "family scan complete",
                    summary + "; delivered by broadcast", "");
        } else {
            DexKitZinstantResolver.recordFailure(context, host, "cache_rejected");
            SelfCheckRegistry.markStatus(FEATURE_OVERLAY, "stale", "cache_rejected", "", "");
            SelfCheckRegistry.markStatus(FEATURE_SCAN, "failed", "cache_rejected",
                    summary, "");
        }
    }

    private static String queryFamily(String id) {
        if (id.equals(DexKitZinstantFingerprint.ANCHOR_AD_BIND)
                || id.equals(DexKitZinstantFingerprint.ANCHOR_FEED_BIND)) return "zinstant";
        if (id.equals(DexKitWebviewFingerprint.ANCHOR_REDIRECT)
                || id.equals(DexKitWebviewFingerprint.ANCHOR_DISPATCH)) return "webview";
        if (id.equals(DexKitPasscodeFingerprint.QUERY_PASSCODE_CALLERS)) return "passcode";
        if (id.equals(DexKitPasscodeFingerprint.QUERY_BACKUP_CALLERS)) return "backup";
        if (id.equals(DexKitTelemetryFingerprint.QUERY_ACCESSORS)
                || id.startsWith(DexKitTelemetryFingerprint.QUERY_TABLE_PREFIX)) return "telemetry";
        if (id.equals(DexKitMeFingerprint.QUERY_BUILDERS)) return "me";
        if (id.equals(DexKitChatFingerprint.QUERY_SEND_SHAPES)) return "chat";
        return "";
    }

    private static List<DexKitBridgeRunner.StringSpec> stringSpecs() {
        List<DexKitBridgeRunner.StringSpec> specs = new ArrayList<>();
        specs.add(new DexKitBridgeRunner.StringSpec(
                DexKitPasscodeFingerprint.QUERY_PASSCODE_CALLERS,
                DexKitPasscodeFingerprint.PASSCODE_KEY));
        specs.add(new DexKitBridgeRunner.StringSpec(
                DexKitPasscodeFingerprint.QUERY_BACKUP_CALLERS,
                DexKitPasscodeFingerprint.BACKUP_KEY_PREFIX));
        for (Map.Entry<String, String> labelTable
                : DexKitTelemetryFingerprint.labelTables().entrySet()) {
            specs.add(new DexKitBridgeRunner.StringSpec(
                    DexKitTelemetryFingerprint.QUERY_TABLE_PREFIX + labelTable.getValue(),
                    "FROM " + labelTable.getValue()));
        }
        return specs;
    }

    private static List<DexKitBridgeRunner.MethodSpec> familySpecs() {
        List<DexKitBridgeRunner.MethodSpec> specs = new ArrayList<>();
        specs.add(new DexKitBridgeRunner.MethodSpec(DexKitZinstantFingerprint.ANCHOR_AD_BIND,
                DexKitZinstantFingerprint.expectedOwner(DexKitZinstantFingerprint.ANCHOR_AD_BIND),
                DexKitZinstantFingerprint.RETURN_VOID,
                DexKitZinstantFingerprint.expectedParamCount(
                        DexKitZinstantFingerprint.ANCHOR_AD_BIND)));
        specs.add(new DexKitBridgeRunner.MethodSpec(DexKitZinstantFingerprint.ANCHOR_FEED_BIND,
                DexKitZinstantFingerprint.expectedOwner(
                        DexKitZinstantFingerprint.ANCHOR_FEED_BIND),
                DexKitZinstantFingerprint.RETURN_VOID,
                DexKitZinstantFingerprint.expectedParamCount(
                        DexKitZinstantFingerprint.ANCHOR_FEED_BIND)));
        specs.add(new DexKitBridgeRunner.MethodSpec(DexKitWebviewFingerprint.ANCHOR_REDIRECT,
                DexKitWebviewFingerprint.OWNER_WEB_VIEW,
                DexKitWebviewFingerprint.TYPE_URI, 1));
        specs.add(new DexKitBridgeRunner.MethodSpec(DexKitWebviewFingerprint.ANCHOR_DISPATCH,
                null, DexKitWebviewFingerprint.TYPE_VOID, 6));
        specs.add(new DexKitBridgeRunner.MethodSpec(DexKitTelemetryFingerprint.QUERY_ACCESSORS,
                DexKitTelemetryFingerprint.OWNER_DB_IMPL, null, 0));
        specs.add(new DexKitBridgeRunner.MethodSpec(DexKitMeFingerprint.QUERY_BUILDERS,
                DexKitMeFingerprint.OWNER_TAB_ME, null, 1));
        // Chat repository: both send shapes are void/4; parameter types discriminate.
        specs.add(new DexKitBridgeRunner.MethodSpec(DexKitChatFingerprint.QUERY_SEND_SHAPES,
                null, "void", 4));
        return specs;
    }

    private static DexKitPilotPolicy.ScanInputs zinstantInputs(
            DexKitZinstantResolver.HostIdentity host, String codeDigest,
            Map<String, DexKitBridgeRunner.QueryResult> results,
            int previousPartialAttempts, long started) {
        DexKitBridgeRunner.QueryResult ad = results.get(DexKitZinstantFingerprint.ANCHOR_AD_BIND);
        DexKitBridgeRunner.QueryResult feed =
                results.get(DexKitZinstantFingerprint.ANCHOR_FEED_BIND);
        if (ad == null) {
            ad = new DexKitBridgeRunner.QueryResult(0, "not run");
        }
        if (feed == null) {
            feed = new DexKitBridgeRunner.QueryResult(0, "not run");
        }
        boolean retained = DexKitZinstantFingerprint.isRetainedVersion(host.versionCode);
        FingerprintResolver.Resolution adResolution = DexKitZinstantFingerprint.evaluate(
                DexKitZinstantFingerprint.ANCHOR_AD_BIND,
                DexKitZinstantResolver.withViewFlags(
                        host.loader, DexKitZinstantFingerprint.ANCHOR_AD_BIND, ad),
                codeDigest, codeDigest, retained);
        FingerprintResolver.Resolution feedResolution = DexKitZinstantFingerprint.evaluate(
                DexKitZinstantFingerprint.ANCHOR_FEED_BIND,
                DexKitZinstantResolver.withViewFlags(
                        host.loader, DexKitZinstantFingerprint.ANCHOR_FEED_BIND, feed),
                codeDigest, codeDigest, retained);
        DexKitPilotPolicy.ScanInputs inputs = new DexKitPilotPolicy.ScanInputs();
        inputs.adStatus = adResolution.status;
        inputs.adSymbol = adResolution.symbol;
        inputs.feedStatus = feedResolution.status;
        inputs.feedSymbol = feedResolution.symbol;
        inputs.adQueryError = ad.error;
        inputs.feedQueryError = feed.error;
        inputs.matchAd = ad.matchCount;
        inputs.matchFeed = feed.matchCount;
        inputs.versionCode = host.versionCode;
        inputs.codeDigest = codeDigest;
        inputs.lastUpdateTime = host.lastUpdateTime;
        try {
            inputs.apkSize = new File(host.sourceDir).length();
        } catch (Throwable ignored) {
            inputs.apkSize = -1L;
        }
        inputs.queryRevision = DexKitZinstantFingerprint.QUERY_REVISION;
        inputs.resolverFormat = DexKitCache.RESOLVER_FORMAT;
        inputs.moduleVersion = BuildConfig.VERSION_CODE;
        inputs.durationMs = (System.nanoTime() - started) / 1000000L;
        inputs.scannedAt = System.currentTimeMillis();
        inputs.previousPartialAttempts = previousPartialAttempts;
        return inputs;
    }

    private static Map<String, String> resolveWebview(DexKitZinstantResolver.HostIdentity host,
            String codeDigest, Map<String, DexKitBridgeRunner.QueryResult> results,
            boolean retained, Map<String, String> anchorStatus) {
        Map<String, String> resolved = new LinkedHashMap<>();
        DexKitBridgeRunner.QueryResult redirect =
                results.get(DexKitWebviewFingerprint.ANCHOR_REDIRECT);
        DexKitBridgeRunner.QueryResult dispatch =
                results.get(DexKitWebviewFingerprint.ANCHOR_DISPATCH);
        if (redirect == null || dispatch == null) {
            if (anchorStatus != null) {
                anchorStatus.put(DexKitWebviewFingerprint.ANCHOR_REDIRECT, "query_missing");
                anchorStatus.put(DexKitWebviewFingerprint.ANCHOR_DISPATCH, "query_missing");
            }
            return resolved;
        }
        if (!redirect.error.isEmpty() || !dispatch.error.isEmpty()) {
            if (anchorStatus != null) {
                anchorStatus.put(DexKitWebviewFingerprint.ANCHOR_REDIRECT, redirect.error);
                anchorStatus.put(DexKitWebviewFingerprint.ANCHOR_DISPATCH, dispatch.error);
            }
            return resolved;
        }
        FingerprintResolver.Resolution redirectResolution =
                DexKitWebviewFingerprint.evaluateRedirect(
                        webviewHits(host.loader, redirect), codeDigest, codeDigest, retained);
        FingerprintResolver.Resolution companionResolution =
                DexKitWebviewFingerprint.evaluateCompanion(
                        webviewHits(host.loader, dispatch), codeDigest, codeDigest, retained);
        if (!"resolved".equals(redirectResolution.status)
                || !"resolved".equals(companionResolution.status)) {
            if (anchorStatus != null) {
                anchorStatus.put(DexKitWebviewFingerprint.ANCHOR_REDIRECT,
                        redirectResolution.status);
                anchorStatus.put(DexKitWebviewFingerprint.ANCHOR_COMPANION,
                        companionResolution.status);
            }
            return resolved;
        }
        FingerprintResolver.Resolution dispatchResolution =
                DexKitWebviewFingerprint.evaluateDispatch(companionResolution.symbol,
                        webviewHits(host.loader, dispatch), codeDigest, codeDigest, retained);
        if (anchorStatus != null) {
            anchorStatus.put(DexKitWebviewFingerprint.ANCHOR_REDIRECT, redirectResolution.status);
            anchorStatus.put(DexKitWebviewFingerprint.ANCHOR_COMPANION,
                    companionResolution.status);
            anchorStatus.put(DexKitWebviewFingerprint.ANCHOR_DISPATCH,
                    dispatchResolution.status);
        }
        if (!"resolved".equals(dispatchResolution.status)) {
            return resolved;
        }
        resolved.put(DexKitWebviewFingerprint.ANCHOR_REDIRECT, redirectResolution.symbol);
        resolved.put(DexKitWebviewFingerprint.ANCHOR_COMPANION, companionResolution.symbol);
        resolved.put(DexKitWebviewFingerprint.ANCHOR_DISPATCH, dispatchResolution.symbol);
        return resolved;
    }

    /**
     * Resolves the passcode and backup anchors from string-anchored caller linkage.
     * Family coherence: reader, setter, and backup resolve together per their pairing
     * rules; a partial set resolves nothing.
     */
    private static Map<String, String> resolvePasscode(String codeDigest,
            Map<String, DexKitBridgeRunner.QueryResult> stringResults, boolean retained,
            Map<String, String> anchorStatus) {
        Map<String, String> resolved = new LinkedHashMap<>();
        DexKitBridgeRunner.QueryResult passcode =
                stringResults.get(DexKitPasscodeFingerprint.QUERY_PASSCODE_CALLERS);
        DexKitBridgeRunner.QueryResult backup =
                stringResults.get(DexKitPasscodeFingerprint.QUERY_BACKUP_CALLERS);
        boolean passcodeError = passcode == null || !passcode.error.isEmpty();
        boolean backupError = backup == null || !backup.error.isEmpty();
        if (passcodeError) passcode = new DexKitBridgeRunner.QueryResult(0, "query_error");
        if (backupError) backup = new DexKitBridgeRunner.QueryResult(0, "query_error");
        FingerprintResolver.Resolution reader =
                DexKitPasscodeFingerprint.evaluateReader(
                        callerHits(passcode), codeDigest, codeDigest, retained);
        FingerprintResolver.Resolution setter =
                DexKitPasscodeFingerprint.evaluateSetter(
                        callerHits(passcode),
                        "resolved".equals(reader.status) ? reader.symbol : "",
                        codeDigest, codeDigest, retained);
        FingerprintResolver.Resolution backupMethod =
                DexKitPasscodeFingerprint.evaluateBackup(
                        callerHits(backup), codeDigest, codeDigest, retained);
        if (anchorStatus != null) {
            anchorStatus.put("symbols.passcode.reader", passcodeError ? "query_error" : reader.status);
            anchorStatus.put("symbols.passcode.setter", setter.status);
            anchorStatus.put("symbols.backup.reader", backupError ? "query_error" : backupMethod.status);
        }
        // Each family can recover without withholding a healthy sibling.
        String[] readerParts = DexKitPasscodeFingerprint.splitIdentity(reader.symbol);
        String[] setterParts = DexKitPasscodeFingerprint.splitIdentity(setter.symbol);
        String[] backupParts = DexKitPasscodeFingerprint.splitIdentity(backupMethod.symbol);
        if (!passcodeError && "resolved".equals(reader.status)
                && !readerParts[0].isEmpty() && !readerParts[1].isEmpty()) {
            resolved.put(DexKitPasscodeFingerprint.ANCHOR_READER_CLASS, readerParts[0]);
            resolved.put(DexKitPasscodeFingerprint.ANCHOR_READER_METHOD, readerParts[1]);
            if ("resolved".equals(setter.status) && !setterParts[0].isEmpty()
                    && !setterParts[1].isEmpty()) {
                resolved.put(DexKitPasscodeFingerprint.ANCHOR_SETTER_CLASS, setterParts[0]);
                resolved.put(DexKitPasscodeFingerprint.ANCHOR_SETTER_METHOD, setterParts[1]);
            }
        }
        if (!backupError && "resolved".equals(backupMethod.status)
                && !backupParts[0].isEmpty() && !backupParts[1].isEmpty()) {
            resolved.put(DexKitPasscodeFingerprint.ANCHOR_BACKUP_CLASS, backupParts[0]);
            resolved.put(DexKitPasscodeFingerprint.ANCHOR_BACKUP_METHOD, backupParts[1]);
        }
        return resolved;
    }

    /**
     * Resolves the telemetry analytics-DAO accessors through table linkage: each
     * stable Room table's SELECTs must live in exactly one accessor's DAO type,
     * and the four labels must map to four distinct accessors. Family coherence:
     * partial sets resolve nothing.
     */
    private static Map<String, String> resolveTelemetry(
            Map<String, DexKitBridgeRunner.QueryResult> results,
            Map<String, DexKitBridgeRunner.QueryResult> stringResults,
            Map<String, String> anchorStatus) {
        Map<String, String> resolved = new LinkedHashMap<>();
        DexKitBridgeRunner.QueryResult accessors =
                results.get(DexKitTelemetryFingerprint.QUERY_ACCESSORS);
        if (accessors == null || !accessors.error.isEmpty()) {
            if (anchorStatus != null) {
                anchorStatus.put("symbols.telemetry.dao",
                        accessors == null ? "query_missing" : accessors.error);
            }
            return resolved;
        }
        List<DexKitTelemetryFingerprint.AccessorHit> hits = new ArrayList<>();
        for (DexKitBridgeRunner.RawHit raw : accessors.hits) {
            if (raw.paramCount != 0) {
                continue;
            }
            hits.add(new DexKitTelemetryFingerprint.AccessorHit(
                    raw.className, raw.methodName, raw.returnTypeName));
        }
        Map<String, Set<String>> tableUsers = new LinkedHashMap<>();
        boolean tablesOk = true;
        for (Map.Entry<String, String> labelTable
                : DexKitTelemetryFingerprint.labelTables().entrySet()) {
            DexKitBridgeRunner.QueryResult table =
                    stringResults.get(DexKitTelemetryFingerprint.QUERY_TABLE_PREFIX
                            + labelTable.getValue());
            if (table == null || !table.error.isEmpty()) {
                tablesOk = false;
                if (anchorStatus != null) {
                    anchorStatus.put("symbols.telemetry.table." + labelTable.getValue(),
                            table == null ? "query_missing" : table.error);
                }
                continue;
            }
            Set<String> users = new java.util.LinkedHashSet<>();
            for (DexKitBridgeRunner.RawHit raw : table.hits) {
                users.add(DexKitTelemetryFingerprint.normalize(raw.className));
            }
            tableUsers.put(labelTable.getValue(), users);
        }
        if (!tablesOk) {
            return resolved;
        }
        Map<String, String> diagnosis =
                DexKitTelemetryFingerprint.diagnose(hits, tableUsers);
        if (anchorStatus != null) {
            for (Map.Entry<String, String> labelTable
                    : DexKitTelemetryFingerprint.labelTables().entrySet()) {
                anchorStatus.put("symbols.telemetry." + labelTable.getKey(),
                        diagnosis.get(labelTable.getKey()));
            }
        }
        return DexKitTelemetryFingerprint.evaluate(hits, tableUsers);
    }

    /**
     * Live bottom-tabs checks for family arming: the resolved enum must declare a
     * GROUP constant, and calling the resolved index getters on the singleton must
     * not contradict the letter roles. Inconclusive calibration (uninitialized or
     * hidden-tab state) abstains rather than vetoing; contradiction vetoes.
     */
    private static boolean tabsLiveChecks(ClassLoader loader, Map<String, String> extended) {
        if (extended == null
                || !extended.containsKey(DexKitBottomTabsFingerprint.ANCHOR_ENUM_CLASS)) {
            return false;
        }
        try {
            Class<?> enumClass = Class.forName(
                    extended.get(DexKitBottomTabsFingerprint.ANCHOR_ENUM_CLASS),
                    false, loader);
            enumClass.getField("GROUP");
        } catch (Throwable ignored) {
            return false;
        }
        String calibration = calibrateBottomTabs(loader, extended);
        return extended.containsKey(DexKitBottomTabsFingerprint.ANCHOR_LIST)
                ? "full".equals(calibration) : !"contradicted".equals(calibration);
    }

    /**
     * Calls the resolved index getters on the state singleton and compares values
     * against letter roles. Returns "full", "contradicted", or "inconclusive".
     * Any reflection failure abstains.
     */
    private static String calibrateBottomTabs(ClassLoader loader, Map<String, String> extended) {
        return calibrateBottomTabsDetailed(loader, extended).status;
    }

    /** Calibration outcome plus measured values for scan forensics. */
    private static final class Calibration {
        final String status;
        final String values;

        Calibration(String status, String values) {
            this.status = status == null ? "inconclusive" : status;
            this.values = values == null ? "" : values;
        }
    }

    private static Calibration calibrateBottomTabsDetailed(ClassLoader loader,
                                                           Map<String, String> extended) {
        if (extended == null || loader == null) {
            return new Calibration("inconclusive", "");
        }
        try {
            String stateClass = extended.get(DexKitBottomTabsFingerprint.ANCHOR_STATE_CLASS);
            String singleton = extended.get(DexKitBottomTabsFingerprint.ANCHOR_SINGLETON);
            if (stateClass == null || stateClass.isEmpty()
                    || singleton == null || singleton.isEmpty()) {
                return new Calibration("inconclusive", "");
            }
            Class<?> state = Class.forName(stateClass, false, loader);
            java.lang.reflect.Method singletonMethod = state.getDeclaredMethod(singleton);
            singletonMethod.setAccessible(true);
            Object instance = singletonMethod.invoke(null);
            if (instance == null) {
                return new Calibration("inconclusive", "");
            }
            if (extended.containsKey(DexKitBottomTabsFingerprint.ANCHOR_LIST)) {
                Map<String, Integer> indexes = new LinkedHashMap<>();
                for (String role : DexKitBottomTabsFingerprint.INDEX_ROLES) {
                    java.lang.reflect.Field field = state.getDeclaredField(extended.get(
                            DexKitBottomTabsFingerprint.fieldAnchorForIndexRole(role)));
                    field.setAccessible(true);
                    indexes.put(role, field.getInt(instance));
                }
                java.lang.reflect.Field list = state.getDeclaredField(
                        extended.get(DexKitBottomTabsFingerprint.ANCHOR_LIST));
                list.setAccessible(true);
                List<?> tabs = (List<?>) list.get(instance);
                List<String> names = new ArrayList<>();
                for (Object tab : tabs) {
                    if (!(tab instanceof Enum<?>)) return new Calibration("contradicted", "non-enum tab");
                    names.add(((Enum<?>) tab).name());
                }
                Map<String, Boolean> enabled = new LinkedHashMap<>();
                for (String role : DexKitBottomTabsFingerprint.ENABLED_ROLES) {
                    java.lang.reflect.Field field = state.getDeclaredField(extended.get(
                            DexKitBottomTabsFingerprint.fieldAnchorForEnabledRole(role)));
                    field.setAccessible(true);
                    enabled.put(role, field.getBoolean(instance));
                }
                java.lang.reflect.Field icons = state.getDeclaredField(
                        extended.get("symbols.bottom_tabs.icons_field"));
                java.lang.reflect.Field preloaded = state.getDeclaredField(
                        extended.get("symbols.bottom_tabs.preloaded_field"));
                icons.setAccessible(true);
                preloaded.setAccessible(true);
                int[] iconValues = (int[]) icons.get(instance);
                boolean[] preloadValues = (boolean[]) preloaded.get(instance);
                if (iconValues == null || preloadValues == null
                        || iconValues.length != tabs.size() || preloadValues.length != tabs.size()) {
                    return new Calibration("contradicted", "tab arrays/list mismatch");
                }
                return new Calibration(DexKitBottomTabsFingerprint.calibrateState(names, indexes, enabled),
                        names.toString() + " " + indexes + " " + enabled);
            }
            Map<String, String> roles = new LinkedHashMap<>();
            for (String role : DexKitBottomTabsFingerprint.INDEX_ROLES) {
                String method = extended.get(
                        DexKitBottomTabsFingerprint.anchorForIndexRole(role));
                if (method != null && !method.isEmpty()) {
                    roles.put(role, method);
                }
            }
            Map<String, Integer> values = new LinkedHashMap<>();
            StringBuilder measured = new StringBuilder();
            for (Map.Entry<String, String> role : roles.entrySet()) {
                try {
                    java.lang.reflect.Method getter =
                            state.getDeclaredMethod(role.getValue());
                    getter.setAccessible(true);
                    Object value = getter.invoke(instance);
                    if (value instanceof Integer) {
                        values.put(role.getValue(), (Integer) value);
                        if (measured.length() > 0) {
                            measured.append(' ');
                        }
                        measured.append(role.getValue()).append('=').append(value);
                    }
                } catch (Throwable ignored) {
                }
            }
            return new Calibration(
                    DexKitBottomTabsFingerprint.calibrate(values, roles),
                    measured.toString());
        } catch (Throwable ignored) {
            return new Calibration("inconclusive", "");
        }
    }

    /**
     * Resolves the bottom-tabs state family: DexKit class prefilter, signature
     * scoring, runtime field layout, letter-gated mapping, enum GROUP check, and
     * live calibration. Every stage fails closed with a recorded status.
     */
    private static Map<String, String> resolveBottomTabs(Context context,
            DexKitZinstantResolver.HostIdentity host, Map<String, String> anchorStatus,
            boolean calibrate) {
        Map<String, String> resolved = new LinkedHashMap<>();
        java.util.Map<String, DexKitBridgeRunner.ClassQueryResult> classes;
        try {
            classes = DexKitBridgeRunner.scanClasses(host.sourceDir,
                    java.util.Collections.singletonList(
                            new DexKitBridgeRunner.ClassSpec(
                                    DexKitBottomTabsFingerprint.QUERY_STATE_CANDIDATES)));
        } catch (Throwable throwable) {
            if (anchorStatus != null) {
                anchorStatus.put("symbols.bottom_tabs.state", "bridge_failed");
            }
            return resolved;
        }
        DexKitBridgeRunner.ClassQueryResult candidates =
                classes.get(DexKitBottomTabsFingerprint.QUERY_STATE_CANDIDATES);
        if (candidates == null || !candidates.error.isEmpty()) {
            if (anchorStatus != null) {
                anchorStatus.put("symbols.bottom_tabs.state",
                        candidates == null ? "query_missing" : candidates.error);
            }
            return resolved;
        }
        if (anchorStatus != null) {
            anchorStatus.put("symbols.bottom_tabs.candidates",
                    String.valueOf(candidates.classNames.size()));
            StringBuilder names = new StringBuilder();
            for (int index = 0; index < candidates.classNames.size() && index < 8; index++) {
                if (names.length() > 0) {
                    names.append(',');
                }
                names.append(candidates.classNames.get(index));
            }
            anchorStatus.put("symbols.bottom_tabs.candidate_names", names.toString());
        }
        if (candidates.classNames.isEmpty() || candidates.classNames.size() > 150) {
            if (anchorStatus != null) {
                anchorStatus.put("symbols.bottom_tabs.state",
                        candidates.classNames.isEmpty()
                                ? "no_candidates" : "too_many_candidates");
            }
            return resolved;
        }
        java.util.Map<String, DexKitBridgeRunner.QueryResult> dumps;
        try {
            java.util.List<DexKitBridgeRunner.MethodSpec> specs = new ArrayList<>();
            for (String candidate : candidates.classNames) {
                specs.add(new DexKitBridgeRunner.MethodSpec(
                        DexKitBottomTabsFingerprint.QUERY_STATE_CANDIDATES + "." + candidate,
                        candidate, null, null, false));
            }
            dumps = DexKitBridgeRunner.scanMethods(host.sourceDir, specs);
        } catch (Throwable throwable) {
            if (anchorStatus != null) {
                anchorStatus.put("symbols.bottom_tabs.state", "bridge_failed");
            }
            return resolved;
        }
        Map<String, List<DexKitBottomTabsFingerprint.MethodHit>> plain = new LinkedHashMap<>();
        int dumpErrors = 0;
        int dumpHits = 0;
        for (String candidate : candidates.classNames) {
            DexKitBridgeRunner.QueryResult dump = dumps.get(
                    DexKitBottomTabsFingerprint.QUERY_STATE_CANDIDATES + "." + candidate);
            if (dump == null || !dump.error.isEmpty()) {
                dumpErrors++;
                continue;
            }
            dumpHits += dump.hits.size();
            List<DexKitBottomTabsFingerprint.MethodHit> hits = new ArrayList<>();
            for (DexKitBridgeRunner.RawHit raw : dump.hits) {
                hits.add(new DexKitBottomTabsFingerprint.MethodHit(
                        raw.className, raw.methodName, raw.returnTypeName,
                        raw.paramTypeNames, raw.isStatic,
                        new ArrayList<String>(), new ArrayList<String>()));
            }
            plain.put(candidate, hits);
        }
        if (anchorStatus != null) {
            anchorStatus.put("symbols.bottom_tabs.dump_errors", String.valueOf(dumpErrors));
            anchorStatus.put("symbols.bottom_tabs.dump_hits", String.valueOf(dumpHits));
        }
        String stateClass = DexKitBottomTabsFingerprint.selectStateClass(plain);
        if (anchorStatus != null) {
            anchorStatus.put("symbols.bottom_tabs.selected",
                    stateClass.isEmpty() ? "none" : stateClass);
            StringBuilder shapes = new StringBuilder();
            for (Map.Entry<String, String> shape
                    : DexKitBottomTabsFingerprint.shapeSummary(plain).entrySet()) {
                if (shapes.length() > 0) {
                    shapes.append(' ');
                }
                shapes.append(shortAnchor(shape.getKey())).append('=').append(shape.getValue());
            }
            anchorStatus.put("symbols.bottom_tabs.shapes", shapes.toString());
        }
        if (stateClass.isEmpty()) {
            if (anchorStatus != null) {
                anchorStatus.put("symbols.bottom_tabs.state", "ambiguous_or_no_shape_match");
            }
            return resolved;
        }
        java.util.Map<String, DexKitBridgeRunner.QueryResult> detailed;
        try {
            detailed = DexKitBridgeRunner.scanMethods(host.sourceDir,
                    java.util.Collections.singletonList(
                            new DexKitBridgeRunner.MethodSpec(
                                    DexKitBottomTabsFingerprint.QUERY_STATE_CANDIDATES
                                            + ".detail",
                                    stateClass, null, null, true)));
        } catch (Throwable throwable) {
            if (anchorStatus != null) {
                anchorStatus.put("symbols.bottom_tabs.state", "bridge_failed");
            }
            return resolved;
        }
        DexKitBridgeRunner.QueryResult detail = detailed.get(
                DexKitBottomTabsFingerprint.QUERY_STATE_CANDIDATES + ".detail");
        if (detail == null || !detail.error.isEmpty()) {
            if (anchorStatus != null) {
                anchorStatus.put("symbols.bottom_tabs.state",
                        detail == null ? "query_missing" : detail.error);
            }
            return resolved;
        }
        List<DexKitBottomTabsFingerprint.MethodHit> methods = new ArrayList<>();
        for (DexKitBridgeRunner.RawHit raw : detail.hits) {
            methods.add(new DexKitBottomTabsFingerprint.MethodHit(
                    raw.className, raw.methodName, raw.returnTypeName,
                    raw.paramTypeNames, raw.isStatic, raw.usedFields,
                    invokedNames(raw)));
        }
        DexKitBottomTabsFingerprint.FieldLayout layout =
                reflectFieldLayout(host.loader, stateClass);
        if (layout == null) {
            if (anchorStatus != null) {
                anchorStatus.put("symbols.bottom_tabs.state", "layout_unavailable");
            }
            return resolved;
        }
        DexKitBottomTabsFingerprint.Resolution resolution =
                DexKitBottomTabsFingerprint.resolve(stateClass, methods, layout);
        if (!resolution.resolved()) {
            if (anchorStatus != null) {
                anchorStatus.put("symbols.bottom_tabs.state", resolution.status);
                if ("field_shape_changed".equals(resolution.status)) {
                    anchorStatus.put("symbols.bottom_tabs.layout_ints",
                            layout.intFields.toString());
                    anchorStatus.put("symbols.bottom_tabs.layout_bools",
                            layout.boolFields.toString());
                    anchorStatus.put("symbols.bottom_tabs.layout_arrays",
                            layout.intArrayField + "/" + layout.boolArrayField + "/"
                                    + layout.hasListField);
                }
            }
            return resolved;
        }
        if (!enumHasGroup(host.loader, resolution.enumClass)) {
            if (anchorStatus != null) {
                anchorStatus.put("symbols.bottom_tabs.state", "enum_group_missing");
            }
            return resolved;
        }
        Calibration calibration = calibrate
                ? calibrateBottomTabsDetailed(host.loader, toExtended(resolution))
                : null;
        if (anchorStatus != null) {
            if (calibration == null) {
                anchorStatus.put("symbols.bottom_tabs.calibration", "skipped");
            } else {
                anchorStatus.put("symbols.bottom_tabs.calibration", calibration.status);
                if (!calibration.values.isEmpty()) {
                    anchorStatus.put("symbols.bottom_tabs.values", calibration.values);
                }
            }
        }
        if (calibration != null && ("contradicted".equals(calibration.status)
                || (resolution.anchors.containsKey(DexKitBottomTabsFingerprint.ANCHOR_LIST)
                && !"full".equals(calibration.status)))) {
            if (anchorStatus != null) {
                anchorStatus.put("symbols.bottom_tabs.state", "calibration_contradicted");
            }
            return resolved;
        }
        resolved.putAll(resolution.anchors);
        return resolved;
    }

    private static List<String> invokedNames(DexKitBridgeRunner.RawHit raw) {
        List<String> names = new ArrayList<>();
        if (raw == null || raw.invoked == null) {
            return names;
        }
        for (DexKitBridgeRunner.RawHit callee : raw.invoked) {
            names.add(callee.className + "#" + callee.methodName);
        }
        return names;
    }

    private static Map<String, String> toExtended(
            DexKitBottomTabsFingerprint.Resolution resolution) {
        Map<String, String> extended = new LinkedHashMap<>();
        if (resolution == null) {
            return extended;
        }
        extended.putAll(resolution.anchors);
        return extended;
    }

    /** Runtime field layout in declaration order; null when the shape mismatches. */
    private static DexKitBottomTabsFingerprint.FieldLayout reflectFieldLayout(
            ClassLoader loader, String stateClass) {
        if (loader == null || stateClass == null || stateClass.isEmpty()) {
            return null;
        }
        try {
            Class<?> state = Class.forName(stateClass, false, loader);
            List<String> ints = new ArrayList<>();
            List<String> bools = new ArrayList<>();
            String intArray = "";
            String boolArray = "";
            String list = "";
            for (java.lang.reflect.Field field : state.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> type = field.getType();
                if (type == Integer.TYPE) {
                    ints.add(field.getName());
                } else if (type == Boolean.TYPE) {
                    bools.add(field.getName());
                } else if (type == int[].class) {
                    if (!intArray.isEmpty()) {
                        return null;
                    }
                    intArray = field.getName();
                } else if (type == boolean[].class) {
                    if (!boolArray.isEmpty()) {
                        return null;
                    }
                    boolArray = field.getName();
                } else if (java.util.List.class.isAssignableFrom(type)) {
                    if (!list.isEmpty()) return null;
                    list = field.getName();
                }
            }
            return new DexKitBottomTabsFingerprint.FieldLayout(
                    ints, bools, intArray, boolArray, list);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean enumHasGroup(ClassLoader loader, String enumClass) {
        if (loader == null || enumClass == null || enumClass.isEmpty()) {
            return false;
        }
        try {
            Class.forName(enumClass, false, loader).getField("GROUP");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Resolves the Me TabMe-builder: the unique list-returning one-param method on
     * the stable owner. Item classes, fields, and ids are runtime-derived, never
     * resolved here.
     */
    private static Map<String, String> resolveChat(
            DexKitZinstantResolver.HostIdentity host,
            Map<String, DexKitBridgeRunner.QueryResult> results,
            Map<String, String> anchorStatus) {
        Map<String, String> resolved = new LinkedHashMap<>();
        DexKitBridgeRunner.QueryResult query =
                results.get(DexKitChatFingerprint.QUERY_SEND_SHAPES);
        if (query == null || !query.error.isEmpty()) {
            if (anchorStatus != null) {
                anchorStatus.put("symbols.chat.repository",
                        query == null ? "query_missing" : query.error);
            }
            return resolved;
        }
        List<DexKitChatFingerprint.MethodHit> hits = new ArrayList<>();
        for (DexKitBridgeRunner.RawHit raw : query.hits) {
            hits.add(new DexKitChatFingerprint.MethodHit(
                    raw.className, raw.methodName, raw.paramTypeNames));
        }
        DexKitChatFingerprint.Resolution resolution = DexKitChatFingerprint.evaluate(hits);
        if (anchorStatus != null) {
            anchorStatus.put("symbols.chat.repository", resolution.status);
        }
        if (!resolution.resolved()) {
            return resolved;
        }
        resolved.put(DexKitChatFingerprint.ANCHOR_REPOSITORY_CLASS, resolution.repositoryClass);
        resolved.put(DexKitChatFingerprint.ANCHOR_ACK_METHOD, resolution.ackMethod);
        resolved.put(DexKitChatFingerprint.ANCHOR_TYPING_METHOD, resolution.typingMethod);
        resolved.putAll(resolveSeenQueue(host, resolution.repositoryClass, anchorStatus));
        return resolved;
    }

    private static Map<String, String> resolveSeenQueue(
            DexKitZinstantResolver.HostIdentity host, String repositoryName,
            Map<String, String> anchorStatus) {
        Map<String, String> selected = new LinkedHashMap<>();
        String status = "no_manager";
        try {
            DexKitBridgeRunner.QueryResult managers = DexKitBridgeRunner.scanStrings(
                    host.sourceDir, java.util.Collections.singletonList(
                            new DexKitBridgeRunner.StringSpec("chat.manager", "SendSeenManager",
                                    false))).get("chat.manager");
            if (managers == null || !managers.error.isEmpty()) {
                anchorStatus.put("symbols.chat.queue", "query_error");
                return selected;
            }
            Class<?> repository = Class.forName(repositoryName, false, host.loader);
            Set<String> owners = new java.util.LinkedHashSet<>();
            for (DexKitBridgeRunner.RawHit hit : managers.hits) owners.add(hit.className);
            int matches = 0;
            for (String owner : owners) {
                Class<?> manager = Class.forName(owner, false, host.loader);
                Map<String, String> candidate = DexKitChatFingerprint.queueShape(manager, repository);
                if (candidate.isEmpty()) continue;
                if (++matches > 1) {
                    selected.clear();
                    status = "ambiguous_manager";
                    break;
                }
                selected.putAll(candidate);
            }
            if (!selected.isEmpty()) {
                String manager = selected.get(DexKitChatFingerprint.ANCHOR_MANAGER);
                DexKitBridgeRunner.QueryResult guards = DexKitBridgeRunner.scanMethods(
                        host.sourceDir, java.util.Collections.singletonList(
                                new DexKitBridgeRunner.MethodSpec("chat.seen_guard", manager,
                                        "void", 1, true, 3, false))).get("chat.seen_guard");
                if (guards == null || !guards.error.isEmpty()) {
                    selected.clear();
                    status = "query_error";
                } else {
                    List<String> fields = new ArrayList<>();
                    for (DexKitBridgeRunner.RawHit guard : guards.hits) {
                        if (guard.paramTypeNames.equals(
                                java.util.Collections.singletonList("java.lang.String"))) {
                            fields.addAll(guard.usedFields);
                        }
                    }
                    Class<?> ack = Class.forName(
                            selected.get(DexKitChatFingerprint.ANCHOR_ACK_CLASS), false, host.loader);
                    String typeField = DexKitChatFingerprint.ackTypeField(ack, fields);
                    if (typeField.isEmpty()) {
                        selected.clear();
                        status = "ambiguous_or_no_ack_type";
                    } else {
                        selected.put(DexKitChatFingerprint.ANCHOR_ACK_TYPE, typeField);
                        status = "resolved";
                    }
                }
            }
        } catch (Throwable throwable) {
            selected.clear();
            status = "query_error";
        }
        anchorStatus.put("symbols.chat.queue", status);
        return selected;
    }

    private static Map<String, String> resolveMe(
            Map<String, DexKitBridgeRunner.QueryResult> results,
            Map<String, String> anchorStatus) {
        Map<String, String> resolved = new LinkedHashMap<>();
        DexKitBridgeRunner.QueryResult builders = results.get(DexKitMeFingerprint.QUERY_BUILDERS);
        if (builders == null || !builders.error.isEmpty()) {
            if (anchorStatus != null) {
                anchorStatus.put("symbols.me.builder",
                        builders == null ? "query_missing" : builders.error);
            }
            return resolved;
        }
        List<DexKitMeFingerprint.MethodHit> hits = new ArrayList<>();
        for (DexKitBridgeRunner.RawHit raw : builders.hits) {
            hits.add(new DexKitMeFingerprint.MethodHit(
                    raw.className, raw.methodName, raw.returnTypeName, raw.paramCount));
        }
        DexKitMeFingerprint.Resolution resolution = DexKitMeFingerprint.evaluate(hits);
        if (anchorStatus != null) {
            anchorStatus.put("symbols.me.builder", resolution.status
                    + " (" + hits.size() + " one-param methods)");
        }
        if (!resolution.resolved()) {
            return resolved;
        }
        resolved.put(DexKitMeFingerprint.ANCHOR_BUILDER, resolution.symbol);
        return resolved;
    }

    /**
     * Row accessor resolution, anchored by class relation instead of a runtime
     * observation: the adapter declares its item type (the shared row base), and the
     * row classes are the Conversation-holding subclasses of that base. Their no-arg
     * String accessors reveal the Conversation uid field, which is the only signal
     * group detection needs (the stable {@code group_} UID convention). No invoke-graph
     * query runs here: it aborts the process for some methods, and the group-flag method
     * it would resolve is redundant with the UID convention.
     */
    private static void resolveRowAnchors(DexKitZinstantResolver.HostIdentity host,
                                          Map<String, String> anchorStatus,
                                          Map<String, String> resolved) {
        StringBuilder detail = new StringBuilder();
        Set<String> bases = inboxItemBaseClasses(host.loader, detail);
        if (bases.isEmpty()) {
            anchorStatus.put("symbols.inbox.uid", "no_item_base_class " + detail);
            return;
        }
        List<DexKitBridgeRunner.SubclassSpec> subclassSpecs = new ArrayList<>();
        Map<String, String> baseOf = new LinkedHashMap<>();
        int baseIndex = 0;
        for (String base : bases) {
            String id = "inbox.rows." + baseIndex++;
            baseOf.put(id, base);
            subclassSpecs.add(new DexKitBridgeRunner.SubclassSpec(
                    id, base, DexKitInboxFingerprint.CONVERSATION_CLASS));
        }
        Map<String, DexKitBridgeRunner.ClassQueryResult> subclassResults =
                DexKitBridgeRunner.findSubclassesWithField(host.sourceDir, subclassSpecs);
        Set<String> rowClasses = new LinkedHashSet<>();
        String chosenBase = "";
        for (Map.Entry<String, String> spec : baseOf.entrySet()) {
            DexKitBridgeRunner.ClassQueryResult result = subclassResults.get(spec.getKey());
            if (result == null || !result.error.isEmpty()) {
                anchorStatus.put("symbols.inbox.uid", "query_error");
                return;
            }
            if (result.classNames.isEmpty()) {
                continue;
            }
            if (!chosenBase.isEmpty()) {
                anchorStatus.put("symbols.inbox.uid", "ambiguous_item_base " + baseOf.keySet());
                return;
            }
            chosenBase = spec.getValue();
            rowClasses.addAll(result.classNames);
        }
        if (chosenBase.isEmpty()) {
            anchorStatus.put("symbols.inbox.uid", "no_row_classes " + detail);
            return;
        }
        List<DexKitBridgeRunner.MethodSpec> specs = new ArrayList<>();
        Map<String, String> ownerOf = new LinkedHashMap<>();
        int index = 0;
        for (String rowClass : rowClasses) {
            String id = DexKitInboxFingerprint.QUERY_ROW_UID_METHODS + "." + index++;
            ownerOf.put(id, rowClass);
            specs.add(new DexKitBridgeRunner.MethodSpec(
                    id, rowClass, "java.lang.String", 0, true, null, false));
        }
        Map<String, DexKitBridgeRunner.QueryResult> results;
        try {
            results = DexKitBridgeRunner.scanMethods(host.sourceDir, specs);
        } catch (Throwable throwable) {
            anchorStatus.put("symbols.inbox.uid", "query_error");
            return;
        }
        List<DexKitInboxFingerprint.RowMethodHit> hits = new ArrayList<>();
        for (Map.Entry<String, String> spec : ownerOf.entrySet()) {
            DexKitBridgeRunner.QueryResult result = results.get(spec.getKey());
            if (result == null || !result.error.isEmpty()) {
                anchorStatus.put("symbols.inbox.uid", "query_error");
                return;
            }
            for (DexKitBridgeRunner.RawHit hit : result.hits) {
                if (hit == null || hit.paramCount != 0 || hit.methodName.isEmpty()
                        || !spec.getValue().equals(hit.className)) {
                    continue;
                }
                hits.add(new DexKitInboxFingerprint.RowMethodHit(
                        hit.methodName, hit.returnTypeName, hit.usedFields));
            }
        }
        DexKitInboxFingerprint.RowResolution resolution =
                DexKitInboxFingerprint.resolveUidField(hits,
                        conversationStringFields(host.loader));
        anchorStatus.put("symbols.inbox.uid",
                resolution.status + " (" + chosenBase + " -> " + rowClasses.size() + " rows)");
        if (!resolution.resolved()) {
            return;
        }
        try {
            Class<?> conversation = Class.forName(
                    DexKitInboxFingerprint.CONVERSATION_CLASS, false, host.loader);
            java.lang.reflect.Field uidField =
                    conversation.getDeclaredField(resolution.uidField);
            if (uidField.getType() != String.class
                    || java.lang.reflect.Modifier.isStatic(uidField.getModifiers())) {
                anchorStatus.put("symbols.inbox.uid", "validation_failed");
                return;
            }
        } catch (Throwable ignored) {
            anchorStatus.put("symbols.inbox.uid", "validation_failed");
            return;
        }
        resolved.put(DexKitInboxFingerprint.ANCHOR_UID_FIELD, resolution.uidField);
    }

    /**
     * The registered ZRTC callback implementation. Zalo registers a CallCallback
     * subclass that overrides every lifecycle method, so hooking the stable base class
     * installs hooks that never fire (hit_count stayed 0 with 20 installed). The anchor is
     * the class relation: the unique subclass of the stable base that overrides an
     * observed callback. The mapped letter is gone from the APK.
     */
    private static Map<String, String> resolveCallCallback(
            DexKitZinstantResolver.HostIdentity host,
            Map<String, String> anchorStatus) {
        Map<String, String> resolved = new LinkedHashMap<>();
        List<DexKitBridgeRunner.SubclassSpec> specs = new ArrayList<>();
        specs.add(new DexKitBridgeRunner.SubclassSpec("call.callbacks",
                DexKitCallFingerprint.CALLBACK_BASE, ""));
        Map<String, DexKitBridgeRunner.ClassQueryResult> results;
        try {
            results = DexKitBridgeRunner.findSubclassesWithField(host.sourceDir, specs);
        } catch (Throwable throwable) {
            anchorStatus.put("symbols.call_recording.callback", "query_error");
            return resolved;
        }
        DexKitBridgeRunner.ClassQueryResult found = results.get("call.callbacks");
        if (found == null || !found.error.isEmpty()) {
            anchorStatus.put("symbols.call_recording.callback", "query_error");
            return resolved;
        }
        List<DexKitCallFingerprint.Candidate> candidates = new ArrayList<>();
        for (String className : found.classNames) {
            try {
                Class<?> type = Class.forName(className, false, host.loader);
                candidates.add(new DexKitCallFingerprint.Candidate(className,
                        observedCallbacksOn(type)));
            } catch (Throwable ignored) {
            }
        }
        DexKitCallFingerprint.Resolution resolution =
                DexKitCallFingerprint.evaluate(candidates);
        anchorStatus.put("symbols.call_recording.callback",
                resolution.status + " (" + found.classNames.size() + " subclasses)");
        if (resolution.resolved()) {
            resolved.put(DexKitCallFingerprint.ANCHOR_CALLBACK_CLASS, resolution.className);
        }
        resolved.putAll(resolveCallPeerManager(host, anchorStatus));
        return resolved;
    }

    /**
     * The active-peer manager: the class that invokes {@code PeerJNI.zrtc_peer_is_in_call}
     * and holds a container object whose single {@code long} field is the native peer
     * handle. That handle is the only way the recorder can start, and the native
     * {@code PeerJNI} hooks never dispatch, so this Java state is the load-bearing anchor.
     */
    private static Map<String, String> resolveCallPeerManager(
            DexKitZinstantResolver.HostIdentity host, Map<String, String> anchorStatus) {
        Map<String, String> resolved = new LinkedHashMap<>();
        List<DexKitBridgeRunner.InvokerSpec> specs = new ArrayList<>();
        specs.add(new DexKitBridgeRunner.InvokerSpec("call.peer_predicate",
                DexKitCallPeerFingerprint.PEER_JNI_CLASS,
                DexKitCallPeerFingerprint.PEER_PREDICATE_METHOD));
        Map<String, DexKitBridgeRunner.QueryResult> results;
        try {
            results = DexKitBridgeRunner.findInvokers(host.sourceDir, specs);
        } catch (Throwable throwable) {
            anchorStatus.put("symbols.call_recording.peer", "query_error");
            return resolved;
        }
        DexKitBridgeRunner.QueryResult invokers = results.get("call.peer_predicate");
        if (invokers == null || !invokers.error.isEmpty()) {
            anchorStatus.put("symbols.call_recording.peer", "query_error");
            return resolved;
        }
        java.util.Set<String> owners = new java.util.LinkedHashSet<>();
        for (DexKitBridgeRunner.RawHit hit : invokers.hits) {
            if (hit != null && !hit.className.isEmpty()) {
                owners.add(hit.className);
            }
        }
        List<DexKitCallPeerFingerprint.Candidate> candidates = new ArrayList<>();
        for (String owner : owners) {
            try {
                Class<?> type = Class.forName(owner, false, host.loader);
                String accessor = staticSelfAccessorOf(type);
                String container = singleLongContainerOf(type);
                String handle = container.isEmpty() ? ""
                        : singleLongFieldOf(fieldTypeOf(type, container));
                candidates.add(new DexKitCallPeerFingerprint.Candidate(
                        owner, accessor, container, handle));
            } catch (Throwable ignored) {
            }
        }
        DexKitCallPeerFingerprint.Resolution resolution =
                DexKitCallPeerFingerprint.evaluate(candidates);
        anchorStatus.put("symbols.call_recording.peer",
                resolution.status + " (" + owners.size() + " predicate invokers)");
        if (!resolution.resolved()) {
            return resolved;
        }
        resolved.put(DexKitCallPeerFingerprint.ANCHOR_MANAGER_CLASS, resolution.className);
        resolved.put(DexKitCallPeerFingerprint.ANCHOR_ACCESSOR, resolution.accessor);
        resolved.put(DexKitCallPeerFingerprint.ANCHOR_CONTAINER, resolution.containerField);
        resolved.put(DexKitCallPeerFingerprint.ANCHOR_HANDLE, resolution.handleField);
        return resolved;
    }

    /**
     * The chat big-file expiry state: the enum declaring both expiry states, plus the
     * static classifier that reads them. Both must be unambiguous; rewriting the wrong
     * method would remap an unrelated state machine.
     */
    private static Map<String, String> resolveMediaState(
            DexKitZinstantResolver.HostIdentity host,
            Map<String, String> anchorStatus) {
        Map<String, String> resolved = new LinkedHashMap<>();
        List<DexKitBridgeRunner.EnumSpec> enumSpecs = new ArrayList<>();
        enumSpecs.add(new DexKitBridgeRunner.EnumSpec("media.states",
                DexKitMediaFingerprint.STATE_EXPIRED,
                DexKitMediaFingerprint.STATE_NOT_EXPIRED));
        Map<String, DexKitBridgeRunner.ClassQueryResult> enumResults;
        try {
            enumResults = DexKitBridgeRunner.findEnumsUsingStrings(host.sourceDir, enumSpecs);
        } catch (Throwable throwable) {
            anchorStatus.put("symbols.media.state", "query_error");
            return resolved;
        }
        DexKitBridgeRunner.ClassQueryResult found = enumResults.get("media.states");
        if (found == null || !found.error.isEmpty()) {
            anchorStatus.put("symbols.media.state", "query_error");
            return resolved;
        }
        List<DexKitMediaFingerprint.Candidate> candidates = new ArrayList<>();
        for (String className : found.classNames) {
            try {
                Class<?> type = Class.forName(className, false, host.loader);
                if (!type.isEnum()) {
                    continue;
                }
                List<String> states = new ArrayList<>();
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (field.isEnumConstant()) {
                        states.add(field.getName());
                    }
                }
                candidates.add(new DexKitMediaFingerprint.Candidate(className, states));
            } catch (Throwable ignored) {
            }
        }
        DexKitMediaFingerprint.Resolution resolution =
                DexKitMediaFingerprint.evaluate(candidates);
        anchorStatus.put("symbols.media.state",
                resolution.status + " (" + found.classNames.size() + " enum candidates)");
        if (!resolution.resolved()) {
            return resolved;
        }
        resolved.put(DexKitMediaFingerprint.ANCHOR_STATE_CLASS, resolution.className);
        resolved.putAll(resolveMediaClassifier(host, resolution.className, anchorStatus));
        return resolved;
    }

    private static Map<String, String> resolveMediaClassifier(
            DexKitZinstantResolver.HostIdentity host, String stateClass,
            Map<String, String> anchorStatus) {
        Map<String, String> resolved = new LinkedHashMap<>();
        List<DexKitBridgeRunner.MethodSpec> specs = new ArrayList<>();
        specs.add(new DexKitBridgeRunner.MethodSpec("media.classifier", null, stateClass,
                null, true));
        Map<String, DexKitBridgeRunner.QueryResult> results;
        try {
            results = DexKitBridgeRunner.scanMethods(host.sourceDir, specs);
        } catch (Throwable throwable) {
            anchorStatus.put("symbols.media.classifier", "query_error");
            return resolved;
        }
        DexKitBridgeRunner.QueryResult methods = results.get("media.classifier");
        if (methods == null || !methods.error.isEmpty()) {
            anchorStatus.put("symbols.media.classifier", "query_error");
            return resolved;
        }
        String expiredRef = stateClass + "#" + DexKitMediaFingerprint.STATE_EXPIRED;
        String freshRef = stateClass + "#" + DexKitMediaFingerprint.STATE_NOT_EXPIRED;
        List<DexKitMediaFingerprint.ClassifierCandidate> candidates = new ArrayList<>();
        for (DexKitBridgeRunner.RawHit hit : methods.hits) {
            if (hit == null || !hit.isStatic || hit.className.isEmpty()
                    || hit.methodName.isEmpty()) {
                continue;
            }
            List<String> used = new ArrayList<>();
            if (hit.usedFields.contains(expiredRef)) {
                used.add(DexKitMediaFingerprint.STATE_EXPIRED);
            }
            if (hit.usedFields.contains(freshRef)) {
                used.add(DexKitMediaFingerprint.STATE_NOT_EXPIRED);
            }
            candidates.add(new DexKitMediaFingerprint.ClassifierCandidate(
                    hit.className, hit.methodName, used));
        }
        DexKitMediaFingerprint.ClassifierResolution resolution =
                DexKitMediaFingerprint.evaluateClassifier(candidates);
        anchorStatus.put("symbols.media.classifier",
                resolution.status + " (" + methods.matchCount + " returning methods)");
        if (!resolution.resolved()) {
            return resolved;
        }
        resolved.put(DexKitMediaFingerprint.ANCHOR_CLASSIFIER_CLASS, resolution.ownerClass);
        resolved.put(DexKitMediaFingerprint.ANCHOR_CLASSIFIER_METHOD, resolution.methodName);
        return resolved;
    }

    /** The single static no-arg method returning the owner's own type, or "". */
    private static String staticSelfAccessorOf(Class<?> owner) {
        String found = "";
        for (java.lang.reflect.Method method : owner.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    || method.getParameterTypes().length != 0
                    || method.getReturnType() != owner) {
                continue;
            }
            if (!found.isEmpty()) {
                return "";
            }
            found = method.getName();
        }
        return found;
    }

    /** The single non-static field whose type declares exactly one long field, or "". */
    private static String singleLongContainerOf(Class<?> owner) {
        String found = "";
        for (java.lang.reflect.Field field : owner.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> fieldType = field.getType();
            if (fieldType.isPrimitive() || fieldType.isArray()) {
                continue;
            }
            if (!singleLongFieldOf(fieldType).isEmpty()) {
                if (!found.isEmpty()) {
                    return "";
                }
                found = field.getName();
            }
        }
        return found;
    }

    private static Class<?> fieldTypeOf(Class<?> owner, String fieldName) {
        try {
            return owner.getDeclaredField(fieldName).getType();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The single long field on a class, or "" when zero or several. */
    private static String singleLongFieldOf(Class<?> type) {
        if (type == null) {
            return "";
        }
        String found = "";
        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            if (field.getType() != Long.TYPE) {
                continue;
            }
            if (!found.isEmpty()) {
                return "";
            }
            found = field.getName();
        }
        return found;
    }

    /** Declared no-arg/void callback methods the feature observes, on one class. */
    private static List<String> observedCallbacksOn(Class<?> type) {
        List<String> found = new ArrayList<>();
        for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    && CallRecordingLifecycle.observes(method.getName())) {
                found.add(method.getName());
            }
        }
        return found;
    }

    /**
     * Deleted-group store, anchored on the table literal. The store is the unique table
     * user shaped as a singleton with a {@code (String)Z} membership check; the other
     * user is a database helper. Resolving by class name alone is what drifted
     * (260802903's {@code l90.c}, then a stale {@code n90.c}) and left the feature stale.
     */
    private static void resolveDeletedGroupStore(DexKitZinstantResolver.HostIdentity host,
                                                 Map<String, String> anchorStatus,
                                                 Map<String, String> resolved) {
        Map<String, DexKitBridgeRunner.QueryResult> results;
        try {
            List<DexKitBridgeRunner.StringSpec> specs = new ArrayList<>();
            specs.add(new DexKitBridgeRunner.StringSpec(
                    DexKitDeletedGroupFingerprint.QUERY_TABLE_OWNERS,
                    DexKitDeletedGroupFingerprint.TABLE, false));
            results = DexKitBridgeRunner.scanStrings(host.sourceDir, specs);
        } catch (Throwable throwable) {
            anchorStatus.put("symbols.inbox.deleted_group", "query_error");
            return;
        }
        DexKitBridgeRunner.QueryResult owners =
                results.get(DexKitDeletedGroupFingerprint.QUERY_TABLE_OWNERS);
        if (owners == null || !owners.error.isEmpty()) {
            anchorStatus.put("symbols.inbox.deleted_group", "query_error");
            return;
        }
        java.util.Set<String> ownerClasses = new java.util.LinkedHashSet<>();
        for (DexKitBridgeRunner.RawHit hit : owners.hits) {
            if (hit != null && !hit.className.isEmpty()) {
                ownerClasses.add(hit.className);
            }
        }
        List<DexKitDeletedGroupFingerprint.Candidate> candidates = new ArrayList<>();
        for (String owner : ownerClasses) {
            try {
                Class<?> type = Class.forName(owner, false, host.loader);
                candidates.add(new DexKitDeletedGroupFingerprint.Candidate(owner,
                        singletonFieldOf(type), membershipCheckOf(type)));
            } catch (Throwable ignored) {
            }
        }
        DexKitDeletedGroupFingerprint.Resolution resolution =
                DexKitDeletedGroupFingerprint.evaluate(candidates);
        anchorStatus.put("symbols.inbox.deleted_group",
                resolution.status + " (" + ownerClasses.size() + " table users)");
        if (!resolution.resolved()) {
            return;
        }
        resolved.put(DexKitDeletedGroupFingerprint.ANCHOR_CLASS, resolution.className);
        resolved.put(DexKitDeletedGroupFingerprint.ANCHOR_FIELD, resolution.singletonField);
        resolved.put(DexKitDeletedGroupFingerprint.ANCHOR_CHECK, resolution.checkMethod);
    }

    /** The single static field holding an instance of the owner's own type, or "". */
    private static String singletonFieldOf(Class<?> owner) {
        String found = "";
        for (java.lang.reflect.Field field : owner.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (field.getType() != owner) {
                continue;
            }
            if (!found.isEmpty()) {
                return "";
            }
            found = field.getName();
        }
        return found;
    }

    /** The single declared instance {@code (String)Z} method, or "". */
    private static String membershipCheckOf(Class<?> owner) {
        String found = "";
        for (java.lang.reflect.Method method : owner.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || params[0] != String.class
                    || method.getReturnType() != Boolean.TYPE) {
                continue;
            }
            if (!found.isEmpty()) {
                return "";
            }
            found = method.getName();
        }
        return found;
    }

    /**
     * Candidate item base classes: for every non-static MessagesView field of an
     * application type, the return types of its single-int-parameter accessors. The
     * adapter base is then the one candidate whose subclasses hold a Conversation, which
     * the caller resolves. No androidx class name is assumed: Zalo's R8 pass obfuscates
     * the nested adapter type.
     */
    private static Set<String> inboxItemBaseClasses(ClassLoader loader, StringBuilder detail) {
        Set<String> bases = new LinkedHashSet<>();
        if (loader == null) {
            detail.append("no_loader");
            return bases;
        }
        try {
            Class<?> messagesView = Class.forName(
                    DexKitInboxFingerprint.MESSAGES_VIEW_CLASS, false, loader);
            int fields = 0;
            for (Class<?> type = messagesView;
                    type != null && type != Object.class; type = type.getSuperclass()) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    String fieldType = field.getType().getName();
                    if (field.getType().isPrimitive() || field.getType() == String.class
                            || fieldType.startsWith("java.")
                            || fieldType.startsWith("android.")) {
                        continue;
                    }
                    fields++;
                    bases.addAll(intIndexedReturnTypes(field.getType()));
                }
            }
            detail.append("viewFields=").append(fields).append(" candidates=").append(bases);
        } catch (Throwable throwable) {
            detail.append("error=").append(throwable.getClass().getSimpleName());
        }
        return bases;
    }

    /** Application return types of a class's single-int-parameter accessors. */
    private static Set<String> intIndexedReturnTypes(Class<?> owner) {
        Set<String> found = new LinkedHashSet<>();
        try {
            for (Class<?> type = owner;
                    type != null && type != Object.class; type = type.getSuperclass()) {
                for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length != 1 || params[0] != Integer.TYPE) {
                        continue;
                    }
                    Class<?> returned = method.getReturnType();
                    if (returned.isPrimitive() || returned == Void.TYPE
                            || returned == String.class) {
                        continue;
                    }
                    String name = returned.getName();
                    if (name.startsWith("java.") || name.startsWith("android.")) {
                        continue;
                    }
                    found.add(name);
                }
            }
        } catch (Throwable ignored) {
        }
        return found;
    }

    /**
     * Resolves the inbox category int field: the single Conversation int field
     * used by number-4 methods. Normal items, the adapter, and row methods are
     * runtime-derived by the feature and never resolved here.
     */
    private static Map<String, String> resolveInbox(
            DexKitZinstantResolver.HostIdentity host,
            Map<String, String> anchorStatus) {
        Map<String, String> resolved = new LinkedHashMap<>();
        resolveRowAnchors(host, anchorStatus, resolved);
        resolveDeletedGroupStore(host, anchorStatus, resolved);
        // BIZ_BOX links the discriminator to OA semantics rather than a field letter.
        List<String> intFields = conversationIntFields(host.loader);
        if (intFields == null || intFields.isEmpty()) {
            if (anchorStatus != null) {
                anchorStatus.put("symbols.inbox.category", "no_conversation_fields");
            }
            return resolved;
        }
        java.util.List<DexKitBridgeRunner.FieldUseSpec> specs = new ArrayList<>();
        for (String field : intFields) {
            specs.add(new DexKitBridgeRunner.FieldUseSpec(
                    DexKitInboxFingerprint.QUERY_CATEGORY_USERS + "." + field,
                    DexKitInboxFingerprint.CONVERSATION_CLASS, field, 4, "BIZ_BOX", "seen_oa_msg"));
        }
        java.util.Map<String, DexKitBridgeRunner.QueryResult> fieldUses;
        try {
            fieldUses = DexKitBridgeRunner.scanFieldUses(host.sourceDir, specs);
        } catch (Throwable throwable) {
            if (anchorStatus != null) {
                anchorStatus.put("symbols.inbox.category", "bridge_failed");
            }
            return resolved;
        }
        Map<String, Integer> matchCounts = new LinkedHashMap<>();
        for (String field : intFields) {
            DexKitBridgeRunner.QueryResult result = fieldUses.get(
                    DexKitInboxFingerprint.QUERY_CATEGORY_USERS + "." + field);
            if (result == null || !result.error.isEmpty()) {
                if (anchorStatus != null && result != null) {
                    anchorStatus.put("symbols.inbox.category", result.error);
                }
                return resolved;
            }
            matchCounts.put(field, result.matchCount);
            if (anchorStatus != null) {
                anchorStatus.put("symbols.inbox.field_" + field,
                        String.valueOf(result.matchCount));
            }
        }
        DexKitInboxFingerprint.Resolution resolution =
                DexKitInboxFingerprint.resolveFieldMatches(matchCounts, intFields);
        if (anchorStatus != null) {
            anchorStatus.put("symbols.inbox.category", resolution.status);
        }
        if (!resolution.resolved()) {
            return resolved;
        }
        resolved.put(DexKitInboxFingerprint.ANCHOR_CATEGORY_FIELD, resolution.field);
        return resolved;
    }

    /** String field names on the Conversation class, in declaration order. */
    private static List<String> conversationStringFields(ClassLoader loader) {
        List<String> names = new ArrayList<>();
        if (loader == null) {
            return names;
        }
        try {
            Class<?> conversation = Class.forName(
                    DexKitInboxFingerprint.CONVERSATION_CLASS, false, loader);
            for (java.lang.reflect.Field field : conversation.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (field.getType() == String.class) {
                    names.add(field.getName());
                }
            }
        } catch (Throwable ignored) {
        }
        return names;
    }

    /** Int field names on the Conversation class, in declaration order. */
    private static List<String> conversationIntFields(ClassLoader loader) {
        List<String> names = new ArrayList<>();
        if (loader == null) {
            return names;
        }
        try {
            Class<?> conversation = Class.forName(
                    DexKitInboxFingerprint.CONVERSATION_CLASS, false, loader);
            for (java.lang.reflect.Field field : conversation.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (field.getType() == Integer.TYPE) {
                    names.add(field.getName());
                }
            }
        } catch (Throwable ignored) {
        }
        return names;
    }

    private static List<DexKitPasscodeFingerprint.CallerHit> callerHits(
            DexKitBridgeRunner.QueryResult result) {
        List<DexKitPasscodeFingerprint.CallerHit> hits = new ArrayList<>();
        if (result == null) {
            return hits;
        }
        for (DexKitBridgeRunner.RawHit raw : result.hits) {
            List<DexKitPasscodeFingerprint.Callee> invoked = new ArrayList<>();
            for (DexKitBridgeRunner.RawHit callee : raw.invoked) {
                invoked.add(new DexKitPasscodeFingerprint.Callee(callee.className,
                        callee.methodName, callee.returnTypeName, callee.paramTypeNames,
                        callee.isStatic));
            }
            hits.add(new DexKitPasscodeFingerprint.CallerHit(
                    raw.className, raw.methodName, invoked));
        }
        return hits;
    }

    private static List<DexKitWebviewFingerprint.MethodHit> webviewHits(ClassLoader loader,
            DexKitBridgeRunner.QueryResult result) {
        List<DexKitWebviewFingerprint.MethodHit> hits = new ArrayList<>();
        if (result == null) {
            return hits;
        }
        for (DexKitBridgeRunner.RawHit raw : result.hits) {
            boolean firstInterface = isInterface(loader, raw, 0);
            boolean lastInterface = isInterface(loader, raw, raw.paramTypeNames.size() - 1);
            hits.add(new DexKitWebviewFingerprint.MethodHit(raw.className, raw.methodName,
                    raw.returnTypeName, raw.paramTypeNames, raw.isStatic,
                    firstInterface, lastInterface));
        }
        return hits;
    }

    private static boolean isInterface(ClassLoader loader, DexKitBridgeRunner.RawHit raw,
                                       int index) {
        try {
            if (loader == null || raw == null || index < 0
                    || index >= raw.paramTypeNames.size()) {
                return false;
            }
            return Class.forName(raw.paramTypeNames.get(index), false, loader).isInterface();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Drops candidate descriptors whose family fails structural preflight on the
     * merged overlay. Family coherence: each family arms together or not at all.
     */
    private static Map<String, String> preflightFilter(Context context,
            DexKitZinstantResolver.HostIdentity host, ClassLoader loader, DexKitCache.Entry entry,
            SymbolSchema.Active exact, SymbolSchema.Active fallback) {
        Map<String, String> kept = new LinkedHashMap<>(entry.extended);
        dropIncompleteFamily(kept,
                DexKitWebviewFingerprint.ANCHOR_REDIRECT,
                DexKitWebviewFingerprint.ANCHOR_COMPANION,
                DexKitWebviewFingerprint.ANCHOR_DISPATCH);
        // Passcode arms on the reader alone when the setter is unresolvable; the
        // setter pair is kept only when fully resolved.
        dropIncompleteFamily(kept,
                DexKitPasscodeFingerprint.ANCHOR_READER_CLASS,
                DexKitPasscodeFingerprint.ANCHOR_READER_METHOD);
        if (!kept.containsKey(DexKitPasscodeFingerprint.ANCHOR_SETTER_CLASS)
                || !kept.containsKey(DexKitPasscodeFingerprint.ANCHOR_SETTER_METHOD)) {
            kept.remove(DexKitPasscodeFingerprint.ANCHOR_SETTER_CLASS);
            kept.remove(DexKitPasscodeFingerprint.ANCHOR_SETTER_METHOD);
        }
        dropIncompleteFamily(kept,
                DexKitPasscodeFingerprint.ANCHOR_BACKUP_CLASS,
                DexKitPasscodeFingerprint.ANCHOR_BACKUP_METHOD);
        dropIncompleteFamily(kept,
                DexKitTelemetryFingerprint.ANCHOR_EVENT_ACCESSOR,
                DexKitTelemetryFingerprint.ANCHOR_SCREEN_ACCESSOR,
                DexKitTelemetryFingerprint.ANCHOR_SESSION_ACCESSOR,
                DexKitTelemetryFingerprint.ANCHOR_VIEW_ACCESSOR);
        if (!tabsLeavesComplete(kept)) {
            dropBottomTabsLeaves(kept);
        }
        dropIncompleteFamily(kept,
                DexKitInboxFingerprint.ANCHOR_CATEGORY_FIELD);
        if (kept.containsKey(DexKitInboxFingerprint.ANCHOR_UID_FIELD)) {
            try {
                java.lang.reflect.Field uid = Class.forName(
                        DexKitInboxFingerprint.CONVERSATION_CLASS, false, loader)
                        .getDeclaredField(kept.get(DexKitInboxFingerprint.ANCHOR_UID_FIELD));
                if (uid.getType() != String.class
                        || java.lang.reflect.Modifier.isStatic(uid.getModifiers())) {
                    kept.remove(DexKitInboxFingerprint.ANCHOR_UID_FIELD);
                }
            } catch (Throwable ignored) {
                kept.remove(DexKitInboxFingerprint.ANCHOR_UID_FIELD);
            }
        }
        if (kept.containsKey(DexKitCallPeerFingerprint.ANCHOR_MANAGER_CLASS)
                && !SymbolPreflight.checkDexkitCallPeer(loader,
                        kept.get(DexKitCallPeerFingerprint.ANCHOR_MANAGER_CLASS),
                        kept.get(DexKitCallPeerFingerprint.ANCHOR_ACCESSOR),
                        kept.get(DexKitCallPeerFingerprint.ANCHOR_CONTAINER),
                        kept.get(DexKitCallPeerFingerprint.ANCHOR_HANDLE),
                        new java.util.ArrayList<String>())) {
            kept.remove(DexKitCallPeerFingerprint.ANCHOR_MANAGER_CLASS);
            kept.remove(DexKitCallPeerFingerprint.ANCHOR_ACCESSOR);
            kept.remove(DexKitCallPeerFingerprint.ANCHOR_CONTAINER);
            kept.remove(DexKitCallPeerFingerprint.ANCHOR_HANDLE);
        }
        if (kept.containsKey(DexKitCallFingerprint.ANCHOR_CALLBACK_CLASS)
                && !SymbolPreflight.checkDexkitCallCallback(loader,
                        kept.get(DexKitCallFingerprint.ANCHOR_CALLBACK_CLASS),
                        new java.util.ArrayList<String>())) {
            kept.remove(DexKitCallFingerprint.ANCHOR_CALLBACK_CLASS);
        }
        dropIncompleteFamily(kept,
                DexKitMediaFingerprint.ANCHOR_STATE_CLASS,
                DexKitMediaFingerprint.ANCHOR_CLASSIFIER_CLASS,
                DexKitMediaFingerprint.ANCHOR_CLASSIFIER_METHOD);
        if (kept.containsKey(DexKitMediaFingerprint.ANCHOR_STATE_CLASS)
                && !SymbolPreflight.checkDexkitMediaState(loader,
                        kept.get(DexKitMediaFingerprint.ANCHOR_STATE_CLASS),
                        kept.get(DexKitMediaFingerprint.ANCHOR_CLASSIFIER_CLASS),
                        kept.get(DexKitMediaFingerprint.ANCHOR_CLASSIFIER_METHOD),
                        new java.util.ArrayList<String>())) {
            kept.remove(DexKitMediaFingerprint.ANCHOR_STATE_CLASS);
            kept.remove(DexKitMediaFingerprint.ANCHOR_CLASSIFIER_CLASS);
            kept.remove(DexKitMediaFingerprint.ANCHOR_CLASSIFIER_METHOD);
        }
        dropIncompleteFamily(kept,
                DexKitDeletedGroupFingerprint.ANCHOR_CLASS,
                DexKitDeletedGroupFingerprint.ANCHOR_FIELD,
                DexKitDeletedGroupFingerprint.ANCHOR_CHECK);
        if (kept.containsKey(DexKitDeletedGroupFingerprint.ANCHOR_CLASS)
                && !SymbolPreflight.checkDexkitDeletedGroup(loader,
                        kept.get(DexKitDeletedGroupFingerprint.ANCHOR_CLASS),
                        kept.get(DexKitDeletedGroupFingerprint.ANCHOR_FIELD),
                        kept.get(DexKitDeletedGroupFingerprint.ANCHOR_CHECK),
                        new java.util.ArrayList<String>())) {
            kept.remove(DexKitDeletedGroupFingerprint.ANCHOR_CLASS);
            kept.remove(DexKitDeletedGroupFingerprint.ANCHOR_FIELD);
            kept.remove(DexKitDeletedGroupFingerprint.ANCHOR_CHECK);
        }
        dropIncompleteFamily(kept,
                DexKitChatFingerprint.ANCHOR_REPOSITORY_CLASS,
                DexKitChatFingerprint.ANCHOR_ACK_METHOD,
                DexKitChatFingerprint.ANCHOR_TYPING_METHOD);
        dropIncompleteFamily(kept,
                DexKitChatFingerprint.ANCHOR_MANAGER, DexKitChatFingerprint.ANCHOR_ACK_CLASS,
                DexKitChatFingerprint.ANCHOR_ACK_TYPE, DexKitChatFingerprint.ANCHOR_SINGLE,
                DexKitChatFingerprint.ANCHOR_BATCH);
        if (!chatQueueOk(loader, kept)) {
            kept.remove(DexKitChatFingerprint.ANCHOR_MANAGER);
            kept.remove(DexKitChatFingerprint.ANCHOR_ACK_CLASS);
            kept.remove(DexKitChatFingerprint.ANCHOR_ACK_TYPE);
            kept.remove(DexKitChatFingerprint.ANCHOR_SINGLE);
            kept.remove(DexKitChatFingerprint.ANCHOR_BATCH);
        }
        if (kept.isEmpty()) {
            return kept;
        }
        // Scan-time and warm activation must validate the same precedence tree.
        JSONObject merged = composeSymbols(exact, fallback, kept,
                entry.adBind, entry.feedBind);
        SymbolSchema.Active overlay = SymbolSchema.dexkitOverlayForHooks(
                DexKitOverlay.buildProfile(host.versionCode, entry.codeDigest,
                        signerSha256(context), merged, "static-verified"),
                host.versionCode);
        boolean webviewOk = false;
        boolean passcodeOk = false;
        boolean backupOk = false;
        boolean telemetryOk = false;
        boolean bottomTabsOk = false;
        boolean meOk = false;
        boolean inboxCategoriesOk = false;
        if (overlay != null) {
            FamilyStates states = familyStates(overlay, loader, kept);
            webviewOk = states.webview;
            passcodeOk = states.passcode;
            backupOk = states.backup;
            telemetryOk = states.telemetry;
            bottomTabsOk = states.bottomTabs;
            meOk = states.me;
            inboxCategoriesOk = states.inboxCategories;
        }
        if (!webviewOk) {
            dropIncompleteFamily(kept,
                    DexKitWebviewFingerprint.ANCHOR_REDIRECT,
                    DexKitWebviewFingerprint.ANCHOR_COMPANION,
                    DexKitWebviewFingerprint.ANCHOR_DISPATCH);
            // Dropping one anchor of a coherent family drops all of them.
            kept.remove(DexKitWebviewFingerprint.ANCHOR_REDIRECT);
            kept.remove(DexKitWebviewFingerprint.ANCHOR_COMPANION);
            kept.remove(DexKitWebviewFingerprint.ANCHOR_DISPATCH);
        }
        if (!passcodeOk) {
            kept.remove(DexKitPasscodeFingerprint.ANCHOR_READER_CLASS);
            kept.remove(DexKitPasscodeFingerprint.ANCHOR_READER_METHOD);
            kept.remove(DexKitPasscodeFingerprint.ANCHOR_SETTER_CLASS);
            kept.remove(DexKitPasscodeFingerprint.ANCHOR_SETTER_METHOD);
        }
        if (!backupOk) {
            kept.remove(DexKitPasscodeFingerprint.ANCHOR_BACKUP_CLASS);
            kept.remove(DexKitPasscodeFingerprint.ANCHOR_BACKUP_METHOD);
        }
        if (!telemetryOk) {
            kept.remove(DexKitTelemetryFingerprint.ANCHOR_EVENT_ACCESSOR);
            kept.remove(DexKitTelemetryFingerprint.ANCHOR_SCREEN_ACCESSOR);
            kept.remove(DexKitTelemetryFingerprint.ANCHOR_SESSION_ACCESSOR);
            kept.remove(DexKitTelemetryFingerprint.ANCHOR_VIEW_ACCESSOR);
        }
        if (!bottomTabsOk) {
            dropBottomTabsLeaves(kept);
        }
        if (!meOk) {
            kept.remove(DexKitMeFingerprint.ANCHOR_BUILDER);
        }
        if (!inboxCategoriesOk) {
            kept.remove(DexKitInboxFingerprint.ANCHOR_CATEGORY_FIELD);
        }
        if (!chatRepositoryOk(loaderOf(host), kept)) {
            kept.remove(DexKitChatFingerprint.ANCHOR_REPOSITORY_CLASS);
            kept.remove(DexKitChatFingerprint.ANCHOR_ACK_METHOD);
            kept.remove(DexKitChatFingerprint.ANCHOR_TYPING_METHOD);
        }
        return kept;
    }

    private static boolean chatQueueOk(ClassLoader loader, Map<String, String> descriptors) {
        try {
            Class<?> manager = Class.forName(descriptors.get(
                    DexKitChatFingerprint.ANCHOR_MANAGER), false, loader);
            Class<?> repository = Class.forName(descriptors.get(
                    DexKitChatFingerprint.ANCHOR_REPOSITORY_CLASS), false, loader);
            Map<String, String> shape = DexKitChatFingerprint.queueShape(manager, repository);
            for (String key : new String[]{DexKitChatFingerprint.ANCHOR_MANAGER,
                    DexKitChatFingerprint.ANCHOR_ACK_CLASS, DexKitChatFingerprint.ANCHOR_SINGLE,
                    DexKitChatFingerprint.ANCHOR_BATCH}) {
                if (!descriptors.containsKey(key) || !descriptors.get(key).equals(shape.get(key))) {
                    return false;
                }
            }
            java.lang.reflect.Field field = Class.forName(
                    descriptors.get(DexKitChatFingerprint.ANCHOR_ACK_CLASS), false, loader)
                    .getDeclaredField(descriptors.get(DexKitChatFingerprint.ANCHOR_ACK_TYPE));
            return field.getType() == Integer.TYPE
                    && !java.lang.reflect.Modifier.isStatic(field.getModifiers());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Live check: the resolved repository declares both chat send shapes. */
    private static boolean chatRepositoryOk(ClassLoader loader, Map<String, String> kept) {
        if (kept == null
                || !kept.containsKey(DexKitChatFingerprint.ANCHOR_REPOSITORY_CLASS)) {
            return false;
        }
        try {
            Class<?> repository = Class.forName(
                    kept.get(DexKitChatFingerprint.ANCHOR_REPOSITORY_CLASS), false, loader);
            List<String> errors = new ArrayList<>();
            SymbolPreflight.checkChatRepository(repository,
                    kept.get(DexKitChatFingerprint.ANCHOR_ACK_METHOD),
                    kept.get(DexKitChatFingerprint.ANCHOR_TYPING_METHOD), errors);
            return errors.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True when every bottom-tabs leaf the overlay synthesis requires is present. */
    private static boolean tabsLeavesComplete(Map<String, String> kept) {
        if (kept == null) {
            return false;
        }
        return DexKitBottomTabsFingerprint.complete(kept);
    }

    private static void dropBottomTabsLeaves(Map<String, String> kept) {
        if (kept == null) {
            return;
        }
        List<String> doomed = new ArrayList<>();
        for (String key : kept.keySet()) {
            if (key.startsWith("symbols.bottom_tabs.")) {
                doomed.add(key);
            }
        }
        for (String key : doomed) {
            kept.remove(key);
        }
    }

    private static void dropIncompleteFamily(Map<String, String> kept, String... anchors) {
        boolean complete = true;
        for (String anchor : anchors) {
            if (!kept.containsKey(anchor)) {
                complete = false;
                break;
            }
        }
        if (!complete) {
            for (String anchor : anchors) {
                kept.remove(anchor);
            }
        }
    }

    private static DexKitCache.Entry withExtended(DexKitCache.Entry entry,
                                                  Map<String, String> extended) {
        if (entry == null) {
            return null;
        }
        Map<String, String> merged = new LinkedHashMap<>(entry.extended);
        if (extended != null) {
            for (Map.Entry<String, String> item : extended.entrySet()) {
                if (item.getValue() == null || item.getValue().isEmpty()) {
                    merged.remove(item.getKey());
                } else {
                    merged.put(item.getKey(), item.getValue());
                }
            }
        }
        return new DexKitCache.Entry(entry.versionCode, entry.codeDigest, entry.lastUpdateTime,
                entry.apkSize, entry.queryRevision, entry.resolverFormat, entry.moduleVersion,
                entry.adBind, entry.feedBind, entry.negative, entry.reason, entry.matchAd,
                entry.matchFeed, entry.scanDurationMs, entry.scannedAt, entry.partialAttempts,
                merged, entry.families);
    }

    private static String describe(DexKitCache.Entry entry,
                                     Map<String, DexKitBridgeRunner.QueryResult> stringResults,
                                     Map<String, String> anchorStatus) {
        int extended = entry.extended == null ? 0 : entry.extended.size();
        StringBuilder statuses = new StringBuilder();
        if (anchorStatus != null) {
            for (Map.Entry<String, String> item : anchorStatus.entrySet()) {
                if (!"resolved".equals(item.getValue())) {
                    if (statuses.length() > 0) {
                        statuses.append(' ');
                    }
                    statuses.append(shortAnchor(item.getKey())).append('=').append(item.getValue());
                }
            }
        }
        String failures = statuses.length() == 0 ? "all resolved" : statuses.toString();
        return (entry.negative ? "confirmed miss " : "cache ready ")
                + "rev " + entry.queryRevision + " · ad " + entry.matchAd
                + " feed " + entry.matchFeed + " · +" + extended + " family anchors · "
                + matchSummary(null, stringResults) + " · " + failures;
    }

    private static String anchorStatusForLog(Map<String, String> anchorStatus) {
        if (anchorStatus == null || anchorStatus.isEmpty()) {
            return "{}";
        }
        StringBuilder output = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> item : anchorStatus.entrySet()) {
            if (!first) {
                output.append(", ");
            }
            first = false;
            output.append(shortAnchor(item.getKey())).append('=').append(item.getValue());
        }
        return output.append('}').toString();
    }

    private static String shortAnchor(String path) {        if (path == null) {
            return "?";
        }
        int lastDot = path.lastIndexOf('.');
        return lastDot < 0 ? path : path.substring(lastDot + 1);
    }

    private static String matchSummary(Map<String, DexKitBridgeRunner.QueryResult> results,
                                       Map<String, DexKitBridgeRunner.QueryResult> stringResults) {
        StringBuilder summary = new StringBuilder();
        appendMatches(summary, results, DexKitZinstantFingerprint.ANCHOR_AD_BIND);
        appendMatches(summary, results, DexKitZinstantFingerprint.ANCHOR_FEED_BIND);
        appendMatches(summary, results, DexKitWebviewFingerprint.ANCHOR_REDIRECT);
        appendMatches(summary, results, DexKitWebviewFingerprint.ANCHOR_DISPATCH);
        appendMatches(summary, stringResults, DexKitPasscodeFingerprint.QUERY_PASSCODE_CALLERS);
        appendMatches(summary, stringResults, DexKitPasscodeFingerprint.QUERY_BACKUP_CALLERS);
        appendMatches(summary, results, DexKitTelemetryFingerprint.QUERY_ACCESSORS);
        appendMatches(summary, results, DexKitMeFingerprint.QUERY_BUILDERS);
        for (Map.Entry<String, String> labelTable
                : DexKitTelemetryFingerprint.labelTables().entrySet()) {
            appendMatches(summary, stringResults,
                    DexKitTelemetryFingerprint.QUERY_TABLE_PREFIX + labelTable.getValue());
        }
        return summary.toString();
    }

    private static void appendMatches(StringBuilder summary,
                                      Map<String, DexKitBridgeRunner.QueryResult> results,
                                      String id) {
        int count = -1;
        if (results != null && results.get(id) != null) {
            count = results.get(id).matchCount;
        }
        if (summary.length() > 0) {
            summary.append(' ');
        }
        summary.append(id).append('=').append(count < 0 ? "?" : String.valueOf(count));
    }

    private static ClassLoader loaderOf(DexKitZinstantResolver.HostIdentity host) {
        return host == null ? null : host.loader;
    }

    private static String signerSha256(Context context) {
        try {
            android.content.pm.PackageManager manager = context.getPackageManager();
            android.content.pm.PackageInfo info;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                info = manager.getPackageInfo(TARGET_PACKAGE,
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.signingInfo == null) {
                    return "";
                }
                android.content.pm.Signature[] signatures =
                        info.signingInfo.getApkContentsSigners();
                if (signatures == null || signatures.length == 0) {
                    return "";
                }
                return sha256(signatures[0].toByteArray());
            }
            info = manager.getPackageInfo(TARGET_PACKAGE,
                    android.content.pm.PackageManager.GET_SIGNATURES);
            if (info.signatures == null || info.signatures.length == 0) {
                return "";
            }
            return sha256(info.signatures[0].toByteArray());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String sha256(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
            return hex(digest.digest());
        }
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hex(digest.digest(data));
    }

    private static String hex(byte[] hash) {
        StringBuilder output = new StringBuilder(hash.length * 2);
        for (byte item : hash) {
            output.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return output.toString();
    }
}
