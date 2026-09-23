package com.ez.zalopatch.xposed.features;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ez.zalopatch.xposed.core.XpHooks;
import com.ez.zalopatch.xposed.core.XpReflect;

/**
 * Inbox conversation-list filtering + filter-popover categories.
 *
 * Inbox symbols drift between Zalo releases. The version-specific adapter, row, field, and
 * method names are resolved from the bundled/imported symbol schema; this class keeps behavior
 * and stable Android/Zalo anchors only.
 */
public final class InboxFeature extends Feature {
    private static final String FEATURE_FILTER = "inbox.filter";
    private static final String FEATURE_FILTER_BAR = "inbox.filter_bar";
    private static final String FEATURE_ROWS = "inbox.rows";
    private static final String FEATURE_MEDIA_BOX = "inbox.media_box";
    private static final String FEATURE_TAP_DIAGNOSTICS = "inbox.tap_diagnostics";
    private static final String FEATURE_DELETED_GROUP = "inbox.deleted_group";
    private static final String MESSAGE_VIEW_CLASS = "com.zing.zalo.ui.maintab.msg.MessagesView";
    // Native category integers. Field name is schema-provided and drifts between Zalo builds.
    private static final int CAT_NORMAL = 1;
    private static final int CAT_OA = 4;

    private static final String CATEGORY_FOCUSED = "focused";
    private static final String CATEGORY_NORMAL = "normal";
    private static final String CATEGORY_GROUPS = "groups";
    private static final String CATEGORY_OA = "oa";
    private static final String CATEGORY_MEDIA = "media";
    private static final String CATEGORY_STRANGERS = "strangers";

    // Process default comes from restart-applied settings; chip taps remain session-only.
    private volatile String sessionSelectedCategory = CATEGORY_FOCUSED;
    private volatile InboxListUpdate lastInboxListUpdate;
    private volatile Object liveMessagesView;
    private InboxNativeRoute strangerRoute;
    private Class<?> strangerDestination;
    private boolean preferConversationUid;
    private boolean defaultStrangersPending;
    private boolean rowsCompatible = true;
    private String rowsCompatibilityError = "";
    private volatile Object deletedGroupRepository;
    private volatile boolean deletedGroupCheckUnavailable;
    private volatile boolean deletedGroupCheckInstalled;

    /** Adapter class discovered at runtime when the schema has no mapping. */
    private static volatile String discoveredAdapterClass = "";
    private static final java.util.Set<String> loggedAdapterNames =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final java.util.Set<String> hookedAdapterClasses =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final String MEDIA_BOX_LAYOUT = "item_channel_media_box";    private final boolean mediaCompatible;
    private final String mediaCompatibilityError;
    private final boolean categoriesCompatible;
    private final String categoryCompatibilityError;
    private boolean hideMediaEnabled;
    private boolean categoriesEnabled;
    private boolean categoriesConfigured;
    private static final java.util.Set<String> schemaSourceChecks = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final java.util.Set<String> schemaFallbackPaths = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final java.util.Set<String> symbolFailuresLogged = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    // Debug diagnostics gate on debug.zalopatch at use time. Never gate permanent feature hooks on
    // diagnostics state.

    public InboxFeature(ClassLoader classLoader, boolean mediaCompatible,
                        String mediaCompatibilityError, boolean categoriesCompatible,
                        String categoryCompatibilityError, boolean rowsCompatible,
                        String rowsCompatibilityError) {
        super(classLoader);
        this.mediaCompatible = mediaCompatible;
        this.mediaCompatibilityError = mediaCompatibilityError;
        this.categoriesCompatible = categoriesCompatible;
        this.categoryCompatibilityError = categoryCompatibilityError;
        this.rowsCompatible = rowsCompatible;
        this.rowsCompatibilityError = rowsCompatibilityError;
    }

    @Override
    public String getFeatureName() {
        return "Inbox";
    }

