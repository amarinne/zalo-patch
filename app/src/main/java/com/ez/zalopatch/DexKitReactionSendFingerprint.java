package com.ez.zalopatch;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * DexKit fingerprint for the native emoji reaction send chain.
 *
 * <p>Static evidence (Zalo 26.10.02, 261002901 base APK, independently inspected):
 *
 * <ul>
 *   <li>{@code ReactionPickerView#getListener()} returns the listener interface
 *       ({@code lo1.r} there). This class/method pair is a stable host boundary.</li>
 *   <li>The listener declares exactly one {@code void (ReactionMsgType, String)} callback
 *       ({@code Z}). The reaction data class ({@code m00.i}) owns a static
 *       {@code Companion} field.</li>
 *   <li>The companion ({@code m00.h}) declares the factory
 *       {@code ReactionMsgType a(int type, String icon)}. That is the native path for an
 *       arbitrary Unicode emoji: the emoji picker resolves an icon to a configured
 *       reaction or falls back to {@code a(100, icon)} ({@code l00.g#p} and
 *       {@code m00.h#a}); the listener then dispatches the reaction and the host stamps
 *       the outgoing timestamp from its own native clock.</li>
 * </ul>
 *
 * <p>This class is deliberately dependency-free (only reflection) so the fingerprint and
 * the live preflight are JVM-testable. DexKit provides the obfuscated names; this class
 * decides uniqueness and validates the live shape. Nothing here is chosen by name alone:
 * a rename is fine, an ambiguous or wrong-shaped candidate is not.
 */
public final class DexKitReactionSendFingerprint {
    /** Stable reaction-bar base class; its name survives because the hook anchors on it. */
    public static final String PICKER_CLASS =
            "com.zing.zalo.ui.widget.reaction.ReactionPickerView";
    /** Concrete reaction-bar used by both context-menu hosts. */
    public static final String PICKER_CONTEXT_CLASS =
            "com.zing.zalo.ui.widget.reaction.ReactionPickerInContextMenuView";
    /** Stable getter that yields the listener interface. */
    public static final String LISTENER_GETTER = "getListener";
    /** Static companion field the Kotlin data class exposes. */
    public static final String COMPANION_FIELD = "Companion";

    public static final String QUERY_LISTENER_CALLBACK = "reaction_send.listener_callback";
    public static final String QUERY_FACTORY = "reaction_send.factory";

    public static final String ANCHOR_LISTENER_CLASS = "symbols.reaction_send.listener_class";
    public static final String ANCHOR_CALLBACK_METHOD = "symbols.reaction_send.callback_method";
    public static final String ANCHOR_REACTION_CLASS = "symbols.reaction_send.reaction_class";
    public static final String ANCHOR_FACTORY_METHOD = "symbols.reaction_send.factory_method";

    public static final List<String> ANCHORS = Collections.unmodifiableList(Arrays.asList(
            ANCHOR_LISTENER_CLASS, ANCHOR_CALLBACK_METHOD,
            ANCHOR_REACTION_CLASS, ANCHOR_FACTORY_METHOD));

    /**
     * The type seed the host itself uses for a Unicode emoji with no configured reaction
     * ({@code m00.h#a(100, icon)}). The reaction class turns it into its own resolved type
     * via the icon map, exactly as for a native tap.
     */
    public static final int UNKNOWN_EMOJI_TYPE = 100;

    private static final String STRING = "java.lang.String";
    private static final String INT = "int";

    private DexKitReactionSendFingerprint() {
    }

    /** One DexKit method hit, reduced to the fields the fingerprint reads. */
    public static final class MethodHit {
        public final String owner;
        public final String name;
        public final String returnType;
        public final List<String> paramTypes;

        public MethodHit(String owner, String name, String returnType, List<String> paramTypes) {
            this.owner = owner == null ? "" : owner;
            this.name = name == null ? "" : name;
            this.returnType = returnType == null ? "" : returnType;
            this.paramTypes = paramTypes == null
                    ? new ArrayList<String>() : new ArrayList<>(paramTypes);
        }
    }

    /** Callback resolution: the listener, the callback method, and the reaction class. */
    public static final class CallbackResolution {
        public final String listenerClass;
        public final String callbackMethod;
        public final String reactionClass;
        public final String status;

        CallbackResolution(String listenerClass, String callbackMethod, String reactionClass,
                           String status) {
            this.listenerClass = listenerClass == null ? "" : listenerClass;
            this.callbackMethod = callbackMethod == null ? "" : callbackMethod;
            this.reactionClass = reactionClass == null ? "" : reactionClass;
            this.status = status == null ? "" : status;
        }

        public boolean resolved() {
            return "resolved".equals(status);
        }
    }

    /** Factory resolution: the companion factory method. */
    public static final class FactoryResolution {
        public final String factoryMethod;
        public final String status;

        FactoryResolution(String factoryMethod, String status) {
            this.factoryMethod = factoryMethod == null ? "" : factoryMethod;
            this.status = status == null ? "" : status;
        }

        public boolean resolved() {
            return "resolved".equals(status);
        }
    }

    /**
     * Selects the unique listener callback: a {@code void} method declared on the listener
     * taking the reaction class followed by a String. Zero or several matches stay
     * unavailable; the reaction class name is taken from the hit, so a rename needs no
     * remap.
     */
    public static CallbackResolution evaluateCallback(List<MethodHit> hits,
                                                      String listenerClass) {
        if (listenerClass == null || listenerClass.isEmpty()) {
            return new CallbackResolution("", "", "", "no_listener");
        }
        if (hits == null || hits.isEmpty()) {
            return new CallbackResolution(listenerClass, "", "", "no_callback");
        }
        List<MethodHit> matching = new ArrayList<>();
        for (MethodHit hit : hits) {
            if (hit == null || !listenerClass.equals(hit.owner) || hit.name.isEmpty()) {
                continue;
            }
            if (!"void".equals(hit.returnType) || hit.paramTypes.size() != 2) {
                continue;
            }
            if (!STRING.equals(hit.paramTypes.get(1))) {
                continue;
            }
            if (!isReactionParameter(hit.paramTypes.get(0))) {
                continue;
            }
            matching.add(hit);
        }
        if (matching.isEmpty()) {
            return new CallbackResolution(listenerClass, "", "", "no_callback");
        }
        if (matching.size() > 1) {
            return new CallbackResolution(listenerClass, "", "", "ambiguous_callback");
        }
        MethodHit selected = matching.get(0);
        return new CallbackResolution(listenerClass, selected.name,
                selected.paramTypes.get(0), "resolved");
    }

    /**
     * Selects the unique factory on the companion: {@code (int, String) -> reactionClass}.
     * The owner must be the companion class; a factory on any other owner is a wrong
     * receiver and is rejected.
     */
    public static FactoryResolution evaluateFactory(List<MethodHit> hits, String reactionClass,
                                                    String companionClass) {
        if (reactionClass == null || reactionClass.isEmpty()
                || companionClass == null || companionClass.isEmpty()) {
            return new FactoryResolution("", "no_companion");
        }
        if (hits == null || hits.isEmpty()) {
            return new FactoryResolution("", "no_factory");
        }
        List<MethodHit> matching = new ArrayList<>();
        for (MethodHit hit : hits) {
            if (hit == null || !companionClass.equals(hit.owner) || hit.name.isEmpty()) {
                continue;
            }
            if (!reactionClass.equals(hit.returnType)) {
                continue;
            }
            if (hit.paramTypes.size() != 2
                    || !INT.equals(hit.paramTypes.get(0))
                    || !STRING.equals(hit.paramTypes.get(1))) {
                continue;
            }
            matching.add(hit);
        }
        if (matching.isEmpty()) {
            return new FactoryResolution("", "no_factory");
        }
        if (matching.size() > 1) {
            return new FactoryResolution("", "ambiguous_factory");
        }
        return new FactoryResolution(matching.get(0).name, "resolved");
    }

    /** A reaction class parameter is a non-primitive, non-String type. */
    public static boolean isReactionParameter(String typeName) {
        return typeName != null && !typeName.isEmpty()
                && !isPrimitive(typeName) && !STRING.equals(typeName);
    }

    private static boolean isPrimitive(String name) {
        return "boolean".equals(name) || "byte".equals(name) || "char".equals(name)
                || "short".equals(name) || "int".equals(name) || "long".equals(name)
                || "float".equals(name) || "double".equals(name) || "void".equals(name);
    }

    /**
     * The native expansion method on the listener: the unique non-static
     * {@code void (String)} method it declares. Tapping the native expand affordance
     * calls this after dismissing the picker, and the host implementation opens the
     * expanded (Zstyle) sheet. Zero or several matches stay unavailable, so the
     * feature fails closed instead of letting the native expansion run.
     */
    public static String expandMethodName(Class<?> listenerType) {
        if (listenerType == null) {
            return "";
        }
        try {
            Method found = null;
            int eligible = 0;
            for (Method method : listenerType.getMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != Void.TYPE) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1 && params[0] == String.class) {
                    eligible++;
                    found = method;
                }
            }
            return eligible == 1 && found != null ? found.getName() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** The listener class named by the stable {@code ReactionPickerView#getListener()}. */
    public static String listenerClassOf(ClassLoader loader) {
        if (loader == null) {
            return "";
        }
        try {
            Class<?> picker = Class.forName(PICKER_CLASS, false, loader);
            Method getter = picker.getMethod(LISTENER_GETTER);
            if (getter.getParameterTypes().length != 0) {
                return "";
            }
            Class<?> listener = getter.getReturnType();
            if (listener == null || listener.isPrimitive() || listener == String.class) {
                return "";
            }
            return listener.getName();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** The companion class named by the reaction class's static {@code Companion} field. */
    public static String companionClassOf(ClassLoader loader, String reactionClass) {
        if (loader == null || reactionClass == null || reactionClass.isEmpty()) {
            return "";
        }
        try {
            Class<?> reaction = Class.forName(reactionClass, false, loader);
            Field companion = reaction.getField(COMPANION_FIELD);
            if (!Modifier.isStatic(companion.getModifiers())) {
                return "";
            }
            Class<?> type = companion.getType();
            if (type == null || type == reaction) {
                return "";
            }
            return type.getName();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Live preflight for the four discovered descriptors. Validates the stable anchor
     * linkage, the listener callback shape, the companion field, and the factory
     * signature/receiver. Returns true only when every member is present exactly once;
     * otherwise the reasons are appended and the feature must stay unavailable.
     */
    public static boolean check(ClassLoader loader, String listenerClass, String callbackMethod,
                                String reactionClass, String factoryMethod, List<String> errors) {
        if (!checkShape(loader, listenerClass, callbackMethod, reactionClass, factoryMethod,
                errors)) {
            return false;
        }
        String anchored = listenerClassOf(loader);
        if (!listenerClass.equals(anchored)) {
            errors.add("listener class does not match " + PICKER_CLASS + "#" + LISTENER_GETTER
                    + " (" + (anchored.isEmpty() ? "missing" : anchored) + ")");
            return false;
        }
        return checkPickerConstructor(loader, listenerClass, errors);
    }

    /**
     * Shape-only validation (no stable-anchor equality), so the reflection rules can be
     * exercised against fixtures on a plain JVM.
     */
    public static boolean checkShape(ClassLoader loader, String listenerClass,
                                     String callbackMethod, String reactionClass,
                                     String factoryMethod, List<String> errors) {
        if (loader == null || isBlank(listenerClass) || isBlank(callbackMethod)
                || isBlank(reactionClass) || isBlank(factoryMethod)) {
            errors.add("incomplete reaction send bindings");
            return false;
        }
        try {
            Class<?> listener = Class.forName(listenerClass, false, loader);
            Class<?> reaction = Class.forName(reactionClass, false, loader);
            if (!listener.isInterface()) {
                errors.add(listenerClass + " is not an interface");
                return false;
            }
            if (reaction.isInterface() || Modifier.isAbstract(reaction.getModifiers())) {
                errors.add(reactionClass + " is not a concrete reaction class");
                return false;
            }
            // Exactly one eligible callback may exist; a cached name is not accepted when a
            // second valid signature also matches.
            Method callback = null;
            int eligibleCallbacks = 0;
            for (Method method : listener.getMethods()) {
                if (method.getReturnType() != Void.TYPE
                        || Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 2 && !params[0].isPrimitive() && params[0] != String.class
                        && params[0] == reaction && params[1] == String.class) {
                    eligibleCallbacks++;
                    callback = method;
                }
            }
            if (eligibleCallbacks != 1 || callback == null
                    || !callback.getName().equals(callbackMethod)) {
                errors.add(listenerClass + "#" + callbackMethod + " is not the unique reaction"
                        + " callback (" + eligibleCallbacks + " eligible)");
                return false;
            }
            Field companionField = reaction.getField(COMPANION_FIELD);
            if (!Modifier.isStatic(companionField.getModifiers())) {
                errors.add(reactionClass + "#" + COMPANION_FIELD + " is not static");
                return false;
            }
            Class<?> companion = companionField.getType();
            // Exactly one eligible factory may exist on the companion, with the companion as
            // the receiver.
            Method factory = null;
            int eligibleFactories = 0;
            for (Method method : companion.getMethods()) {
                if (method.getReturnType() != reaction
                        || Modifier.isAbstract(method.getModifiers())
                        || Modifier.isPrivate(method.getModifiers())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 2 && params[0] == Integer.TYPE && params[1] == String.class
                        && (Modifier.isStatic(method.getModifiers())
                        || method.getDeclaringClass() == companion)) {
                    eligibleFactories++;
                    factory = method;
                }
            }
            if (eligibleFactories != 1 || factory == null
                    || !factory.getName().equals(factoryMethod)) {
                errors.add(companion.getName() + "#" + factoryMethod + " is not the unique"
                        + " companion factory (" + eligibleFactories + " eligible)");
                return false;
            }
            return true;
        } catch (Throwable throwable) {
            errors.add("reaction_send: " + throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : " " + throwable.getMessage()));
            return false;
        }
    }

    /**
     * The concrete picker constructor must still take the Context, the listener, and a
     * String entry point in the exact order the hook reads its arguments from.
     */
    public static boolean checkPickerConstructor(ClassLoader loader, String listenerClass,
                                                 List<String> errors) {
        try {
            Class<?> picker = Class.forName(PICKER_CONTEXT_CLASS, false, loader);
            Class<?> listener = Class.forName(listenerClass, false, loader);
            Method getter = null;
            try {
                getter = Class.forName(PICKER_CLASS, false, loader).getMethod(LISTENER_GETTER);
            } catch (Throwable ignored) {
            }
            if (getter == null || getter.getParameterTypes().length != 0
                    || Modifier.isStatic(getter.getModifiers())
                    || getter.getReturnType() != listener) {
                errors.add(PICKER_CLASS + "#" + LISTENER_GETTER + " is not a public instance"
                        + " getter of " + listenerClass);
                return false;
            }
            for (java.lang.reflect.Constructor<?> constructor : picker.getDeclaredConstructors()) {
                Class<?>[] params = constructor.getParameterTypes();
                if (params.length == 5
                        && params[0] == android.content.Context.class
                        && params[1] == Boolean.TYPE
                        && params[2] == String.class
                        && params[3] == listener
                        && params[4] == String.class) {
                    return true;
                }
            }
            errors.add(PICKER_CONTEXT_CLASS + " constructor mismatch");
            return false;
        } catch (Throwable throwable) {
            errors.add("reaction_send picker: " + throwable.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }

    /** True when every anchor of the family is present and non-empty in the descriptor map. */
    public static boolean complete(Map<String, String> descriptors) {
        if (descriptors == null) {
            return false;
        }
        for (String anchor : ANCHORS) {
            String value = descriptors.get(anchor);
            if (value == null || value.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
