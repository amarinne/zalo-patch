package com.ez.zalopatch.xposed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;

public final class XpReflectTest {
    @Test
    public void invokesPrivateInheritedMethodsAndPrefersTheNearestDeclaration() throws Throwable {
        Child child = new Child();
        assertEquals("base-private", XpReflect.callMethod(child, "inherited"));
        assertEquals("child", XpReflect.callMethod(child, "name"));
        assertEquals("base", XpReflect.callMethod(new Base(), "name"));
    }

    @Test
    public void keepsStaticAndInstanceSelectionSeparate() throws Throwable {
        assertSame(Base.INSTANCE, XpReflect.callStaticMethod(Child.class, "singleton"));
        assertThrows(NoSuchMethodException.class, () -> XpReflect.callMethod(new Child(), "singleton"));
        assertThrows(NoSuchMethodException.class, () -> XpReflect.callStaticMethod(Child.class, "name"));
    }

    @Test
    public void preservesReflectionOrderForAmbiguousReferenceOverloads() throws Throwable {
        Method first = null;
        for (Method method : Child.class.getDeclaredMethods()) {
            if (method.getName().equals("overload") && !Modifier.isStatic(method.getModifiers())) {
                first = method;
                break;
            }
        }
        first.setAccessible(true);
        Child target = new Child();
        assertEquals(first.invoke(target, "value"), XpReflect.callMethod(target, "overload", "value"));
        assertEquals(first.invoke(target, new Object[]{null}),
                XpReflect.callMethod(target, "overload", new Object[]{null}));
    }

    @Test
    public void matchesAllPrimitiveWrappersWithoutNumericWidening() throws Throwable {
        Child target = new Child();
        assertEquals("primitives", XpReflect.callMethod(target, "primitives",
                true, (byte) 1, 'a', (short) 2, 3, 4L, 5F, 6D));
        assertThrows(NoSuchMethodException.class, () -> XpReflect.callMethod(target, "number", 1));
        assertThrows(NoSuchMethodException.class,
                () -> XpReflect.callMethod(target, "number", new Object[]{null}));
        assertEquals(1L, XpReflect.callMethod(target, "number", 1L));
    }

    @Test
    public void missingNameAndWrongArityKeepTheDiagnostic() {
        NoSuchMethodException missing = assertThrows(NoSuchMethodException.class,
                () -> XpReflect.callMethod(new Child(), "absent", "value"));
        assertEquals(Child.class.getName() + "#absent(1 args) not found", missing.getMessage());
        assertThrows(NoSuchMethodException.class, () -> XpReflect.callMethod(new Child(), "name", 1));
    }

    @Test
    public void targetExceptionsStayWrappedAndDoNotRetryTheParent() {
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> XpReflect.callMethod(new Child(), "fail"));
        assertSame(Child.FAILURE, failure.getCause());
    }

    @Test
    public void searchesPastAnIncompatibleChildDeclaration() throws Throwable {
        assertEquals("base-reference", XpReflect.callMethod(new Child(), "compatible", "value"));
        assertEquals("child-number", XpReflect.callMethod(new Child(), "compatible", 1));
    }

    @Test
    public void lateSuperclassLinkageFailureStillPreventsInvocation() throws Exception {
        String prefix = XpReflectTest.class.getName() + "$Linkage";
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith(prefix)) return super.loadClass(name, resolve);
                if (name.equals(prefix + "Missing")) throw new ClassNotFoundException(name);
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try (InputStream input = getResourceAsStream(name.replace('.', '/') + ".class")) {
                        byte[] bytes = input.readAllBytes();
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (Exception error) {
                        throw new ClassNotFoundException(name, error);
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        };
        Class<?> child = loader.loadClass(prefix + "Child");
        Object target = child.getConstructor().newInstance();
        assertThrows(NoClassDefFoundError.class, () -> XpReflect.callMethod(target, "present"));
        assertFalse(child.getField("invoked").getBoolean(target));
    }

    public static class Base {
        static final Base INSTANCE = new Base();
        private String inherited() { return "base-private"; }
        private String name() { return "base"; }
        private static Base singleton() { return INSTANCE; }
        private String fail() { return "must not retry"; }
        private String compatible(Object value) { return "base-reference"; }
    }

    public static final class Child extends Base {
        static final IllegalStateException FAILURE = new IllegalStateException("fixture failure");
        private String name() { return "child"; }
        private String overload(Object value) { return "object"; }
        private String overload(CharSequence value) { return "sequence"; }
        private String primitives(boolean a, byte b, char c, short d, int e, long f, float g, double h) {
            return "primitives";
        }
        private long number(long value) { return value; }
        private String fail() { throw FAILURE; }
        private String compatible(int value) { return "child-number"; }
    }

    public static class LinkageMissing {}
    public static class LinkageBase {
        public void unrelated(LinkageMissing missing) {}
    }
    public static final class LinkageChild extends LinkageBase {
        public boolean invoked;
        public String present() { invoked = true; return "present"; }
    }
}
