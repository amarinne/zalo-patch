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

    /** Distinct changed settings keys. Every writer records its real key, so this is exact. */
    static int pendingCount(Context context) {
        return preferences(context)
                .getStringSet(KEY_PENDING_KEYS, java.util.Collections.emptySet())
                .size();
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
                .putLong(KEY_GENERATION, preferences.getLong(KEY_GENERATION, 0L) + 1L)
                .apply();
    }

    static long generation(Context context) {
        return preferences(context).getLong(KEY_GENERATION, 0L);
    }

    static synchronized void clearIfGeneration(Context context, long generation) {
        SharedPreferences preferences = preferences(context);
        if (preferences.getLong(KEY_GENERATION, 0L) != generation) {
            return;
        }
        preferences(context).edit()
                .remove(KEY_PENDING_COUNT)
                .remove(KEY_PENDING_KEYS)
                .apply();
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
