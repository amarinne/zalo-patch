package com.ez.zalopatch;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Maps allowlisted module state to the shared product-neutral intake envelope. */
final class DiagnosticReportFactory {
    private DiagnosticReportFactory() {
    }

    static Draft createMetadataOnly(
            Context context, String category, String description) throws Exception {
        return create(context, DiagnosticReportContract.newReportId(), category, description,
                null);
    }

    static Draft createCaptured(
            Context context, DiagnosticCaptureManager.FinishedCapture capture) throws Exception {
        if (capture == null) throw new IllegalArgumentException("capture required");
        return create(context, capture.session.reportId, capture.session.category,
                capture.session.description, capture);
    }

    private static Draft create(Context context, String reportId, String category,
                                String description,
                                DiagnosticCaptureManager.FinishedCapture capture)
            throws Exception {
        if (!DiagnosticReportContract.validReportId(reportId)
                || !DiagnosticReportContract.validCategory(category)
                || !DiagnosticReportContract.validDescription(description)) {
            throw new IllegalArgumentException("invalid diagnostic report");
        }
        TweakStore.initialize(context);
        long createdAt = capture == null ? System.currentTimeMillis() : capture.finishedAtWallMs;
        JSONObject root = new JSONObject();
        root.put("envelopeVersion", 1);
        root.put("reportId", reportId);
        root.put("product", DiagnosticReportContract.PRODUCT);
        root.put("productReportVersion", DiagnosticReportContract.PRODUCT_REPORT_VERSION);
        root.put("createdAtUtc", utc(createdAt));
        root.put("category", category);
        root.put("description", description);
        PackageSnapshot packages = packageSnapshot(context);
        String rootStatus = capture == null
                ? RootAccess.hasFreshCache(context)
                ? RootAccess.cached(context).reportValue() : "not_checked"
                : capture.data.rootAccessStatus;
        root.put("commonMetadata", commonMetadata(context, packages));
        root.put("productMetadata", productMetadata(
                context, packages, rootStatus, capture));
        root.put("capture", captureMetadata(capture, createdAt, rootStatus));
        root.put("rawDiagnostics", rawDiagnostics(context, capture));
        String json = root.toString();
        if (!DiagnosticReportContract.validDraftJson(json)) {
            throw new IllegalArgumentException("invalid or oversized diagnostic report");
        }
        return new Draft(reportId, category, description, json);
    }

    static Draft fromJson(String json) {
        if (!DiagnosticReportContract.validDraftJson(json)) return null;
        try {
            JSONObject root = new JSONObject(json);
            return new Draft(root.getString("reportId"), root.getString("category"),
                    root.getString("description"), json);
        } catch (Exception ignored) {
            return null;
        }
    }

    static String prettyJson(String json) {
        try {
            return new JSONObject(json).toString(2);
        } catch (Exception ignored) {
            return json == null ? "" : json;
        }
    }

    private static JSONObject commonMetadata(Context context, PackageSnapshot packages)
            throws Exception {
        JSONObject common = new JSONObject();
        common.put("appVersionName", bounded(BuildConfig.VERSION_NAME, 256));
        common.put("appVersionCode", BuildConfig.VERSION_CODE);
        common.put("buildType", bounded(BuildConfig.BUILD_TYPE, 64));
        common.put("manufacturer", bounded(Build.MANUFACTURER, 256));
        common.put("brand", bounded(Build.BRAND, 256));
        common.put("model", bounded(Build.MODEL, 256));
        common.put("device", bounded(Build.DEVICE, 256));
        common.put("product", bounded(Build.PRODUCT, 256));
        common.put("androidRelease", bounded(Build.VERSION.RELEASE, 64));
        common.put("androidApi", Build.VERSION.SDK_INT);
        common.put("androidSecurityPatch", bounded(Build.VERSION.SECURITY_PATCH, 64));
        common.put("androidDisplay", bounded(Build.DISPLAY, 256));
        common.put("androidIncremental", bounded(Build.VERSION.INCREMENTAL, 256));
        common.put("buildFingerprint", bounded(Build.FINGERPRINT, 1024));
        common.put("xiaomiOsProperties", new JSONObject());
        JSONArray locales = new JSONArray();
        android.os.LocaleList configured = context.getResources().getConfiguration().getLocales();
        for (int index = 0; index < configured.size() && index < 8; index++) {
            locales.put(bounded(configured.get(index).toLanguageTag(), 64));
        }
        common.put("locales", locales);
        JSONObject versions = new JSONObject();
        versions.put("zalo_patch", packages.module.toJson());
        versions.put("zalo", packages.zalo.toJson());
        common.put("packageVersions", versions);
        return common;
    }

