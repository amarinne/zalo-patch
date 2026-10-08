package com.ez.zalopatch.xposed.features;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.os.Handler;
import android.os.Looper;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.MessagesHomeLaunch;
import com.ez.zalopatch.BottomTabsArrays;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ez.zalopatch.xposed.core.XpHooks;
import com.ez.zalopatch.xposed.core.XpReflect;

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
    // Set by the old generation in onHotReloading. Pending retry and class-load
    // callbacks must not install hooks after the framework froze old code.
    private static volatile boolean retiredForHotReload;

    /** Stops pending retry and watch callbacks from arming hooks after reload. */
    public static void retireForHotReload() {
        retiredForHotReload = true;
    }
    private final AtomicBoolean loggedOnce = new AtomicBoolean(false);
    private final AtomicBoolean currentConsumersLoggedOnce = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<XpHooks.Handle> classLoadUnhooks = new CopyOnWriteArrayList<>();
    private static final java.util.Set<String> schemaSourceChecks = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static final java.util.Set<String> schemaFallbackPaths = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static final java.util.Set<String> symbolFailuresLogged = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static final java.util.Set<String> staleSymbolsReported = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private CurrentTabSymbols currentSymbols;
    private boolean hideDiscovery;
    private boolean hideTimeline;
    private boolean keepGroupTab;
    private boolean forceMessagesAsHome;
    // Lifecycle and view callbacks run on the host main thread. Weak keys and values
    // must not retain a retired activity through its pager or native tab controller.
    private final WeakHashMap<Activity, LaunchHomeRequest> homeLaunches = new WeakHashMap<>();
    private final WeakHashMap<Activity, WeakReference<Object>> homeTabs = new WeakHashMap<>();

    private static final class LaunchHomeRequest {
        boolean resumed;
        boolean queued;
    }

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
            SelfCheckRegistry.markDisabled(FEATURE_FORCE_HOME, "ZaloLauncherActivity launch");
            return;
        }
        if (!forceMessagesAsHome) {
            SelfCheckRegistry.markDisabled(FEATURE_FORCE_HOME, "ZaloLauncherActivity launch");
        }

        if (installMatchingHooks()) {
            return;
        }

        scheduleRetry();
        watchClassLoads();
        SelfCheckRegistry.markStale(FEATURE_STATE, "symbol schema bottom_tabs.current_tab_symbols", "no matching current tab state class");
        SelfCheckRegistry.markStale(FEATURE_CONSUMERS, "symbol schema bottom_tabs.current_tab_symbols", "no matching current tab state class");
        if (forceMessagesAsHome) {
            SelfCheckRegistry.markStale(FEATURE_FORCE_HOME, "symbol schema bottom_tabs.current_tab_symbols", "no matching current tab state class");
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

        // Legacy bottom-tab route retired: supported releases expose current tab
        // symbols (or the DexKit overlay), and the legacy letters are unmapped.
        return false;
    }

    private void scheduleRetry() {
        if (!retryScheduled.compareAndSet(false, true)) {
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (retiredForHotReload) {
                    return;
                }
                try {
                    if (installMatchingHooks()) {
                        return;
                    }
                } catch (Throwable retryFailure) {
                    log("Bottom tab retry failed: "
                            + retryFailure.getClass().getSimpleName());
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
        if (watched.isEmpty()) {
            return;
        }
        List<XpHooks.Handle> hooks = XpHooks.hookAllMethods(FEATURE_STATE, ClassLoader.class, "loadClass", new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) {
                if (retiredForHotReload) {
                    return;
                }
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
        for (XpHooks.Handle unhook : classLoadUnhooks) {
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
            Class<?> clazz = XpReflect.findClassIfExists(className, loader);
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

    private void hookCurrentBottomTabs(Class<?> mainTabClass) {
        XpHooks.Before rebuildBefore = new XpHooks.Before() {
            @Override
            public void before(XpHooks.HookParam param) {
                TAB_REBUILD_DEPTH.set(TAB_REBUILD_DEPTH.get() + 1);
            }
        };
        XpHooks.After rebuildAfter = new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) {
                try {
                    applyCurrentTabState(param.thisObject);
                } finally {
                    TAB_REBUILD_DEPTH.set(Math.max(0, TAB_REBUILD_DEPTH.get() - 1));
                }
            }
        };
        XpHooks.hookAllMethods(FEATURE_STATE, mainTabClass, currentMethod("rebuild"),
                rebuildBefore, rebuildAfter);

        if (!currentMethod("refresh").isEmpty()) XpHooks.hookAllMethods(FEATURE_STATE, mainTabClass, currentMethod("refresh"),
                new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) {
                applyCurrentTabState(param.thisObject);
            }
        });

        XpHooks.hookAllMethods(FEATURE_STATE, mainTabClass, currentMethod("singleton"),
                new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) {
                Object state = param.getResult();
                if (state != null) {
                    applyCurrentTabState(state);
                }
            }
        });

        if (hideDiscovery || !currentSymbols.preserveIconArrays) {
            hookCurrentBooleanFlag(mainTabClass, currentMethod("hide_discovery"), hideDiscovery);
        }
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
            XpHooks.Before customTabBefore = new XpHooks.Before() {
                @Override
                public void before(XpHooks.HookParam param) {
                    applyCurrentSingletonState(mainTabClass);
                }
            };
            XpHooks.After customTabAfter = new XpHooks.After() {
                @Override
                public void after(XpHooks.HookParam param) {
                    applyCurrentSingletonState(mainTabClass);
                    if (HookConfig.isDebugEnabled()) {
                        logCurrentTabArrays("CustomMainTab init", mainTabClass);
                    }
                }
            };
            XpHooks.hookAllConstructors(FEATURE_CONSUMERS, customMainTabClass,
                    customTabBefore, customTabAfter);
        }

        for (String adapterClass : SymbolSchema.strings(HookConfig.resolveModuleContextForHooks(),
                "symbols.bottom_tabs.consumer_adapter_classes")) {
            hookCurrentPagerAdapter(mainTabClass, adapterClass, "", "");
        }
        hookDiscoveredConsumers(mainTabClass);
    }

    /**
     * Consumer adapters without mapping: MainTabView field types in the state
     * class's package that hold icon/preloaded arrays. Package co-rotation keeps
     * this aligned across releases; the array shape keeps it precise. The state
     * class itself is excluded. Hook effects are idempotent with the schema path
     * (same Before/After), so overlap is harmless.
     */
    private void hookDiscoveredConsumers(Class<?> mainTabClass) {
        if (currentSymbols == null || currentSymbols.stateClassName == null
                || currentSymbols.stateClassName.isEmpty()) {
            return;
        }
        Class<?> mainTabViewClass = findClassIfExists(CURRENT_MAIN_TAB_VIEW_CLASS);
        if (mainTabViewClass == null) {
            return;
        }
        int hooked = 0;
        for (Field field : mainTabViewClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> type = field.getType();
            if (!looksLikeConsumerAdapter(type, currentSymbols.stateClassName)) {
                continue;
            }
            hookCurrentPagerAdapter(mainTabClass, type.getName(), "", "");
            hooked++;
        }
        if (hooked > 0) {
            log("Discovered bottom tab consumers -> " + hooked);
        }
    }

    /** Package co-location plus icon/preloaded array shape, minus the state class. */
    static boolean looksLikeConsumerAdapter(Class<?> type, String stateClassName) {
        if (type == null || type.isPrimitive() || type.isArray()
                || type.isInterface() || type.isEnum()) {
            return false;
        }
        String name = type.getName();
        if (name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("android.") || name.startsWith("androidx.")
                || name.startsWith("kotlin.")) {
            return false;
        }
        if (name.equals(stateClassName)) {
            return false;
        }
        if (stateClassName == null || !stateClassName.contains(".")) {
            return false;
        }
        String statePackage = stateClassName.substring(0, stateClassName.lastIndexOf('.') + 1);
        if (!name.startsWith(statePackage)) {
            return false;
        }
        boolean ints = false;
        boolean bools = false;
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (field.getType() == int[].class) {
                ints = true;
            } else if (field.getType() == boolean[].class) {
                bools = true;
            }
        }
        return ints && bools;
    }

    private void hookCurrentForceMessagesAsHome(Class<?> mainTabClass) {
        if (!forceMessagesAsHome) return;
        Class<?> tabType = findClassIfExists(CURRENT_MAIN_TAB_VIEW_CLASS);
        Class<?> launcherType = findClassIfExists("com.zing.zalo.ui.ZaloLauncherActivity");
        if (tabType == null || launcherType == null) {
            SelfCheckRegistry.markStale(FEATURE_FORCE_HOME, "launcher startup", "launcher or MainTabView unavailable");
            return;
        }
        Method createView = null;
        for (Method method : tabType.getDeclaredMethods()) {
            Class<?>[] args = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers()) && method.getReturnType() == View.class
                    && args.length == 3 && args[0] == LayoutInflater.class
                    && args[1] == ViewGroup.class && args[2] == Bundle.class) {
                if (createView != null) {
                    SelfCheckRegistry.markStale(FEATURE_FORCE_HOME, "launcher startup", "ambiguous MainTabView creation shape");
                    return;
                }
                createView = method;
            }
        }
        if (createView == null) {
            SelfCheckRegistry.markStale(FEATURE_FORCE_HOME, "launcher startup", "MainTabView creation shape unavailable");
            return;
        }
        XpHooks.hookMethod(FEATURE_FORCE_HOME, createView, new XpHooks.After() {
            @Override public void after(XpHooks.HookParam param) {
                if (!(param.getResult() instanceof View)) return;
                Activity activity = activityOf(((View) param.getResult()).getContext());
                if (activity == null || !launcherType.isInstance(activity)) return;
                homeTabs.put(activity, new WeakReference<>(param.thisObject));
                queueLaunchHome(mainTabClass, activity);
            }
        });
        List<XpHooks.Handle> creates = XpHooks.hookAllMethods(FEATURE_FORCE_HOME, launcherType, "onCreate", new XpHooks.Before() {
            @Override public void before(XpHooks.HookParam param) {
                Activity activity = (Activity) param.thisObject;
                armLaunchHome(activity, activity.getIntent(), true, param.args.length > 0 && param.args[0] != null);
            }
        });
        List<XpHooks.Handle> intents = XpHooks.hookAllMethods(FEATURE_FORCE_HOME, launcherType, "onNewIntent",
                new XpHooks.Before() {
                    @Override public void before(XpHooks.HookParam param) {
                        if (param.args.length == 1 && param.args[0] instanceof Intent)
                            armLaunchHome((Activity) param.thisObject, (Intent) param.args[0], false, false);
                    }
                }, new XpHooks.After() {
                    @Override public void after(XpHooks.HookParam param) {
                        Activity activity = (Activity) param.thisObject;
                        LaunchHomeRequest request = homeLaunches.get(activity);
                        if (request != null) request.resumed = true;
                        queueLaunchHome(mainTabClass, activity);
                    }
                });
        List<XpHooks.Handle> resumes = XpHooks.hookAllMethods(FEATURE_FORCE_HOME, launcherType, "onResume", new XpHooks.After() {
            @Override public void after(XpHooks.HookParam param) {
                Activity activity = (Activity) param.thisObject;
                LaunchHomeRequest request = homeLaunches.get(activity);
                if (request != null) request.resumed = true;
                queueLaunchHome(mainTabClass, activity);
            }
        });
        if (creates.isEmpty() || intents.isEmpty() || resumes.isEmpty()) {
            SelfCheckRegistry.markStale(FEATURE_FORCE_HOME, "launcher startup", "launcher lifecycle unavailable");
            return;
        }
        SelfCheckRegistry.markInstalled(FEATURE_FORCE_HOME, "ZaloLauncherActivity launch", creates.size() + intents.size() + resumes.size() + 1);
    }

    private void armLaunchHome(Activity activity, Intent intent, boolean cold, boolean restored) {
        // A new routed intent invalidates an older queued launcher callback. Warm
        // launches use the incoming argument: Zalo may return before setIntent().
        homeLaunches.remove(activity);
        if (retiredForHotReload || intent == null) return;
        boolean routed = intent.getData() != null || (intent.getExtras() != null && !intent.getExtras().isEmpty());
        if (MessagesHomeLaunch.accepts(intent.getAction(), intent.hasCategory(Intent.CATEGORY_LAUNCHER), routed, cold, restored))
            homeLaunches.put(activity, new LaunchHomeRequest());
    }

    private Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }

    private void queueLaunchHome(Class<?> mainTabClass, Activity activity) {
        LaunchHomeRequest request = homeLaunches.get(activity);
        WeakReference<Object> tabRef = homeTabs.get(activity);
        Object tab = tabRef == null ? null : tabRef.get();
        if (request == null || !request.resumed || request.queued || tab == null) return;
        try {
            Object pager = getPagerByShape(tab, "setCurrentItem");
            if (!(pager instanceof View) || activityOf(((View) pager).getContext()) != activity) return;
            Object state = applyCurrentSingletonState(mainTabClass);
            int messageIndex = getIntFieldOr(state, currentSymbols.messageIndexField, -1);
            if (messageIndex < 0) return;
            int sourcePage = (Integer) XpReflect.callMethod(pager, "getCurrentItem");
            // Consume even when the host already selected Messages. No subsequent
            // tab choice or ordinary resume can re-arm this launch request.
            request.queued = true;
            WeakReference<Activity> ownerRef = new WeakReference<>(activity);
            WeakReference<View> pagerRef = new WeakReference<>((View) pager);
            ((View) pager).post(() -> {
                Activity owner = ownerRef.get();
                View currentPager = pagerRef.get();
                if (retiredForHotReload || owner == null || currentPager == null
                        || owner.isFinishing() || owner.isDestroyed() || !currentPager.isAttachedToWindow()
                        || homeLaunches.get(owner) != request || homeTabs.get(owner) != tabRef) return;
                try {
                    Object currentTab = tabRef.get();
                    if (currentTab == null || getPagerByShape(currentTab, "setCurrentItem") != currentPager
                            || !Integer.valueOf(sourcePage).equals(XpReflect.callMethod(currentPager, "getCurrentItem"))) return;
                    if (sourcePage != messageIndex) {
                        XpReflect.callMethod(currentPager, "setCurrentItem", messageIndex, false);
                        SelfCheckRegistry.markSuppressed(FEATURE_FORCE_HOME, "ZaloLauncherActivity launch",
                                "launch from=" + sourcePage + " to=" + messageIndex);
                    }
                } catch (Throwable throwable) {
                    SelfCheckRegistry.markFailed(FEATURE_FORCE_HOME, "launcher startup", throwable);
                }
            });
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE_FORCE_HOME, "launcher startup", throwable);
        }
    }

    /**
     * Pager by stable shape: the first MainTabView instance field whose value
     * carries the named method (the swipeable pager's setCurrentItem). The
     * per-release pager field letter is retired.
     */
    private Object getPagerByShape(Object mainTabView, String methodName) {
        if (mainTabView == null) {
            return null;
        }
        for (Field field : mainTabView.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(mainTabView);
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
        XpHooks.Before pagerBefore = new XpHooks.Before() {
            @Override
            public void before(XpHooks.HookParam param) {
                applyCurrentSingletonState(mainTabClass);
            }
        };
        XpHooks.After pagerAfter = new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) {
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
        };
        XpHooks.hookAllConstructors(FEATURE_CONSUMERS, adapterClass, pagerBefore, pagerAfter);
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
            return XpReflect.getObjectField(object, fieldName);
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
            Object state = XpReflect.callStaticMethod(
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
            Object state = XpReflect.callStaticMethod(
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
            return XpReflect.getIntField(object, fieldName);
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
        if (methodName == null || methodName.isEmpty()) return;
        XpHooks.hookAllMethods(FEATURE_STATE, mainTabClass, methodName, new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) {
                if (!isRebuilding()) {
                    param.setResult(!hidden);
                }
            }
        });
    }

    private void hookCurrentIndexMethod(Class<?> mainTabClass, String methodName, String tabName) {
        if (methodName == null || methodName.isEmpty()) return;
        XpHooks.hookAllMethods(FEATURE_STATE, mainTabClass, methodName, new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) throws Throwable {
                if (!isRebuilding()) {
                    param.setResult(indexOf(getFilteredTabs(param.thisObject), tabName));
                }
            }
        });
    }

    private void hookCurrentSizeMethod(Class<?> mainTabClass, String methodName) {
        if (methodName == null || methodName.isEmpty()) return;
        XpHooks.hookAllMethods(FEATURE_STATE, mainTabClass, methodName, new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) throws Throwable {
                if (!isRebuilding()) {
                    param.setResult(getFilteredTabs(param.thisObject).size());
                }
            }
        });
    }

    private void applyCurrentTabState(Object mainTabState) {
        try {
            List<Object> tabs = getOriginalTabs(mainTabState);
            if (keepGroupTab && !currentSymbols.preserveIconArrays) {
                Object groupTab = getCurrentTab(currentSymbols.groupTabField);
                if (groupTab != null && !containsTab(tabs, "GROUP")) {
                    tabs.add(Math.min(2, tabs.size()), groupTab);
                }
            }
            List<Object> filteredTabs = getFilteredTabs(mainTabState);
            int[] filteredIcons;
            boolean[] filteredPreloaded;
            if (currentSymbols.preserveIconArrays) {
                // The surviving enum-to-int method returns labels, not icons. Keep
                // the host's actual icon and preload values paired with each enum.
                int[] icons = (int[]) XpReflect.getObjectField(mainTabState, currentSymbols.iconsField);
                boolean[] preloaded = (boolean[]) XpReflect.getObjectField(mainTabState, currentSymbols.preloadedField);
                boolean restoreGroup = keepGroupTab && !containsTab(tabs, "GROUP");
                BottomTabsArrays.Result arrays = BottomTabsArrays.configure(tabs, icons, preloaded,
                        hideDiscovery, hideTimeline, keepGroupTab,
                        restoreGroup ? getCurrentTab(currentSymbols.groupTabField) : null,
                        restoreGroup ? nativeGroupIcon() : 0);
                filteredTabs = arrays.tabs;
                filteredIcons = arrays.icons;
                filteredPreloaded = arrays.preloaded;
            } else {
                filteredIcons = buildCurrentIconArray(mainTabState, filteredTabs);
                filteredPreloaded = filterBooleanArray(tabs, findBooleanArray(mainTabState, tabs.size()));
            }

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
            return tabClass == null ? null : XpReflect.getStaticObjectField(tabClass, fieldName);
        } catch (Throwable throwable) {
            logSymbolFailure("field", currentSymbols.enumClassName + "#" + fieldName, throwable);
            return null;
        }
    }

    private int nativeGroupIcon() {
        if (findClassIfExists("com.zing.zalo.ui.maintab.group.GroupTabParentView") == null) {
            return 0;
        }
        android.content.Context context = HookConfig.resolveFallbackContextForHooks();
        return context == null ? 0 : context.getResources().getIdentifier(
                "stencils_ic_tab_groups", "drawable", "com.zing.zalo");
    }

    private int[] buildCurrentIconArray(Object mainTabState, List<Object> tabs) {
        int[] result = new int[tabs.size()];
        for (int i = 0; i < tabs.size(); i++) {
            try {
                result[i] = (Integer) XpReflect.callStaticMethod(
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
            XpReflect.setIntField(object, fieldName, value);
            return true;
        } catch (Throwable throwable) {
            logSymbolFailure("field", classNameOf(object) + "#" + fieldName, throwable);
            return false;
        }
    }

    private boolean setBooleanFieldIfExists(Object object, String fieldName, boolean value) {
        try {
            XpReflect.setBooleanField(object, fieldName, value);
            return true;
        } catch (Throwable throwable) {
            logSymbolFailure("field", classNameOf(object) + "#" + fieldName, throwable);
            return false;
        }
    }

    private boolean setObjectFieldIfExists(Object object, String fieldName, Object value) {
        try {
            XpReflect.setObjectField(object, fieldName, value);
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

    private static boolean isRebuilding() {
        return TAB_REBUILD_DEPTH.get() > 0;
    }

    @SuppressWarnings("unchecked")
    private List<Object> getOriginalTabs(Object mainTabState) throws Throwable {
        if (currentSymbols != null && !currentSymbols.tabsField.isEmpty()) {
            Object tabs = XpReflect.getObjectField(mainTabState, currentSymbols.tabsField);
            if (tabs instanceof List) return (List<Object>) tabs;
            throw new IllegalStateException("mapped tabs field is not a list");
        }
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
                        item.optString("preloaded_field", ""),
                        item.optString("tabs_field", ""),
                        item.optBoolean("preserve_icon_arrays", false)));
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

    private static String currentMethod(String role) {
        SymbolSchema.Active active = SymbolSchema.activeForHooks(HookConfig.resolveModuleContextForHooks());
        JSONObject symbols = active == null || active.root == null ? null : active.root.optJSONObject("symbols");
        JSONObject bottom = symbols == null ? null : symbols.optJSONObject("bottom_tabs");
        JSONObject methods = bottom == null ? null : bottom.optJSONObject("current_methods");
        JSONArray definitions = bottom == null ? null : bottom.optJSONArray("current_tab_symbols");
        JSONObject definition = definitions == null ? null : definitions.optJSONObject(0);
        if (definition != null && definition.optBoolean("preserve_icon_arrays", false)
                && methods != null && !methods.has(role)
                && ("refresh".equals(role) || "group_flag".equals(role)
                || "phonebook_index".equals(role) || "more_index".equals(role)
                || "me_index".equals(role) || "size".equals(role))) {
            // These methods were removed from the compact host route. Do not fall
            // through to neighbouring names or report intentional absence as stale.
            return "";
        }
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
        final String tabsField;
        final boolean preserveIconArrays;

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
                String preloadedField,
                String tabsField,
                boolean preserveIconArrays) {
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
            this.tabsField = tabsField;
            this.preserveIconArrays = preserveIconArrays;
        }
    }
}
