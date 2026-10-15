package com.ez.zalopatch;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public final class BottomTabsArraysTest {
    private enum Tab { MESSAGE, PHONEBOOK, GROUP, DISCOVERY, TIMELINE, ME }

    @Test public void offOnOffPreservesNativeInputAndRestoresEveryTabIdentity() {
        List<Tab> nativeTabs = new ArrayList<>(Arrays.asList(
                Tab.MESSAGE, Tab.PHONEBOOK, Tab.DISCOVERY, Tab.TIMELINE, Tab.ME));
        int[] nativeIcons = {101, 102, 104, 105, 106};
        boolean[] nativePreloaded = {true, false, true, false, true};
        BottomTabsArrays.Result off = BottomTabsArrays.configure(nativeTabs,
                nativeIcons, nativePreloaded, false, false, false, null, 0);

        BottomTabsArrays.Result on = BottomTabsArrays.configure(nativeTabs,
                nativeIcons, nativePreloaded, true, true, true, Tab.GROUP, 103);
        assertEquals(Arrays.asList(Tab.MESSAGE, Tab.PHONEBOOK, Tab.GROUP, Tab.ME), on.tabs);
        assertSame(Tab.ME, on.tabs.get(3));
        assertArrayEquals(new int[]{101, 102, 103, 106}, on.icons);
        assertArrayEquals(new boolean[]{true, false, false, true}, on.preloaded);
        assertEquals(Arrays.asList(Tab.MESSAGE, Tab.PHONEBOOK, Tab.DISCOVERY,
                Tab.TIMELINE, Tab.ME), nativeTabs);
        assertArrayEquals(new int[]{101, 102, 104, 105, 106}, nativeIcons);
        assertArrayEquals(new boolean[]{true, false, true, false, true}, nativePreloaded);

        // Restart supplies the native list again, not the module's compact output.
        BottomTabsArrays.Result restored = BottomTabsArrays.configure(nativeTabs,
                nativeIcons, nativePreloaded, false, false, false, null, 0);
        assertEquals(off.tabs, restored.tabs);
        assertArrayEquals(off.icons, restored.icons);
        assertArrayEquals(off.preloaded, restored.preloaded);
        for (int i = 0; i < nativeTabs.size(); i++) {
            assertSame(nativeTabs.get(i), restored.tabs.get(i));
        }
    }

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
