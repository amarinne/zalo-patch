package com.ez.zalopatch.xposed.features;

import android.view.View;
import android.view.ViewGroup;

import com.ez.zalopatch.DexKitStoryAdFingerprint;
import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;
import com.ez.zalopatch.xposed.core.XpHooks;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Collapses the dedicated story-ad view after native construction and binding finish. */
public final class StoryAdFeature extends Feature {
    private final boolean compatible;
    private final String compatibilityError;
    private final AtomicBoolean reported = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();
    private volatile boolean armed;

    public StoryAdFeature(ClassLoader loader, boolean compatible, String compatibilityError) {
        super(loader);
        this.compatible = compatible;
        this.compatibilityError = compatibilityError;
    }

    @Override
    public String getFeatureName() { return "Story ads"; }

    @Override
    public void doHook() {
        String feature = Tweaks.KEY_HIDE_STORY_ADS;
        if (!HookConfig.isEnabled(feature)) {
            SelfCheckRegistry.markDisabled(feature, "dedicated story-ad view");
            return;
        }
        if (!compatible) {
            SelfCheckRegistry.markStale(feature, "qualified story-ad binder", compatibilityError);
            return;
        }
        String ownerName = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                DexKitStoryAdFingerprint.ANCHOR_CLASS, "");
        String bindName = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                DexKitStoryAdFingerprint.ANCHOR_BIND, "");
        String target = ownerName + "#" + bindName;
        final Class<?> owner;
        final Method binder;
        try {
            owner = Class.forName(ownerName, false, classLoader);
            binder = DexKitStoryAdFingerprint.binding(owner, bindName);
            if (!View.class.isAssignableFrom(owner) || binder == null) {
                SelfCheckRegistry.markStale(feature, target, "story-ad view shape changed");
                return;
            }
        } catch (Throwable failure) {
            SelfCheckRegistry.markStale(feature, target, failure.getClass().getSimpleName());
            return;
        }
        XpHooks.Handle bindHook = null;
        try {
            XpHooks.After collapse = param -> {
                if (!DexKitStoryAdFingerprint.shouldCollapse(armed && !failed.get(),
                        !param.hasThrowable(), owner, param.thisObject)) return;
                try {
                    if (collapse((View) param.thisObject) && reported.compareAndSet(false, true)) {
                        SelfCheckRegistry.markSuppressed(feature, target, "dedicated ad view collapsed");
                    }
                } catch (Throwable failure) {
                    if (failed.compareAndSet(false, true)) {
                        SelfCheckRegistry.markFailed(feature, target, failure);
                    }
                }
            };
            bindHook = XpHooks.hookMethod(feature, binder, null, collapse);
            int constructors = XpHooks.hookAllConstructors(feature, owner, null, collapse).size();
            armed = true;
            SelfCheckRegistry.markInstalled(feature, target, constructors + 1);
        } catch (Throwable failure) {
            armed = false;
            if (bindHook != null) bindHook.unhook();
            SelfCheckRegistry.markFailed(feature, target, failure);
        }
    }

    private static boolean collapse(View view) {
        ViewGroup.LayoutParams layout = view.getLayoutParams();
        boolean changed = view.getVisibility() != View.GONE || view.getMinimumHeight() != 0
                || (layout != null && layout.height != 0);
        if (!changed) return false;
        view.setVisibility(View.GONE);
        view.setMinimumHeight(0);
        if (layout != null && layout.height != 0) {
            layout.height = 0;
            view.setLayoutParams(layout);
        }
        return view.getVisibility() == View.GONE && view.getMinimumHeight() == 0
                && (view.getLayoutParams() == null || view.getLayoutParams().height == 0);
    }
}
