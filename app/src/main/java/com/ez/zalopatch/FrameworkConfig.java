package com.ez.zalopatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** Module-process publication. This class never requests root or hot reloads hooks. */
final class FrameworkConfig {
    private static final Object serviceLock = new Object();
    private static volatile XposedService service;
    private static volatile boolean remoteExpected;

    private FrameworkConfig() {}

    static void initialize(Context context) {
        // A legacy framework has no service; never inherit modern transport expectation.
        if (BuildConfig.LSPATCH) { remoteExpected = false; return; }
        remoteExpected = context.getSharedPreferences("config_transport", Context.MODE_PRIVATE)
                .getBoolean("remote_expected", false);
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override public void onServiceBind(XposedService bound) {
                try {
                    boolean supported = ConfigSnapshot.remoteSupported(bound.getFrameworkProperties(),
                            XposedService.PROP_CAP_REMOTE);
                    synchronized (serviceLock) {
                        remoteExpected = supported;
                        context.getSharedPreferences("config_transport", Context.MODE_PRIVATE).edit()
                                .putBoolean("remote_expected", remoteExpected).apply();
                        service = supported ? bound : null;
                    }
                    if (supported) Log.i("ZaloPatch", "Config service framework=" + bound.getFrameworkName()
                            + " version=" + bound.getFrameworkVersion() + " remote=true");
                    new Thread(() -> refreshState(context), "config-service-sync").start();
                } catch (Throwable error) {
                    Log.i("ZaloPatch", "Config service unavailable " + error.getClass().getSimpleName());
                }
            }
            @Override public void onServiceDied(XposedService dead) {
                synchronized (serviceLock) {
                    if (service == dead) service = null;
                }
            }
        });
    }

    static boolean hasRemoteService() { return service != null; }

    static boolean usesRemoteTransport() { return remoteExpected || service != null; }

    static boolean hasPublishedSnapshot(Context context) {
        return ConfigSnapshot.decode(TweakStore.preferences(context).getString(ConfigSnapshot.KEY, null),
                BuildConfig.VERSION_CODE) != null;
    }

    static synchronized boolean apply(Context context) {
        return publish(context, true);
    }

    static synchronized boolean refreshState(Context context) {
        return publish(context, false);
    }

    private static boolean publish(Context context, boolean applySettings) {
        try {
            if (applySettings) TweakStore.initialize(context);
            long generation;
            Map<String, ?> persisted;
            synchronized (SettingsStore.LOCK) {
                if (!SettingsStore.canPublish(context)) return false;
                generation = SettingsChanges.generation(context);
                persisted = TweakStore.preferences(context).getAll();
            }
            SharedPreferences local = TweakStore.preferences(context);
            String encoded = local.getString(ConfigSnapshot.KEY, null);
            ConfigSnapshot previous = ConfigSnapshot.decode(encoded, BuildConfig.VERSION_CODE);
            if (previous == null) previous = ConfigSnapshot.previous(encoded, BuildConfig.VERSION_CODE);
            // Before snapshots existed, saved settings were already applied when no edits remained.
            if (!applySettings && previous == null && encoded == null
                    && SettingsChanges.pendingCount(context) == 0) applySettings = true;
            // State reconciliation must never publish pending per-toggle changes.
            if (!applySettings && previous == null) return false;
            Map<String, String> values = new TreeMap<>();
            if (applySettings) {
                for (Settings.Setting<?> setting : Settings.all()) {
                    Object value = persisted.get(setting.key);
                    values.put(setting.key, String.valueOf(value == null ? setting.defaultValue : value));
                }
                values.put(NotificationRuleStore.MIRROR_KEY,
                        NotificationRuleStore.encode(NotificationRuleStore.resolve(null,
                                (String) persisted.get(NotificationRuleStore.PREF_KEY))));
            } else {
                values.putAll(previous.values);
            }
            values.put(DiagnosticsState.KEY_RUNTIME_DISCOVERY_REQUESTED,
                    String.valueOf(Boolean.TRUE.equals(persisted.get(DiagnosticsState.KEY_RUNTIME_DISCOVERY_REQUESTED))));
            values.put(DiagnosticsState.KEY_RUNTIME_DISCOVERY_LAST_VERSION_CODE,
                    String.valueOf(persisted.get(DiagnosticsState.KEY_RUNTIME_DISCOVERY_LAST_VERSION_CODE) == null
                            ? -1L : persisted.get(DiagnosticsState.KEY_RUNTIME_DISCOVERY_LAST_VERSION_CODE)));
            for (String key : TweakStore.ARTIFACT_MIRROR_KEYS) {
                Object value = persisted.get(key);
                values.put(key, value instanceof String ? (String) value : "");
            }
            values.putAll(DexKitMirror.snapshot(context));
            values.put(SymbolCatalogMirror.MIRROR_KEY, SymbolCatalogMirror.snapshot(context,
                    SymbolSchema.installedZaloVersionCode(context)));
            ConfigSnapshot snapshot = new ConfigSnapshot(values,
                    applySettings || previous.moduleVersion != BuildConfig.VERSION_CODE
                            ? UUID.randomUUID().toString() : previous.id,
                    applySettings ? generation : previous.generation,
                    BuildConfig.VERSION_CODE);
            return publishSnapshot(local, snapshot.encode());
        } catch (Throwable error) {
            Log.i("ZaloPatch", "Config publication failed " + error.getClass().getSimpleName());
            return false;
        }
    }

    static boolean publishSnapshot(SharedPreferences local, String encoded) {
        XposedService bound;
        synchronized (serviceLock) {
            bound = service;
            if (bound == null && remoteExpected) return false;
        }
        // commit() is the IPC acknowledgment. A service-library readback may only be local cache.
        return ConfigSnapshot.publish(bound == null ? null
                        : () -> bound.getRemotePreferences(ConfigSnapshot.GROUP).edit()
                                .putString(ConfigSnapshot.KEY, encoded).commit(),
                () -> {
                    synchronized (serviceLock) {
                        // A replacement service has not acknowledged this publication.
                        if (service != bound || (bound == null && remoteExpected)) return false;
                        return local.edit().putString(ConfigSnapshot.KEY, encoded).commit();
                    }
                });
    }

    static synchronized void acknowledge(Context context, String snapshotId) {
        ConfigSnapshot published = ConfigSnapshot.decode(TweakStore.preferences(context)
                .getString(ConfigSnapshot.KEY, null), BuildConfig.VERSION_CODE);
        if (published != null && published.id.equals(snapshotId)) {
            // Newer edits do not invalidate evidence of what this process loaded.
            SettingsChanges.acknowledge(context, published);
        }
    }
}
