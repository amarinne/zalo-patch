package com.ez.zalopatch.xposed.core;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Legacy API 82 adapter for the explicit LSPatch artifact. */
public final class XpHooks {
    private static final CopyOnWriteArrayList<XC_MethodHook.Unhook> HANDLES = new CopyOnWriteArrayList<>();
    private XpHooks() {}
    public static int apiVersion() { return XposedBridge.getXposedVersion(); }

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
        private volatile XC_MethodHook.Unhook raw;

        private Handle(XC_MethodHook.Unhook raw) {
            this.raw = raw;
        }

        public void unhook() {
            XC_MethodHook.Unhook handle = raw;
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

    private static Handle install(String owner, Executable executable, Before before, After after) {
        // Each callback owns its state even when multiple hooks share an owner and method.
        String stateKey = "zalopatch:" + java.util.UUID.randomUUID();
        XC_MethodHook.Unhook raw = XposedBridge.hookMethod(executable, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                HookParam param = new HookParam(call.thisObject, call.args, executable);
                call.setObjectExtra(stateKey, param);
                if (before == null) return;
                try {
                    before.before(param);
                    if (param.throwable != null) call.setThrowable(param.throwable);
                    else if (param.resultReady) call.setResult(param.result);
                } catch (Throwable failure) {
                    XpLog.w("[" + owner + "] before hook failed, continuing: " + failure);
                }
                // Legacy protective mode retains argument edits even when a callback throws.
                call.args = param.args;
            }
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (after == null) return;
                HookParam param = (HookParam) call.getObjectExtra(stateKey);
                if (param == null) param = new HookParam(call.thisObject, call.args, executable);
                param.thisObject = call.thisObject;
                param.args = call.args;
                param.result = call.getResult();
                param.throwable = call.getThrowable();
                param.resultReady = false;
                try {
                    after.after(param);
                    if (param.throwable != null) call.setThrowable(param.throwable);
                    else if (param.resultReady) call.setResult(param.result);
                } catch (Throwable failure) {
                    // Apply result changes only after successful callback completion.
                    XpLog.w("[" + owner + "] after hook failed, continuing: " + failure);
                }
            }
        });
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

    /** Hooks one qualified constructor without arming other overloads. */
    public static Handle hookConstructor(String owner, Constructor<?> constructor, Before before) {
        return hookConstructor(owner, constructor, before, null);
    }

    public static Handle hookConstructor(String owner, Constructor<?> constructor,
                                         Before before, After after) {
        if (constructor == null) throw new IllegalArgumentException("Cannot hook a null constructor");
        try {
            constructor.setAccessible(true);
            return install(owner, constructor, before, after);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Failed to hook " + constructor, failure);
        }
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
                for (Handle handle : installed) handle.unhook();
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
        try {
            return XposedBridge.invokeOriginalMethod(method, receiver, args);
        } catch (java.lang.reflect.InvocationTargetException thrown) {
            Throwable cause = thrown.getCause();
            throw cause != null ? cause : thrown;
        }
    }

    public static void unhookAll() {
        for (XC_MethodHook.Unhook raw : HANDLES) {
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
