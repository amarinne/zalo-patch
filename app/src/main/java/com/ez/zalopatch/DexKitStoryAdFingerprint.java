package com.ez.zalopatch;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The story-ad binder must contain every exact event marker and have one live View owner. */
public final class DexKitStoryAdFingerprint {
    public static final String ANCHOR_CLASS = "symbols.story_ads.view_class";
    public static final String ANCHOR_BIND = "symbols.story_ads.bind_method";
    public static final String QUERY_BIND = "story_ads.bind";
    public static final List<String> MARKERS = Collections.unmodifiableList(Arrays.asList(
            "click_story_ad_cta", "click_name_story_ad", "send_message_story_ad"));

    private DexKitStoryAdFingerprint() { }

    public static final class Hit {
        public final String owner;
        public final String name;
        public final String returnType;
        public final List<String> parameters;
        public final boolean isStatic;
        public final boolean liveSignature;

        public Hit(String owner, String name, String returnType, List<String> parameters,
                   boolean isStatic, boolean liveSignature) {
            this.owner = owner;
            this.name = name;
            this.returnType = returnType;
            this.parameters = parameters;
            this.isStatic = isStatic;
            this.liveSignature = liveSignature;
        }
    }

    /** Reject incomplete query output and ambiguous results before selecting any owner. */
    public static Hit select(int matchCount, List<Hit> hits, String error) {
        if (error == null || !error.isEmpty() || hits == null || matchCount != hits.size()
                || matchCount != 1) return null;
        Hit hit = hits.get(0);
        return hit != null && hit.owner != null && !hit.owner.isEmpty()
                && hit.name != null && !hit.name.startsWith("<") && !hit.name.isEmpty()
                && "void".equals(hit.returnType) && !hit.isStatic && hit.liveSignature
                && hit.parameters != null && !hit.parameters.isEmpty() ? hit : null;
    }

    /** Unique declared binder. Overloads are unavailable rather than selected by arity. */
    public static Method binding(Class<?> owner, String name) {
        if (owner == null || name == null || name.isEmpty() || name.startsWith("<")
                || owner.isInterface() || Modifier.isAbstract(owner.getModifiers())
                || owner.getDeclaredConstructors().length == 0) return null;
        Method found = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (!name.equals(method.getName())) continue;
            if (found != null || method.getReturnType() != void.class
                    || method.getParameterTypes().length == 0
                    || Modifier.isStatic(method.getModifiers())
                    || Modifier.isAbstract(method.getModifiers())
                    || method.isSynthetic()) return null;
            found = method;
        }
        return found;
    }

    /** Only successful calls on the dedicated qualified owner may change its view. */
    public static boolean shouldCollapse(boolean enabled, boolean successful,
                                         Class<?> qualifiedOwner, Object receiver) {
        return enabled && successful && qualifiedOwner != null && receiver != null
                && receiver.getClass() == qualifiedOwner;
    }
}
