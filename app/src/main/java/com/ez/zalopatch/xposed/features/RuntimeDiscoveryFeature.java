package com.ez.zalopatch.xposed.features;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SelfCheckReceiver;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Runtime symbol-discovery tool for re-mapping obfuscated Zalo targets after an app update.
 *
 * Strategy (per docs/hook-target-comparison): anchor on STABLE surfaces only, then dump child
 * classes/fields/methods at runtime. The single most stable anchor is the Android
 * androidx RecyclerView.setAdapter(...) — version-independent — which lets us capture Zalo's
 * conversation adapter, its backing list item classes, and the conversation category fields
 * without depending on any obfuscated Zalo method name.
 *
 * Installed only after a compatibility-report diagnostics request, then cleared after the first
 * candidate dump for that installed Zalo version.
 */
public final class RuntimeDiscoveryFeature extends Feature {
    private static final String FEATURE_DISCOVERY = "runtime_discovery";
    private static final String MAIN_TAB_VIEW_CLASS = "com.zing.zalo.ui.maintab.MainTabView";
    private static final String MESSAGES_VIEW_CLASS = "com.zing.zalo.ui.maintab.msg.MessagesView";
    private static final String TAB_ME_VIEW_CLASS = "com.zing.zalo.ui.maintab.me.TabMeView";
    private static final String CONVERSATION_CLASS = "com.zing.zalo.data.chat.model.tabmessage.Conversation";

    private static final String[] LOADABLE_CLASSES = {
            MAIN_TAB_VIEW_CLASS,
            MESSAGES_VIEW_CLASS,
            TAB_ME_VIEW_CLASS,
            "com.zing.zalo.ui.maintab.msg.fixedbanner.FixedBannerView",
            "com.zing.zalo.zdesign.component.popover.PopoverView",
            "com.zing.zalo.zdesign.component.popover.PopoverItemView",
            "com.zing.zalo.ui.zviews.StrangerMessagesView",
            "com.zing.zalo.comm.oa.BizBoxMessagesView",
            "com.zing.zalo.comm.oa.ui.VipMessagesView",
            "com.zing.zalo.ui.widget.ZinstantAdItemView",
            "com.zing.zalo.social.presentation.timeline.components.ads.FeedItemZInstantAds",
            "com.zing.zalo.zinstant.utils.ZinstantCommunicatorHelper",
            "com.zing.zalo.zinstant.utils.ScriptHelperImpl",
            "com.zing.zalo.analytics.db.AnalyticsRoomDatabase",
            "com.zing.zalo.analytics.db.AnalyticsRoomDatabase_Impl",
            "com.zing.v4.view.ViewPager",
            "androidx.recyclerview.widget.RecyclerView"
    };

    private final AtomicBoolean classDumped = new AtomicBoolean(false);
    private final Set<String> dumpedAdapters = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> dumpedItemClasses = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> emittedItemCandidates = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> emittedSurfaceCandidates = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> dumpedViews = Collections.synchronizedSet(new HashSet<>());
    private final AtomicBoolean completionSent = new AtomicBoolean(false);

