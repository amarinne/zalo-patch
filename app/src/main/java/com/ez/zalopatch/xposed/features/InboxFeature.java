package com.ez.zalopatch.xposed.features;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.HookReflect;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

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
    private volatile Object lastInboxAdapter;
    private volatile List<Object> lastUnfilteredItems;
    private volatile boolean isOurReentrantCall;
    private volatile Object deletedGroupRepository;
    private volatile boolean deletedGroupCheckUnavailable;
    private volatile boolean deletedGroupCheckInstalled;
    private volatile Object friendManager;
    private final boolean mediaCompatible;
    private final String mediaCompatibilityError;
    private final boolean categoriesCompatible;
    private final String categoryCompatibilityError;
    private boolean hideMediaEnabled;
    private boolean categoriesEnabled;
    private final Map<String, Boolean> oaFollowCache = new ConcurrentHashMap<>();
    private static final java.util.Set<String> schemaSourceChecks = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final java.util.Set<String> schemaFallbackPaths = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final java.util.Set<String> symbolFailuresLogged = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    // Debug diagnostics gate on debug.zalopatch at use time. Never gate permanent feature hooks on
    // diagnostics state.

    public InboxFeature(ClassLoader classLoader, boolean mediaCompatible,
                        String mediaCompatibilityError, boolean categoriesCompatible,
                        String categoryCompatibilityError) {
        super(classLoader);
        this.mediaCompatible = mediaCompatible;
        this.mediaCompatibilityError = mediaCompatibilityError;
        this.categoriesCompatible = categoriesCompatible;
        this.categoryCompatibilityError = categoryCompatibilityError;
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
        sessionSelectedCategory = configuredDefaultCategory(
                categoriesEnabled
                        ? HookConfig.getLevel(Tweaks.KEY_DEFAULT_INBOX_FILTER) : 0,
                HookConfig.isEnabled(Tweaks.KEY_CATEGORY_GROUPS),
                HookConfig.isEnabled(Tweaks.KEY_CATEGORY_OA),
                HookConfig.isEnabled(Tweaks.KEY_CATEGORY_STRANGERS));
        if (!configuredHideMedia && !configuredCategories) {
            SelfCheckRegistry.markDisabled(FEATURE_FILTER, messageAdapterClass());
        }
        if (!configuredHideMedia) {
            SelfCheckRegistry.markDisabled(FEATURE_MEDIA_BOX, mediaBoxItemClass());
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
        boolean debugEnabled = HookConfig.isDebugEnabled();
        if (!debugEnabled) {
            SelfCheckRegistry.markDisabled(FEATURE_TAP_DIAGNOSTICS, clickHandlerClass());
        }

        if (hideMediaEnabled || categoriesEnabled) {
            runGuarded("Message list filtering", FEATURE_FILTER, messageAdapterClass(),
                    this::hookMessageListFiltering);
        } else if (configuredHideMedia || configuredCategories) {
            String reason = !mediaCompatible ? mediaCompatibilityError : categoryCompatibilityError;
            SelfCheckRegistry.markStale(FEATURE_FILTER, "structural preflight", reason);
        }
        if (categoriesEnabled) {
            runGuarded("Inbox filter bar", FEATURE_FILTER_BAR, "RecyclerView.setAdapter",
                    this::hookInboxFilterBar);
        }
        if (debugEnabled) {
            runGuarded("Inbox tap diagnostics", FEATURE_TAP_DIAGNOSTICS, clickHandlerClass() + "#" + clickMethod(), this::hookInboxTapDiagnostics);
        }
    }

    // ---------------------------------------------------------------- list filtering

    private volatile Method listSetterMethod;

    private void hookMessageListFiltering() throws Throwable {
        Class<?> adapterClass = XposedHelpers.findClass(messageAdapterClass(), classLoader);
        int hooked = 0;
        for (Method method : adapterClass.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length < 1 || !List.class.isAssignableFrom(params[0])) {
                continue;
            }
            listSetterMethod = method;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 1 || !(param.args[0] instanceof List)) {
                        return;
                    }
                    lastInboxAdapter = param.thisObject;
                    List<?> incoming = (List<?>) param.args[0];
                    // Cache the unfiltered list so a later category switch can re-filter from source.
                    if (!isOurReentrantCall) {
                        lastUnfilteredItems = new ArrayList<>(incoming);
                    }
                    param.args[0] = filterInboxItems(incoming);
                }
            });
            hooked++;
        }
        log("Adapter list hooks installed -> " + hooked + " methods on " + messageAdapterClass());
        if (hooked > 0) {
            SelfCheckRegistry.markInstalled(FEATURE_FILTER, messageAdapterClass(), hooked);
            if (hideMediaEnabled) {
                SelfCheckRegistry.markInstalled(FEATURE_MEDIA_BOX, mediaBoxItemClass(), hooked);
            }
        } else {
            SelfCheckRegistry.markStale(FEATURE_FILTER, messageAdapterClass(), "no List setter methods");
            if (hideMediaEnabled) {
                SelfCheckRegistry.markStale(FEATURE_MEDIA_BOX, messageAdapterClass(),
                        "no List setter methods");
            }
        }
    }

    private List<Object> filterInboxItems(List<?> original) {
        boolean hideMedia = effectiveHideMediaBox();
        String category = sessionSelectedCategory;
        boolean applyCategory = !CATEGORY_FOCUSED.equals(category);

        if (!hideMedia && !applyCategory) {
            return new ArrayList<>(original);
        }

        List<Object> filtered = new ArrayList<>(original.size());
        int mediaRemoved = 0;
        for (Object item : original) {
            if (hideMedia && isMediaBoxItem(item)) {
                mediaRemoved++;
                continue;
            }
            if (applyCategory && !belongsToCategory(item, category)) {
                continue;
            }
            filtered.add(item);
        }
        if (mediaRemoved > 0) {
            SelfCheckRegistry.markSuppressed(FEATURE_MEDIA_BOX, mediaBoxItemClass(),
                    "removed=" + mediaRemoved + " in=" + original.size());
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
        Class<?> recyclerViewClass = XposedHelpers.findClass(
                "androidx.recyclerview.widget.RecyclerView", classLoader);
        XposedBridge.hookAllMethods(recyclerViewClass, "setAdapter", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!shouldShowInboxLab()) {
                    return;
                }
                Object adapter = param.args.length > 0 ? param.args[0] : null;
                if (adapter == null || !messageAdapterClass().equals(adapter.getClass().getName())) {
                    return;
                }
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
        if (HookConfig.isEnabled(Tweaks.KEY_CATEGORY_OA)) {
            addCategoryChip(ctx, row, "OA", CATEGORY_OA);
        }
        if (HookConfig.isEnabled(Tweaks.KEY_CATEGORY_STRANGERS)) {
            addCategoryChip(ctx, row, "Strangers", CATEGORY_STRANGERS);
        }
        restyleChips();
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
        return categoriesEnabled;
    }

    private boolean effectiveHideMediaBox() {
        return hideMediaEnabled;
    }

    private int dp(android.content.Context ctx, float value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- classification

    private void hookInboxTapDiagnostics() throws Throwable {
        Class<?> clickHandlerClass = XposedHelpers.findClass(clickHandlerClass(), classLoader);
        XposedBridge.hookAllMethods(clickHandlerClass, clickMethod(), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!HookConfig.isDebugEnabled() || param.args.length < 2 || !(param.args[1] instanceof Integer)) {
                    return;
                }
                Object item = itemFromClickHandler(param.thisObject, (Integer) param.args[1]);
                if (item != null) {
                    String detail = describeRowForDebug(item);
                    SelfCheckRegistry.markSuppressed(FEATURE_TAP_DIAGNOSTICS, clickHandlerClass() + "#" + clickMethod(), compactDetail(detail));
                    log("Tap row -> " + detail);
                }
            }
        });
        SelfCheckRegistry.markInstalled(FEATURE_TAP_DIAGNOSTICS, clickHandlerClass() + "#" + clickMethod(), 1);
    }

    private Object itemFromClickHandler(Object handler, int position) {
        try {
            Object messagesView = HookReflect.findFieldValueByClassName(handler, messageViewClass());
            Object adapter = XposedHelpers.getObjectField(messagesView, messagesViewAdapterField());
            return XposedHelpers.callMethod(adapter, adapterItemMethod(), position);
        } catch (Throwable throwable) {
            log("Tap row lookup failed-soft: "
                    + throwable.getClass().getSimpleName() + " " + throwable.getMessage());
            return null;
        }
    }

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
                return isStrangerBoxItem(item);
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
        return item != null && normalItemClasses().contains(item.getClass().getName());
    }

    private String readUid(Object item) {
        try {
            return String.valueOf(XposedHelpers.callMethod(item, rowUidMethod()));
        } catch (Throwable t) {
            return "?";
        }
    }

    private String describeRowForDebug(Object item) {
        String className = item == null ? "null" : item.getClass().getName();
        String uid = readUid(item);
        Object conversation = conversationOf(item);
        int category = categoryOf(item);
        int topOut = topOutOf(conversation);
        int rowType = intField(item, rowTypeField(), -1);
        boolean followedByMemory = friendManagerFlag(uid, friendManagerFollowMethod(0));
        boolean followedByDb = friendManagerFlag(uid, friendManagerFollowMethod(1));
        boolean deletedGroup = isDeletedGroupUid(uid);
        boolean ours = isOaItem(item);
        return "uid=" + uid
                + " title=" + readTitle(item)
                + " cls=" + className
                + " rowType=" + rowType
                + " cat=" + category
                + " topOut=" + topOut
                + " fmA=" + followedByMemory
                + " fmC=" + followedByDb
                + " deletedGroup=" + deletedGroup
                + " bools=" + readBooleans(item)
                + " oursOa=" + ours;
    }

    /** Title text fields are schema-provided because row models drift between Zalo releases. */
    private String readTitle(Object item) {
        for (String f : SymbolSchema.strings(HookConfig.resolveModuleContextForHooks(), "symbols.inbox.row_title_fields")) {
            try {
                Object v = XposedHelpers.getObjectField(item, f);
                if (v != null && v.toString().trim().length() > 0) {
                    String s = v.toString();
                    return s.length() > 24 ? s.substring(0, 24) : s;
                }
            } catch (Throwable ignored) {
            }
        }
        return "?";
    }

    /** Dump every no-arg boolean method on a normal row so the group/OA predicate can be identified. */
    private String readBooleans(Object item) {
        StringBuilder sb = new StringBuilder();
        for (Method m : item.getClass().getDeclaredMethods()) {
            if (m.getParameterTypes().length != 0 || m.getReturnType() != boolean.class) {
                continue;
            }
            try {
                m.setAccessible(true);
                Object r = m.invoke(item);
                if (Boolean.TRUE.equals(r)) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(m.getName());
                }
            } catch (Throwable ignored) {
            }
        }
        return "[" + sb + "]";
    }

    private String firstNonEmptyString(Object obj, String prefix, List<String> fields) {
        for (String f : fields) {
            try {
                Object v = XposedHelpers.getObjectField(obj, f);
                if (v instanceof String && ((String) v).length() > 0) {
                    String s = (String) v;
                    return prefix + (s.length() > 20 ? s.substring(0, 20) : s);
                }
            } catch (Throwable ignored) {
            }
        }
        return prefix + "?";
    }

    /** Native category int from the stable Conversation object; field names come from schema. */
    private int categoryOf(Object item) {
        if (!isNormalItem(item)) {
            return -1;
        }
        try {
            Object conversation = XposedHelpers.getObjectField(item, conversationField());
            if (conversation == null) {
                return -1;
            }
            Object value = XposedHelpers.getObjectField(conversation, categoryIntField());
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable throwable) {
            logSymbolFailure("field-chain", classNameOf(item) + "#" + conversationField()
                    + " -> " + categoryIntField(), throwable);
            return -1;
        }
    }

    private int countUnknownItems(List<?> items) {
        int unknown = 0;
        for (Object item : items) {
            if (item == null) {
                unknown++;
                continue;
            }
            String className = item.getClass().getName();
            if (isNormalItem(item)
                    || mediaBoxItemClass().equals(className)
                    || bizBoxItemClass().equals(className)
                    || strangerBoxItemClass().equals(className)) {
                continue;
            }
            unknown++;
        }
        return unknown;
    }

    private String compactDetail(String detail) {
        if (detail == null) {
            return "";
        }
        String compact = detail.replace('\n', ' ').trim();
        return compact.length() > 160 ? compact.substring(0, 160) : compact;
    }

    /**
     * Group detection: Zalo group conversation uids carry a literal "group_" prefix (stable across
     * versions). The wz.c.h() boolean is the current per-item group flag (was m() pre-26.05).
     */
    private boolean isGroupItem(Object item) {
        if (!isNormalItem(item)) {
            return false;
        }
        String uid = readUid(item);
        if (isDeletedGroupUid(uid)) {
            return false;
        }
        try {
            if (uid.startsWith("group_")) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            return Boolean.TRUE.equals(XposedHelpers.callMethod(item, groupFlagMethod()));
        } catch (Throwable throwable) {
            logSymbolFailure("method", classNameOf(item) + "#" + groupFlagMethod(), throwable);
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
                Class<?> repositoryClass = XposedHelpers.findClass(deletedGroupRepositoryClass(), classLoader);
                repository = XposedHelpers.getStaticObjectField(repositoryClass, deletedGroupRepositoryField());
                deletedGroupRepository = repository;
            }
            boolean deleted = Boolean.TRUE.equals(XposedHelpers.callMethod(repository, deletedGroupCheckMethod(), uid));
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
     * OA detection: prefer Zalo's current row-local topOut marker. Live taps on orange-marked rows
     * showed topOut 1 and 2, while the native category can remain 1 after Zalo resolves final position.
     */
    private boolean isOaItem(Object item) {
        Object conversation = conversationOf(item);
        if (conversation == null) {
            return false;
        }
        String uid = stringField(conversation, conversationUidField());
        if (uid == null || uid.length() == 0 || uid.startsWith("group_")) {
            return false;
        }
        int topOut = topOutOf(conversation);
        return topOut == 1 || topOut == 2 || categoryOf(item) == CAT_OA || isKnownOaFollowUid(uid);
    }

    private boolean isKnownOaFollowUid(String uid) {
        Boolean cached = oaFollowCache.get(uid);
        if (cached != null) {
            return cached;
        }
        boolean result = false;
        try {
            Object manager = friendManager;
            if (manager == null) {
                Class<?> managerClass = XposedHelpers.findClass(friendManagerClass(), classLoader);
                manager = XposedHelpers.callStaticMethod(managerClass, friendManagerInstanceMethod());
                friendManager = manager;
            }
            result = friendManagerFlag(uid, friendManagerFollowMethod(0))
                    || friendManagerFlag(uid, friendManagerFollowMethod(1));
        } catch (Throwable throwable) {
            if (HookConfig.isDebugEnabled()) {
                log("OA follow check failed-soft: "
                        + throwable.getClass().getSimpleName() + " " + throwable.getMessage());
            }
        }
        oaFollowCache.put(uid, result);
        return result;
    }

    private boolean friendManagerFlag(String uid, String methodName) {
        if (uid == null || uid.length() == 0) {
            return false;
        }
        try {
            Object manager = friendManager;
            if (manager == null) {
                Class<?> managerClass = XposedHelpers.findClass(friendManagerClass(), classLoader);
                manager = XposedHelpers.callStaticMethod(managerClass, friendManagerInstanceMethod());
                friendManager = manager;
            }
            return Boolean.TRUE.equals(XposedHelpers.callMethod(manager, methodName, uid));
        } catch (Throwable throwable) {
            logSymbolFailure("method-chain", friendManagerClass() + "#" + friendManagerInstanceMethod()
                    + " -> " + methodName, throwable);
            return false;
        }
    }

    private Object conversationOf(Object item) {
        if (!isNormalItem(item)) {
            return null;
        }
        try {
            return XposedHelpers.getObjectField(item, conversationField());
        } catch (Throwable throwable) {
            logSymbolFailure("field", classNameOf(item) + "#" + conversationField(), throwable);
            return null;
        }
    }

    private int topOutOf(Object conversation) {
        try {
            Object topOutInfo = XposedHelpers.getObjectField(conversation, topOutField());
            if (topOutInfo == null) {
                return -1;
            }
            Object value = XposedHelpers.getObjectField(topOutInfo, topOutValueField());
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable throwable) {
            logSymbolFailure("field-chain", classNameOf(conversation) + "#" + topOutField()
                    + " -> " + topOutValueField(), throwable);
            return -1;
        }
    }

    private String stringField(Object target, String fieldName) {
        try {
            Object value = XposedHelpers.getObjectField(target, fieldName);
            return value instanceof String ? (String) value : null;
        } catch (Throwable throwable) {
            logSymbolFailure("field", classNameOf(target) + "#" + fieldName, throwable);
            return null;
        }
    }

    private int intField(Object target, String fieldName, int fallback) {
        try {
            Object value = XposedHelpers.getObjectField(target, fieldName);
            return value instanceof Integer ? (Integer) value : fallback;
        } catch (Throwable throwable) {
            logSymbolFailure("field", classNameOf(target) + "#" + fieldName, throwable);
            return fallback;
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

    /** Media box rows are synthetic Zalo rows; the row class comes from schema. */
    private boolean isMediaBoxItem(Object item) {
        return item != null && mediaBoxItemClass().equals(item.getClass().getName());
    }

    private boolean isStrangerBoxItem(Object item) {
        return item != null && strangerBoxItemClass().equals(item.getClass().getName());
    }

    /** Re-run the adapter list-setter from the cached unfiltered list so the new category applies. */
    private void refreshInbox() {
        Object adapter = lastInboxAdapter;
        Method setter = listSetterMethod;
        List<Object> source = lastUnfilteredItems;
        if (adapter == null || setter == null || source == null) {
            log("refreshInbox skipped (adapter/setter/source missing)");
            return;
        }
        try {
            isOurReentrantCall = true;
            setter.setAccessible(true);
            setter.invoke(adapter, new ArrayList<>(source));
        } catch (Throwable throwable) {
            log("refreshInbox failed: " + throwable.getClass().getSimpleName());
        } finally {
            isOurReentrantCall = false;
        }
    }

    private static String messageViewClass() {
        return schemaString("symbols.inbox.message_view_class", MESSAGE_VIEW_CLASS);
    }

    private static String messageAdapterClass() {
        return schemaString("symbols.inbox.message_adapter_class", "");
    }

    private static String normalItemClass() {
        return schemaString("symbols.inbox.normal_item_class", "");
    }

    private static List<String> normalItemClasses() {
        return SymbolSchema.strings(HookConfig.resolveModuleContextForHooks(),
                "symbols.inbox.normal_item_classes", normalItemClass());
    }

    private static String mediaBoxItemClass() {
        return schemaString("symbols.inbox.media_box_item_class", "");
    }

    private static String bizBoxItemClass() {
        return schemaString("symbols.inbox.biz_box_item_class", "");
    }

    private static String strangerBoxItemClass() {
        return schemaString("symbols.inbox.stranger_box_item_class", "");
    }

    private static String clickHandlerClass() {
        return schemaString("symbols.inbox.click_handler_class", "");
    }

    private static String conversationField() {
        return schemaString("symbols.inbox.conversation_field", "");
    }

    private static String categoryIntField() {
        return schemaString("symbols.inbox.category_int_field", "");
    }

    private static String messagesViewAdapterField() {
        return schemaString("symbols.inbox.messages_view_adapter_field", "");
    }

    private static String clickMethod() {
        return schemaString("symbols.inbox.click_method", "");
    }

    private static String adapterItemMethod() {
        return schemaString("symbols.inbox.adapter_item_method", "");
    }

    private static String rowUidMethod() {
        return schemaString("symbols.inbox.row_uid_method", "");
    }

    private static String groupFlagMethod() {
        return schemaString("symbols.inbox.group_flag_method", "");
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

    private static String rowTypeField() {
        return schemaString("symbols.inbox.row_type_field", "");
    }

    private static String profileField() {
        return schemaString("symbols.inbox.profile_field", "");
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

    private static String friendManagerClass() {
        return schemaString("symbols.inbox.friend_manager_class", "");
    }

    private static String friendManagerInstanceMethod() {
        return schemaString("symbols.inbox.friend_manager_instance_method", "");
    }

    private static String friendManagerFollowMethod(int index) {
        List<String> methods = SymbolSchema.strings(HookConfig.resolveModuleContextForHooks(),
                "symbols.inbox.friend_manager_follow_methods");
        return index >= 0 && index < methods.size() ? methods.get(index) : "";
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
