package com.ez.zalopatch.xposed.features;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class ZinstantFeature extends Feature {
    private static final String FEATURE_FEED_AD = "zinstant.feed_ad";
    private static final String FEATURE_MESSAGE_AD = "zinstant.message_ad";
    private static final String FEATURE_NETWORK = "zinstant.network";
    private static final String FEATURE_SCRIPT = "zinstant.script";
    private static final String ZINSTANT_AD_ITEM_VIEW_CLASS = "com.zing.zalo.ui.widget.ZinstantAdItemView";
    private static final String ZINSTANT_FEED_ADS_CLASS = "com.zing.zalo.social.presentation.timeline.components.ads.FeedItemZInstantAds";
    private static final String ZINSTANT_COMMUNICATOR_CLASS = "com.zing.zalo.zinstant.utils.ZinstantCommunicatorHelper";
    private static final String ZINSTANT_SCRIPT_HELPER_CLASS = "com.zing.zalo.zinstant.utils.ScriptHelperImpl";
    private boolean hideMessageAds;
    private boolean hideFeedAds;
    private boolean hidePromoServices;
    private final boolean messageViewCompatible;
    private final String messageViewError;
    private final boolean feedViewCompatible;
    private final String feedViewError;

    public ZinstantFeature(ClassLoader classLoader, boolean messageViewCompatible,
                           String messageViewError, boolean feedViewCompatible,
                           String feedViewError) {
        super(classLoader);
        this.messageViewCompatible = messageViewCompatible;
        this.messageViewError = messageViewError;
        this.feedViewCompatible = feedViewCompatible;
        this.feedViewError = feedViewError;
    }

    @Override
    public String getFeatureName() {
        return "Zinstant";
    }

    @Override
    public void doHook() throws Throwable {
        hideMessageAds = HookConfig.isEnabled(Tweaks.KEY_HIDE_MESSAGE_ADS);
        hideFeedAds = HookConfig.isEnabled(Tweaks.KEY_HIDE_FEED_ADS);
        hidePromoServices = HookConfig.isEnabled(Tweaks.KEY_HIDE_PROMO_SERVICES);
        if (!hideMessageAds) {
            SelfCheckRegistry.markDisabled(FEATURE_MESSAGE_AD, ZINSTANT_AD_ITEM_VIEW_CLASS);
        } else if (!messageViewCompatible) {
            SelfCheckRegistry.markStale(FEATURE_MESSAGE_AD, "structural preflight",
                    messageViewError);
        }
        if (!hideFeedAds) {
            SelfCheckRegistry.markDisabled(FEATURE_FEED_AD, ZINSTANT_FEED_ADS_CLASS);
        } else if (!feedViewCompatible) {
            SelfCheckRegistry.markStale(FEATURE_FEED_AD, "structural preflight",
                    feedViewError);
        }
        if (!shouldHidePromoServices()) {
            SelfCheckRegistry.markDisabled(FEATURE_NETWORK, ZINSTANT_COMMUNICATOR_CLASS);
            SelfCheckRegistry.markDisabled(FEATURE_SCRIPT, ZINSTANT_SCRIPT_HELPER_CLASS);
        }
        if (messageViewCompatible || feedViewCompatible) {
            hookZinstantAdViews();
            hookActivityViewScan();
        }
        hookZinstantNetwork();
        log("Zinstant suppression hooks installed");
    }

    private boolean shouldHideMessageAds() {
        return hideMessageAds && messageViewCompatible;
    }

    private boolean shouldHideFeedAds() {
        return hideFeedAds && feedViewCompatible;
    }

    private boolean shouldHidePromoServices() {
        return hidePromoServices;
    }

    private void hookZinstantAdViews() {
        if (messageViewCompatible) {
        try {
            Class<?> adViewClass = XposedHelpers.findClass(ZINSTANT_AD_ITEM_VIEW_CLASS, classLoader);
            XposedBridge.hookAllConstructors(adViewClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (shouldHideMessageAds()) {
                        collapseView((View) param.thisObject);
                        SelfCheckRegistry.markSuppressed(FEATURE_MESSAGE_AD, ZINSTANT_AD_ITEM_VIEW_CLASS, "constructor collapse");
                    }
                }
            });
            int hooked = hookVoidViewMethods(adViewClass, adBindMethod(), 3, this::shouldHideMessageAds);
            if (shouldHideMessageAds()) {
                SelfCheckRegistry.markInstalled(FEATURE_MESSAGE_AD, ZINSTANT_AD_ITEM_VIEW_CLASS, hooked + 1);
            }
            log("Zinstant ad-item suppression installed -> bindMethods=" + hooked);
        } catch (Throwable throwable) {
            if (shouldHideMessageAds()) {
                SelfCheckRegistry.markStale(FEATURE_MESSAGE_AD, ZINSTANT_AD_ITEM_VIEW_CLASS, throwable.getMessage());
            }
            log("Failed to hook ZinstantAdItemView: " + throwable);
        }
        }

        if (feedViewCompatible) {
        try {
            Class<?> feedAdsClass = XposedHelpers.findClass(ZINSTANT_FEED_ADS_CLASS, classLoader);
            XposedBridge.hookAllConstructors(feedAdsClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (shouldHideFeedAds()) {
                        collapseView((View) param.thisObject);
                        SelfCheckRegistry.markSuppressed(FEATURE_FEED_AD, ZINSTANT_FEED_ADS_CLASS, "constructor collapse");
                    }
                }
            });
            int hooked = hookVoidViewMethods(feedAdsClass, feedBindMethod(), 4, this::shouldHideFeedAds);
            if (shouldHideFeedAds()) {
                SelfCheckRegistry.markInstalled(FEATURE_FEED_AD, ZINSTANT_FEED_ADS_CLASS, hooked + 1);
            }
            log("Zinstant feed-ad suppression installed -> bindMethods=" + hooked);
        } catch (Throwable throwable) {
            if (shouldHideFeedAds()) {
                SelfCheckRegistry.markStale(FEATURE_FEED_AD, ZINSTANT_FEED_ADS_CLASS, throwable.getMessage());
            }
            log("Failed to hook FeedItemZInstantAds: " + throwable);
        }
        }
    }

    private int hookVoidViewMethods(Class<?> clazz, String methodName, int parameterCount, BooleanSupplier enabled) {
        String feature = ZINSTANT_AD_ITEM_VIEW_CLASS.equals(clazz.getName()) ? FEATURE_MESSAGE_AD : FEATURE_FEED_AD;
        int hooked = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if ((methodName != null && !methodName.equals(method.getName()))
                    || method.getReturnType() != Void.TYPE
                    || (parameterCount >= 0 && method.getParameterTypes().length != parameterCount)
                    || (parameterCount < 0 && method.getParameterTypes().length == 0)) {
                continue;
            }
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (enabled.getAsBoolean() && param.thisObject instanceof View) {
                        collapseView((View) param.thisObject);
                        param.setResult(null);
                        SelfCheckRegistry.markSuppressed(feature, clazz.getName() + "#" + method.getName(), "bind collapse");
                    }
                }
            });
            hooked++;
        }
        return hooked;
    }

    private void hookZinstantNetwork() {
        try {
            Class<?> communicatorClass = XposedHelpers.findClass(ZINSTANT_COMMUNICATOR_CLASS, classLoader);
            int hooked = 0;
            for (Method method : communicatorClass.getDeclaredMethods()) {
                if (!networkMethods().contains(method.getName())) {
                    continue;
                }
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (shouldHidePromoServices()) {
                            param.setResult(fallbackFor(method.getReturnType()));
                            SelfCheckRegistry.markSuppressed(FEATURE_NETWORK, ZINSTANT_COMMUNICATOR_CLASS + "#" + method.getName(), firstStringArg(param));
                        }
                    }
                });
                hooked++;
            }
            if (shouldHidePromoServices()) {
                if (hooked > 0) {
                    SelfCheckRegistry.markInstalled(FEATURE_NETWORK, ZINSTANT_COMMUNICATOR_CLASS, hooked);
                } else {
                    SelfCheckRegistry.markStale(FEATURE_NETWORK, ZINSTANT_COMMUNICATOR_CLASS, "no matching methods");
                }
            }
            log("Zinstant network hooks installed -> " + hooked + " methods");
        } catch (Throwable throwable) {
            if (shouldHidePromoServices()) {
                SelfCheckRegistry.markStale(FEATURE_NETWORK, ZINSTANT_COMMUNICATOR_CLASS, throwable.getMessage());
            }
            log("Failed to hook zinstant communicator: " + throwable);
        }

        try {
            Class<?> scriptHelperClass = XposedHelpers.findClass(ZINSTANT_SCRIPT_HELPER_CLASS, classLoader);
            java.util.List<String> missing = new java.util.ArrayList<>();
            int hooked = 0;
            for (String method : scriptVoidMethods()) {
                int count = hookZinstantVoidMethod(
                        scriptHelperClass, method, "Zinstant script " + method);
                hooked += count;
                if (count == 0) missing.add(method);
            }
            for (String method : scriptObjectMethods()) {
                int count = hookZinstantObjectMethod(
                        scriptHelperClass, method, null, "Zinstant script " + method);
                hooked += count;
                if (count == 0) missing.add(method);
            }
            if (shouldHidePromoServices()) {
                if (missing.isEmpty() && hooked > 0) {
                    SelfCheckRegistry.markInstalled(FEATURE_SCRIPT,
                            ZINSTANT_SCRIPT_HELPER_CLASS, hooked);
                } else {
                    SelfCheckRegistry.markStale(FEATURE_SCRIPT,
                            ZINSTANT_SCRIPT_HELPER_CLASS, "missing methods " + missing);
                }
            }
        } catch (Throwable throwable) {
            if (shouldHidePromoServices()) {
                SelfCheckRegistry.markStale(FEATURE_SCRIPT, ZINSTANT_SCRIPT_HELPER_CLASS, throwable.getMessage());
            }
            log("Failed to hook ScriptHelperImpl: " + throwable);
        }
    }

    private int hookZinstantVoidMethod(Class<?> clazz, String methodName, String label) {
        int hooked = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!methodName.equals(method.getName()) || method.getReturnType() != Void.TYPE) {
                continue;
            }
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (shouldHidePromoServices()) {
                        param.setResult(null);
                        SelfCheckRegistry.markSuppressed(FEATURE_SCRIPT, label, firstStringArg(param));
                    }
                }
            });
            hooked++;
        }
        log(label + " hooks installed -> " + hooked + " methods");
        return hooked;
    }

    private int hookZinstantObjectMethod(Class<?> clazz, String methodName,
                                         Object fallbackValue, String label) {
        int hooked = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!methodName.equals(method.getName()) || method.getReturnType() == Void.TYPE) {
                continue;
            }
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (shouldHidePromoServices()) {
                        param.setResult(fallbackValue);
                        SelfCheckRegistry.markSuppressed(FEATURE_SCRIPT, label, firstStringArg(param));
                    }
                }
            });
            hooked++;
        }
        log(label + " hooks installed -> " + hooked + " methods");
        return hooked;
    }

    private void hookActivityViewScan() {
        XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof Activity)) {
                    return;
                }
                View root = ((Activity) param.thisObject).getWindow() == null
                        ? null
                        : ((Activity) param.thisObject).getWindow().getDecorView();
                scanAndCollapse(root);
            }
        });
        int count = 0;
        if (shouldHideMessageAds()) {
            count++;
        }
        if (shouldHideFeedAds()) {
            count++;
        }
        if (count > 0) {
            SelfCheckRegistry.markInstalled("zinstant.view_scan", "Activity#onResume", count);
        }
    }

    private void scanAndCollapse(View view) {
        if (view == null) {
            return;
        }
        String className = view.getClass().getName();
        if (shouldHideMessageAds() && ZINSTANT_AD_ITEM_VIEW_CLASS.equals(className)) {
            collapseView(view);
            SelfCheckRegistry.markSuppressed(FEATURE_MESSAGE_AD, className, "activity scan collapse");
        } else if (shouldHideFeedAds() && ZINSTANT_FEED_ADS_CLASS.equals(className)) {
            collapseView(view);
            SelfCheckRegistry.markSuppressed(FEATURE_FEED_AD, className, "activity scan collapse");
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                scanAndCollapse(group.getChildAt(i));
            }
        }
    }

    private static String firstStringArg(XC_MethodHook.MethodHookParam param) {
        if (param.args == null) {
            return "";
        }
        for (Object arg : param.args) {
            if (arg instanceof String) {
                return (String) arg;
            }
        }
        return "";
    }

    private static Object fallbackFor(Class<?> returnType) {
        if (returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (returnType == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (returnType == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (returnType == Float.TYPE) {
            return Float.valueOf(0f);
        }
        if (returnType == Double.TYPE) {
            return Double.valueOf(0d);
        }
        return null;
    }

    private static void collapseView(View view) {
        if (view == null) {
            return;
        }
        view.setVisibility(View.GONE);
        view.setMinimumHeight(0);
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams != null) {
            layoutParams.height = 0;
            if (layoutParams.width <= 0) {
                layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            }
            view.setLayoutParams(layoutParams);
        }
    }

    private static String adBindMethod() {
        return schemaString("symbols.zinstant.ad_bind_method", "");
    }

    private static String feedBindMethod() {
        return schemaString("symbols.zinstant.feed_bind_method", "");
    }

    private static List<String> networkMethods() {
        return SymbolSchema.strings(HookConfig.resolveModuleContextForHooks(),
                "symbols.zinstant.network_methods",
                "sendHttpRequest", "get", "post", "requestSocket", "sendAsyncRequest");
    }

    private static List<String> scriptVoidMethods() {
        return SymbolSchema.strings(HookConfig.resolveModuleContextForHooks(),
                "symbols.zinstant.script_void_methods",
                "downloadScripts", "get", "post");
    }

    private static List<String> scriptObjectMethods() {
        return SymbolSchema.strings(HookConfig.resolveModuleContextForHooks(),
                "symbols.zinstant.script_object_methods",
                "getScriptContent", "getEncryptedScriptContent");
    }

    private static String schemaString(String path, String fallback) {
        return SymbolSchema.string(HookConfig.resolveModuleContextForHooks(), path, fallback);
    }

    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