    @Override
    public void doHook() {
        boolean configuredHideMedia = HookConfig.isEnabled(Tweaks.KEY_HIDE_MEDIA_BOX);
        boolean configuredCategories = HookConfig.isEnabled(Tweaks.KEY_FILTER_POPOVER_CATEGORIES);
        hideMediaEnabled = configuredHideMedia && mediaCompatible;
        categoriesEnabled = configuredCategories && categoriesCompatible;
        categoriesConfigured = configuredCategories;
        SymbolSchema.Active selected = SymbolSchema.activeForHooks(HookConfig.resolveModuleContextForHooks());
        preferConversationUid = selected.source.startsWith("DexKit") || selected.source.startsWith("Fallback");
        if (configuredCategories) {
            prepareStrangerRoute();
            defaultStrangersPending = strangerRoute != null
                    && HookConfig.isEnabled(Tweaks.KEY_CATEGORY_STRANGERS)
                    && HookConfig.getLevel(Tweaks.KEY_DEFAULT_INBOX_FILTER) == 4;
        }
        sessionSelectedCategory = configuredDefaultCategory(
                categoriesEnabled
                        ? HookConfig.getLevel(Tweaks.KEY_DEFAULT_INBOX_FILTER) : 0,
                HookConfig.isEnabled(Tweaks.KEY_CATEGORY_GROUPS),
                categoriesEnabled && HookConfig.isEnabled(Tweaks.KEY_CATEGORY_OA),
                false); // Strangers opens a separate native screen; never filter the inbox by its box row.
        if (!configuredHideMedia && !configuredCategories) {
            SelfCheckRegistry.markDisabled(FEATURE_FILTER, messageAdapterClass());
        }
        if (!configuredHideMedia) {
            SelfCheckRegistry.markDisabled(FEATURE_MEDIA_BOX,
                    "LayoutInflater#inflate(" + MEDIA_BOX_LAYOUT + ")");
        } else if (!mediaCompatible) {
            SelfCheckRegistry.markStale(FEATURE_MEDIA_BOX, "structural preflight",
                    mediaCompatibilityError);
        }
        if (!configuredCategories) {
            SelfCheckRegistry.markDisabled(FEATURE_FILTER_BAR, "RecyclerView.setAdapter");
        } else if (!categoriesCompatible) {
            SelfCheckRegistry.markStale(FEATURE_FILTER_BAR, "structural preflight",
                    categoryCompatibilityError);
        }
        // Row-behavior symbols (friend follow, topOut marker, box rows, adapter field)
        // are validated up front; a drifted letter fails closed here with its reason
        // instead of misclassifying rows. The core filter keeps its own gate above.
        if (rowsCompatible) {
            SelfCheckRegistry.markStatus(FEATURE_ROWS, "ok", "row symbols",
                    "friend follow, topOut, box rows, adapter field", "");
        } else {
            SelfCheckRegistry.markStale(FEATURE_ROWS, "structural preflight",
                    rowsCompatibilityError);
        }
        boolean debugEnabled = HookConfig.isDebugEnabled();
        if (!debugEnabled) {
            SelfCheckRegistry.markDisabled(FEATURE_TAP_DIAGNOSTICS, "debug diagnostics off");
        }

        if (configuredHideMedia || configuredCategories) {
            String adapter = messageAdapterClass();
            boolean pinnedOk = false;
            if (!adapter.isEmpty()) {
                try {
                    Class<?> adapterClass = XpReflect.findClass(adapter, classLoader);
                    pinnedOk = installListHooks(adapterClass);
                } catch (Throwable pinnedGone) {
                    // Pinned adapter renamed on this release; fall through to runtime discovery.
                    log("Pinned adapter " + adapter + " unavailable; using runtime discovery");
                }
            }
            if (!pinnedOk) {
                runGuarded("Adapter discovery", FEATURE_FILTER, "MessagesView attach",
                        this::hookAdapterDiscovery);
                SelfCheckRegistry.markInstalled(FEATURE_FILTER, "adapter discovery", 0);
            }
        }
        if (configuredCategories) {
            // Inject the bar whenever the user wants it; the setAdapter matcher uses the
            // runtime-discovered adapter, so a renamed release still shows the UI. Category
            // filtering itself degrades gracefully without the category discriminator.
            runGuarded("Inbox filter bar", FEATURE_FILTER_BAR, "RecyclerView.setAdapter",
                    this::hookInboxFilterBar);
        }
        if (configuredHideMedia) {
            runGuarded("Media box layout", FEATURE_MEDIA_BOX,
                    "LayoutInflater#inflate(" + MEDIA_BOX_LAYOUT + ")",
                    this::hookMediaBoxLayout);
        }
    }

    // ---------------------------------------------------------------- list filtering


