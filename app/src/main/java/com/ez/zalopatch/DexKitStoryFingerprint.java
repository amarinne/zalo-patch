package com.ez.zalopatch;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Selects only the outbound story-view serializers. Each request route resolves independently. */
public final class DexKitStoryFingerprint {
    public static final String ANCHOR_SEEN_CLASS = "symbols.stories.seen_request_class";
    public static final String ANCHOR_SEEN_METHOD = "symbols.stories.seen_request_method";
    public static final String ANCHOR_SUGGESTED_CLASS = "symbols.stories.suggested_seen_request_class";
    public static final String ANCHOR_SUGGESTED_METHOD = "symbols.stories.suggested_seen_request_method";
    public static final String QUERY_SEEN = "stories.seen_request";
    public static final String QUERY_SUGGESTED = "stories.suggested_seen_request";
    public static final String STORY_ID = "storyId";
    public static final int SEEN_COMMAND = 1343;
    public static final int SUGGESTED_COMMAND = 1356;

    private DexKitStoryFingerprint() { }

    /** Query evidence includes both literals and the serializer's stable Java calls. */
    public static final class MethodHit {
        public final String owner;
        public final String name;
        public final String returnType;
        public final List<String> parameters;
        public final boolean isStatic;
        public final int command;
        public final boolean storyId;
        public final boolean parsesStoryId;
        public final boolean writesPayload;

        public MethodHit(String owner, String name, String returnType, List<String> parameters,
                         boolean isStatic, int command, boolean storyId,
                         boolean parsesStoryId, boolean writesPayload) {
            this.owner = owner == null ? "" : owner;
            this.name = name == null ? "" : name;
            this.returnType = returnType == null ? "" : returnType;
            this.parameters = parameters == null ? new ArrayList<>() : new ArrayList<>(parameters);
            this.isStatic = isStatic;
            this.command = command;
            this.storyId = storyId;
            this.parsesStoryId = parsesStoryId;
            this.writesPayload = writesPayload;
        }
    }

    public static final class Resolution {
        public final String owner;
        public final String method;
        public final String status;

        private Resolution(String owner, String method, String status) {
            this.owner = owner;
            this.method = method;
            this.status = status;
        }

        public boolean resolved() { return "resolved".equals(status); }
    }

    /** Zero or multiple qualifying serializers never authorize a hook. */
    public static Resolution evaluate(boolean suggested, List<MethodHit> hits) {
        MethodHit selected = null;
        List<String> expected = suggested
                ? Arrays.asList("int", "int", "java.lang.String")
                : Arrays.asList("int", "java.lang.String");
        int command = suggested ? SUGGESTED_COMMAND : SEEN_COMMAND;
        if (hits != null) for (MethodHit hit : hits) {
            if (hit == null || hit.owner.isEmpty() || hit.name.isEmpty() || hit.isStatic
                    || !"void".equals(hit.returnType) || !expected.equals(hit.parameters)
                    || hit.command != command || !hit.storyId || !hit.parsesStoryId
                    || !hit.writesPayload) continue;
            if (selected != null) return new Resolution("", "", "ambiguous_request");
            selected = hit;
        }
        return selected == null ? new Resolution("", "", "no_request")
                : new Resolution(selected.owner, selected.name, "resolved");
    }

    /** The catalog and overlay use the same public instance serializer shape. */
    public static Method requestMethod(Class<?> owner, String name, boolean suggested) {
        if (owner == null || name == null || name.isEmpty() || owner.isInterface()
                || Modifier.isAbstract(owner.getModifiers())) return null;
        try {
            Method method = owner.getDeclaredMethod(name, parameterTypes(suggested));
            int modifiers = method.getModifiers();
            return method.getReturnType() == Void.TYPE && Modifier.isPublic(modifiers)
                    && !Modifier.isStatic(modifiers) && !Modifier.isAbstract(modifiers)
                    && !Modifier.isNative(modifiers) ? method : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    public static Class<?>[] parameterTypes(boolean suggested) {
        return suggested ? new Class<?>[]{Integer.TYPE, Integer.TYPE, String.class}
                : new Class<?>[]{Integer.TYPE, String.class};
    }
}
