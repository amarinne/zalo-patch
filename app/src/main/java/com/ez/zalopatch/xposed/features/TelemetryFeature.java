package com.ez.zalopatch.xposed.features;

import android.content.ComponentName;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class TelemetryFeature extends Feature {
    private static final String FEATURE_AD_ID = "telemetry.ad_id";
    private static final String FEATURE_ANALYTICS_DB = "telemetry.analytics_db";
    private static final String FEATURE_FIREBASE = "telemetry.firebase";
    private static final String FEATURE_MEASUREMENT_BIND = "telemetry.measurement_bind";
    private static final String AD_ID_INFO_CLASS = "com.google.android.gms.ads.identifier.AdvertisingIdClient$Info";
    private static final String FAKE_AD_ID = "00000000-0000-0000-0000-000000000000";
    private final Set<String> hookedDaoMethods = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final boolean allowDynamicHooks;

    public TelemetryFeature(ClassLoader classLoader) {
        this(classLoader, true);
    }

    public TelemetryFeature(ClassLoader classLoader, boolean allowDynamicHooks) {
        super(classLoader);
        this.allowDynamicHooks = allowDynamicHooks;
    }

    @Override
    public String getFeatureName() {
        return "Telemetry";
    }

    @Override
    public void doHook() throws Throwable {
        hookTelemetrySinks();
        hookAdvertisingId();
        hookMeasurementBinding();
    }

    private void hookTelemetrySinks() {
        if (allowDynamicHooks) {
            hookCurrentAnalyticsDatabaseAccessors();
        } else if (isAnyAnalyticsDbSuppressionEnabled()) {
            SelfCheckRegistry.markStale(FEATURE_ANALYTICS_DB, "exact artifact profile",
                    "Artifact verification required for Room hooks");
        } else {
            SelfCheckRegistry.markDisabled(FEATURE_ANALYTICS_DB, "AnalyticsRoomDatabase_Impl");
        }
        if (HookConfig.isEnabled(Tweaks.KEY_DISABLE_CRASHLYTICS)) {
            hookAllNamedMethods("com.google.firebase.crashlytics.FirebaseCrashlytics", new HashSet<>(Arrays.asList("recordException", "log", "setCustomKey")), "Crashlytics");
        } else {
            SelfCheckRegistry.markDisabled("telemetry.crashlytics", "FirebaseCrashlytics");
        }
    }

    private void hookAdvertisingId() {
        if (!HookConfig.isEnabled(Tweaks.KEY_DISABLE_AD_ID)) {
            SelfCheckRegistry.markDisabled(FEATURE_AD_ID, "AdvertisingIdClient");
            return;
        }
        try {
            Class<?> advertisingClientClass = XposedHelpers.findClass("com.google.android.gms.ads.identifier.AdvertisingIdClient", classLoader);
            Class<?> infoClass = XposedHelpers.findClass(AD_ID_INFO_CLASS, classLoader);

            XposedHelpers.findAndHookMethod(advertisingClientClass, "getAdvertisingIdInfo", android.content.Context.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(newAdInfo(infoClass));
                    SelfCheckRegistry.markSuppressed(FEATURE_AD_ID, "getAdvertisingIdInfo", FAKE_AD_ID);
                }
            });

            XposedHelpers.findAndHookMethod(advertisingClientClass, "getInfo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(newAdInfo(infoClass));
                    SelfCheckRegistry.markSuppressed(FEATURE_AD_ID, "getInfo", FAKE_AD_ID);
                }
            });

            XposedHelpers.findAndHookMethod(advertisingClientClass, "getIsAdIdFakeForDebugLogging", android.content.Context.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(Boolean.TRUE);
                }
            });

            SelfCheckRegistry.markInstalled(FEATURE_AD_ID, "AdvertisingIdClient", 3);
            log("Advertising ID suppression installed");
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE_AD_ID, "AdvertisingIdClient", throwable);
            log("Failed to hook Advertising ID: " + throwable);
        }
    }

    private void hookMeasurementBinding() {
        boolean enabled = HookConfig.isEnabled(Tweaks.KEY_DISABLE_MEASUREMENT_BIND);
        if (!enabled) {
            SelfCheckRegistry.markDisabled(FEATURE_MEASUREMENT_BIND, "ContextImpl.bindServiceCommon");
            SelfCheckRegistry.markDisabled(FEATURE_FIREBASE, "FirebaseAnalytics");
        }
        try {
            int hooked = hookMeasurementServiceMethods(XposedHelpers.findClass("android.app.ContextImpl", null), "ContextImpl", enabled);
            hooked += hookMeasurementServiceMethods(ContextWrapper.class, "ContextWrapper", enabled);
            if (hooked == 0 && enabled) {
                SelfCheckRegistry.markStale(FEATURE_MEASUREMENT_BIND, "framework bind service", "no matching methods");
            }

            try {
                Class<?> firebaseAnalyticsClass = XposedHelpers.findClass("com.google.firebase.analytics.FirebaseAnalytics", classLoader);
                int firebaseHooked = 0;
                for (Method method : firebaseAnalyticsClass.getDeclaredMethods()) {
                    if (method.getReturnType() != Void.TYPE) {
                        continue;
                    }
                    Class<?>[] types = method.getParameterTypes();
                    if (types.length == 2 && types[0] == String.class && types[1] == Bundle.class) {
                        method.setAccessible(true);
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (enabled) {
                                    param.setResult(null);
                                    SelfCheckRegistry.markSuppressed(FEATURE_FIREBASE, "FirebaseAnalytics", String.valueOf(param.args[0]));
                                }
                            }
                        });
                        firebaseHooked++;
                        log("Blocked FirebaseAnalytics event method");
                    }
                }
                if (enabled) {
                    if (firebaseHooked > 0) {
                        SelfCheckRegistry.markInstalled(FEATURE_FIREBASE, "FirebaseAnalytics", firebaseHooked);
                    } else {
                        SelfCheckRegistry.markDisabled(FEATURE_FIREBASE, "event API absent in host");
                    }
                }
            } catch (Throwable throwable) {
                if (enabled) {
                    SelfCheckRegistry.markDisabled(FEATURE_FIREBASE, "event API absent in host");
                }
                log("FirebaseAnalytics event hook skipped: " + throwable);
            }

            if (enabled && hooked > 0) {
                SelfCheckRegistry.markInstalled(FEATURE_MEASUREMENT_BIND, "framework bind service", hooked);
            }
            log("Measurement bind suppression installed");
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE_MEASUREMENT_BIND, "ContextImpl.bindServiceCommon", throwable);
            log("Failed to hook measurement bind: " + throwable);
        }
    }

    private void hookCurrentAnalyticsDatabaseAccessors() {
        boolean enabled = isAnyAnalyticsDbSuppressionEnabled();
        if (!enabled) {
            SelfCheckRegistry.markDisabled(FEATURE_ANALYTICS_DB, "AnalyticsRoomDatabase_Impl");
        }
        try {
            Class<?> databaseClass = XposedHelpers.findClass(schemaString("symbols.telemetry.analytics_db_class",
                    "com.zing.zalo.analytics.db.AnalyticsRoomDatabase_Impl"), classLoader);
            int hooked = 0;
            Set<String> expectedEnabled = enabledAnalyticsAccessors();
            Set<String> foundEnabled = new HashSet<>();
            for (Method method : databaseClass.getDeclaredMethods()) {
                if (!looksLikeDaoAccessor(method)
                        || analyticsLabelForAccessor(method.getName()) == null) {
                    continue;
                }
                method.setAccessible(true);
                if (expectedEnabled.contains(method.getName())) {
                    foundEnabled.add(method.getName());
                }
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        hookDaoObject(param.getResult(), method.getName());
                    }
                });
                hooked++;
            }
            if (enabled) {
                if (!foundEnabled.containsAll(expectedEnabled)) {
                    Set<String> missing = new HashSet<>(expectedEnabled);
                    missing.removeAll(foundEnabled);
                    SelfCheckRegistry.markStale(FEATURE_ANALYTICS_DB,
                            "AnalyticsRoomDatabase_Impl", "missing enabled accessors " + missing);
                } else if (hooked > 0) {
                    SelfCheckRegistry.markInstalled(FEATURE_ANALYTICS_DB, "AnalyticsRoomDatabase_Impl", hooked);
                } else {
                    SelfCheckRegistry.markStale(FEATURE_ANALYTICS_DB, "AnalyticsRoomDatabase_Impl", "no DAO accessors");
                }
            }
            log("Current analytics DB accessor hooks installed -> " + hooked + " methods");
        } catch (Throwable throwable) {
            if (enabled) {
                SelfCheckRegistry.markStale(FEATURE_ANALYTICS_DB, "AnalyticsRoomDatabase_Impl", throwable.getMessage());
            }
            log("Failed to hook current analytics DB accessors: " + throwable);
        }
    }

    private int hookMeasurementServiceMethods(Class<?> clazz, String label, boolean enabled) {
        int hooked = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!looksLikeServiceMethod(method)) {
                continue;
            }
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Intent intent = firstIntentArg(param);
                    if (!enabled || !isMeasurementBrokerIntent(intent)) {
                        return;
                    }
                    param.setResult(serviceFallbackFor(method.getReturnType()));
                    SelfCheckRegistry.markSuppressed(FEATURE_MEASUREMENT_BIND, label + "#" + method.getName(), measurementComponentName(intent));
                    log("Blocked " + label + "#" + method.getName() + " -> " + measurementComponentName(intent));
                }
            });
            hooked++;
        }
        return hooked;
    }

    private static boolean looksLikeServiceMethod(Method method) {
        if (!method.getName().contains("Service")) {
            return false;
        }
        return firstIntentParameterIndex(method) >= 0;
    }

    private static int firstIntentParameterIndex(Method method) {
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (Intent.class.isAssignableFrom(types[i])) {
                return i;
            }
        }
        return -1;
    }

    private static Intent firstIntentArg(XC_MethodHook.MethodHookParam param) {
        if (param.args == null) {
            return null;
        }
        for (Object arg : param.args) {
            if (arg instanceof Intent) {
                return (Intent) arg;
            }
        }
        return null;
    }

    private static boolean isMeasurementBrokerIntent(Intent intent) {
        return "com.google.android.gms.measurement.service.MeasurementBrokerService".equals(measurementComponentName(intent));
    }

    private static String measurementComponentName(Intent intent) {
        if (intent == null) {
            return "";
        }
        ComponentName component = intent.getComponent();
        return component == null ? "" : component.getClassName();
    }

    private static Object serviceFallbackFor(Class<?> returnType) {
        if (returnType == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (returnType == Long.TYPE) {
            return Long.valueOf(0L);
        }
        return null;
    }

    private void hookDaoObject(Object dao, String accessorName) {
        if (dao == null) {
            return;
        }
        if (!isAnalyticsDaoObject(dao)) {
            return;
        }
        String label = analyticsLabelForAccessor(accessorName);
        boolean enabled = label == null ? isAnyAnalyticsDbSuppressionEnabled() : isAnalyticsLabelEnabled(label);
        int candidates = 0;
        int newlyHooked = 0;
        for (Method method : dao.getClass().getDeclaredMethods()) {
            if (!isDaoWriteMethod(method)) {
                continue;
            }
            candidates++;
            String key = dao.getClass().getName() + "#" + method.getName()
                    + Arrays.toString(method.getParameterTypes()) + "->" + method.getReturnType().getName();
            if (!hookedDaoMethods.add(key)) {
                continue;
            }
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled || firstArgIsList(param)) {
                        return;
                    }
                    param.setResult(fallbackFor(method.getReturnType()));
                    SelfCheckRegistry.markSuppressed(FEATURE_ANALYTICS_DB, dao.getClass().getName() + "#" + method.getName(), label == null ? accessorName : label);
                }
            });
            newlyHooked++;
        }
        if (newlyHooked > 0) {
            log((label == null ? "Current analytics" : label)
                    + " DAO hooks installed for " + dao.getClass().getName()
                    + " via " + accessorName + " -> " + newlyHooked + " methods");
        }
        if (candidates > 0) {
            // The Room accessor may be called repeatedly. A duplicate guard means later
            // observations add zero new hooks; that is still a healthy installed path.
            if (enabled) {
                SelfCheckRegistry.markInstalled(FEATURE_ANALYTICS_DB, dao.getClass().getName(), candidates);
            }
        } else if (enabled) {
            SelfCheckRegistry.markStale(FEATURE_ANALYTICS_DB, dao.getClass().getName(),
                    "no suppressible DAO write methods via " + accessorName);
        }
    }

    private static boolean looksLikeDaoAccessor(Method method) {
        if (method.getParameterTypes().length != 0 || method.getReturnType() == Void.TYPE) {
            return false;
        }
        Class<?> returnType = method.getReturnType();
        String name = returnType.getName();
        // Only stable framework prefixes are excluded here. Callers additionally require the method
        // name to match a schema-defined accessor, which is what actually selects the DAO getters,
        // so no obfuscated package name is needed and none should be reintroduced.
        if (returnType.isPrimitive()
                || name.startsWith("java.")
                || name.startsWith("android.")
                || name.startsWith("androidx.")) {
            return false;
        }
        return true;
    }

    /**
     * Only stable framework prefixes are excluded. This is reached from a hook on an accessor whose
     * name matched a schema-defined one on the analytics database class, so the value is the DAO by
     * construction. Deliberately no obfuscated package names: those shuffle every Zalo release, and
     * the one that used to be listed first had already stopped matching anything.
     */
    private static boolean isAnalyticsDaoObject(Object dao) {
        String name = dao.getClass().getName();
        return !name.startsWith("java.")
                && !name.startsWith("android.")
                && !name.startsWith("androidx.");
    }

    private String analyticsLabelForAccessor(String accessorName) {
        if (schemaString("symbols.telemetry.analytics_event_accessor", "").equals(accessorName)) {
            return "Event analytics";
        }
        if (schemaString("symbols.telemetry.analytics_screen_accessor", "").equals(accessorName)) {
            return "Screen analytics";
        }
        if (schemaString("symbols.telemetry.analytics_session_accessor", "").equals(accessorName)) {
            return "Session analytics";
        }
        if (schemaString("symbols.telemetry.analytics_view_accessor", "").equals(accessorName)) {
            return "View analytics";
        }
        return null;
    }

    private Set<String> enabledAnalyticsAccessors() {
        Set<String> names = new HashSet<>();
        if (HookConfig.isEnabled(Tweaks.KEY_DISABLE_EVENT_ANALYTICS)) {
            names.add(schemaString("symbols.telemetry.analytics_event_accessor", ""));
        }
        if (HookConfig.isEnabled(Tweaks.KEY_DISABLE_SCREEN_ANALYTICS)) {
            names.add(schemaString("symbols.telemetry.analytics_screen_accessor", ""));
        }
        if (HookConfig.isEnabled(Tweaks.KEY_DISABLE_SESSION_ANALYTICS)) {
            names.add(schemaString("symbols.telemetry.analytics_session_accessor", ""));
        }
        if (HookConfig.isEnabled(Tweaks.KEY_DISABLE_VIEW_ANALYTICS)) {
            names.add(schemaString("symbols.telemetry.analytics_view_accessor", ""));
        }
        names.remove("");
        return names;
    }

    private boolean isAnalyticsLabelEnabled(String label) {
        if ("Event analytics".equals(label)) {
            return HookConfig.isEnabled(Tweaks.KEY_DISABLE_EVENT_ANALYTICS);
        }
        if ("Screen analytics".equals(label)) {
            return HookConfig.isEnabled(Tweaks.KEY_DISABLE_SCREEN_ANALYTICS);
        }
        if ("Session analytics".equals(label)) {
            return HookConfig.isEnabled(Tweaks.KEY_DISABLE_SESSION_ANALYTICS);
        }
        if ("View analytics".equals(label)) {
            return HookConfig.isEnabled(Tweaks.KEY_DISABLE_VIEW_ANALYTICS);
        }
        return false;
    }

    private boolean isAnyAnalyticsDbSuppressionEnabled() {
        return HookConfig.isEnabled(Tweaks.KEY_DISABLE_EVENT_ANALYTICS)
                || HookConfig.isEnabled(Tweaks.KEY_DISABLE_SCREEN_ANALYTICS)
                || HookConfig.isEnabled(Tweaks.KEY_DISABLE_SESSION_ANALYTICS)
                || HookConfig.isEnabled(Tweaks.KEY_DISABLE_VIEW_ANALYTICS);
    }

    static boolean isDaoWriteMethod(Method method) {
        return TelemetryDaoShape.isWriteMethod(method);
    }

    private static boolean firstArgIsList(XC_MethodHook.MethodHookParam param) {
        return param.args != null && param.args.length > 0 && param.args[0] instanceof java.util.List;
    }

    private static Object fallbackFor(Class<?> returnType) {
        if (returnType == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (returnType == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        return null;
    }

    private void hookAllNamedMethods(String className, Set<String> names, String label) {
        String feature = "telemetry." + label.toLowerCase(java.util.Locale.US).replace(' ', '_');
        if (className == null || className.isEmpty() || names == null || names.isEmpty()) {
            SelfCheckRegistry.markStale(feature, label, "schema symbols missing");
            return;
        }
        try {
            Class<?> clazz = XposedHelpers.findClass(className, classLoader);
            int hooked = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!names.contains(method.getName()) || method.getReturnType() != Void.TYPE) {
                    continue;
                }
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(null);
                        SelfCheckRegistry.markSuppressed(feature, className + "#" + method.getName(), label);
                    }
                });
                hooked++;
            }
            if (hooked > 0) {
                SelfCheckRegistry.markInstalled(feature, className, hooked);
            } else {
                SelfCheckRegistry.markStale(feature, className, "no matching methods");
            }
            log(label + " hooks installed for " + className + " -> " + hooked + " methods");
        } catch (Throwable throwable) {
            SelfCheckRegistry.markStale(feature, className, throwable.getMessage());
            log("Failed to hook telemetry class " + className + ": " + throwable);
        }
    }

    private static Object newAdInfo(Class<?> infoClass) throws Throwable {
        return XposedHelpers.newInstance(infoClass, FAKE_AD_ID, Boolean.TRUE);
    }

    private static String schemaString(String path, String fallback) {
        return SymbolSchema.string(HookConfig.resolveModuleContextForHooks(), path, fallback);
    }
}
