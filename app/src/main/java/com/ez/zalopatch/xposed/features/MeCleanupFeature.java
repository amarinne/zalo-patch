package com.ez.zalopatch.xposed.features;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class MeCleanupFeature extends Feature {
    private static final String FEATURE_ITEMS = "me_cleanup.items";
    private static final String FEATURE_VISIBLE_ROWS = "me_cleanup.visible_rows";
    private static final String FEATURE_QR_WALLET = "me_cleanup.qr_wallet";
    private static final String FEATURE_ZCLOUD = "me_cleanup.zcloud";
    private static final String FEATURE_ZSTYLE = "me_cleanup.zstyle";
    private static final String FEATURE_ZBUSINESS = "me_cleanup.zbusiness";
    private static final String FEATURE_REFRESH = "me_cleanup.refresh";
    private static final String TAB_ME_CLASS = "com.zing.zalo.ui.maintab.me.TabMeView";
    private static final String CURRENT_TAB_ME_ZINSTANT_VIEW_CLASS = "com.zing.zalo.ui.maintab.me.TabMeZinstantView";
    private final AtomicBoolean loggedOnce = new AtomicBoolean(false);
    private final AtomicBoolean debugLoggedOnce = new AtomicBoolean(false);
    private final AtomicBoolean reportedQrWallet = new AtomicBoolean(false);
    private final AtomicBoolean reportedZCloud = new AtomicBoolean(false);
    private final AtomicBoolean reportedZStyle = new AtomicBoolean(false);
    private final AtomicBoolean reportedZBusiness = new AtomicBoolean(false);
    private boolean hideQrWallet;
    private boolean hideZCloud;
    private boolean hideZStyle;
    private boolean hideZBusiness;

    public MeCleanupFeature(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getFeatureName() {
        return "MeCleanup";
    }

    @Override
    public void doHook() throws Throwable {
        hideQrWallet = HookConfig.isEnabled(Tweaks.KEY_HIDE_QR_WALLET);
        hideZCloud = HookConfig.isEnabled(Tweaks.KEY_HIDE_ZCLOUD);
        hideZStyle = HookConfig.isEnabled(Tweaks.KEY_HIDE_ZSTYLE);
        hideZBusiness = HookConfig.isEnabled(Tweaks.KEY_HIDE_ZBUSINESS);

        markItemStatus(FEATURE_QR_WALLET, hideQrWallet, "QR Wallet");
        markItemStatus(FEATURE_ZCLOUD, hideZCloud, "zCloud");
        markItemStatus(FEATURE_ZSTYLE, hideZStyle, "zStyle");
        markItemStatus(FEATURE_ZBUSINESS, hideZBusiness, "zBusiness");

        if (!hideQrWallet && !hideZCloud && !hideZStyle && !hideZBusiness) {
            SelfCheckRegistry.markDisabled(FEATURE_ITEMS, "TabMeView item filters");
            SelfCheckRegistry.markDisabled(FEATURE_REFRESH, "TabMe refresh hooks");
            SelfCheckRegistry.markDisabled(FEATURE_VISIBLE_ROWS, "TextView#setText");
            return;
        }

        runGuarded("Current TabMe builder", FEATURE_ITEMS, tabMeClass() + "#" + currentBuilderMethod(), this::hookCurrentTabMeBuilder);
        runGuarded("Visible TabMe rows", FEATURE_VISIBLE_ROWS,
                "TextView#setText", this::hookVisibleRowText);
        if (hideZStyle && !zStyleViewClass().isEmpty()) {
            runGuarded("zStyle TabMe view", FEATURE_ZSTYLE,
                    zStyleViewClass(), this::hookZStyleView);
        }
        if (!legacyBuilderMethod().isEmpty()) {
            runGuarded("Legacy TabMe builder", "me_cleanup.legacy",
                    tabMeClass() + "#" + legacyBuilderMethod(), this::hookLegacyTabMeBuilder);
        }
    }

    private void hookCurrentTabMeBuilder() {
        Class<?> tabMeClass = XposedHelpers.findClass(tabMeClass(), classLoader);
        int hooked = XposedBridge.hookAllMethods(tabMeClass, currentBuilderMethod(), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object filtered = filterIfNeeded(param.getResult());
                if (filtered != param.getResult()) {
                    param.setResult(filtered);
                }
            }
        }).size();
        if (hooked == 0) {
            throw new NoSuchMethodError(tabMeClass() + "#" + currentBuilderMethod());
        }
        if (hideQrWallet || hideZCloud || hideZStyle || hideZBusiness) {
            SelfCheckRegistry.markInstalled(FEATURE_REFRESH,
                    tabMeClass() + "#" + currentBuilderMethod(), hooked);
        }
    }

    private void hookLegacyTabMeBuilder() {
        Class<?> tabMeClass = XposedHelpers.findClass(tabMeClass(), classLoader);
        int hooked = XposedBridge.hookAllMethods(tabMeClass, legacyBuilderMethod(), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object filtered = filterIfNeeded(param.getResult());
                if (filtered != param.getResult()) {
                    param.setResult(filtered);
                }
            }
        }).size();
        if (hooked == 0) {
            throw new NoSuchMethodError(tabMeClass() + "#" + legacyBuilderMethod());
        }
    }

    private void hookVisibleRowText() {
        XposedBridge.hookAllMethods(TextView.class, "setText", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof TextView)) {
                    return;
                }
                TextView textView = (TextView) param.thisObject;
                int reason = visibleTextHideReason(String.valueOf(textView.getText()));
                if (reason == HIDE_NONE) {
                    return;
                }
                hideVisibleTextRow(textView, reason);
                textView.post(() -> hideVisibleTextRow(textView, reason));
            }
        });
    }

    private void hookZStyleView() {
        Class<?> viewClass = XposedHelpers.findClass(zStyleViewClass(), classLoader);
        int hooked = XposedBridge.hookAllConstructors(viewClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof View)) {
                    return;
                }
                View view = (View) param.thisObject;
                collapseZStyleView(view);
                view.post(() -> collapseZStyleView(view));
            }
        }).size();
        if (hooked == 0) {
            throw new NoSuchMethodError(zStyleViewClass() + "#<init>");
        }
    }

    private void collapseZStyleView(View view) {
        collapseView(view);
        reportSuppressed(HIDE_ZSTYLE, zStyleViewClass(), "zstyle=1");
    }

    private void hideVisibleTextRow(TextView textView, int reason) {
        if (!hasAncestorClass(textView, tabMeClass())) {
            return;
        }
        View row = findClickableAncestor(textView);
        if (row == null || row.getVisibility() == View.GONE) {
            return;
        }
        collapseView(row);
        reportSuppressed(reason, "TabMe visible text", reasonDetail(reason));
    }

    private static boolean hasAncestorClass(View view, String className) {
        if (view == null || className == null || className.isEmpty()) {
            return false;
        }
        android.view.ViewParent parent = view.getParent();
        int depth = 0;
        while (parent instanceof View && depth++ < 24) {
            View parentView = (View) parent;
            if (className.equals(parentView.getClass().getName())) {
                return true;
            }
            parent = parentView.getParent();
        }
        return false;
    }

    private void markItemStatus(String feature, boolean enabled, String label) {
        if (enabled) {
            SelfCheckRegistry.markInstalled(feature, "TabMe cleanup rule", 1);
        } else {
            SelfCheckRegistry.markDisabled(feature, label);
        }
    }

    private boolean filterListField(Object owner) throws Throwable {
        for (Class<?> current = owner.getClass(); current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(owner);
                if (!looksLikeTabMeItemList(value)) {
                    continue;
                }
                Object filtered = filterIfNeeded(value);
                if (filtered != value) {
                    field.set(owner, filtered);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean looksLikeTabMeItemList(Object value) {
        if (!(value instanceof List)) {
            return false;
        }
        List<?> list = (List<?>) value;
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String className = item.getClass().getName();
            if (settingItemClass().equals(className) || zinstantItemClass().equals(className)) {
                return true;
            }
        }
        return false;
    }

    private Object filterIfNeeded(Object value) {
        if (!(value instanceof List)) {
            return value;
        }
        List<?> originalItems = (List<?>) value;
        logDebugItems(originalItems);
        List<Object> filteredItems = new ArrayList<>(originalItems.size());
        int removed = 0;
        int qrWalletRemoved = 0;
        int zCloudRemoved = 0;
        int zStyleRemoved = 0;
        int zBusinessRemoved = 0;
        for (Object item : originalItems) {
            int reason = hideReason(item, hideQrWallet, hideZCloud, hideZStyle, hideZBusiness);
            if (reason != HIDE_NONE) {
                removed++;
                if (reason == HIDE_QR_WALLET) {
                    qrWalletRemoved++;
                } else if (reason == HIDE_ZCLOUD) {
                    zCloudRemoved++;
                } else if (reason == HIDE_ZSTYLE) {
                    zStyleRemoved++;
                } else if (reason == HIDE_ZBUSINESS) {
                    zBusinessRemoved++;
                }
            } else {
                filteredItems.add(item);
            }
        }
        if (removed > 0 && loggedOnce.compareAndSet(false, true)) {
            log("TabMe items filtered -> kept=" + filteredItems.size() + ", removed=" + removed);
        }
        if (removed > 0) {
            if (qrWalletRemoved > 0) {
                reportSuppressed(HIDE_QR_WALLET, "TabMe item list",
                        "kept=" + filteredItems.size() + " removed=" + removed + " qr=" + qrWalletRemoved);
            }
            if (zCloudRemoved > 0) {
                reportSuppressed(HIDE_ZCLOUD, "TabMe item list",
                        "kept=" + filteredItems.size() + " removed=" + removed + " zcloud=" + zCloudRemoved);
            }
            if (zStyleRemoved > 0) {
                reportSuppressed(HIDE_ZSTYLE, "TabMe item list",
                        "kept=" + filteredItems.size() + " removed=" + removed + " zstyle=" + zStyleRemoved);
            }
            if (zBusinessRemoved > 0) {
                reportSuppressed(HIDE_ZBUSINESS, "TabMe item list",
                        "kept=" + filteredItems.size() + " removed=" + removed + " zbusiness=" + zBusinessRemoved);
            }
        }
        return removed > 0 ? filteredItems : value;
    }

    private void reportSuppressed(int reason, String target, String detail) {
        String feature = featureForReason(reason);
        AtomicBoolean reported = reportedFlag(reason);
        if (feature == null || reported == null || !reported.compareAndSet(false, true)) {
            return;
        }
        SelfCheckRegistry.markSuppressed(feature, target, detail);
    }

    private static String featureForReason(int reason) {
        if (reason == HIDE_QR_WALLET) {
            return FEATURE_QR_WALLET;
        }
        if (reason == HIDE_ZCLOUD) {
            return FEATURE_ZCLOUD;
        }
        if (reason == HIDE_ZSTYLE) {
            return FEATURE_ZSTYLE;
        }
        if (reason == HIDE_ZBUSINESS) {
            return FEATURE_ZBUSINESS;
        }
        return null;
    }

    private AtomicBoolean reportedFlag(int reason) {
        if (reason == HIDE_QR_WALLET) {
            return reportedQrWallet;
        }
        if (reason == HIDE_ZCLOUD) {
            return reportedZCloud;
        }
        if (reason == HIDE_ZSTYLE) {
            return reportedZStyle;
        }
        if (reason == HIDE_ZBUSINESS) {
            return reportedZBusiness;
        }
        return null;
    }

    private VisibleRemoval hideVisibleRows(View root) {
        VisibleRemoval removal = new VisibleRemoval();
        hideVisibleRows(root, removal);
        return removal;
    }

    private void hideVisibleRows(View view, VisibleRemoval removal) {
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        int reason = visibleRowHideReason(group);
        if (reason != HIDE_NONE) {
            collapseView(group);
            removal.removed++;
            if (reason == HIDE_QR_WALLET) {
                removal.qrWallet++;
            } else if (reason == HIDE_ZCLOUD) {
                removal.zCloud++;
            } else if (reason == HIDE_ZSTYLE) {
                removal.zStyle++;
            } else if (reason == HIDE_ZBUSINESS) {
                removal.zBusiness++;
            }
            return;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            hideVisibleRows(group.getChildAt(i), removal);
        }
    }

    private int visibleRowHideReason(ViewGroup group) {
        if (group.getVisibility() == View.GONE || !group.isClickable()) {
            return HIDE_NONE;
        }
        String text = visibleText(group);
        if (hideQrWallet && containsQrWalletMarker(text, "")) {
            return HIDE_QR_WALLET;
        }
        if (hideZCloud && containsZCloudMarker(text, "")) {
            return HIDE_ZCLOUD;
        }
        if (hideZStyle && containsZStyleMarker(text)) {
            return HIDE_ZSTYLE;
        }
        if (hideZBusiness && containsZBusinessMarker(text, "")) {
            return HIDE_ZBUSINESS;
        }
        return HIDE_NONE;
    }

    private int visibleTextHideReason(String text) {
        if (hideQrWallet && containsQrWalletMarker(text, "")) {
            return HIDE_QR_WALLET;
        }
        if (hideZCloud && containsZCloudMarker(text, "")) {
            return HIDE_ZCLOUD;
        }
        if (hideZStyle && containsZStyleMarker(text)) {
            return HIDE_ZSTYLE;
        }
        if (hideZBusiness && containsZBusinessMarker(text, "")) {
            return HIDE_ZBUSINESS;
        }
        return HIDE_NONE;
    }

    private static View findClickableAncestor(View view) {
        android.view.ViewParent parent = view.getParent();
        int depth = 0;
        while (parent instanceof View && depth++ < 8) {
            View parentView = (View) parent;
            if (parentView.isClickable()) {
                return parentView;
            }
            parent = parentView.getParent();
        }
        return null;
    }

    private static String reasonDetail(int reason) {
        if (reason == HIDE_QR_WALLET) {
            return "qr=1";
        }
        if (reason == HIDE_ZCLOUD) {
            return "zcloud=1";
        }
        if (reason == HIDE_ZSTYLE) {
            return "zstyle=1";
        }
        if (reason == HIDE_ZBUSINESS) {
            return "zbusiness=1";
        }
        return "";
    }

    private static String visibleText(View view) {
        StringBuilder builder = new StringBuilder();
        collectVisibleText(view, builder);
        return builder.toString();
    }

    private static void collectVisibleText(View view, StringBuilder builder) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(text);
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectVisibleText(group.getChildAt(i), builder);
            }
        }
    }

    private void logDebugItems(List<?> items) {
        if (!HookConfig.isDebugEnabled() || !debugLoggedOnce.compareAndSet(false, true)) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("TabMe item snapshot count=").append(items.size());
        int index = 0;
        for (Object item : items) {
            builder.append(" | ").append(index++).append(":").append(describeItem(item));
        }
        log(builder.toString());
    }

    private static final int HIDE_NONE = 0;
    private static final int HIDE_QR_WALLET = 1;
    private static final int HIDE_ZCLOUD = 2;
    private static final int HIDE_ZSTYLE = 3;
    private static final int HIDE_ZBUSINESS = 4;

    private static int hideReason(Object item, boolean hideQrWallet, boolean hideZCloud, boolean hideZStyle, boolean hideZBusiness) {
        if (item == null) {
            return HIDE_NONE;
        }
        String className = item.getClass().getName();
        if (hideZStyle && isZStyleItemClass(className)) {
            return HIDE_ZSTYLE;
        }
        if (settingItemClass().equals(className)) {
            return currentSettingHideReason(item, hideQrWallet, hideZCloud, hideZStyle, hideZBusiness);
        }
        if (!legacyItemClass().equals(className)) {
            return HIDE_NONE;
        }
        try {
            String tracking = invokeStringMethod(item, legacyTrackingMethod());
            String title = invokeStringMethod(item, legacyTitleMethod());
            String desc = invokeStringMethod(item, legacySummaryMethod());

            if (hideQrWallet && containsSchemaValue("features.me_cleanup.qr_wallet.tracking_ids", tracking, "tab_me_qr_wallet")) {
                return HIDE_QR_WALLET;
            }
            if (hideZCloud && containsSchemaValue("features.me_cleanup.zcloud.tracking_ids", tracking, "tab_me_z_cloud")) {
                return HIDE_ZCLOUD;
            }
            if (hideZBusiness && containsSchemaValue("features.me_cleanup.zbusiness.tracking_ids", tracking,
                    "tab_me_z_business", "tab_me_zbusiness", "tab_me_business")) {
                return HIDE_ZBUSINESS;
            }

            String titleLower = title == null ? "" : title.toLowerCase();
            String descLower = desc == null ? "" : desc.toLowerCase();
            if (hideZStyle && (titleLower.contains("zstyle")
                    || descLower.contains("music library")
                    || descLower.contains("background and music library"))) {
                return HIDE_ZSTYLE;
            }
            return hideZBusiness && containsZBusinessMarker(title, desc) ? HIDE_ZBUSINESS : HIDE_NONE;
        } catch (Throwable throwable) {
            return HIDE_NONE;
        }
    }

    private static int currentSettingHideReason(Object item, boolean hideQrWallet, boolean hideZCloud, boolean hideZStyle, boolean hideZBusiness) {
        try {
            int id = getCurrentSettingId(item);
            if (hideQrWallet && id == qrWalletItemId()) {
                return HIDE_QR_WALLET;
            }
            if (hideZCloud && id == zCloudItemId()) {
                return HIDE_ZCLOUD;
            }
            String title = stringField(item, settingTitleField());
            String desc = stringField(item, settingSummaryField());
            if (hideQrWallet && containsQrWalletMarker(title, desc)) {
                return HIDE_QR_WALLET;
            }
            if (hideZCloud && containsZCloudMarker(title, desc)) {
                return HIDE_ZCLOUD;
            }
            if (hideZStyle && (containsZStyleMarker(title) || containsZStyleMarker(desc))) {
                return HIDE_ZSTYLE;
            }
            if (hideZBusiness && containsZBusinessMarker(title, desc)) {
                return HIDE_ZBUSINESS;
            }
            return HIDE_NONE;
        } catch (Throwable throwable) {
            return HIDE_NONE;
        }
    }

    private static String describeItem(Object item) {
        if (item == null) {
            return "null";
        }
        String className = item.getClass().getName();
        if (settingItemClass().equals(className)) {
            try {
                return className + "{id=" + getCurrentSettingId(item)
                        + ",title=" + compact(stringField(item, settingTitleField()))
                        + ",desc=" + compact(stringField(item, settingSummaryField())) + "}";
            } catch (Throwable ignored) {
                return className + "{unreadable}";
            }
        }
        if (legacyItemClass().equals(className)) {
            try {
                return className + "{tracking=" + compact(invokeStringMethod(item, legacyTrackingMethod()))
                        + ",title=" + compact(invokeStringMethod(item, legacyTitleMethod()))
                        + ",desc=" + compact(invokeStringMethod(item, legacySummaryMethod())) + "}";
            } catch (Throwable ignored) {
                return className + "{unreadable}";
            }
        }
        return className;
    }

    private static String compact(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.replace('\n', ' ').trim();
        return trimmed.length() > 32 ? trimmed.substring(0, 32) : trimmed;
    }

    private static void collapseView(View view) {
        view.setVisibility(View.GONE);
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams != null) {
            layoutParams.height = 0;
            view.setLayoutParams(layoutParams);
        }
    }

    private static final class VisibleRemoval {
        int removed;
        int qrWallet;
        int zCloud;
        int zStyle;
        int zBusiness;
    }

    private static int getCurrentSettingId(Object item) throws Throwable {
        Object idEnum = objectField(item, settingIdField());
        Object idValue = objectField(idEnum, settingIdValueField());
        return idValue instanceof Integer ? (Integer) idValue : -1;
    }

    private static boolean containsZStyleMarker(String value) {
        String lower = value == null ? "" : value.toLowerCase();
        return containsAny(lower, "features.me_cleanup.zstyle.text_markers",
                "zstyle", "z style", "music library", "background and music library");
    }

    private static boolean containsQrWalletMarker(String title, String desc) {
        String text = ((title == null ? "" : title) + " " + (desc == null ? "" : desc)).toLowerCase();
        return containsAny(text, "features.me_cleanup.qr_wallet.text_markers",
                "qr wallet", "qr code", "my qr", "mã qr", "vi qr");
    }

    private static boolean containsZCloudMarker(String title, String desc) {
        String text = ((title == null ? "" : title) + " " + (desc == null ? "" : desc)).toLowerCase();
        return containsAny(text, "features.me_cleanup.zcloud.text_markers",
                "zcloud", "z cloud", "zalo cloud", "cloud của tôi", "my cloud");
    }

    private static boolean containsZBusinessMarker(String title, String desc) {
        String text = ((title == null ? "" : title) + " " + (desc == null ? "" : desc)).toLowerCase();
        return containsAny(text, "features.me_cleanup.zbusiness.text_markers",
                "zbusiness", "z business", "zalo business", "zalo for business",
                "tài khoản kinh doanh", "tai khoan kinh doanh", "doanh nghiệp", "doanh nghiep");
    }

    private static String invokeStringMethod(Object target, String methodName) throws Throwable {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        Object result = method.invoke(target);
        return result == null ? null : String.valueOf(result);
    }

    private static String stringField(Object target, String fieldName) throws Throwable {
        Object value = objectField(target, fieldName);
        return value == null ? null : String.valueOf(value);
    }

    private static Object objectField(Object target, String fieldName) throws Throwable {
        if (target == null) {
            return null;
        }
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Field findField(Class<?> startClass, String fieldName) throws NoSuchFieldException {
        for (Class<?> current = startClass; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                // Try the parent class below.
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static String tabMeClass() {
        return schemaString("symbols.me.tab_me_class", TAB_ME_CLASS);
    }

    private static String adapterClass() {
        return schemaString("symbols.me.adapter_class", "");
    }

    private static String settingItemClass() {
        return schemaString("symbols.me.setting_item_class", "");
    }

    private static String zinstantItemClass() {
        return schemaString("symbols.me.zinstant_item_class", "");
    }

    private static boolean isZStyleItemClass(String className) {
        for (String value : SymbolSchema.strings(HookConfig.resolveModuleContextForHooks(),
                "symbols.me.zstyle_item_classes")) {
            if (value.equals(className)) {
                return true;
            }
        }
        return false;
    }

    private static String zinstantViewClass() {
        return schemaString("symbols.me.zinstant_view_class", CURRENT_TAB_ME_ZINSTANT_VIEW_CLASS);
    }

    private static String zStyleViewClass() {
        return schemaString("symbols.me.zstyle_view_class", "");
    }

    private static String currentBuilderMethod() {
        return schemaString("symbols.me.current_builder_method", "");
    }

    private static String legacyBuilderMethod() {
        return schemaString("symbols.me.legacy_builder_method", "");
    }

    private static String adapterRefreshMethod() {
        return schemaString("symbols.me.adapter_refresh_method", "");
    }

    private static String settingIdField() {
        return schemaString("symbols.me.setting_id_field", "");
    }

    private static String settingIdValueField() {
        return schemaString("symbols.me.setting_id_value_field", "");
    }

    private static String settingTitleField() {
        return schemaString("symbols.me.setting_title_field", "");
    }

    private static String settingSummaryField() {
        return schemaString("symbols.me.setting_summary_field", "");
    }

    private static String legacyItemClass() {
        return schemaString("symbols.me.legacy_item_class", "");
    }

    private static String legacyTrackingMethod() {
        return schemaString("symbols.me.legacy_tracking_method", "");
    }

    private static String legacyTitleMethod() {
        return schemaString("symbols.me.legacy_title_method", "");
    }

    private static String legacySummaryMethod() {
        return schemaString("symbols.me.legacy_summary_method", "");
    }

    private static int qrWalletItemId() {
        return SymbolSchema.integer(HookConfig.resolveModuleContextForHooks(),
                "symbols.me.qr_wallet_item_id", -1);
    }

    private static int zCloudItemId() {
        return SymbolSchema.integer(HookConfig.resolveModuleContextForHooks(),
                "symbols.me.zcloud_item_id", -1);
    }

    private static boolean containsSchemaValue(String path, String value, String... fallback) {
        if (value == null) {
            return false;
        }
        for (String item : SymbolSchema.strings(HookConfig.resolveModuleContextForHooks(), path, fallback)) {
            if (value.equals(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String text, String path, String... fallback) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String marker : SymbolSchema.strings(HookConfig.resolveModuleContextForHooks(), path, fallback)) {
            if (!marker.isEmpty() && text.contains(marker.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String schemaString(String path, String fallback) {
        return SymbolSchema.string(HookConfig.resolveModuleContextForHooks(), path, fallback);
    }
}
