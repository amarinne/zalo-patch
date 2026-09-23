package com.ez.zalopatch.xposed.features;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Selects a unique host-interface navigation route without obfuscated member names. */
final class InboxNativeRoute {
    final Field hostField;
    final Method managerGetter;
    final Method open;

    private InboxNativeRoute(Field hostField, Method managerGetter, Method open) {
        this.hostField = hostField;
        this.managerGetter = managerGetter;
        this.open = open;
    }

    static InboxNativeRoute find(Class<?> viewClass, Class<?> bundleClass, String navigatorPackage) {
        InboxNativeRoute found = null;
        for (Class<?> type = viewClass; type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !field.getType().isInterface()) continue;
                for (Method getter : field.getType().getMethods()) {
                    if (Modifier.isStatic(getter.getModifiers()) || getter.getParameterCount() != 0
                            || !getter.getReturnType().getName().startsWith(navigatorPackage)) continue;
                    Method open = null;
                    for (Method method : getter.getReturnType().getMethods()) {
                        Class<?>[] args = method.getParameterTypes();
                        if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != Void.TYPE
                                || args.length != 4 || args[0] != Class.class || args[1] != bundleClass
                                || args[2] != Integer.TYPE || args[3] != Boolean.TYPE) continue;
                        if (open != null) return null;
                        open = method;
                    }
                    if (open == null) continue;
                    if (found != null) return null;
                    found = new InboxNativeRoute(field, getter, open);
                }
            }
        }
        return found;
    }

    boolean open(Object view, Class<?> destination) throws ReflectiveOperationException {
        hostField.setAccessible(true);
        Object host = hostField.get(view);
        if (host == null) return false;
        Object manager = managerGetter.invoke(host);
        if (manager == null) return false;
        open.invoke(manager, destination, null, 1, true);
        return true;
    }
}
