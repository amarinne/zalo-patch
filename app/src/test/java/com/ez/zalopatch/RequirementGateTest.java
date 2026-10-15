package com.ez.zalopatch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RequirementGateTest {
    @Test
    public void rootRequirementNeedsGrantedRoot() {
        assertTrue(RequirementGate.isMet(Tweaks.Requirement.ROOT,
                RootAccess.State.GRANTED, RuntimeEnvironment.ResourceHooks.PENDING, 29));
        assertFalse(RequirementGate.isMet(Tweaks.Requirement.ROOT,
                RootAccess.State.DENIED, RuntimeEnvironment.ResourceHooks.OBSERVED, 29));
        assertFalse(RequirementGate.isMet(Tweaks.Requirement.ROOT,
                RootAccess.State.ABSENT, RuntimeEnvironment.ResourceHooks.PENDING, 29));
    }

    @Test
    public void resourceRequirementStaysEnabledUntilObservationExists() {
        assertTrue(RequirementGate.isMet(Tweaks.Requirement.RESOURCE_HOOKS,
                RootAccess.State.ABSENT, RuntimeEnvironment.ResourceHooks.PENDING, 29));
        assertTrue(RequirementGate.isMet(Tweaks.Requirement.RESOURCE_HOOKS,
                RootAccess.State.ABSENT, RuntimeEnvironment.ResourceHooks.OBSERVED, 29));
        assertFalse(RequirementGate.isMet(Tweaks.Requirement.RESOURCE_HOOKS,
                RootAccess.State.GRANTED, RuntimeEnvironment.ResourceHooks.UNAVAILABLE, 29));
    }

    @Test
    public void sharedRecordingsRequireAndroid10RegardlessOfRootOrResourceHooks() {
        for (RootAccess.State root : RootAccess.State.values()) {
            for (RuntimeEnvironment.ResourceHooks resources : RuntimeEnvironment.ResourceHooks.values()) {
                assertFalse(RequirementGate.isMet(Tweaks.Requirement.SHARED_RECORDINGS, root, resources, 26));
                assertFalse(RequirementGate.isMet(Tweaks.Requirement.SHARED_RECORDINGS, root, resources, 28));
                assertTrue(RequirementGate.isMet(Tweaks.Requirement.SHARED_RECORDINGS, root, resources, 29));
                assertTrue(RequirementGate.isMet(Tweaks.Requirement.SHARED_RECORDINGS, root, resources, 34));
            }
        }
    }

    @Test
    public void ordinarySettingsRemainAvailableBelowAndroid10() {
        assertTrue(RequirementGate.isMet(Tweaks.Requirement.NONE,
                RootAccess.State.ABSENT, RuntimeEnvironment.ResourceHooks.UNAVAILABLE, 26));
        assertTrue(RequirementGate.isMet(Tweaks.Requirement.NONE,
                RootAccess.State.DENIED, RuntimeEnvironment.ResourceHooks.PENDING, 28));
    }

    @Test
    public void registryDeclaresFeatureRequirements() {
        for (Tweaks.Item item : Tweaks.ITEMS) {
            assertEquals(item.key.equals(Tweaks.KEY_HIDE_ZCLOUD_BANNER)
                            ? Tweaks.Requirement.RESOURCE_HOOKS
                            : item.key.equals(Tweaks.KEY_AUTO_RECORD_CALLS)
                            ? Tweaks.Requirement.SHARED_RECORDINGS : Tweaks.Requirement.NONE,
                    item.requirement);
        }
    }
}
