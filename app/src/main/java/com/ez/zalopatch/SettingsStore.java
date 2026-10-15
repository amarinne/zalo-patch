package com.ez.zalopatch;

import android.content.Context;
import android.content.SharedPreferences;

public final class SettingsStore {
    static final Object LOCK = new Object();
    static final String GENERATION = "internal.settings_generation";
    static final String PENDING = "internal.settings_pending_keys";
    private static final java.util.Map<SharedPreferences, java.util.Map<String, Object>> repairs =
            new java.util.WeakHashMap<>();
    private SettingsStore() {
    }

    public static void initialize(Context context) {
        SharedPreferences preferences = TweakStore.preferences(context);
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        for (Settings.Setting<?> setting : Settings.all()) {
            if (setting.type == Settings.Type.BOOLEAN) {
                if (!preferences.contains(setting.key)) {
                    editor.putBoolean(setting.key, (Boolean) setting.defaultValue);
                    changed = true;
                }
            } else if (setting.type == Settings.Type.INT) {
                int value = preferences.getInt(setting.key, (Integer) setting.defaultValue);
                int coerced = Settings.coerceInt(setting.key, value);
                if (!preferences.contains(setting.key) || coerced != value) {
                    editor.putInt(setting.key, coerced);
                    changed = true;
                }
            }
        }
        if (changed) {
            editor.commit();
        }
    }

    public static boolean getBoolean(Context context, String key) {
        return TweakStore.preferences(context).getBoolean(key, Settings.defaultBoolean(key));
    }

    public static boolean putBoolean(Context context, String key, boolean value) {
        return save(context, java.util.Collections.singletonMap(key, value));
    }

    public static int getInt(Context context, String key) {
        int value = TweakStore.preferences(context).getInt(key, Settings.defaultInt(key));
        return Settings.coerceInt(key, value);
    }

    public static boolean putInt(Context context, String key, int value) {
        return save(context, java.util.Collections.singletonMap(key, Settings.coerceInt(key, value)));
    }

    /** One durable transaction for an accepted edit, master switch, or typed import. */
    static boolean save(Context context, java.util.Map<String, ?> changes) {
        synchronized (LOCK) {
            SharedPreferences prefs = TweakStore.preferences(context);
            java.util.Map<String, Object> repair = repairs.get(prefs);
            if (repair != null) {
                if (!restore(prefs, repair)) return false;
                repairs.remove(prefs);
            }
            java.util.Map<String, ?> before = prefs.getAll();
            java.util.Map<String, Object> updates = new java.util.HashMap<>();
            java.util.Set<String> keys = new java.util.HashSet<>(prefs.getStringSet(
                    PENDING, java.util.Collections.emptySet()));
            for (java.util.Map.Entry<String, ?> entry : changes.entrySet()) {
                String key = entry.getKey();
                Settings.Setting<?> setting = Settings.find(key);
                if (setting == null && !NotificationRuleStore.PREF_KEY.equals(key)) return false;
                if (setting != null && (setting.type == Settings.Type.BOOLEAN
                        ? !(entry.getValue() instanceof Boolean) : !(entry.getValue() instanceof Integer))) return false;
                if (setting == null && !(entry.getValue() instanceof String)) return false;
                Object previous = before.get(key);
                if (previous == null && setting != null) previous = setting.defaultValue;
                if (!java.util.Objects.equals(previous, entry.getValue())) {
                    updates.put(key, entry.getValue());
                    if (setting != null && setting.requiresRestart()) keys.add(key);
                    if (NotificationRuleStore.PREF_KEY.equals(key)) keys.add(NotificationRuleStore.MIRROR_KEY);
                }
            }
            if (updates.isEmpty()) return true;
            updates.put(GENERATION, SettingsChanges.generation(context) + 1L);
            updates.put(PENDING, keys);
            SharedPreferences.Editor editor = prefs.edit();
            updates.forEach((key, value) -> put(editor, key, value));
            if (editor.commit()) return true;
            // Android updates its memory cache even when commit fails. Reject the UI edit and
            // restore that cache as well as disk; never publish an unaccepted value.
            java.util.Map<String, Object> rollback = new java.util.HashMap<>();
            for (String key : updates.keySet()) rollback.put(key, before.get(key));
            if (!restore(prefs, rollback)) repairs.put(prefs, rollback);
            return false;
        }
    }

    static boolean confirmSaved(Context context, boolean saved) {
        if (!saved) android.widget.Toast.makeText(context, R.string.zp_settings_save_failed,
                android.widget.Toast.LENGTH_LONG).show();
        return saved;
    }

    static boolean canPublish(Context context) {
        return !repairs.containsKey(TweakStore.preferences(context));
    }

    private static boolean restore(SharedPreferences prefs, java.util.Map<String, Object> values) {
        SharedPreferences.Editor editor = prefs.edit();
        values.forEach((key, value) -> {
            if (value == null) editor.remove(key);
            else put(editor, key, value);
        });
        return editor.commit();
    }

    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof java.util.Set) editor.putStringSet(key, (java.util.Set<String>) value);
        else throw new IllegalArgumentException("Unsupported preference: " + key);
    }
}
