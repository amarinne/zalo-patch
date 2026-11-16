package com.ez.zalopatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Semantic local recall route. Resolution is observation evidence, not preservation proof.
 *
 * <p>The route is the local tombstone transition: an undo handler receives a recall event,
 * a coordinator calls a writer that forces an empty row through {@code updateChatMsgAPI},
 * and separately updates the cached conversation preview. The coordinator is shared with
 * local delete controls, so the undo handler is a separate required node: the gate only
 * applies while that handler is on the call stack. The writer runs after the coordinator
 * has already mutated the live message object, so a writer-only veto cannot preserve text;
 * the coordinator boundary is the only local point that runs before those mutations.
 */
public final class DexKitRecallFingerprint {
    public static final String WRITER_LITERAL = "updateMessageUndo->updateChatMsgAPI";
    public static final String PREVIEW_LITERAL = "UndoMessage -> updateTabMsg success msgId: ";
    public static final String MESSAGE_ID = "com.zing.zalo.data.entity.chat.message.MessageId";
    public static final String ZADB = "com.zing.zalo.db.zadb.Zadb";
    public static final String API_CODE = "com.zing.zalo.db.zadb.wrapper.ZdbApiCode";
    public static final String PREFIX = "symbols.recall.";
    public static final String CHAT_ROW = "com.zing.zalo.ui.chat.chatrow.ChatRow";
    public static final String TIME_CONFIG = PREFIX + "time_config_class";
    public static final String TIME_VISIBILITY = PREFIX + "time_visibility_method";
    public static final String TIME_REFRESH = PREFIX + "time_refresh_method";
    public static final String CAPTION_ROW = "com.zing.zalo.ui.chat.chatrow.ChatRowHasCaption";
    public static final String TEXT_DRAW = PREFIX + "text_draw_method";
    public static final String TEXT_RECALLED = PREFIX + "text_recalled_field";

    /** RecyclerView#getAdapter fixes the owner; its sole public final void() notifies observers. */
    public static java.lang.reflect.Method adapterRefresh(Class<?> adapter) {
        java.lang.reflect.Method selected = null;
        for (java.lang.reflect.Method method : adapter.getDeclaredMethods()) {
            int flags = method.getModifiers();
            if (!java.lang.reflect.Modifier.isPublic(flags)
                    || !java.lang.reflect.Modifier.isFinal(flags)
                    || java.lang.reflect.Modifier.isStatic(flags)
                    || method.getReturnType() != Void.TYPE || method.getParameterCount() != 0) continue;
            if (selected != null) return null;
            selected = method;
        }
        return selected;
    }
    public static final List<String> CLASSES = Collections.unmodifiableList(Arrays.asList(
            "writer_class", "model_class", "undo_class", "record_class", "coordinator_class",
            "undo_handler_class"));
    public static final List<String> METHODS = Collections.unmodifiableList(Arrays.asList(
            "writer_method", "update_method", "coordinator_method", "preview_method",
            "undo_handler_method", "identity_json_method"));
    public static final String ID_FIELD = PREFIX + "identity_field";

    public static final List<String> FIELDS = Collections.unmodifiableList(Arrays.asList(
            "identity_field", "text_field", "type_field", "owner_field", "sender_field"));

    private DexKitRecallFingerprint() {
    }

    /** Every descriptor path this family owns, in schema order. */
    public static List<String> paths() {
        ArrayList<String> paths = new ArrayList<>();
        for (String name : CLASSES) {
            paths.add(PREFIX + name);
        }
        for (String name : METHODS) {
            paths.add(PREFIX + name);
        }
        for (String name : FIELDS) {
            paths.add(PREFIX + name);
        }
        return Collections.unmodifiableList(paths);
    }

    /** One method identity, including whether it is static. */
    public static final class MethodShape {
        public final String owner;
        public final String name;
        public final String result;
        public final List<String> params;
        public final boolean isStatic;

        public MethodShape(String owner, String name, String result, List<String> params,
                           boolean isStatic) {
            this.owner = owner;
            this.name = name;
            this.result = result;
            this.params = params == null ? Collections.emptyList() : params;
            this.isStatic = isStatic;
        }

        public boolean matches(String returnType, String... types) {
            return !isStatic && returnType.equals(result) && params.equals(Arrays.asList(types));
        }
    }

    /**
     * The writer is a non-static void method over a host model, a boolean selector, and an
     * undo payload. The selector picks the tombstone type, so it cannot be dropped.
     */
    public static boolean writerShape(MethodShape writer) {
        return writer != null && !writer.isStatic && "void".equals(writer.result)
                && writer.params.size() == 3 && "boolean".equals(writer.params.get(1))
                && !writer.params.get(0).startsWith("java.")
                && !writer.params.get(2).startsWith("java.");
    }

