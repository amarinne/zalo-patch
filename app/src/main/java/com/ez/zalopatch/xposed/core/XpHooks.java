package com.ez.zalopatch.xposed.core;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Project-owned hook adapter around LibXposed API 102.
 *
 * <p>The legacy API 82 contract exposed split before/after callbacks with early return,
 * result replacement, and throwable inspection. API 102 exposes a single interceptor chain
 * instead, so every feature expresses its hooks through {@link Before} and {@link After}
 * against {@link HookParam} and this class translates them onto one {@code intercept}
 * implementation per hooked executable. This is the only place that touches the raw
 * framework hook types, which keeps feature logic readable and gives hot reload one
 * registry to replace or remove hooks from.
 *
 * <p>Semantics preserved from the legacy contract:
 *
 * <ul>
 *   <li>{@code setResult} inside {@link Before} skips the original call (early return);
 *       {@link After} still runs, matching {@code XC_MethodHook} behavior.</li>
 *   <li>{@code setResult} inside {@link After} replaces the returned value and clears a
 *       pending throwable.</li>
 *   <li>A callback that throws does not break the host call: the failure is logged and
 *       the chain continues as if the hook were absent (legacy protective mode).</li>
 *   <li>{@code hookAllMethods} arms every declared overload sharing a name, and
 *       {@code hookAllConstructors} arms every declared constructor.</li>
 * </ul>
 *
 * <p>Every installed hook carries a stable id of {@code featureId + "|" + executable},
 * so reinstalling the same feature deterministically replaces its previous hooks
 * instead of stacking duplicates.
 */
public final class XpHooks {
    private static volatile XposedInterface api;
    private static final CopyOnWriteArrayList<XposedInterface.HookHandle> HANDLES =
            new CopyOnWriteArrayList<>();

    private XpHooks() {
    }

    /** Attaches the framework interface for the current module generation. */
    public static void attach(XposedInterface framework) {
        api = framework;
        XpLog.attach(framework);
    }

    public static XposedInterface framework() {
        return api;
    }

