package com.ez.zalopatch.xposed.core;

import android.content.ContentValues;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.ez.zalopatch.HookConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

public final class SelfCheckRegistry {
    private static final Uri SELF_CHECK_URI = Uri.parse(
            "content://com.ez.zalopatch.config/self_check");
    private static final String MODULE_PACKAGE = "com.ez.zalopatch";
    private static final String RUN_ID = UUID.randomUUID().toString();
    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final AtomicBoolean UPDATE_REJECTION_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean FALLBACK_ONLY = new AtomicBoolean(false);

    private SelfCheckRegistry() {
    }

    public static synchronized void markInstalled(String feature, String target, int count) {
        Entry entry = entry(feature);
        if (entry.outcomeContradiction) return;
        if (entry.hitCount > 0) {
            entry.status = "active";
        } else if (!"active".equals(entry.status)) {
            entry.status = "installed_no_hits";
        }
        entry.target = target;
        entry.installCount = Math.max(entry.installCount, Math.max(count, 0));
        entry.error = "";
        mirror(feature, entry);
    }

    public static synchronized void markStale(String feature, String target, String reason) {
        Entry entry = entry(feature);
        if (entry.outcomeContradiction) return;
        entry.status = "stale";
        entry.target = target;
        entry.detail = "";
        entry.error = reason;
        mirror(feature, entry);
    }

    public static synchronized void markFailed(String feature, String target, Throwable throwable) {
        Entry entry = entry(feature);
        if (entry.outcomeContradiction) return;
        entry.status = "failed";
        entry.target = target;
        entry.error = errorText(throwable);
        mirror(feature, entry);
    }

    public static synchronized void markDisabled(String feature, String target) {
        Entry entry = entry(feature);
        if (entry.outcomeContradiction) return;
        entry.status = "disabled";
        entry.target = target;
        entry.error = "";
        mirror(feature, entry);
    }

    public static synchronized void markSuppressed(String feature, String target, String detail) {
        Entry entry = entry(feature);
        if (entry.outcomeContradiction) return;
        entry.status = "active";
        entry.target = target;
        entry.detail = detail == null ? "" : detail;
        entry.hitCount++;
        mirror(feature, entry);
    }

    public static void incrementHit(String feature, String target, String detail) {
        markSuppressed(feature, target, detail);
    }

    public static synchronized void markStatus(String feature, String status, String target, String detail, String error) {
        Entry entry = entry(feature);
        if (entry.outcomeContradiction) return;
        entry.status = status == null || status.isEmpty() ? "unknown" : status;
        entry.target = target == null ? "" : target;
        entry.detail = detail == null ? "" : detail;
        entry.error = error == null ? "" : error;
        mirror(feature, entry);
    }

    /** Keep a confirmed outcome contradiction visible even if another hook later intervenes. */
    public static synchronized void markOutcomeContradiction(String feature, String route, String detail) {
        org.json.JSONObject evidence = com.ez.zalopatch.OutcomeEvidence.parse(detail);
        if (evidence == null || !route.equals(evidence.optString("route"))) return;
        Entry entry = entry(feature);
        if (entry.outcomeContradiction) return;
        entry.outcomeContradiction = true;
        entry.status = "stale";
        entry.target = "outcome:" + route;
        entry.detail = evidence.toString();
        entry.error = "OutcomeDrift: visible_after_hide";
        mirror(feature, entry);
    }

    private static Entry entry(String feature) {
        Entry entry = ENTRIES.get(feature);
        if (entry != null) {
            return entry;
        }
        Entry created = new Entry();
        Entry existing = ENTRIES.putIfAbsent(feature, created);
        return existing != null ? existing : created;
    }

