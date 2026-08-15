package com.ez.zalopatch.xposed.features;

import android.os.Handler;
import android.os.Looper;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class BottomTabsFeature extends Feature {
    private static final String FEATURE_STATE = "bottom_tabs.state";
    private static final String FEATURE_CONSUMERS = "bottom_tabs.consumers";
    private static final String FEATURE_FORCE_HOME = "bottom_tabs.force_home";
    private static final String FEATURE_SCHEMA = "bottom_tabs.schema";
    private static final String CURRENT_CUSTOM_MAIN_TAB_CLASS = "com.zing.zalo.ui.maintab.widget.CustomMainTab";
    private static final String CURRENT_MAIN_TAB_VIEW_CLASS = "com.zing.zalo.ui.maintab.MainTabView";
    private static final ThreadLocal<Integer> TAB_REBUILD_DEPTH = ThreadLocal.withInitial(() -> 0);
    private final AtomicBoolean installComplete = new AtomicBoolean(false);
    private final AtomicBoolean retryScheduled = new AtomicBoolean(false);
    private final AtomicBoolean classLoadWatchInstalled = new AtomicBoolean(false);
    private final AtomicBoolean loggedOnce = new AtomicBoolean(false);
    private final AtomicBoolean currentConsumersLoggedOnce = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<XC_MethodHook.Unhook> classLoadUnhooks = new CopyOnWriteArrayList<>();
    private static final java.util.Set<String> schemaSourceChecks = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static final java.util.Set<String> schemaFallbackPaths = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static final java.util.Set<String> symbolFailuresLogged = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static final java.util.Set<String> staleSymbolsReported = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private CurrentTabSymbols currentSymbols;
    private boolean hideDiscovery;
    private boolean hideTimeline;
    private boolean keepGroupTab;
    private boolean forceMessagesAsHome;

    public BottomTabsFeature(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getFeatureName() {
        return "BottomTabs";
    }

    @Override
    public void doHook() throws Throwable {
        hideDiscovery = HookConfig.isEnabled(Tweaks.KEY_HIDE_DISCOVERY_TAB);
        hideTimeline = HookConfig.isEnabled(Tweaks.KEY_HIDE_TIMELINE_TAB);
        keepGroupTab = HookConfig.isEnabled(Tweaks.KEY_KEEP_GROUP_TAB);
        forceMessagesAsHome = HookConfig.isEnabled(Tweaks.KEY_FORCE_MESSAGES_AS_HOME);

        if (!hideDiscovery && !hideTimeline && !keepGroupTab && !forceMessagesAsHome) {
            SelfCheckRegistry.markDisabled(FEATURE_STATE, "bottom tab settings");
            SelfCheckRegistry.markDisabled(FEATURE_CONSUMERS, "bottom tab consumers");
            SelfCheckRegistry.markDisabled(FEATURE_FORCE_HOME, "MainTabView#onResume");
            return;
        }
        if (!forceMessagesAsHome) {
            SelfCheckRegistry.markDisabled(FEATURE_FORCE_HOME, "MainTabView#onResume");
        }

        if (installMatchingHooks()) {
            return;
        }

        scheduleRetry();
        watchClassLoads();
        SelfCheckRegistry.markStale(FEATURE_STATE, "symbol schema bottom_tabs.current_tab_symbols", "no matching current or legacy tab state class");
        SelfCheckRegistry.markStale(FEATURE_CONSUMERS, "symbol schema bottom_tabs.current_tab_symbols", "no matching current or legacy tab state class");
        if (forceMessagesAsHome) {
            SelfCheckRegistry.markStale(FEATURE_FORCE_HOME, "symbol schema bottom_tabs.current_tab_symbols", "no matching current or legacy tab state class");
        }
    }

    private boolean installMatchingHooks() {
        return installMatchingHooks(null);
    }

    private boolean installMatchingHooks(ClassLoader preferredLoader) {
        if (installComplete.get()) {
            return true;
        }
        for (CurrentTabSymbols symbols : currentTabSymbols()) {
            Class<?> currentMainTabClass = findClassIfExists(symbols.stateClassName, preferredLoader);
            if (currentMainTabClass != null) {
                currentSymbols = symbols;
                hookCurrentBottomTabs(currentMainTabClass);
                hookCurrentTabConsumers(currentMainTabClass);
                hookCurrentForceMessagesAsHome(currentMainTabClass);
                installComplete.set(true);
                unhookClassLoadWatch();
                SelfCheckRegistry.markInstalled(FEATURE_STATE, symbols.stateClassName, 1);
                SelfCheckRegistry.markInstalled(FEATURE_CONSUMERS, "CustomMainTab/PagerAdapter", 1);
                log("Current bottom tab hooks installed for " + symbols.stateClassName);
                return true;
            }
        }

        String legacyClassName = schemaString("symbols.bottom_tabs.legacy_state_class", "");
        Class<?> mainTabClass = findClassIfExists(legacyClassName, preferredLoader);
        if (mainTabClass == null) {
            return false;
        }
        hookLegacyBottomTabs(mainTabClass);
        installComplete.set(true);
        unhookClassLoadWatch();
        SelfCheckRegistry.markInstalled(FEATURE_STATE, legacyClassName, 12);
        return true;
    }

    private void scheduleRetry() {
        if (!retryScheduled.compareAndSet(false, true)) {
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (installMatchingHooks()) {
                    return;
                }
                SelfCheckRegistry.markStale(FEATURE_STATE, "symbol schema bottom_tabs.current_tab_symbols", "retry found no matching class");
                SelfCheckRegistry.markStale(FEATURE_CONSUMERS, "symbol schema bottom_tabs.current_tab_symbols", "retry found no matching class");
                if (forceMessagesAsHome) {
                    SelfCheckRegistry.markStale(FEATURE_FORCE_HOME, "symbol schema bottom_tabs.current_tab_symbols", "retry found no matching class");
                }
            }
        }, 2000L);
    }

    private void watchClassLoads() {
        if (!classLoadWatchInstalled.compareAndSet(false, true)) {
            return;
        }
        ArrayList<String> watched = new ArrayList<>();
        for (CurrentTabSymbols symbols : currentTabSymbols()) {
            if (symbols.stateClassName != null && !symbols.stateClassName.isEmpty()) {
                watched.add(symbols.stateClassName);
            }
        }
        String legacyClassName = schemaString("symbols.bottom_tabs.legacy_state_class", "");
        if (legacyClassName != null && !legacyClassName.isEmpty()) {
            watched.add(legacyClassName);
        }
        if (watched.isEmpty()) {
            return;
        }
        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (installComplete.get() || !(param.getResult() instanceof Class<?>)) {
                    return;
                }
                Class<?> loadedClass = (Class<?>) param.getResult();
                if (!watched.contains(loadedClass.getName())) {
                    return;
                }
                if (installMatchingHooks(loadedClass.getClassLoader())) {
                    log("Bottom tab hooks installed after class load " + loadedClass.getName());
                }
            }
        });
        classLoadUnhooks.addAll(hooks);
        log("Watching bottom tab class loads -> " + watched);
    }

    private void unhookClassLoadWatch() {
        for (XC_MethodHook.Unhook unhook : classLoadUnhooks) {
            try {
                unhook.unhook();
            } catch (Throwable ignored) {
            }
        }
        classLoadUnhooks.clear();
    }

    private ClassLoader liveClassLoader() {
        android.content.Context context = HookConfig.resolveFallbackContextForHooks();
        ClassLoader loader = context == null ? null : context.getClassLoader();
        return loader != null ? loader : classLoader;
    }

    private Class<?> findClassIfExists(String className) {
        return findClassIfExists(className, null);
    }

    private Class<?> findClassIfExists(String className, ClassLoader preferredLoader) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        ClassLoader[] loaders = new ClassLoader[]{
                preferredLoader,
                liveClassLoader(),
                classLoader,
                Thread.currentThread().getContextClassLoader()
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }
            Class<?> clazz = XposedHelpers.findClassIfExists(className, loader);
            if (clazz != null) {
                return clazz;
            }
        }
        try {
            return Class.forName(className, false, liveClassLoader());
        } catch (Throwable throwable) {
            logSymbolFailure("class", className, throwable);
            return null;
        }
    }

    private void hookLegacyBottomTabs(Class<?> mainTabClass) {
        String rebuildMethod = legacyRebuildMethod();
        if (rebuildMethod == null || rebuildMethod.isEmpty()) {
            SelfCheckRegistry.markStale(FEATURE_STATE, mainTabClass.getName(), "missing rebuild method");
            return;
        }
        XposedBridge.hookAllMethods(mainTabClass, rebuildMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                TAB_REBUILD_DEPTH.set(TAB_REBUILD_DEPTH.get() + 1);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    Object mainTabState = param.thisObject;
                    List<Object> originalTabs = getOriginalTabs(mainTabState);
                    if (!originalTabs.isEmpty() && loggedOnce.compareAndSet(false, true)) {
                        log("Original bottom tabs -> " + stringifyTabs(originalTabs));
                        log("Bottom tab flags -> discovery=" + hideDiscovery + ", timeline=" + hideTimeline);
                    }
                } finally {
                    TAB_REBUILD_DEPTH.set(Math.max(0, TAB_REBUILD_DEPTH.get() - 1));
                }
            }
        });

        hookBooleanFlag(mainTabClass, schemaString("symbols.bottom_tabs.legacy_hide_discovery_method", ""), hideDiscovery);
        hookBooleanFlag(mainTabClass, schemaString("symbols.bottom_tabs.legacy_hide_timeline_method", ""), hideTimeline);
        hookIndexMethod(mainTabClass, legacyIndexMethod("message"), "MESSAGE");
        hookIndexMethod(mainTabClass, legacyIndexMethod("phonebook"), "PHONEBOOK");
        hookIndexMethod(mainTabClass, legacyIndexMethod("group"), "GROUP");
        hookIndexMethod(mainTabClass, legacyIndexMethod("discovery"), "DISCOVERY");
        hookIndexMethod(mainTabClass, legacyIndexMethod("timeline"), "TIMELINE");
        hookIndexMethod(mainTabClass, legacyIndexMethod("more"), "MORE");
        hookIndexMethod(mainTabClass, legacyIndexMethod("me"), "ME");
        hookFilteredTabList(mainTabClass, schemaString("symbols.bottom_tabs.legacy_tab_list_method", ""));
        hookFilteredTabSize(mainTabClass, schemaString("symbols.bottom_tabs.legacy_tab_size_method", ""));
        hookFilteredIntArray(mainTabClass, schemaString("symbols.bottom_tabs.legacy_int_array_method", ""));
        hookFilteredBooleanArray(mainTabClass, schemaString("symbols.bottom_tabs.legacy_boolean_array_method", ""));
    }

    private void hookCurrentBottomTabs(Class<?> mainTabClass) {
        XposedBridge.hookAllMethods(mainTabClass, currentMethod("rebuild"), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                TAB_REBUILD_DEPTH.set(TAB_REBUILD_DEPTH.get() + 1);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    applyCurrentTabState(param.thisObject);
                } finally {
                    TAB_REBUILD_DEPTH.set(Math.max(0, TAB_REBUILD_DEPTH.get() - 1));
                }
            }
        });

        XposedBridge.hookAllMethods(mainTabClass, currentMethod("refresh"), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                applyCurrentTabState(param.thisObject);
            }
        });

        XposedBridge.hookAllMethods(mainTabClass, currentMethod("singleton"), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object state = param.getResult();
                if (state != null) {
                    applyCurrentTabState(state);
                }
            }
        });

        hookCurrentBooleanFlag(mainTabClass, currentMethod("hide_discovery"), hideDiscovery);
        if (keepGroupTab) {
            hookCurrentBooleanFlag(mainTabClass, currentMethod("group_flag"), false);
        }
        hookCurrentIndexMethod(mainTabClass, currentMethod("message_index"), "MESSAGE");
        hookCurrentIndexMethod(mainTabClass, currentMethod("phonebook_index"), "PHONEBOOK");
        hookCurrentIndexMethod(mainTabClass, currentMethod("group_index"), "GROUP");
        hookCurrentIndexMethod(mainTabClass, currentMethod("discovery_index"), "DISCOVERY");
        hookCurrentIndexMethod(mainTabClass, currentMethod("timeline_index"), "TIMELINE");
        hookCurrentIndexMethod(mainTabClass, currentMethod("more_index"), "MORE");
        hookCurrentIndexMethod(mainTabClass, currentMethod("me_index"), "ME");
        hookCurrentSizeMethod(mainTabClass, currentMethod("size"));
    }

    private void hookCurrentTabConsumers(Class<?> mainTabClass) {
        Class<?> customMainTabClass = findClassIfExists(CURRENT_CUSTOM_MAIN_TAB_CLASS);
        if (customMainTabClass != null) {
            XposedBridge.hookAllConstructors(customMainTabClass, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    applyCurrentSingletonState(mainTabClass);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    applyCurrentSingletonState(mainTabClass);
                    if (HookConfig.isDebugEnabled()) {
                        logCurrentTabArrays("CustomMainTab init", mainTabClass);
                    }
                }
            });
        }

        for (String adapterClass : SymbolSchema.strings(HookConfig.resolveModuleContextForHooks(),
                "symbols.bottom_tabs.consumer_adapter_classes")) {
            hookCurrentPagerAdapter(mainTabClass, adapterClass, "", "");
        }

    }

    private void hookCurrentForceMessagesAsHome(Class<?> mainTabClass) {
        if (!forceMessagesAsHome) {
            return;
        }
        Class<?> mainTabViewClass = findClassIfExists(CURRENT_MAIN_TAB_VIEW_CLASS);
        if (mainTabViewClass == null) {
            SelfCheckRegistry.markStale(FEATURE_FORCE_HOME, CURRENT_MAIN_TAB_VIEW_CLASS, "MainTabView unavailable");
            return;
        }
        String lifecycleMethod = schemaString("symbols.bottom_tabs.main_tab_home_hook_method", "");
        if (lifecycleMethod.isEmpty()) {
            SelfCheckRegistry.markStale(FEATURE_FORCE_HOME, mainTabViewClass.getName(),
                    "missing home lifecycle method");
            return;
        }
        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(mainTabViewClass, lifecycleMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object state = applyCurrentSingletonState(mainTabClass);
                    if (state == null) {
                        return;
                    }
                    int currentItem = currentMainTabItem(param.thisObject);
                    int messageIndex = getIntFieldOr(state, currentSymbols.messageIndexField, -1);
                    int groupIndex = getIntFieldOr(state, currentSymbols.groupIndexField, -1);
                    int discoveryIndex = getIntFieldOr(state, currentSymbols.discoveryIndexField, -1);
                    int timelineIndex = getIntFieldOr(state, currentSymbols.timelineIndexField, -1);
                    if (messageIndex < 0 || currentItem == messageIndex) {
                        return;
                    }
                    if (currentItem != groupIndex && currentItem != discoveryIndex && currentItem != timelineIndex) {
                        return;
                    }
                    Object pager = getObjectFieldOrFirstMatching(param.thisObject,
                            schemaString("symbols.bottom_tabs.main_tab_pager_field", ""), "setCurrentItem");
                    if (pager == null) {
                        SelfCheckRegistry.markStale(FEATURE_FORCE_HOME,
                                mainTabViewClass.getName() + "#" + lifecycleMethod, "pager unavailable");
                        return;
                    }
                    XposedHelpers.callMethod(pager, "setCurrentItem", messageIndex, false);
                    SelfCheckRegistry.markSuppressed(FEATURE_FORCE_HOME,
                            mainTabViewClass.getName() + "#" + lifecycleMethod,
                            "from=" + currentItem + " to=" + messageIndex);
                } catch (Throwable throwable) {
                    SelfCheckRegistry.markFailed(FEATURE_FORCE_HOME,
                            mainTabViewClass.getName() + "#" + lifecycleMethod, throwable);
                    log("Current force home hook failed: " + throwable.getClass().getSimpleName());
                }
            }
        });
        if (hooks.isEmpty()) {
            SelfCheckRegistry.markStale(FEATURE_FORCE_HOME, mainTabViewClass.getName() + "#" + lifecycleMethod,
                    "method unavailable");
            return;
        }
        SelfCheckRegistry.markInstalled(FEATURE_FORCE_HOME, mainTabViewClass.getName() + "#" + lifecycleMethod,
                hooks.size());
    }

    private int currentMainTabItem(Object mainTabView) {
        List<String> methodNames = SymbolSchema.strings(HookConfig.resolveModuleContextForHooks(),
                "symbols.bottom_tabs.main_tab_current_item_methods");
        Throwable lastFailure = null;
        int failedMethods = 0;
        for (String methodName : methodNames) {
            try {
                Object value = XposedHelpers.callMethod(mainTabView, methodName);
                if (value instanceof Integer) {
                    return (Integer) value;
                }
            } catch (Throwable throwable) {
                lastFailure = throwable;
                failedMethods++;
                logSymbolFailure("method", classNameOf(mainTabView) + "#" + methodName, throwable);
            }
        }
        if (!methodNames.isEmpty() && failedMethods == methodNames.size()) {
            markSymbolStale(FEATURE_FORCE_HOME,
                    classNameOf(mainTabView) + "#" + String.join("|", methodNames), lastFailure);
        }
        return -1;
    }

    private Object getObjectFieldOrFirstMatching(Object object, String preferredFieldName, String methodName) {
        try {
            Object value = XposedHelpers.getObjectField(object, preferredFieldName);
            if (value != null) {
                return value;
            }
        } catch (Throwable throwable) {
            logSymbolFailure("field", classNameOf(object) + "#" + preferredFieldName, throwable);
        }
        if (object == null) {
            return null;
        }
        for (Field field : object.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(object);
                if (value != null && hasMethod(value.getClass(), methodName)) {
                    return value;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private boolean hasMethod(Class<?> clazz, String methodName) {
        for (Method method : clazz.getMethods()) {
            if (methodName.equals(method.getName())) {
                return true;
            }
        }
        return false;
    }

    private void hookCurrentPagerAdapter(Class<?> mainTabClass, String adapterClassName, String iconsField, String preloadedField) {
        Class<?> adapterClass = findClassIfExists(adapterClassName);
        if (adapterClass == null) {
            return;
        }
        XposedBridge.hookAllConstructors(adapterClass, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                applyCurrentSingletonState(mainTabClass);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object state = applyCurrentSingletonState(mainTabClass);
                    if (state == null) {
                        return;
                    }
                    Object icons = getObjectFieldOrFirstArray(state, currentSymbols.iconsField, int[].class);
                    Object preloaded = getObjectFieldOrFirstArray(state, currentSymbols.preloadedField, boolean[].class);
                    if (!setObjectFieldIfExists(param.thisObject, iconsField, icons)) {
                        setFirstArrayField(param.thisObject, int[].class, icons);
                    }
                    if (!setObjectFieldIfExists(param.thisObject, preloadedField, preloaded)) {
                        setFirstArrayField(param.thisObject, boolean[].class, preloaded);
                    }
                    if (currentConsumersLoggedOnce.compareAndSet(false, true)) {
                        log("Current bottom tab consumers hooked -> " + adapterClassName);
                    }
                    SelfCheckRegistry.markSuppressed(FEATURE_CONSUMERS, adapterClassName,
                            "icons/preloaded refreshed");
                    if (HookConfig.isDebugEnabled()) {
                        logCurrentTabArrays("PagerAdapter init " + adapterClassName, mainTabClass);
                    }
                } catch (Throwable throwable) {
                    SelfCheckRegistry.markFailed(FEATURE_CONSUMERS, adapterClassName, throwable);
                    log("Current pager adapter refresh failed: " + throwable.getClass().getSimpleName());
                }
            }
        });
    }

    private boolean setFirstArrayField(Object object, Class<?> arrayType, Object value) {
        if (object == null || value == null || !arrayType.isInstance(value)) {
            return false;
        }
        for (Field field : object.getClass().getDeclaredFields()) {
            if (field.getType() != arrayType) {
                continue;
            }
            try {
                field.setAccessible(true);
                field.set(object, value);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private Object getObjectFieldOrFirstArray(Object object, String fieldName, Class<?> arrayType) {
        try {
            return XposedHelpers.getObjectField(object, fieldName);
        } catch (Throwable throwable) {
            logSymbolFailure("field", classNameOf(object) + "#" + fieldName, throwable);
        }
        if (object == null) {
            return null;
        }
        for (Field field : object.getClass().getDeclaredFields()) {
            if (field.getType() != arrayType || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                return field.get(object);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Object applyCurrentSingletonState(Class<?> mainTabClass) {
        try {
            Object state = XposedHelpers.callStaticMethod(
                    mainTabClass, currentMethod("singleton"));
            if (state != null) {
                applyCurrentTabState(state);
            }
            return state;
        } catch (Throwable throwable) {
            markSymbolStale(FEATURE_STATE,
                    mainTabClass.getName() + "#" + currentMethod("singleton"), throwable);
            logSymbolFailure("method",
                    mainTabClass.getName() + "#" + currentMethod("singleton"), throwable);
            return null;
        }
    }

    private void logCurrentTabArrays(String source, Class<?> mainTabClass) {
        try {
            Object state = XposedHelpers.callStaticMethod(
                    mainTabClass, currentMethod("singleton"));
            if (state == null) {
                return;
            }
            List<Object> tabs = getOriginalTabs(state);
            log(source + " tabs=" + stringifyTabs(tabs)
                    + " message=" + getIntFieldOr(state, currentSymbols.messageIndexField, -99)
                    + " phonebook=" + getIntFieldOr(state, currentSymbols.phonebookIndexField, -99)
                    + " group=" + getIntFieldOr(state, currentSymbols.groupIndexField, -99)
                    + " discovery=" + getIntFieldOr(state, currentSymbols.discoveryIndexField, -99)
                    + " timeline=" + getIntFieldOr(state, currentSymbols.timelineIndexField, -99)
                    + " more=" + getIntFieldOr(state, currentSymbols.moreIndexField, -99)
                    + " me=" + getIntFieldOr(state, currentSymbols.meIndexField, -99)
                    + " size=" + getIntFieldOr(state, currentSymbols.sizeField, -99));
        } catch (Throwable throwable) {
            log("Current tab debug snapshot failed: " + throwable.getClass().getSimpleName());
        }
    }

    private int getIntFieldOr(Object object, String fieldName, int fallback) {
        try {
            return XposedHelpers.getIntField(object, fieldName);
        } catch (Throwable throwable) {
            logSymbolFailure("field", classNameOf(object) + "#" + fieldName, throwable);
            return fallback;
        }
    }

    private void setCurrentStatePrimitiveFieldsByShape(
            Object state,
            boolean groupEnabled,
            boolean timelineEnabled,
            boolean discoveryEnabled,
            boolean moreEnabled,
            boolean meEnabled,
            int messageIndex,
            int phonebookIndex,
            int groupIndex,
            int discoveryIndex,
            int timelineIndex,
            int moreIndex,
            int meIndex,
            int size) {
        int[] indexValues = {
                messageIndex, phonebookIndex, groupIndex, discoveryIndex,
                timelineIndex, moreIndex, meIndex, size
        };
        boolean[] enabledValues = {
                groupEnabled, timelineEnabled, discoveryEnabled, moreEnabled, meEnabled
        };
        int intIndex = 0;
        int booleanIndex = 0;
        for (Field field : state.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                if (field.getType() == int.class && intIndex < indexValues.length) {
                    field.setInt(state, indexValues[intIndex++]);
                } else if (field.getType() == boolean.class && booleanIndex < enabledValues.length) {
                    field.setBoolean(state, enabledValues[booleanIndex++]);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void hookCurrentBooleanFlag(Class<?> mainTabClass, String methodName, boolean hidden) {
        XposedBridge.hookAllMethods(mainTabClass, methodName, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!isRebuilding()) {
                    param.setResult(!hidden);
                }
            }
        });
    }

    private void hookCurrentIndexMethod(Class<?> mainTabClass, String methodName, String tabName) {
        XposedBridge.hookAllMethods(mainTabClass, methodName, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isRebuilding()) {
                    param.setResult(indexOf(getFilteredTabs(param.thisObject), tabName));
                }
            }
        });
    }

    private void hookCurrentSizeMethod(Class<?> mainTabClass, String methodName) {
        XposedBridge.hookAllMethods(mainTabClass, methodName, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isRebuilding()) {
                    param.setResult(getFilteredTabs(param.thisObject).size());
                }
            }
        });
    }

    private void applyCurrentTabState(Object mainTabState) {
        try {
            List<Object> tabs = getOriginalTabs(mainTabState);
            if (keepGroupTab) {
                Object groupTab = getCurrentTab(currentSymbols.groupTabField);
                if (groupTab != null && !containsTab(tabs, "GROUP")) {
                    tabs.add(Math.min(2, tabs.size()), groupTab);
                }
            }
            List<Object> filteredTabs = getFilteredTabs(mainTabState);
            int[] filteredIcons = buildCurrentIconArray(mainTabState, filteredTabs);
            boolean[] filteredPreloaded = filterBooleanArray(tabs, findBooleanArray(mainTabState, tabs.size()));

            tabs.clear();
            tabs.addAll(filteredTabs);

            int messageIndex = indexOf(filteredTabs, "MESSAGE");
            int phonebookIndex = indexOf(filteredTabs, "PHONEBOOK");
            int groupIndex = indexOf(filteredTabs, "GROUP");
            int discoveryIndex = indexOf(filteredTabs, "DISCOVERY");
            int timelineIndex = indexOf(filteredTabs, "TIMELINE");
            int moreIndex = indexOf(filteredTabs, "MORE");
            int meIndex = indexOf(filteredTabs, "ME");

            boolean namedPrimitiveFieldsWritten = true;
            namedPrimitiveFieldsWritten &= setBooleanFieldIfExists(
                    mainTabState, currentSymbols.groupEnabledField, groupIndex >= 0);
            namedPrimitiveFieldsWritten &= setBooleanFieldIfExists(
                    mainTabState, currentSymbols.timelineEnabledField, timelineIndex >= 0);
            namedPrimitiveFieldsWritten &= setBooleanFieldIfExists(
                    mainTabState, currentSymbols.discoveryEnabledField, discoveryIndex >= 0);
            namedPrimitiveFieldsWritten &= setBooleanFieldIfExists(
                    mainTabState, currentSymbols.moreEnabledField, moreIndex >= 0);
            namedPrimitiveFieldsWritten &= setBooleanFieldIfExists(
                    mainTabState, currentSymbols.meEnabledField, meIndex >= 0);

            namedPrimitiveFieldsWritten &= setIntFieldIfExists(
                    mainTabState, currentSymbols.messageIndexField, messageIndex);
            namedPrimitiveFieldsWritten &= setIntFieldIfExists(
                    mainTabState, currentSymbols.phonebookIndexField, phonebookIndex);
            namedPrimitiveFieldsWritten &= setIntFieldIfExists(
                    mainTabState, currentSymbols.groupIndexField, groupIndex);
            namedPrimitiveFieldsWritten &= setIntFieldIfExists(
                    mainTabState, currentSymbols.discoveryIndexField, discoveryIndex);
            namedPrimitiveFieldsWritten &= setIntFieldIfExists(
                    mainTabState, currentSymbols.timelineIndexField, timelineIndex);
            namedPrimitiveFieldsWritten &= setIntFieldIfExists(
                    mainTabState, currentSymbols.moreIndexField, moreIndex);
            namedPrimitiveFieldsWritten &= setIntFieldIfExists(
                    mainTabState, currentSymbols.meIndexField, meIndex);
            namedPrimitiveFieldsWritten &= setIntFieldIfExists(
                    mainTabState, currentSymbols.sizeField, filteredTabs.size());
            if (!namedPrimitiveFieldsWritten) {
                setCurrentStatePrimitiveFieldsByShape(mainTabState, groupIndex >= 0, timelineIndex >= 0,
                        discoveryIndex >= 0, moreIndex >= 0, meIndex >= 0, messageIndex, phonebookIndex,
                        groupIndex, discoveryIndex, timelineIndex, moreIndex, meIndex, filteredTabs.size());
            }

            if (!setObjectFieldIfExists(mainTabState, currentSymbols.iconsField, filteredIcons)) {
                setFirstArrayField(mainTabState, int[].class, filteredIcons);
            }
            if (!setObjectFieldIfExists(mainTabState, currentSymbols.preloadedField, filteredPreloaded)) {
                setFirstArrayField(mainTabState, boolean[].class, filteredPreloaded);
            }

            if (loggedOnce.compareAndSet(false, true)) {
                log("Current bottom tabs -> " + stringifyTabs(filteredTabs)
                        + " flags discoveryHidden=" + hideDiscovery
                        + " timelineHidden=" + hideTimeline
                        + " keepGroup=" + keepGroupTab);
            }
            SelfCheckRegistry.markSuppressed(FEATURE_STATE, mainTabState.getClass().getName(),
                    stringifyTabs(filteredTabs));
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE_STATE, currentSymbols == null ? "current tabs" : currentSymbols.stateClassName, throwable);
            log("Current tab state apply failed: " + throwable.getClass().getSimpleName());
        }
    }

    private Object getCurrentTab(String fieldName) {
        try {
            Class<?> tabClass = findClassIfExists(currentSymbols.enumClassName);
            return tabClass == null ? null : XposedHelpers.getStaticObjectField(tabClass, fieldName);
        } catch (Throwable throwable) {
            logSymbolFailure("field", currentSymbols.enumClassName + "#" + fieldName, throwable);
            return null;
        }
    }

    private int[] buildCurrentIconArray(Object mainTabState, List<Object> tabs) {
        int[] result = new int[tabs.size()];
        for (int i = 0; i < tabs.size(); i++) {
            try {
                result[i] = (Integer) XposedHelpers.callStaticMethod(
                        mainTabState.getClass(), currentMethod("icon_resolver"), tabs.get(i));
            } catch (Throwable throwable) {
                logSymbolFailure("method", mainTabState.getClass().getName() + "#" + currentMethod("icon_resolver"), throwable);
                result[i] = 0;
            }
        }
        return result;
    }

    private boolean[] findBooleanArray(Object object, int expectedLength) {
        for (Field field : object.getClass().getDeclaredFields()) {
            if (field.getType() != boolean[].class) {
                continue;
            }
            try {
                field.setAccessible(true);
                boolean[] value = (boolean[]) field.get(object);
                if (value != null && value.length == expectedLength) {
                    return value;
                }
            } catch (Throwable ignored) {
            }
        }
        return new boolean[expectedLength];
    }

    private boolean containsTab(List<Object> tabs, String name) {
        return indexOf(tabs, name) >= 0;
    }

    private boolean setIntFieldIfExists(Object object, String fieldName, int value) {
        try {
            XposedHelpers.setIntField(object, fieldName, value);
            return true;
        } catch (Throwable throwable) {
            logSymbolFailure("field", classNameOf(object) + "#" + fieldName, throwable);
            return false;
        }
    }

    private boolean setBooleanFieldIfExists(Object object, String fieldName, boolean value) {
        try {
            XposedHelpers.setBooleanField(object, fieldName, value);
            return true;
        } catch (Throwable throwable) {
            logSymbolFailure("field", classNameOf(object) + "#" + fieldName, throwable);
            return false;
        }
    }

    private boolean setObjectFieldIfExists(Object object, String fieldName, Object value) {
        try {
            XposedHelpers.setObjectField(object, fieldName, value);
            return true;
        } catch (Throwable throwable) {
            logSymbolFailure("field", classNameOf(object) + "#" + fieldName, throwable);
            return false;
        }
    }

    private void logSymbolFailure(String kind, String symbol, Throwable throwable) {
        String key = kind + ":" + symbol;
        if (HookConfig.isDebugEnabled() && symbolFailuresLogged.add(key)) {
            log("Symbol resolution miss " + kind + "=" + symbol
                    + " exception=" + throwable.getClass().getSimpleName());
        }
    }

    private void markSymbolStale(String feature, String symbol, Throwable throwable) {
        String key = feature + ":" + symbol;
        if (throwable != null && staleSymbolsReported.add(key)) {
            SelfCheckRegistry.markStale(feature, symbol,
                    throwable.getClass().getSimpleName());
        }
    }

    private static String classNameOf(Object object) {
        return object == null ? "null" : object.getClass().getName();
    }

    private void hookBooleanFlag(Class<?> mainTabClass, String methodName, boolean hidden) {
        if (methodName == null || methodName.isEmpty()) {
            SelfCheckRegistry.markStale(FEATURE_STATE, mainTabClass.getName(), "missing boolean flag method");
            return;
        }
        XposedHelpers.findAndHookMethod(mainTabClass, methodName, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!isRebuilding()) {
                    param.setResult(!hidden);
                }
            }
        });
    }

    private void hookIndexMethod(Class<?> mainTabClass, String methodName, String tabName) {
        if (methodName == null || methodName.isEmpty()) {
            SelfCheckRegistry.markStale(FEATURE_STATE, mainTabClass.getName(), "missing index method for " + tabName);
            return;
        }
        XposedHelpers.findAndHookMethod(mainTabClass, methodName, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isRebuilding()) {
                    List<Object> filteredTabs = getFilteredTabs(param.thisObject);
                    param.setResult(indexOf(filteredTabs, tabName));
                }
            }
        });
    }

    private void hookFilteredTabList(Class<?> mainTabClass, String methodName) {
        if (methodName == null || methodName.isEmpty()) {
            SelfCheckRegistry.markStale(FEATURE_STATE, mainTabClass.getName(), "missing tab list method");
            return;
        }
        XposedHelpers.findAndHookMethod(mainTabClass, methodName, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isRebuilding()) {
                    param.setResult(getFilteredTabs(param.thisObject));
                }
            }
        });
    }

    private void hookFilteredTabSize(Class<?> mainTabClass, String methodName) {
        if (methodName == null || methodName.isEmpty()) {
            SelfCheckRegistry.markStale(FEATURE_STATE, mainTabClass.getName(), "missing tab size method");
            return;
        }
        XposedHelpers.findAndHookMethod(mainTabClass, methodName, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isRebuilding()) {
                    param.setResult(getFilteredTabs(param.thisObject).size());
                }
            }
        });
    }

    private void hookFilteredIntArray(Class<?> mainTabClass, String methodName) {
        if (methodName == null || methodName.isEmpty()) {
            SelfCheckRegistry.markStale(FEATURE_STATE, mainTabClass.getName(), "missing int array method");
            return;
        }
        XposedHelpers.findAndHookMethod(mainTabClass, methodName, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isRebuilding()) {
                    int[] original = (int[]) param.getResult();
                    if (original == null) {
                        return;
                    }
                    param.setResult(filterIntArray(getOriginalTabs(param.thisObject), original));
                }
            }
        });
    }

    private void hookFilteredBooleanArray(Class<?> mainTabClass, String methodName) {
        if (methodName == null || methodName.isEmpty()) {
            SelfCheckRegistry.markStale(FEATURE_STATE, mainTabClass.getName(), "missing boolean array method");
            return;
        }
        XposedHelpers.findAndHookMethod(mainTabClass, methodName, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isRebuilding()) {
                    boolean[] original = (boolean[]) param.getResult();
                    if (original == null) {
                        return;
                    }
                    param.setResult(filterBooleanArray(getOriginalTabs(param.thisObject), original));
                }
            }
        });
    }

    private static boolean isRebuilding() {
        return TAB_REBUILD_DEPTH.get() > 0;
    }

    @SuppressWarnings("unchecked")
    private List<Object> getOriginalTabs(Object mainTabState) throws Throwable {
        for (Field field : mainTabState.getClass().getDeclaredFields()) {
            if (List.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                Object value = field.get(mainTabState);
                if (value instanceof List) {
                    return (List<Object>) value;
                }
            }
        }
        throw new NoSuchFieldError(mainTabState.getClass().getName() + "#<List>");
    }

    private List<Object> getFilteredTabs(Object mainTabState) throws Throwable {
        List<Object> filteredTabs = new ArrayList<>(getOriginalTabs(mainTabState));
        Iterator<Object> iterator = filteredTabs.iterator();
        while (iterator.hasNext()) {
            String name = String.valueOf(iterator.next());
            if ((hideDiscovery && "DISCOVERY".equals(name)) || (hideTimeline && "TIMELINE".equals(name))) {
                iterator.remove();
            }
        }
        return filteredTabs;
    }

    private int indexOf(List<Object> tabs, String name) {
        if ((hideDiscovery && "DISCOVERY".equals(name)) || (hideTimeline && "TIMELINE".equals(name))) {
            return -1;
        }
        for (int i = 0; i < tabs.size(); i++) {
            if (name.equals(String.valueOf(tabs.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    private int[] filterIntArray(List<Object> originalTabs, int[] original) {
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < originalTabs.size() && i < original.length; i++) {
            String name = String.valueOf(originalTabs.get(i));
            if (!(hideDiscovery && "DISCOVERY".equals(name)) && !(hideTimeline && "TIMELINE".equals(name))) {
                values.add(original[i]);
            }
        }
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private boolean[] filterBooleanArray(List<Object> originalTabs, boolean[] original) {
        List<Boolean> values = new ArrayList<>();
        for (int i = 0; i < originalTabs.size() && i < original.length; i++) {
            String name = String.valueOf(originalTabs.get(i));
            if (!(hideDiscovery && "DISCOVERY".equals(name)) && !(hideTimeline && "TIMELINE".equals(name))) {
                values.add(original[i]);
            }
        }
        boolean[] result = new boolean[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private static String stringifyTabs(List<Object> tabs) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < tabs.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(tabs.get(i));
        }
        return builder.toString();
    }

    private static List<CurrentTabSymbols> currentTabSymbols() {
        try {
            SymbolSchema.Active active = SymbolSchema.activeForHooks(HookConfig.resolveModuleContextForHooks());
            JSONObject root = active.root;
            JSONObject symbols = root.optJSONObject("symbols");
            JSONObject bottomTabs = symbols == null ? null : symbols.optJSONObject("bottom_tabs");
            JSONArray array = bottomTabs == null ? null : bottomTabs.optJSONArray("current_tab_symbols");
            if (array == null || array.length() == 0) {
                recordSchemaSource("symbols.bottom_tabs.current_tab_symbols", "schema_missing", "", true);
                return java.util.Collections.emptyList();
            }
            ArrayList<CurrentTabSymbols> result = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                JSONObject enabled = item.optJSONObject("enabled_fields");
                JSONObject index = item.optJSONObject("index_fields");
                if (enabled == null || index == null) {
                    continue;
                }
                result.add(new CurrentTabSymbols(
                        item.optString("state_class", ""),
                        item.optString("enum_class", ""),
                        item.optString("group_tab_field", ""),
                        enabled.optString("group", ""),
                        enabled.optString("timeline", ""),
                        enabled.optString("discovery", ""),
                        enabled.optString("more", ""),
                        enabled.optString("me", ""),
                        index.optString("message", ""),
                        index.optString("phonebook", ""),
                        index.optString("group", ""),
                        index.optString("discovery", ""),
                        index.optString("timeline", ""),
                        index.optString("more", ""),
                        index.optString("me", ""),
                        index.optString("size", ""),
                        item.optString("icons_field", ""),
                        item.optString("preloaded_field", "")));
            }
            if (result.isEmpty()) {
                recordSchemaSource("symbols.bottom_tabs.current_tab_symbols", "schema_invalid", "", true);
                return java.util.Collections.emptyList();
            }
            recordSchemaSource("symbols.bottom_tabs.current_tab_symbols",
                    sourceKey(active.source), result.get(0).stateClassName, false);
            return result;
        } catch (Throwable ignored) {
            recordSchemaSource("symbols.bottom_tabs.current_tab_symbols", "schema_error", "", true);
            return java.util.Collections.emptyList();
        }
    }

    private static String legacyRebuildMethod() {
        return schemaString("symbols.bottom_tabs.legacy_rebuild_method", "");
    }

    private static String legacyIndexMethod(String tab) {
        return schemaString("symbols.bottom_tabs.legacy_index_methods." + tab, "");
    }

    private static String currentMethod(String role) {
        return schemaString("symbols.bottom_tabs.current_methods." + role, "");
    }

    private static String schemaString(String path, String fallback) {
        SymbolSchema.ResolvedString resolved = SymbolSchema.stringForHooks(
                HookConfig.resolveModuleContextForHooks(), path, fallback);
        recordSchemaSource(path, resolved.source, resolved.value, resolved.fallback);
        return resolved.value;
    }

    private static void recordSchemaSource(String path, String source, String value, boolean fallback) {
        if (fallback) {
            schemaFallbackPaths.add(path);
        } else {
            schemaFallbackPaths.remove(path);
        }
        String key = source + ":" + path;
        if (!schemaSourceChecks.add(key) && !fallback) {
            return;
        }
        boolean usesFallback = !schemaFallbackPaths.isEmpty();
        String status = usesFallback ? "stale" : "ok";
        String target = "source=" + (usesFallback ? "java_fallback" : source);
        String detail = path + "=" + shortValue(value);
        String error = usesFallback ? "Java fallback used for bottom-tab symbols: " + schemaFallbackPaths : "";
        SelfCheckRegistry.markStatus(FEATURE_SCHEMA, status, target, detail, error);
    }

    private static String sourceKey(String source) {
        if (source == null || source.isEmpty()) {
            return "unknown";
        }
        return source.toLowerCase(java.util.Locale.US).replace(' ', '_');
    }

    private static String shortValue(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private static final class CurrentTabSymbols {
        final String stateClassName;
        final String enumClassName;
        final String groupTabField;
        final String groupEnabledField;
        final String timelineEnabledField;
        final String discoveryEnabledField;
        final String moreEnabledField;
        final String meEnabledField;
        final String messageIndexField;
        final String phonebookIndexField;
        final String groupIndexField;
        final String discoveryIndexField;
        final String timelineIndexField;
        final String moreIndexField;
        final String meIndexField;
        final String sizeField;
        final String iconsField;
        final String preloadedField;

        CurrentTabSymbols(
                String stateClassName,
                String enumClassName,
                String groupTabField,
                String groupEnabledField,
                String timelineEnabledField,
                String discoveryEnabledField,
                String moreEnabledField,
                String meEnabledField,
                String messageIndexField,
                String phonebookIndexField,
                String groupIndexField,
                String discoveryIndexField,
                String timelineIndexField,
                String moreIndexField,
                String meIndexField,
                String sizeField,
                String iconsField,
                String preloadedField) {
            this.stateClassName = stateClassName;
            this.enumClassName = enumClassName;
            this.groupTabField = groupTabField;
            this.groupEnabledField = groupEnabledField;
            this.timelineEnabledField = timelineEnabledField;
            this.discoveryEnabledField = discoveryEnabledField;
            this.moreEnabledField = moreEnabledField;
            this.meEnabledField = meEnabledField;
            this.messageIndexField = messageIndexField;
            this.phonebookIndexField = phonebookIndexField;
            this.groupIndexField = groupIndexField;
            this.discoveryIndexField = discoveryIndexField;
            this.timelineIndexField = timelineIndexField;
            this.moreIndexField = moreIndexField;
            this.meIndexField = meIndexField;
            this.sizeField = sizeField;
            this.iconsField = iconsField;
            this.preloadedField = preloadedField;
        }
    }
}
