package com.ez.zalopatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.test.AndroidTestCase;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import io.github.libxposed.service.XposedService;

/** Exercises the same rootless boundary as the settings UI, preserving local and remote state. */
public final class FrameworkConfigTest extends AndroidTestCase {
    public void testLegacyBlobRevocation() {
        String key = "internal.test_blob_revocation";
        String original = SettingsPropertyMirror.readBlob(key);
        try {
            assertTrue(SettingsPropertyMirror.writeBlob(key, "fixture"));
            assertEquals("fixture", SettingsPropertyMirror.readBlob(key));
            assertTrue(SettingsPropertyMirror.writeBlob(key, ""));
            assertNull(SettingsPropertyMirror.readBlob(key));
        } finally {
            assertTrue(SettingsPropertyMirror.writeBlob(key, original));
            assertEquals(original, SettingsPropertyMirror.readBlob(key));
        }
    }

    public void testRootlessPublicationAndRestartGeneration() throws Exception {
        Context context = getContext();
        awaitConfiguredTransport(context);
        TweakStore.initialize(context);
        SharedPreferences prefs = TweakStore.preferences(context);
        SharedPreferences ui = SettingsChanges.preferences(context);
        Map<String, ?> original = new HashMap<>(prefs.getAll());
        Map<String, ?> originalUi = new HashMap<>(ui.getAll());
        Map<String, ?> originalRoot = RootAccess.snapshotForTest(context);
        Field field = FrameworkConfig.class.getDeclaredField("service");
        field.setAccessible(true);
        XposedService service = (XposedService) field.get(null);
        SharedPreferences remote = service == null ? null : service.getRemotePreferences(ConfigSnapshot.GROUP);
        String previousRemote = remote == null ? null : remote.getString(ConfigSnapshot.KEY, null);
        String priorProperty = SettingsPropertyMirror.read(Tweaks.KEY_HIDE_DISCOVERY_TAB);
        try {
            RootAccess.setForTest(context, RootAccess.State.DENIED);
            assertTrue(ui.edit().clear().commit());
            prefs.edit().putBoolean(Tweaks.KEY_HIDE_DISCOVERY_TAB, true).commit();
            SettingsChanges.markChanged(context, Tweaks.KEY_HIDE_DISCOVERY_TAB);
            Bundle response = context.getContentResolver().call(ConfigProvider.URI,
                    "apply_config", null, null);
            assertNotNull(response);
            assertTrue(response.getBoolean("synced"));
            ConfigSnapshot first = ConfigSnapshot.decode(prefs.getString(ConfigSnapshot.KEY, null),
                    BuildConfig.VERSION_CODE);
            assertNotNull(first);
            assertEquals("true", first.values.get(Tweaks.KEY_HIDE_DISCOVERY_TAB));
            assertEquals(prefs.getString(ZaloArtifactState.KEY_STATUS, ""),
                    first.values.get(ZaloArtifactState.KEY_STATUS));
            assertNotNull(first.values.get(NotificationRuleStore.MIRROR_KEY));
            assertNotNull(first.values.get(DexKitMirror.MIRROR_CACHE_KEY));
            assertNotNull(first.values.get(DexKitMirror.MIRROR_BUDGET_KEY));
            assertNotNull(first.values.get(SymbolCatalogMirror.MIRROR_KEY));
            assertTrue(SettingsChanges.pendingCount(context) > 0);
            prefs.edit().putBoolean(Tweaks.KEY_HIDE_DISCOVERY_TAB, false).commit();
            SettingsChanges.markChanged(context, Tweaks.KEY_HIDE_DISCOVERY_TAB);
            assertTrue(FrameworkConfig.refreshState(context));
            ConfigSnapshot stateOnly = ConfigSnapshot.decode(prefs.getString(ConfigSnapshot.KEY, null),
                    BuildConfig.VERSION_CODE);
            assertEquals("true", stateOnly.values.get(Tweaks.KEY_HIDE_DISCOVERY_TAB));
            FrameworkConfig.acknowledge(context, first.id);
            assertTrue(SettingsChanges.pendingCount(context) > 0);
            assertTrue(FrameworkConfig.apply(context));
            ConfigSnapshot second = ConfigSnapshot.decode(prefs.getString(ConfigSnapshot.KEY, null),
                    BuildConfig.VERSION_CODE);
            assertEquals("false", second.values.get(Tweaks.KEY_HIDE_DISCOVERY_TAB));
            assertFalse(first.id.equals(second.id));
            FrameworkConfig.acknowledge(context, first.id);
            assertTrue(SettingsChanges.pendingCount(context) > 0);
            FrameworkConfig.acknowledge(context, second.id);
            assertEquals(0, SettingsChanges.pendingCount(context));
            assertEquals(RootAccess.State.DENIED, RootAccess.cached(context));
            assertEquals(priorProperty, SettingsPropertyMirror.read(Tweaks.KEY_HIDE_DISCOVERY_TAB));
            if (remote != null) {
                assertEquals(second.id, ConfigSnapshot.decode(remote.getString(ConfigSnapshot.KEY, null),
                        BuildConfig.VERSION_CODE).id);
            }
        } finally {
            restore(prefs, original);
            restore(ui, originalUi);
            RootAccess.restoreForTest(context, originalRoot);
            if (remote != null) {
                SharedPreferences.Editor editor = remote.edit();
                if (previousRemote == null) editor.remove(ConfigSnapshot.KEY);
                else editor.putString(ConfigSnapshot.KEY, previousRemote);
                assertTrue(editor.commit());
            }
        }
    }

