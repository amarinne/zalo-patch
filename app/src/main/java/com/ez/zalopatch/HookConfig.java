package com.ez.zalopatch;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

public final class HookConfig {
    private static final Uri PROVIDER_URI = Uri.parse("content://com.ez.zalopatch.config/prefs");
    private static final String MODULE_PACKAGE = "com.ez.zalopatch";
    private static final Map<String, String> cache = new ConcurrentHashMap<>();
    private static volatile Context appContext;
    private static volatile Context moduleContext;
    private static volatile boolean moduleContextUnavailable;
    private static volatile XSharedPreferences xPrefs;
    private static volatile NotificationRuleStore.RuleSet notificationRules;
    private static volatile Boolean hookDebugEnabled;

    private HookConfig() {
    }

    public static void init() {
        if (hookDebugEnabled == null) {
            synchronized (HookConfig.class) {
                if (hookDebugEnabled == null) {
                    hookDebugEnabled = readDebugEnabled();
                }
            }
        }
    }

    public static void setAppContext(Context context) {
        if (context != null) {
            Context applicationContext = context.getApplicationContext();
            appContext = applicationContext != null ? applicationContext : context;
            moduleContext = null;
            moduleContextUnavailable = false;
            reload();
        }
    }

    public static void reload() {
        cache.clear();
        xPrefs = null;
        notificationRules = null;
    }

    public static NotificationRuleStore.RuleSet notificationRules() {
        NotificationRuleStore.RuleSet cached = notificationRules;
        if (cached != null) {
            return cached;
        }
        synchronized (HookConfig.class) {
            cached = notificationRules;
            if (cached == null) {
                String json = SettingsPropertyMirror.readBlob(NotificationRuleStore.MIRROR_KEY);
                try {
                    cached = NotificationRuleStore.decode(json);
                } catch (Exception ignored) {
                    cached = NotificationRuleStore.RuleSet.empty();
                }
                notificationRules = cached;
            }
        }
        return cached;
    }

    private static XSharedPreferences getXPrefs() {
        if (xPrefs == null) {
            synchronized (HookConfig.class) {
                if (xPrefs == null) {
                    xPrefs = new XSharedPreferences(MODULE_PACKAGE, Tweaks.PREFS_NAME);
                    xPrefs.makeWorldReadable();
                }
            }
        }
        xPrefs.reload();
        return xPrefs;
    }

