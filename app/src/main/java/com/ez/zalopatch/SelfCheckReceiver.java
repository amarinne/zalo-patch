package com.ez.zalopatch;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

public final class SelfCheckReceiver extends BroadcastReceiver {
    public static final String ACTION_UPDATE = "com.ez.zalopatch.SELF_CHECK_UPDATE";
    public static final String ACTION_RECORD_NOTIFICATION_HISTORY =
            "com.ez.zalopatch.RECORD_NOTIFICATION_HISTORY";
    public static final String ACTION_COMPLETE_RUNTIME_DISCOVERY =
            "com.ez.zalopatch.COMPLETE_RUNTIME_DISCOVERY";
    public static final String ACTION_RECORD_RUNTIME_DISCOVERY_EVIDENCE =
            "com.ez.zalopatch.RECORD_RUNTIME_DISCOVERY_EVIDENCE";
    public static final String EXTRA_VALUES = "values";
    private static final AtomicBoolean REJECTION_LOGGED = new AtomicBoolean(false);

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!senderAllowed(context)) {
            if (REJECTION_LOGGED.compareAndSet(false, true)) {
                Log.w("ZaloPatch", "SelfCheck fallback rejected sender "
                        + rejectedSenderDescription());
            }
            return;
        }
        handleAllowed(context, intent);
    }

    static void handleAllowed(Context context, Intent intent) {
        if (ACTION_COMPLETE_RUNTIME_DISCOVERY.equals(intent.getAction())) {
            DiagnosticsState.completeRuntimeDiscovery(
                    context, intent.getLongExtra("version_code", -1L));
            return;
        }
        if (ACTION_RECORD_RUNTIME_DISCOVERY_EVIDENCE.equals(intent.getAction())) {
            RemapEvidenceStore.record(context, intent.getLongExtra("version_code", -1L),
                    intent.getStringExtra("kind"), intent.getStringExtra("value"));
            return;
        }
        if (ACTION_RECORD_NOTIFICATION_HISTORY.equals(intent.getAction())) {
            Object extra = intent.getParcelableExtra(EXTRA_VALUES);
            if (!(extra instanceof ContentValues)) return;
            ContentValues values = (ContentValues) extra;
            if (SettingsStore.getBoolean(context, Tweaks.KEY_RECORD_NOTIFICATION_HISTORY)) {
                try {
                    long id = new NotificationHistoryStore(context).record(values);
                    if (id >= 0L || id == -2L) {
                        recordHistoryStatus(context, "active", intent, "");
                    } else {
                        recordHistoryStatus(context, "failed", intent, "history_insert_failed");
                    }
                } catch (Throwable throwable) {
                    recordHistoryStatus(context, "failed", intent,
                            throwable.getClass().getSimpleName());
                }
            }
            return;
        }
        if (!ACTION_UPDATE.equals(intent.getAction())) return;
        ContentValues values = selfCheckValues(intent);
        String feature = intent.getStringExtra("feature");
        if (feature != null && !feature.isEmpty()) {
            ConfigProvider.recordSelfCheck(context, feature, values);
        }
    }

    private static void recordHistoryStatus(
            Context context, String status, Intent intent, String error) {
        android.content.SharedPreferences preferences = TweakStore.preferences(context);
        String prefix = "selfcheck.notifications.history.";
        int hits = preferences.getInt(prefix + "hit_count", 0);
        android.content.SharedPreferences.Editor editor = preferences.edit()
                .putString(prefix + "status", status)
                .putString(prefix + "target", intent.getStringExtra("target") == null
                        ? "NotificationManager.notify" : intent.getStringExtra("target"))
                .putString(prefix + "detail", intent.getStringExtra("detail") == null
                        ? "" : intent.getStringExtra("detail"))
                .putString(prefix + "error", error == null ? "" : error)
                .putLong(prefix + "updated_at", System.currentTimeMillis());
        if ("active".equals(status)) editor.putInt(prefix + "hit_count", hits + 1);
        editor.apply();
        if ("failed".equals(status)) {
            ContentValues trace = new ContentValues();
            trace.put("status", status);
            trace.put("target", intent.getStringExtra("target"));
            trace.put("error", error);
            trace.put("updated_at", System.currentTimeMillis());
            trace.put("artifact_generation", preferences.getString(
                    ZaloArtifactState.KEY_GENERATION, ""));
            trace.put("run_id", preferences.getString("internal.selfcheck_run_id", ""));
            RuntimeStatusTraceStore.record(context, "notifications.history", trace);
        }
    }

    private static ContentValues selfCheckValues(Intent intent) {
        ContentValues values = new ContentValues();
        values.put("status", intent.getStringExtra("status"));
        values.put("target", intent.getStringExtra("target"));
        values.put("install_count", intent.getIntExtra("install_count", 0));
        values.put("hit_count", intent.getIntExtra("hit_count", 0));
        values.put("detail", intent.getStringExtra("detail"));
        values.put("error", intent.getStringExtra("error"));
        values.put("updated_at", intent.getLongExtra("updated_at", 0L));
        values.put("artifact_lightweight", intent.getStringExtra("artifact_lightweight"));
        values.put("artifact_generation", intent.getStringExtra("artifact_generation"));
        values.put("module_version_code", intent.getIntExtra("module_version_code", -1));
        values.put("profile_sha256", intent.getStringExtra("profile_sha256"));
        values.put("run_id", intent.getStringExtra("run_id"));
        return values;
    }

    private boolean senderAllowed(Context context) {
        if (Build.VERSION.SDK_INT < 34) {
            return false;
        }
        String senderPackage = getSentFromPackage();
        if (context.getPackageName().equals(senderPackage)
                || ZaloArtifactIdentity.PACKAGE_NAME.equals(senderPackage)) {
            return true;
        }
        int uid = getSentFromUid();
        if (uid == Process.myUid()) return true;
        try {
            return uid == context.getPackageManager().getPackageUid(
                    ZaloArtifactIdentity.PACKAGE_NAME, 0);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String rejectedSenderDescription() {
        if (Build.VERSION.SDK_INT < 34) {
            return "identity unavailable below API 34";
        }
        return "package=" + getSentFromPackage() + " uid=" + getSentFromUid();
    }
}
