package com.ez.zalopatch.xposed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/** Install and early-return contracts of the API 102 adapter, against a disposable framework. */
public final class XpHooksAdapterTest {

    @After
    public void detach() {
        XpHooks.unhookAll();
        XpHooks.attach(null);
    }

    @Test
    public void aFailedOverloadRollsBackTheOnesAlreadyHooked() {
        Fake fake = new Fake(2);
        XpHooks.attach(fake.framework);

        assertThrows(IllegalStateException.class, () -> XpHooks.hookAllMethods(
                "test", Overloads.class, "probe", (XpHooks.Before) param -> { }));

        assertEquals("the overload already hooked must be undone", 1, fake.unhooked.size());
        assertEquals("rolled-back hooks must leave the registry", 0, XpHooks.installedCount());
    }

    @Test
    public void aBeforeThrowableSkipsTheOriginalCallAndStillRunsAfter() {
        Fake fake = new Fake(0);
        XpHooks.attach(fake.framework);
        IllegalStateException blocked = new IllegalStateException("blocked");
        List<String> calls = new ArrayList<>();

        XpHooks.hookMethod("test", probe(int.class), new XpHooks.Before() {
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
                () -> fake.lastHooker().intercept(fake.chain(probe(int.class))));

        assertSame(blocked, thrown);
        assertEquals("the original call must be skipped", 0, fake.proceeded);
        assertEquals(List.of("before", "after"), calls);
    }

    /** Declared overloads used by the {@code hookAllMethods} case. */
    public static final class Overloads {
        public int probe(int value) {
            return value;
        }

        public int probe(String value) {
            return 0;
        }
    }

    private static Method probe(Class<?> parameter) {
        try {
            return Overloads.class.getDeclaredMethod("probe", parameter);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError(missing);
        }
    }

    /** Framework double: fails a chosen install, records unhooks, and answers chain calls. */
    private static final class Fake {
        final XposedInterface framework;
        final List<XposedInterface.HookHandle> unhooked = new ArrayList<>();
        final List<XposedInterface.Hooker> hookers = new ArrayList<>();
        int proceeded;
        private final int failAt;
        private int installs;

        Fake(int failAt) {
            this.failAt = failAt;
            this.framework = (XposedInterface) Proxy.newProxyInstance(
                    Fake.class.getClassLoader(), new Class<?>[]{XposedInterface.class},
                    (self, method, args) -> {
                        if (!"hook".equals(method.getName())) {
                            throw new UnsupportedOperationException(method.getName());
                        }
                        if (++installs == failAt) {
                            throw new IllegalStateException("refused");
                        }
                        return new Builder((Executable) args[0]);
                    });
        }

        XposedInterface.Hooker lastHooker() {
            return hookers.get(hookers.size() - 1);
        }

        XposedInterface.Chain chain(Executable executable) {
            return new Chain(executable);
        }

        private final class Builder implements XposedInterface.HookBuilder {
            private final Executable executable;

            Builder(Executable executable) {
                this.executable = executable;
            }

            @Override public XposedInterface.HookBuilder setPriority(int priority) { return this; }
            @Override public XposedInterface.HookBuilder setExceptionMode(XposedInterface.ExceptionMode mode) { return this; }
            @Override public XposedInterface.HookBuilder setId(String id) { return this; }

            @Override
            public XposedInterface.HookHandle intercept(XposedInterface.Hooker hooker) {
                hookers.add(hooker);
                return new Handle(executable);
            }
        }

        private final class Handle implements XposedInterface.HookHandle {
            private final Executable executable;

            Handle(Executable executable) {
                this.executable = executable;
            }

            @Override public void unhook() { unhooked.add(this); }
            @Override public Executable getExecutable() { return executable; }
            @Override public String getId() { return "test"; }
            @Override public XposedInterface.HookHandle replaceHook(XposedInterface.Hooker hooker) { return this; }
        }

        private final class Chain implements XposedInterface.Chain {
            private final Executable executable;

            Chain(Executable executable) {
                this.executable = executable;
            }

            @Override public Executable getExecutable() { return executable; }
            @Override public Object getThisObject() { return null; }
            @Override public List<Object> getArgs() { return Collections.emptyList(); }
            @Override public Object getArg(int index) { return null; }

            @Override
            public Object proceed() {
                proceeded++;
                return 42;
            }

            @Override public Object proceed(Object[] args) { return proceed(); }
            @Override public Object proceedWith(Object value) { return proceed(); }
            @Override public Object proceedWith(Object value, Object[] args) { return proceed(); }
        }
    }
}
