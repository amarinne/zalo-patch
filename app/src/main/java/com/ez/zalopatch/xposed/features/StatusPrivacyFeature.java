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
 * Status-privacy tweaks, each blocking (or, for online status, actually submitting) exactly one
 * outbound signal — nothing here touches how the app renders or fetches <i>other people's</i>
 * seen/typing/online status, only what this account tells the server about itself.
 */
public final class StatusPrivacyFeature extends Feature {
    private static final String FEATURE_SEEN = "messages.block_seen_status";
    private static final String FEATURE_SEEN_ENQUEUE = "messages.block_seen_status.enqueue";
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
        installSeenAckShortcutBlock();
        installTypingBlock();
        installOnlineStatusHide();
        installOnlineStatusQueryBypass();
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
                                filterToDeliveredOnly(param, 0, FEATURE_SEEN,
                                        className + "#" + methodName, typeField, deliveredTypeValue);
                            }
                        }));
        installSeenEnqueueDiagnostic();
    }

    /**
     * {@code Lje0/k0} (hooked above) is the batched/debounced ack path, reached when a message
     * becomes visible via the message list's draw pass. {@code Ll00/r;->Q(List, boolean, boolean,
     * boolean)V} is a second, independent path to the exact same socket call
     * ({@code Ls00/x;->e(...)}), used while a conversation is actively open and a new message
     * arrives live — confirmed by field testing: seen status kept reaching the other side for
     * messages read while staying in an already-open conversation, even with the flush above
     * correctly blocking everything for the "open from a notification" case.
     *
     * <p>A first cut filtered {@code Q()}'s list argument the same way as the flush above, by
     * entry type. Live testing showed that was wrong: it started blocking delivered status too,
     * meaning {@code Q()}'s entries don't reliably carry the same {@code Ln00/b}-style type
     * convention the flush's do (or {@code Q()} is used for more than just seen/delivered acks and
     * this module doesn't yet know how to tell them apart). Rather than guess again and risk
     * another regression, this is diagnostic-only for now: it logs each entry's type field
     * (unconditional on the debug flag) and {@code Q()}'s own three boolean parameters, without
     * touching the call, so the next live test can show what a genuine "seen, live, still in
     * conversation" call actually looks like before writing a filter for it.
     */
    private void installSeenAckShortcutBlock() {
        if (!HookConfig.isEnabled(Tweaks.KEY_BLOCK_SEEN_STATUS)) {
            return;
        }
        String className = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.send_typing_class", "l00.r");
        String methodName = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.seen_ack_shortcut_method", "Q");
        String typeField = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.seen_entry_type_field", "a");
        runGuarded("seen-status shortcut diagnostic", FEATURE_SEEN, className + "#" + methodName, () ->
                XposedHelpers.findAndHookMethod(className, classLoader, methodName,
                        List.class, boolean.class, boolean.class, boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                List<?> batch = (List<?>) param.args[0];
                                StringBuilder types = new StringBuilder();
                                for (Object entry : batch) {
                                    if (types.length() > 0) {
                                        types.append(',');
                                    }
                                    try {
                                        types.append(XposedHelpers.getIntField(entry, typeField));
                                    } catch (Throwable unknownShape) {
                                        types.append('?');
                                    }
                                }
                                log("Q() called: batch=" + batch.size() + " types=[" + types
                                        + "] p2=" + param.args[1] + " p3=" + param.args[2]
                                        + " p4=" + param.args[3]);
                            }
                        }));
    }

    /**
     * Shared by both seen-ack hooks: keeps only entries whose {@code typeField} equals
     * {@code deliveredTypeValue} (delivered) in the {@code List} at {@code param.args[listArgIndex]}
     * and drops the rest, replacing the argument in place (or short-circuiting the call entirely if
     * nothing delivered-typed remains). Entries that don't expose the field at all — a different ack
     * shape than expected — are left in the batch untouched rather than dropped, since this module
     * can't tell what they are.
     */
    private void filterToDeliveredOnly(XC_MethodHook.MethodHookParam param, int listArgIndex,
                                       String feature, String target, String typeField,
                                       int deliveredTypeValue) {
        List<?> batch = (List<?>) param.args[listArgIndex];
        ArrayList<Object> kept = new ArrayList<>(batch.size());
        int dropped = 0;
        for (Object entry : batch) {
            try {
                int type = XposedHelpers.getIntField(entry, typeField);
                if (type == deliveredTypeValue) {
                    kept.add(entry);
                } else {
                    dropped++;
                }
            } catch (Throwable unknownShape) {
                kept.add(entry);
            }
        }
        if (dropped == 0) {
            return;
        }
        SelfCheckRegistry.incrementHit(feature, target,
                "dropped " + dropped + " seen ack(s), kept " + kept.size() + " other ack(s)");
        if (kept.isEmpty()) {
            param.setResult(null);
            return;
        }
        param.args[listArgIndex] = kept;
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

    /**
     * Online-status visibility is a stored server-side privacy preference, not a per-message
     * packet like seen/typing — there's no batch to filter. This submits the exact same request
     * Zalo's own "Online status" bottom sheet sends when tapped off: {@code Lpn/h0;->q3(settingId,
     * value, extra)}, settingId 0x1b (27), value 0 (hidden), over socket cmd 0x111 — confirmed by
     * field testing to genuinely flip the account's real preference (Zalo's own native toggle
     * read back as off afterward).
     *
     * <p>Confirmed by the same field testing: Zalo enforces this preference <b>symmetrically</b>,
     * server-side — once hidden, this account also stopped being able to see anyone else's online
     * status, same as toggling it off through Zalo's own UI would. {@link
     * #installOnlineStatusQueryBypass()} exists specifically to undo that side effect locally.
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

    /**
     * Undoes the symmetric side effect of {@link #installOnlineStatusHide()}: once this account is
     * marked hidden server-side, Zalo's own client gates whether it can query/see friends' online
     * status on the very same local flag ({@code Lyz/j;->j2()Z}) that reflects this account's own
     * (now hidden) preference — confirmed live, not assumed. An earlier version of this hook forced
     * that getter to always report visible without also submitting the real hide request, which
     * left the native "am I hidden" toggle permanently lying about its own state. Now that hiding
     * is done for real via {@link #installOnlineStatusHide()}, forcing this getter only affects
     * whether *this* client still queries others, not whether this account looks hidden to anyone
     * else, so it's safe to force unconditionally while the feature is on.
     */
    private void installOnlineStatusQueryBypass() {
        if (!HookConfig.isEnabled(Tweaks.KEY_HIDE_ONLINE_STATUS)) {
            return;
        }
        String className = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.online_status_query_gate_class", "yz.j");
        String methodName = SymbolSchema.string(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.online_status_query_gate_method", "j2");
        runGuarded("online-status query bypass", FEATURE_ONLINE_STATUS, className + "#" + methodName,
                () -> XposedHelpers.findAndHookMethod(className, classLoader, methodName,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                param.setResult(Boolean.TRUE);
                            }
                        }));
    }
}
