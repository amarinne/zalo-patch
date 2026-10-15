package com.ez.zalopatch;

import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.service.XposedService;

import static org.junit.Assert.*;

/** Exercises publication against disposable preferences, without Binder, root, or app data. */
public final class FrameworkConfigPublicationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private Field serviceField;
    private Field expectedField;
    private Object previousService;
    private Object previousExpected;

    @Before public void saveTransport() throws Exception {
        serviceField = FrameworkConfig.class.getDeclaredField("service");
        expectedField = FrameworkConfig.class.getDeclaredField("remoteExpected");
        serviceField.setAccessible(true);
        expectedField.setAccessible(true);
        previousService = serviceField.get(null);
        previousExpected = expectedField.get(null);
        expectedField.set(null, true);
    }

    @After public void restoreTransport() throws Exception {
        serviceField.set(null, previousService);
        expectedField.set(null, previousExpected);
    }

    @Test public void replacedServiceCannotPublishSuccessAndRetryUsesTheReboundService() throws Exception {
        Preferences local = new Preferences();
        local.values.put(ConfigSnapshot.KEY, "previous");
        Preferences replacementRemote = new Preferences();
        XposedService replacement = service(replacementRemote);
        Preferences oldRemote = new Preferences();
        oldRemote.onCommit = () -> bind(replacement);
        bind(service(oldRemote));

        assertFalse(FrameworkConfig.publishSnapshot(local.prefs, "in-flight"));
        assertEquals("previous", local.values.get(ConfigSnapshot.KEY));
        assertFalse(replacementRemote.values.containsKey(ConfigSnapshot.KEY));
        assertEquals(0, local.commits);

        assertTrue(FrameworkConfig.publishSnapshot(local.prefs, "retry"));
        assertEquals("retry", replacementRemote.values.get(ConfigSnapshot.KEY));
        assertEquals("retry", local.values.get(ConfigSnapshot.KEY));
        assertEquals(1, replacementRemote.commits);
    }

    @Test public void serviceDeathDuringCommitRequiresRebindWithoutProviderFallback() throws Exception {
        Preferences local = new Preferences();
        Preferences remote = new Preferences();
        remote.onCommit = () -> bind(null);
        bind(service(remote));

        assertFalse(FrameworkConfig.publishSnapshot(local.prefs, "in-flight"));
        assertFalse(FrameworkConfig.publishSnapshot(local.prefs, "while-dead"));
        assertEquals(0, local.commits);
        assertEquals(Boolean.TRUE, expectedField.get(null));

        Preferences rebound = new Preferences();
        bind(service(rebound));
        assertTrue(FrameworkConfig.publishSnapshot(local.prefs, "after-rebind"));
        assertEquals("after-rebind", rebound.values.get(ConfigSnapshot.KEY));
    }

    @Test public void failedRemoteCommitCannotUseItsUpdatedCacheAsDeliveryEvidence() throws Exception {
        Preferences local = new Preferences();
        Preferences remote = new Preferences();
        remote.commitResult = false;
        bind(service(remote));

        assertFalse(FrameworkConfig.publishSnapshot(local.prefs, "failed"));
        assertEquals("failed", remote.values.get(ConfigSnapshot.KEY));
        assertEquals(0, local.commits);

        remote.commitResult = true;
        assertTrue(FrameworkConfig.publishSnapshot(local.prefs, "failed"));
        assertEquals(2, remote.commits);
        assertEquals("failed", local.values.get(ConfigSnapshot.KEY));
    }

    @Test public void editDuringCommitSurvivesAcknowledgmentAgainstTheLoadedSnapshot() throws Exception {
        TestContext context = new TestContext();
        String a = Tweaks.KEY_HIDE_DISCOVERY_TAB;
        String b = Tweaks.KEY_HIDE_TIMELINE_TAB;
        assertTrue(SettingsStore.putBoolean(context, a, true));
        ConfigSnapshot snapshot = new ConfigSnapshot(Map.of(a, "true", b, "false"),
                "in-flight", SettingsChanges.generation(context), BuildConfig.VERSION_CODE);
        Preferences remote = new Preferences();
        remote.onCommit = () -> assertTrue(SettingsStore.putBoolean(context, b, true));
        bind(service(remote));
        assertTrue(FrameworkConfig.publishSnapshot(TweakStore.preferences(context), snapshot.encode()));
        FrameworkConfig.acknowledge(context, snapshot.id);
        assertEquals(1, SettingsChanges.pendingCount(context));
        assertEquals("true", SettingsChanges.active(context).values.get(a));
        assertEquals("false", SettingsChanges.active(context).values.get(b));

        remote.onCommit = () -> {};
        ConfigSnapshot latest = new ConfigSnapshot(Map.of(a, "true", b, "true"),
                "latest", SettingsChanges.generation(context), BuildConfig.VERSION_CODE);
        assertTrue(FrameworkConfig.publishSnapshot(TweakStore.preferences(context), latest.encode()));
        FrameworkConfig.acknowledge(context, "unknown");
        FrameworkConfig.acknowledge(context, snapshot.id);
        assertEquals(1, SettingsChanges.pendingCount(context));
        FrameworkConfig.acknowledge(context, latest.id);
        assertEquals(0, SettingsChanges.pendingCount(context));
        FrameworkConfig.acknowledge(context, snapshot.id);
        assertEquals("latest", SettingsChanges.active(context).id);
    }

    @Test public void importKeepsPendingWhenApplyCapturesValuesBeforeTheSave() throws Exception {
        TestContext context = new TestContext();
        context.dataDir = temporary.newFolder().getAbsolutePath();
        SharedPreferences prefs = TweakStore.preferences(context);
        Preferences file = context.files.get(Tweaks.PREFS_NAME);
        file.values.put(Tweaks.KEY_HIDE_DISCOVERY_TAB, false);
        file.values.put(Tweaks.KEY_HIDE_TIMELINE_TAB, false);
        ConfigSnapshot[] captured = new ConfigSnapshot[1];
        file.beforeCommit = () -> {
            file.beforeCommit = () -> {};
            captured[0] = new ConfigSnapshot(Map.of(Tweaks.KEY_HIDE_DISCOVERY_TAB,
                    String.valueOf(prefs.getBoolean(Tweaks.KEY_HIDE_DISCOVERY_TAB, false))),
                    "before-import-save", SettingsChanges.generation(context), BuildConfig.VERSION_CODE);
        };
        Field initialized = TweakStore.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        Object previous = initialized.get(null);
        try {
            initialized.set(null, true);
            assertEquals(2, SettingsBackup.importJson(context, backupInput()));
        } finally {
            initialized.set(null, previous);
        }

        assertEquals("false", captured[0].values.get(Tweaks.KEY_HIDE_DISCOVERY_TAB));
        assertTrue(prefs.getBoolean(Tweaks.KEY_HIDE_DISCOVERY_TAB, false));
        assertTrue(prefs.getBoolean(Tweaks.KEY_HIDE_TIMELINE_TAB, false));
        bind(service(new Preferences()));
        assertTrue(FrameworkConfig.publishSnapshot(prefs, captured[0].encode()));
        FrameworkConfig.acknowledge(context, captured[0].id);
        SettingsChanges.clearIfGeneration(context, captured[0].generation);
        assertEquals(2, SettingsChanges.pendingCount(context));
        SettingsChanges.markChanged(context, Tweaks.KEY_HIDE_DISCOVERY_TAB);
        assertEquals(2, SettingsChanges.pendingCount(context));
    }

    @Test public void failedImportDoesNotRecordAnUncommittedGeneration() throws Exception {
        TestContext context = new TestContext();
        SettingsChanges.markChanged(context, "already-pending");
        long generation = SettingsChanges.generation(context);
        TweakStore.preferences(context);
        context.files.get(Tweaks.PREFS_NAME).commitResult = false;

        try {
            SettingsBackup.importJson(context, backupInput());
            fail("failed preference commit accepted");
        } catch (IOException expected) {
            assertEquals("Preference write failed", expected.getMessage());
        }
        assertEquals(generation, SettingsChanges.generation(context));
        assertEquals(1, SettingsChanges.pendingCount(context));
    }

    @Test public void rulesOnlyImportAndEmptyRulesRemovalBothRequireANewApply() throws Exception {
        TestContext context = new TestContext();
        context.dataDir = temporary.newFolder().getAbsolutePath();
        SharedPreferences prefs = TweakStore.preferences(context);
        Field initialized = TweakStore.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        Object previous = initialized.get(null);
        bind(service(new Preferences()));
        try {
            initialized.set(null, true);
            for (String keyword : new String[]{"promotion", ""}) {
                ConfigSnapshot prior = new ConfigSnapshot(Map.of(NotificationRuleStore.MIRROR_KEY,
                        NotificationRuleStore.encode(NotificationRuleStore.load(context))),
                        "before-rules-" + keyword, SettingsChanges.generation(context), BuildConfig.VERSION_CODE);
                assertTrue(FrameworkConfig.publishSnapshot(prefs, prior.encode()));
                assertEquals(keyword.isEmpty() ? 0 : 1,
                        SettingsBackup.importJson(context, rulesInput(keyword)));
                assertEquals(keyword.isEmpty() ? java.util.Collections.emptyList() : java.util.List.of(keyword),
                        NotificationRuleStore.load(context).list(NotificationRuleStore.Type.KEYWORD_BLOCKLIST));
                assertTrue(SettingsChanges.generation(context) > prior.generation);
                assertEquals(java.util.Set.of(NotificationRuleStore.MIRROR_KEY),
                        TweakStore.preferences(context).getStringSet(SettingsStore.PENDING, null));
                FrameworkConfig.acknowledge(context, prior.id);
                SettingsChanges.clearIfGeneration(context, prior.generation);
                assertEquals(1, SettingsChanges.pendingCount(context));
                // The rule editor uses the same key, so another edit stays one pending item.
                SettingsChanges.markChanged(context, "notifications.custom_rules");
                assertEquals(1, SettingsChanges.pendingCount(context));
                SettingsChanges.clearIfGeneration(context, SettingsChanges.generation(context));
            }
        } finally {
            initialized.set(null, previous);
        }
    }

    @Test public void failedRulesOnlyImportDoesNotAdvanceGeneration() throws Exception {
        TestContext context = new TestContext();
        TweakStore.preferences(context);
        context.files.get(Tweaks.PREFS_NAME).commitResult = false;
        long generation = SettingsChanges.generation(context);
        try {
            SettingsBackup.importJson(context, rulesInput("promotion"));
            fail("failed rule preference commit accepted");
        } catch (IOException expected) {
            assertEquals("Preference write failed", expected.getMessage());
        }
        assertEquals(generation, SettingsChanges.generation(context));
        assertEquals(0, SettingsChanges.pendingCount(context));
    }

    @Test public void upgradePreservesAppliedSettingsAndKeepsNewerEditsPending() throws Exception {
        TestContext context = new TestContext();
        context.dataDir = temporary.newFolder().getAbsolutePath();
        SharedPreferences prefs = TweakStore.preferences(context);
        ConfigSnapshot old = new ConfigSnapshot(Map.of(Tweaks.KEY_HIDE_DISCOVERY_TAB, "true",
                NotificationRuleStore.MIRROR_KEY, NotificationRuleStore.encode(NotificationRuleStore.RuleSet.empty())), "old", 0, BuildConfig.VERSION_CODE - 1);
        prefs.edit().putString(ConfigSnapshot.KEY, old.encode())
                .putBoolean(Tweaks.KEY_HIDE_DISCOVERY_TAB, false).commit();
        SettingsChanges.markChanged(context, Tweaks.KEY_HIDE_DISCOVERY_TAB);
        Preferences remote = new Preferences();
        bind(service(remote));
        assertTrue(FrameworkConfig.refreshState(context));
        ConfigSnapshot upgraded = ConfigSnapshot.decode((String) remote.values.get(ConfigSnapshot.KEY),
                BuildConfig.VERSION_CODE);
        assertNotNull(upgraded);
        assertEquals("true", upgraded.values.get(Tweaks.KEY_HIDE_DISCOVERY_TAB));
        assertEquals(NotificationRuleStore.encode(NotificationRuleStore.RuleSet.empty()),
                upgraded.values.get(NotificationRuleStore.MIRROR_KEY));
        assertEquals(old.generation, upgraded.generation);
        assertNotEquals(old.id, upgraded.id);
        FrameworkConfig.acknowledge(context, upgraded.id);
        assertEquals(1, SettingsChanges.pendingCount(context));
    }

    @Test public void preSnapshotUpgradePreparesSavedSettingsOnlyWithoutPendingEdits() throws Exception {
        TestContext context = new TestContext();
        context.dataDir = temporary.newFolder().getAbsolutePath();
        SharedPreferences prefs = TweakStore.preferences(context);
        prefs.edit().putBoolean(Tweaks.KEY_HIDE_DISCOVERY_TAB, true).commit();
        Preferences remote = new Preferences();
        bind(service(remote));
        Field initialized = TweakStore.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        Object previous = initialized.get(null);
        try {
            initialized.set(null, true);
            SettingsChanges.markChanged(context, Tweaks.KEY_HIDE_DISCOVERY_TAB);
            assertFalse(FrameworkConfig.refreshState(context));
            assertFalse(FrameworkConfig.hasPublishedSnapshot(context));
            SettingsChanges.clearIfGeneration(context, SettingsChanges.generation(context));
            assertTrue(FrameworkConfig.refreshState(context));
            assertTrue(FrameworkConfig.hasPublishedSnapshot(context));
            assertEquals("true", ConfigSnapshot.decode((String) remote.values.get(ConfigSnapshot.KEY),
                    BuildConfig.VERSION_CODE).values.get(Tweaks.KEY_HIDE_DISCOVERY_TAB));
        } finally { initialized.set(null, previous); }
    }

    @Test public void completedAndCancelledDiscoveryRevokesThePublishedRequest() throws Exception {
        TestContext context = new TestContext();
        context.dataDir = temporary.newFolder().getAbsolutePath();
        SharedPreferences prefs = TweakStore.preferences(context);
        Preferences remote = new Preferences();
        bind(service(remote));
        ConfigSnapshot first = new ConfigSnapshot(Map.of(Tweaks.KEY_HIDE_DISCOVERY_TAB, "true",
                DiagnosticsState.KEY_RUNTIME_DISCOVERY_REQUESTED, "true"), "discovery", 0,
                BuildConfig.VERSION_CODE);
        assertTrue(FrameworkConfig.publishSnapshot(prefs, first.encode()));
        prefs.edit().putBoolean(Tweaks.KEY_HIDE_DISCOVERY_TAB, false).commit();
        SettingsChanges.markChanged(context, Tweaks.KEY_HIDE_DISCOVERY_TAB);
        Field initialized = TweakStore.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        Object previous = initialized.get(null);
        try {
            initialized.set(null, true);
            DiagnosticsState.completeRuntimeDiscovery(context, 261001905L);
            ConfigSnapshot completed = ConfigSnapshot.decode((String) remote.values.get(ConfigSnapshot.KEY),
                    BuildConfig.VERSION_CODE);
            assertEquals("false", completed.values.get(DiagnosticsState.KEY_RUNTIME_DISCOVERY_REQUESTED));
            assertEquals("261001905", completed.values.get(DiagnosticsState.KEY_RUNTIME_DISCOVERY_LAST_VERSION_CODE));
            assertEquals("true", completed.values.get(Tweaks.KEY_HIDE_DISCOVERY_TAB));
            prefs.edit().putBoolean(DiagnosticsState.KEY_RUNTIME_DISCOVERY_REQUESTED, true).commit();
            assertTrue(FrameworkConfig.refreshState(context));
            DiagnosticsState.clearRuntimeDiscoveryRequest(context);
            ConfigSnapshot cancelled = ConfigSnapshot.decode((String) remote.values.get(ConfigSnapshot.KEY),
                    BuildConfig.VERSION_CODE);
            assertEquals("false", cancelled.values.get(DiagnosticsState.KEY_RUNTIME_DISCOVERY_REQUESTED));
            assertEquals(first.id, cancelled.id);
            assertEquals(1, SettingsChanges.pendingCount(context));
        } finally { initialized.set(null, previous); }
    }

    private static ByteArrayInputStream rulesInput(String keyword) throws Exception {
        NotificationRuleStore.RuleSet rules = new NotificationRuleStore.RuleSet(
                keyword.isEmpty() ? java.util.Collections.emptyList() : java.util.List.of(keyword),
                null, null, null);
        String json = SettingsBackup.encodeSettings(Map.of(), 0, "test", rules);
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    private static ByteArrayInputStream backupInput() {
        String json = "{\"format_version\":1,\"settings\":{\"ui.hide_discovery_tab\":true,"
                + "\"ui.hide_timeline_tab\":true}}";
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    private void bind(XposedService service) {
        try {
            serviceField.set(null, service);
        } catch (IllegalAccessException error) {
            throw new AssertionError(error);
        }
    }

    @SuppressWarnings("unchecked")
    private static XposedService service(Preferences remote) throws Exception {
        // Seed the SDK's preference cache. No service interface or Binder call is needed.
        Constructor<XposedService> constructor = XposedService.class.getDeclaredConstructor(
                Class.forName("io.github.libxposed.service.IXposedService"));
        constructor.setAccessible(true);
        XposedService service = constructor.newInstance(new Object[]{null});
        Field cache = XposedService.class.getDeclaredField("mRemotePrefs");
        cache.setAccessible(true);
        ((Map<String, Object>) cache.get(service)).put(ConfigSnapshot.GROUP, remote.prefs);
        return service;
    }

    static final class TestContext extends ContextWrapper {
        final Map<String, Preferences> files = new HashMap<>();
        String dataDir;

        TestContext() { super(null); }

        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return files.computeIfAbsent(name, ignored -> new Preferences()).prefs;
        }

        @Override public android.content.Context getApplicationContext() { return this; }

        @Override public ApplicationInfo getApplicationInfo() {
            ApplicationInfo info = new ApplicationInfo();
            info.dataDir = dataDir;
            return info;
        }

        @Override public java.io.File getFilesDir() { return new java.io.File(dataDir, "files"); }
    }

    static final class Preferences {
        final Map<String, Object> values = new HashMap<>();
        boolean commitResult = true;
        int commits;
        Runnable beforeCommit = () -> {};
        Runnable onCommit = () -> {};
        final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        case "getString":
                        case "getInt":
                        case "getBoolean":
                        case "getLong":
                        case "getStringSet": return values.getOrDefault(args[0], args[1]);
                        case "getAll": return new HashMap<>(values);
                        case "edit": return editor();
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });

        private SharedPreferences.Editor editor() {
            Map<String, Object> updates = new HashMap<>();
            Set<String> removals = new HashSet<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "putString":
                            case "putBoolean":
                            case "putInt":
                            case "putLong":
                            case "putStringSet": updates.put((String) args[0], args[1]); return proxy;
                            case "remove": removals.add((String) args[0]); return proxy;
                            case "apply":
                            case "commit":
                                beforeCommit.run();
                                removals.forEach(values::remove);
                                values.putAll(updates);
                                commits++;
                                onCommit.run();
                                return method.getName().equals("commit") ? commitResult : null;
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }
    }
}
