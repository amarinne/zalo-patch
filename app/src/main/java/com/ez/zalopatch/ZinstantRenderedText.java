package com.ez.zalopatch;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Reads only visible literal text from the host's named rendered ZOM tree. */
public final class ZinstantRenderedText {
    private static final String NODE_PACKAGE = "com.zing.zalo.zinstant.zom.node.";
    private static final int MAX_NODES = 128;
    private static final int MAX_DEPTH = 12;
    private static final int MAX_CHARS = 4096;

    private ZinstantRenderedText() {}

    public static String read(Object rootTree) {
        if (rootTree == null) return "";
        try {
            Field documentField = null;
            for (Class<?> type = rootTree.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (!field.getType().getName().equals(NODE_PACKAGE + "ZOMDocument")) continue;
                    if (documentField != null) return "";
                    documentField = field;
                }
            }
            if (documentField == null) return "";
            documentField.setAccessible(true);
            Object document = documentField.get(rootTree);
            if (document == null) return "";
            Object node = document.getClass().getField("mZOMRoot").get(document);
            Reader reader = new Reader();
            reader.visit(node, 0);
            return reader.text.toString();
        } catch (ReflectiveOperationException | RuntimeException mismatch) {
            return "";
        }
    }

    private static boolean isType(Object value, String name) {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals(NODE_PACKAGE + name)) return true;
        }
        return false;
    }

    private static final class Reader {
        final StringBuilder text = new StringBuilder();
        final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        int nodes;

        void visit(Object node, int depth) throws ReflectiveOperationException {
            if (node == null || !visited.add(node)) return;
            if (++nodes > MAX_NODES || depth > MAX_DEPTH || !isType(node, "ZOM")) {
                throw new IllegalArgumentException("rendered tree exceeds supported shape");
            }
            Class<?> type = node.getClass();
            if (type.getField("mVisibility").getInt(node) != 0
                    || type.getField("mRelativeVisibility").getInt(node) != 0
                    || type.getField("mOpacity").getFloat(node) <= 0) return;
            if (isType(node, "ZOMText")) {
                Object value = type.getMethod("getPlainText").invoke(node);
                if (!(value instanceof String)) throw new IllegalArgumentException("text shape");
                String plain = (String) value;
                if (text.length() + plain.length() + 1 > MAX_CHARS) {
                    throw new IllegalArgumentException("rendered text exceeds limit");
                }
                text.append(' ').append(plain);
            }
            if (isType(node, "ZOMContainer")) {
                Object children = type.getField("mChildren").get(node);
                if (children == null) return;
                if (!(children instanceof Object[])) throw new IllegalArgumentException("children shape");
                Object[] values = (Object[]) children;
                if (values.length > MAX_NODES) throw new IllegalArgumentException("children limit");
                for (Object child : values) visit(child, depth + 1);
            }
        }
    }
}