    public void testUpgradeAndDiscoveryPreservePendingSettings() throws Exception {
        Context context = getContext();
        awaitConfiguredTransport(context);
        TweakStore.initialize(context);
        SharedPreferences prefs = TweakStore.preferences(context);
        SharedPreferences ui = SettingsChanges.preferences(context);
        SharedPreferences evidence = context.getSharedPreferences("remap_evidence_v1", Context.MODE_PRIVATE);
        Map<String, ?> original = new HashMap<>(prefs.getAll());
        Map<String, ?> originalUi = new HashMap<>(ui.getAll());
        Map<String, ?> originalEvidence = new HashMap<>(evidence.getAll());
        Map<String, ?> originalRoot = RootAccess.snapshotForTest(context);
        Field field = FrameworkConfig.class.getDeclaredField("service");
        field.setAccessible(true);
        XposedService service = (XposedService) field.get(null);
        SharedPreferences remote = service == null ? null : service.getRemotePreferences(ConfigSnapshot.GROUP);
        String previousRemote = remote == null ? null : remote.getString(ConfigSnapshot.KEY, null);
        try {
            RootAccess.setForTest(context, RootAccess.State.DENIED);
            assertTrue(ui.edit().clear().commit());
            seedDefaults(prefs);
            ConfigSnapshot old = new ConfigSnapshot(java.util.Collections.singletonMap(
                    Tweaks.KEY_HIDE_DISCOVERY_TAB, "true"), "previous-module", 0, BuildConfig.VERSION_CODE - 1);
            assertTrue(prefs.edit().putString(ConfigSnapshot.KEY, old.encode())
                    .putBoolean(Tweaks.KEY_HIDE_DISCOVERY_TAB, false)
                    .putLong(DiagnosticsState.KEY_RUNTIME_DISCOVERY_LAST_VERSION_CODE, -1L).commit());
            SettingsChanges.markChanged(context, Tweaks.KEY_HIDE_DISCOVERY_TAB);
            assertTrue(FrameworkConfig.refreshState(context));
            ConfigSnapshot upgraded = ConfigSnapshot.decode(prefs.getString(ConfigSnapshot.KEY, null), BuildConfig.VERSION_CODE);
            assertNotNull(upgraded);
            assertEquals("true", upgraded.values.get(Tweaks.KEY_HIDE_DISCOVERY_TAB));
            assertFalse(old.id.equals(upgraded.id));
            assertEquals(0L, upgraded.generation);
            FrameworkConfig.acknowledge(context, upgraded.id);
            assertEquals(1, SettingsChanges.pendingCount(context));
            assertTrue(DiagnosticsState.requestRuntimeDiscovery(context));
            assertEquals("true", ConfigSnapshot.decode(prefs.getString(ConfigSnapshot.KEY, null),
                    BuildConfig.VERSION_CODE).values.get(DiagnosticsState.KEY_RUNTIME_DISCOVERY_REQUESTED));
            DiagnosticsState.completeRuntimeDiscovery(context, SymbolSchema.installedZaloVersionCode(context));
            assertEquals("false", ConfigSnapshot.decode(prefs.getString(ConfigSnapshot.KEY, null),
                    BuildConfig.VERSION_CODE).values.get(DiagnosticsState.KEY_RUNTIME_DISCOVERY_REQUESTED));
            assertTrue(prefs.edit().putLong(DiagnosticsState.KEY_RUNTIME_DISCOVERY_LAST_VERSION_CODE, -1L).commit());
            assertTrue(DiagnosticsState.requestRuntimeDiscovery(context));
            DiagnosticsState.clearRuntimeDiscoveryRequest(context);
            ConfigSnapshot cleared = ConfigSnapshot.decode(prefs.getString(ConfigSnapshot.KEY, null), BuildConfig.VERSION_CODE);
            assertEquals("false", cleared.values.get(DiagnosticsState.KEY_RUNTIME_DISCOVERY_REQUESTED));
            assertEquals("true", cleared.values.get(Tweaks.KEY_HIDE_DISCOVERY_TAB));
            assertEquals(upgraded.id, cleared.id);
            assertEquals(1, SettingsChanges.pendingCount(context));
            if (remote != null) assertEquals(prefs.getString(ConfigSnapshot.KEY, null), remote.getString(ConfigSnapshot.KEY, null));
        } finally {
            restore(prefs, original);
            restore(ui, originalUi);
            restore(evidence, originalEvidence);
            RootAccess.restoreForTest(context, originalRoot);
            if (remote != null) {
                SharedPreferences.Editor editor = remote.edit();
                if (previousRemote == null) editor.remove(ConfigSnapshot.KEY);
                else editor.putString(ConfigSnapshot.KEY, previousRemote);
                assertTrue(editor.commit());
            }
        }
    }

