package com.ez.zalopatch.xposed.features;

import android.content.Context;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;
import com.ez.zalopatch.xposed.core.XpHooks;
import com.ez.zalopatch.xposed.core.XpReflect;

import java.util.ArrayList;
import java.util.List;

/** Blocks only outbound seen and typing signals; incoming status rendering stays native. */
public final class StatusPrivacyFeature extends Feature {
    private static final String FEATURE_SEEN = "messages.block_seen_status";
    private static final String FEATURE_TYPING = "messages.block_typing_status";
    private static final int SEEN_ACK_TYPE = 3;

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

    private void installSeenBlock() {
        if (!HookConfig.isEnabled(Tweaks.KEY_BLOCK_SEEN_STATUS)) {
            SelfCheckRegistry.markDisabled(FEATURE_SEEN,
                    "seen acknowledgement send");
            return;
        }
        Context context = HookConfig.resolveModuleContextForHooks();
        SymbolSchema.Active schema = SymbolSchema.activeForHooks(context);
        String managerClass = schema.string("symbols.chat.send_seen_manager_class", "");
        String ackClass = schema.string("symbols.chat.seen_ack_class", "");
        String typeField = schema.string("symbols.chat.seen_ack_type_field", "");
        String singleMethod = schema.string("symbols.chat.send_seen_single_method", "");
        String batchMethod = schema.string("symbols.chat.send_seen_batch_method", "");
        String repositoryClass = schema.string("symbols.chat.message_repository_class", "");
        String directMethod = schema.string("symbols.chat.send_ack_method", "");
        String target = "source=" + schema.source + " "
                + managerClass + "#" + singleMethod + "/" + batchMethod
                + " + " + repositoryClass + "#" + directMethod;
        runGuarded("seen-status block", FEATURE_SEEN, target, () -> {
            Class<?> ackType = XpReflect.findClass(ackClass, classLoader);
            XpHooks.findAndHookMethod(FEATURE_SEEN, managerClass, classLoader, singleMethod,
                    new Class<?>[]{ackType},
                    new XpHooks.Before() {
                        @Override
                        public void before(XpHooks.HookParam param) {
                            try {
                                if (XpReflect.getIntField(param.args[0], typeField)
                                        != SEEN_ACK_TYPE) {
                                    return;
                                }
                                param.setResult(null);
                                SelfCheckRegistry.incrementHit(FEATURE_SEEN,
                                        managerClass + "#" + singleMethod,
                                        "blocked queued seen acknowledgement");
                            } catch (Throwable ignored) {
                            }
                        }
                    }, null);
            XpHooks.findAndHookMethod(FEATURE_SEEN, managerClass, classLoader, batchMethod,
                    new Class<?>[]{ArrayList.class},
                    new XpHooks.Before() {
                        @Override
                        public void before(XpHooks.HookParam param) {
                            @SuppressWarnings("unchecked")
                            List<Object> batch = (List<Object>) param.args[0];
                            StatusPrivacyAckFilter.Result filtered =
                                    StatusPrivacyAckFilter.filterSeen(batch, SEEN_ACK_TYPE,
                                            entry -> XpReflect.getIntField(entry, typeField));
                            if (filtered.dropped == 0) {
                                return;
                            }
                            SelfCheckRegistry.incrementHit(FEATURE_SEEN,
                                    managerClass + "#" + batchMethod,
                                    "blocked " + filtered.dropped + " queued seen acknowledgement(s)");
                            if (filtered.kept.isEmpty()) {
                                param.setResult(null);
                            } else {
                                param.args[0] = filtered.kept;
                            }
                        }
                    }, null);
            XpHooks.findAndHookMethod(FEATURE_SEEN, repositoryClass, classLoader, directMethod,
                    new Class<?>[]{List.class, boolean.class, boolean.class, boolean.class},
                    new XpHooks.Before() {
                        @Override
                        public void before(XpHooks.HookParam param) {
                            if (!StatusPrivacyAckFilter.shouldBlockDirectAck(
                                    (Boolean) param.args[3])) {
                                return;
                            }
                            param.setResult(null);
                            SelfCheckRegistry.incrementHit(FEATURE_SEEN,
                                    repositoryClass + "#" + directMethod,
                                    "blocked direct seen acknowledgement");
                        }
                    }, null);
        });
    }

    private void installTypingBlock() {
        if (!HookConfig.isEnabled(Tweaks.KEY_BLOCK_TYPING_STATUS)) {
            SelfCheckRegistry.markDisabled(FEATURE_TYPING,
                    "typing indicator send");
            return;
        }
        SymbolSchema.Active schema = SymbolSchema.activeForHooks(
                HookConfig.resolveModuleContextForHooks());
        String repositoryClass = schema.string("symbols.chat.message_repository_class", "");
        String method = schema.string("symbols.chat.send_typing_method", "");
        runGuarded("typing-status block", FEATURE_TYPING,
                "source=" + schema.source + " " + repositoryClass + "#" + method, () ->
                        XpHooks.findAndHookMethod(FEATURE_TYPING, repositoryClass, classLoader,
                                method,
                                new Class<?>[]{String.class, int.class, boolean.class,
                                        boolean.class},
                                new XpHooks.Before() {
                                    @Override
                                    public void before(XpHooks.HookParam param) {
                                        param.setResult(null);
                                        SelfCheckRegistry.incrementHit(
                                                FEATURE_TYPING,
                                                repositoryClass + "#" + method,
                                                "blocked typing indicator send");
                                    }
                                }, null));
    }
}
