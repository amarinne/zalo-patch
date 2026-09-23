package com.ez.zalopatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared DexKit fingerprint definition for the chat seen/typing repository family.
 *
 * <p>The message repository owns two stable, distinctive send shapes whose names rotate
 * per release:
 *
 * <ul>
 *   <li>Seen ack (direct): {@code void (List, boolean, boolean, boolean)}</li>
 *   <li>Typing: {@code void (String, int, boolean, boolean)}</li>
 * </ul>
 *
 * <p>Exactly one owner class may declare one of each; anything else stays unavailable.
 * The class name and method names are read from the hits, so a rename does not need a
 * remap. Dependency-free: hits arrive as plain shapes; uniqueness decides.
 */
public final class DexKitChatFingerprint {
    public static final String ANCHOR_REPOSITORY_CLASS = "symbols.chat.message_repository_class";
    public static final String ANCHOR_ACK_METHOD = "symbols.chat.send_ack_method";
    public static final String ANCHOR_TYPING_METHOD = "symbols.chat.send_typing_method";

    public static final String QUERY_SEND_SHAPES = "chat.send_shapes";

    private static final List<String> ACK_PARAMS =
            Arrays.asList("java.util.List", "boolean", "boolean", "boolean");
    private static final List<String> TYPING_PARAMS =
            Arrays.asList("java.lang.String", "int", "boolean", "boolean");

    private DexKitChatFingerprint() {
    }

    /** One method hit with its parameter types. */
    public static final class MethodHit {
        public final String owner;
        public final String name;
        public final List<String> paramTypes;

        public MethodHit(String owner, String name, List<String> paramTypes) {
            this.owner = owner == null ? "" : owner;
            this.name = name == null ? "" : name;
            this.paramTypes = paramTypes == null
                    ? new ArrayList<String>() : new ArrayList<>(paramTypes);
        }
    }

    /** Resolution outcome: the repository class plus its two method names. */
    public static final class Resolution {
        public final String repositoryClass;
        public final String ackMethod;
        public final String typingMethod;
        public final String status;

        Resolution(String repositoryClass, String ackMethod, String typingMethod,
                   String status) {
            this.repositoryClass = repositoryClass == null ? "" : repositoryClass;
            this.ackMethod = ackMethod == null ? "" : ackMethod;
            this.typingMethod = typingMethod == null ? "" : typingMethod;
            this.status = status == null ? "" : status;
        }

        public boolean resolved() {
            return "resolved".equals(status);
        }
    }

    /**
     * Resolves the repository: the single owner declaring exactly one seen-ack send and
     * exactly one typing send. Zero or several owners stay unavailable.
     */
    public static Resolution evaluate(List<MethodHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return new Resolution("", "", "", "no_candidates");
        }
        Map<String, List<MethodHit>> ackByOwner = new LinkedHashMap<>();
        Map<String, List<MethodHit>> typingByOwner = new LinkedHashMap<>();
        for (MethodHit hit : hits) {
            if (hit == null || hit.owner.isEmpty()) {
                continue;
            }
            if (ACK_PARAMS.equals(hit.paramTypes)) {
                ackByOwner.computeIfAbsent(hit.owner, key -> new ArrayList<>()).add(hit);
            } else if (TYPING_PARAMS.equals(hit.paramTypes)) {
                typingByOwner.computeIfAbsent(hit.owner, key -> new ArrayList<>()).add(hit);
            }
        }
        List<String> owners = new ArrayList<>();
        for (String owner : ackByOwner.keySet()) {
            if (typingByOwner.containsKey(owner)) {
                owners.add(owner);
            }
        }
        if (owners.isEmpty()) {
            return new Resolution("", "", "", "no_repository");
        }
        if (owners.size() > 1) {
            return new Resolution("", "", "", "ambiguous_repository");
        }
        String owner = owners.get(0);
        List<MethodHit> acks = ackByOwner.get(owner);
        List<MethodHit> typings = typingByOwner.get(owner);
        if (acks.size() != 1) {
            return new Resolution("", "", "", "ambiguous_ack_method");
        }
        if (typings.size() != 1) {
            return new Resolution("", "", "", "ambiguous_typing_method");
        }
        return new Resolution(owner, acks.get(0).name, typings.get(0).name, "resolved");
    }
    public static final String ANCHOR_MANAGER = "symbols.chat.send_seen_manager_class";
    public static final String ANCHOR_ACK_CLASS = "symbols.chat.seen_ack_class";
    public static final String ANCHOR_ACK_TYPE = "symbols.chat.seen_ack_type_field";
    public static final String ANCHOR_SINGLE = "symbols.chat.send_seen_single_method";
    public static final String ANCHOR_BATCH = "symbols.chat.send_seen_batch_method";

    /** A manager must be semantically anchored before inspecting this shape. */
    public static Map<String, String> queueShape(Class<?> manager, Class<?> repository) {
        Map<String, String> result = new LinkedHashMap<>();
        boolean hasRepository = false;
        boolean hasQueue = false;
        for (java.lang.reflect.Field field : manager.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            hasRepository |= field.getType() == repository;
            hasQueue |= field.getType() == ArrayList.class;
        }
        if (!hasRepository || !hasQueue) return result;
        java.lang.reflect.Method single = null;
        java.lang.reflect.Method batch = null;
        for (java.lang.reflect.Method method : manager.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(modifiers)
                    || !java.lang.reflect.Modifier.isSynchronized(modifiers)
                    || method.getReturnType() != Void.TYPE
                    || method.getParameterTypes().length != 1) continue;
            Class<?> parameter = method.getParameterTypes()[0];
            if (parameter == ArrayList.class) {
                if (batch != null) return result;
                batch = method;
            } else if (!parameter.isPrimitive() && parameter != String.class) {
                if (single != null) return result;
                single = method;
            }
        }
        if (single == null || batch == null) return result;
        Class<?> ack = single.getParameterTypes()[0];
        boolean repositoryReturnsAck = false;
        for (java.lang.reflect.Method method : repository.getDeclaredMethods()) {
            repositoryReturnsAck |= !java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == ack
                    && Arrays.equals(method.getParameterTypes(), new Class<?>[]{String.class});
        }
        if (!repositoryReturnsAck) return result;
        result.put(ANCHOR_MANAGER, manager.getName());
        result.put(ANCHOR_ACK_CLASS, ack.getName());
        result.put(ANCHOR_SINGLE, single.getName());
        result.put(ANCHOR_BATCH, batch.getName());
        return result;
    }

    /** The manager's number-3 guard must read exactly one ack-owned instance int. */
    public static String ackTypeField(Class<?> ack, List<String> guardFields) {
        String selected = "";
        for (java.lang.reflect.Field field : ack.getDeclaredFields()) {
            if (field.getType() != Integer.TYPE
                    || java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || !guardFields.contains(ack.getName() + "#" + field.getName())) continue;
            if (!selected.isEmpty()) return "";
            selected = field.getName();
        }
        return selected;
    }

}
