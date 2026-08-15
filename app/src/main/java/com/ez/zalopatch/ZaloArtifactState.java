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
    public static final String KEY_ERROR = "internal.zalo_artifact.error";
    public static final String KEY_CHECKED_AT = "internal.zalo_artifact.checked_at";
    public static final String KEY_CATALOG_STATUS = "internal.zalo_catalog.status";
    public static final String KEY_CATALOG_SEQUENCE = "internal.zalo_catalog.sequence";
    public static final String KEY_CATALOG_DIGEST = "internal.zalo_catalog.digest";
    public static final String KEY_CATALOG_ERROR = "internal.zalo_catalog.error";
    public static final String KEY_CATALOG_CHECKED_AT = "internal.zalo_catalog.checked_at";
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
            SymbolCatalogContract.Entry catalogEntry = SymbolCatalogCache.load(context,
                    identity.versionCode, identity.baseApkSha256, identity.signerSha256);
            long now = System.currentTimeMillis();
            long catalogCheckedAt = preferences.getLong(KEY_CATALOG_CHECKED_AT, 0L);
            String catalogStatus = catalogEntry == null ? "missing" : "cached";
            String catalogError = "";
            if (!instrumentationInstalled(context)
                    && (catalogEntry == null || generationChanged
                    || now - catalogCheckedAt >= CATALOG_CHECK_INTERVAL_MS)) {
                SymbolCatalogClient.Result catalogResult = SymbolCatalogClient.resolve(
                        BuildConfig.SYMBOL_CATALOG_URL, identity, catalogEntry);
                catalogStatus = catalogResult.status;
                if ("available".equals(catalogResult.status)) {
                    if (SymbolCatalogCache.save(context, identity, catalogResult.envelope)) {
                        catalogEntry = SymbolCatalogCache.load(context, identity.versionCode,
                                identity.baseApkSha256, identity.signerSha256);
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
            String status;
            String error = "";
            if (!profile.valid) {
                status = "unsupported";
                error = profile.validation + "; catalog " + catalogStatus;
            } else if (!expectedSigner.equals(identity.signerSha256)) {
                status = "mismatch";
                error = "Zalo signing certificate does not match the selected profile";
            } else if (!expectedHash.equals(identity.baseApkSha256)) {
                status = "mismatch";
                error = "Zalo base APK hash does not match the selected profile";
            } else {
                status = "ready";
            }
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
            return new Result(status, identity.lightweightKey, identity.generation, error);
        } catch (Throwable throwable) {
            if (throwable instanceof InterruptedException
                    || Thread.currentThread().isInterrupted()) {
                return new Result("cancelled", "", "", "Artifact check cancelled");
            }
            String error = throwable.getClass().getSimpleName() + ": "
                    + (throwable.getMessage() == null ? "artifact check failed" : throwable.getMessage());
            preferences.edit()
                    .putString(KEY_STATUS, "failed")
                    .putString(KEY_ERROR, error)
                    .putLong(KEY_CHECKED_AT, System.currentTimeMillis())
                    .commit();
            HookConfig.reload();
            return new Result("failed", "", "", error);
        }
    }

    public static Compatibility forHooks(Context context) {
        try {
            ZaloArtifactIdentity current = ZaloArtifactIdentity.capture(context, false);
            String status = HookConfig.getRawString(KEY_STATUS, "pending");
            String storedLightweight = HookConfig.getRawString(KEY_LIGHTWEIGHT, "");
            String generation = HookConfig.getRawString(KEY_GENERATION, "");
            String storedHash = HookConfig.getRawString(KEY_BASE_SHA256, "");
            String storedSigner = HookConfig.getRawString(KEY_SIGNER_SHA256, "");
            String storedProfileHash = HookConfig.getRawString(KEY_PROFILE_SHA256, "");
            String error = HookConfig.getRawString(KEY_ERROR, "");
            SymbolSchema.Active profile = SymbolSchema.activeForHooks(context);
            String currentProfileHash = profile.valid
                    ? ZaloArtifactIdentity.sha256(profile.json) : "";
            boolean authorized = "ready".equals(status)
                    && profile.valid
                    && current.lightweightKey.equals(storedLightweight)
                    && profile.string("artifact.base_apk_sha256", "").equals(storedHash)
                    && profile.string("artifact.signer_sha256", "").equals(storedSigner)
                    && currentProfileHash.equals(storedProfileHash);
            if (!authorized) {
                requestFromHook(context);
                String reason = current.lightweightKey.equals(storedLightweight)
                        ? (error.isEmpty() ? "Artifact verification " + status : error)
                        : "Installed Zalo artifact changed; verification pending";
                return new Compatibility(false, status, current.lightweightKey, generation, reason);
            }
            return new Compatibility(true, "ready", current.lightweightKey, generation, "");
        } catch (Throwable throwable) {
            requestFromHook(context);
            return new Compatibility(false, "failed", "", "",
                    "Installed Zalo artifact unavailable: " + throwable.getClass().getSimpleName());
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
            boolean authorized = "ready".equals(status)
                    && profile.valid
                    && current.lightweightKey.equals(preferences.getString(KEY_LIGHTWEIGHT, ""))
                    && profile.string("artifact.base_apk_sha256", "").equals(
                            preferences.getString(KEY_BASE_SHA256, ""))
                    && profile.string("artifact.signer_sha256", "").equals(
                            preferences.getString(KEY_SIGNER_SHA256, ""))
                    && profileHash.equals(preferences.getString(KEY_PROFILE_SHA256, ""));
            String reason = authorized ? "" : preferences.getString(KEY_ERROR, "");
            if (!authorized && reason.isEmpty()) {
                reason = current.lightweightKey.equals(preferences.getString(KEY_LIGHTWEIGHT, ""))
                        ? "Artifact verification " + status
                        : "Installed Zalo artifact changed; verification pending";
            }
            return new Compatibility(authorized, status, current.lightweightKey,
                    generation, reason);
        } catch (Throwable throwable) {
            return new Compatibility(false, "failed", "", "",
                    "Installed Zalo artifact unavailable: " + throwable.getClass().getSimpleName());
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
        String error = preferences.getString(KEY_ERROR, "");
        StringBuilder summary = new StringBuilder();
        summary.append(status).append(" | Zalo ").append(versionCode)
                .append(" | ").append(verification)
                .append(" | ").append(source)
                .append(" | catalog ").append(catalogStatus);
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

        Compatibility(boolean compatible, String status, String lightweightKey,
                      String generation, String reason) {
            this.compatible = compatible;
            this.status = status;
            this.lightweightKey = lightweightKey;
            this.generation = generation;
            this.reason = reason;
        }
    }

    static final class Result {
        final String status;
        final String lightweightKey;
        final String generation;
        final String error;

        Result(String status, String lightweightKey, String generation, String error) {
            this.status = status;
            this.lightweightKey = lightweightKey;
            this.generation = generation;
            this.error = error;
        }
    }
}
