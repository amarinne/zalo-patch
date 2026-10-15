package com.ez.zalopatch.xposed.core;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import com.ez.zalopatch.DexKitVideoAdsFingerprint;
import com.ez.zalopatch.SymbolSchema;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The same concrete owner and declared-method checks guard scan, preflight, and install. */
public final class VideoAdsTargets {
    private VideoAdsTargets() { }

    public static DexKitVideoAdsFingerprint.Candidate candidate(ClassLoader loader,
            String owner, String markerName) throws Exception {
        return candidate(loader, owner, markerName, java.util.Collections.emptyList());
    }

    public static DexKitVideoAdsFingerprint.Candidate candidate(ClassLoader loader,
            String owner, String markerName, List<String> implementationNames) throws Exception {
        Class<?> type = Class.forName(owner, false, loader);
        if (!View.class.isAssignableFrom(type) || type.isInterface()) {
            throw new IllegalArgumentException("ad owner is not a View class");
        }
        List<DexKitVideoAdsFingerprint.MethodShape> methods = shapes(type);
        DexKitVideoAdsFingerprint.MethodShape marker = null;
        for (DexKitVideoAdsFingerprint.MethodShape method : methods) {
            if (method.name.equals(markerName)) marker = method;
        }
        boolean concrete = !Modifier.isAbstract(type.getModifiers());
        if (concrete) constructor(type);
        List<DexKitVideoAdsFingerprint.Candidate> implementations = new ArrayList<>();
        for (String name : implementationNames) {
            Class<?> implementation = Class.forName(name, false, loader);
            // Final direct children make this complete without a second, unbounded hierarchy scan.
            if (implementation.getSuperclass() != type || !Modifier.isFinal(implementation.getModifiers())
                    || Modifier.isAbstract(implementation.getModifiers()) || implementation.isInterface()) {
                throw new IllegalArgumentException("ad implementation is not a final direct subclass");
            }
            constructor(implementation);
            implementations.add(new DexKitVideoAdsFingerprint.Candidate(name, null, true,
                    shapes(implementation)));
        }
        return new DexKitVideoAdsFingerprint.Candidate(owner, marker, concrete, methods, implementations);
    }

    private static List<DexKitVideoAdsFingerprint.MethodShape> shapes(Class<?> type) {
        List<DexKitVideoAdsFingerprint.MethodShape> methods = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            List<String> parameters = new ArrayList<>();
            for (Class<?> parameter : method.getParameterTypes()) parameters.add(parameter.getName());
            DexKitVideoAdsFingerprint.MethodShape shape = new DexKitVideoAdsFingerprint.MethodShape(
                    method.getName(), method.getReturnType().getName(), parameters,
                    Modifier.isStatic(method.getModifiers()), Modifier.isAbstract(method.getModifiers()));
            methods.add(shape);
        }
        return methods;
    }

    public static boolean check(ClassLoader loader, DexKitVideoAdsFingerprint.Route route,
                                Map<String, String> descriptors, List<String> errors) {
        try {
            for (String key : route.anchors()) {
                if (descriptors.get(key) == null || descriptors.get(key).isEmpty()) {
                    errors.add(route.id + " anchors incomplete");
                    return false;
                }
            }
            Map<String, String> shape = DexKitVideoAdsFingerprint.shape(route, candidate(loader,
                    descriptors.get(route.anchor("view_class")),
                    descriptors.get(route.anchor("marker_method")), implementationNames(route, descriptors)));
            for (String key : route.anchors()) {
                if (!descriptors.get(key).equals(shape.get(key))) {
                    errors.add(route.id + " concrete ad view relationship changed");
                    return false;
                }
            }
            return true;
        } catch (Throwable throwable) {
            errors.add(route.id + ": " + throwable.getClass().getSimpleName());
            return false;
        }
    }

    private static List<String> implementationNames(DexKitVideoAdsFingerprint.Route route,
                                                   Map<String, String> descriptors) {
        if (!route.implementations()) return java.util.Collections.emptyList();
        String value = descriptors.get(route.anchor("implementation_classes"));
        if (!com.ez.zalopatch.DexKitAnchors.validDescriptor(
                com.ez.zalopatch.DexKitAnchors.find(route.anchor("implementation_classes")), value)) {
            throw new IllegalArgumentException("invalid ad implementation list");
        }
        return java.util.Arrays.asList(value.split(","));
    }

    public static List<Class<?>> owners(ClassLoader loader, DexKitVideoAdsFingerprint.Route route,
                                         Map<String, String> descriptors) throws Exception {
        List<Class<?>> owners = new ArrayList<>();
        List<String> names = route.implementations() ? implementationNames(route, descriptors)
                : java.util.Collections.singletonList(descriptors.get(route.anchor("view_class")));
        for (String name : names) owners.add(Class.forName(name, false, loader));
        return owners;
    }

    public static List<Method> binders(ClassLoader loader, DexKitVideoAdsFingerprint.Route route,
                                      Map<String, String> descriptors, Class<?> owner) throws Exception {
        List<Method> methods = new ArrayList<>();
        Class<?> base = Class.forName(descriptors.get(route.anchor("view_class")), false, loader);
        String[] leaves = route.item() || route == DexKitVideoAdsFingerprint.Route.TEMPLATE
                ? new String[]{"bind_method", "payload_bind_method"}
                : route == DexKitVideoAdsFingerprint.Route.NATIVE ? new String[]{"bind_method"}
                : new String[]{"marker_method"};
        for (String leaf : leaves) {
            Method contract = method(base, descriptors.get(route.anchor(leaf)));
            Method binder = owner.getDeclaredMethod(contract.getName(), contract.getParameterTypes());
            if (Modifier.isAbstract(binder.getModifiers()) || Modifier.isStatic(binder.getModifiers())
                    || binder.getReturnType() != contract.getReturnType()) {
                throw new NoSuchMethodException("concrete ad binder required");
            }
            methods.add(binder);
        }
        return methods;
    }

    public static Map<String, String> bindings(SymbolSchema.Active schema,
                                              DexKitVideoAdsFingerprint.Route route) {
        Map<String, String> descriptors = new java.util.LinkedHashMap<>();
        for (String key : route.anchors()) descriptors.put(key,
                schema == null ? "" : schema.string(key, ""));
        return descriptors;
    }

    public static Constructor<?> constructor(Class<?> type) throws Exception {
        return type.getDeclaredConstructor(Context.class, AttributeSet.class);
    }

    public static Method method(Class<?> type, String name) throws Exception {
        Method found = null;
        for (Method method : type.getDeclaredMethods()) if (method.getName().equals(name)) {
            if (found != null) throw new NoSuchMethodException("ambiguous " + name);
            found = method;
        }
        if (found == null) throw new NoSuchMethodException(name);
        return found;
    }
}
