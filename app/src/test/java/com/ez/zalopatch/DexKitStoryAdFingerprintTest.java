package com.ez.zalopatch;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public final class DexKitStoryAdFingerprintTest {
    private static DexKitStoryAdFingerprint.Hit hit(boolean live, boolean isStatic) {
        return new DexKitStoryAdFingerprint.Hit("fixture.StoryAdView", "bind", "void",
                Collections.singletonList("fixture.StoryAd"), isStatic, live);
    }

    @Test
    public void uniqueCompleteLiveResultSelectsBinder() {
        DexKitStoryAdFingerprint.Hit expected = hit(true, false);
        assertSame(expected, DexKitStoryAdFingerprint.select(1,
                Collections.singletonList(expected), ""));
    }

    @Test
    public void missingAmbiguousTruncatedAndFailedQueriesStayUnavailable() {
        List<DexKitStoryAdFingerprint.Hit> one = Collections.singletonList(hit(true, false));
        assertNull(DexKitStoryAdFingerprint.select(0, Collections.emptyList(), ""));
        assertNull(DexKitStoryAdFingerprint.select(2, Arrays.asList(one.get(0), one.get(0)), ""));
        assertNull(DexKitStoryAdFingerprint.select(2, one, ""));
        assertNull(DexKitStoryAdFingerprint.select(1, one, "native query failed"));
    }

    @Test
    public void staticOrUnqualifiedOwnerCannotArm() {
        assertNull(DexKitStoryAdFingerprint.select(1,
                Collections.singletonList(hit(true, true)), ""));
        assertNull(DexKitStoryAdFingerprint.select(1,
                Collections.singletonList(hit(false, false)), ""));
    }

    @Test
    public void bindingRejectsOverloadsAbstractOwnersAndStaticMethods() {
        assertNotNull(DexKitStoryAdFingerprint.binding(Fixture.class, "bind"));
        assertNull(DexKitStoryAdFingerprint.binding(Overloaded.class, "bind"));
        assertNull(DexKitStoryAdFingerprint.binding(AbstractFixture.class, "bind"));
        assertNull(DexKitStoryAdFingerprint.binding(StaticFixture.class, "bind"));
        assertNull(DexKitStoryAdFingerprint.binding(Fixture.class, "missing"));
    }

    @Test
    public void nativeExceptionsToggleOffAndOtherOwnersRemainUntouched() {
        assertTrue(DexKitStoryAdFingerprint.shouldCollapse(true, true, Fixture.class, new Fixture()));
        assertFalse(DexKitStoryAdFingerprint.shouldCollapse(false, true, Fixture.class, new Fixture()));
        assertFalse(DexKitStoryAdFingerprint.shouldCollapse(true, false, Fixture.class, new Fixture()));
        assertFalse(DexKitStoryAdFingerprint.shouldCollapse(true, true, Fixture.class, new ChildFixture()));
        assertFalse(DexKitStoryAdFingerprint.shouldCollapse(true, true, Fixture.class, null));
    }

    public static class Fixture { public void bind(Object model) { } }
    public static final class ChildFixture extends Fixture { }
    public static final class Overloaded {
        public void bind(Object model) { }
        public void bind(int model) { }
    }
    public abstract static class AbstractFixture { public void bind(Object model) { } }
    public static final class StaticFixture { public static void bind(Object model) { } }
}
