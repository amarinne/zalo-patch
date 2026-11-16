package com.ez.zalopatch;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Selection is UI state only: registration gates it, installation and runtime observation only
 * describe it. No helper here may claim that a package is active.
 */
public final class InstanceSelectionTest {
    private static final String CLONE_A = "vn.example.insta";
    private static final String CLONE_B = "vn.example.instb";

    @Test public void restoreKeepsRegisteredSelectionAndFallsBackOnRevocation() {
        Set<String> registered = Set.of(CLONE_A, CLONE_B);

        assertEquals(CLONE_A, InstanceSelection.restore(CLONE_A, registered));
        assertEquals(HostPackages.ZALO, InstanceSelection.restore(CLONE_A, Set.of(CLONE_B)));
        assertEquals(HostPackages.ZALO, InstanceSelection.restore(null, registered));
        assertEquals(HostPackages.ZALO, InstanceSelection.restore(CLONE_A, Collections.emptySet()));
        assertEquals(HostPackages.ZALO, InstanceSelection.restore("com.example.unregistered", registered));
    }

    @Test public void candidatesAreOriginalFirstThenRegisteredPackagesInStableOrder() {
        List<String> candidates = InstanceSelection.candidates(Set.of(CLONE_B, CLONE_A));

        assertEquals(List.of(HostPackages.ZALO, CLONE_A, CLONE_B), candidates);
        assertEquals(List.of(HostPackages.ZALO), InstanceSelection.candidates(Collections.emptySet()));
        assertEquals(List.of(HostPackages.ZALO), InstanceSelection.candidates(null));
    }

    @Test public void validityIsRegistrationOnlyAndNeverInstallation() {
        assertTrue(InstanceSelection.valid(HostPackages.ZALO, Collections.emptySet()));
        assertTrue(InstanceSelection.valid(CLONE_A, Set.of(CLONE_A)));
        assertFalse(InstanceSelection.valid(CLONE_A, Collections.emptySet()));
        assertFalse(InstanceSelection.valid(null, Set.of(CLONE_A)));
        assertFalse(InstanceSelection.valid("com.zing.zalo.clone", Set.of(CLONE_A)));
    }

    @Test public void stateReportsUnavailableThenNotCheckedOrObservedButNeverActive() {
        assertEquals(InstanceSelection.State.UNAVAILABLE,
                InstanceSelection.state(false, true, true));
        assertEquals(InstanceSelection.State.UNAVAILABLE,
                InstanceSelection.state(true, false, true));
        assertEquals(InstanceSelection.State.NOT_CHECKED,
                InstanceSelection.state(true, true, false));
        assertEquals(InstanceSelection.State.OBSERVED,
                InstanceSelection.state(true, true, true));
        assertFalse(InstanceSelection.State.UNAVAILABLE.name().equals("ACTIVE"));
        assertFalse(InstanceSelection.State.NOT_CHECKED.name().equals("ACTIVE"));
    }

    @Test public void installedDefaultsToUnavailableWithoutAPackageManager() {
        assertFalse(InstanceSelection.installed(null, CLONE_A));
        assertFalse(InstanceSelection.installed(null, HostPackages.ZALO));
    }
}