    public void testPreSnapshotUpgradePreparesOnlyPreviouslyAppliedSettings() throws Exception {
        Context context = getContext();
        awaitConfiguredTransport(context);
        TweakStore.initialize(context);
        SharedPreferences prefs = TweakStore.preferences(context);
        SharedPreferences ui = SettingsChanges.preferences(context);
        Map<String, ?> original = new HashMap<>(prefs.getAll());
        Map<String, ?> originalUi = new HashMap<>(ui.getAll());
        Field field = FrameworkConfig.class.getDeclaredField("service");
        field.setAccessible(true);
        XposedService service = (XposedService) field.get(null);
        SharedPreferences remote = service == null ? null : service.getRemotePreferences(ConfigSnapshot.GROUP);
        String previousRemote = remote == null ? null : remote.getString(ConfigSnapshot.KEY, null);
        try {
            assertTrue(ui.edit().clear().commit());
            assertTrue(prefs.edit().remove(ConfigSnapshot.KEY).remove(SettingsStore.PENDING)
                    .putBoolean(Tweaks.KEY_HIDE_DISCOVERY_TAB, true).commit());
            assertTrue(FrameworkConfig.refreshState(context));
            assertEquals("true", ConfigSnapshot.decode(prefs.getString(ConfigSnapshot.KEY, null),
                    BuildConfig.VERSION_CODE).values.get(Tweaks.KEY_HIDE_DISCOVERY_TAB));
            assertTrue(prefs.edit().remove(ConfigSnapshot.KEY).putBoolean(Tweaks.KEY_HIDE_DISCOVERY_TAB, false).commit());
            SettingsChanges.markChanged(context, Tweaks.KEY_HIDE_DISCOVERY_TAB);
            assertFalse(FrameworkConfig.refreshState(context));
            assertFalse(FrameworkConfig.hasPublishedSnapshot(context));
            assertEquals(1, SettingsChanges.pendingCount(context));
        } finally {
            restore(prefs, original);
            restore(ui, originalUi);
            if (remote != null) {
                SharedPreferences.Editor editor = remote.edit();
                if (previousRemote == null) editor.remove(ConfigSnapshot.KEY);
                else editor.putString(ConfigSnapshot.KEY, previousRemote);
                assertTrue(editor.commit());
            }
        }
    }

