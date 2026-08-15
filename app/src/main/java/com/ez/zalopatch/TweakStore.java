package com.ez.zalopatch;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;

public final class TweakStore {
    private static final String RETIRED_ZINSTANT_LEVEL = "zinstant.suppression_level";
    private static final int RETIRED_ZINSTANT_AD_ONLY = 1;
    private static final int RETIRED_ZINSTANT_ALL_RELATED = 2;
    private static volatile boolean initialized;

    private TweakStore() {
    }

    public static synchronized void initialize(Context context) {
        if (initialized) {
            makeReadable(context);
            return;
        }
        deleteRetiredSchemaOverride(context);
        SharedPreferences preferences = preferences(context);
        removeRetiredDiagnostics(preferences);
        SettingsStore.initialize(context);
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        int schemaVersion = preferences.getInt(Tweaks.KEY_PREFS_SCHEMA_VERSION, 0);
        if (schemaVersion < 2) {
            editor.putBoolean(Tweaks.KEY_HIDE_MEDIA_BOX, true);
            changed = true;
        }
        if (schemaVersion < 3) {
            editor.putBoolean(Tweaks.KEY_HIDE_ZCLOUD_BANNER, true);
            changed = true;
        }
        if (schemaVersion < 4) {
            removeRetiredKeys(editor);
            changed = true;
        }
        if (schemaVersion < 5) {
            migrateRetiredZinstantLevel(preferences, editor);
            changed = true;
        }
        if (schemaVersion < 6) {
            editor.putBoolean(Tweaks.KEY_HIDE_PROMO_SERVICES, false);
            changed = true;
        }
        if (schemaVersion < Tweaks.PREFS_SCHEMA_VERSION) {
            editor.putInt(Tweaks.KEY_PREFS_SCHEMA_VERSION, Tweaks.PREFS_SCHEMA_VERSION);
            changed = true;
        }
        if (changed) {
            editor.commit();
        }
        makeReadable(context);
        initialized = true;
    }

    public static SharedPreferences preferences(Context context) {
        try {
            return context.getSharedPreferences(Tweaks.PREFS_NAME, Context.MODE_WORLD_READABLE);
        } catch (SecurityException ignored) {
            return context.getSharedPreferences(Tweaks.PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    public static boolean isEnabled(Context context, String key) {
        return SettingsStore.getBoolean(context, key);
    }

    public static void setEnabled(Context context, String key, boolean enabled) {
        SettingsStore.putBoolean(context, key, enabled);
        makeReadable(context);
    }

    public static boolean syncProperties(Context context) {
        SettingsStore.initialize(context);
        boolean success = true;
        for (Settings.Setting<?> setting : Settings.all()) {
            if (setting.type == Settings.Type.BOOLEAN) {
                success &= SettingsPropertyMirror.writeBoolean(
                        setting.key, SettingsStore.getBoolean(context, setting.key));
            } else if (setting.type == Settings.Type.INT) {
                success &= SettingsPropertyMirror.writeInt(
                        setting.key, SettingsStore.getInt(context, setting.key));
            }
        }
        try {
            success &= SettingsPropertyMirror.writeBlob(NotificationRuleStore.MIRROR_KEY,
                    NotificationRuleStore.encode(NotificationRuleStore.load(context)));
        } catch (Exception ignored) {
            success = false;
        }
        return success;
    }

    private static void removeRetiredKeys(SharedPreferences.Editor editor) {
        editor.remove("inbox.enable_top_dock_categories");
        editor.remove("inbox.show_category_badges");
        editor.remove("inbox.keep_oa_legacy_access");
        editor.remove("inbox.category.focused");
        editor.remove("inbox.category.other");
        editor.remove("inbox.category.business_box");
        editor.remove("inbox.category.tags");
        editor.remove("settings.hide_qr_wallet");
        editor.remove("settings.hide_zcloud");
        editor.remove("settings.hide_zstyle");
        editor.remove("inbox.debug_log");
        editor.remove("telemetry.disable_legacy_action_log");
    }

    private static void migrateRetiredZinstantLevel(SharedPreferences preferences, SharedPreferences.Editor editor) {
        int level = preferences.getInt(RETIRED_ZINSTANT_LEVEL, 0);
        if (level >= RETIRED_ZINSTANT_AD_ONLY) {
            editor.putBoolean(Tweaks.KEY_HIDE_MESSAGE_ADS, true);
            editor.putBoolean(Tweaks.KEY_HIDE_FEED_ADS, true);
        }
        if (level >= RETIRED_ZINSTANT_ALL_RELATED) {
            editor.putBoolean(Tweaks.KEY_HIDE_PROMO_SERVICES, true);
        }
        editor.remove(RETIRED_ZINSTANT_LEVEL);
    }

    private static void makeReadable(Context context) {
        File dataDir = new File(context.getApplicationInfo().dataDir);
        File prefsDir = new File(dataDir, "shared_prefs");
        File prefsFile = new File(prefsDir, Tweaks.PREFS_NAME + ".xml");

        dataDir.setReadable(true, false);
        dataDir.setExecutable(true, false);
        prefsDir.setReadable(true, false);
        prefsDir.setExecutable(true, false);
        if (prefsFile.exists()) {
            prefsFile.setReadable(true, false);
        }
    }

    private static void deleteRetiredSchemaOverride(Context context) {
        File retired = new File(context.getFilesDir(), "symbol-schema-imported.json");
        if (retired.exists()) {
            retired.delete();
        }
    }

    private static void removeRetiredDiagnostics(SharedPreferences preferences) {
        String[] features = new String[]{
                "telemetry.legacy_action_log",
                "telemetry.event_analytics",
                "telemetry.screen_analytics",
                "telemetry.session_analytics",
                "telemetry.view_analytics",
                "telemetry.advertisingid_cache",
                "zinstant.router",
                "me_cleanup.legacy",
                "messages.recall_capture",
                "messages.recall_capture.database",
                "messages.recall_restore",
                "messages.discovery",
                "messages.discovery.recall_render"
        };
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        String[] retiredSettings = new String[]{
                "inbox.debug_log",
                "telemetry.disable_legacy_action_log",
                "messages.restore_recalled"
        };
        for (String key : retiredSettings) {
            if (preferences.contains(key)) {
                editor.remove(key);
                changed = true;
            }
        }
        for (String feature : features) {
            String prefix = "selfcheck." + feature + ".";
            for (String key : preferences.getAll().keySet()) {
                if (key.startsWith(prefix)) {
                    editor.remove(key);
                    changed = true;
                }
            }
        }
        if (changed) {
            editor.apply();
        }
    }
}