    private static String errorText(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        String message = throwable.getMessage();
        if (message == null || message.isEmpty()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + ": " + message;
    }

    private static void mirror(String feature, Entry entry) {
        entry.updatedAt = System.currentTimeMillis();
        Context context = HookConfig.resolveModuleContextForHooks();
        if (context == null) {
            return;
        }
        submit(context, feature, entry.status, entry.target, entry.installCount, entry.hitCount,
                entry.detail, entry.error, RUN_ID);
        if (HookConfig.isDebugEnabled()) {
            logForHooks("ZaloPatch: [SelfCheck] " + feature + " " + entry.status
                    + " hits=" + entry.hitCount + " target=" + entry.target);
        }
    }

    public static void submit(Context context, String feature, String status, String target,
                              int installCount, int hitCount, String detail, String error) {
        submit(context, feature, status, target, installCount, hitCount, detail, error, RUN_ID);
    }

    private static void submit(Context context, String feature, String status, String target,
                               int installCount, int hitCount, String detail, String error,
                               String runId) {
        ContentValues values = new ContentValues();
        try {
            Intent evidence = new Intent();
            com.ez.zalopatch.ZaloArtifactState.addEvidence(evidence, context);
            values.put("status", status);
            values.put("target", target);
            values.put("install_count", installCount);
            values.put("hit_count", hitCount);
            // Route provenance is diagnostic text, never the artifact identity gate. Keeping it
            // in detail also preserves it through the existing broadcast fallback transport.
            String routeDetail = "config_source=" + HookConfig.snapshotSource()
                    + " config_snapshot_id=" + HookConfig.snapshotId()
                    + " route=" + evidence.getStringExtra("route_profile_source")
                    + " route_profile_sha256=" + evidence.getStringExtra("route_profile_sha256");
            values.put("detail", detail == null || detail.isEmpty() ? routeDetail
                    : detail + "\n" + routeDetail);
            values.put("error", error);
            values.put("updated_at", System.currentTimeMillis());
            values.put("artifact_lightweight", evidence.getStringExtra("artifact_lightweight"));
            values.put("artifact_generation", evidence.getStringExtra("artifact_generation"));
            values.put("module_version_code", evidence.getIntExtra("module_version_code", -1));
            values.put("profile_sha256", evidence.getStringExtra("profile_sha256"));
            values.put("run_id", runId);
            values.put("config_snapshot_id", HookConfig.snapshotId());
            if (FALLBACK_ONLY.get()) {
                sendFallback(context, feature, values);
                return;
            }
            android.os.Bundle request = new android.os.Bundle();
            request.putString("feature", feature);
            request.putParcelable("values", values);
            android.os.Bundle response = context.getContentResolver().call(
                    Uri.parse("content://com.ez.zalopatch.config"),
                    "record_self_check", null, request);
            int updated = response == null ? 0 : response.getInt("recorded", 0);
            if (updated != 1 && UPDATE_REJECTION_LOGGED.compareAndSet(false, true)) {
                logForHooks("ZaloPatch: SelfCheck provider rejected update code=" + updated);
            }
            if (updated != 1) sendFallback(context, feature, values);
        } catch (Throwable updateThrowable) {
            if (Build.VERSION.SDK_INT >= 34) FALLBACK_ONLY.set(true);
            Log.i("ZaloPatch", "SelfCheck provider update failed: " + errorText(updateThrowable));
            if (UPDATE_REJECTION_LOGGED.compareAndSet(false, true)) {
                logForHooks("ZaloPatch: SelfCheck provider update threw "
                        + updateThrowable.getClass().getSimpleName() + "; using broadcast fallback");
            }
            sendFallback(context, feature, values);
        }
    }

    private static void sendFallback(Context context, String feature, ContentValues values) {
        if (Build.VERSION.SDK_INT < 34) return;
        try {
            Intent intent = new Intent("com.ez.zalopatch.SELF_CHECK_UPDATE");
            intent.setComponent(new ComponentName(MODULE_PACKAGE,
                    MODULE_PACKAGE + ".SelfCheckReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra("feature", feature);
            intent.putExtra("status", values.getAsString("status"));
            intent.putExtra("target", values.getAsString("target"));
            intent.putExtra("install_count", values.getAsInteger("install_count"));
            intent.putExtra("hit_count", values.getAsInteger("hit_count"));
            intent.putExtra("detail", values.getAsString("detail"));
            intent.putExtra("error", values.getAsString("error"));
            intent.putExtra("updated_at", values.getAsLong("updated_at"));
            intent.putExtra("artifact_lightweight", values.getAsString("artifact_lightweight"));
            intent.putExtra("artifact_generation", values.getAsString("artifact_generation"));
            intent.putExtra("module_version_code", values.getAsInteger("module_version_code"));
            intent.putExtra("profile_sha256", values.getAsString("profile_sha256"));
            intent.putExtra("run_id", values.getAsString("run_id"));
            intent.putExtra("config_snapshot_id", values.getAsString("config_snapshot_id"));
            android.os.Bundle optionsBundle = null;
            if (Build.VERSION.SDK_INT >= 34) {
                android.app.BroadcastOptions options = android.app.BroadcastOptions.makeBasic();
                options.setShareIdentityEnabled(true);
                optionsBundle = options.toBundle();
            }
            if (Build.VERSION.SDK_INT >= 34) {
                context.sendBroadcast(intent, null, optionsBundle);
            } else {
                context.sendBroadcast(intent);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void logForHooks(String message) {
        try {
            XpLog.i(message);
        } catch (Throwable ignored) {
        }
    }

    private static final class Entry {
        boolean outcomeContradiction;
        String status = "installed_no_hits";
        String target = "";
        String detail = "";
        String error = "";
        int installCount;
        int hitCount;
        long updatedAt;
    }
}
