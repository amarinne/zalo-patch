package com.ez.zalopatch.xposed.features;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests for the runtime consumer-adapter predicate. */
public final class BottomTabsConsumerTest {
    @SuppressWarnings("unused")
    static final class NoArrays {
        int count;
        String name;
    }

    @Test
    public void samePackageArrayHolderQualifies() {
        assertTrue(BottomTabsFeature.looksLikeConsumerAdapter(
                oh1.FakeAdapter.class, "oh1.w"));
    }

    @Test
    public void stateClassItselfExcluded() {
        assertFalse(BottomTabsFeature.looksLikeConsumerAdapter(
                oh1.FakeAdapter.class, "oh1.FakeAdapter"));
    }

    @Test
    public void missingArrayKindDisqualifies() {
        assertFalse(BottomTabsFeature.looksLikeConsumerAdapter(
                oh1.FakeOther.class, "oh1.w"));
    }

    @Test
    public void frameworkAndMismatchedPackagesRejected() {
        assertFalse(BottomTabsFeature.looksLikeConsumerAdapter(
                NoArrays.class, "oh1.w"));
        assertFalse(BottomTabsFeature.looksLikeConsumerAdapter(
                java.util.ArrayList.class, "oh1.w"));
        assertFalse(BottomTabsFeature.looksLikeConsumerAdapter(null, "oh1.w"));
        assertFalse(BottomTabsFeature.looksLikeConsumerAdapter(
                oh1.FakeAdapter.class, ""));
        assertFalse(BottomTabsFeature.looksLikeConsumerAdapter(
                oh1.FakeAdapter.class, "other.State"));
    }
}
