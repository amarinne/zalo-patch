package com.ez.zalopatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Settings {
    public enum Type {
        BOOLEAN,
        INT
    }

    public static final class Setting<T> {
        public final String key;
        public final Type type;
        public final T defaultValue;
        public final List<T> allowedValues;
        public final String section;
        public final boolean implemented;
        public final boolean visible;

        private Setting(
                String key,
                Type type,
                T defaultValue,
                List<T> allowedValues,
                String section,
                boolean implemented,
                boolean visible) {
            this.key = key;
            this.type = type;
            this.defaultValue = defaultValue;
            this.allowedValues = allowedValues;
            this.section = section;
            this.implemented = implemented;
            this.visible = visible;
        }
    }

    private static final List<Setting<?>> SETTINGS;

    static {
        ArrayList<Setting<?>> settings = new ArrayList<>();
        settings.add(intSetting(Tweaks.KEY_PREFS_SCHEMA_VERSION, "Internal", Tweaks.PREFS_SCHEMA_VERSION, null, true, false));
        settings.add(intSetting(Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION, Tweaks.SECTION_NOTIFICATIONS,
                500, java.util.Arrays.asList(100, 250, 500, 1000, 5000, 0), true, true));
        settings.add(intSetting(Tweaks.KEY_DEFAULT_INBOX_FILTER, Tweaks.SECTION_INBOX,
                0,
                java.util.Arrays.asList(0, 1, 2, 3, 4), true, true));
        for (Tweaks.Item item : Tweaks.ITEMS) {
            settings.add(boolSetting(item.key, item.section, item.defaultEnabled, item.implemented, true));
        }
        SETTINGS = Collections.unmodifiableList(settings);
    }

    private Settings() {
    }

    public static List<Setting<?>> all() {
        return SETTINGS;
    }

    public static Setting<?> find(String key) {
        for (Setting<?> setting : SETTINGS) {
            if (setting.key.equals(key)) {
                return setting;
            }
        }
        return null;
    }

    public static boolean defaultBoolean(String key) {
        Setting<?> setting = find(key);
        if (setting != null && setting.type == Type.BOOLEAN && setting.defaultValue instanceof Boolean) {
            return (Boolean) setting.defaultValue;
        }
        return false;
    }

    public static int defaultInt(String key) {
        Setting<?> setting = find(key);
        if (setting != null && setting.type == Type.INT && setting.defaultValue instanceof Integer) {
            return (Integer) setting.defaultValue;
        }
        return 0;
    }

    public static int coerceInt(String key, int value) {
        Setting<?> setting = find(key);
        if (setting == null || setting.type != Type.INT || setting.allowedValues == null || setting.allowedValues.isEmpty()) {
            return value;
        }
        for (Object allowed : setting.allowedValues) {
            if (allowed instanceof Integer && ((Integer) allowed) == value) {
                return value;
            }
        }
        return defaultInt(key);
    }

    private static Setting<Boolean> boolSetting(
            String key,
            String section,
            boolean defaultValue,
            boolean implemented,
            boolean visible) {
        return new Setting<>(key, Type.BOOLEAN, defaultValue, null, section, implemented, visible);
    }

    private static Setting<Integer> intSetting(
            String key,
            String section,
            int defaultValue,
            List<Integer> allowedValues,
            boolean implemented,
            boolean visible) {
        List<Integer> allowed = allowedValues == null ? null : Collections.unmodifiableList(new ArrayList<>(allowedValues));
        return new Setting<>(key, Type.INT, defaultValue, allowed, section, implemented, visible);
    }
}
