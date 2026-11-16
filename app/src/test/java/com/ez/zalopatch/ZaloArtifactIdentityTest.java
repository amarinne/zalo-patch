package com.ez.zalopatch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public final class ZaloArtifactIdentityTest {
    @Test
    public void renamedInstallationsCannotShareAnEvidenceGeneration() {
        ZaloArtifactIdentity original = identity("com.zing.zalo");
        ZaloArtifactIdentity clone = identity("vn.example.zalo2");
        assertNotEquals(original.lightweightKey, clone.lightweightKey);
        assertNotEquals(original.generation, clone.generation);
        assertEquals(original.generation, identity("com.zing.zalo").generation);
    }

    private static ZaloArtifactIdentity identity(String packageName) {
        return new ZaloArtifactIdentity(packageName, 261001905L, "26.10.01", 100L,
                "/fixture/base.apk", "base-digest", "signer-digest",
                java.util.Collections.emptyList());
    }

    @Test
    public void canonicalHashIsDeterministicAndSensitiveToInput() {
        assertEquals(ZaloArtifactIdentity.sha256("artifact"),
                ZaloArtifactIdentity.sha256("artifact"));
        assertNotEquals(ZaloArtifactIdentity.sha256("artifact"),
                ZaloArtifactIdentity.sha256("artifact-changed"));
    }
}
