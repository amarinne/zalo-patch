package com.ez.zalopatch;

import android.content.Context;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Negative coverage for per-package configuration scope: original spelling, clone defaults,
 * missing/malformed clone publication, acknowledgement and initialization isolation.
 */
public final class InstanceConfigScopeTest {
    private static final String A = Tweaks.KEY_HIDE_DISCOVERY_TAB;
    private static final String B = Tweaks.KEY_HIDE_TIMELINE_TAB;
    private static final String CLONE_A = "vn.example.insta";
    private static final String CLONE_B = "vn.example.instb";

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Before public void resetInitialization() throws Exception {
        Field initialized = TweakStore.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        initialized.set(null, false);
        Field scopes = TweakStore.class.getDeclaredField("initializedScopes");
        scopes.setAccessible(true);
        ((Set<?>) scopes.get(null)).clear();
    }

    private FrameworkConfigPublicationTest.TestContext context() {
        FrameworkConfigPublicationTest.TestContext context =
                new FrameworkConfigPublicationTest.TestContext();
        context.dataDir = temporary.getRoot().getAbsolutePath();
        return context;
    }

    @Test public void threeScopesKeepChoicesPendingAndDefaultsSeparate() {
        FrameworkConfigPublicationTest.TestContext module = context();
        Context cloneA = HostPackages.scoped(module, CLONE_A);
        Context cloneB = HostPackages.scoped(module, CLONE_B);
        TweakStore.initialize(module);
        TweakStore.initialize(cloneA);
        TweakStore.initialize(cloneB);

        assertTrue(SettingsStore.putBoolean(module, A, true));
        assertTrue(SettingsStore.putBoolean(cloneB, B, true));

        assertTrue(SettingsStore.getBoolean(module, A));
        assertFalse(SettingsStore.getBoolean(cloneA, A));
        assertFalse(SettingsStore.getBoolean(cloneA, B));
        assertFalse(SettingsStore.getBoolean(cloneB, A));
        assertTrue(SettingsStore.getBoolean(cloneB, B));

        assertEquals(1, SettingsChanges.pendingCount(module));
        assertEquals(0, SettingsChanges.pendingCount(cloneA));
        assertEquals(1, SettingsChanges.pendingCount(cloneB));

        assertTrue(module.files.containsKey(Tweaks.PREFS_NAME));
        assertTrue(module.files.containsKey("settings_ui"));
        assertTrue(module.files.containsKey("settings_ui.host."
                + ZaloArtifactIdentity.sha256(CLONE_B)));
        assertTrue(module.files.containsKey(Tweaks.PREFS_NAME + ".host."
                + ZaloArtifactIdentity.sha256(CLONE_A)));
        assertTrue(module.files.containsKey(Tweaks.PREFS_NAME + ".host."
                + ZaloArtifactIdentity.sha256(CLONE_B)));
        assertNotEquals(module.files.get(Tweaks.PREFS_NAME).values,
                module.files.get(Tweaks.PREFS_NAME + ".host."
                        + ZaloArtifactIdentity.sha256(CLONE_A)).values);
    }

    @Test public void cloneNeverInheritsOriginalSnapshotRulesOrAcknowledgement() throws Exception {
        FrameworkConfigPublicationTest.TestContext module = context();
        Context clone = HostPackages.scoped(module, CLONE_A);
        TweakStore.initialize(module);
        TweakStore.initialize(clone);
        assertTrue(NotificationRuleStore.save(module, new NotificationRuleStore.RuleSet(
                java.util.List.of("promotion"), null, null, null)));
        assertEquals(1, NotificationRuleStore.load(module).total());
        assertEquals(0, NotificationRuleStore.load(clone).total());

        String original = new ConfigSnapshot(settings(null), "original", 0,
                BuildConfig.VERSION_CODE).encode();
        assertTrue(TweakStore.preferences(module).edit()
                .putString(ConfigSnapshot.KEY, original).commit());
        FrameworkConfig.acknowledge(module, "original");
        assertEquals("original", SettingsChanges.active(module).id);
        assertTrue(FrameworkConfig.hasPublishedSnapshot(module));

        assertFalse(FrameworkConfig.hasPublishedSnapshot(clone));
        assertNull(SettingsChanges.active(clone));
        assertEquals(0, SettingsChanges.pendingCount(clone));
        assertNull(ConfigSnapshot.providerSnapshotFor(CLONE_A, original, null));
        assertEquals(original, ConfigSnapshot.providerSnapshotFor(HostPackages.ZALO, original, null));

        assertTrue(TweakStore.preferences(clone).edit()
                .putString(ConfigSnapshot.KEY, "not a snapshot").commit());
        assertFalse(FrameworkConfig.hasPublishedSnapshot(clone));
        assertNull(SettingsChanges.active(clone));
        assertNull(ConfigSnapshot.decode("not a snapshot", BuildConfig.VERSION_CODE));
    }

