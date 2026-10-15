package com.ez.zalopatch;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

/** Disposable preference transactions model Android's memory update before disk acknowledgement. */
public final class SettingsChangesTest {
    private static final String A = Tweaks.KEY_HIDE_DISCOVERY_TAB;
    private static final String B = Tweaks.KEY_HIDE_TIMELINE_TAB;

    private FrameworkConfigPublicationTest.TestContext baseline() throws Exception {
        FrameworkConfigPublicationTest.TestContext context = new FrameworkConfigPublicationTest.TestContext();
        Map<String, String> values = new HashMap<>();
        for (Settings.Setting<?> setting : Settings.all()) values.put(setting.key, String.valueOf(setting.defaultValue));
        values.put(NotificationRuleStore.MIRROR_KEY, NotificationRuleStore.encode(NotificationRuleStore.RuleSet.empty()));
        assertTrue(SettingsChanges.acknowledge(context,
                new ConfigSnapshot(values, "running", 0, BuildConfig.VERSION_CODE)));
        return context;
    }

    @Test public void fastTogglesCountDifferencesAndReverseToRunningBaseline() throws Exception {
        var context = baseline();
        for (int index = 0; index < 51; index++) {
            assertTrue(SettingsStore.putBoolean(context, A, (index & 1) == 0));
            assertEquals((index & 1) == 0 ? 1 : 0, SettingsChanges.pendingCount(context));
        }
        assertTrue(SettingsStore.putBoolean(context, B, true));
        assertEquals(2, SettingsChanges.pendingCount(context));
        assertTrue(SettingsStore.putBoolean(context, A, false));
        assertEquals(1, SettingsChanges.pendingCount(context));
        assertTrue(SettingsStore.putBoolean(context, B, false));
        assertEquals(0, SettingsChanges.pendingCount(context));
    }

    @Test public void everyAcceptedEditIsOneCommitWithIntentAndRevision() throws Exception {
        var context = baseline();
        TweakStore.preferences(context);
        var file = context.files.get(Tweaks.PREFS_NAME);
        assertTrue(SettingsStore.putBoolean(context, A, true));
        assertEquals(1, file.commits);
        assertEquals(Boolean.TRUE, file.values.get(A));
        assertEquals(1L, file.values.get(SettingsStore.GENERATION));
        assertFalse(file.values.containsKey(ConfigSnapshot.KEY));
        assertTrue(SettingsStore.putBoolean(context, A, true));
        assertEquals(1, file.commits);
    }

    @Test public void batchEditHasNoPartialPublishedRevision() throws Exception {
        var context = baseline();
        TweakStore.preferences(context);
        var file = context.files.get(Tweaks.PREFS_NAME);
        file.onCommit = () -> {
            assertEquals(Boolean.TRUE, file.values.get(A));
            assertEquals(Boolean.TRUE, file.values.get(B));
            assertEquals(1L, SettingsChanges.generation(context));
        };
        assertTrue(SettingsStore.save(context, Map.of(A, true, B, true)));
        assertEquals(1, file.commits);
        assertEquals(2, SettingsChanges.pendingCount(context));
    }

    @Test public void validStartupAckMovesBaselineEvenWithNewerSavedIntent() throws Exception {
        var context = baseline();
        assertTrue(SettingsStore.putBoolean(context, A, true));
        ConfigSnapshot loaded = new ConfigSnapshot(Map.of(A, "true"), "restart", 1, BuildConfig.VERSION_CODE);
        assertTrue(SettingsStore.putBoolean(context, B, true));
        assertTrue(SettingsChanges.acknowledge(context, loaded));
        assertEquals(1, SettingsChanges.pendingCount(context));
        assertTrue(SettingsStore.putBoolean(context, B, false));
        assertEquals(0, SettingsChanges.pendingCount(context));
        assertTrue(SettingsStore.putBoolean(context, A, false));
        assertEquals(1, SettingsChanges.pendingCount(context));
    }

    @Test public void persistedBaselineAndIntentSurviveNewContext() throws Exception {
        var context = baseline();
        assertTrue(SettingsStore.putBoolean(context, A, true));
        var reopened = new FrameworkConfigPublicationTest.TestContext();
        reopened.files.putAll(context.files);
        assertTrue(SettingsStore.getBoolean(reopened, A));
        assertEquals(1, SettingsChanges.pendingCount(reopened));
        assertTrue(SettingsStore.putBoolean(reopened, A, false));
        assertFalse(SettingsChanges.needsRestart(reopened));
    }

    @Test public void failedCommitRejectsCachedValueAndRetryRepairsBeforeSaving() throws Exception {
        var context = baseline();
        TweakStore.preferences(context);
        var file = context.files.get(Tweaks.PREFS_NAME);
        file.commitResult = false;
        assertFalse(SettingsStore.putBoolean(context, A, true));
        assertFalse(SettingsStore.getBoolean(context, A));
        assertEquals(0L, SettingsChanges.generation(context));
        assertEquals(0, SettingsChanges.pendingCount(context));
        assertFalse(SettingsStore.canPublish(context));
        file.commitResult = true;
        assertTrue(SettingsStore.putBoolean(context, B, true));
        assertTrue(SettingsStore.canPublish(context));
        assertFalse(SettingsStore.getBoolean(context, A));
        assertEquals(1, SettingsChanges.pendingCount(context));
    }

    @Test public void listAndRulesReverseAgainstTypedBaseline() throws Exception {
        var context = baseline();
        assertTrue(SettingsStore.putInt(context, Tweaks.KEY_DEFAULT_INBOX_FILTER, 2));
        assertTrue(NotificationRuleStore.save(context, new NotificationRuleStore.RuleSet(
                java.util.List.of("promotion"), null, null, null)));
        assertEquals(2, SettingsChanges.pendingCount(context));
        assertTrue(NotificationRuleStore.save(context, NotificationRuleStore.RuleSet.empty()));
        assertTrue(SettingsStore.putInt(context, Tweaks.KEY_DEFAULT_INBOX_FILTER, 0));
        assertEquals(0, SettingsChanges.pendingCount(context));
    }

    @Test public void moduleLocalRetentionSavesWithoutRequiringRuntimeActivation() throws Exception {
        var context = baseline();
        assertTrue(SettingsStore.putInt(context, Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION, 100));
        assertEquals(100, SettingsStore.getInt(context, Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION));
        assertFalse(SettingsChanges.needsRestart(context));
        assertFalse(TweakStore.preferences(context).getAll().containsKey(ConfigSnapshot.KEY));
        var unknown = new FrameworkConfigPublicationTest.TestContext();
        SettingsChanges.markChanged(unknown, Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION);
        assertEquals(0, SettingsChanges.pendingCount(unknown));
    }

    @Test public void unknownActivationAndUpgradeRequireRestartWithoutClaimingRuntimeState() throws Exception {
        var context = new FrameworkConfigPublicationTest.TestContext();
        assertTrue(SettingsChanges.needsRestart(context));
        assertTrue(SettingsChanges.acknowledge(context, new ConfigSnapshot(Map.of(), "old", 0, BuildConfig.VERSION_CODE - 1)));
        assertTrue(SettingsChanges.needsRestart(context));
    }
}