    public static boolean isEnabled(String key) {
        String value = readValue(appContext, key);
        if (value == null) {
            return Settings.defaultBoolean(key);
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        return Settings.defaultBoolean(key);
    }

    public static int getLevel(String key) {
        String value = readValue(appContext, key);
        if (value == null) {
            return Settings.defaultInt(key);
        }
        try {
            return Settings.coerceInt(key, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return Settings.defaultInt(key);
        }
    }

    public static int getLevel(Context context, String key) {
        String value = readValue(context, key);
        if (value == null) {
            return Settings.defaultInt(key);
        }
        try {
            return Settings.coerceInt(key, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return Settings.defaultInt(key);
        }
    }

    public static boolean getRawBoolean(String key, boolean fallback) {
        String value = readValue(appContext, key);
        if (value == null) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        return fallback;
    }

    public static long getRawLong(String key, long fallback) {
        String value = readValue(appContext, key);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public static String getRawString(String key, String fallback) {
        String value = readValue(appContext, key);
        return value == null ? fallback : value;
    }

    private static String readValue(Context context, String key) {
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        String propertyValue = SettingsPropertyMirror.read(key);
        if (propertyValue != null) {
            cache.put(key, propertyValue);
            return propertyValue;
        }
        if (context == null) {
            context = resolveFallbackContext();
        }
        if (context == null) {
            return readFromXPrefs(key);
        }
        String value = readFromProvider(resolveModuleContext(context), key, "module");
        if (value != null) {
            return value;
        }
        value = readFromProvider(context, key, "target");
        if (value != null) {
            return value;
        }
        return readFromXPrefs(key);
    }

    private static String readFromProvider(Context context, String key, String source) {
        if (context == null) {
            return null;
        }
        try {
            Cursor cursor = context.getContentResolver().query(Uri.withAppendedPath(PROVIDER_URI, key), null, null, null, null);
            if (cursor == null) {
                return null;
            }
            try {
                int valueIndex = cursor.getColumnIndex("value");
                if (valueIndex >= 0 && cursor.moveToFirst()) {
                    String value = cursor.getString(valueIndex);
                    if (value != null) {
                        cache.put(key, value);
                    }
                    return value;
                }
            } finally {
                cursor.close();
            }
        } catch (Throwable throwable) {
            if (isDebugEnabled()) {
                android.util.Log.i("ZaloPatch", "Provider read failed source=" + source
                        + " key=" + key + " " + throwable.getClass().getSimpleName());
            }
        }
        return null;
    }

    private static Context resolveModuleContext(Context baseContext) {
        Context cached = moduleContext;
        if (cached != null) {
            return cached;
        }
        if (moduleContextUnavailable) {
            return null;
        }
        if (baseContext == null) {
            return null;
        }
        try {
            cached = baseContext.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
            moduleContext = cached;
            return cached;
        } catch (Throwable throwable) {
            moduleContextUnavailable = true;
            android.util.Log.i("ZaloPatch", "Module context unavailable: "
                    + throwable.getClass().getSimpleName());
            return null;
        }
    }

    private static String readFromXPrefs(String key) {
        try {
            XSharedPreferences prefs = getXPrefs();
            prefs.reload();
            Object value = prefs.getAll().get(key);
            String str = value != null ? String.valueOf(value) : null;
            if (isDebugEnabled()) {
                android.util.Log.i("ZaloPatch", "XPrefs read key=" + key + " value=" + str);
            }
            if (str != null) {
                cache.put(key, str);
            }
            return str;
        } catch (Throwable t) {
            if (isDebugEnabled()) {
                android.util.Log.i("ZaloPatch", "XPrefs read failed: "
                        + t.getClass().getSimpleName());
            }
            return null;
        }
    }

    /**
     * Dev debug toggle, backed by an Android system property (readable by any app at any time,
     * unlike the prefs/ContentProvider path which is SELinux-blocked / unreliable cross-process).
     * Toggle from the host with no rebuild:
     *     adb shell setprop debug.zalopatch 1   # enable lightweight debug diagnostics
     *     adb shell setprop debug.zalopatch 0   # disable
     * Then restart Zalo. The "debug." prefix is shell-settable without root and survives until
     * reboot. Works at any call site, including process-start.
     */
    public static boolean isDebugEnabled() {
        Boolean cached = hookDebugEnabled;
        return cached != null ? cached : readDebugEnabled();
    }

    private static boolean readDebugEnabled() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String value = (String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, "debug.zalopatch", "0");
            return "1".equals(value) || "true".equalsIgnoreCase(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void logStartupSnapshot() {
        Context context = appContext;
        if (context == null) {
            XposedBridge.log("ZaloPatch: HookConfig snapshot source=none appContext=false");
            return;
        }
        int count = 0;
        try {
            Context providerContext = resolveModuleContext(context);
            String source = providerContext != null ? "module-provider" : "target-provider";
            if (providerContext == null) {
                providerContext = context;
            }
            Cursor cursor = providerContext.getContentResolver().query(PROVIDER_URI, null, null, null, null);
            if (cursor != null) {
                try {
                    while (cursor.moveToNext()) {
                        count++;
                    }
                } finally {
                    cursor.close();
                }
            }
            XposedBridge.log("ZaloPatch: HookConfig snapshot source=" + source + " keys=" + count);
        } catch (Throwable throwable) {
            XposedBridge.log("ZaloPatch: HookConfig snapshot provider failed: "
                    + throwable.getClass().getSimpleName() + " " + throwable.getMessage());
        }
    }

    public static Context resolveFallbackContextForHooks() {
        return appContext != null ? appContext : resolveFallbackContext();
    }

    public static Context resolveModuleContextForHooks() {
        Context context = resolveFallbackContextForHooks();
        Context module = resolveModuleContext(context);
        return module != null ? module : context;
    }

    private static Context resolveFallbackContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Context context = (Context) activityThreadClass.getMethod("currentApplication").invoke(null);
            if (context != null) {
                return context;
            }
            Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
            if (activityThread != null) {
                return (Context) activityThreadClass.getMethod("getSystemContext").invoke(activityThread);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
