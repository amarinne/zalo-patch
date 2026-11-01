package com.ez.zalopatch.xposed.features;

import android.content.Context;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;
import com.ez.zalopatch.xposed.core.XpHooks;
import com.ez.zalopatch.xposed.core.XpReflect;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Blocks only outbound seen and typing signals; incoming status rendering stays native. */
public final class StatusPrivacyFeature extends Feature {
    private static final String FEATURE_SEEN = "messages.block_seen_status";
    private static final String FEATURE_TYPING = "messages.block_typing_status";
    private static final int SEEN_ACK_TYPE = 3;

    /** Test-only probe action for headless verification (`zalo-verify behave seen`). */
    static final String BEHAVE_ACTION = "com.ez.zalopatch.behave.SEEN_PROBE";

    /**
     * Cached ack type fields per runtime class. Same match rule as
     * {@code XpReflect.getIntField}: first declared field with this name walking up the
     * hierarchy. Batch elements may be subclasses, so the key includes the runtime class.
     */
    private static final Map<String, Field> ACK_TYPE_FIELDS = new ConcurrentHashMap<>();
    private static final java.util.Set<String> ACK_TYPE_FIELD_MISSES =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Map<String, java.lang.reflect.Method> TRANSPORT_COMPLETIONS =
            new ConcurrentHashMap<>();
    private static final java.util.Set<String> TRANSPORT_COMPLETION_MISSES =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** Last single-route ack, for the debug probe to re-read through the cached handle. */
    private static volatile WeakReference<Object> LAST_ACK = new WeakReference<>(null);

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
        if (HookConfig.isEnabled(Tweaks.KEY_BLOCK_SEEN_STATUS) && HookConfig.isDebugEnabled()) {
            // Debug-started processes only: headless probe for `zalo-verify behave`.
            runGuarded("seen behave probe", FEATURE_SEEN, BEHAVE_ACTION,
                    this::hookBehaveProbe);
        }
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
        String transportClass = schema.string("symbols.chat.seen_transport_class", "");
        String transportSeen = schema.string("symbols.chat.seen_transport_seen_method", "");
        String transportMarker = schema.string("symbols.chat.seen_transport_marker_method", "");
        String target = "source=" + schema.source + " "
                + managerClass + "#" + singleMethod + "/" + batchMethod
                + " + " + repositoryClass + "#" + directMethod;
        runGuarded("seen-status block", FEATURE_SEEN, target, () -> {
            boolean hookedAny = false;
            // Shared ACK transport: drops only read receipts; delivery ACKs pass.
            if (!transportClass.isEmpty() && !transportSeen.isEmpty()) {
                try {
                    hookSeenTransport(transportClass, transportSeen, transportMarker);
                    hookedAny = true;
                    SelfCheckRegistry.markInstalled(FEATURE_SEEN + ".queued",
                            transportClass + "#" + transportSeen + "/" + transportMarker, 2);
                    SelfCheckRegistry.markDisabled(FEATURE_SEEN + ".direct",
                            "shared ACK transport route in use");
                } catch (Throwable throwable) {
                    SelfCheckRegistry.markStale(FEATURE_SEEN + ".queued",
                            transportClass + "#" + transportSeen,
                            throwable.getClass().getSimpleName());
                    log("seen transport route unavailable: "
                            + throwable.getClass().getSimpleName());
                }
            }
            // Routes arm independently; a missing route remains visible as stale.
            if (!hookedAny && !managerClass.isEmpty() && !ackClass.isEmpty() && !typeField.isEmpty()) {
                try {
                    hookQueuedSeen(managerClass, ackClass, typeField, singleMethod, batchMethod);
                    hookedAny = true;
                    SelfCheckRegistry.markInstalled(FEATURE_SEEN + ".queued",
                            managerClass + "#" + singleMethod + "/" + batchMethod, 2);
                } catch (Throwable throwable) {
                    SelfCheckRegistry.markStale(FEATURE_SEEN + ".queued", target,
                            throwable.getClass().getSimpleName());
                    log("queued seen route unavailable: "
                            + throwable.getClass().getSimpleName());
                }
            } else if (!hookedAny) {
                SelfCheckRegistry.markStale(FEATURE_SEEN + ".queued", target,
                        "queued seen symbols unavailable");
            }
            // Direct acknowledgement route is independent of the queue.
            if (!hookedAny && !repositoryClass.isEmpty() && !directMethod.isEmpty()) {
                try {
                    hookDirectAck(repositoryClass, directMethod);
                    hookedAny = true;
                    SelfCheckRegistry.markInstalled(FEATURE_SEEN + ".direct",
                            repositoryClass + "#" + directMethod, 1);
                } catch (Throwable throwable) {
                    SelfCheckRegistry.markStale(FEATURE_SEEN + ".direct", target,
                            throwable.getClass().getSimpleName());
                    log("direct ack route unavailable: "
                            + throwable.getClass().getSimpleName());
                }
            }
            if (!hookedAny) {
                throw new IllegalStateException("no seen route resolvable");
            }
        });
    }

    private void hookSeenTransport(String transportClass, String seenMethod,
                                   String markerMethod) throws Throwable {
        Class<?> transport = XpReflect.findClass(transportClass, classLoader);
        java.lang.reflect.Method seen = transportMethod(transport, seenMethod,
                List.class, Boolean.TYPE, Integer.TYPE, Integer.TYPE);
        java.lang.reflect.Method marker = markerMethod.isEmpty() ? null
                : transportMethod(transport, markerMethod,
                List.class, Boolean.TYPE, Boolean.TYPE, Boolean.TYPE);
        XpHooks.hookMethod(FEATURE_SEEN, seen, (XpHooks.Before) param -> {
            try {
                if (param.args == null || param.args.length < 5
                        || !(param.args[1] instanceof Boolean)
                        || !(Boolean) param.args[1]
                        || !(param.args[2] instanceof Number)
                        || !StatusPrivacyAckFilter.isSeenTransportType(
                        ((Number) param.args[2]).intValue())) {
                    return;
                }
                completeTransport(param.args[4]);
                param.setResult(null);
                SelfCheckRegistry.incrementHit(FEATURE_SEEN,
                        transportClass + "#" + seenMethod,
                        "blocked shared-transport seen acknowledgement");
                SelfCheckRegistry.incrementHit(FEATURE_SEEN + ".queued",
                        transportClass + "#" + seenMethod, "blocked seen ACK");
            } catch (Throwable error) {
                SelfCheckRegistry.markFailed(FEATURE_SEEN + ".queued",
                        "seen transport hook", error);
            }
        });
        if (marker == null) return;
        XpHooks.hookMethod(FEATURE_SEEN, marker, (XpHooks.Before) param -> {
            try {
                if (param.args == null || param.args.length < 2
                        || !Boolean.TRUE.equals(param.args[1])) return;
                param.args[1] = Boolean.FALSE;
                SelfCheckRegistry.incrementHit(FEATURE_SEEN,
                        transportClass + "#" + markerMethod,
                        "cleared read-marker seen flag");
                SelfCheckRegistry.incrementHit(FEATURE_SEEN + ".queued",
                        transportClass + "#" + markerMethod, "cleared seen flag");
            } catch (Throwable error) {
                SelfCheckRegistry.markFailed(FEATURE_SEEN + ".queued",
                        "read-marker hook", error);
            }
        });
    }

    /** Unique declared void method with the leading parameter shape; ambiguity fails closed. */
    private static java.lang.reflect.Method transportMethod(Class<?> owner, String name,
                                                            Class<?>... leading)
            throws NoSuchMethodException {
        java.lang.reflect.Method found = null;
        for (java.lang.reflect.Method candidate : owner.getDeclaredMethods()) {
            if (!name.equals(candidate.getName())
                    || candidate.getReturnType() != Void.TYPE
                    || candidate.getParameterTypes().length < leading.length) continue;
            Class<?>[] parameters = candidate.getParameterTypes();
            boolean matches = true;
            for (int index = 0; index < leading.length; index++) {
                if (parameters[index] != leading[index]) {
                    matches = false;
                    break;
                }
            }
            if (!matches) continue;
            if (found != null) {
                throw new NoSuchMethodException("ambiguous " + owner.getName() + "#" + name);
            }
            found = candidate;
        }
        if (found == null) {
            throw new NoSuchMethodException("missing " + owner.getName() + "#" + name);
        }
        found.setAccessible(true);
        return found;
    }

    /**
     * Completes a dropped receipt through the callback's interface method, so callers
     * waiting on the transport do not stall. Unknown callback shapes are left alone.
     */
    private static void completeTransport(Object callback) throws Exception {
        if (callback == null) return;
        Class<?> type = callback.getClass();
        String key = type.getName();
        if (TRANSPORT_COMPLETION_MISSES.contains(key)) return;
        java.lang.reflect.Method completion = TRANSPORT_COMPLETIONS.get(key);
        if (completion == null) {
            completion = completionMethod(type);
            if (completion == null) return;
            TRANSPORT_COMPLETIONS.put(key, completion);
        }
        completion.invoke(callback, new org.json.JSONObject());
    }

    private static java.lang.reflect.Method completionMethod(Class<?> type) {
        java.lang.reflect.Method found = null;
        for (Class<?> contract : type.getInterfaces()) {
            for (java.lang.reflect.Method candidate : contract.getMethods()) {
                if (candidate.getParameterCount() != 1
                        || candidate.getParameterTypes()[0] != Object.class
                        || candidate.getReturnType() != Void.TYPE) continue;
                if (found != null && !found.equals(candidate)) {
                    TRANSPORT_COMPLETION_MISSES.add(type.getName());
                    return null;
                }
                found = candidate;
            }
        }
        if (found == null) TRANSPORT_COMPLETION_MISSES.add(type.getName());
        return found;
    }

    private void hookQueuedSeen(String managerClass, String ackClass, String typeField,
                                String singleMethod, String batchMethod) throws Throwable {
        Class<?> ackType = XpReflect.findClass(ackClass, classLoader);
        XpHooks.findAndHookMethod(FEATURE_SEEN, managerClass, classLoader, singleMethod,
                new Class<?>[]{ackType},
                new XpHooks.Before() {
                    @Override
                    public void before(XpHooks.HookParam param) {
                        try {
                            LAST_ACK = new WeakReference<>(param.args[0]);
                            if (ackTypeOf(param.args[0], typeField)
                                    != SEEN_ACK_TYPE) {
                                return;
                            }
                            param.setResult(null);
                            SelfCheckRegistry.incrementHit(FEATURE_SEEN,
                                    managerClass + "#" + singleMethod,
                                    "blocked queued seen acknowledgement");
                            SelfCheckRegistry.incrementHit(FEATURE_SEEN + ".queued",
                                    managerClass + "#" + singleMethod, "blocked seen type 3");
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
                                        entry -> ackTypeOf(entry, typeField));
                        if (filtered.dropped == 0) {
                            return;
                        }
                        SelfCheckRegistry.incrementHit(FEATURE_SEEN,
                                managerClass + "#" + batchMethod,
                                "blocked " + filtered.dropped + " queued seen acknowledgement(s)");
                        SelfCheckRegistry.incrementHit(FEATURE_SEEN + ".queued",
                                managerClass + "#" + batchMethod, "blocked seen type 3");
                        if (filtered.kept.isEmpty()) {
                            param.setResult(null);
                        } else {
                            param.args[0] = filtered.kept;
                        }
                    }
                }, null);
    }

    private void hookDirectAck(String repositoryClass, String directMethod) throws Throwable {
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
                        SelfCheckRegistry.incrementHit(FEATURE_SEEN + ".direct",
                                repositoryClass + "#" + directMethod, "blocked seen=true");
                    }
                }, null);
    }

    /**
     * Cached equivalent of {@code XpReflect.getIntField}: first declared field with this
     * name walking up the runtime class hierarchy, resolved once per class. A miss throws
     * the same {@code NoSuchFieldException} shape the uncached path produced, so the
     * surrounding fail-soft behavior (pass the ack through) is unchanged.
     */
    private static int ackTypeOf(Object ack, String typeField) throws Throwable {
        Field field = ackTypeFieldFor(ack == null ? null : ack.getClass(), typeField);
        if (field == null) {
            throw new NoSuchFieldException(
                    (ack == null ? "<null>" : ack.getClass().getName())
                            + "#" + typeField + " not found");
        }
        return field.getInt(ack);
    }

    private static Field ackTypeFieldFor(Class<?> owner, String typeField) {
        if (owner == null || typeField == null || typeField.isEmpty()) {
            return null;
        }
        String key = owner.getName() + "#" + typeField;
        Field hit = ACK_TYPE_FIELDS.get(key);
        if (hit != null) {
            return hit;
        }
        if (ACK_TYPE_FIELD_MISSES.contains(key)) {
            return null;
        }
        Field found = null;
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            for (Field candidate : current.getDeclaredFields()) {
                if (typeField.equals(candidate.getName())) {
                    found = candidate;
                    break;
                }
            }
            if (found != null) {
                break;
            }
        }
        if (found == null) {
            ACK_TYPE_FIELD_MISSES.add(key);
            return null;
        }
        found.setAccessible(true);
        ACK_TYPE_FIELDS.put(key, found);
        return found;
    }

    /**
     * Debug-only probe: resolves the ack type field against the live obfuscated class
     * and, when hook traffic has cached an ack instance, re-reads it through the same
     * cached handle the hot path uses. Reports on the existing queued row (no new ID).
     */
    private void hookBehaveProbe() throws Throwable {
        android.content.Context context = HookConfig.resolveFallbackContextForHooks();
        if (context == null) {
            throw new IllegalStateException("application context unavailable");
        }
        SymbolSchema.Active schema = SymbolSchema.activeForHooks(
                HookConfig.resolveModuleContextForHooks());
        String ackClass = schema.string("symbols.chat.seen_ack_class", "");
        String typeField = schema.string("symbols.chat.seen_ack_type_field", "");
        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ignored, android.content.Intent intent) {
                if (!HookConfig.isDebugCurrentlyEnabled() || intent == null
                        || !BEHAVE_ACTION.equals(intent.getAction())) {
                    return;
                }
                Class<?> type = XpReflect.findClassIfExists(ackClass, classLoader);
                Field handle = ackTypeFieldFor(type, typeField);
                Object last = LAST_ACK.get();
                String detail;
                if (handle == null) {
                    detail = "shape=missing";
                } else if (last == null) {
                    detail = "shape=found traffic=none";
                } else {
                    try {
                        detail = "shape=found traffic=value=" + handle.getInt(last);
                    } catch (Throwable throwable) {
                        detail = "shape=found traffic=unreadable";
                    }
                }
                SelfCheckRegistry.markSuppressed(FEATURE_SEEN + ".queued", "behave:seen", detail);
            }
        };
        // Exported: shell-sent test broadcasts cross UIDs (verified on device for the
        // inbox probe). Harmless by construction: debug-started processes only, debug
        // re-checked per broadcast, no extras read, read-only report on an existing row.
        androidx.core.content.ContextCompat.registerReceiver(context, receiver,
                new android.content.IntentFilter(BEHAVE_ACTION),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
    }

    private void installTypingBlock() {        if (!HookConfig.isEnabled(Tweaks.KEY_BLOCK_TYPING_STATUS)) {
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
