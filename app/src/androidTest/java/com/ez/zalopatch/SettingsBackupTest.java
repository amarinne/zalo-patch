package com.ez.zalopatch;

import junit.framework.TestCase;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

public final class SettingsBackupTest extends TestCase {
    public void testPortableSettingsRoundTrip() throws Exception {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put(Tweaks.KEY_HIDE_DISCOVERY_TAB, true);
        source.put(Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION, 250);
        source.put(Tweaks.KEY_BACKUP_FREQUENT_PUSH, true);
        source.put(Tweaks.KEY_BACKUP_PUSH_INTERVAL, 3);

        String json = SettingsBackup.encodeSettings(source, 1234L, "test");
        Map<String, Object> decoded = SettingsBackup.decodeSettings(json);

        assertEquals(source, decoded);
        assertFalse(decoded.containsKey(Tweaks.KEY_CALL_RECORDING_PROBE));
        assertFalse(decoded.containsKey(Tweaks.KEY_AUTO_RECORD_CALLS));
        assertFalse(decoded.containsKey(Tweaks.KEY_CALL_RECORDING_NOTIFICATIONS));
        assertEquals(true, decoded.get(Tweaks.KEY_BACKUP_FREQUENT_PUSH));
        assertEquals(3, decoded.get(Tweaks.KEY_BACKUP_PUSH_INTERVAL));
    }

    public void testUnsupportedRetentionFallsBackToDefault() throws Exception {
        Map<String, Object> decoded = SettingsBackup.decodeSettings(
                "{\"format_version\":1,\"settings\":{\"notifications.history_retention\":999}}");
        assertEquals(500, decoded.get(Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION));
    }

    public void testUnlimitedRetentionRoundTrips() throws Exception {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put(Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION,
                NotificationHistoryStore.RETENTION_UNLIMITED);

        String json = SettingsBackup.encodeSettings(source, 1234L, "test");

        assertEquals(NotificationHistoryStore.RETENTION_UNLIMITED,
                SettingsBackup.decodeSettings(json)
                        .get(Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION));
    }

    public void testNotificationRulesRoundTrip() throws Exception {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put(Tweaks.KEY_HIDE_PROMO_NOTIFICATIONS, true);
        NotificationRuleStore.RuleSet rules = new NotificationRuleStore.RuleSet(
                Collections.singletonList("promotion"),
                null,
                Collections.singletonList("Blocked account"),
                null);

        String json = SettingsBackup.encodeSettings(source, 1234L, "test", rules);
        NotificationRuleStore.RuleSet decoded = SettingsBackup.decodeRules(json);

        assertEquals(Collections.singletonList("promotion"),
                decoded.list(NotificationRuleStore.Type.KEYWORD_BLOCKLIST));
        assertEquals(Collections.singletonList("Blocked account"),
                decoded.list(NotificationRuleStore.Type.ACCOUNT_BLOCKLIST));
    }

    public void testRejectsUnknownSetting() throws Exception {
        assertRejected("{\"format_version\":1,\"settings\":{\"unknown.setting\":true}}");
    }

    public void testRejectsCallProbe() throws Exception {
        assertRejected("{\"format_version\":1,\"settings\":{\"calls.recording_probe\":true}}");
        assertRejected("{\"format_version\":1,\"settings\":{\"calls.auto_record\":true}}");
        assertRejected("{\"format_version\":1,\"settings\":{\"calls.recording_notifications\":true}}");
    }

    public void testRejectsWrongValueType() throws Exception {
        assertRejected("{\"format_version\":1,\"settings\":{\"ui.hide_discovery_tab\":1}}");
    }

    public void testRejectsFractionalInteger() throws Exception {
        assertRejected("{\"format_version\":1,\"settings\":{\"notifications.history_retention\":250.5}}");
    }

    private static void assertRejected(String json) throws Exception {
        try {
            SettingsBackup.decodeSettings(json);
            fail("Expected invalid backup rejection");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