    /** Each relationship is established by a narrowed production DexKit predicate query. */
    public static final class Route {
        public final MethodShape writer;
        public final MethodShape update;
        public final MethodShape coordinator;
        public final MethodShape preview;
        public final MethodShape undoHandler;
        public final String identityField;
        public final boolean writerCallsUpdate;
        public final boolean updateCallsNative;
        public final boolean coordinatorCallsWriter;
        public final boolean coordinatorCallsPreview;

        public Route(MethodShape writer, MethodShape update, MethodShape coordinator,
                     MethodShape preview, MethodShape undoHandler, String identityField,
                     boolean writerCallsUpdate, boolean updateCallsNative,
                     boolean coordinatorCallsWriter, boolean coordinatorCallsPreview) {
            this.writer = writer;
            this.update = update;
            this.coordinator = coordinator;
            this.preview = preview;
            this.undoHandler = undoHandler;
            this.identityField = identityField;
            this.writerCallsUpdate = writerCallsUpdate;
            this.updateCallsNative = updateCallsNative;
            this.coordinatorCallsWriter = coordinatorCallsWriter;
            this.coordinatorCallsPreview = coordinatorCallsPreview;
        }

        boolean qualified() {
            if (!writerShape(writer) || update == null || coordinator == null || preview == null
                    || undoHandler == null || identityField == null || identityField.isEmpty()) {
                return false;
            }
            return update.owner.equals(writer.owner) && update.params.size() == 3
                    && update.matches(API_CODE, update.params.get(0), MESSAGE_ID, "boolean")
                    && coordinator.matches("void", "java.lang.Object")
                    && preview.owner.equals(coordinator.owner)
                    && preview.matches("void", MESSAGE_ID, "boolean", writer.params.get(2),
                    "boolean")
                    && undoHandler.matches("void", writer.params.get(0))
                    && writerCallsUpdate && updateCallsNative && coordinatorCallsWriter
                    && coordinatorCallsPreview;
        }
    }

    public static final class Resolution {
        public final String status;
        public final Map<String, String> descriptors;

        Resolution(String status, Map<String, String> descriptors) {
            this.status = status;
            this.descriptors = Collections.unmodifiableMap(descriptors);
        }
    }

    /** Selects exactly one coherent route; distinct routes fail closed and resolve nothing. */
    public static Resolution evaluate(List<Route> candidates) {
        Route selected = null;
        Map<String, String> selectedOutput = null;
        for (Route route : candidates == null ? Collections.<Route>emptyList() : candidates) {
            if (route == null || !route.qualified()) {
                continue;
            }
            Map<String, String> routeOutput = descriptors(route);
            if (selected != null) {
                if (routeOutput.equals(selectedOutput)) {
                    continue;
                }
                return new Resolution("ambiguous_route", new LinkedHashMap<>());
            }
            selected = route;
            selectedOutput = routeOutput;
        }
        Map<String, String> output = new LinkedHashMap<>();
        if (selected == null) {
            return new Resolution("no_coherent_route", output);
        }
        output.putAll(selectedOutput);
        for (Map.Entry<String, String> item : output.entrySet()) {
            if (!DexKitAnchors.validDescriptor(DexKitAnchors.find(item.getKey()),
                    item.getValue())) {
                return new Resolution("invalid_descriptor", new LinkedHashMap<>());
            }
        }
        return new Resolution("resolved", output);
    }

    /** The descriptor map one route publishes, used to tell a repeat from a rival. */
    private static Map<String, String> descriptors(Route route) {
        Map<String, String> output = new LinkedHashMap<>();
        output.put(PREFIX + "writer_class", route.writer.owner);
        output.put(PREFIX + "writer_method", route.writer.name);
        output.put(PREFIX + "model_class", route.writer.params.get(0));
        output.put(PREFIX + "undo_class", route.writer.params.get(2));
        output.put(PREFIX + "record_class", route.update.params.get(0));
        output.put(PREFIX + "update_method", route.update.name);
        output.put(PREFIX + "coordinator_class", route.coordinator.owner);
        output.put(PREFIX + "coordinator_method", route.coordinator.name);
        output.put(PREFIX + "preview_method", route.preview.name);
        output.put(PREFIX + "undo_handler_class", route.undoHandler.owner);
        output.put(PREFIX + "undo_handler_method", route.undoHandler.name);
        output.put(ID_FIELD, route.identityField);
        return output;
    }
}