    public void testDexkitProviderFailuresRefreshRestartBudgetWithoutApplyingSettings() throws Exception {
        Context context = getContext();
        awaitConfiguredTransport(context);
        TweakStore.initialize(context);
        SharedPreferences prefs = TweakStore.preferences(context);
        SharedPreferences ui = SettingsChanges.preferences(context);
        Map<String, ?> original = new HashMap<>(prefs.getAll());
        Map<String, ?> originalUi = new HashMap<>(ui.getAll());
        Field field = FrameworkConfig.class.getDeclaredField("service");
        field.setAccessible(true);
        XposedService service = (XposedService) field.get(null);
        SharedPreferences remote = service == null ? null : service.getRemotePreferences(ConfigSnapshot.GROUP);
        String previousRemote = remote == null ? null : remote.getString(ConfigSnapshot.KEY, null);
        android.content.pm.PackageInfo host = context.getPackageManager()
                .getPackageInfo("com.zing.zalo", 0);
        long versionCode = android.os.Build.VERSION.SDK_INT >= 28
                ? host.getLongVersionCode() : host.versionCode;
        String scope = DexKitPilotPolicy.budgetScope(versionCode, host.lastUpdateTime,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT,
                BuildConfig.VERSION_CODE);
        try {
            assertTrue(prefs.edit().putString(DexKitStore.KEY_SCAN_SCOPE, scope)
                    .putInt(DexKitStore.KEY_SCAN_FAILURES, 0)
                    .putLong(DexKitStore.KEY_SCAN_LAST_ATTEMPT_MS, 0L)
                    .putLong(DexKitStore.KEY_SCAN_SLOT_MS, 0L)
                    .putBoolean(Tweaks.KEY_HIDE_DISCOVERY_TAB, true).commit());
            assertTrue(FrameworkConfig.apply(context));
            ConfigSnapshot applied = ConfigSnapshot.decode(prefs.getString(ConfigSnapshot.KEY, null),
                    BuildConfig.VERSION_CODE);
            assertNotNull(applied);
            FrameworkConfig.acknowledge(context, applied.id);
            assertTrue(prefs.edit().putBoolean(Tweaks.KEY_HIDE_DISCOVERY_TAB, false).commit());
            SettingsChanges.markChanged(context, Tweaks.KEY_HIDE_DISCOVERY_TAB);
            Bundle extras = new Bundle();
            extras.putLong("version_code", versionCode);
            extras.putLong("last_update_time", host.lastUpdateTime);
            for (int failures = 1; failures <= DexKitStore.MAX_FAILURES; failures++) {
                Bundle response = context.getContentResolver().call(ConfigProvider.URI,
                        "record_dexkit_scan_failure", null, extras);
                assertNotNull(response);
                assertTrue(response.getBoolean("recorded"));
                ConfigSnapshot refreshed = ConfigSnapshot.decode(prefs.getString(ConfigSnapshot.KEY, null),
                        BuildConfig.VERSION_CODE);
                assertNotNull(refreshed);
                DexKitMirror.Budget budget = DexKitMirror.decodeBudget(
                        refreshed.values.get(DexKitMirror.MIRROR_BUDGET_KEY));
                assertNotNull(budget);
                assertEquals(failures, budget.failures);
                assertEquals(scope, budget.scope);
                assertEquals(failures < DexKitStore.MAX_FAILURES, DexKitMirror.claimFromMirror(
                        budget, scope, System.currentTimeMillis()).allowed);
                assertEquals(applied.id, refreshed.id);
                assertEquals(applied.generation, refreshed.generation);
                assertEquals("true", refreshed.values.get(Tweaks.KEY_HIDE_DISCOVERY_TAB));
                assertTrue(SettingsChanges.pendingCount(context) > 0);
                if (remote != null) {
                    assertEquals(prefs.getString(ConfigSnapshot.KEY, null),
                            remote.getString(ConfigSnapshot.KEY, null));
                }
            }
        } finally {
            restore(prefs, original);
            restore(ui, originalUi);
            if (remote != null) {
                SharedPreferences.Editor editor = remote.edit();
                if (previousRemote == null) editor.remove(ConfigSnapshot.KEY);
                else editor.putString(ConfigSnapshot.KEY, previousRemote);
                assertTrue(editor.commit());
            }
        }
    }

