package com.ez.zalopatch.xposed.features;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;
import com.ez.zalopatch.xposed.core.XpHooks;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hides the ZCloud banner in the message list without the legacy resource hook.
 *
 * <p>LibXposed API 102 exposes no layout-inflation resource contract, so the banner is
 * located through the stable Android framework boundary instead: every
 * {@link LayoutInflater#inflate} call in the Zalo process passes through an after-hook
 * that hides {@code fixed_banner_container} when the inflated layout is
 * {@code messageslist} and {@code inbox.hide_zcloud_banner} is enabled. The layout id
 * comparison keeps the per-inflation cost to one integer check on every other layout.
 *
 * <p>The inflate hook stays installed even when the setting is off so the runtime
 * capability report keeps its observed/unavailable signal; behavior stays gated on
 * the setting.
 */
public final class ZcloudBannerFeature extends Feature {
    private static final String FEATURE = Tweaks.KEY_HIDE_ZCLOUD_BANNER;
    private static final String TARGET_PACKAGE = "com.zing.zalo";
    private static final String MESSAGES_LIST_LAYOUT = "messageslist";
    private static final String BANNER_CONTAINER_ID = "fixed_banner_container";
    private static final AtomicBoolean OBSERVED = new AtomicBoolean(false);
    private static final Object IDS_LOCK = new Object();
    private static volatile boolean idsResolved;
    private static volatile int messagesListId;
    private static volatile int bannerContainerId;

    public ZcloudBannerFeature(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getFeatureName() {
        return "ZcloudBanner";
    }

    /** True once layout inflation has been seen in this process. */
    public static boolean observed() {
        return OBSERVED.get();
    }

    @Override
    public void doHook() {
        XpHooks.After onInflated = new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) {
                OBSERVED.set(true);
                if (!HookConfig.isEnabled(FEATURE)) {
                    return;
                }
                if (!(param.getResult() instanceof View)
                        || param.args == null || param.args.length == 0
                        || !(param.args[0] instanceof Integer)) {
                    return;
                }
                View root = (View) param.getResult();
                ensureIds(root);
                if (messagesListId == 0
                        || ((Integer) param.args[0]).intValue() != messagesListId
                        || bannerContainerId == 0) {
                    return;
                }
                View banner = root.findViewById(bannerContainerId);
                if (banner != null && banner.getVisibility() != View.GONE) {
                    hideView(banner);
                    SelfCheckRegistry.incrementHit(FEATURE,
                            "LayoutInflater#inflate(messageslist/fixed_banner_container)",
                            "banner hidden");
                }
            }
        };
        List<XpHooks.Handle> installed =
                XpHooks.hookAllMethods("banner", LayoutInflater.class, "inflate", onInflated);
        if (installed.isEmpty()) {
            SelfCheckRegistry.markStale(FEATURE,
                    "LayoutInflater#inflate(messageslist/fixed_banner_container)",
                    "no inflate overloads found");
            return;
        }
        if (!HookConfig.isEnabled(FEATURE)) {
            SelfCheckRegistry.markDisabled(FEATURE, "ZCloud banner hide");
            return;
        }
        SelfCheckRegistry.markInstalled(FEATURE,
                "LayoutInflater#inflate(messageslist/fixed_banner_container)",
                installed.size());
    }

    private static void ensureIds(View root) {
        if (idsResolved) {
            return;
        }
        synchronized (IDS_LOCK) {
            if (idsResolved) {
                return;
            }
            try {
                messagesListId = root.getResources().getIdentifier(
                        MESSAGES_LIST_LAYOUT, "layout", TARGET_PACKAGE);
                bannerContainerId = root.getResources().getIdentifier(
                        BANNER_CONTAINER_ID, "id", TARGET_PACKAGE);
            } catch (Throwable ignored) {
            }
            // Early framework layouts can use a Resources instance without Zalo's
            // asset path. Retry on later inflations instead of caching zero forever.
            idsResolved = messagesListId != 0 && bannerContainerId != 0;
        }
    }

    private static void hideView(View view) {
        view.setVisibility(View.GONE);
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams != null) {
            layoutParams.height = 0;
            view.setLayoutParams(layoutParams);
        }
    }
}