    /**
     * Hides the media-box row through its stable layout name instead of a mapped
     * item class: collapsing the inflated {@code item_channel_media_box} root needs
     * no symbols at all. Per-inflation cost is one integer comparison otherwise.
     */
    private void hookMediaBoxLayout() throws Throwable {
        final int[] layoutId = {0};
        XpHooks.After collapse = new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) {
                if (!(param.getResult() instanceof android.view.View)
                        || param.args == null || param.args.length == 0
                        || !(param.args[0] instanceof Integer)) {
                    return;
                }
                android.view.View root = (android.view.View) param.getResult();
                if (layoutId[0] == 0) {
                    try {
                        layoutId[0] = root.getResources().getIdentifier(
                                MEDIA_BOX_LAYOUT, "layout", "com.zing.zalo");
                    } catch (Throwable ignored) {
                        return;
                    }
                }
                if (layoutId[0] == 0
                        || ((Integer) param.args[0]).intValue() != layoutId[0]) {
                    return;
                }
                root.setVisibility(android.view.View.GONE);
                android.view.ViewGroup.LayoutParams params = root.getLayoutParams();
                if (params != null) {
                    params.height = 0;
                    root.setLayoutParams(params);
                }
                SelfCheckRegistry.incrementHit(FEATURE_MEDIA_BOX,
                        "LayoutInflater#inflate(" + MEDIA_BOX_LAYOUT + ")", "row collapsed");
            }
        };
        int hooked = XpHooks.hookAllMethods(FEATURE_MEDIA_BOX,
                android.view.LayoutInflater.class, "inflate", collapse).size();
        if (hooked == 0) {
            SelfCheckRegistry.markStale(FEATURE_MEDIA_BOX,
                    "LayoutInflater#inflate(" + MEDIA_BOX_LAYOUT + ")",
                    "no inflate overloads found");
            return;
        }
        SelfCheckRegistry.markInstalled(FEATURE_MEDIA_BOX,
                "LayoutInflater#inflate(" + MEDIA_BOX_LAYOUT + ")", hooked);
    }

    /**
     * Adapter discovery without mapping: MessagesView is stable, so its
     * constructors are hooked and the first RecyclerView.Adapter-typed field
     * found names the inbox adapter. The setAdapter backup covers adapters
     * attached before field initialization. Install is idempotent per class.
     */
    private void hookAdapterDiscovery() throws Throwable {
        Class<?> messagesView = XpReflect.findClass(MESSAGE_VIEW_CLASS, classLoader);
        XpHooks.After discover = new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) {
                tryDiscoverAdapter(param.thisObject);
            }
        };
        XpHooks.hookAllConstructors(FEATURE_FILTER, messagesView, null, discover);
        Class<?> recyclerViewClass = XpReflect.findClass(
                "androidx.recyclerview.widget.RecyclerView", classLoader);
        XpHooks.hookAllMethods(FEATURE_FILTER, recyclerViewClass, "setAdapter",
                new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) {
                Object adapter = param.args.length > 0 ? param.args[0] : null;
                if (adapter == null || param.thisObject == null) {
                    return;
                }
                String adapterName = adapter.getClass().getName();
                if (loggedAdapterNames.add(adapterName)) {
                    log("setAdapter observed: " + adapterName
                            + " underMessages=" + underMessagesView(param.thisObject)
                            + " inboxCandidate=" + isInboxAdapterCandidate(adapter.getClass()));
                }
                if (underMessagesView(param.thisObject)
                        || isInboxAdapterCandidate(adapter.getClass())) {
                    Object ancestorView = findMessagesView(param.thisObject);
                    if (ancestorView != null) {
                        liveMessagesView = ancestorView;
                    } else {
                        captureMessagesView(adapter);
                    }
                    // Hook the inbox adapter, then inject the bar immediately instead of
                    // waiting for another setAdapter: the first attach is often the only one.
                    boolean installed = installListHooks(adapter.getClass());
                    if (installed && categoriesConfigured
                            && param.thisObject instanceof android.view.View) {
                        final android.view.View recyclerView =
                                (android.view.View) param.thisObject;
                        recyclerView.post(new Runnable() {
                            @Override
                            public void run() {
                                injectFilterBar(recyclerView);
                            }
                        });
                    }
                }
            }
        });
    }

    private void tryDiscoverAdapter(Object messagesView) {
        if (messagesView == null) {
            return;
        }
        if (!discoveredAdapterClass.isEmpty()) {
            installListHooksForName(discoveredAdapterClass);
            return;
        }
        for (Class<?> current = messagesView.getClass(); current != null
                && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (isRecyclerAdapterType(field.getType()) && installListHooks(field.getType())) {
                    return;
                }
            }
        }
    }

    private boolean isRecyclerAdapterType(Class<?> type) {
        if (type == null) {
            return false;
        }
        try {
            Class<?> base = Class.forName(
                    "androidx.recyclerview.widget.RecyclerView$Adapter", false, classLoader);
            return base.isAssignableFrom(type);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Structural inbox-adapter test that survives renames: direct Conversation use,
     * or at least two MessagesView fields (the stable host view the adapter binds).
     */
    private boolean isInboxAdapterCandidate(Class<?> adapterClass) {
        if (holdsConversation(adapterClass)) {
            return true;
        }
        try {
            Class<?> messagesView = XpReflect.findClass(MESSAGE_VIEW_CLASS, classLoader);
            int fields = 0;
            for (Class<?> current = adapterClass; current != null && current != Object.class;
                    current = current.getSuperclass()) {
                for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (messagesView.isAssignableFrom(field.getType())
                            && ++fields >= 2) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** True when the adapter mentions Conversation in fields or method signatures. */
    private boolean holdsConversation(Class<?> adapterClass) {        if (adapterClass == null) {
            return false;
        }
        try {
            Class<?> conversation = XpReflect.findClass(
                    "com.zing.zalo.data.chat.model.tabmessage.Conversation", classLoader);
            for (Class<?> current = adapterClass; current != null && current != Object.class;
                    current = current.getSuperclass()) {
                for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (conversation.isAssignableFrom(field.getType())) {
                        return true;
                    }
                }
                for (java.lang.reflect.Method method : current.getDeclaredMethods()) {
                    if (conversation.isAssignableFrom(method.getReturnType())) {
                        return true;
                    }
                    for (Class<?> param : method.getParameterTypes()) {
                        if (conversation.isAssignableFrom(param)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean underMessagesView(Object view) {
        return findMessagesView(view) != null;
    }

    /**
     * The MessagesView ancestor of a view, or null. The adapter-field route goes stale
     * whenever Zalo attaches an adapter that carries no view reference, which made the
     * Strangers chip appear and vanish by navigation; the attached hierarchy is current
     * by construction.
     */
    private Object findMessagesView(Object view) {
        Object node = view;
        int depth = 0;
        while (node instanceof android.view.View && depth++ < 24) {
            if (node.getClass().getName().equals(MESSAGE_VIEW_CLASS)) {
                return node;
            }
            android.view.ViewParent parent = ((android.view.View) node).getParent();
            node = parent;
        }
        return null;
    }

    private void installListHooksForName(String className) {
        if (className == null || className.isEmpty()) {
            return;
        }
        try {
            installListHooks(XpReflect.findClass(className, classLoader));
        } catch (Throwable throwable) {
            logSymbolFailure("class", className, throwable);
        }
    }

    private synchronized boolean installListHooks(Class<?> adapterClass) {
        if (adapterClass == null) {
            return false;
        }
        if (!hookedAdapterClasses.add(adapterClass.getName())) {
            return true;
        }
        try {
            hookMessageListFiltering(adapterClass);
            if (discoveredAdapterClass == null || discoveredAdapterClass.isEmpty()) {
                discoveredAdapterClass = adapterClass.getName();
            }
            return true;
        } catch (Throwable throwable) {
            hookedAdapterClasses.remove(adapterClass.getName());
            logSymbolFailure("class", adapterClass.getName(), throwable);
            return false;
        }
    }

    private void hookMessageListFiltering(Class<?> adapterClass) throws Throwable {
        int hooked = 0;
        for (Method method : adapterClass.getDeclaredMethods()) {
            if (!InboxListUpdate.accepts(method)) {
                continue;
            }
            XpHooks.hookMethod(FEATURE_FILTER, method, new XpHooks.Before() {
                @Override
                public void before(XpHooks.HookParam param) {
                    if (param.args.length < 1 || !(param.args[0] instanceof List)) {
                        return;
                    }
                    // Snapshot before filtering; replay must use this exact invocation.
                    lastInboxListUpdate = new InboxListUpdate(method, param.thisObject, param.args);
                    param.args[0] = filterInboxItems((List<?>) param.args[0]);
                }
            });
            hooked++;
        }
        log("Adapter list hooks installed -> " + hooked + " methods on " + messageAdapterClass());
        if (hooked > 0) {
            SelfCheckRegistry.markInstalled(FEATURE_FILTER, messageAdapterClass(), hooked);
        } else {
            SelfCheckRegistry.markStale(FEATURE_FILTER, messageAdapterClass(), "no List setter methods");
            if (hideMediaEnabled) {
                SelfCheckRegistry.markStale(FEATURE_MEDIA_BOX, messageAdapterClass(),
                        "no List setter methods");
            }
            throw new NoSuchMethodException(adapterClass.getName() + " has no List setter methods");
        }
    }

    private List<Object> filterInboxItems(List<?> original) {
        try {
            return filterInboxItemsOrThrow(original);
        } catch (Throwable throwable) {
            logSymbolFailure("filter", messageAdapterClass(), throwable);
            return new ArrayList<>(original);
        }
    }

    private List<Object> filterInboxItemsOrThrow(List<?> original) {
        boolean hideMedia = effectiveHideMediaBox();
        String category = sessionSelectedCategory;
        boolean applyCategory = !CATEGORY_FOCUSED.equals(category);


        if (!hideMedia && !applyCategory) {
            return new ArrayList<>(original);
        }

        List<Object> filtered = new ArrayList<>(original.size());
        int classifiable = 0;
        for (Object item : original) {
            if (applyCategory) {
                if (conversationOf(item) != null) {
                    classifiable++;
                }
                if (!belongsToCategory(item, category)) {
                    continue;
                }
            }
            filtered.add(item);
        }
        if (applyCategory && classifiable == 0 && !original.isEmpty()) {
            logSymbolFailure("filter", messageAdapterClass(),
                    new IllegalStateException("no classifiable items; showing all"));
            return new ArrayList<>(original);
        }
        if (applyCategory) {
            log("Filter category=" + category + " in=" + original.size() + " out=" + filtered.size());
            SelfCheckRegistry.markSuppressed(FEATURE_FILTER, messageAdapterClass(),
                    "category=" + category + " in=" + original.size() + " out=" + filtered.size()
                            + " unknown=" + countUnknownItems(original));
        } else if (hideMedia) {
            SelfCheckRegistry.markSuppressed(FEATURE_FILTER, messageAdapterClass(),
                    "media-only in=" + original.size() + " out=" + filtered.size());
        }
        return filtered;
    }

    // ---------------------------------------------------------------- module-owned filter bar

    private static final String FILTER_BAR_TAG = "zp_filter_bar";
    private static final String CHIP_ALL = "all";
    private static final int FILTER_BAR_ELEVATION_DP = 8;
    private final java.util.List<android.widget.TextView> filterChips = new ArrayList<>();

    /**
     * Module-owned UI surface: a horizontal chip bar inserted above the inbox list. Unlike the
     * popover, this is our own view hierarchy, so click handling is reliable and doesn't depend on
     * Zalo's obfuscated popover dispatch. Anchored via the stable RecyclerView.setAdapter +
     * the inbox adapter class; placed in the RecyclerView's parent.
     */
    private void hookInboxFilterBar() throws Throwable {
        Class<?> recyclerViewClass = XpReflect.findClass(
                "androidx.recyclerview.widget.RecyclerView", classLoader);
        XpHooks.hookAllMethods(FEATURE_FILTER_BAR, recyclerViewClass, "setAdapter",
                new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) {
                if (!shouldShowInboxLab()) {
                    return;
                }
                Object adapter = param.args.length > 0 ? param.args[0] : null;
                if (adapter == null || !messageAdapterClass().equals(adapter.getClass().getName())) {
                    return;
                }
                captureMessagesView(adapter);
                if (param.thisObject instanceof android.view.View) {
                    final android.view.View rv = (android.view.View) param.thisObject;
                    rv.post(new Runnable() {
                        @Override
                        public void run() {
                            injectFilterBar(rv);
                        }
                    });
                }
            }
        });
        log("Inbox filter bar hooked on RecyclerView.setAdapter");
    }

    private boolean hierarchyDumped;

    private void injectFilterBar(android.view.View recyclerView) {
        try {
            if (HookConfig.isDebugEnabled() && !hierarchyDumped) {
                hierarchyDumped = true;
                dumpAncestry(recyclerView);
            }
            // Walk up to the nearest vertical LinearLayout and insert the bar just above the
            // subtree containing the list, so it stacks above the list instead of hiding behind it.
            android.view.View child = recyclerView;
            android.view.ViewParent par = child.getParent();
            android.view.ViewGroup verticalParent = null;
            while (par instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) par;
                if (vg instanceof android.widget.LinearLayout
                        && ((android.widget.LinearLayout) vg).getOrientation()
                        == android.widget.LinearLayout.VERTICAL) {
                    verticalParent = vg;
                    break;
                }
                child = vg;
                par = vg.getParent();
            }
            if (verticalParent == null) {
                injectFilterBarOverlay(recyclerView);
                return;
            }
            if (verticalParent.findViewWithTag(FILTER_BAR_TAG) != null) {
                return; // already present
            }
            android.content.Context ctx = recyclerView.getContext();
            android.view.View bar = buildFilterBar(ctx);
            bar.setTag(FILTER_BAR_TAG);
            int idx = verticalParent.indexOfChild(child);
            verticalParent.addView(bar, idx);
            SelfCheckRegistry.markSuppressed(FEATURE_FILTER_BAR, "module chip bar",
                    "parent=" + verticalParent.getClass().getName());
            log("Filter bar injected above " + child.getClass().getSimpleName()
                    + " in " + verticalParent.getClass().getName());
        } catch (Throwable t) {
            SelfCheckRegistry.markFailed(FEATURE_FILTER_BAR, "module chip bar", t);
            log("injectFilterBar failed: " + t.getClass().getSimpleName());
        }
    }

    private void injectFilterBarOverlay(android.view.View recyclerView) {
        android.view.ViewGroup parent = nearestUsableParent(recyclerView);
        if (parent == null) {
            log("filter bar: no usable parent found");
            return;
        }
        if (parent.findViewWithTag(FILTER_BAR_TAG) != null) {
            return;
        }
        android.content.Context ctx = recyclerView.getContext();
        android.view.View bar = buildFilterBar(ctx);
        bar.setTag(FILTER_BAR_TAG);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            bar.setElevation(dp(ctx, FILTER_BAR_ELEVATION_DP));
        }
        android.view.ViewGroup.LayoutParams params = overlayParams(parent, recyclerView);
        parent.addView(bar, params);
        SelfCheckRegistry.markSuppressed(FEATURE_FILTER_BAR, "module chip bar overlay",
                "parent=" + parent.getClass().getName());
        log("Filter bar overlay injected in " + parent.getClass().getName());
    }

    private android.view.ViewGroup nearestUsableParent(android.view.View view) {
        android.view.ViewParent parent = view.getParent();
        while (parent instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) parent;
            String name = group.getClass().getName();
            if (group instanceof android.widget.FrameLayout
                    || group instanceof android.widget.RelativeLayout
                    || name.contains("ConstraintLayout")
                    || group.getChildCount() > 0) {
                return group;
            }
            parent = group.getParent();
        }
        android.view.View root = view.getRootView();
        return root instanceof android.view.ViewGroup ? (android.view.ViewGroup) root : null;
    }

    private android.view.ViewGroup.LayoutParams overlayParams(android.view.ViewGroup parent, android.view.View recyclerView) {
        int height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        if (parent instanceof android.widget.FrameLayout) {
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, height);
            lp.gravity = android.view.Gravity.TOP;
            return lp;
        }
        if (parent instanceof android.widget.RelativeLayout) {
            android.widget.RelativeLayout.LayoutParams lp = new android.widget.RelativeLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, height);
            lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
            return lp;
        }
        return new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    /** TEMP(verify): log the RecyclerView's ancestor chain to pick a good anchor for the bar. */
    private void dumpAncestry(android.view.View view) {
        StringBuilder sb = new StringBuilder("ancestry:");
        android.view.ViewParent v = view.getParent();
        int level = 0;
        while (v instanceof android.view.ViewGroup && level < 6) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            String orient = "";
            if (vg instanceof android.widget.LinearLayout) {
                orient = ((android.widget.LinearLayout) vg).getOrientation()
                        == android.widget.LinearLayout.VERTICAL ? "(V)" : "(H)";
            }
            sb.append("\n  L").append(level).append(' ').append(vg.getClass().getName())
                    .append(orient).append(" children=").append(vg.getChildCount())
                    .append(" h=").append(vg.getHeight());
            v = vg.getParent();
            level++;
        }
        log(sb.toString());
    }

    private android.view.View buildFilterBar(android.content.Context ctx) {
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(0xFF121212);
        scroll.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        int pad = dp(ctx, 8);
        row.setPadding(pad, pad, pad, pad);
        scroll.addView(row);

        filterChips.clear();
        addAllChip(ctx, row);
        addCategoryChip(ctx, row, "Chats", CATEGORY_NORMAL);
        if (HookConfig.isEnabled(Tweaks.KEY_CATEGORY_GROUPS)) {
            addCategoryChip(ctx, row, "Groups", CATEGORY_GROUPS);
        }
        if (categoriesEnabled && HookConfig.isEnabled(Tweaks.KEY_CATEGORY_OA)) {
            addCategoryChip(ctx, row, "OA", CATEGORY_OA);
        }
        if (strangerRoute != null && liveMessagesView != null
                && HookConfig.isEnabled(Tweaks.KEY_CATEGORY_STRANGERS)) {
            addCategoryChip(ctx, row, "Strangers", CATEGORY_STRANGERS);
        }
        restyleChips();
        if (defaultStrangersPending) {
            scroll.post(new Runnable() {
                @Override public void run() {
                    if (defaultStrangersPending) openStrangers();
                }
            });
        }
        return scroll;
    }

    static String configuredDefaultCategory(
            int value, boolean groupsEnabled, boolean oaEnabled, boolean strangersEnabled) {
        switch (value) {
            case 1:
                return CATEGORY_NORMAL;
            case 2:
                return groupsEnabled ? CATEGORY_GROUPS : CATEGORY_FOCUSED;
            case 3:
                return oaEnabled ? CATEGORY_OA : CATEGORY_FOCUSED;
            case 4:
                return strangersEnabled ? CATEGORY_STRANGERS : CATEGORY_FOCUSED;
            case 0:
            default:
                return CATEGORY_FOCUSED;
        }
    }

    private void addAllChip(android.content.Context ctx, android.widget.LinearLayout row) {
        android.widget.TextView chip = buildChip(ctx, "All", CHIP_ALL);
        chip.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                showAllInbox();
            }
        });
        filterChips.add(chip);
        row.addView(chip);
    }

    private void addCategoryChip(android.content.Context ctx, android.widget.LinearLayout row,
                                 String label, final String category) {
        android.widget.TextView chip = buildChip(ctx, label, category);
        chip.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (CATEGORY_STRANGERS.equals(category)) {
                    openStrangers();
                    return;
                }
                if (CATEGORY_OA.equals(category) && !categoriesEnabled) {
                    showAllInbox();
                    return;
                }
                sessionSelectedCategory = category;
                log("Filter bar -> " + category);
                SelfCheckRegistry.markSuppressed(FEATURE_FILTER_BAR, "chip:" + category, "selected");
                restyleChips();
                refreshInbox();
            }
        });
        filterChips.add(chip);
        row.addView(chip);
    }

    private android.widget.TextView buildChip(android.content.Context ctx, String label, String tag) {
        android.widget.TextView chip = new android.widget.TextView(ctx);
        chip.setText(label);
        chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        int hp = dp(ctx, 14);
        int vp = dp(ctx, 7);
        chip.setPadding(hp, vp, hp, vp);
        chip.setTag(tag);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(ctx, 8);
        chip.setLayoutParams(lp);
        return chip;
    }

    private void showAllInbox() {
        sessionSelectedCategory = CATEGORY_FOCUSED;
        log("Filter bar -> all (category reset)");
        SelfCheckRegistry.markSuppressed(FEATURE_FILTER_BAR, "chip:all", "selected");
        restyleChips();
        refreshInbox();
    }

    private void restyleChips() {
        for (android.widget.TextView chip : filterChips) {
            String tag = String.valueOf(chip.getTag());
            boolean selected = isChipSelected(tag);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(chip.getContext(), 16));
            bg.setColor(selected ? 0xFF0068FF : 0xFF2A2A2A);
            chip.setBackground(bg);
            chip.setTextColor(selected ? 0xFFFFFFFF : 0xFFBBBBBB);
        }
    }

    private boolean isChipSelected(String tag) {
        if (CHIP_ALL.equals(tag)) {
            return CATEGORY_FOCUSED.equals(sessionSelectedCategory);
        }
        return tag.equals(sessionSelectedCategory);
    }

    private boolean shouldShowInboxLab() {
        // UI visibility follows the user's setting, not static compatibility; the
        // setAdapter matcher already restricts injection to the discovered inbox
        // adapter, and filtering degrades gracefully without the category field.
        return categoriesConfigured;
    }

    private boolean effectiveHideMediaBox() {
        return hideMediaEnabled;
    }

    private int dp(android.content.Context ctx, float value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- classification

    private boolean belongsToCategory(Object item, String category) {
        switch (category) {
            case CATEGORY_NORMAL:
                return isNormalChatItem(item);
            case CATEGORY_GROUPS:
                return isGroupItem(item);
            case CATEGORY_OA:
                return isOaItem(item);
            case CATEGORY_MEDIA:
                return false;
            case CATEGORY_STRANGERS:
                // Strangers opens Zalo's native screen from the chip and is never a
                // list filter (the box-row letter is retired); nothing matches here.
                return false;
            case CATEGORY_FOCUSED:
            default:
                return true;
        }
    }

    private boolean isNormalChatItem(Object item) {
        return isNormalItem(item)
                && !isGroupItem(item)
                && !isOaItem(item);
    }

    private boolean isNormalItem(Object item) {
        if (item == null) {
            return false;
        }
        // Structural route only: a Conversation-typed instance field decides.
        // The pinned row-class list is retired; a stale letter rejects renamed
        // rows instead of classifying them.
        return declaresConversationField(item.getClass());
    }

    /** Structural normal-item check: a Conversation-typed instance field. */
    private static final Map<String, Boolean> conversationFieldPresence =
            new ConcurrentHashMap<>();

    private static boolean declaresConversationField(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        Boolean cached = conversationFieldPresence.get(clazz.getName());
        if (cached != null) {
            return cached;
        }
        boolean found = conversationFieldName(clazz) != null;
        conversationFieldPresence.put(clazz.getName(), found);
        return found;
    }

    private static String conversationFieldName(Class<?> clazz) {
        String found = null;
        for (Class<?> current = clazz; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if ("com.zing.zalo.data.chat.model.tabmessage.Conversation"
                        .equals(field.getType().getName())) {
                    if (found != null) {
                        // Ambiguous: more than one Conversation field. Fail closed rather
                        // than guess which one carries the row's conversation (F5).
                        return null;
                    }
                    found = field.getName();
                }
            }
        }
        return found;
    }

    private String readUid(Object item) {
        if (preferConversationUid) {
            // Only the resolved conversation UID is trustworthy here. The mapped row-uid
            // method belongs to a neighbouring profile on an unmapped artifact, where the
            // obfuscated letter now points at an unrelated predicate.
            return conversationUid(conversationOf(item));
        }
        try {
            return String.valueOf(XpReflect.callMethod(item, rowUidMethod()));
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Native category int from the stable Conversation object; field names come from schema. */
    private int categoryOf(Object item) {
        if (!isNormalItem(item)) {
            return -1;
        }
        try {
            // Same validated accessor the rest of the route uses, so a renamed row class
            // reaches the structural Conversation field instead of failing on the
            // stale mapped one (F5).
            Object conversation = conversationOf(item);
            if (conversation == null) {
                return -1;
            }
            String categoryField = categoryIntField();
            if (categoryField == null || categoryField.isEmpty()) {
                return -1;
            }
            Object value = XpReflect.getObjectField(conversation, categoryField);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable throwable) {
            logSymbolFailure("field-chain", classNameOf(item) + "#" + conversationField()
                    + " -> " + categoryIntField(), throwable);
            return -1;
        }
    }

    private int countUnknownItems(List<?> items) {
        int unknown = 0;
        boolean debug = HookConfig.isDebugEnabled();
        int logged = 0;
        for (Object item : items) {
            if (item == null) {
                unknown++;
                continue;
            }
            if (isNormalItem(item)) {
                continue;
            }
            // Class names only, never row content: lets the unknown-row count in
            // the filter detail be traced to a concrete row type (T3).
            if (debug && logged < 5) {
                log("Unknown inbox row class -> " + item.getClass().getName());
                logged++;
            }
            unknown++;
        }
        return unknown;
    }

    /**
     * Group detection: Zalo group conversation uids carry a literal "group_" prefix (stable across
     * versions). A deleted group keeps that prefix and is still a group row, so the
     * deleted-group store is consulted only as evidence (its own self-check row) and never
     * decides the category; treating it as an exclusion moved deleted groups into Chats. The
     * retired schema group-flag letter mis-decided on neighbouring releases
     * (it classified OA rows as groups on 26.09.01), so the prefix is the only decider.
     */
    private boolean isGroupItem(Object item) {
        if (!isNormalItem(item)) {
            return false;
        }
        String uid = readUid(item);
        if (uid == null) {
            return false;
        }
        if (isDeletedGroupUid(uid)) {
            SelfCheckRegistry.markSuppressed(FEATURE_DELETED_GROUP,
                    deletedGroupRepositoryClass() + "#" + deletedGroupCheckMethod(),
                    "deleted group classified as group");
        }
        try {
            return uid.startsWith("group_");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isDeletedGroupUid(String uid) {
        if (uid == null || !uid.startsWith("group_") || deletedGroupCheckUnavailable) {
            return false;
        }
        try {
            Object repository = deletedGroupRepository;
            if (repository == null) {
                Class<?> repositoryClass = XpReflect.findClass(deletedGroupRepositoryClass(), classLoader);
                repository = XpReflect.getStaticObjectField(repositoryClass, deletedGroupRepositoryField());
                deletedGroupRepository = repository;
            }
            boolean deleted = Boolean.TRUE.equals(XpReflect.callMethod(repository, deletedGroupCheckMethod(), uid));
            if (!deletedGroupCheckInstalled) {
                deletedGroupCheckInstalled = true;
                SelfCheckRegistry.markInstalled(FEATURE_DELETED_GROUP,
                        deletedGroupRepositoryClass() + "#" + deletedGroupCheckMethod(), 1);
            }
            if (deleted) {
                SelfCheckRegistry.markSuppressed(FEATURE_DELETED_GROUP, deletedGroupRepositoryClass() + "#" + deletedGroupCheckMethod(), uid);
            }
            return deleted;
        } catch (Throwable throwable) {
            deletedGroupCheckUnavailable = true;
            if (throwable instanceof NoSuchFieldError || throwable instanceof ClassNotFoundException) {
                SelfCheckRegistry.markStale(FEATURE_DELETED_GROUP, deletedGroupRepositoryClass() + "#" + deletedGroupCheckMethod(),
                        throwable.getClass().getSimpleName() + " " + throwable.getMessage());
            } else {
                    SelfCheckRegistry.markFailed(FEATURE_DELETED_GROUP, deletedGroupRepositoryClass() + "#" + deletedGroupCheckMethod(), throwable);
            }
            if (HookConfig.isDebugEnabled()) {
                log("Deleted group check failed-soft: "
                        + throwable.getClass().getSimpleName() + " " + throwable.getMessage());
            }
            return false;
        }
    }

    /**
     * OA detection: Zalo's row-local topOut marker plus the native Conversation
     * category. Live taps on orange-marked rows showed topOut 1 and 2, while the
     * native category can remain 1 after Zalo resolves final position. The
     * friend-manager follow fallback is retired (T2c): its letter is missing on
     * current releases and topOut/category already discriminate on device.
     */
    private boolean isOaItem(Object item) {
        Object conversation = conversationOf(item);
        if (conversation == null) {
            return false;
        }
        String uid = conversationUid(conversation);
        if (uid == null || uid.length() == 0 || uid.startsWith("group_")) {
            return false;
        }
        int topOut = topOutOf(conversation);
        return topOut == 1 || topOut == 2 || categoryOf(item) == CAT_OA;
    }

    private Object conversationOf(Object item) {
        if (!isNormalItem(item)) {
            return null;
        }
        String mapped = conversationField();
        if (mapped != null && !mapped.isEmpty()) {
            try {
                Object value = XpReflect.getObjectField(item, mapped);
                if (value != null) {
                    return value;
                }
            } catch (Throwable throwable) {
                logSymbolFailure("field", classNameOf(item) + "#" + mapped, throwable);
            }
            // Mapped field missing on a renamed row: fall through to the structural
            // accessor instead of giving up (F5).
        }
        try {
            String runtime = conversationFieldName(item.getClass());
            if (runtime == null) {
                return null;
            }
            return XpReflect.getObjectField(item, runtime);
        } catch (Throwable throwable) {
            logSymbolFailure("field", classNameOf(item) + "#conversation(runtime)", throwable);
            return null;
        }
    }

    private String conversationUid(Object conversation) {
        return stringField(conversation, conversationUidField());
    }

    private void prepareStrangerRoute() {
        try {
            Class<?> view = XpReflect.findClass(MESSAGE_VIEW_CLASS, classLoader);
            strangerDestination = XpReflect.findClass(
                    "com.zing.zalo.ui.zviews.StrangerMessagesView", classLoader);
            strangerRoute = InboxNativeRoute.find(view, android.os.Bundle.class,
                    "com.zing.zalo.zview.");
        } catch (Throwable ignored) {
            strangerRoute = null;
        }
    }

    private void captureMessagesView(Object adapter) {
        // Never clears a previously resolved view: a later attach without a view
        // reference must not hide the chip, and the ancestry route above stays primary.
        Object found = null;
        try {
            for (Class<?> type = adapter.getClass(); type != null && type != Object.class;
                    type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                            || !MESSAGE_VIEW_CLASS.equals(field.getType().getName())) continue;
                    field.setAccessible(true);
                    Object value = field.get(adapter);
                    if (value == null) continue;
                    if (found != null && found != value) return;
                    found = value;
                }
            }
            if (found != null) liveMessagesView = found;
        } catch (Throwable ignored) {
        }
    }

    private void openStrangers() {
        try {
            if (strangerRoute == null) {
                log("Strangers tap ignored: no native route resolved");
                return;
            }
            if (liveMessagesView == null) {
                log("Strangers tap ignored: no Messages view retained");
                return;
            }
            if (strangerRoute.open(liveMessagesView, strangerDestination)) {
                defaultStrangersPending = false;
                SelfCheckRegistry.markSuppressed(FEATURE_FILTER_BAR, "native Strangers screen", "opened");
            } else {
                log("Strangers open returned false: host or manager null at tap time");
            }
        } catch (Throwable error) {
            logSymbolFailure("navigation", "StrangerMessagesView", error);
        }
    }

    private int topOutOf(Object conversation) {
        try {
            Object topOutInfo = XpReflect.getObjectField(conversation, topOutField());
            if (topOutInfo == null) {
                return -1;
            }
            Object value = XpReflect.getObjectField(topOutInfo, topOutValueField());
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable throwable) {
            logSymbolFailure("field-chain", classNameOf(conversation) + "#" + topOutField()
                    + " -> " + topOutValueField(), throwable);
            return -1;
        }
    }

    private String stringField(Object target, String fieldName) {
        try {
            Object value = XpReflect.getObjectField(target, fieldName);
            return value instanceof String ? (String) value : null;
        } catch (Throwable throwable) {
            logSymbolFailure("field", classNameOf(target) + "#" + fieldName, throwable);
            return null;
        }
    }

    private void logSymbolFailure(String kind, String symbol, Throwable throwable) {
        String key = kind + ":" + symbol;
        if (HookConfig.isDebugEnabled() && symbolFailuresLogged.add(key)) {
            log("Symbol resolution miss " + kind + "=" + symbol
                    + " exception=" + throwable.getClass().getSimpleName());
        }
    }

    private static String classNameOf(Object object) {
        return object == null ? "null" : object.getClass().getName();
    }

    /** Re-run the adapter list-setter from the cached unfiltered list so the new category applies. */
    private void refreshInbox() {
        InboxListUpdate update = lastInboxListUpdate;
        if (update == null) {
            log("refreshInbox skipped (adapter/setter/source missing)");
            return;
        }
        try {
            update.method.setAccessible(true);
            // Origin invoker: the filtered list goes straight to the original setter
            // without re-firing this feature's own hook, so no reentrancy guard is needed.
            XpHooks.invokeOriginal(update.method, update.adapter,
                    update.withItems(filterInboxItems(new ArrayList<>(update.source))));
        } catch (Throwable throwable) {
            log("refreshInbox failed: " + throwable.getClass().getSimpleName());
        }
    }

    private static String messageAdapterClass() {
        // Prefer the runtime-discovered adapter: on a renamed release the pinned
        // schema name is stale, and the setAdapter matcher must use the live class.
        String discovered = discoveredAdapterClass == null ? "" : discoveredAdapterClass;
        if (!discovered.isEmpty()) {
            return discovered;
        }
        String schema = schemaString("symbols.inbox.message_adapter_class", "");
        return schema != null ? schema : "";
    }

    private static String conversationField() {
        return schemaString("symbols.inbox.conversation_field", "");
    }

    private static String categoryIntField() {
        return schemaString("symbols.inbox.category_int_field", "");
    }

    private static String rowUidMethod() {
        return schemaString("symbols.inbox.row_uid_method", "");
    }

    private static String deletedGroupRepositoryClass() {
        return schemaString("symbols.inbox.deleted_group_repository_class", "");
    }

    private static String deletedGroupRepositoryField() {
        return schemaString("symbols.inbox.deleted_group_repository_field", "");
    }

    private static String deletedGroupCheckMethod() {
        return schemaString("symbols.inbox.deleted_group_check_method", "");
    }

    private static String conversationUidField() {
        return schemaString("symbols.inbox.conversation_uid_field", "");
    }

    private static String topOutField() {
        return schemaString("symbols.inbox.top_out_field", "");
    }

    private static String topOutValueField() {
        return schemaString("symbols.inbox.top_out_value_field", "");
    }

    private static String schemaString(String path, String fallback) {
        SymbolSchema.ResolvedString resolved = SymbolSchema.stringForHooks(
                HookConfig.resolveModuleContextForHooks(), path, fallback);
        recordSchemaSource(path, resolved);
        return resolved.value;
    }

    private static void recordSchemaSource(String path, SymbolSchema.ResolvedString resolved) {
        if (resolved.fallback) {
            schemaFallbackPaths.add(path);
        } else {
            schemaFallbackPaths.remove(path);
        }
        String key = resolved.source + ":" + path;
        if (!schemaSourceChecks.add(key) && !resolved.fallback) {
            return;
        }
        boolean usesFallback = !schemaFallbackPaths.isEmpty();
        String status = usesFallback ? "stale" : "ok";
        String target = "source=" + (usesFallback ? "java_fallback" : resolved.source);
        String detail = path + "=" + shortValue(resolved.value);
        String error = usesFallback ? "Java fallback used for inbox symbols: " + schemaFallbackPaths : "";
        SelfCheckRegistry.markStatus("inbox.schema", status, target, detail, error);
    }

    private static String shortValue(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 80 ? value.substring(0, 80) : value;
    }
}