    private static JSONObject productMetadata(
            Context context, PackageSnapshot packages, String rootStatus,
            DiagnosticCaptureManager.FinishedCapture capture) throws Exception {
        SymbolSchema.Active schema = SymbolSchema.active(context);
        List<SelfCheckData.Row> rows = SelfCheckData.load(context);
        SelfCheckData.Counts counts = SelfCheckData.counts(rows);
        JSONObject product = new JSONObject();
        product.put("debugLoggingEnabled", HookConfig.isDebugEnabled());
        product.put("traceLoggingEnabled", systemPropertyEnabled("debug.zalopatch.trace"));
        product.put("rootAccessStatus", rootStatus);
        RuntimeEnvironment.Snapshot environment = RuntimeEnvironment.current(context);
        product.put("runtimeFramework", environment.framework.value());
        product.put("runtimeEnvironmentReported", environment.reported);
        product.put("resourceHooksObserved", environment.resourceHooksObserved);
        product.put("resourceHooksStatus", environment.resourceHooks.value());
        product.put("pendingSettingsChanges", SettingsChanges.pendingCount(context));
        product.put("customNotificationRuleCount", NotificationRuleStore.load(context).total());

        JSONObject schemaJson = new JSONObject();
        schemaJson.put("source", bounded(schema.source, 64));
        schemaJson.put("schemaVersion", schema.schemaVersion);
        schemaJson.put("schemaRevision", schema.schemaRevision);
        schemaJson.put("valid", schema.valid);
        schemaJson.put("validation", bounded(schema.validation, 1024));
        schemaJson.put("installedZaloVersionCode", schema.installedVersionCode);
        schemaJson.put("profileCount", schema.profileCount);
        schemaJson.put("supportedVersionCodes", bounded(schema.supportedVersionCodes, 512));
        product.put("symbolSchema", schemaJson);
        product.put("remapEvidence", remapEvidence(context, capture));

        JSONObject countJson = new JSONObject();
        countJson.put("failed", counts.failed);
        countJson.put("stale", counts.stale);
        countJson.put("installedNoHits", counts.installedNoHits);
        countJson.put("active", counts.active);
        countJson.put("disabled", counts.disabled);
        countJson.put("other", counts.other);
        product.put("selfCheckCounts", countJson);
        JSONArray selfCheck = new JSONArray();
        for (int index = 0; index < rows.size() && index < 128; index++) {
            SelfCheckData.Row row = rows.get(index);
            JSONObject item = new JSONObject();
            item.put("feature", bounded(row.feature, 128));
            item.put("status", bounded(row.status, 32));
            item.put("installCount", Math.max(0, row.installCount));
            item.put("hitCount", Math.max(0, row.hitCount));
            // Detail and error-message fields stay out of the report by contract; detail carries
            // free text such as notification channel ids and conversation counts. Evidence a
            // reviewer needs belongs in target, which is a bounded feature descriptor.
            item.put("target", bounded(row.target, 512));
            item.put("updatedAt", Math.max(0L, row.updatedAt));
            selfCheck.put(item);
        }
        product.put("selfCheckRows", selfCheck);
        JSONArray runtimeTrace = new JSONArray();
        List<JSONObject> traceEvents = RuntimeStatusTraceStore.load(context);
        int traceStart = Math.max(0, traceEvents.size() - 64);
        for (int index = traceStart; index < traceEvents.size(); index++) {
            runtimeTrace.put(traceEvents.get(index));
        }
        product.put("runtimeStatusTrace", runtimeTrace);

        JSONObject setup = new JSONObject();
        setup.put("setupState", setupState(packages.zalo.present, schema.valid, rows, counts));
        setup.put("zaloPackagePresent", packages.zalo.present);
        setup.put("symbolSchemaValid", schema.valid);
        setup.put("runtimeSelfCheckPresent", !rows.isEmpty());
        boolean internetGranted = context.checkSelfPermission(
                android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED;
        setup.put("internetPermissionGranted", internetGranted);
        setup.put("rootAccessStatus", rootStatus);
        setup.put("failedCount", counts.failed);
        setup.put("staleCount", counts.stale);
        product.put("setupChecks", setup);
        return product;
    }

    /**
     * Reconciliation stores either one of its own fixed explanations or "Type: message" built from
     * a throwable. Raw exception messages are excluded from reports, so keep only the type half.
     */
    private static String sanitizedErrorType(String error) {
        if (error == null || error.isEmpty()) {
            return "";
        }
        int separator = error.indexOf(": ");
        if (separator <= 0) {
            return error;
        }
        String type = error.substring(0, separator);
        return type.matches("[A-Za-z0-9_.$]+") ? type : "";
    }

    private static JSONObject remapEvidence(
            Context context, DiagnosticCaptureManager.FinishedCapture capture) throws Exception {
        JSONObject evidence = new JSONObject();
        boolean compatibility = capture != null
                && "compatibility".equals(capture.session.category);
        evidence.put("requestedForReport", compatibility);
        SymbolSchema.Active activeProfile = SymbolSchema.active(context);
        evidence.put("exactBundledProfileMapped", SymbolSchema.bundled(context).valid);
        evidence.put("exactProfileMapped", activeProfile.valid);
        evidence.put("profileSource", bounded(activeProfile.source, 64));
        long installedVersion = SymbolSchema.installedZaloVersionCode(context);
        long completedVersion = DiagnosticsState.runtimeDiscoveryLastVersionCode(context);
        evidence.put("installedZaloVersionCode", installedVersion);
        evidence.put("lastCompletedVersionCode", completedVersion);
        evidence.put("completedForInstalledVersion",
                installedVersion > 0L && installedVersion == completedVersion);

        JSONObject artifact = new JSONObject();
        try {
            ZaloArtifactIdentity identity = ZaloArtifactIdentity.capture(context, true);
            artifact.put("baseApkSha256", identity.baseApkSha256);
            artifact.put("signerSha256", identity.signerSha256);
            JSONArray splits = new JSONArray();
            for (ZaloArtifactIdentity.Split split : identity.splits) {
                JSONObject item = new JSONObject();
                item.put("name", bounded(split.name, 256));
                item.put("sha256", split.sha256);
                splits.put(item);
            }
            artifact.put("splits", splits);
        } catch (Exception exception) {
            artifact.put("error", exception.getClass().getSimpleName());
        }
        android.content.SharedPreferences preferences = TweakStore.preferences(context);
        artifact.put("status", bounded(
                preferences.getString(ZaloArtifactState.KEY_STATUS, "pending"), 32));
        artifact.put("matchEvidence", bounded(preferences.getString(
                ZaloArtifactState.KEY_EVIDENCE, ZaloArtifactState.EVIDENCE_UNKNOWN), 32));
        artifact.put("reconciledBaseApkSha256", bounded(
                preferences.getString(ZaloArtifactState.KEY_BASE_SHA256, ""), 64));
        artifact.put("profileRevision",
                preferences.getInt(ZaloArtifactState.KEY_PROFILE_REVISION, 0));
        artifact.put("statusErrorType", bounded(sanitizedErrorType(
                preferences.getString(ZaloArtifactState.KEY_ERROR, "")), 128));
        artifact.put("catalogStatus", bounded(
                preferences.getString(ZaloArtifactState.KEY_CATALOG_STATUS, "missing"), 32));
        artifact.put("reconcileSuppressed", ZaloArtifactState.reconcileSuppressed(context));
        evidence.put("artifact", artifact);

        RemapEvidenceStore.Snapshot stored = RemapEvidenceStore.load(context);
        java.util.LinkedHashSet<String> candidateLines =
                new java.util.LinkedHashSet<>(stored.candidates);
        java.util.LinkedHashSet<String> surfaceLines =
                new java.util.LinkedHashSet<>(stored.surfaces);
        boolean logTruncated = false;
        if (capture != null) {
            collectRemapLines(capture.data.logs, candidateLines, surfaceLines);
            collectRemapLines(capture.data.lsposedLines, candidateLines, surfaceLines);
            logTruncated = Boolean.TRUE.equals(
                    capture.data.truncationFlags.get("diagnosticEventsAndLogs"))
                    || Boolean.TRUE.equals(
                    capture.data.truncationFlags.get("lsposedModuleLines"));
        }
        JSONArray candidates = new JSONArray();
        JSONArray surfaces = new JSONArray();
        int evidenceBytes = 0;
        boolean structuredTruncated = false;
        for (String line : candidateLines) {
            String bounded = bounded(line, 8192);
            int bytes = DiagnosticReportContract.utf8Bytes(bounded);
            if (evidenceBytes + bytes > 96 * 1024) {
                structuredTruncated = true;
                break;
            }
            candidates.put(bounded);
            evidenceBytes += bytes;
        }
        for (String line : surfaceLines) {
            String bounded = bounded(line, 8192);
            int bytes = DiagnosticReportContract.utf8Bytes(bounded);
            if (evidenceBytes + bytes > 96 * 1024) {
                structuredTruncated = true;
                break;
            }
            surfaces.put(bounded);
            evidenceBytes += bytes;
        }
        evidence.put("storedEvidenceVersionCode", stored.versionCode);
        evidence.put("structuredEvidencePresent", !stored.candidates.isEmpty()
                || !stored.surfaces.isEmpty());
        evidence.put("candidates", candidates);
        evidence.put("stableSurfaceMetadata", surfaces);
        evidence.put("candidateCount", candidates.length());
        evidence.put("surfaceMetadataCount", surfaces.length());
        evidence.put("sourceLogsTruncated", logTruncated);
        evidence.put("structuredEvidenceTruncated", structuredTruncated);
        evidence.put("complete", compatibility
                && installedVersion > 0L
                && installedVersion == completedVersion
                && stored.versionCode == installedVersion
                && candidates.length() > 0
                && surfaces.length() > 0
                && !structuredTruncated);
        return evidence;
    }

    private static void collectRemapLines(
            String value, java.util.Set<String> candidates, java.util.Set<String> surfaces) {
        if (value == null || value.isEmpty()) return;
        for (String raw : value.split("\\r?\\n")) {
            int marker = raw.indexOf("[RuntimeDiscovery] ");
            if (marker < 0) continue;
            String line = raw.substring(marker + "[RuntimeDiscovery] ".length()).trim();
            if (line.startsWith("CANDIDATE ")) {
                candidates.add(line);
            } else if (line.startsWith("VIEW ")
                    || line.startsWith("fields ->")
                    || line.startsWith("methods ->")) {
                surfaces.add(line);
            }
        }
    }

    static void collectRemapLinesForTest(
            String value, java.util.Set<String> candidates, java.util.Set<String> surfaces) {
        collectRemapLines(value, candidates, surfaces);
    }

    private static JSONObject captureMetadata(
            DiagnosticCaptureManager.FinishedCapture capture, long finishedAtMs,
            String rootStatus)
            throws Exception {
        JSONObject metadata = new JSONObject();
        if (capture == null) {
            metadata.put("outcome", "not_requested");
            metadata.put("startedAtUtc", JSONObject.NULL);
            metadata.put("finishedAtUtc", utc(finishedAtMs));
            metadata.put("previousDiagnosticLoggingEnabled", JSONObject.NULL);
            metadata.put("rootAccessStatus", rootStatus);
            metadata.put("commandFailures", new JSONArray());
            metadata.put("truncationFlags", new JSONObject());
            return metadata;
        }
        metadata.put("outcome", capture.data.outcome);
        metadata.put("startedAtUtc", utc(capture.session.startedAtWallMs));
        metadata.put("finishedAtUtc", utc(capture.finishedAtWallMs));
        metadata.put("previousDiagnosticLoggingEnabled",
                capture.session.debugLoggingManaged
                        ? capture.session.previousDebugLogging : JSONObject.NULL);
        metadata.put("rootAccessStatus", capture.data.rootAccessStatus);
        JSONArray failures = new JSONArray();
        for (String failure : capture.data.commandFailures) failures.put(failure);
        metadata.put("commandFailures", failures);
        JSONObject truncation = new JSONObject();
        for (Map.Entry<String, Boolean> entry : capture.data.truncationFlags.entrySet()) {
            truncation.put(entry.getKey(), entry.getValue());
        }
        metadata.put("truncationFlags", truncation);
        return metadata;
    }

    private static JSONObject rawDiagnostics(
            Context context, DiagnosticCaptureManager.FinishedCapture capture) throws Exception {
        JSONObject raw = new JSONObject();
        raw.put("diagnosticEventsAndLogs", capture == null ? "" : capture.data.logs);
        raw.put("crashExcerpt", capture == null ? "" : capture.data.crashExcerpt);
        raw.put("lsposedModuleLines", capture == null ? "" : capture.data.lsposedLines);
        JSONObject settings = new JSONObject();
        for (Settings.Setting<?> setting : Settings.all()) {
            if (!setting.visible) continue;
            if (setting.type == Settings.Type.BOOLEAN) {
                settings.put(setting.key, SettingsStore.getBoolean(context, setting.key));
            } else if (setting.type == Settings.Type.INT) {
                settings.put(setting.key, SettingsStore.getInt(context, setting.key));
            }
        }
        raw.put("runtimeSettings", settings);
        return raw;
    }

    private static PackageSnapshot packageSnapshot(Context context) {
        return new PackageSnapshot(
                new PackageVersion(true, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                installedPackage(context, "com.zing.zalo"));
    }

    @SuppressWarnings("deprecation")
    private static PackageVersion installedPackage(Context context, String packageName) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return new PackageVersion(true, info.versionName == null ? "" : info.versionName,
                    Math.max(0L, code));
        } catch (PackageManager.NameNotFoundException ignored) {
            return new PackageVersion(false, "", 0L);
        }
    }

