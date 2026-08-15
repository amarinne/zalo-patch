package com.ez.zalopatch.xposed.core;

import com.ez.zalopatch.DiagnosticsState;
import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.ZaloArtifactState;
import com.ez.zalopatch.xposed.features.BottomTabsFeature;
import com.ez.zalopatch.xposed.features.CallRecordingProbeFeature;
import com.ez.zalopatch.xposed.features.CallRecordingFeature;
import com.ez.zalopatch.xposed.features.ChatFeature;
import com.ez.zalopatch.xposed.features.InboxFeature;
import com.ez.zalopatch.xposed.features.InteractionTraceFeature;
import com.ez.zalopatch.xposed.features.MeCleanupFeature;
import com.ez.zalopatch.xposed.features.NotificationFeature;
import com.ez.zalopatch.xposed.features.RuntimeDiscoveryFeature;
import com.ez.zalopatch.xposed.features.SymbolSchemaHealthFeature;
import com.ez.zalopatch.xposed.features.TelemetryFeature;
import com.ez.zalopatch.xposed.features.ZinstantFeature;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

public final class MainFeatures {
    private static final String FEATURE_RUNTIME_DISCOVERY = "runtime_discovery";

    private MainFeatures() {
    }

    public static void start(ClassLoader classLoader, boolean mainProcess) {
        if (mainProcess) {
            runFeature(new SymbolSchemaHealthFeature(classLoader));
        }
        List<Feature> features = new ArrayList<>();
        features.add(new NotificationFeature(classLoader));
        ZaloArtifactState.Compatibility artifact = ZaloArtifactState.forHooks(
                HookConfig.resolveFallbackContextForHooks());
        features.add(new TelemetryFeature(classLoader, artifact.compatible));
        features.add(new InteractionTraceFeature(classLoader));
        if (artifact.compatible) {
            SymbolPreflight.Result preflight = SymbolPreflight.inspect(
                    SymbolSchema.activeForHooks(HookConfig.resolveFallbackContextForHooks()),
                    classLoader);
            boolean bottomEnabled = HookConfig.isEnabled(Tweaks.KEY_HIDE_DISCOVERY_TAB)
                    || HookConfig.isEnabled(Tweaks.KEY_HIDE_TIMELINE_TAB)
                    || HookConfig.isEnabled(Tweaks.KEY_KEEP_GROUP_TAB)
                    || HookConfig.isEnabled(Tweaks.KEY_FORCE_MESSAGES_AS_HOME);
            if (preflight.bottomTabs || !bottomEnabled) {
                features.add(new BottomTabsFeature(classLoader));
            } else {
                SelfCheckRegistry.markStale("bottom_tabs.state", "structural preflight",
                        preflight.reason(preflight.bottomErrors));
                SelfCheckRegistry.markStale("bottom_tabs.consumers", "structural preflight",
                        preflight.reason(preflight.bottomErrors));
            }
            addInbox(features, classLoader, preflight);
            addMeCleanup(features, classLoader, preflight);
            features.add(new ZinstantFeature(classLoader,
                    preflight.zinstantMessage, preflight.reason(preflight.zinstantMessageErrors),
                    preflight.zinstantFeed, preflight.reason(preflight.zinstantFeedErrors)));
            features.add(new ChatFeature(classLoader));
            features.add(new CallRecordingFeature(classLoader));
            features.add(new CallRecordingProbeFeature(classLoader));
        } else {
            SelfCheckRegistry.markStatus("zalo_artifact",
                    "failed".equals(artifact.status) ? "failed" : "stale",
                    "exact artifact profile",
                    artifact.reason, "");
        }
        maybeAddRuntimeDiscovery(features, classLoader);

        for (Feature feature : features) {
            runFeature(feature);
        }
    }

    private static void addInbox(List<Feature> features, ClassLoader classLoader,
                                 SymbolPreflight.Result preflight) {
        boolean hideMedia = HookConfig.isEnabled(Tweaks.KEY_HIDE_MEDIA_BOX);
        boolean filterCategories = HookConfig.isEnabled(Tweaks.KEY_FILTER_POPOVER_CATEGORIES);
        features.add(new InboxFeature(classLoader,
                !hideMedia || preflight.inboxMedia,
                preflight.reason(preflight.inboxMediaErrors),
                !filterCategories || preflight.inboxCategories,
                preflight.reason(preflight.inboxCategoryErrors)));
    }

    private static void addMeCleanup(List<Feature> features, ClassLoader classLoader,
                                     SymbolPreflight.Result preflight) {
        boolean qr = HookConfig.isEnabled(Tweaks.KEY_HIDE_QR_WALLET);
        boolean cloud = HookConfig.isEnabled(Tweaks.KEY_HIDE_ZCLOUD);
        boolean style = HookConfig.isEnabled(Tweaks.KEY_HIDE_ZSTYLE);
        boolean business = HookConfig.isEnabled(Tweaks.KEY_HIDE_ZBUSINESS);
        if (preflight.me || (!qr && !cloud && !style && !business)) {
            features.add(new MeCleanupFeature(classLoader));
            return;
        }
        String reason = preflight.reason(preflight.meErrors);
        markMeItem("me_cleanup.qr_wallet", qr, "QR Wallet", reason);
        markMeItem("me_cleanup.zcloud", cloud, "zCloud", reason);
        markMeItem("me_cleanup.zstyle", style, "zStyle", reason);
        markMeItem("me_cleanup.zbusiness", business, "zBusiness", reason);
        SelfCheckRegistry.markStale("me_cleanup.items", "structural preflight", reason);
        SelfCheckRegistry.markStale("me_cleanup.refresh", "structural preflight", reason);
        SelfCheckRegistry.markStale("me_cleanup.visible_rows", "structural preflight", reason);
    }

    private static void markMeItem(String feature, boolean enabled, String label, String reason) {
        if (enabled) {
            SelfCheckRegistry.markStale(feature, "structural preflight", reason);
        } else {
            SelfCheckRegistry.markDisabled(feature, label);
        }
    }

    private static void maybeAddRuntimeDiscovery(List<Feature> features, ClassLoader classLoader) {
        boolean requested = HookConfig.getRawBoolean(DiagnosticsState.KEY_RUNTIME_DISCOVERY_REQUESTED, false);
        long installedVersionCode = SymbolSchema.installedZaloVersionCode(HookConfig.resolveFallbackContextForHooks());
        long lastVersionCode = HookConfig.getRawLong(DiagnosticsState.KEY_RUNTIME_DISCOVERY_LAST_VERSION_CODE, -1L);
        if (requested && installedVersionCode > 0L && installedVersionCode != lastVersionCode) {
            features.add(new RuntimeDiscoveryFeature(classLoader));
            return;
        }
        String detail = requested
                ? "requested=true installed=" + installedVersionCode + " last=" + lastVersionCode
                : "requested=false installed=" + installedVersionCode + " last=" + lastVersionCode;
        SelfCheckRegistry.markStatus(FEATURE_RUNTIME_DISCOVERY, "disabled",
                "explicit request required", detail, "");
    }

    private static void runFeature(Feature feature) {
        try {
            feature.doHook();
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed("feature." + feature.getFeatureName(),
                    feature.getFeatureName(), throwable);
            XposedBridge.log("ZaloPatch: [" + feature.getFeatureName() + "] failed: " + throwable);
        }
    }

}
