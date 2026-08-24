package com.ez.zalopatch.xposed.features;

import android.content.Context;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Extends Zalo's app-wide passcode lock grace period from the module setting. */
public final class PasscodeGraceFeature extends Feature {
    private static final String FEATURE = "security.passcode_grace";
    private static final String PREF_KEY = "SaveActiveTimePasscodeSetting";

    public PasscodeGraceFeature(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getFeatureName() {
        return "PasscodeGrace";
    }

    @Override
    public void doHook() {
        if (!HookConfig.isEnabled(Tweaks.KEY_PASSCODE_GRACE)) {
            // Off means staying off the shared preference reader entirely.
            SelfCheckRegistry.markDisabled(FEATURE, "passcode grace read");
            return;
        }
        final int chosen = HookConfig.getLevel(Tweaks.KEY_PASSCODE_GRACE_MS);
        Context context = HookConfig.resolveModuleContextForHooks();
        SymbolSchema.Active schema = SymbolSchema.activeForHooks(context);
        String readerClass = schema.string("symbols.passcode.prefs_int_reader_class", "");
        String readerMethod = schema.string("symbols.passcode.prefs_int_reader_method", "");
        String target = "source=" + schema.source + " " + readerClass + "#" + readerMethod
                + "(I,String,Z)I";
        runGuarded("passcode grace", FEATURE, target, () ->
                XposedHelpers.findAndHookMethod(readerClass, classLoader, readerMethod,
                        int.class, String.class, boolean.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                // This reader is hot. Keep the key comparison first and do
                                // nothing else on the non-matching path.
                                if (param.args == null || param.args.length < 2
                                        || !PREF_KEY.equals(param.args[1])) {
                                    return;
                                }
                                if (param.hasThrowable()
                                        || !(param.getResult() instanceof Number)) {
                                    return;
                                }
                                int returned = ((Number) param.getResult()).intValue();
                                int effective = effectiveResult(returned, chosen);
                                if (effective == returned) {
                                    return;
                                }
                                param.setResult(effective);
                                SelfCheckRegistry.incrementHit(FEATURE,
                                        readerClass + "#" + readerMethod,
                                        "grace extended to " + effective + " ms");
                            }
                        }));
    }

    static int effectiveResult(int returned, int chosen) {
        return chosen > 0 && chosen != returned ? chosen : returned;
    }
}