    private static String setupState(
            boolean zaloPresent, boolean schemaValid, List<SelfCheckData.Row> rows,
            SelfCheckData.Counts counts) {
        if (!zaloPresent || !schemaValid || counts.failed > 0) return "failed";
        if (rows.isEmpty() || counts.stale > 0) return "warning";
        return "ready";
    }

    private static boolean systemPropertyEnabled(String key) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            String value = (String) properties.getMethod(
                    "get", String.class, String.class).invoke(null, key, "0");
            return "1".equals(value) || "true".equalsIgnoreCase(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String bounded(String value, int maxBytes) {
        return DiagnosticReportContract.boundedToken(value, maxBytes);
    }

    private static String utc(long timeMs) {
        SimpleDateFormat format = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(timeMs));
    }

    static final class Draft {
        final String reportId;
        final String category;
        final String description;
        final String json;

        Draft(String reportId, String category, String description, String json) {
            this.reportId = reportId;
            this.category = category;
            this.description = description;
            this.json = json;
        }
    }

    private static final class PackageSnapshot {
        final PackageVersion module;
        final PackageVersion zalo;

        PackageSnapshot(PackageVersion module, PackageVersion zalo) {
            this.module = module;
            this.zalo = zalo;
        }
    }

    private static final class PackageVersion {
        final boolean present;
        final String versionName;
        final long versionCode;

        PackageVersion(boolean present, String versionName, long versionCode) {
            this.present = present;
            this.versionName = versionName;
            this.versionCode = versionCode;
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("present", present);
            json.put("versionName", bounded(versionName, 256));
            json.put("versionCode", versionCode);
            return json;
        }
    }
}