    /** Runtime Xposed API version, or 0 when no framework is attached. */
    public static int apiVersion() {
        try {
            XposedInterface framework = api;
            return framework == null ? 0 : Math.max(0, framework.getApiVersion());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Before-callback: runs before the original call; {@code setResult} skips it. */
    public interface Before {
        void before(HookParam param) throws Throwable;
    }

    /** After-callback: runs after the original call with result or throwable visible. */
    public interface After {
        void after(HookParam param) throws Throwable;
    }

    /** Single installed hook; replaces {@code XC_MethodHook.Unhook} at call sites. */
    public static final class Handle {
        private volatile XposedInterface.HookHandle raw;

        private Handle(XposedInterface.HookHandle raw) {
            this.raw = raw;
        }

        public void unhook() {
            XposedInterface.HookHandle handle = raw;
            if (handle != null) {
                try {
                    handle.unhook();
                } catch (Throwable ignored) {
                }
                raw = null;
            }
        }
    }

    /** Mutable per-call state; field access mirrors the legacy {@code MethodHookParam}. */
    public static final class HookParam {
        public Object[] args;
        public Object thisObject;
        public final Executable method;

        private Object result;
        private boolean resultReady;
        private Throwable throwable;

        private HookParam(Object thisObject, Object[] args, Executable method) {
            this.thisObject = thisObject;
            this.args = args;
            this.method = method;
        }

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.resultReady = true;
            this.throwable = null;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            if (throwable != null) {
                this.resultReady = false;
            }
        }
    }

    private static final class Adapter implements XposedInterface.Hooker {
        private final String owner;
        private final Before before;
        private final After after;

        Adapter(String owner, Before before, After after) {
            this.owner = owner;
            this.before = before;
            this.after = after;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            List<Object> chainArgs = chain.getArgs();
            Object[] args = chainArgs == null
                    ? new Object[0]
                    : chainArgs.toArray(new Object[0]);
            HookParam param = new HookParam(chain.getThisObject(), args, chain.getExecutable());
            if (before != null) {
                try {
                    before.before(param);
                } catch (Throwable callbackFailure) {
                    XpLog.w("[" + owner + "] before hook failed, continuing: "
                            + callbackFailure);
                    param.resultReady = false;
                    param.throwable = null;
                }
            }
            if (!param.resultReady) {
                try {
                    param.result = chain.proceed(param.args);
                } catch (Throwable callFailure) {
                    param.throwable = callFailure;
                }
                try {
                    // Constructor interception reports the new instance only after
                    // the call completes; methods report the same receiver again.
                    param.thisObject = chain.getThisObject();
                } catch (Throwable ignored) {
                }
            }
            if (after != null) {
                Object resultBeforeCallback = param.result;
                boolean resultReadyBeforeCallback = param.resultReady;
                Throwable throwableBeforeCallback = param.throwable;
                try {
                    after.after(param);
                } catch (Throwable callbackFailure) {
                    XpLog.w("[" + owner + "] after hook failed, continuing: "
                            + callbackFailure);
                    // Legacy protective mode discards partial result or throwable
                    // changes made by an after-callback that then fails.
                    param.result = resultBeforeCallback;
                    param.resultReady = resultReadyBeforeCallback;
                    param.throwable = throwableBeforeCallback;
                }
            }
            if (param.throwable != null) {
                throw param.throwable;
            }
            return param.result;
        }
    }

    private static XposedInterface requireApi() {
        XposedInterface framework = api;
        if (framework == null) {
            throw new IllegalStateException("XpHooks used before framework attach");
        }
        return framework;
    }

    private static String hookId(String owner, Executable executable) {
        return owner + "|" + executable;
    }

    private static Handle install(
            String owner, Executable executable, Before before, After after) {
        XposedInterface framework = requireApi();
        Adapter adapter = new Adapter(owner, before, after);
        XposedInterface.HookHandle raw;
        try {
            raw = framework.hook(executable)
                    .setId(hookId(owner, executable))
                    .intercept(adapter);
        } catch (RuntimeException installFailure) {
            throw installFailure;
        }
        HANDLES.add(raw);
        return new Handle(raw);
    }

    /** Hooks every declared method overload sharing {@code name}, like legacy hookAllMethods. */
    public static List<Handle> hookAllMethods(Class<?> clazz, String name, Before before) {
        return hookAllMethods("hook", clazz, name, before, null);
    }

    /** Hooks every declared method overload sharing {@code name}, like legacy hookAllMethods. */
    public static List<Handle> hookAllMethods(Class<?> clazz, String name, After after) {
        return hookAllMethods("hook", clazz, name, null, after);
    }

    public static List<Handle> hookAllMethods(String owner, Class<?> clazz, String name,
                                              After after) {
        return hookAllMethods(owner, clazz, name, null, after);
    }

    public static List<Handle> hookAllMethods(String owner, Class<?> clazz, String name,
                                              Before before) {
        return hookAllMethods(owner, clazz, name, before, null);
    }

    public static List<Handle> hookAllMethods(
            String owner, Class<?> clazz, String name, Before before, After after) {
        List<Handle> installed = new ArrayList<>();
        if (clazz == null || name == null) {
            return installed;
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (!name.equals(method.getName())) {
                continue;
            }
            try {
                method.setAccessible(true);
                installed.add(install(owner, method, before, after));
            } catch (Throwable installFailure) {
                throw new IllegalStateException(
                        "Failed to hook " + clazz.getName() + "#" + name,
                        installFailure);
            }
        }
        return installed;
    }

    /** Hooks a single resolved method, like legacy hookMethod. */
    public static Handle hookMethod(Method method, Before before) {
        return hookMethod("hook", method, before, null);
    }

    /** Hooks a single resolved method, like legacy hookMethod. */
    public static Handle hookMethod(Method method, After after) {
        return hookMethod("hook", method, null, after);
    }

    public static Handle hookMethod(String owner, Method method, Before before, After after) {
        if (method == null) {
            throw new IllegalArgumentException("Cannot hook a null method");
        }
        try {
            method.setAccessible(true);
            return install(owner, method, before, after);
        } catch (RuntimeException installFailure) {
            throw new IllegalStateException("Failed to hook " + method, installFailure);
        }
    }

    public static Handle hookMethod(String owner, Method method, Before before) {
        return hookMethod(owner, method, before, null);
    }

    public static Handle hookMethod(String owner, Method method, After after) {
        return hookMethod(owner, method, null, after);
    }

    /** Hooks every declared constructor, like legacy hookAllConstructors. */
    public static List<Handle> hookAllConstructors(Class<?> clazz, Before before, After after) {
        return hookAllConstructors("hook", clazz, before, after);
    }

    public static List<Handle> hookAllConstructors(
            String owner, Class<?> clazz, Before before, After after) {
        List<Handle> installed = new ArrayList<>();
        if (clazz == null) {
            return installed;
        }
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            try {
                constructor.setAccessible(true);
                installed.add(install(owner, constructor, before, after));
            } catch (Throwable installFailure) {
                throw new IllegalStateException(
                        "Failed to hook " + clazz.getName() + "#<init>", installFailure);
            }
        }
        return installed;
    }

