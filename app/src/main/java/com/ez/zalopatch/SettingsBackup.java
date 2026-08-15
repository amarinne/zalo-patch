package com.ez.zalopatch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SettingsBackup {
    private static final int FORMAT_VERSION = 2;
    private static final int LEGACY_FORMAT_VERSION = 1;

    static final class BackupData {
        final Map<String, Object> settings;
        final NotificationRuleStore.RuleSet rules;
        final boolean hasRules;

        BackupData(
                Map<String, Object> settings,
                NotificationRuleStore.RuleSet rules,
                boolean hasRules) {
            this.settings = settings;
            this.rules = rules;
            this.hasRules = hasRules;
        }
    }

    private SettingsBackup() {
    }

    public static String exportJson(Context context) throws Exception {
        TweakStore.initialize(context);
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (Settings.Setting<?> setting : Settings.all()) {
            if (!portable(setting)) {
                continue;
            }
            if (setting.type == Settings.Type.BOOLEAN) {
                values.put(setting.key, SettingsStore.getBoolean(context, setting.key));
            } else if (setting.type == Settings.Type.INT) {
                values.put(setting.key, SettingsStore.getInt(context, setting.key));
            }
        }
        return encodeSettings(values, System.currentTimeMillis(), BuildConfig.VERSION_NAME,
                NotificationRuleStore.load(context));
    }

    static String encodeSettings(Map<String, Object> values, long exportedAt, String moduleVersion)
            throws Exception {
        return encodeSettings(values, exportedAt, moduleVersion, NotificationRuleStore.RuleSet.empty());
    }

    static String encodeSettings(
            Map<String, Object> values,
            long exportedAt,
            String moduleVersion,
            NotificationRuleStore.RuleSet rules) throws Exception {
        JSONObject jsonValues = new JSONObject();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Settings.Setting<?> setting = Settings.find(entry.getKey());
            if (setting == null || !portable(setting)) {
                throw new IllegalArgumentException("Unknown setting: " + entry.getKey());
            }
            validateValue(setting, entry.getValue());
            jsonValues.put(entry.getKey(), entry.getValue());
        }
        JSONObject root = new JSONObject();
        root.put("format_version", FORMAT_VERSION);
        root.put("exported_at", exportedAt);
        root.put("module_version", moduleVersion == null ? "" : moduleVersion);
        root.put("settings", jsonValues);
        root.put("notification_rules", new JSONObject(NotificationRuleStore.encode(rules)));
        return root.toString(2);
    }

    public static int importJson(Context context, InputStream input) throws Exception {
        BackupData backup = decodeBackup(read(input));
        Map<String, Object> values = backup.settings;
        SharedPreferences.Editor editor = TweakStore.preferences(context).edit();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Settings.Setting<?> setting = Settings.find(entry.getKey());
            if (setting == null || !portable(setting)) {
                throw new IllegalArgumentException("Unknown setting: " + entry.getKey());
            }
            Object value = entry.getValue();
            if (setting.type == Settings.Type.BOOLEAN) {
                editor.putBoolean(entry.getKey(), (Boolean) value);
            } else if (setting.type == Settings.Type.INT) {
                editor.putInt(entry.getKey(), Settings.coerceInt(entry.getKey(), ((Number) value).intValue()));
            }
            // Record the actual key so pending-change counting stays a set union and an
            // imported key that is later toggled by hand is not counted twice.
            SettingsChanges.markChanged(context, entry.getKey());
        }
        editor.putInt(Tweaks.KEY_PREFS_SCHEMA_VERSION, Tweaks.PREFS_SCHEMA_VERSION);
        if (backup.hasRules) {
            NotificationRuleStore.put(editor, backup.rules);
        }
        if (!editor.commit()) {
            throw new IOException("Preference write failed");
        }
        TweakStore.initialize(context);
        return values.size() + (backup.hasRules ? backup.rules.total() : 0);
    }

    static Map<String, Object> decodeSettings(String json) throws Exception {
        return decodeBackup(json).settings;
    }

    static NotificationRuleStore.RuleSet decodeRules(String json) throws Exception {
        return decodeBackup(json).rules;
    }

    static BackupData decodeBackup(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        int formatVersion = root.optInt("format_version", -1);
        if (formatVersion != FORMAT_VERSION && formatVersion != LEGACY_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported backup format");
        }
        JSONObject jsonValues = root.optJSONObject("settings");
        if (jsonValues == null) {
            throw new IllegalArgumentException("Missing settings object");
        }
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        Iterator<String> keys = jsonValues.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Settings.Setting<?> setting = Settings.find(key);
            if (setting == null || !portable(setting)) {
                throw new IllegalArgumentException("Unknown setting: " + key);
            }
            Object value = jsonValues.get(key);
            validateValue(setting, value);
            values.put(key, setting.type == Settings.Type.INT
                    ? Settings.coerceInt(key, ((Number) value).intValue()) : value);
        }
        JSONObject jsonRules = root.optJSONObject("notification_rules");
        boolean hasRules = formatVersion >= FORMAT_VERSION;
        if (hasRules && jsonRules == null) {
            throw new IllegalArgumentException("Missing notification rules object");
        }
        NotificationRuleStore.RuleSet rules = hasRules
                ? NotificationRuleStore.decode(jsonRules.toString())
                : NotificationRuleStore.RuleSet.empty();
        return new BackupData(values, rules, hasRules);
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    static boolean portable(Settings.Setting<?> setting) {
        return setting.visible
                && !setting.key.startsWith("calls.");
    }

    private static void validateValue(Settings.Setting<?> setting, Object value) {
        if (setting.type == Settings.Type.BOOLEAN && !(value instanceof Boolean)) {
            throw new IllegalArgumentException("Expected boolean: " + setting.key);
        }
        if (setting.type == Settings.Type.INT) {
            if (!(value instanceof Number)) {
                throw new IllegalArgumentException("Expected integer: " + setting.key);
            }
            double numeric = ((Number) value).doubleValue();
            int integer = ((Number) value).intValue();
            if (!Double.isFinite(numeric) || numeric != integer) {
                throw new IllegalArgumentException("Expected integer: " + setting.key);
            }
        }
    }
}
