package com.ez.zalopatch.xposed.features;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Keeps the observed setter, receiver, source list, and extra arguments together. */
final class InboxListUpdate {
    final Method method;
    final Object adapter;
    final List<Object> source;
    private final Object[] arguments;

    InboxListUpdate(Method method, Object adapter, Object[] arguments) {
        this.method = method;
        this.adapter = adapter;
        this.arguments = arguments.clone();
        this.source = new ArrayList<>((List<?>) arguments[0]);
    }

    Object[] withItems(List<?> items) {
        Object[] replay = arguments.clone();
        replay[0] = items;
        return replay;
    }

    static boolean accepts(Method method) {
        Class<?>[] params = method.getParameterTypes();
        return !Modifier.isStatic(method.getModifiers())
                && method.getReturnType() == Void.TYPE
                && params.length > 0 && List.class.isAssignableFrom(params[0]);
    }
}
