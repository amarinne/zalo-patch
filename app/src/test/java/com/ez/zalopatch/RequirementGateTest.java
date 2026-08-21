package com.ez.zalopatch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RequirementGateTest {
    @Test
    public void rootRequirementNeedsGrantedRoot() {
        assertTrue(RequirementGate.isMet(Tweaks.Requirement.ROOT,
                RootAccess.State.GRANTED, RuntimeEnvironment.ResourceHooks.PENDING));
        assertFalse(RequirementGate.isMet(Tweaks.Requirement.ROOT,
                RootAccess.State.DENIED, RuntimeEnvironment.ResourceHooks.OBSERVED));
        assertFalse(RequirementGate.isMet(Tweaks.Requirement.ROOT,
                RootAccess.State.ABSENT, RuntimeEnvironment.ResourceHooks.PENDING));
    }

    @Test
    public void resourceRequirementStaysEnabledUntilObservationExists() {
        assertTrue(RequirementGate.isMet(Tweaks.Requirement.RESOURCE_HOOKS,
                RootAccess.State.ABSENT, RuntimeEnvironment.ResourceHooks.PENDING));
        assertTrue(RequirementGate.isMet(Tweaks.Requirement.RESOURCE_HOOKS,
                RootAccess.State.ABSENT, RuntimeEnvironment.ResourceHooks.OBSERVED));
        assertFalse(RequirementGate.isMet(Tweaks.Requirement.RESOURCE_HOOKS,
                RootAccess.State.GRANTED, RuntimeEnvironment.ResourceHooks.UNAVAILABLE));
    }

    @Test
    public void registryDeclaresOnlyObservedResourceRequirement() {
        for (Tweaks.Item item : Tweaks.ITEMS) {
            assertEquals(item.key.equals(Tweaks.KEY_HIDE_ZCLOUD_BANNER)
                            ? Tweaks.Requirement.RESOURCE_HOOKS : Tweaks.Requirement.NONE,
                    item.requirement);
        }
    }
}
