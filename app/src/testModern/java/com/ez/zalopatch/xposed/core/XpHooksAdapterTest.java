package com.ez.zalopatch.xposed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Executable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/**
 * Exercises the API 102 adapter's install and early-return contracts against a disposable
 * framework, so the rollback and callback semantics stay verifiable without a device.
 */
public final class XpHooksAdapterTest {

    @After
    public void detach() {
        XpHooks.unhookAll();
        XpHooks.attach(null);
    }

    @Test
    public void rollsBackInstalledOverloadsWhenALaterHookFails() {
        Framework framework = new Framework(2);
        XpHooks.attach(framework.proxy);

        assertThrows(IllegalStateException.class, () -> XpHooks.hookAllMethods(
                "test", Overloads.class, "probe", (XpHooks.Before) param -> { }));

        assertEquals("the overload already hooked must be undone", 1, framework.unhooked.size());
        assertEquals("rolled-back hooks must leave the registry", 0, XpHooks.installedCount());
    }

    @Test
    public void removesAnUnhookedHandleFromTheRegistry() {
        Framework framework = new Framework(0);
        XpHooks.attach(framework.proxy);

        XpHooks.Handle handle = XpHooks.hookMethod(
                "test", method("probe", int.class), (XpHooks.Before) param -> { });
        assertEquals(1, XpHooks.installedCount());

        handle.unhook();

        assertEquals(1, framework.unhooked.size());
        assertEquals(0, XpHooks.installedCount());
    }

    @Test
    public void beforeThrowableSkipsTheOriginalCallAndStillRunsAfter() {
        Framework framework = new Framework(0);
        XpHooks.attach(framework.proxy);
        IllegalStateException blocked = new IllegalStateException("blocked");
        List<String> calls = new ArrayList<>();

        XpHooks.hookMethod("test", method("probe", int.class), new XpHooks.Before() {
            @Override
            public void before(XpHooks.HookParam param) {
                calls.add("before");
                param.setThrowable(blocked);
            }
        }, new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) {
                calls.add("after");
            }
        });

        Throwable thrown = assertThrows(IllegalStateException.class,
                () -> framework.lastInterceptor().intercept(framework.chain(method("probe", int.class))));

        assertSame(blocked, thrown);
        assertEquals("the original call must be skipped", 0, framework.proceeded);
        assertEquals(List.of("before", "after"), calls);
    }

    /** Declared overloads used by {@code hookAllMethods} tests. */
    public static final class Overloads {
        public int probe(int value) {
            return value;
        }

        public int probe(String value) {
            return 0;
        }
    }

    private static Method method(String name, Class<?>... parameterTypes) {
        try {
            Method method = Overloads.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException missing) {
            throw new AssertionError(missing);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object objectMethod(Object instance, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":
                return instance == args[0];
            case "hashCode":
                return System.identityHashCode(instance);
            default:
                return "test-proxy";
        }
    }

    /** Minimal framework that can fail a chosen install and records what the adapter did. */
    private static final class Framework {
        final XposedInterface proxy;
        final List<XposedInterface.HookHandle> unhooked = new ArrayList<>();
        private final List<XposedInterface.Hooker> interceptors = new ArrayList<>();
        private final int failAtHook;
        private int hookCalls;
        int proceeded;

        Framework(int failAtHook) {
            this.failAtHook = failAtHook;
            this.proxy = proxy(XposedInterface.class, (instance, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return objectMethod(instance, method, args);
                }
                if (!"hook".equals(method.getName())) {
                    throw new UnsupportedOperationException(method.getName());
                }
                if (++hookCalls == failAtHook) {
                    throw new IllegalStateException("framework refused hook " + hookCalls);
                }
                return builder((Executable) args[0]);
            });
        }

        XposedInterface.Hooker lastInterceptor() {
            return interceptors.get(interceptors.size() - 1);
        }

        XposedInterface.Chain chain(Executable executable) {
            return proxy(XposedInterface.Chain.class, (instance, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return objectMethod(instance, method, args);
                }
                switch (method.getName()) {
                    case "getExecutable":
                        return executable;
                    case "getThisObject":
                        return null;
                    case "getArgs":
                        return Collections.emptyList();
                    case "getArg":
                        return null;
                    case "proceed":
                        proceeded++;
                        return 42;
                    case "proceedWith":
                        proceeded++;
                        return 42;
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        private XposedInterface.HookBuilder builder(Executable executable) {
            return proxy(XposedInterface.HookBuilder.class, (instance, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return objectMethod(instance, method, args);
                }
                switch (method.getName()) {
                    case "setId":
                    case "setPriority":
                    case "setExceptionMode":
                        return instance;
                    case "intercept":
                        interceptors.add((XposedInterface.Hooker) args[0]);
                        return handle(executable);
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        private XposedInterface.HookHandle handle(Executable executable) {
            return proxy(XposedInterface.HookHandle.class, (instance, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return objectMethod(instance, method, args);
                }
                switch (method.getName()) {
                    case "unhook":
                        unhooked.add((XposedInterface.HookHandle) instance);
                        return null;
                    case "getExecutable":
                        return executable;
                    case "getId":
                        return "test";
                    case "replaceHook":
                        return instance;
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            });
        }
    }
}
