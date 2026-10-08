package com.ez.zalopatch.xposed.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Pure-Java seam for typed row extraction; a mapped name alone is not a type contract. */
public final class TypedFieldAccess {
    private TypedFieldAccess() {}

    public static boolean hasType(Field field, String typeName) {
        return field != null && !Modifier.isStatic(field.getModifiers())
                && field.getType().getName().equals(typeName);
    }

    public static Field uniqueField(Class<?> owner, String typeName) {
        Field found = null;
        for (Class<?> current = owner; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!hasType(field, typeName)) continue;
                if (found != null) return null;
                found = field;
            }
        }
        return found;
    }

    public static Field resolve(Class<?> owner, String mappedName, String typeName) {
        // Count across the entire hierarchy, even when the mapped field is typed.
        Field unique = uniqueField(owner, typeName);
        if (unique == null) return null;
        Field mapped = mappedField(owner, mappedName);
        Field selected = hasType(mapped, typeName) ? mapped : unique;
        selected.setAccessible(true);
        return selected;
    }

    private static Field mappedField(Class<?> owner, String name) {
        if (name == null || name.isEmpty()) return null;
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    public static Object read(Field field, Object target, String typeName)
            throws IllegalAccessException {
        if (!hasType(field, typeName) || target == null
                || !field.getDeclaringClass().isInstance(target)) return null;
        field.setAccessible(true);
        Object value = field.get(target);
        return field.getType().isInstance(value) ? value : null;
    }
}