    @Test public void foreignSnapshotIdCannotAcknowledgeClonePending() throws Exception {
        FrameworkConfigPublicationTest.TestContext module = context();
        Context clone = HostPackages.scoped(module, CLONE_A);
        TweakStore.initialize(module);
        TweakStore.initialize(clone);
        assertTrue(SettingsStore.putBoolean(clone, A, true));
        assertEquals(1, SettingsChanges.pendingCount(clone));
        assertTrue(TweakStore.preferences(module).edit().putString(ConfigSnapshot.KEY,
                new ConfigSnapshot(settings(null), "original", 0,
                        BuildConfig.VERSION_CODE).encode()).commit());

        FrameworkConfig.acknowledge(clone, "original");
        assertEquals(1, SettingsChanges.pendingCount(clone));
        assertNull(SettingsChanges.active(clone));

        Map<String, String> cloneValues = settings(Map.of(A, "true"));
        ConfigSnapshot own = new ConfigSnapshot(cloneValues, "clone", 0,
                BuildConfig.VERSION_CODE);
        assertTrue(TweakStore.preferences(clone).edit()
                .putString(ConfigSnapshot.KEY, own.encode()).commit());
        FrameworkConfig.acknowledge(clone, "original");
        assertEquals(1, SettingsChanges.pendingCount(clone));
        FrameworkConfig.acknowledge(clone, "clone");
        assertEquals(0, SettingsChanges.pendingCount(clone));
        assertEquals("clone", SettingsChanges.active(clone).id);
    }

    @Test public void applyPublishesOnlyTheSelectedScope() throws Exception {
        FrameworkConfigPublicationTest.TestContext module = context();
        Context cloneA = HostPackages.scoped(module, CLONE_A);
        Context cloneB = HostPackages.scoped(module, CLONE_B);
        TweakStore.initialize(module);
        TweakStore.initialize(cloneA);
        TweakStore.initialize(cloneB);
        assertTrue(SettingsStore.putBoolean(module, A, true));
        assertTrue(SettingsStore.putBoolean(cloneA, B, true));

        Field expected = FrameworkConfig.class.getDeclaredField("remoteExpected");
        Field service = FrameworkConfig.class.getDeclaredField("service");
        expected.setAccessible(true);
        service.setAccessible(true);
        Object previousExpected = expected.get(null);
        Object previousService = service.get(null);
        try {
            expected.set(null, false);
            service.set(null, null);
            assertTrue(FrameworkConfig.apply(module));
            assertTrue(FrameworkConfig.apply(cloneA));
        } finally {
            expected.set(null, previousExpected);
            service.set(null, previousService);
        }

        ConfigSnapshot original = ConfigSnapshot.decode(TweakStore.preferences(module)
                .getString(ConfigSnapshot.KEY, null), BuildConfig.VERSION_CODE);
        ConfigSnapshot clone = ConfigSnapshot.decode(TweakStore.preferences(cloneA)
                .getString(ConfigSnapshot.KEY, null), BuildConfig.VERSION_CODE);
        assertNotNull(original);
        assertNotNull(clone);
        assertEquals("true", original.values.get(A));
        assertEquals("false", clone.values.get(A));
        assertEquals("true", clone.values.get(B));
        assertNotEquals(original.id, clone.id);
        assertFalse(FrameworkConfig.hasPublishedSnapshot(cloneB));
        assertEquals(0, SettingsChanges.pendingCount(cloneB));
    }

