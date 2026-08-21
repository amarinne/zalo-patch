package com.ez.zalopatch;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Module-private record of the runtime that actually loaded the hook. */
public final class RuntimeEnvironment {
    private static final String PREFS = "runtime_environment_v1";
    private static final String KEY_REPORTED = "reported";
    private static final String KEY_FRAMEWORK = "framework";
    private static final String KEY_RESOURCE_HOOKS = "resource_hooks_observed";
    private static final String KEY_RESOURCE_HOOKS_STATUS = "resource_hooks_status";
    private static final String KEY_MODULE_VERSION = "module_version";
    private static final String KEY_ZALO_VERSION = "zalo_version";
    private static final String KEY_UPDATED_AT = "updated_at";

    public enum Framework {
        LSPOSED,
        LSPATCH,
        UNKNOWN;

        public String value() {
            return name().toLowerCase(Locale.US);
        }
    }

    public enum ResourceHooks {
        PENDING,
        OBSERVED,
        UNAVAILABLE;

        public String value() {
            return name().toLowerCase(Locale.US);
        }
    }

    public static final class Snapshot {
        public final boolean reported;
        public final Framework framework;
        public final ResourceHooks resourceHooks;
        public final boolean resourceHooksObserved;
        public final int moduleVersionCode;
        public final long zaloVersionCode;
        public final long updatedAtMs;

        Snapshot(boolean reported, Framework framework, ResourceHooks resourceHooks,
                 int moduleVersionCode, long zaloVersionCode, long updatedAtMs) {
            this.reported = reported;
            this.framework = framework == null ? Framework.UNKNOWN : framework;
            this.resourceHooks = resourceHooks == null ? ResourceHooks.PENDING : resourceHooks;
            this.resourceHooksObserved = this.resourceHooks == ResourceHooks.OBSERVED;
            this.moduleVersionCode = moduleVersionCode;
            this.zaloVersionCode = zaloVersionCode;
            this.updatedAtMs = updatedAtMs;
        }

        static Snapshot pending(long zaloVersionCode) {
            return new Snapshot(false, Framework.UNKNOWN, ResourceHooks.PENDING,
                    BuildConfig.VERSION_CODE, zaloVersionCode, 0L);
        }
    }

    private RuntimeEnvironment() {
    }

    public static Snapshot current(Context context) {
        long installedVersion = SymbolSchema.installedZaloVersionCode(context);
        SharedPreferences prefs = preferences(context);
        if (!prefs.getBoolean(KEY_REPORTED, false)
                || prefs.getInt(KEY_MODULE_VERSION, -1) != BuildConfig.VERSION_CODE
                || prefs.getLong(KEY_ZALO_VERSION, -1L) != installedVersion) {
            return Snapshot.pending(installedVersion);
        }
        return new Snapshot(true, parseFramework(prefs.getString(KEY_FRAMEWORK, "")),
                readResourceHooks(prefs),
                prefs.getInt(KEY_MODULE_VERSION, -1),
                prefs.getLong(KEY_ZALO_VERSION, -1L),
                prefs.getLong(KEY_UPDATED_AT, 0L));
    }

    static boolean record(Context context, String frameworkValue, String resourceHooksValue,
                          int moduleVersionCode, long zaloVersionCode) {
        Framework framework = parseFramework(frameworkValue);
        if (moduleVersionCode != BuildConfig.VERSION_CODE || zaloVersionCode <= 0L) return false;
        SharedPreferences prefs = preferences(context);
        Snapshot incoming = new Snapshot(true, framework, parseResourceHooks(resourceHooksValue),
                moduleVersionCode, zaloVersionCode, System.currentTimeMillis());
        Snapshot merged = merge(readUnchecked(prefs), incoming);
        return prefs.edit()
                .putBoolean(KEY_REPORTED, true)
                .putString(KEY_FRAMEWORK, merged.framework.value())
                .putString(KEY_RESOURCE_HOOKS_STATUS, merged.resourceHooks.value())
                .putBoolean(KEY_RESOURCE_HOOKS, merged.resourceHooksObserved)
                .putInt(KEY_MODULE_VERSION, merged.moduleVersionCode)
                .putLong(KEY_ZALO_VERSION, merged.zaloVersionCode)
                .putLong(KEY_UPDATED_AT, merged.updatedAtMs)
                .commit();
    }

