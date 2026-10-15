package com.ez.zalopatch.xposed.features;

import android.view.View;
import android.view.ViewGroup;

import com.ez.zalopatch.DexKitVideoAdsFingerprint;
import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;
import com.ez.zalopatch.xposed.core.VideoAdsTargets;
import com.ez.zalopatch.xposed.core.XpHooks;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Collapses qualified ad roots after native construction and binding without skipping host work. */
public final class VideoAdsFeature extends Feature {
    private final Map<DexKitVideoAdsFingerprint.Route, Boolean> ready;

    public VideoAdsFeature(ClassLoader loader,
                          Map<DexKitVideoAdsFingerprint.Route, Boolean> ready) {
        super(loader);
        this.ready = new EnumMap<>(DexKitVideoAdsFingerprint.Route.class);
        if (ready != null) this.ready.putAll(ready);
    }

    @Override public String getFeatureName() { return "VideoAdsFeature"; }

    @Override public void doHook() {
        boolean enabled = HookConfig.isEnabled(Tweaks.KEY_HIDE_VIDEO_ADS);
        SymbolSchema.Active schema = SymbolSchema.activeForHooks(HookConfig.resolveFallbackContextForHooks());
        for (DexKitVideoAdsFingerprint.Route route : DexKitVideoAdsFingerprint.Route.values()) {
            String target = route.ownerSuffix;
            if (!enabled) {
                SelfCheckRegistry.markDisabled(route.row(), target);
                continue;
            }
            if (!Boolean.TRUE.equals(ready.get(route))) {
                SelfCheckRegistry.markStale(route.row(), target,
                        "complete exact or current-host discovery bindings required");
                continue;
            }
            Map<String, String> bindings = VideoAdsTargets.bindings(schema, route);
            List<String> errors = new ArrayList<>();
            if (!VideoAdsTargets.check(classLoader, route, bindings, errors)) {
                SelfCheckRegistry.markStale(route.row(), target, String.join("; ", errors));
                continue;
            }
            List<XpHooks.Handle> handles = new ArrayList<>();
            AtomicBoolean armed = new AtomicBoolean();
            try {
                List<Class<?>> owners = VideoAdsTargets.owners(classLoader, route, bindings);
                // Resolve the complete route before installing any callbacks.
                Map<Class<?>, List<java.lang.reflect.Method>> binders = new java.util.LinkedHashMap<>();
                for (Class<?> owner : owners) {
                    VideoAdsTargets.constructor(owner);
                    binders.put(owner, VideoAdsTargets.binders(classLoader, route, bindings, owner));
                }
                final String label = bindings.get(route.anchor("view_class"));
                AtomicBoolean reported = new AtomicBoolean();
                AtomicBoolean failed = new AtomicBoolean();
                XpHooks.After collapse = param -> {
                    if (!armed.get() || failed.get()) return;
                    try {
                        if (!param.hasThrowable() && param.thisObject != null
                                && owners.contains(param.thisObject.getClass())
                                && collapse((View) param.thisObject)
                                && reported.compareAndSet(false, true)) {
                            SelfCheckRegistry.markSuppressed(route.row(), label,
                                    "dedicated ad root collapsed after native work");
                        }
                    } catch (Throwable throwable) {
                        if (failed.compareAndSet(false, true)) {
                            SelfCheckRegistry.markFailed(route.row(), label, throwable);
                        }
                    }
                };
                for (Class<?> owner : owners) {
                    handles.add(XpHooks.hookConstructor(route.row(),
                            VideoAdsTargets.constructor(owner), null, collapse));
                    for (java.lang.reflect.Method binder : binders.get(owner)) {
                        handles.add(XpHooks.hookMethod(route.row(), binder, null, collapse));
                    }
                }
                armed.set(true);
                SelfCheckRegistry.markInstalled(route.row(), label, handles.size());
            } catch (Throwable throwable) {
                armed.set(false);
                for (XpHooks.Handle handle : handles) handle.unhook();
                SelfCheckRegistry.markFailed(route.row(), target, throwable);
            }
        }
    }

    /** Count only an actual state change. Repeated binds of an already collapsed root are not hits. */
    static boolean collapse(View view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        boolean changed = view.getVisibility() != View.GONE
                || (params != null && params.height != 0);
        if (!changed) return false;
        view.setVisibility(View.GONE);
        if (params != null && params.height != 0) {
            params.height = 0;
            view.setLayoutParams(params);
        }
        return view.getVisibility() == View.GONE && (params == null || params.height == 0);
    }
}
