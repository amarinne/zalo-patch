package com.ez.zalopatch.xposed.core;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import com.ez.zalopatch.DexKitVideoAdsFingerprint;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public final class VideoAdsTargetsTest {
    public abstract static class Base extends View {
        Base(Context context, AttributeSet attrs) { super(context, attrs); }
        public long getStartTimeShow() { return 0; }
        public abstract void bind(Payload value, Callback callback);
    }
    public static class Payload { }
    public static class Callback { }
    public static final class First extends Base {
        public First(Context context, AttributeSet attrs) { super(context, attrs); }
        @Override public void bind(Payload value, Callback callback) { }
    }
    public static final class Second extends Base {
        public Second(Context context, AttributeSet attrs) { super(context, attrs); }
        @Override public void bind(Payload value, Callback callback) { }
    }
    public static class OpenChild extends Base {
        public OpenChild(Context context, AttributeSet attrs) { super(context, attrs); }
        @Override public void bind(Payload value, Callback callback) { }
    }
    public static final class Grandchild extends OpenChild {
        public Grandchild(Context context, AttributeSet attrs) { super(context, attrs); }
    }
    public static final class MissingConstructor extends Base {
        public MissingConstructor(Context context) { super(context, null); }
        @Override public void bind(Payload value, Callback callback) { }
    }

    private static final ClassLoader LOADER = VideoAdsTargetsTest.class.getClassLoader();

    @Test public void concreteOwnersBindTheirOwnExactOverrides() throws Exception {
        DexKitVideoAdsFingerprint.Candidate candidate = VideoAdsTargets.candidate(LOADER,
                Base.class.getName(), "getStartTimeShow", Arrays.asList(First.class.getName(), Second.class.getName()));
        assertFalse(candidate.concreteView);
        assertEquals(2, candidate.implementations.size());
        DexKitVideoAdsFingerprint.Route route = DexKitVideoAdsFingerprint.Route.NATIVE;
        Map<String, String> descriptors = new LinkedHashMap<>();
        descriptors.put(route.anchor("view_class"), Base.class.getName());
        descriptors.put(route.anchor("bind_method"), "bind");
        descriptors.put(route.anchor("implementation_classes"), First.class.getName() + "," + Second.class.getName());
        assertEquals(Arrays.asList(First.class, Second.class), VideoAdsTargets.owners(LOADER, route, descriptors));
        for (Class<?> owner : VideoAdsTargets.owners(LOADER, route, descriptors)) {
            assertEquals(owner, VideoAdsTargets.constructor(owner).getDeclaringClass());
            Method binder = VideoAdsTargets.binders(LOADER, route, descriptors, owner).get(0);
            assertEquals(owner, binder.getDeclaringClass());
            assertFalse(Modifier.isAbstract(binder.getModifiers()));
            assertArrayEquals(new Class<?>[]{Payload.class, Callback.class}, binder.getParameterTypes());
        }
        assertThrows(NoSuchMethodException.class, () -> VideoAdsTargets.binders(LOADER, route, descriptors, Base.class));
    }

    @Test public void unsafeAncestryAndMissingXmlConstructorsAreRejected() {
        for (Class<?> type : Arrays.asList(OpenChild.class, Grandchild.class, Base.class, String.class)) {
            assertThrows(IllegalArgumentException.class, () -> VideoAdsTargets.candidate(LOADER,
                    Base.class.getName(), "getStartTimeShow", Collections.singletonList(type.getName())));
        }
        assertThrows(NoSuchMethodException.class, () -> VideoAdsTargets.candidate(LOADER,
                Base.class.getName(), "getStartTimeShow", Collections.singletonList(MissingConstructor.class.getName())));
        assertThrows(IllegalArgumentException.class, () -> VideoAdsTargets.candidate(LOADER,
                String.class.getName(), "length", Collections.emptyList()));
    }
}
