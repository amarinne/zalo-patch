package com.ez.zalopatch.xposed.features;

import android.app.AndroidAppHelper;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Opens tapped chat/feed http(s) links through the system browser instead of Zalo's in-app
 * webview, and voids the redirect/tracking wrapper applied on the way in.
 *
 * <p>Two anchors, two owners: the open dispatch lives on the extracted Kotlin companion of
 * {@code ZaloWebView}, the redirect transform on {@code ZaloWebView} itself (static). Both names
 * come from the schema and are structurally preflighted; an unresolved profile never arms this
 * feature.
 *
 * <p>Zalo-internal pages (settings, login, mini-apps, OA H5) stay in-app because only content
 * links carry the {@code EXTRA_SOURCE_LINK} bundle key, and mini-app/OA H5 opens are excluded
 * even when they do.
 */
public final class WebLinkExternalizeFeature extends Feature {
    private static final String FEATURE_KEY = Tweaks.KEY_OPEN_LINKS_EXTERNALLY;

    public WebLinkExternalizeFeature(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getFeatureName() {
        return "WebLinkExternalize";
    }

    @Override
    public void doHook() {
        if (!HookConfig.isEnabled(FEATURE_KEY)) {
            SelfCheckRegistry.markDisabled(FEATURE_KEY, "external link open");
            return;
        }
        Context context = HookConfig.resolveModuleContextForHooks();
        SymbolSchema.Active schema = SymbolSchema.activeForHooks(context);
        String webViewClass = schema.string("symbols.webview.zalo_web_view_class", "");
        String companionClass = schema.string("symbols.webview.companion_class", "");
        String dispatchMethod = schema.string("symbols.webview.open_dispatch_method", "");
        String transformMethod = schema.string("symbols.webview.redirect_transform_method", "");
        String target = "source=" + schema.source + " "
                + companionClass + "#" + dispatchMethod + "(...) + "
                + webViewClass + "#" + transformMethod + "(Uri)";
        runGuarded("open-links externally", FEATURE_KEY, target, () -> {
            Method redirect = findRedirect(webViewClass, transformMethod);
            Class<?> companion = XposedHelpers.findClass(companionClass, classLoader);
            Method dispatch = findDispatch(companion, dispatchMethod);
            if (dispatch == null) {
                throw new NoSuchMethodError(
                        companionClass + "#" + dispatchMethod
                                + "(interface, String, Bundle, boolean, int, interface) not found");
            }
            XC_MethodHook.Unhook dispatchHook = installExternalOpen(
                    dispatch, companionClass, dispatchMethod);
            try {
                installRedirectVoid(redirect, webViewClass, transformMethod);
            } catch (Throwable installFailure) {
                try {
                    dispatchHook.unhook();
                } catch (Throwable rollbackFailure) {
                    installFailure.addSuppressed(rollbackFailure);
                }
                throw installFailure;
            }
        });
    }

    /** Returns the original Uri so no tapped link gains a redirect/tracking hop. */
    private void installRedirectVoid(Method redirect, String webViewClass, String transformMethod) {
        XposedBridge.hookMethod(redirect, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(param.args[0]);
                SelfCheckRegistry.incrementHit(FEATURE_KEY,
                        webViewClass + "#" + transformMethod,
                        "returned original Uri");
            }
        });
    }

    private XC_MethodHook.Unhook installExternalOpen(Method dispatch, String companionClass,
                                                     String dispatchMethod) {
        return XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object bundleArg = param.args.length > 2 ? param.args[2] : null;
                if (!(bundleArg instanceof Bundle)) {
                    return;
                }
                Bundle bundle = (Bundle) bundleArg;
                String url = param.args[1] instanceof String ? (String) param.args[1] : null;
                if (WebLinkExternalizeGate.classify(
                        bundle.containsKey(WebLinkExternalizeGate.KEY_SOURCE_LINK),
                        bundle.getBoolean(WebLinkExternalizeGate.KEY_FROM_MINI_APP, false),
                        bundle.containsKey(WebLinkExternalizeGate.KEY_OA_H5),
                        url) != WebLinkExternalizeGate.Decision.EXTERNAL) {
                    return;
                }
                Context hostContext = resolveHostContext(param.args.length > 0
                        ? param.args[0] : null);
                if (hostContext == null) {
                    return;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    hostContext.startActivity(intent);
                } catch (ActivityNotFoundException missingHandler) {
                    // No external handler for this URL: let the original in-app open proceed.
                    return;
                } catch (Throwable startFailure) {
                    log("external open failed, keeping in-app open: "
                            + startFailure.getClass().getSimpleName());
                    return;
                }
                param.setResult(null);
                SelfCheckRegistry.incrementHit(FEATURE_KEY,
                        companionClass + "#" + dispatchMethod,
                        "opened externally");
            }
        });
    }

    private Method findRedirect(String webViewClass, String transformMethod)
            throws NoSuchMethodException {
        Class<?> webView = XposedHelpers.findClass(webViewClass, classLoader);
        Method redirect = webView.getDeclaredMethod(transformMethod, Uri.class);
        if (redirect.getReturnType() != Uri.class
                || !Modifier.isStatic(redirect.getModifiers())) {
            throw new NoSuchMethodException(
                    webViewClass + "#" + transformMethod + "(Uri) static Uri shape changed");
        }
        return redirect;
    }

    /**
     * The dispatch method's first parameter is a presenter interface whose obfuscated name rotates
     * every release and whose last parameter is likewise unstable, so pin the middle four types
     * and the return type — exactly what structural preflight matched.
     */
    private static Method findDispatch(Class<?> companion, String name) {
        for (Method candidate : companion.getDeclaredMethods()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (name.equals(candidate.getName())
                    && candidate.getReturnType() == Void.TYPE
                    && Modifier.isStatic(candidate.getModifiers())
                    && parameters.length == 6
                    && parameters[0].isInterface()
                    && parameters[1] == String.class
                    && parameters[2] == Bundle.class
                    && parameters[3] == Boolean.TYPE
                    && parameters[4] == Integer.TYPE
                    && parameters[5].isInterface()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Primary path: the dispatch host exposes the activity/view Context through a no-arg getter.
     * The getter's own name drifts, so candidates are found by shape and the first one returning a
     * live Context wins. The running application is the stable fallback; with
     * {@link Intent#FLAG_ACTIVITY_NEW_TASK} either is a valid launch identity. No context means no
     * safe external open: leave Zalo's own path alone.
     */
    private Context resolveHostContext(Object host) {
        if (host instanceof Context) {
            return (Context) host;
        }
        if (host != null) {
            Class<?> current = host.getClass();
            while (current != null && current != Object.class) {
                for (Method method : current.getDeclaredMethods()) {
                    if (method.getParameterTypes().length != 0
                            || method.getReturnType() != Context.class) {
                        continue;
                    }
                    try {
                        method.setAccessible(true);
                        Object result = method.invoke(host);
                        if (result instanceof Context) {
                            return (Context) result;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                current = current.getSuperclass();
            }
        }
        return AndroidAppHelper.currentApplication();
    }
}
