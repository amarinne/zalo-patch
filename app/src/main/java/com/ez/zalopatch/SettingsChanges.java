package com.ez.zalopatch;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Persistent UI apply state. Kept outside portable settings backups. */
final class SettingsChanges {
    /**
     * Module-process only: which settings changed since the last apply, for the pending-changes bar.
     * Deliberately not the hook-readable Tweaks file and not the UiSettings file; see
     * {@link Tweaks#PREFS_NAME}.
     */
    private static final String PREFS = "settings_ui";
    private static final String KEY_PENDING_COUNT = "pending_change_count";
    private static final String KEY_PENDING_KEYS = "pending_change_keys";
    private static final String KEY_GENERATION = "change_generation";

    private SettingsChanges() {
    }

    private static final String ACTIVE = "active_config_snapshot";

    static ConfigSnapshot active(Context context) {
        String encoded = preferences(context).getString(ACTIVE, null);
        ConfigSnapshot snapshot = ConfigSnapshot.decode(encoded, BuildConfig.VERSION_CODE);
        return snapshot != null ? snapshot : ConfigSnapshot.previous(encoded, BuildConfig.VERSION_CODE);
    }

    static boolean needsRestart(Context context) {
        ConfigSnapshot active = active(context);
        return active == null || active.moduleVersion != BuildConfig.VERSION_CODE || pendingCount(context) > 0;
    }

    /** Compare saved intent with the snapshot acknowledged by Zalo, never a restart command. */
    static int pendingCount(Context context) {
        synchronized (SettingsStore.LOCK) {
            ConfigSnapshot active = active(context);
            if (active == null) {
                Set<String> keys = new HashSet<>(preferences(context).getStringSet(
                        KEY_PENDING_KEYS, java.util.Collections.emptySet()));
                keys.addAll(TweakStore.preferences(context).getStringSet(
                        SettingsStore.PENDING, java.util.Collections.emptySet()));
                keys.remove(Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION);
                return keys.size();
            }
            int count = 0;
            for (Settings.Setting<?> setting : Settings.all()) {
                if (!setting.requiresRestart()) continue;
                String saved = setting.type == Settings.Type.BOOLEAN
                        ? String.valueOf(SettingsStore.getBoolean(context, setting.key))
                        : String.valueOf(SettingsStore.getInt(context, setting.key));
                String loaded = active.values.getOrDefault(setting.key, String.valueOf(setting.defaultValue));
                if (!saved.equals(loaded)) count++;
            }
            try {
                String saved = NotificationRuleStore.encode(NotificationRuleStore.load(context));
                String loaded = NotificationRuleStore.encode(NotificationRuleStore.decode(
                        active.values.get(NotificationRuleStore.MIRROR_KEY)));
                if (!saved.equals(loaded)) count++;
            } catch (Exception invalid) { count++; }
            return count;
        }
    }

    static boolean runtimeBoolean(Context context, String key) {
        ConfigSnapshot active = active(context);
        return active == null ? Settings.defaultBoolean(key)
                : Boolean.parseBoolean(active.values.getOrDefault(key, String.valueOf(Settings.defaultBoolean(key))));
    }

    static boolean acknowledge(Context context, ConfigSnapshot snapshot) {
        synchronized (SettingsStore.LOCK) {
            try {
                SharedPreferences preferences = preferences(context);
                String before = preferences.getString(ACTIVE, null);
                if (preferences.edit().putString(ACTIVE, snapshot.settingsOnly().encode()).commit()) return true;
                SharedPreferences.Editor rollback = preferences.edit();
                if (before == null) rollback.remove(ACTIVE);
                else rollback.putString(ACTIVE, before);
                rollback.commit();
                return false;
            } catch (Exception invalid) { return false; }
        }
    }

    static synchronized void markChanged(Context context, String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        SharedPreferences preferences = preferences(context);
        Set<String> keys = new HashSet<>(preferences.getStringSet(
                KEY_PENDING_KEYS, java.util.Collections.emptySet()));
        keys.add(key);
        preferences.edit()
                .putStringSet(KEY_PENDING_KEYS, keys)
                .putLong(KEY_GENERATION, generation(context) + 1L)
                .commit();
    }

    static long generation(Context context) {
        return Math.max(preferences(context).getLong(KEY_GENERATION, 0L),
                TweakStore.preferences(context).getLong(SettingsStore.GENERATION, 0L));
    }

    static synchronized void clearIfGeneration(Context context, long generation) {
        SharedPreferences preferences = preferences(context);
        if (preferences.getLong(KEY_GENERATION, 0L) != generation) {
            return;
        }
        preferences(context).edit()
                .remove(KEY_PENDING_COUNT)
                .remove(KEY_PENDING_KEYS)
                .commit();
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