    static Snapshot merge(Snapshot current, Snapshot incoming) {
        if (current == null || !current.reported
                || current.moduleVersionCode != incoming.moduleVersionCode
                || current.zaloVersionCode != incoming.zaloVersionCode
                || current.framework != incoming.framework) {
            return incoming;
        }
        ResourceHooks mergedResourceHooks = mergeResourceHooks(
                current.resourceHooks, incoming.resourceHooks);
        return new Snapshot(true, incoming.framework, mergedResourceHooks,
                incoming.moduleVersionCode, incoming.zaloVersionCode, incoming.updatedAtMs);
    }

    public static Framework detect(boolean lspatchMarker, boolean lsposedMarker,
                                   int xposedApiVersion, String... runtimeEvidence) {
        if (lspatchMarker) return Framework.LSPATCH;
        if (lsposedMarker || strongLsposedEvidence(runtimeEvidence)
                || xposedApiVersion >= 100) {
            return Framework.LSPOSED;
        }
        return Framework.UNKNOWN;
    }

    static void clearForTest(Context context) {
        preferences(context).edit().clear().commit();
    }

    static java.util.Map<String, ?> snapshotForTest(Context context) {
        return new java.util.HashMap<>(preferences(context).getAll());
    }

    static void restoreForTest(Context context, java.util.Map<String, ?> values) {
        SharedPreferences.Editor editor = preferences(context).edit().clear();
        if (values != null) {
            for (java.util.Map.Entry<String, ?> entry : values.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
                else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
                else if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            }
        }
        editor.commit();
    }

    private static boolean strongLsposedEvidence(String[] evidence) {
        if (evidence == null) return false;
        for (String item : evidence) {
            String value = item == null ? "" : item.toLowerCase(Locale.US);
            if (value.contains("org.lsposed.lspd.")
                    || value.contains("/data/adb/lspd/")
                    || value.contains("/data/adb/modules/zygisk_lsposed/")
                    || value.contains("/data/adb/modules/lsposed/")
                    || value.contains("lspd.dex")) {
                return true;
            }
        }
        return false;
    }

    private static Snapshot readUnchecked(SharedPreferences prefs) {
        if (!prefs.getBoolean(KEY_REPORTED, false)) return null;
        return new Snapshot(true, parseFramework(prefs.getString(KEY_FRAMEWORK, "")),
                readResourceHooks(prefs),
                prefs.getInt(KEY_MODULE_VERSION, -1),
                prefs.getLong(KEY_ZALO_VERSION, -1L),
                prefs.getLong(KEY_UPDATED_AT, 0L));
    }

    private static Framework parseFramework(String value) {
        if ("lsposed".equalsIgnoreCase(value)) return Framework.LSPOSED;
        if ("lspatch".equalsIgnoreCase(value)) return Framework.LSPATCH;
        return Framework.UNKNOWN;
    }

    private static ResourceHooks parseResourceHooks(String value) {
        if ("observed".equalsIgnoreCase(value)) return ResourceHooks.OBSERVED;
        if ("unavailable".equalsIgnoreCase(value)) return ResourceHooks.UNAVAILABLE;
        return ResourceHooks.PENDING;
    }

    private static ResourceHooks readResourceHooks(SharedPreferences prefs) {
        if (prefs.contains(KEY_RESOURCE_HOOKS_STATUS)) {
            return parseResourceHooks(prefs.getString(KEY_RESOURCE_HOOKS_STATUS, ""));
        }
        return prefs.getBoolean(KEY_RESOURCE_HOOKS, false)
                ? ResourceHooks.OBSERVED : ResourceHooks.PENDING;
    }

    private static ResourceHooks mergeResourceHooks(ResourceHooks current, ResourceHooks incoming) {
        if (current == ResourceHooks.OBSERVED || incoming == ResourceHooks.OBSERVED) {
            return ResourceHooks.OBSERVED;
        }
        if (current == ResourceHooks.UNAVAILABLE || incoming == ResourceHooks.UNAVAILABLE) {
            return ResourceHooks.UNAVAILABLE;
        }
        return ResourceHooks.PENDING;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