    public void testLegacyArtifactIgnoresInheritedRemoteExpectation() throws Exception {
        if (!BuildConfig.LSPATCH) return;
        Context context = getContext();
        TweakStore.initialize(context);
        SharedPreferences prefs = TweakStore.preferences(context);
        SharedPreferences transport = context.getSharedPreferences("config_transport", Context.MODE_PRIVATE);
        Map<String, ?> original = new HashMap<>(prefs.getAll());
        Map<String, ?> originalTransport = new HashMap<>(transport.getAll());
        Field expected = FrameworkConfig.class.getDeclaredField("remoteExpected");
        Field service = FrameworkConfig.class.getDeclaredField("service");
        expected.setAccessible(true);
        service.setAccessible(true);
        Object previousExpected = expected.get(null);
        Object previousService = service.get(null);
        try {
            assertTrue(transport.edit().putBoolean("remote_expected", true).commit());
            expected.set(null, true);
            service.set(null, null);
            FrameworkConfig.initialize(context);
            assertFalse((Boolean) expected.get(null));
            Bundle result = context.getContentResolver().call(ConfigProvider.URI, "apply_config", null, null);
            assertNotNull(result);
            assertTrue(result.getBoolean("synced"));
            assertEquals("provider_snapshot", result.getString("transport"));
            assertNotNull(ConfigSnapshot.decode(prefs.getString(ConfigSnapshot.KEY, null), BuildConfig.VERSION_CODE));
        } finally {
            restore(prefs, original);
            restore(transport, originalTransport);
            expected.set(null, previousExpected);
            service.set(null, previousService);
        }
    }

    public void testRemoteColdStartFreezesAndRejectsUnavailableSnapshots() throws Exception {
        Map<String, Object> before = resetHookState();
        try {
            final String[] live = {new ConfigSnapshot(coldValues(false), "cold", 1, BuildConfig.VERSION_CODE).encode()};
            HookConfig.initRemote(new HookConfig.RemoteConfig() {
                @Override public long properties() { return 3; }
                @Override public String readSnapshot() { return live[0]; }
            });
            assertEquals("framework-remote", HookConfig.snapshotSource());
            assertFalse(HookConfig.isEnabled(Tweaks.KEY_HIDE_DISCOVERY_TAB));
            assertEquals("", HookConfig.readBlob(DexKitMirror.MIRROR_CACHE_KEY));
            live[0] = new ConfigSnapshot(java.util.Collections.singletonMap(Tweaks.KEY_HIDE_DISCOVERY_TAB, "true"),
                    "later", 2, BuildConfig.VERSION_CODE).encode();
            HookConfig.reload();
            HookConfig.initRemote(new HookConfig.RemoteConfig() {
                @Override public long properties() { return 3; }
                @Override public String readSnapshot() { return live[0]; }
            });
            assertFalse(HookConfig.isEnabled(Tweaks.KEY_HIDE_DISCOVERY_TAB));
            assertEquals("cold", HookConfig.snapshotId());
            restoreHookState(before);
            before = resetHookState();
            HookConfig.initRemote(new HookConfig.RemoteConfig() {
                @Override public long properties() { return 3; }
                @Override public String readSnapshot() { throw new IllegalStateException("IPC unavailable"); }
            });
            assertTrue(HookConfig.hasAuthoritativeSnapshot());
            assertEquals("pending", HookConfig.getRawString(ZaloArtifactState.KEY_STATUS, "pending"));
            assertNull(HookConfig.readBlob(SymbolCatalogMirror.MIRROR_KEY));
            assertNull(HookConfig.readBlob(DexKitMirror.MIRROR_CACHE_KEY));
        } finally { restoreHookState(before); }
    }

