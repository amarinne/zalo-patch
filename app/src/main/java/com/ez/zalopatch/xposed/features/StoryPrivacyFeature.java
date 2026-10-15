package com.ez.zalopatch.xposed.features;

import com.ez.zalopatch.DexKitStoryFingerprint;
import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;
import com.ez.zalopatch.xposed.core.XpHooks;

/** Leaves watched state and story UI native; skips only the two outbound view requests. */
public final class StoryPrivacyFeature extends Feature {
    public static final String FEATURE_SEEN = "messages.view_stories_anonymously.seen";
    public static final String FEATURE_SUGGESTED = "messages.view_stories_anonymously.suggested";
    private final boolean seenCompatible;
    private final String seenError;
    private final boolean suggestedCompatible;
    private final String suggestedError;

    public StoryPrivacyFeature(ClassLoader loader, boolean seenCompatible, String seenError,
                               boolean suggestedCompatible, String suggestedError) {
        super(loader);
        this.seenCompatible = seenCompatible;
        this.seenError = seenError;
        this.suggestedCompatible = suggestedCompatible;
        this.suggestedError = suggestedError;
    }

    @Override public String getFeatureName() { return "StoryPrivacy"; }

    @Override public void doHook() {
        install(false, FEATURE_SEEN, seenCompatible, seenError);
        install(true, FEATURE_SUGGESTED, suggestedCompatible, suggestedError);
    }

    private void install(boolean suggested, String feature, boolean compatible, String error) {
        if (!HookConfig.isEnabled(Tweaks.KEY_VIEW_STORIES_ANONYMOUSLY)) {
            SelfCheckRegistry.markDisabled(feature, "outbound story view request");
            return;
        }
        if (!compatible) {
            SelfCheckRegistry.markStale(feature, "structural preflight", error);
            return;
        }
        SymbolSchema.Active schema = SymbolSchema.activeForHooks(
                HookConfig.resolveModuleContextForHooks());
        String owner = schema.string(suggested ? DexKitStoryFingerprint.ANCHOR_SUGGESTED_CLASS
                : DexKitStoryFingerprint.ANCHOR_SEEN_CLASS, "");
        String method = schema.string(suggested ? DexKitStoryFingerprint.ANCHOR_SUGGESTED_METHOD
                : DexKitStoryFingerprint.ANCHOR_SEEN_METHOD, "");
        String target = "source=" + schema.source + " " + owner + "#" + method;
        try {
            XpHooks.findAndHookMethod(feature, owner, classLoader, method,
                    DexKitStoryFingerprint.parameterTypes(suggested), new XpHooks.Before() {
                        @Override public void before(XpHooks.HookParam param) {
                            try {
                                param.setResult(null);
                                SelfCheckRegistry.incrementHit(feature, target,
                                        "blocked outbound story view request");
                            } catch (Throwable throwable) {
                                SelfCheckRegistry.markFailed(feature, target, throwable);
                            }
                        }
                    }, null);
            SelfCheckRegistry.markInstalled(feature, target, 1);
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(feature, target, throwable);
        }
    }
}
