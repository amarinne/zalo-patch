package com.ez.zalopatch;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PersistableBundle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ZaloArtifactState {
    public static final String KEY_STATUS = "internal.zalo_artifact.status";
    public static final String KEY_LIGHTWEIGHT = "internal.zalo_artifact.lightweight";
    public static final String KEY_GENERATION = "internal.zalo_artifact.generation";
    public static final String KEY_VERSION_CODE = "internal.zalo_artifact.version_code";
    public static final String KEY_BASE_SHA256 = "internal.zalo_artifact.base_sha256";
    public static final String KEY_SIGNER_SHA256 = "internal.zalo_artifact.signer_sha256";
    public static final String KEY_PROFILE_REVISION = "internal.zalo_artifact.profile_revision";
    public static final String KEY_PROFILE_SHA256 = "internal.zalo_artifact.profile_sha256";
    public static final String KEY_PROFILE_SOURCE = "internal.zalo_artifact.profile_source";
    public static final String KEY_VERIFICATION = "internal.zalo_artifact.verification";
    public static final String KEY_EVIDENCE = "internal.zalo_artifact.evidence";
    public static final String KEY_ERROR = "internal.zalo_artifact.error";
    public static final String KEY_CHECKED_AT = "internal.zalo_artifact.checked_at";
    public static final String KEY_CATALOG_STATUS = "internal.zalo_catalog.status";
    public static final String KEY_CATALOG_SEQUENCE = "internal.zalo_catalog.sequence";
    public static final String KEY_CATALOG_DIGEST = "internal.zalo_catalog.digest";
    public static final String KEY_CATALOG_ERROR = "internal.zalo_catalog.error";
    public static final String KEY_CATALOG_CHECKED_AT = "internal.zalo_catalog.checked_at";
    public static final String KEY_CATALOG_MODULE_VERSION = "internal.zalo_catalog.module_version";
    /** Absolute deadline after a 426/module_too_old answer, keyed to the artifact below. */
    static final String KEY_CATALOG_RETRY_AT = "internal.zalo_catalog.module_too_old_retry_at";
    static final String KEY_CATALOG_RETRY_KEY = "internal.zalo_catalog.module_too_old_retry_key";
    /** True while an unknown exact artifact has remap evidence waiting for upload. */
    public static final String KEY_REMAP_UPLOAD_PENDING =
            "internal.zalo_catalog.remap_upload_pending";
    /** The installed base APK is byte-identical to the one the profile was mapped from. */
    public static final String EVIDENCE_EXACT_APK = "exact_apk";
    /**
     * The installed artifact carries the profile's exact versionCode and Zalo signing certificate,
     * but a different base APK container. Play re-stamps its signing block per download and serves
     * per-device bundle variants, so one release has many base APK hashes with identical code.
     * Symbols are accepted; per-anchor structural preflight remains the load-bearing check.
     */
    public static final String EVIDENCE_VERSION_SIGNER = "version_signer";
    /**
     * The installed artifact carries the profile's exact versionCode, but neither the mapped base
     * APK container nor the Zalo signing certificate. Re-signing rezips without rebuilding dex, and
     * a repacker has no source to re-obfuscate with, so the mapped symbol names normally survive.
     * Symbols are accepted and every anchor is left to structural preflight. Provenance is
     * unverified at this tier, so it is always surfaced rather than folded into a plain ready.
     */
    public static final String EVIDENCE_VERSION_ONLY = "version_only";
    public static final String EVIDENCE_NONE = "none";
    /**
     * Authorized, but the stored state predates the match tier or has not been reconciled since.
     * Never report this as an exact match; a re-check is requested instead.
     */
    public static final String EVIDENCE_UNKNOWN = "unknown";
    private static final int JOB_ID_BASE = 0x5a100000;
    static final String JOB_HOST_PACKAGE = "host_package";
    private static final long CATALOG_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final AtomicBoolean REQUESTED_FROM_HOOK = new AtomicBoolean(false);
    /** Installed-lightweight cache scoped by target package, never a process-global original. */
    private static final ConcurrentHashMap<String, String> CACHED_LIGHTWEIGHT =
            new ConcurrentHashMap<>();

    private ZaloArtifactState() {
    }

    public static boolean schedule(Context context) {
        return schedule(context, false);
    }

    public static boolean schedule(Context context, boolean forceCatalogCheck) {
        if (context == null || instrumentationInstalled(context)) {
            return false;
        }
        String target = registeredTarget(context, HostPackages.target(context));
        if (target == null) {
            // A revoked or malformed selected target must never become the original by default.
            return false;
        }
        if (forceCatalogCheck) {
            TweakStore.preferences(context).edit().putLong(KEY_CATALOG_CHECKED_AT, 0L).apply();
        }
        Context base = HostPackages.base(context);
        CACHED_LIGHTWEIGHT.remove(cacheScope(HostPackages.scoped(base, target)));
        JobScheduler scheduler = base.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            return false;
        }
        PersistableBundle extras = new PersistableBundle();
        extras.putString(JOB_HOST_PACKAGE, target);
        JobInfo job = new JobInfo.Builder(jobId(target),
                new ComponentName(base, ZaloArtifactJobService.class))
                .setMinimumLatency(0L)
                .setOverrideDeadline(5_000L)
                .setExtras(extras)
                .build();
        return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
    }

    /** Canonical or an explicitly registered renamed package; malformed input fails closed. */
    static String registeredTarget(Context moduleContext, String packageName) {
        if (moduleContext == null || packageName == null || packageName.isEmpty()) return null;
        if (HostPackages.ZALO.equals(packageName)) return packageName;
        try {
            if (!HostPackages.parse(packageName).contains(packageName)) return null;
            return HostPackages.allows(
                    HostPackages.additional(HostPackages.base(moduleContext)), packageName)
                    ? packageName : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** Package-change routing: only the canonical install or a still-registered rename. */
    static String eventTarget(Context moduleContext, String packageName) {
        return registeredTarget(moduleContext, packageName);
    }

    /**
     * Job identity is persisted as an explicit package extra. A missing or revoked target is
     * invalid; it never falls back to the original's scoped prefs or catalog deadline.
     */
    static String jobTarget(Context moduleContext, String requestedPackage) {
        return registeredTarget(moduleContext, requestedPackage);
    }

    static Context jobContext(Context moduleContext, String requestedPackage) {
        String target = jobTarget(moduleContext, requestedPackage);
        return target == null ? null : HostPackages.scoped(moduleContext, target);
    }

    /** Stable per-target job id so one target's schedule cannot cancel another's. */
    static int jobId(String packageName) {
        int hash = 0;
        try {
            hash = Integer.parseInt(ZaloArtifactIdentity.sha256(
                    packageName == null ? "" : packageName).substring(0, 7), 16);
        } catch (RuntimeException ignored) {
        }
        return JOB_ID_BASE + (hash & 0x0fffffff);
    }

    /** Verified immutable catalog content is shared; mutable state stays in the target scope. */
    static Context catalogStore(Context context) {
        return HostPackages.scopeSuffix(context).isEmpty() ? context : HostPackages.base(context);
    }

    static String cacheScope(Context context) {
        // The in-memory cache is process-local, so the package name is enough and matches the
        // invalidation key in schedule(); scoped persistence keeps using the existing suffix.
        return context == null ? "" : HostPackages.transportHost(context);
    }

    static void rememberLightweight(Context context, String value) {
        if (context == null || value == null) return;
        CACHED_LIGHTWEIGHT.put(cacheScope(context), value);
    }

    static String rememberedLightweight(Context context) {
        return context == null ? null : CACHED_LIGHTWEIGHT.get(cacheScope(context));
    }

    /**
     * Reconciliation is suppressed while the instrumentation package is installed so a background
     * job cannot mutate preferences under a preservation-aware test run. A test APK left behind by
     * an aborted run keeps that suppression, which stalls the artifact at its stored state, so the
     * condition is surfaced in the artifact summary and diagnostic report rather than staying
     * silent.
     */
    public static boolean reconcileSuppressed(Context context) {
        return context != null && instrumentationInstalled(context);
    }

    private static boolean instrumentationInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo("com.ez.zalopatch.test", 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static synchronized Result reconcile(Context context) {
        TweakStore.initialize(context);
        SharedPreferences preferences = TweakStore.preferences(context);
        try {
            ZaloArtifactIdentity identity = ZaloArtifactIdentity.capture(context, true);
            boolean renamed = HostPackages.renamed(context);
            String previousGeneration = preferences.getString(KEY_GENERATION, "");
            boolean generationChanged = !identity.generation.equals(previousGeneration);
            boolean instrumentationPresent = instrumentationInstalled(context);
            // Verified immutable catalog content is shared under the existing policy; a clone
            // reads it without rewriting the original's mutable deadline or cache files.
            SymbolCatalogContract.Entry catalogEntry = SymbolCatalogCache.load(
                    catalogStore(context), identity.versionCode);
            long now = System.currentTimeMillis();
            long catalogCheckedAt = preferences.getLong(KEY_CATALOG_CHECKED_AT, 0L);
            int catalogModuleVersion = preferences.getInt(KEY_CATALOG_MODULE_VERSION, -1);
            String storedCatalogStatus = preferences.getString(KEY_CATALOG_STATUS, "missing");
            String catalogStatus;
            String catalogError = "";
            boolean catalogFetchAttempted = false;
            boolean moduleTooOldRejected = false;
            String catalogRetryKey = "";
            if (renamed) {
                // A clone's actual mutable artifact identity is persisted in its own scope, but
                // catalog fetch/upload/revocation stay with the canonical installation so clone
                // APK evidence, retry counters and deadlines never masquerade as canonical.
                catalogStatus = catalogEntry != null ? "cached" : "missing";
            } else {
                // A 404 or 426 caches nothing, so an uncached artifact alone must not force the
                // fetch: that kept `catalogEntry == null` true forever and re-queried on every
                // reconcile, ignoring the interval entirely. Negative answers for this same
                // generation are honoured until the interval elapses, and are kept in the stored
                // status while no fetch runs. A missing cached entry still requests a replacement.
                catalogStatus = catalogEntry != null ? "cached"
                        : ("cached".equals(storedCatalogStatus)
                        || "updated".equals(storedCatalogStatus)
                        ? "missing" : storedCatalogStatus);
                boolean knownAbsent = "unknown".equals(storedCatalogStatus)
                        || "module_too_old".equals(storedCatalogStatus);
                catalogRetryKey = CatalogFetchPolicy.cooldownKey(
                        identity.baseApkSha256, identity.versionCode, BuildConfig.VERSION_CODE);
                boolean moduleTooOldCoolingDown = CatalogFetchPolicy.coolingDown(
                        now,
                        preferences.getLong(KEY_CATALOG_RETRY_AT, 0L),
                        preferences.getString(KEY_CATALOG_RETRY_KEY, ""),
                        catalogRetryKey);
                if (CatalogFetchPolicy.shouldFetch(
                        instrumentationPresent,
                        generationChanged,
                        catalogModuleVersion != BuildConfig.VERSION_CODE,
                        now - catalogCheckedAt >= CATALOG_CHECK_INTERVAL_MS,
                        catalogEntry == null,
                        knownAbsent,
                        moduleTooOldCoolingDown)) {
                    catalogFetchAttempted = true;
                    // Admitted, module/host-current evidence only. Stale or missing evidence is
                    // reported as unknown; the build variant and root access are not framework
                    // evidence, and this field never authorizes anything.
                    RuntimeEnvironment.Snapshot runtimeEnvironment =
                            RuntimeEnvironment.current(context);
                    SymbolCatalogClient.Result catalogResult = SymbolCatalogClient.resolve(
                            BuildConfig.SYMBOL_CATALOG_URL, identity, catalogEntry,
                            runtimeEnvironment.framework);
                    catalogStatus = catalogResult.status;
                    moduleTooOldRejected = "module_too_old".equals(catalogResult.status);
                    if ("available".equals(catalogResult.status)) {
                        if (SymbolCatalogCache.save(context, identity, catalogResult.envelope)) {
                            catalogEntry = SymbolCatalogCache.load(
                                    context, identity.versionCode);
                            catalogStatus = catalogEntry == null ? "invalid" : "updated";
                        } else {
                            catalogStatus = "invalid";
                        }
                    } else if (catalogResult.status.endsWith("error")
                            || "invalid_response".equals(catalogResult.status)
                            || "redirect_rejected".equals(catalogResult.status)) {
                        catalogError = catalogResult.status;
                    }
                    if ("revoked".equals(catalogResult.status)) {
                        SymbolCatalogCache.clear(context);
                        catalogEntry = null;
                    }
                    if ("unknown".equals(catalogResult.status)) {
                        SymbolResourceUploader.markPending(context);
                        // Unknown artifacts automatically enable the bounded discovery feature.
                        // The next normal Zalo process run records candidates; no report screen is
                        // needed.
                        if (!instrumentationPresent) {
                            DiagnosticsState.requestRuntimeDiscovery(context);
                        }
                    }
                    catalogCheckedAt = now;
                }
                // Uploads are deliberately sent by the module job, after the catalog miss. A Zalo
                // process can only record evidence and request this job; it never performs network
                // IO. Clone evidence is never uploaded as canonical-family evidence.
                SymbolResourceUploader.uploadIfPending(context, identity);
            }
            SymbolSchema.Active bundled = SymbolSchema.selectBundledForVersion(
                    context, identity.versionCode);
            SymbolSchema.Active remote = SymbolSchema.selectRemoteEntry(
                    catalogEntry, identity.versionCode);
            // One shared policy: a signed remote profile replaces the bundled exact profile only
            // when it names the bundled schema revision and carries a newer one.
            SymbolSchema.Active profile = SymbolProfileSelection.select(bundled, remote);
            if (profile == null) profile = bundled;
            String verification = profile.string("artifact.verification", "unverified");
            String profileHash = profile.valid ? ZaloArtifactIdentity.sha256(profile.json) : "";
            Decision decision;
            if (renamed) {
                // Renamed installations keep the existing version-only policy: the shared
                // canonical-family profile is selected by exact versionCode, and provenance is
                // never promoted to an exact or signer-match tier without clone evidence.
                decision = decideRenamed(profile.valid, profile.validation, catalogStatus);
            } else {
                String expectedHash = profile.string("artifact.base_apk_sha256", "");
                String expectedSigner = profile.string("artifact.signer_sha256", "");
                decision = decide(profile.valid, profile.validation, catalogStatus,
                        expectedSigner, expectedHash, identity.signerSha256,
                        identity.baseApkSha256);
            }
            String status = decision.status;
            String error = decision.error;
            String evidence = decision.evidence;
            if (generationChanged) {
                clearSelfCheck(preferences);
            }
            SharedPreferences.Editor editor = preferences.edit()
                    .putString(KEY_STATUS, status)
                    .putString(KEY_LIGHTWEIGHT, identity.lightweightKey)
                    .putString(KEY_GENERATION, identity.generation)
                    .putLong(KEY_VERSION_CODE, identity.versionCode)
                    .putString(KEY_BASE_SHA256, identity.baseApkSha256)
                    .putString(KEY_SIGNER_SHA256, identity.signerSha256)
                    .putInt(KEY_PROFILE_REVISION, profile.schemaRevision)
                    .putString(KEY_PROFILE_SHA256, profileHash)
                    .putString(KEY_PROFILE_SOURCE, profile.source)
                    .putString(KEY_VERIFICATION, verification)
                    .putString(KEY_EVIDENCE, evidence)
                    .putString(KEY_ERROR, error)
                    .putLong(KEY_CHECKED_AT, now)
                    .putString(KEY_CATALOG_STATUS, catalogStatus)
                    .putInt(KEY_CATALOG_SEQUENCE, catalogEntry == null ? 0 : catalogEntry.sequence)
                    .putString(KEY_CATALOG_DIGEST, catalogEntry == null ? "" : catalogEntry.digest)
                    .putString(KEY_CATALOG_ERROR, catalogError)
                    .putLong(KEY_CATALOG_CHECKED_AT, catalogCheckedAt);
            if (catalogFetchAttempted) {
                if (moduleTooOldRejected) {
                    long retryAt = CatalogFetchPolicy.retryAt(now, CATALOG_CHECK_INTERVAL_MS);
                    editor.putLong(KEY_CATALOG_RETRY_AT, retryAt)
                            .putString(KEY_CATALOG_RETRY_KEY, catalogRetryKey);
                } else {
                    editor.remove(KEY_CATALOG_RETRY_AT).remove(KEY_CATALOG_RETRY_KEY);
                }
            } else if (renamed) {
                // Clone scopes never inherit the canonical target's retry deadline.
                editor.remove(KEY_CATALOG_RETRY_AT).remove(KEY_CATALOG_RETRY_KEY);
            }
            if (!instrumentationPresent) {
                editor.putInt(KEY_CATALOG_MODULE_VERSION, BuildConfig.VERSION_CODE);
            }
            editor.commit();
            SymbolSchema.invalidate();
            rememberLightweight(context, identity.lightweightKey);
            TweakStore.initialize(context);
            HookConfig.reload();
            FrameworkConfig.refreshState(context);
            return new Result(status, identity.lightweightKey, identity.generation, error,
                    evidence);
        } catch (Throwable throwable) {
            if (throwable instanceof InterruptedException
                    || Thread.currentThread().isInterrupted()) {
                return new Result("cancelled", "", "", "Artifact check cancelled", EVIDENCE_NONE);
            }
            String error = throwable.getClass().getSimpleName() + ": "
                    + (throwable.getMessage() == null ? "artifact check failed" : throwable.getMessage());
            preferences.edit()
                    .putString(KEY_STATUS, "failed")
                    .putString(KEY_EVIDENCE, EVIDENCE_NONE)
                    .putString(KEY_ERROR, error)
                    .putLong(KEY_CHECKED_AT, System.currentTimeMillis())
                    .commit();
            HookConfig.reload();
            FrameworkConfig.refreshState(context);
            return new Result("failed", "", "", error, EVIDENCE_NONE);
        }
    }

    /** Called when the Zalo process finishes a requested trace. */
    static void requestRemapUpload(Context context) {
        if (context == null) return;
        SharedPreferences preferences = TweakStore.preferences(context);
        if (!"unknown".equals(preferences.getString(KEY_CATALOG_STATUS, "missing"))) return;
        if (HostPackages.renamed(context)) return;
        SymbolResourceUploader.markPending(context);
        schedule(context);
    }

    /** Runs an explicit foreground compatibility check without deferring to JobScheduler. */
    static synchronized ManualCheckResult checkNow(Context context) {
        if (context == null) {
            return new ManualCheckResult("failed", "Context unavailable", -1L);
        }
        Context applicationContext = context.getApplicationContext();
        if (instrumentationInstalled(applicationContext)) {
            return new ManualCheckResult("suppressed",
                    "com.ez.zalopatch.test is installed", -1L);
        }
        String target = registeredTarget(applicationContext,
                HostPackages.target(applicationContext));
        if (target == null) {
            return new ManualCheckResult("failed", "Target unavailable", -1L);
        }
        JobScheduler scheduler = applicationContext.getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.cancel(jobId(target));
        SharedPreferences preferences = TweakStore.preferences(applicationContext);
        // An explicit foreground retry is the owner action that lifts a module-too-old cooldown.
        // The reconcile still records a fresh deadline if the server rejects this build again.
        // For a clone scope this only moves its own deadline; shared catalog content is untouched.
        preferences.edit()
                .putLong(KEY_CATALOG_CHECKED_AT, 0L)
                .remove(KEY_CATALOG_RETRY_AT)
                .remove(KEY_CATALOG_RETRY_KEY)
                .commit();
        Result result = reconcile(applicationContext);
        String catalogStatus = preferences.getString(KEY_CATALOG_STATUS, "missing");
        String catalogError = preferences.getString(KEY_CATALOG_ERROR, "");
        if ("failed".equals(result.status) && catalogError.isEmpty()) {
            catalogStatus = "failed";
            catalogError = result.error;
        }
        return new ManualCheckResult(catalogStatus, catalogError,
                preferences.getLong(KEY_VERSION_CODE, -1L));
    }

    /**
     * Decides whether the installed artifact may use a selected profile.
     *
     * <p>The profile is selected by exact {@code versionCode}; symbols are never taken from another
     * version here. Container identity is recorded evidence rather than a gate, at two tiers.
     *
     * <p>The base APK hash identifies a download, not a build: Play serves per-device bundle
     * variants and re-stamps its signing block per serving, so one release legitimately has several
     * base APK hashes over byte-identical dex (Decision 14).
     *
     * <p>The signer is likewise not a gate (Decision 15). Re-signing requires a rezip, not a dex
     * rebuild, so clone tools and mirror redistributions produce a fresh signer over unchanged code.
     * Repackaging does not re-obfuscate either, because that needs source the repacker does not
     * have, so schema symbol names survive it. Where a container genuinely did diverge, the names
     * fail to resolve and per-anchor structural preflight declines that feature.
     *
     * <p>Preflight validates structure, not identity, so it cannot prove a resolved name belongs to
     * the class it was mapped from. The tier is therefore reported rather than hidden: it is the
     * triage signal that says whether a defect report describes this module or someone's patch.
     */
    static Decision decide(boolean profileValid, String profileValidation, String catalogStatus,
                           String expectedSigner, String expectedBaseHash,
                           String actualSigner, String actualBaseHash) {
        if (!profileValid) {
            return new Decision("unsupported", EVIDENCE_NONE,
                    profileValidation + "; catalog " + catalogStatus);
        }
        if (expectedSigner == null || !expectedSigner.equals(actualSigner)) {
            return new Decision("ready", EVIDENCE_VERSION_ONLY, "");
        }
        if (expectedBaseHash != null && expectedBaseHash.equals(actualBaseHash)) {
            return new Decision("ready", EVIDENCE_EXACT_APK, "");
        }
        return new Decision("ready", EVIDENCE_VERSION_SIGNER, "");
    }

    /**
     * Renamed installations keep their accepted version-only provenance. A shared canonical-family
     * profile is selected by exact versionCode, but a clone never claims the mapped container or
     * signing certificate even when a re-sign happens to reuse one.
     */
    static Decision decideRenamed(boolean profileValid, String profileValidation,
                                  String catalogStatus) {
        if (!profileValid) {
            return new Decision("unsupported", EVIDENCE_NONE,
                    profileValidation + "; catalog " + catalogStatus);
        }
        return new Decision("ready", EVIDENCE_VERSION_ONLY, "");
    }

    /**
     * Authorization inputs, deliberately excluding the install identity. Splits, install path and
     * install time vary across devices and across ordinary Play activity on one device, and carry
     * no symbol information. What remains: the module reconciled to {@code ready}, a profile is
     * selected for the installed versionCode, and the hook process resolved the same profile bytes
     * the module did.
     *
     * <p>The signer comparison this used to make was the provenance gate a second time, in the hook
     * process. Provenance is a reported tier rather than a gate (Decision 15), and the profile hash
     * is what carries the cross-process consistency intent: a version move changes the selected
     * profile, which moves the hash.
     */
    static boolean authorizes(String status, boolean profileValid, String profileHash,
                              String storedProfileHash) {
        return "ready".equals(status)
                && profileValid
                && profileHash != null && profileHash.equals(storedProfileHash);
    }

    public static Compatibility forHooks(Context context) {
        if (HostPackages.renamed(context)) return renamedCompatibility(context);
        try {
            ZaloArtifactIdentity current = ZaloArtifactIdentity.capture(context, false);
            String status = HookConfig.getRawString(KEY_STATUS, "pending");
            String storedLightweight = HookConfig.getRawString(KEY_LIGHTWEIGHT, "");
            String generation = HookConfig.getRawString(KEY_GENERATION, "");
            String storedProfileHash = HookConfig.getRawString(KEY_PROFILE_SHA256, "");
            String evidence = HookConfig.getRawString(KEY_EVIDENCE, EVIDENCE_UNKNOWN);
            String error = HookConfig.getRawString(KEY_ERROR, "");
            SymbolSchema.Active profile = SymbolSchema.activeForHooks(context);
            String currentProfileHash = profile.valid
                    ? ZaloArtifactIdentity.sha256(profile.json) : "";
            boolean installChanged = !current.lightweightKey.equals(storedLightweight);
            // The install identity covers split set, install path and install time, none of which
            // say anything about the symbols. It triggers a re-check; it does not withhold hooks.
            // Adding an on-demand split would otherwise disarm every feature until a deferrable job
            // ran. The profile is still selected by the installed versionCode, and a version change
            // moves the profile hash, so a real version move is still caught below.
            if (installChanged) {
                requestFromHook(context);
            }
            boolean authorized = authorizes(status, profile.valid,
                    currentProfileHash, storedProfileHash);
            if (!authorized) {
                requestFromHook(context);
                String reason = installChanged
                        ? "Installed Zalo artifact changed; verification pending"
                        : (error.isEmpty() ? "Artifact verification " + status : error);
                return new Compatibility(false, status, current.lightweightKey, generation, reason,
                        EVIDENCE_NONE);
            }
            if (installChanged || EVIDENCE_UNKNOWN.equals(evidence)) {
                // Reconciled before the match tier existed, or not since. Stay authorized and let
                // the module process record which tier this artifact actually matched on.
                requestFromHook(context);
            }
            return new Compatibility(true, "ready", current.lightweightKey, generation, "",
                    evidence);
        } catch (Throwable throwable) {
            requestFromHook(context);
            return new Compatibility(false, "failed", "", "",
                    "Installed Zalo artifact unavailable: " + throwable.getClass().getSimpleName(),
                    EVIDENCE_NONE);
        }
    }

    /** Clone admission and symbols refer to this installation, not the primary app's snapshot. */
    private static Compatibility renamedCompatibility(Context context) {
        try {
            ZaloArtifactIdentity current = ZaloArtifactIdentity.capture(context, false);
            SymbolSchema.Active profile = SymbolSchema.exactForProcess(context);
            Decision decision = decideRenamed(profile.valid, profile.validation, "local");
            return new Compatibility("ready".equals(decision.status), decision.status,
                    current.lightweightKey, current.lightweightKey, decision.error, decision.evidence);
        } catch (Throwable error) {
            return new Compatibility(false, "failed", "", "",
                    "Clone artifact unavailable: " + error.getClass().getSimpleName(), EVIDENCE_NONE);
        }
    }

    /**
     * Module-side view of a clone. It reports only the persisted reconciliation for this scope, so
     * an unreconciled or stale clone is Not checked rather than inheriting the original's result.
     */
    private static Compatibility renamedStoredCompatibility(Context context) {
        try {
            ZaloArtifactIdentity current = ZaloArtifactIdentity.capture(context, false);
            SharedPreferences preferences = TweakStore.preferences(context);
            String status = preferences.getString(KEY_STATUS, "pending");
            String generation = preferences.getString(KEY_GENERATION, "");
            String storedLightweight = preferences.getString(KEY_LIGHTWEIGHT, "");
            SymbolSchema.Active profile = SymbolSchema.active(context);
            String profileHash = profile.valid
                    ? ZaloArtifactIdentity.sha256(profile.json) : "";
            boolean installCurrent = current.lightweightKey.equals(storedLightweight);
            boolean authorized = installCurrent
                    && authorizes(status, profile.valid, profileHash,
                    preferences.getString(KEY_PROFILE_SHA256, ""));
            String reason = authorized ? "" : preferences.getString(KEY_ERROR, "");
            if (!authorized && reason.isEmpty()) {
                reason = installCurrent
                        ? "Artifact verification " + status
                        : "Installed Zalo artifact changed; verification pending";
            }
            // The renamed policy never promotes provenance to exact or signer match.
            return new Compatibility(authorized, status, current.lightweightKey, generation,
                    reason, authorized ? EVIDENCE_VERSION_ONLY : EVIDENCE_NONE);
        } catch (Throwable throwable) {
            return new Compatibility(false, "failed", "", "",
                    "Clone artifact unavailable: " + throwable.getClass().getSimpleName(),
                    EVIDENCE_NONE);
        }
    }

    public static Compatibility currentCompatibility(Context context) {
        if (HostPackages.renamed(context)) return renamedStoredCompatibility(context);
        try {
            ZaloArtifactIdentity current = ZaloArtifactIdentity.capture(context, false);
            SharedPreferences preferences = TweakStore.preferences(context);
            String status = preferences.getString(KEY_STATUS, "pending");
            String generation = preferences.getString(KEY_GENERATION, "");
            SymbolSchema.Active profile = SymbolSchema.active(context);
            String profileHash = profile.valid ? ZaloArtifactIdentity.sha256(profile.json) : "";
            boolean authorized = authorizes(status, profile.valid,
                    profileHash, preferences.getString(KEY_PROFILE_SHA256, ""));
            String reason = authorized ? "" : preferences.getString(KEY_ERROR, "");
            if (!authorized && reason.isEmpty()) {
                reason = current.lightweightKey.equals(preferences.getString(KEY_LIGHTWEIGHT, ""))
                        ? "Artifact verification " + status
                        : "Installed Zalo artifact changed; verification pending";
            }
            return new Compatibility(authorized, status, current.lightweightKey,
                    generation, reason,
                    authorized ? preferences.getString(KEY_EVIDENCE, EVIDENCE_UNKNOWN)
                            : EVIDENCE_NONE);
        } catch (Throwable throwable) {
            return new Compatibility(false, "failed", "", "",
                    "Installed Zalo artifact unavailable: " + throwable.getClass().getSimpleName(),
                    EVIDENCE_NONE);
        }
    }

    public static String summary(Context context) {
        SharedPreferences preferences = TweakStore.preferences(context);
        String status = preferences.getString(KEY_STATUS, "pending");
        long versionCode = preferences.getLong(KEY_VERSION_CODE, -1L);
        String hash = preferences.getString(KEY_BASE_SHA256, "");
        String source = preferences.getString(KEY_PROFILE_SOURCE, "unknown");
        String catalogStatus = preferences.getString(KEY_CATALOG_STATUS, "missing");
        String evidence = preferences.getString(KEY_EVIDENCE, EVIDENCE_UNKNOWN);
        String error = preferences.getString(KEY_ERROR, "");
        StringBuilder summary = new StringBuilder();
        summary.append(status).append(" | Zalo ").append(versionCode)
                .append(" | ").append(source)
                .append(" | catalog ").append(catalogStatus)
                .append(" | match ").append(evidence);
        if (EVIDENCE_VERSION_SIGNER.equals(evidence)) {
            summary.append("\nBase APK container differs from the mapped one; matched on exact "
                    + "versionCode and Zalo signing certificate.");
        }
        if (EVIDENCE_VERSION_ONLY.equals(evidence)) {
            summary.append("\nZalo signing certificate differs from the mapped one; matched on "
                    + "exact versionCode only. Provenance is unverified and every anchor is "
                    + "gated by structural preflight.");
        }
        if (reconcileSuppressed(context)) {
            summary.append("\nRe-check suppressed while com.ez.zalopatch.test is installed; "
                    + "uninstall it to resume artifact reconciliation.");
        }
        if (!hash.isEmpty()) {
            summary.append("\nSHA-256 ").append(hash.substring(0, Math.min(12, hash.length())));
        }
        if (!error.isEmpty()) {
            summary.append("\n").append(error);
        }
        return summary.toString();
    }

    public static String currentLightweight(Context context) {
        if (context == null) return "";
        String cached = rememberedLightweight(context);
        if (cached != null) {
            return cached;
        }
        try {
            cached = ZaloArtifactIdentity.capture(context, false).lightweightKey;
            rememberLightweight(context, cached);
            return cached;
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String currentEvidenceEpoch(Context context) {
        return ZaloArtifactIdentity.sha256(evidenceIdentity(context).epochMaterial());
    }

    static ArtifactEvidenceIdentity evidenceIdentity(Context context) {
        if (HostPackages.renamed(context)) {
            String lightweight = currentLightweight(context);
            SymbolSchema.Active profile = SymbolSchema.exactForProcess(context);
            return new ArtifactEvidenceIdentity(lightweight, lightweight, BuildConfig.VERSION_CODE,
                    profile.valid ? ZaloArtifactIdentity.sha256(profile.json) : "");
        }
        boolean moduleProcess = SymbolSchema.isModuleProcess(context);
        SharedPreferences preferences = moduleProcess ? TweakStore.preferences(context) : null;
        String generation = moduleProcess ? preferences.getString(KEY_GENERATION, "")
                : HookConfig.getRawString(KEY_GENERATION, "");
        String profileHash = moduleProcess ? preferences.getString(KEY_PROFILE_SHA256, "")
                : HookConfig.getRawString(KEY_PROFILE_SHA256, "");
        return new ArtifactEvidenceIdentity(currentLightweight(context), generation,
                BuildConfig.VERSION_CODE, profileHash);
    }

    public static void addEvidence(Intent intent, Context context) {
        ArtifactEvidenceIdentity identity = evidenceIdentity(context);
        intent.putExtra("artifact_lightweight", identity.lightweight);
        intent.putExtra("artifact_generation", identity.generation);
        intent.putExtra("module_version_code", identity.moduleVersion);
        intent.putExtra("profile_sha256", identity.profileHash);
        // These describe the hook process route. They never participate in artifact admission.
        SymbolSchema.Active route = SymbolSchema.activeForProcess(context);
        intent.putExtra("route_profile_sha256", route.valid
                ? ZaloArtifactIdentity.sha256(route.json) : "");
        intent.putExtra("route_profile_source", route.source);
    }

    private static void requestFromHook(Context context) {
        if (context == null || !REQUESTED_FROM_HOOK.compareAndSet(false, true)) {
            return;
        }
        try {
            context.getContentResolver().call(
                    Uri.parse("content://com.ez.zalopatch.config"),
                    "reconcile_zalo_artifact", null, null);
        } catch (Throwable ignored) {
        }
    }

    private static void clearSelfCheck(SharedPreferences preferences) {
        SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith("selfcheck.")) {
                editor.remove(entry.getKey());
            }
        }
        editor.remove("internal.selfcheck_generation");
        editor.remove("internal.selfcheck_run_id");
        editor.apply();
    }

    public static final class Compatibility {
        public final boolean compatible;
        public final String status;
        public final String lightweightKey;
        public final String generation;
        public final String reason;
        public final String evidence;

        Compatibility(boolean compatible, String status, String lightweightKey,
                      String generation, String reason, String evidence) {
            this.compatible = compatible;
            this.status = status;
            this.lightweightKey = lightweightKey;
            this.generation = generation;
            this.reason = reason;
            this.evidence = evidence;
        }

        /** True when the profile was mapped from a different container of the same release. */
        public boolean containerUnverified() {
            return compatible && EVIDENCE_VERSION_SIGNER.equals(evidence);
        }

        /** True when the installed artifact does not carry the mapped Zalo signing certificate. */
        public boolean signerUnverified() {
            return compatible && EVIDENCE_VERSION_ONLY.equals(evidence);
        }
    }

    static final class Decision {
        final String status;
        final String evidence;
        final String error;

        Decision(String status, String evidence, String error) {
            this.status = status;
            this.evidence = evidence;
            this.error = error;
        }
    }

    static final class Result {
        final String status;
        final String lightweightKey;
        final String generation;
        final String error;
        final String evidence;

        Result(String status, String lightweightKey, String generation, String error,
               String evidence) {
            this.status = status;
            this.lightweightKey = lightweightKey;
            this.generation = generation;
            this.error = error;
            this.evidence = evidence;
        }
    }

    static final class ManualCheckResult {
        final String catalogStatus;
        final String error;
        final long versionCode;

        ManualCheckResult(String catalogStatus, String error, long versionCode) {
            this.catalogStatus = catalogStatus;
            this.error = error;
            this.versionCode = versionCode;
        }
    }
}