    @Test public void initializationAndApplicationContextStayInTheirScope() {
        FrameworkConfigPublicationTest.TestContext module = context();
        Context cloneA = HostPackages.scoped(module, CLONE_A);
        Context cloneB = HostPackages.scoped(module, CLONE_B);
        TweakStore.initialize(module);
        assertTrue(SettingsStore.putBoolean(module, A, true));
        TweakStore.initialize(cloneA);

        assertTrue(TweakStore.preferences(module).getBoolean(A, false));
        assertFalse(TweakStore.preferences(cloneA).getBoolean(A, true));
        assertTrue(TweakStore.preferences(cloneA).contains(A));
        assertSame(cloneA, cloneA.getApplicationContext());
        assertEquals("", HostPackages.scopeSuffix(module));
        assertEquals(HostPackages.ZALO, HostPackages.transportHost(module));
        assertEquals(CLONE_A, HostPackages.transportHost(cloneA));
        assertEquals(ZaloArtifactIdentity.sha256(CLONE_A), HostPackages.scopeSuffix(cloneA));
        assertTrue(((Context) cloneA).getDatabasePath("notification_history.db").getPath()
                .contains("hosts/" + ZaloArtifactIdentity.sha256(CLONE_A) + "/databases"));
    }

    @Test public void originalWireNamesDoNotMoveAndClonesStayDeterministic() {
        String canonical = "debug.zp." + Integer.toHexString(A.hashCode());
        assertEquals(canonical, SettingsPropertyMirror.propertyName(null, A));
        assertEquals(canonical, SettingsPropertyMirror.propertyName(HostPackages.ZALO, A));
        String cloneA = SettingsPropertyMirror.propertyName(CLONE_A, A);
        String cloneB = SettingsPropertyMirror.propertyName(CLONE_B, A);
        assertEquals(cloneA, SettingsPropertyMirror.propertyName(CLONE_A, A));
        assertNotEquals(canonical, cloneA);
        assertNotEquals(cloneA, cloneB);
        assertTrue(cloneA.startsWith("debug.zp."
                + ZaloArtifactIdentity.sha256(CLONE_A).substring(0, 9) + "."));
        assertTrue(cloneA.length() + ".127".length() <= 31);

        assertEquals(ConfigSnapshot.GROUP, ConfigSnapshot.remoteGroup(null));
        assertEquals(ConfigSnapshot.GROUP, ConfigSnapshot.remoteGroup(HostPackages.ZALO));
        assertEquals(ConfigSnapshot.GROUP + "." + ZaloArtifactIdentity.sha256(CLONE_A),
                ConfigSnapshot.remoteGroup(CLONE_A));
        assertNotEquals(ConfigSnapshot.remoteGroup(CLONE_A),
                ConfigSnapshot.remoteGroup(CLONE_B));
    }

    @Test public void explicitHostAndCallerSelectionFailClosed() {
        assertTrue(HostPackages.explicitHostAllowed(1000, 1000, false));
        assertTrue(HostPackages.explicitHostAllowed(2000, 1000, true));
        assertFalse(HostPackages.explicitHostAllowed(2000, 1000, false));

        Set<String> trusted = HostPackages.parse("vn.example.insta\nvn.example.instb");
        assertTrue(HostPackages.allows(trusted, "vn.example.insta"));
        assertTrue(HostPackages.allows(trusted, HostPackages.ZALO));
        assertFalse(HostPackages.allows(trusted, "vn.example.insta.clone"));
        assertFalse(HostPackages.allows(trusted, "com.ez.zalopatch"));
        assertFalse(HostPackages.allows(trusted, null));
        assertNull(HostPackages.selectCaller(trusted,
                new String[]{"vn.example.insta", "vn.example.instb"}));
        assertNull(HostPackages.selectCaller(trusted, new String[]{"com.example.unknown"}));
    }

    private static Map<String, String> settings(Map<String, String> overrides) throws Exception {
        Map<String, String> values = new HashMap<>();
        for (Settings.Setting<?> setting : Settings.all()) {
            values.put(setting.key, String.valueOf(setting.defaultValue));
        }
        values.put(NotificationRuleStore.MIRROR_KEY,
                NotificationRuleStore.encode(NotificationRuleStore.RuleSet.empty()));
        if (overrides != null) values.putAll(overrides);
        return values;
    }
}
