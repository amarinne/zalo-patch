package com.ez.zalopatch;

import android.content.Context;
import android.content.SharedPreferences;

public final class SettingsStore {
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

    public static void putBoolean(Context context, String key, boolean value) {
        TweakStore.preferences(context).edit().putBoolean(key, value).apply();
    }

    public static int getInt(Context context, String key) {
        int value = TweakStore.preferences(context).getInt(key, Settings.defaultInt(key));
        return Settings.coerceInt(key, value);
    }

    public static void putInt(Context context, String key, int value) {
        TweakStore.preferences(context).edit()
                .putInt(key, Settings.coerceInt(key, value))
                .apply();
    }
}
