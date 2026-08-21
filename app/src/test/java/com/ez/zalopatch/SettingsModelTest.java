package com.ez.zalopatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public final class SettingsModelTest {
    @Test
    public void settingKeysAreUniqueAndResolvable() {
        Set<String> keys = new HashSet<>();
        for (Settings.Setting<?> setting : Settings.all()) {
            assertTrue("Duplicate setting: " + setting.key, keys.add(setting.key));
            assertNotNull(Settings.find(setting.key));
        }
    }

    @Test
    public void telemetryMasterCoversEveryTelemetrySettingExactlyOnce() {
        Set<String> masterKeys = new HashSet<>(Tweaks.TELEMETRY_KEYS);
        assertEquals(Tweaks.TELEMETRY_KEYS.size(), masterKeys.size());

        Set<String> sectionKeys = new HashSet<>();
        for (Tweaks.Item item : Tweaks.ITEMS) {
            if (Tweaks.SECTION_TELEMETRY.equals(item.section)) {
                sectionKeys.add(item.key);
            }
        }
        assertEquals(sectionKeys, masterKeys);
    }

    @Test
    public void notificationRetentionRejectsUnsupportedValues() {
        assertEquals(100, Settings.coerceInt(Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION, 100));
        assertEquals(250, Settings.coerceInt(Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION, 250));
        assertEquals(500, Settings.coerceInt(Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION, 500));
        assertEquals(1000, Settings.coerceInt(Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION, 1000));
        assertEquals(5000, Settings.coerceInt(Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION, 5000));
        assertEquals(NotificationHistoryStore.RETENTION_UNLIMITED,
                Settings.coerceInt(Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION,
                        NotificationHistoryStore.RETENTION_UNLIMITED));
        assertEquals(500, Settings.coerceInt(Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION, 999));
    }

    @Test
    public void defaultInboxFilterRejectsUnsupportedValues() {
        for (int value = 0; value <= 4; value++) {
            assertEquals(value, Settings.coerceInt(Tweaks.KEY_DEFAULT_INBOX_FILTER, value));
        }
        assertEquals(0, Settings.coerceInt(Tweaks.KEY_DEFAULT_INBOX_FILTER, 99));
    }

    @Test
    public void callProbeRemainsDefaultOff() {
        assertFalse(Settings.defaultBoolean(Tweaks.KEY_CALL_RECORDING_PROBE));
    }

    @Test
    public void callRecordingNotificationsRemainDefaultOff() {
        assertFalse(Settings.defaultBoolean(Tweaks.KEY_CALL_RECORDING_NOTIFICATIONS));
    }

    @Test
    public void experimentalBroadZinstantSuppressionRemainsDefaultOff() {
        Settings.Setting<?> setting = Settings.find(Tweaks.KEY_HIDE_PROMO_SERVICES);
        assertNotNull(setting);
        assertTrue(setting.visible);
        assertTrue(setting.implemented);
        assertFalse(Settings.defaultBoolean(Tweaks.KEY_HIDE_PROMO_SERVICES));
    }

    @Test
    public void everyUserFacingToggleDefaultsOff() {
        for (Tweaks.Item item : Tweaks.ITEMS) {
            assertFalse("Default-on toggle: " + item.key, item.defaultEnabled);
            assertFalse("Settings default-on toggle: " + item.key,
                    Settings.defaultBoolean(item.key));
        }
    }

    @Test
    public void everyVisibleToggleHasExactlyOneOwner() {
        Set<String> rendered = new HashSet<>();
        List<String> sections = Arrays.asList(
                Tweaks.SECTION_NAVIGATION,
                Tweaks.SECTION_INBOX,
                Tweaks.SECTION_CHAT,
                Tweaks.SECTION_ME,
                Tweaks.SECTION_ADS,
                Tweaks.SECTION_NOTIFICATIONS,
                Tweaks.SECTION_TELEMETRY,
                Tweaks.SECTION_CALLS,
                Tweaks.SECTION_BACKUP,
                Tweaks.SECTION_DEVELOPER);
        for (String section : sections) {
            for (Tweaks.Group group : Tweaks.groups(section)) {
                for (String key : group.keys) {
                    assertTrue("Duplicate settings row: " + key, rendered.add(key));
                }
            }
        }
        Set<String> expected = new HashSet<>();
        for (Tweaks.Item item : Tweaks.ITEMS) {
            expected.add(item.key);
        }
        assertTrue(rendered.remove(Tweaks.KEY_DEFAULT_INBOX_FILTER));
        assertTrue(rendered.remove(Tweaks.KEY_BACKUP_PUSH_INTERVAL));
        assertEquals(expected, rendered);
    }

    @Test
    public void sectionIdentityIsNotDisplayCopy() {
        for (String section : Arrays.asList(
                Tweaks.SECTION_NAVIGATION, Tweaks.SECTION_INBOX, Tweaks.SECTION_CHAT,
                Tweaks.SECTION_ME, Tweaks.SECTION_ADS, Tweaks.SECTION_NOTIFICATIONS,
                Tweaks.SECTION_TELEMETRY, Tweaks.SECTION_CALLS,
                Tweaks.SECTION_BACKUP,
                Tweaks.SECTION_DEVELOPER)) {
            assertEquals(section, section.toLowerCase(java.util.Locale.US));
            assertFalse(section.contains(" "));
            assertFalse(section.contains("/"));
        }
    }
}
