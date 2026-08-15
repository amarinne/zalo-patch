package com.ez.zalopatch.xposed.features;

import java.lang.reflect.Method;
import java.util.Collection;

final class TelemetryDaoShape {
    private TelemetryDaoShape() {
    }

    static boolean isWriteMethod(Method method) {
        if (method.getParameterTypes().length != 1
                || Collection.class.isAssignableFrom(method.getParameterTypes()[0])) {
            return false;
        }
        Class<?> returnType = method.getReturnType();
        return returnType == Void.TYPE || returnType == Long.TYPE || returnType == Integer.TYPE;
    }
}
