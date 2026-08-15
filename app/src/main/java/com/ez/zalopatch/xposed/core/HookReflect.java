package com.ez.zalopatch.xposed.core;

import java.lang.reflect.Field;

public final class HookReflect {
    private HookReflect() {
    }

    public static Object findFieldValueByClassName(Object target, String className) throws Throwable {
        if (target == null) {
            return null;
        }
        for (Class<?> current = target.getClass(); current != null;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(target);
                if (value != null && className.equals(value.getClass().getName())) {
                    return value;
                }
            }
        }
        return null;
    }
}
