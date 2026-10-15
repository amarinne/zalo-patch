package com.ez.zalopatch.xposed.features;

import com.ez.zalopatch.DexKitRemoteConfigFingerprint;
import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;
import com.ez.zalopatch.xposed.core.XpHooks;
import com.ez.zalopatch.xposed.core.XpLog;
import com.ez.zalopatch.xposed.core.XpReflect;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Overrides only Zalo's two keyed Story/community sponsored-placement gates. */
public final class SponsoredPlacementsFeature extends Feature {
    private static final String FEATURE = "ads.sponsored_placements";
    private final boolean preflight;
    private final String preflightError;

    public SponsoredPlacementsFeature(ClassLoader classLoader, boolean preflight,
                                      String preflightError) {
        super(classLoader);
        this.preflight = preflight;
        this.preflightError = preflightError == null ? "" : preflightError;
    }

    @Override
    public String getFeatureName() {
        return "SponsoredPlacements";
    }

    @Override
    public void doHook() {
        try {
            installHook();
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE, "keyed config integer reader", throwable);
            XpLog.e("ZaloPatch: sponsored-placement feature failed", throwable);
        }
    }

    private void installHook() {
        if (!HookConfig.isEnabled(Tweaks.KEY_DISABLE_SPONSORED_PLACEMENTS)) {
            SelfCheckRegistry.markDisabled(FEATURE, "keyed config integer reader");
            return;
        }
        if (!preflight) {
            SelfCheckRegistry.markStale(FEATURE, "structural preflight",
                    preflightError.isEmpty() ? "required config getter is unavailable"
                            : preflightError);
            return;
        }

        SymbolSchema.Active schema = SymbolSchema.activeForHooks(
                HookConfig.resolveModuleContextForHooks());
        String ownerName = schema.string(
                DexKitRemoteConfigFingerprint.ANCHOR_READER_CLASS, "");
        String methodName = schema.string(
                DexKitRemoteConfigFingerprint.ANCHOR_READER_METHOD, "");
        String target = ownerName + "#" + methodName + "(String,int)int";
        if (ownerName.isEmpty() || methodName.isEmpty()) {
            SelfCheckRegistry.markStale(FEATURE, "symbols.remote_config.int_reader",
                    "required config getter is unavailable");
            return;
        }

        try {
            Class<?> owner;
            try {
                owner = XpReflect.findClass(ownerName, classLoader);
            } catch (ClassNotFoundException missing) {
                SelfCheckRegistry.markStale(FEATURE, target,
                        "required config getter class is missing");
                return;
            }
            Method reader;
            try {
                reader = owner.getDeclaredMethod(methodName, String.class, Integer.TYPE);
            } catch (NoSuchMethodException missing) {
                SelfCheckRegistry.markStale(FEATURE, target,
                        "required config getter method is missing");
                return;
            }
            if (reader.getReturnType() != Integer.TYPE
                    || !Modifier.isStatic(reader.getModifiers())) {
                SelfCheckRegistry.markStale(FEATURE, target,
                        "config getter signature changed after preflight");
                return;
            }
            reader.setAccessible(true);
            XpHooks.hookMethod(FEATURE, reader, new XpHooks.After() {
                @Override
                public void after(XpHooks.HookParam param) {
                    try {
                        if (!HookConfig.isEnabled(Tweaks.KEY_DISABLE_SPONSORED_PLACEMENTS)
                                || param.hasThrowable() || param.args == null
                                || param.args.length != 2
                                || !(param.args[0] instanceof String)
                                || !(param.args[1] instanceof Number)
                                || !(param.getResult() instanceof Number)) {
                            return;
                        }
                        String key = (String) param.args[0];
                        if (!DexKitRemoteConfigFingerprint.isSponsoredPlacementKey(key)) {
                            return;
                        }
                        param.setResult(0);
                        String placement = DexKitRemoteConfigFingerprint.STORY_KEY.equals(key)
                                ? "Story sponsored placement" : "community sponsored placement";
                        SelfCheckRegistry.markSuppressed(FEATURE, target, placement);
                    } catch (Throwable throwable) {
                        SelfCheckRegistry.markFailed(FEATURE, target, throwable);
                        XpLog.e("ZaloPatch: sponsored-placement config override failed",
                                throwable);
                    }
                }
            });
            SelfCheckRegistry.markInstalled(FEATURE, target, 1);
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE, target, throwable);
            XpLog.e("ZaloPatch: sponsored-placement config reader hook failed", throwable);
        }
    }
}
