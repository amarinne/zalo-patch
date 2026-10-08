package com.ez.zalopatch;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public final class BottomTabsArraysTest {
    @Test public void removingTabsRetainsActualHostIconsAndPreloadIdentity() {
        BottomTabsArrays.Result result = BottomTabsArrays.filter(
                Arrays.asList("MESSAGE", "PHONEBOOK", "GROUP", "DISCOVERY", "TIMELINE", "ME", "MORE"),
                Arrays.asList("MESSAGE", "PHONEBOOK", "GROUP", "ME", "MORE"),
                new int[]{101, 102, 103, 104, 105, 106, 107},
                new boolean[]{true, false, false, true, false, false, true});
        assertArrayEquals(new int[]{101, 102, 103, 106, 107}, result.icons);
        assertArrayEquals(new boolean[]{true, false, false, false, true}, result.preloaded);
        BottomTabsArrays.Result repeated = BottomTabsArrays.filter(
                Arrays.asList("MESSAGE", "PHONEBOOK", "GROUP", "ME", "MORE"),
                Arrays.asList("MESSAGE", "PHONEBOOK", "GROUP", "ME", "MORE"), result.icons, result.preloaded);
        assertArrayEquals(result.icons, repeated.icons);
    }
    @Test(expected = IllegalArgumentException.class)
    public void injectedTabWithoutHostIconFailsBeforeMutation() {
        BottomTabsArrays.filter(Arrays.asList("MESSAGE"), Arrays.asList("MESSAGE", "GROUP"),
                new int[]{101}, new boolean[]{true});
    }
    @Test(expected = IllegalArgumentException.class)
    public void mismatchedArrayFailsBeforeMutation() {
        BottomTabsArrays.filter(Arrays.asList("MESSAGE", "ME"), Arrays.asList("MESSAGE"),
                new int[]{101}, new boolean[]{true, false});
    }
    @Test(expected = IllegalArgumentException.class)
    public void ambiguousDuplicateTabFailsBeforeMutation() {
        BottomTabsArrays.filter(Arrays.asList("MESSAGE", "MESSAGE"), Arrays.asList("MESSAGE"),
                new int[]{101, 102}, new boolean[]{true, false});
    }

    @Test public void accountDisabledGroupIsRestoredWithoutChangingOtherHostIcons() {
        BottomTabsArrays.Result result = BottomTabsArrays.configure(
                Arrays.asList("MESSAGE", "PHONEBOOK", "DISCOVERY", "TIMELINE", "ME"),
                new int[]{101, 102, 104, 105, 106},
                new boolean[]{true, false, true, false, true},
                true, true, true, "GROUP", 103);
        assertEquals(Arrays.asList("MESSAGE", "PHONEBOOK", "GROUP", "ME"), result.tabs);
        assertArrayEquals(new int[]{101, 102, 103, 106}, result.icons);
        assertArrayEquals(new boolean[]{true, false, false, true}, result.preloaded);
        BottomTabsArrays.Result repeated = BottomTabsArrays.configure(result.tabs,
                result.icons, result.preloaded, true, true, true, null, 0);
        assertEquals(result.tabs, repeated.tabs);
        assertArrayEquals(result.icons, repeated.icons);
        assertArrayEquals(result.preloaded, repeated.preloaded);
    }

    @Test public void keepingGroupAlsoWorksWithDiscoveryAndTimelineVisible() {
        BottomTabsArrays.Result result = BottomTabsArrays.configure(
                Arrays.asList("MESSAGE", "PHONEBOOK", "DISCOVERY", "TIMELINE", "ME"),
                new int[]{101, 102, 104, 105, 106}, new boolean[]{true, false, false, false, false},
                false, false, true, "GROUP", 103);
        assertEquals(Arrays.asList("MESSAGE", "PHONEBOOK", "GROUP", "DISCOVERY", "TIMELINE", "ME"), result.tabs);
        assertArrayEquals(new int[]{101, 102, 103, 104, 105, 106}, result.icons);
    }

    @Test public void disablingKeepGroupLeavesNativeRebuiltStateUnchanged() {
        BottomTabsArrays.Result result = BottomTabsArrays.configure(
                Arrays.asList("MESSAGE", "PHONEBOOK", "ME"), new int[]{101, 102, 106},
                new boolean[]{true, false, false}, false, false, false, null, 0);
        assertEquals(Arrays.asList("MESSAGE", "PHONEBOOK", "ME"), result.tabs);
        assertArrayEquals(new int[]{101, 102, 106}, result.icons);
    }

    @Test public void existingGroupKeepsHostIconAndPreload() {
        BottomTabsArrays.Result result = BottomTabsArrays.configure(
                Arrays.asList("MESSAGE", "PHONEBOOK", "GROUP", "ME"), new int[]{101, 102, 203, 106},
                new boolean[]{true, false, true, false}, false, false, true, "GROUP", 103);
        assertArrayEquals(new int[]{101, 102, 203, 106}, result.icons);
        assertArrayEquals(new boolean[]{true, false, true, false}, result.preloaded);
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingNativeGroupIconRejectsRestoration() {
        BottomTabsArrays.configure(Arrays.asList("MESSAGE", "PHONEBOOK", "ME"),
                new int[]{101, 102, 106}, new boolean[]{true, false, false},
                false, false, true, "GROUP", 0);
    }
}
