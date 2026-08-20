package com.ez.zalopatch.xposed.features;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Three independent status-privacy tweaks, each blocking (or, for online status, actually
 * submitting) exactly one outbound signal — nothing here touches how the app renders or fetches
 * <i>other people's</i> seen/typing/online status, only what this account tells the server about
 * itself.
 */
public final class StatusPrivacyFeature extends Feature {
    private static final String FEATURE_SEEN = "messages.block_seen_status";
    private static final String FEATURE_TYPING = "messages.block_typing_status";
    private static final String FEATURE_ONLINE_STATUS = "messages.hide_online_status";

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
        installOnlineStatusHide();
    }

    /**
     * The queue this flushes over the socket carries both delivered acks (entry type 2) and seen
     * acks (entry type 3) together in one batch. Blocking the whole flush — the first cut of this
     * feature — hid delivered too, so the other side couldn't even tell the message had arrived.
     * Instead, filter seen-typed entries out of the batch and let the delivered ones send exactly
     * as Zalo intended; the local "message read" UI update already happened before this call runs,
     * so removing an entry here only stops the network side from finding out.
     */
    private void installSeenBlock() {
        if (!HookConfig.isEnabled(Tweaks.KEY_BLOCK_SEEN_STATUS)) {
            SelfCheckRegistry.markDisabled(FEATURE_SEEN, "seen ack send");
            return;
        }
        String className = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.send_seen_manager_class", "je0.k0");
        String methodName = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.send_seen_flush_method", "i");
        String typeField = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.seen_entry_type_field", "a");
        int seenTypeValue = SymbolSchema.integer(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.seen_entry_type_value", 3);
        runGuarded("seen-status block", FEATURE_SEEN, className + "#" + methodName, () ->
                XposedHelpers.findAndHookMethod(className, classLoader, methodName,
                        ArrayList.class, boolean.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                List<?> batch = (List<?>) param.args[0];
                                ArrayList<Object> withoutSeen = new ArrayList<>(batch.size());
                                for (Object entry : batch) {
                                    if (XposedHelpers.getIntField(entry, typeField) != seenTypeValue) {
                                        withoutSeen.add(entry);
                                    }
                                }
                                if (withoutSeen.size() == batch.size()) {
                                    return;
                                }
                                SelfCheckRegistry.incrementHit(FEATURE_SEEN, className + "#" + methodName,
                                        "dropped " + (batch.size() - withoutSeen.size())
                                                + " seen ack(s), kept " + withoutSeen.size()
                                                + " delivered ack(s)");
                                if (withoutSeen.isEmpty()) {
                                    param.setResult(null);
                                    return;
                                }
                                param.args[0] = withoutSeen;
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

    /**
     * Online-status visibility is a stored server-side privacy preference, not a live broadcast —
     * there is nothing to intercept. Zalo's own native "Hiện trạng thái truy cập" toggle already
     * submits this correctly; this hook exists only because the native UI path is not reachable for
     * every account/build. Rather than reverse the server's enforcement locally (that approach —
     * forcing the visibility check to always pass — also fooled the toggle that reports whether
     * <i>you</i> are hidden, so it silently stopped working), this submits the exact same request
     * Zalo's own "Online status" bottom sheet sends when you tap it off:
     * {@code Lpn/h0;->q3(settingId, value, extra)}, settingId 0x1b (27), value 0 (hidden), over
     * socket cmd 0x111. It never touches the read side, so seeing everyone else is unaffected.
     */
    private void installOnlineStatusHide() {
        if (!HookConfig.isEnabled(Tweaks.KEY_HIDE_ONLINE_STATUS)) {
            SelfCheckRegistry.markDisabled(FEATURE_ONLINE_STATUS, "online-status privacy save");
            return;
        }
        String className = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.online_status_save_class", "pn.h0");
        String methodName = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.online_status_save_method", "q3");
        int settingId = SymbolSchema.integer(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.online_status_setting_id", 27);
        runGuarded("online-status hide", FEATURE_ONLINE_STATUS, className + "#" + methodName, () -> {
            Class<?> requestClass = XposedHelpers.findClass(className, classLoader);
            Object request = XposedHelpers.newInstance(requestClass);
            XposedHelpers.callMethod(request, methodName, settingId, 0, "");
            SelfCheckRegistry.incrementHit(FEATURE_ONLINE_STATUS, className + "#" + methodName,
                    "submitted online-status hidden (settingId=" + settingId + ")");
        });
    }
}