    public RuntimeDiscoveryFeature(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getFeatureName() {
        return "RuntimeDiscovery";
    }

    @Override
    public void doHook() {
        dumpLoadableClasses();
        hookRecyclerViewSetAdapter();
        hookZaloViewPagerSetAdapter();
        hookViewConstructor(MAIN_TAB_VIEW_CLASS);
        hookViewConstructor(MESSAGES_VIEW_CLASS);
        hookViewConstructor("com.zing.zalo.zdesign.component.popover.PopoverView");
        hookViewConstructor(TAB_ME_VIEW_CLASS);
        SelfCheckRegistry.markInstalled(FEATURE_DISCOVERY, "stable anchors", 6);
    }

    private void dumpLoadableClasses() {
        if (!classDumped.compareAndSet(false, true)) {
            return;
        }
        StringBuilder builder = new StringBuilder("loadable classes:");
        for (String className : LOADABLE_CLASSES) {
            builder.append('\n').append("  ").append(className).append('=');
            builder.append(XposedHelpers.findClassIfExists(className, classLoader) != null ? "yes" : "no");
        }
        log(builder.toString());
    }

    /**
     * Stable anchor. Every list in Zalo (inbox, popover, Me tab) is driven through this Android
     * method, so hooking it once captures every adapter + its data without any obfuscated name.
     */
    private void hookRecyclerViewSetAdapter() {
        Class<?> recyclerViewClass = XposedHelpers.findClassIfExists(
                "androidx.recyclerview.widget.RecyclerView", classLoader);
        if (recyclerViewClass == null) {
            log("RecyclerView class unavailable; cannot anchor on setAdapter");
            SelfCheckRegistry.markStale(FEATURE_DISCOVERY, "RecyclerView.setAdapter", "RecyclerView unavailable");
            return;
        }
        XposedBridge.hookAllMethods(recyclerViewClass, "setAdapter", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length < 1 || param.args[0] == null) {
                    return;
                }
                Object adapter = param.args[0];
                String adapterClass = adapter.getClass().getName();
                if (!isZaloClass(adapterClass)) {
                    return;
                }
                Surface surface = surfaceForAdapter(param.thisObject, adapter);
                boolean firstSight = dumpedAdapters.add(adapterClass);
                if (firstSight) {
                    SelfCheckRegistry.markSuppressed(FEATURE_DISCOVERY, "RecyclerView.setAdapter", adapterClass);
                    dumpAdapter(adapter, adapterClass, surface);
                }
                // Lists are usually empty at setAdapter time; re-dump after data loads so we
                // capture the real conversation item classes + category fields.
                scheduleDeferredDump(param.thisObject, adapter, adapterClass);
            }
        });
        log("Anchored on RecyclerView.setAdapter for adapter discovery");
        SelfCheckRegistry.markInstalled(FEATURE_DISCOVERY, "RecyclerView.setAdapter", 1);
    }

    private final Set<String> deferredDumped = Collections.synchronizedSet(new HashSet<>());

    /** Re-dump an adapter's list fields ~5s later, by which time conversations have loaded. */
    private void scheduleDeferredDump(Object recyclerView, Object adapter, String adapterClass) {
        if (!(recyclerView instanceof android.view.View)) {
            return;
        }
        if (!deferredDumped.add(adapterClass)) {
            return;
        }
        try {
            ((android.view.View) recyclerView).postDelayed(new Runnable() {
                @Override
                public void run() {
                    log("DEFERRED re-dump of " + adapterClass);
                    dumpAdapter(adapter, adapterClass, surfaceForAdapter(recyclerView, adapter));
                }
            }, 5000);
        } catch (Throwable ignored) {
        }
    }

    private void dumpAdapter(Object adapter, String adapterClass, Surface surface) {
        try {
            log("ADAPTER " + adapterClass);
            if (surface == Surface.INBOX) {
                emitCandidate("inbox_adapter=" + adapterClass);
                dumpMessagesViewAdapterField(adapter);
            } else if (surface == Surface.ME) {
                emitCandidate("me_adapter=" + adapterClass);
            }
            log("  methods -> " + describeMethods(adapter.getClass()));
            boolean inboxItemsObserved = false;
            // Find every List field on the adapter and dump the classes of its first items.
            for (Field field : adapter.getClass().getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(adapter);
                if (!(value instanceof List)) {
                    continue;
                }
                List<?> items = (List<?>) value;
                StringBuilder itemClasses = new StringBuilder();
                int limit = Math.min(items.size(), 20);
                for (int i = 0; i < limit; i++) {
                    Object item = items.get(i);
                    if (itemClasses.length() > 0) {
                        itemClasses.append(", ");
                    }
                    itemClasses.append(item == null ? "null" : item.getClass().getName());
                }
                log("  list field " + field.getName() + " size=" + items.size()
                        + " itemClasses=[" + itemClasses + "]");
                boolean surfaceItemsObserved = containsZaloItem(items, limit);
                if (surface == Surface.INBOX && surfaceItemsObserved) {
                    inboxItemsObserved = true;
                    emitCandidate("inbox_list adapter=" + adapterClass
                            + " field=" + field.getName()
                            + " item_classes=[" + itemClasses + "]");
                } else if (surface == Surface.ME && surfaceItemsObserved) {
                    emitCandidate("me_list adapter=" + adapterClass
                            + " field=" + field.getName()
                            + " item_classes=[" + itemClasses + "]");
                }
                // Deep-dump each distinct item class so we can re-map mw.c/a/q/v + category fields.
                for (int i = 0; i < limit; i++) {
                    Object item = items.get(i);
                    if (item != null) {
                        emitItemCandidate(item, surface);
                        dumpItemClass(item);
                    }
                }
            }
            // Some adapters expose items only via a no-arg getter (e.g. av.b.M():ArrayList).
            for (Method method : adapter.getClass().getDeclaredMethods()) {
                if (method.getParameterTypes().length != 0
                        || !java.util.Collection.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(adapter);
                    if (!(value instanceof java.util.Collection)) {
                        continue;
                    }
                    java.util.Collection<?> coll = (java.util.Collection<?>) value;
                    int i = 0;
                    for (Object item : coll) {
                        if (i++ >= 12) {
                            break;
                        }
                        if (item != null) {
                            log("  via " + method.getName() + "() item " + item.getClass().getName());
                            dumpItemClass(item);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            // Do not clear the one-shot request after an unrelated empty adapter. Inbox rows are
            // the minimum evidence needed to remap filtering safely.
            if (inboxItemsObserved) {
                sendCompletion();
            }
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE_DISCOVERY, "adapter dump " + adapterClass, throwable);
            log("  adapter dump failed: " + throwable.getClass().getSimpleName());
        }
    }

    private void hookZaloViewPagerSetAdapter() {
        Class<?> viewPagerClass = XposedHelpers.findClassIfExists("com.zing.v4.view.ViewPager", classLoader);
        if (viewPagerClass == null) {
            log("Zalo ViewPager unavailable; static bottom-tab shape scan remains available");
            return;
        }
        XposedBridge.hookAllMethods(viewPagerClass, "setAdapter", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length < 1 || param.args[0] == null) {
                    return;
                }
                Object adapter = param.args[0];
                Class<?> adapterClass = adapter.getClass();
                if (!isZaloClass(adapterClass.getName())
                        || !hasBottomTabConsumerShape(adapterClass)) {
                    return;
                }
                String candidate = "bottom_tabs_consumer=" + adapterClass.getName();
                if (emittedSurfaceCandidates.add(candidate)) {
                    emitCandidate(candidate + " fields=[" + describeFieldTypes(adapterClass) + "]");
                }
            }
        });
        SelfCheckRegistry.markInstalled(FEATURE_DISCOVERY, "com.zing.v4.view.ViewPager.setAdapter", 1);
    }

    private static Surface surfaceForAdapter(Object recyclerView, Object adapter) {
        if (hasViewAncestor(recyclerView, MESSAGES_VIEW_CLASS)
                || referencesSurface(adapter, MESSAGES_VIEW_CLASS)) {
            return Surface.INBOX;
        }
        if (hasViewAncestor(recyclerView, TAB_ME_VIEW_CLASS)
                || referencesSurface(adapter, TAB_ME_VIEW_CLASS)) {
            return Surface.ME;
        }
        return Surface.OTHER;
    }

    private static boolean hasViewAncestor(Object candidate, String className) {
        if (!(candidate instanceof View)) {
            return false;
        }
        Object current = candidate;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (className.equals(current.getClass().getName())) {
                return true;
            }
            if (!(current instanceof View)) {
                return false;
            }
            ViewParent parent = ((View) current).getParent();
            current = parent;
        }
        return false;
    }

    /**
     * RecyclerView may be attached after setAdapter(), so ancestry alone is not reliable at that
     * callback. Live adapters often retain their named stable surface as a field.
     */
    private static boolean referencesSurface(Object adapter, String className) {
        if (adapter == null) {
            return false;
        }
        for (Class<?> current = adapter.getClass(); current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (className.equals(field.getType().getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsZaloItem(List<?> items, int limit) {
        for (int i = 0; i < limit; i++) {
            Object item = items.get(i);
            if (item != null && isZaloClass(item.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    private void dumpMessagesViewAdapterField(Object adapter) {
        for (Class<?> current = adapter.getClass(); current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!MESSAGES_VIEW_CLASS.equals(field.getType().getName())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object messagesView = field.get(adapter);
                    if (messagesView == null) {
                        continue;
                    }
                    for (Class<?> viewClass = messagesView.getClass(); viewClass != null;
                         viewClass = viewClass.getSuperclass()) {
                        for (Field viewField : viewClass.getDeclaredFields()) {
                            viewField.setAccessible(true);
                            if (viewField.get(messagesView) != adapter) {
                                continue;
                            }
                            String candidate = "messages_view_adapter_field=" + viewField.getName()
                                    + " adapter=" + adapter.getClass().getName();
                            if (emittedSurfaceCandidates.add(candidate)) {
                                emitCandidate(candidate);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void emitItemCandidate(Object item, Surface surface) {
        if (surface == Surface.OTHER || item == null) {
            return;
        }
        Class<?> itemClass = item.getClass();
        String key = surface.name() + ":" + itemClass.getName();
        if (!emittedItemCandidates.add(key)) {
            return;
        }
        if (surface == Surface.ME) {
            emitCandidate("me_item=" + itemClass.getName()
                    + " fields=[" + describeFieldTypes(itemClass) + "]");
            return;
        }
        String conversationField = "";
        for (Field field : itemClass.getDeclaredFields()) {
            if (CONVERSATION_CLASS.equals(field.getType().getName())) {
                conversationField = field.getName();
                break;
            }
        }
        emitCandidate("inbox_item=" + itemClass.getName()
                + " conversation_field=" + conversationField
                + " fields=[" + describeFieldTypes(itemClass) + "]");
    }

    private void sendCompletion() {
        if (!completionSent.compareAndSet(false, true)) {
            return;
        }
        Context context = HookConfig.resolveModuleContextForHooks();
        if (context == null) {
            return;
        }
        try {
            long versionCode = SymbolSchema.installedZaloVersionCode(
                    HookConfig.resolveFallbackContextForHooks());
            Bundle response = context.getContentResolver().call(
                    android.net.Uri.parse("content://com.ez.zalopatch.config"),
                    "complete_runtime_discovery", String.valueOf(versionCode), null);
            if (response == null || !response.getBoolean("completed", false)) {
                sendFallback(context, SelfCheckReceiver.ACTION_COMPLETE_RUNTIME_DISCOVERY,
                        versionCode, "", "");
            }
        } catch (Throwable throwable) {
            long versionCode = SymbolSchema.installedZaloVersionCode(
                    HookConfig.resolveFallbackContextForHooks());
            sendFallback(context, SelfCheckReceiver.ACTION_COMPLETE_RUNTIME_DISCOVERY,
                    versionCode, "", "");
        }
    }

    /**
     * Dump an inbox list item once per class: its no-arg accessor methods (to find group/OA/media
     * predicates) and the int fields of any nested conversation object (to find the category field
     * that replaces the old Conversation.f48696h).
     */
    private void dumpItemClass(Object item) {
        String itemClass = item.getClass().getName();
        if (!dumpedItemClasses.add(itemClass)) {
            return;
        }
        try {
            log("  ITEM " + itemClass);
            SelfCheckRegistry.markSuppressed(FEATURE_DISCOVERY, "item dump", itemClass);
            log("    fields -> " + describeFields(item));
            log("    methods -> " + describeMethods(item.getClass()));
            // Walk object-typed fields that point at zalo classes (e.g. the Conversation) and dump
            // their int fields + field list — this is where the native category int lives.
            for (Field field : item.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (field.getType().isPrimitive() || field.getType() == String.class) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object sub = field.get(item);
                    if (sub == null || !isZaloClass(sub.getClass().getName())) {
                        continue;
                    }
                    log("    field " + field.getName() + " -> " + sub.getClass().getName()
                            + " ints=[" + describeIntFields(sub) + "]");
                    log("      subfields -> " + describeFields(sub));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE_DISCOVERY, "item dump " + itemClass, throwable);
            log("    item dump failed: " + throwable.getClass().getSimpleName());
        }
    }

    private void hookViewConstructor(String className) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
        if (clazz == null) {
            log("surface unavailable " + className);
            SelfCheckRegistry.markStale(FEATURE_DISCOVERY, className, "surface unavailable");
            return;
        }
        XposedBridge.hookAllConstructors(clazz, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!dumpedViews.add(className)) {
                    return;
                }
                try {
                    Object instance = param.thisObject;
                    log("VIEW " + className);
                    SelfCheckRegistry.markSuppressed(FEATURE_DISCOVERY, "view constructor", className);
                    String fields = describeFields(instance);
                    String methods = describeMethods(instance.getClass());
                    log("  fields -> " + fields);
                    log("  methods -> " + methods);
                    emitEvidence("surface", "VIEW " + className + "\nfields -> " + fields
                            + "\nmethods -> " + methods);
                    if (MAIN_TAB_VIEW_CLASS.equals(className)) {
                        emitMainTabCandidates(instance.getClass());
                    } else if (TAB_ME_VIEW_CLASS.equals(className)) {
                        emitCandidate("me_view=" + className
                                + " fields=[" + describeFieldTypes(instance.getClass()) + "]");
                    }
                } catch (Throwable throwable) {
                    SelfCheckRegistry.markFailed(FEATURE_DISCOVERY, className, throwable);
                    log(className + " dump failed: " + throwable.getClass().getSimpleName());
                }
            }
        });
    }

    private void emitMainTabCandidates(Class<?> mainTabClass) {
        for (Field field : mainTabClass.getDeclaredFields()) {
            Class<?> fieldType = field.getType();
            if (fieldType.getName().contains("ViewPager")) {
                String candidate = "bottom_tabs_pager_field=" + field.getName()
                        + " type=" + fieldType.getName();
                if (emittedSurfaceCandidates.add(candidate)) {
                    emitCandidate(candidate);
                }
            }
            if (hasBottomTabConsumerShape(fieldType)) {
                String candidate = "bottom_tabs_consumer_field=" + field.getName()
                        + " class=" + fieldType.getName();
                if (emittedSurfaceCandidates.add(candidate)) {
                    emitCandidate(candidate + " fields=[" + describeFieldTypes(fieldType) + "]");
                }
            }
        }
    }

    private void emitCandidate(String value) {
        log("CANDIDATE " + value);
        emitEvidence("candidate", "CANDIDATE " + value);
    }

    private void emitEvidence(String kind, String value) {
        Context context = HookConfig.resolveModuleContextForHooks();
        if (context == null) return;
        try {
            long versionCode = SymbolSchema.installedZaloVersionCode(
                    HookConfig.resolveFallbackContextForHooks());
            Bundle extras = new Bundle();
            extras.putLong("version_code", versionCode);
            extras.putString("kind", kind);
            extras.putString("value", value);
            Bundle response = context.getContentResolver().call(
                    android.net.Uri.parse("content://com.ez.zalopatch.config"),
                    "record_runtime_discovery_evidence", null, extras);
            if (response == null || !response.getBoolean("recorded", false)) {
                sendFallback(context, SelfCheckReceiver.ACTION_RECORD_RUNTIME_DISCOVERY_EVIDENCE,
                        versionCode, kind, value);
            }
        } catch (Throwable ignored) {
            long versionCode = SymbolSchema.installedZaloVersionCode(
                    HookConfig.resolveFallbackContextForHooks());
            sendFallback(context, SelfCheckReceiver.ACTION_RECORD_RUNTIME_DISCOVERY_EVIDENCE,
                    versionCode, kind, value);
        }
    }

    private static void sendFallback(
            Context context, String action, long versionCode, String kind, String value) {
        if (context == null || android.os.Build.VERSION.SDK_INT < 34) return;
        try {
            Intent intent = new Intent(action);
            intent.setComponent(new ComponentName("com.ez.zalopatch",
                    "com.ez.zalopatch.SelfCheckReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra("version_code", versionCode);
            if (!kind.isEmpty()) intent.putExtra("kind", kind);
            if (!value.isEmpty()) intent.putExtra("value", value);
            android.app.BroadcastOptions options = android.app.BroadcastOptions.makeBasic();
            options.setShareIdentityEnabled(true);
            context.sendBroadcast(intent, null, options.toBundle());
        } catch (Throwable throwable) {
            Log.i("ZaloPatch", "Runtime discovery fallback failed: "
                    + throwable.getClass().getSimpleName());
        }
    }

    private static boolean hasBottomTabConsumerShape(Class<?> clazz) {
        boolean hasIntArray = false;
        boolean hasBooleanArray = false;
        for (Field field : clazz.getDeclaredFields()) {
            hasIntArray |= field.getType() == int[].class;
            hasBooleanArray |= field.getType() == boolean[].class;
        }
        return hasIntArray && hasBooleanArray;
    }

    private static String describeFieldTypes(Class<?> clazz) {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (Field field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (count++ >= 60) {
                builder.append(" ...");
                break;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(field.getName()).append(':').append(field.getType().getName());
        }
        return builder.toString();
    }

    private String describeFields(Object instance) {
        StringBuilder builder = new StringBuilder();
        Field[] fields = instance.getClass().getDeclaredFields();
        int count = 0;
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (count++ >= 60) {
                builder.append(" ...");
                break;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(field.getName()).append(':').append(field.getType().getName());
            try {
                field.setAccessible(true);
                Object value = field.get(instance);
                if (value != null && isZaloClass(value.getClass().getName())) {
                    builder.append('=').append(value.getClass().getName());
                }
            } catch (Throwable ignored) {
            }
        }
        return builder.toString();
    }

    private String describeIntFields(Object instance) {
        StringBuilder builder = new StringBuilder();
        for (Field field : instance.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (field.getType() != int.class && field.getType() != Integer.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(instance);
                if (builder.length() > 0) {
                    builder.append(", ");
                }
                builder.append(field.getName()).append('=').append(value);
            } catch (Throwable ignored) {
            }
        }
        return builder.toString();
    }

    private String describeMethods(Class<?> clazz) {
        StringBuilder builder = new StringBuilder();
        Method[] methods = clazz.getDeclaredMethods();
        int count = 0;
        for (Method method : methods) {
            if (count++ >= 90) {
                builder.append(" ...");
                break;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(method.getName()).append('(');
            Class<?>[] params = method.getParameterTypes();
            for (int p = 0; p < params.length; p++) {
                if (p > 0) {
                    builder.append(',');
                }
                builder.append(params[p].getSimpleName());
            }
            builder.append("):").append(method.getReturnType().getSimpleName());
        }
        return builder.toString();
    }

    private enum Surface {
        INBOX,
        ME,
        OTHER
    }
    /**
     * Heuristic: anything under the Zalo root, or a short dotted name that is not a platform or
     * Kotlin package, is treated as Zalo's own obfuscated code. The 24-character bound is a guess
     * that has not been checked against evidence; it exists to keep long framework names out.
     */
    private static boolean isZaloClass(String name) {
        if (name == null) {
            return false;
        }
        if (name.startsWith("com.zing.zalo")) {
            return true;
        }
        // Obfuscated short package names (e.g. mw.c, rc1.x, jp.h) have no dots-deep java/android prefix.
        return !name.startsWith("java.") && !name.startsWith("javax.")
                && !name.startsWith("android.") && !name.startsWith("androidx.")
                && !name.startsWith("kotlin.") && name.indexOf('.') >= 0
                && name.length() < 24;
    }

}