    public void testAbsentRemoteCapabilitySelectsProviderSnapshotAtAttach() throws Exception {
        SharedPreferences prefs = TweakStore.preferences(getContext());
        String previous = prefs.getString(ConfigSnapshot.KEY, null);
        Map<String, Object> before = resetHookState();
        try {
            String encoded = new ConfigSnapshot(java.util.Collections.singletonMap(Tweaks.KEY_HIDE_DISCOVERY_TAB, "false"),
                    "provider-cold", 1, BuildConfig.VERSION_CODE).encode();
            assertTrue(prefs.edit().putString(ConfigSnapshot.KEY, encoded).commit());
            HookConfig.initRemote(new HookConfig.RemoteConfig() {
                @Override public long properties() { return 1; }
                @Override public String readSnapshot() { throw new AssertionError("remote absent"); }
            });
            assertFalse(HookConfig.hasAuthoritativeSnapshot());
            HookConfig.setAppContext(getContext());
            assertEquals("provider-snapshot", HookConfig.snapshotSource());
            assertFalse(HookConfig.isEnabled(Tweaks.KEY_HIDE_DISCOVERY_TAB));
            assertEquals("provider-cold", HookConfig.snapshotId());
            assertTrue(prefs.edit().putString(ConfigSnapshot.KEY, new ConfigSnapshot(
                    java.util.Collections.singletonMap(Tweaks.KEY_HIDE_DISCOVERY_TAB, "true"), "provider-later", 2,
                    BuildConfig.VERSION_CODE).encode()).commit());
            HookConfig.setAppContext(getContext());
            assertFalse(HookConfig.isEnabled(Tweaks.KEY_HIDE_DISCOVERY_TAB));
        } finally {
            SharedPreferences.Editor restore = prefs.edit();
            if (previous == null) restore.remove(ConfigSnapshot.KEY);
            else restore.putString(ConfigSnapshot.KEY, previous);
            assertTrue(restore.commit());
            restoreHookState(before);
        }
    }

    private static void awaitConfiguredTransport(Context context) {
        if (BuildConfig.LSPATCH || !context.getSharedPreferences("config_transport", Context.MODE_PRIVATE)
                .getBoolean("remote_expected", false)) return;
        long deadline = android.os.SystemClock.uptimeMillis() + 5_000L;
        while (!FrameworkConfig.hasRemoteService() && android.os.SystemClock.uptimeMillis() < deadline) {
            android.os.SystemClock.sleep(50L);
        }
        assertTrue("Expected framework config service did not bind", FrameworkConfig.hasRemoteService());
    }

    private static void seedDefaults(SharedPreferences prefs) {
        SharedPreferences.Editor editor = prefs.edit();
        for (Settings.Setting<?> setting : Settings.all()) {
            if (setting.type == Settings.Type.BOOLEAN) editor.putBoolean(setting.key, (Boolean) setting.defaultValue);
            else editor.putInt(setting.key, (Integer) setting.defaultValue);
        }
        editor.remove(NotificationRuleStore.PREF_KEY);
        assertTrue(editor.commit());
    }

    private static Map<String, String> coldValues(boolean hideDiscovery) {
        Map<String, String> values = new HashMap<>();
        values.put(Tweaks.KEY_HIDE_DISCOVERY_TAB, String.valueOf(hideDiscovery));
        values.put(DexKitMirror.MIRROR_CACHE_KEY, "");
        return values;
    }

    private static final String[] HOOK_FIELDS = {"snapshot", "authoritative", "remoteInitialized",
            "providerSnapshotChecked", "snapshotSource", "appContext", "moduleContext",
            "moduleContextUnavailable", "notificationRules", "legacySnapshot", "legacySnapshotChecked",
            "legacySnapshotInvalid", "legacySettings", "legacyRules"};

    private static Map<String, Object> resetHookState() throws Exception {
        Map<String, Object> before = new HashMap<>();
        for (String name : HOOK_FIELDS) {
            Field field = HookConfig.class.getDeclaredField(name);
            field.setAccessible(true);
            before.put(name, field.get(null));
            field.set(null, field.getType() == boolean.class ? false
                    : name.equals("snapshotSource") ? "legacy" : null);
        }
        return before;
    }

    private static void restoreHookState(Map<String, Object> before) throws Exception {
        for (String name : HOOK_FIELDS) {
            Field field = HookConfig.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, before.get(name));
        }
        HookConfig.reload();
    }

    private static void restore(SharedPreferences preferences, Map<String, ?> original) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : original.entrySet()) {
            Object value = entry.getValue();
            String key = entry.getKey();
            if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof java.util.Set) {
                @SuppressWarnings("unchecked") java.util.Set<String> set = (java.util.Set<String>) value;
                editor.putStringSet(key, set);
            }
        }
        assertTrue(editor.commit());
    }
}
