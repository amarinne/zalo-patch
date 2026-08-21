package com.ez.zalopatch;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.Map;
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
    private static final int JOB_ID = 0x5a10;
    private static final long CATALOG_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final AtomicBoolean REQUESTED_FROM_HOOK = new AtomicBoolean(false);
    private static volatile String cachedLightweight;

    private ZaloArtifactState() {
    }

    public static boolean schedule(Context context) {
        return schedule(context, false);
    }

    public static boolean schedule(Context context, boolean forceCatalogCheck) {
        if (context == null || instrumentationInstalled(context)) {
            return false;
        }
        if (forceCatalogCheck) {
            TweakStore.preferences(context).edit().putLong(KEY_CATALOG_CHECKED_AT, 0L).apply();
        }
        cachedLightweight = null;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            return false;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, ZaloArtifactJobService.class))
                .setMinimumLatency(0L)
                .setOverrideDeadline(5_000L)
                .build();
        return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
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

    static Result reconcile(Context context) {
        TweakStore.initialize(context);
        SharedPreferences preferences = TweakStore.preferences(context);
        try {
            ZaloArtifactIdentity identity = ZaloArtifactIdentity.capture(context, true);
            String previousGeneration = preferences.getString(KEY_GENERATION, "");
            boolean generationChanged = !identity.generation.equals(previousGeneration);
            SymbolCatalogContract.Entry catalogEntry = SymbolCatalogCache.load(
                    context, identity.versionCode);
            long now = System.currentTimeMillis();
            long catalogCheckedAt = preferences.getLong(KEY_CATALOG_CHECKED_AT, 0L);
            String catalogStatus = catalogEntry == null ? "missing" : "cached";
            String catalogError = "";
            // A 404 caches nothing, so an uncached artifact alone must not force the fetch: that
            // kept `catalogEntry == null` true forever and re-queried on every reconcile, ignoring
            // the interval entirely. A recorded `unknown` for this same generation is a negative
            // answer and is honoured until the interval elapses. Manual retry still works because
            // it zeroes KEY_CATALOG_CHECKED_AT, which drives the interval clause.
            boolean knownAbsent = "unknown".equals(
                    preferences.getString(KEY_CATALOG_STATUS, "missing"));
            if (!instrumentationInstalled(context)
                    && (generationChanged
                    || now - catalogCheckedAt >= CATALOG_CHECK_INTERVAL_MS
                    || (catalogEntry == null && !knownAbsent))) {
                SymbolCatalogClient.Result catalogResult = SymbolCatalogClient.resolve(
                        BuildConfig.SYMBOL_CATALOG_URL, identity, catalogEntry);
                catalogStatus = catalogResult.status;
                if ("available".equals(catalogResult.status)) {
                    if (SymbolCatalogCache.save(context, identity, catalogResult.envelope)) {
                        catalogEntry = SymbolCatalogCache.load(context, identity.versionCode);
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
                catalogCheckedAt = now;
            }
            SymbolSchema.Active bundled = SymbolSchema.selectBundledForVersion(
                    context, identity.versionCode);
            SymbolSchema.Active remote = SymbolSchema.selectRemoteEntry(
                    catalogEntry, identity.versionCode);
            // Shadow mode for already-bundled versions. Remote activation is enabled only when the
            // installed exact artifact has no bundled map.
            SymbolSchema.Active profile = bundled.valid ? bundled : remote;
            if (profile == null) profile = bundled;
            String expectedHash = profile.string("artifact.base_apk_sha256", "");
            String expectedSigner = profile.string("artifact.signer_sha256", "");
            String verification = profile.string("artifact.verification", "unverified");
            String profileHash = profile.valid ? ZaloArtifactIdentity.sha256(profile.json) : "";
            Decision decision = decide(profile.valid, profile.validation, catalogStatus,
                    expectedSigner, expectedHash, identity.signerSha256, identity.baseApkSha256);
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
            editor.commit();
            SymbolSchema.invalidate();
            cachedLightweight = identity.lightweightKey;
            TweakStore.initialize(context);
            HookConfig.reload();
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
            return new Result("failed", "", "", error, EVIDENCE_NONE);
        }
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

    public static Compatibility currentCompatibility(Context context) {
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
        String verification = preferences.getString(KEY_VERIFICATION, "unverified");
        String source = preferences.getString(KEY_PROFILE_SOURCE, "unknown");
        String catalogStatus = preferences.getString(KEY_CATALOG_STATUS, "missing");
        String evidence = preferences.getString(KEY_EVIDENCE, EVIDENCE_UNKNOWN);
        String error = preferences.getString(KEY_ERROR, "");
        StringBuilder summary = new StringBuilder();
        summary.append(status).append(" | Zalo ").append(versionCode)
                .append(" | ").append(verification)
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
        String cached = cachedLightweight;
        if (cached != null) {
            return cached;
        }
        try {
            cached = ZaloArtifactIdentity.capture(context, false).lightweightKey;
            cachedLightweight = cached;
            return cached;
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String currentEvidenceEpoch(Context context) {
        String lightweight = currentLightweight(context);
        SymbolSchema.Active profile = SymbolSchema.active(context);
        String profileHash = profile.valid ? ZaloArtifactIdentity.sha256(profile.json) : "";
        String generation = context != null
                && android.os.Process.myUid() == context.getApplicationInfo().uid
                ? TweakStore.preferences(context).getString(KEY_GENERATION, "")
                : HookConfig.getRawString(KEY_GENERATION, "");
        return ZaloArtifactIdentity.sha256(lightweight + "\n" + generation + "\n"
                + BuildConfig.VERSION_CODE + "\n" + profileHash);
    }

    public static void addEvidence(Intent intent, Context context) {
        SymbolSchema.Active profile = SymbolSchema.active(context);
        intent.putExtra("artifact_lightweight", currentLightweight(context));
        intent.putExtra("artifact_generation", HookConfig.getRawString(KEY_GENERATION, ""));
        intent.putExtra("module_version_code", BuildConfig.VERSION_CODE);
        intent.putExtra("profile_sha256", profile.valid
                ? ZaloArtifactIdentity.sha256(profile.json) : "");
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
}