    /**
     * Resolves {@code className#methodName} with exact parameter types and hooks it, like
     * legacy {@code findAndHookMethod}. Lookup failures surface as unchecked
     * {@link IllegalStateException}, matching the legacy helper's unchecked failure mode.
     */
    public static Handle findAndHookMethod(
            String owner, String className, ClassLoader classLoader, String methodName,
            Class<?>[] parameterTypes, Before before, After after) {
        try {
            Class<?> clazz = XpReflect.findClass(className, classLoader);
            return findAndHookMethod(owner, clazz, methodName, parameterTypes, before, after);
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Throwable checked) {
            throw new IllegalStateException(
                    "Cannot resolve " + className + "#" + methodName, checked);
        }
    }

    /** Class-based variant of {@link #findAndHookMethod(String, String, ClassLoader,
     * String, Class[], Before, After)}. */
    public static Handle findAndHookMethod(
            String owner, Class<?> clazz, String methodName,
            Class<?>[] parameterTypes, Before before, After after) {
        if (clazz == null) {
            throw new IllegalArgumentException("Cannot hook a method on a null class");
        }
        try {
            Method method = clazz.getDeclaredMethod(methodName, parameterTypes);
            return hookMethod(owner, method, before, after);
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Throwable checked) {
            throw new IllegalStateException(
                    "Cannot resolve " + clazz.getName() + "#" + methodName, checked);
        }
    }

    /** Removes every hook installed through this adapter in the current generation. */

    /**
     * Invokes the original method bypassing every hook on it (Origin invoker).
     * For re-entrant calls the module makes itself, where the legacy code used a
     * guard flag to ignore its own hook firing.
     */
    public static Object invokeOriginal(Method method, Object receiver, Object... args)
            throws Throwable {
        XposedInterface framework = requireApi();
        try {
            return framework.getInvoker(method)
                    .setType(XposedInterface.Invoker.Type.ORIGIN)
                    .invoke(receiver, args);
        } catch (java.lang.reflect.InvocationTargetException thrown) {
            Throwable cause = thrown.getCause();
            throw cause != null ? cause : thrown;
        }
    }

    public static void unhookAll() {
        for (XposedInterface.HookHandle raw : HANDLES) {
            try {
                raw.unhook();
            } catch (Throwable ignored) {
            }
        }
        HANDLES.clear();
    }

    /** Number of hooks installed through this adapter in the current generation. */
    public static int installedCount() {
        return HANDLES.size();
    }
}
