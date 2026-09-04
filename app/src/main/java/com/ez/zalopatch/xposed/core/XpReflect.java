package com.ez.zalopatch.xposed.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflection helpers for hook-side code.
 *
 * <p>Replaces the {@code XposedHelpers} subset this module used under the legacy API 82
 * contract. LibXposed API 102 ships no equivalent helper, and the legacy helper class
 * cannot be called from an API 102 module, so the exact call shapes the features rely on
 * live here: class lookup, field access, and best-match method invocation. Anything not
 * listed here was unused and stays unimplemented on purpose.
 */
public final class XpReflect {
    private XpReflect() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader)
            throws ClassNotFoundException {
        return Class.forName(className, false, classLoader);
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        if (className == null || className.isEmpty() || classLoader == null) {
            return null;
        }
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }

    public static Object getObjectField(Object target, String fieldName) throws Throwable {
        return findField(target == null ? null : target.getClass(), fieldName).get(target);
    }

    public static boolean getBooleanField(Object target, String fieldName) throws Throwable {
        return findField(target.getClass(), fieldName).getBoolean(target);
    }

    public static int getIntField(Object target, String fieldName) throws Throwable {
        return findField(target.getClass(), fieldName).getInt(target);
    }

    public static long getLongField(Object target, String fieldName) throws Throwable {
        return findField(target.getClass(), fieldName).getLong(target);
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) throws Throwable {
        return findField(clazz, fieldName).get(null);
    }

    public static void setObjectField(Object target, String fieldName, Object value)
            throws Throwable {
        findField(target.getClass(), fieldName).set(target, value);
    }

    public static void setBooleanField(Object target, String fieldName, boolean value)
            throws Throwable {
        findField(target.getClass(), fieldName).setBoolean(target, value);
    }

    public static void setIntField(Object target, String fieldName, int value) throws Throwable {
        findField(target.getClass(), fieldName).setInt(target, value);
    }

    public static Object callMethod(Object target, String methodName, Object... args)
            throws Throwable {
        Method method = findMethod(target.getClass(), methodName, false, args);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args)
            throws Throwable {
        Method method = findMethod(clazz, methodName, true, args);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    public static <T> T newInstance(Class<T> clazz, Object... args) throws Throwable {
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            if (matches(constructor.getParameterTypes(), args)) {
                constructor.setAccessible(true);
                return clazz.cast(constructor.newInstance(args));
            }
        }
        throw new NoSuchMethodException(
                clazz.getName() + "#<init>(" + args.length + " args) not found");
    }

    private static Field findField(Class<?> clazz, String fieldName) throws Throwable {
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (fieldName.equals(field.getName())) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        throw new NoSuchFieldException(
                (clazz == null ? "<null>" : clazz.getName()) + "#" + fieldName + " not found");
    }

    private static Method findMethod(Class<?> clazz, String methodName, boolean staticOnly,
                                     Object[] args) throws Throwable {
        List<Method> candidates = new ArrayList<>();
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (methodName.equals(method.getName())
                        && Modifier.isStatic(method.getModifiers()) == staticOnly
                        && matches(method.getParameterTypes(), args)) {
                    candidates.add(method);
                }
            }
        }
        if (candidates.isEmpty()) {
            throw new NoSuchMethodException(
                    clazz.getName() + "#" + methodName + "(" + args.length + " args) not found");
        }
        return candidates.get(0);
    }

    private static boolean matches(Class<?>[] parameterTypes, Object[] args) {
        if (parameterTypes.length != args.length) {
            return false;
        }
        for (int index = 0; index < parameterTypes.length; index++) {
            if (!assignable(parameterTypes[index], args[index])) {
                return false;
            }
        }
        return true;
    }

    private static boolean assignable(Class<?> parameterType, Object argument) {
        if (argument == null) {
            return !parameterType.isPrimitive();
        }
        Class<?> argumentType = argument.getClass();
        if (parameterType.isPrimitive()) {
            return box(parameterType) == argumentType;
        }
        return parameterType.isAssignableFrom(argumentType);
    }

    private static Class<?> box(Class<?> primitive) {
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == char.class) return Character.class;
        if (primitive == short.class) return Short.class;
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == float.class) return Float.class;
        if (primitive == double.class) return Double.class;
        return Void.class;
    }
}
