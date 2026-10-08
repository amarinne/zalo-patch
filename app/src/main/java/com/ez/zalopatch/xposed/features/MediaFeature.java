package com.ez.zalopatch.xposed.features;

import android.content.Context;

import com.ez.zalopatch.DexKitMediaFingerprint;
import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import java.lang.reflect.Method;

import com.ez.zalopatch.xposed.core.XpHooks;
import com.ez.zalopatch.xposed.core.XpReflect;

/**
 * Keeps on-device chat big files usable after Zalo marks them expired.
 *
 * <p>The host classifies each big file through a static method returning an expiry-state
 * enum. When that classifier reports expired, this feature maps the result back to
 * not-expired so the existing local file keeps opening. It never restores missing
 * files, never touches the network, and stays inert while the symbols are unmapped.
 */
public final class MediaFeature extends Feature {
    private static final String FEATURE_STATE = "messages.keep_expired_media";

    public MediaFeature(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getFeatureName() {
        return "Media";
    }

    @Override
    public void doHook() {
        if (!HookConfig.isEnabled(Tweaks.KEY_KEEP_EXPIRED_MEDIA)) {
            SelfCheckRegistry.markDisabled(FEATURE_STATE, "expired big-file state rewrite");
            return;
        }
        Context context = HookConfig.resolveModuleContextForHooks();
        SymbolSchema.Active schema = SymbolSchema.activeForHooks(context);
        String stateClass = schema.string(
                DexKitMediaFingerprint.ANCHOR_STATE_CLASS, "");
        String classifierClass = schema.string(
                DexKitMediaFingerprint.ANCHOR_CLASSIFIER_CLASS, "");
        String classifierMethod = schema.string(
                DexKitMediaFingerprint.ANCHOR_CLASSIFIER_METHOD, "");
        String target = "source=" + schema.source + " "
                + classifierClass + "#" + classifierMethod + "()";
        runGuarded("expired media state", FEATURE_STATE, target, () -> {
            Class<?> state = XpReflect.findClass(stateClass, classLoader);
            Object expired = enumConstant(state, DexKitMediaFingerprint.STATE_EXPIRED);
            Object fresh = enumConstant(state, DexKitMediaFingerprint.STATE_NOT_EXPIRED);
            Method classifier = null;
            for (Class<?> current = XpReflect.findClass(classifierClass, classLoader);
                 current != null; current = current.getSuperclass()) {
                for (Method candidate : current.getDeclaredMethods()) {
                    if (classifierMethod.equals(candidate.getName())
                            && java.lang.reflect.Modifier.isStatic(candidate.getModifiers())
                            && candidate.getReturnType() == state) {
                        classifier = candidate;
                        break;
                    }
                }
                if (classifier != null) {
                    break;
                }
            }
            if (classifier == null) {
                throw new NoSuchMethodException(
                        classifierClass + "#" + classifierMethod + " not found");
            }
            classifier.setAccessible(true);
            final Object expiredState = expired;
            final Object freshState = fresh;
            XpHooks.hookMethod(FEATURE_STATE, classifier, new XpHooks.After() {
                @Override
                public void after(XpHooks.HookParam param) {
                    if (expiredState.equals(param.getResult())) {
                        param.setResult(freshState);
                        SelfCheckRegistry.incrementHit(FEATURE_STATE,
                                classifierClass + "#" + classifierMethod,
                                "expired state kept accessible");
                    }
                }
            });
            SelfCheckRegistry.markInstalled(FEATURE_STATE, target, 1);
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> state, String name) {
        if (!state.isEnum()) {
            throw new IllegalArgumentException(state.getName() + " is not an enum");
        }
        return Enum.valueOf((Class) state, name);
    }
}
