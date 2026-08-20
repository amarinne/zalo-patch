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
 * Two independent status-privacy tweaks, each blocking exactly one outbound signal — nothing here
 * touches how the app renders or fetches <i>other people's</i> seen/typing status, only what this
 * account tells the server about itself.
 */
public final class StatusPrivacyFeature extends Feature {
    private static final String FEATURE_SEEN = "messages.block_seen_status";
    private static final String FEATURE_SEEN_ENQUEUE = "messages.block_seen_status.enqueue";
    private static final String FEATURE_TYPING = "messages.block_typing_status";

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
    }

    /**
     * The queue this flushes over the socket carries both delivered and seen acks in one batch.
     * Blocking the whole flush — the first cut of this feature — hid delivered too, so the other
     * side couldn't even tell the message had arrived.
     *
     * <p>The second cut filtered on "entry type == 3" (the value {@code h()}'s enqueue hardcodes
     * for a seen entry) and, per live testing, still let everything through: {@code Lje0/k0;->j
     * (ArrayList)V} runs immediately before this method on the very same list and rewrites every
     * type-3 entry to type 1 as a side effect, so by the time this hook inspects the batch, no
     * entry is ever still type 3. Rather than chase what a "seen" entry is currently labeled after
     * that mutation, this keeps only {@code type == 2} (delivered, which {@code j()} never
     * touches) and drops everything else — unambiguous regardless of what value {@code j()} leaves
     * on the ones that started as seen.
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
        int deliveredTypeValue = SymbolSchema.integer(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.delivered_entry_type_value", 2);
        runGuarded("seen-status block", FEATURE_SEEN, className + "#" + methodName, () ->
                XposedHelpers.findAndHookMethod(className, classLoader, methodName,
                        ArrayList.class, boolean.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                List<?> batch = (List<?>) param.args[0];
                                ArrayList<Object> deliveredOnly = new ArrayList<>(batch.size());
                                for (Object entry : batch) {
                                    int type = XposedHelpers.getIntField(entry, typeField);
                                    if (type == deliveredTypeValue) {
                                        deliveredOnly.add(entry);
                                    }
                                    log("seen flush entry: type=" + type);
                                }
                                if (deliveredOnly.size() == batch.size()) {
                                    return;
                                }
                                SelfCheckRegistry.incrementHit(FEATURE_SEEN, className + "#" + methodName,
                                        "dropped " + (batch.size() - deliveredOnly.size())
                                                + " seen ack(s), kept " + deliveredOnly.size()
                                                + " delivered ack(s)");
                                if (deliveredOnly.isEmpty()) {
                                    param.setResult(null);
                                    return;
                                }
                                param.args[0] = deliveredOnly;
                            }
                        }));
        installSeenEnqueueDiagnostic();
    }

    /**
     * Diagnostic-only, no-op hook. Logs unconditionally (not gated behind the self-check
     * ConfigProvider round trip, which has an independent, unrelated reliability issue) every time
     * something is queued for a seen/delivered ack, so a live test can tell whether the enqueue side
     * (this method) or the flush side ({@code i()}, hooked above) is where a report of "nothing got
     * blocked" actually breaks down.
     */
    private void installSeenEnqueueDiagnostic() {
        String className = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.send_seen_manager_class", "je0.k0");
        String methodName = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.seen_enqueue_method", "h");
        String messageClass = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.seen_message_class", "o00.q");
        runGuarded("seen-status enqueue diagnostic", FEATURE_SEEN_ENQUEUE,
                className + "#" + methodName, () -> {
                    Class<?> messageType = XposedHelpers.findClass(messageClass, classLoader);
                    XposedHelpers.findAndHookMethod(className, classLoader, methodName,
                            String.class, messageType, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    log("seen enqueue called: uid=" + param.args[0]);
                                    SelfCheckRegistry.incrementHit(FEATURE_SEEN_ENQUEUE,
                                            className + "#" + methodName, "enqueue observed");
                                }
                            });
                });
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
                                log("typing send called: isGroup=" + param.args[2]);
                                param.setResult(null);
                                SelfCheckRegistry.incrementHit(FEATURE_TYPING, className + "#" + methodName,
                                        "blocked typing indicator send");
                            }
                        }));
    }
}
