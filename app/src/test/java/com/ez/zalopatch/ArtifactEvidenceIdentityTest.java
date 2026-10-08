package com.ez.zalopatch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class ArtifactEvidenceIdentityTest {
    @Test
    public void unsupportedArtifactKeepsEmptyReconciledHashAfterRouteAdoption() {
        ArtifactEvidenceIdentity identity = identity("");
        assertTrue(identity.matches("october", "generation", 303, ""));
        // A neighbouring or DexKit hash is diagnostic provenance, not reconciled identity.
        assertFalse(identity.matches("october", "generation", 303, "neighbour-profile"));
        assertFalse(identity.matches("october", "generation", 303, "dexkit-overlay"));
        assertEquals("october\ngeneration\n303\n", identity.epochMaterial());
    }

    @Test
    public void exactArtifactRequiresReconciledHashEvenWhenAnotherRouteIsAdopted() {
        ArtifactEvidenceIdentity identity = identity("exact-profile");
        assertTrue(identity.matches("october", "generation", 303, "exact-profile"));
        assertFalse(identity.matches("october", "generation", 303, "dexkit-overlay"));
        assertFalse(identity.matches("october", "generation", 303, ""));
        assertFalse(identity.matches("october", "generation", 303, null));
    }

    @Test
    public void wrongArtifactGenerationAndModuleStillFailClosed() {
        ArtifactEvidenceIdentity identity = identity("");
        assertFalse(identity.matches("old-artifact", "generation", 303, ""));
        assertFalse(identity.matches("october", "old-generation", 303, ""));
        assertFalse(identity.matches("october", "generation", 302, ""));
        assertFalse(identity.matches("october", "generation", null, ""));
        assertFalse(identity.matches(null, "generation", 303, ""));
        assertFalse(identity.matches("october", null, 303, ""));
    }

    @Test
    public void epochChangesForEachReconciledIdentityComponent() {
        String epoch = identity("exact-profile").epochMaterial();
        assertNotEquals(epoch, new ArtifactEvidenceIdentity("other", "generation", 303,
                "exact-profile").epochMaterial());
        assertNotEquals(epoch, new ArtifactEvidenceIdentity("october", "other", 303,
                "exact-profile").epochMaterial());
        assertNotEquals(epoch, new ArtifactEvidenceIdentity("october", "generation", 304,
                "exact-profile").epochMaterial());
        assertNotEquals(epoch, identity("other").epochMaterial());
    }

    @Test
    public void previousProcessFeatureReportsCannotOverwriteCurrentRun() {
        assertEquals(1, admission("current-run", "inbox.filter", "current-run"));
        assertEquals(0, admission("current-run", "inbox.filter", "prior-run"));
        assertEquals(0, admission("current-run", "symbol_fallback", "prior-run"));
        assertEquals(-3, admission("current-run", "symbol_schema", ""));
        assertEquals(-3, admission("current-run", "inbox.filter", null));
    }

    @Test
    public void schemaRowRetainsExistingRunRolloverPolicy() {
        assertEquals(1, admission("prior-run", "symbol_schema", "current-run"));
        assertEquals(1, admission("", "inbox.filter", "current-run"));
        assertEquals(1, ArtifactEvidenceIdentity.processAdmission("new-epoch", "old-epoch",
                "prior-run", "inbox.filter", "current-run"));
        assertEquals(0, admission("current-run", "inbox.filter", "prior-run"));
    }

    private static ArtifactEvidenceIdentity identity(String profileHash) {
        return new ArtifactEvidenceIdentity("october", "generation", 303, profileHash);
    }

    private static int admission(String storedRun, String feature, String reportedRun) {
        return ArtifactEvidenceIdentity.processAdmission("epoch", "epoch", storedRun,
                feature, reportedRun);
    }
}
