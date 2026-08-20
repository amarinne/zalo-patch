package com.ez.zalopatch.xposed.features;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Suppresses the two outbound status packets Zalo's chat layer sends on its own: the read/seen
 * receipt flush (also the funnel the delivery ack goes through) and the typing indicator. Both are
 * blocked at the send call, after the local UI has already updated from the user's own action, so
 * the app still shows messages as read locally and the input box still works normally — only the
 * network packet that would tell the other side is dropped.
 *
 * <p>Online status is different: visibility is a stored server-side privacy preference set through
 * Zalo's own native "Hiện trạng thái truy cập" toggle, not a packet this module can intercept. That
 * native toggle already works correctly on its own. What it does <i>not</i> do is let you keep it
 * off one-way: Zalo's client enforces it symmetrically, gating whether you can see friends' status
 * on the same local flag that gates whether you show yours. {@link #installOnlineStatusBypass()}
 * forces that one local read to always report "enabled," so friends' status stays visible locally
 * regardless of what the user chose for their own visibility.
 */
public final class StatusPrivacyFeature extends Feature {
    private static final String FEATURE_SEEN = "messages.block_seen_status";
    private static final String FEATURE_TYPING = "messages.block_typing_status";
    private static final String FEATURE_ONLINE_STATUS = "messages.always_see_online_status";

    public StatusPrivacyFeature(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getFeatureName() {
        return "StatusPrivacy";
    }

    @Override
    public void doHook() {
        installSeenBlock();
        installTypingBlock();
        installOnlineStatusBypass();
    }

    private void installSeenBlock() {
        if (!HookConfig.isEnabled(Tweaks.KEY_BLOCK_SEEN_STATUS)) {
            SelfCheckRegistry.markDisabled(FEATURE_SEEN, "seen/delivered ack send");
            return;
        }
        String className = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.send_seen_manager_class", "je0.k0");
        String methodName = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.send_seen_flush_method", "i");
        runGuarded("seen-status block", FEATURE_SEEN, className + "#" + methodName, () ->
                XposedHelpers.findAndHookMethod(className, classLoader, methodName,
                        ArrayList.class, boolean.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                param.setResult(null);
                                SelfCheckRegistry.incrementHit(FEATURE_SEEN, className + "#" + methodName,
                                        "blocked seen/delivered ack send");
                            }
                        }));
    }

    private void installTypingBlock() {
        if (!HookConfig.isEnabled(Tweaks.KEY_BLOCK_TYPING_STATUS)) {
            SelfCheckRegistry.markDisabled(FEATURE_TYPING, "typing indicator send");
            return;
        }
        String className = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.send_typing_class", "l00.r");
        String methodName = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.send_typing_method", "S");
        runGuarded("typing-status block", FEATURE_TYPING, className + "#" + methodName, () ->
                XposedHelpers.findAndHookMethod(className, classLoader, methodName,
                        String.class, int.class, boolean.class, boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                param.setResult(null);
                                SelfCheckRegistry.incrementHit(FEATURE_TYPING, className + "#" + methodName,
                                        "blocked typing indicator send");
                            }
                        }));
    }

    private void installOnlineStatusBypass() {
        if (!HookConfig.isEnabled(Tweaks.KEY_ALWAYS_SEE_ONLINE_STATUS)) {
            SelfCheckRegistry.markDisabled(FEATURE_ONLINE_STATUS, "online-status visibility gate");
            return;
        }
        String className = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.online_status_flag_class", "yz.j");
        String methodName = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.online_status_flag_method", "j2");
        runGuarded("online-status bypass", FEATURE_ONLINE_STATUS, className + "#" + methodName, () ->
                XposedHelpers.findAndHookMethod(className, classLoader, methodName,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                param.setResult(Boolean.TRUE);
                                SelfCheckRegistry.incrementHit(FEATURE_ONLINE_STATUS,
                                        className + "#" + methodName,
                                        "forced online-status visibility gate on");
                            }
                        }));
    }
}
