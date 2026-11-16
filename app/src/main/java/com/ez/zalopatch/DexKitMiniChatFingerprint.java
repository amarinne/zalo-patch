package com.ez.zalopatch;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Native chat-head route. The host retains permissions, message policy and launch actions. */
public final class DexKitMiniChatFingerprint {
    public static final String PREFIX = "symbols.mini_chat.";
    public static final String CONVERSATION = "com.zing.zalo.data.chat.model.tabmessage.Conversation";
    public static final String SERVICE = "com.zing.zalo.comm.chathead.minichat.ui.service.MiniChatService";
    public static final String HOST = "com.zing.zalo.comm.chathead.minichat.ui.container.HostFrameLayout";
    public static final List<String> CLASSES = Collections.unmodifiableList(Arrays.asList(
            "capability_class", "config_class", "controller_class", "task_class"));
    public static final List<String> FIELDS = Collections.unmodifiableList(Arrays.asList(
            "legacy_field", "config_field"));
    public static final List<String> METHODS = Collections.unmodifiableList(Arrays.asList(
            "reload_method", "eligible_method"));

    private DexKitMiniChatFingerprint() { }

    public static List<String> paths() {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (List<String> names : Arrays.asList(CLASSES, FIELDS, METHODS)) {
            for (String name : names) result.add(PREFIX + name);
        }
        return result;
    }

    public static boolean taskShape(Class<?>[] p) {
        return p.length == 8 && p[1].getName().equals(CONVERSATION)
                && p[2] == boolean.class && p[3] == int.class && p[4] == boolean.class
                && p[5] == CharSequence.class && p[6].getName().equals("android.os.Bundle")
                && p[7] == boolean.class;
    }

    /** Follow the stable overlay host's constructor types, without guessed obfuscated names. */
    public static Constructor<?>[] overlayConstructors(Class<?> host, Class<?> controller)
            throws Exception {
        java.util.ArrayList<Constructor<?>[]> matches = new java.util.ArrayList<>();
        for (Constructor<?> hostCtor : host.getDeclaredConstructors()) {
            Class<?>[] h = hostCtor.getParameterTypes();
            if (h.length != 2 || !h[0].getName().equals("android.content.Context")) continue;
            for (Constructor<?> root : h[1].getDeclaredConstructors()) {
                Class<?>[] p = root.getParameterTypes();
                if (p.length != 3 || p[0] != h[0] || p[2] != controller) continue;
                try {
                    Constructor<?> manager = p[1].getDeclaredConstructor(h[0]);
                    matches.add(new Constructor<?>[]{root, manager});
                } catch (NoSuchMethodException ignored) { }
            }
        }
        if (matches.size() != 1) throw new IllegalArgumentException("Mini Chat overlay constructors changed");
        return matches.get(0);
    }

    /** Every cache hit and exact binding receives the same loader-specific preflight. */
    public static Binding bind(Map<String, String> symbols, ClassLoader loader) throws Exception {
        for (String path : paths()) {
            if (symbols.get(path) == null || symbols.get(path).isEmpty()) {
                throw new IllegalArgumentException("missing Mini Chat binding");
            }
        }
        Class<?> capabilities = type(symbols, "capability_class", loader);
        Class<?> config = type(symbols, "config_class", loader);
        Class<?> controller = type(symbols, "controller_class", loader);
        Class<?> task = type(symbols, "task_class", loader);
        if (capabilities == config || !Runnable.class.isAssignableFrom(task)) {
            throw new IllegalArgumentException("Mini Chat route changed");
        }
        int constructors = 0;
        for (Constructor<?> ctor : task.getDeclaredConstructors()) {
            Class<?>[] p = ctor.getParameterTypes();
            if (taskShape(p) && p[0] == controller) constructors++;
        }
        if (constructors != 1) throw new IllegalArgumentException("Mini Chat launch shape changed");
        Field legacy = flag(capabilities, symbols.get(PREFIX + "legacy_field"), true);
        Field configured = flag(config, symbols.get(PREFIX + "config_field"), false);
        Method reload = method(config, symbols.get(PREFIX + "reload_method"), void.class);
        Method eligible = method(controller, symbols.get(PREFIX + "eligible_method"), boolean.class);
        task.getDeclaredMethod("run");
        Class.forName(SERVICE, false, loader).getDeclaredMethod("onCreate");
        return new Binding(legacy, configured, reload, eligible, task);
    }

    private static Class<?> type(Map<String, String> symbols, String key, ClassLoader loader)
            throws ClassNotFoundException {
        return Class.forName(symbols.get(PREFIX + key), false, loader);
    }

    private static Field flag(Class<?> owner, String name, boolean capability) throws Exception {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != boolean.class || !Modifier.isStatic(field.getModifiers())
                || (!capability && Modifier.isFinal(field.getModifiers()))) {
            throw new IllegalArgumentException("Mini Chat flag shape changed");
        }
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> owner, String name, Class<?> result) throws Exception {
        Method method = owner.getDeclaredMethod(name);
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != result
                || Modifier.isAbstract(method.getModifiers())) {
            throw new IllegalArgumentException("Mini Chat method shape changed");
        }
        method.setAccessible(true);
        return method;
    }

    public static final class Binding {
        public final Field legacy, configured;
        public final Method reload, eligible;
        public final Class<?> task;

        Binding(Field legacy, Field configured, Method reload, Method eligible,
                Class<?> task) {
            this.legacy = legacy;
            this.configured = configured;
            this.reload = reload;
            this.eligible = eligible;
            this.task = task;
        }
    }
}
