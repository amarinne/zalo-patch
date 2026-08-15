package com.ez.zalopatch;

import android.content.Context;
import android.content.SharedPreferences;

public final class DiagnosticsState {
    public static final String KEY_RUNTIME_DISCOVERY_REQUESTED = "diagnostics.runtime_discovery_requested";
    public static final String KEY_RUNTIME_DISCOVERY_LAST_VERSION_CODE = "diagnostics.runtime_discovery_last_version_code";

    private DiagnosticsState() {
    }

    public static boolean isRuntimeDiscoveryRequested(Context context) {
        return TweakStore.preferences(context).getBoolean(KEY_RUNTIME_DISCOVERY_REQUESTED, false);
    }

    public static long runtimeDiscoveryLastVersionCode(Context context) {
        return TweakStore.preferences(context).getLong(KEY_RUNTIME_DISCOVERY_LAST_VERSION_CODE, -1L);
    }

    public static boolean requestRuntimeDiscovery(Context context) {
        long installedVersion = SymbolSchema.installedZaloVersionCode(context);
        if (installedVersion > 0L
                && installedVersion == runtimeDiscoveryLastVersionCode(context)) {
            return true;
        }
        RemapEvidenceStore.clear(context);
        boolean committed = TweakStore.preferences(context)
                .edit()
                .putBoolean(KEY_RUNTIME_DISCOVERY_REQUESTED, true)
                .commit();
        TweakStore.initialize(context);
        return committed;
    }

    public static void clearRuntimeDiscoveryRequest(Context context) {
        TweakStore.preferences(context)
                .edit()
                .putBoolean(KEY_RUNTIME_DISCOVERY_REQUESTED, false)
                .commit();
        TweakStore.initialize(context);
    }

    public static void completeRuntimeDiscovery(Context context, long versionCode) {
        SharedPreferences.Editor editor = TweakStore.preferences(context).edit();
        editor.putBoolean(KEY_RUNTIME_DISCOVERY_REQUESTED, false);
        editor.putLong(KEY_RUNTIME_DISCOVERY_LAST_VERSION_CODE, versionCode);
        editor.apply();
        TweakStore.initialize(context);
    }

}
