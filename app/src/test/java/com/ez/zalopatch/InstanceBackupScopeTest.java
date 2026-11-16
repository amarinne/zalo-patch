package com.ez.zalopatch;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A picked document belongs to the target captured when the picker opened. Format 2 stays
 * portable, but importing it must never write the original or another registered clone.
 */
public final class InstanceBackupScopeTest {
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

    private FrameworkConfigPublicationTest.TestContext module() {
        FrameworkConfigPublicationTest.TestContext context =
                new FrameworkConfigPublicationTest.TestContext();
        context.dataDir = temporary.getRoot().getAbsolutePath();
        return context;
    }

    @Test public void formatTwoImportWritesOnlyTheCapturedSelectedTarget() throws Exception {
        FrameworkConfigPublicationTest.TestContext module = module();
        Context cloneA = HostPackages.scoped(module, CLONE_A);
        Context cloneB = HostPackages.scoped(module, CLONE_B);
        TweakStore.initialize(module);
        TweakStore.initialize(cloneA);
        TweakStore.initialize(cloneB);

        assertFalse(SettingsStore.getBoolean(module, A));
        assertFalse(SettingsStore.getBoolean(cloneA, A));
        assertTrue(SettingsStore.putBoolean(cloneA, A, true));
        assertTrue(SettingsStore.putBoolean(cloneB, B, true));

        String exportedFromCloneA = SettingsBackup.exportJson(cloneA);
        JSONObject root = new JSONObject(exportedFromCloneA);
        assertEquals(2, root.getInt("format_version"));
        assertFalse(root.getJSONObject("settings").has("internal.host_packages"));

        int applied = SettingsBackup.importJson(cloneB, new ByteArrayInputStream(
                exportedFromCloneA.getBytes(StandardCharsets.UTF_8)));

        assertTrue(applied > 0);
        assertTrue(SettingsStore.getBoolean(cloneB, A));
        // The whole portable snapshot replaces the selected scope, not just the exported key.
        assertFalse(SettingsStore.getBoolean(cloneB, B));

        // The original and the export source keep their own values.
        assertFalse(SettingsStore.getBoolean(module, A));
        assertFalse(SettingsStore.getBoolean(module, B));
        assertTrue(SettingsStore.getBoolean(cloneA, A));
        assertFalse(SettingsStore.getBoolean(cloneA, B));

        Map<String, ?> moduleValues = TweakStore.preferences(module).getAll();
        assertFalse(String.valueOf(moduleValues.get(A)).equals("true"));
        assertTrue(module.files.containsKey(Tweaks.PREFS_NAME + ".host."
                + ZaloArtifactIdentity.sha256(CLONE_A)));
        assertTrue(module.files.containsKey(Tweaks.PREFS_NAME + ".host."
                + ZaloArtifactIdentity.sha256(CLONE_B)));
    }

    @Test public void exportReadsOnlyTheCapturedTargetAndKeepsRulesInThePortableFormat()
            throws Exception {
        FrameworkConfigPublicationTest.TestContext module = module();
        Context cloneA = HostPackages.scoped(module, CLONE_A);
        Context cloneB = HostPackages.scoped(module, CLONE_B);
        TweakStore.initialize(module);
        TweakStore.initialize(cloneA);
        TweakStore.initialize(cloneB);

        assertTrue(SettingsStore.putBoolean(module, A, true));
        assertTrue(SettingsStore.putBoolean(cloneB, B, true));
        assertTrue(NotificationRuleStore.save(cloneB, new NotificationRuleStore.RuleSet(
                java.util.List.of("promotion"), null, null, null)));

        String exportedFromCloneB = SettingsBackup.exportJson(cloneB);
        JSONObject root = new JSONObject(exportedFromCloneB);
        assertTrue(root.getJSONObject("settings").getBoolean(B));
        assertFalse(root.getJSONObject("settings").getBoolean(A));
        assertEquals(1, root.getJSONObject("notification_rules")
                .toString().split("promotion", -1).length - 1);

        String exportedFromOriginal = SettingsBackup.exportJson(module);
        JSONObject original = new JSONObject(exportedFromOriginal);
        assertTrue(original.getJSONObject("settings").getBoolean(A));
        assertFalse(original.getJSONObject("settings").getBoolean(B));
    }
}
