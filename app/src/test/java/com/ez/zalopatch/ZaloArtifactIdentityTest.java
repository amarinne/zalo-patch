package com.ez.zalopatch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public final class ZaloArtifactIdentityTest {
    @Test
    public void canonicalHashIsDeterministicAndSensitiveToInput() {
        assertEquals(ZaloArtifactIdentity.sha256("artifact"),
                ZaloArtifactIdentity.sha256("artifact"));
        assertNotEquals(ZaloArtifactIdentity.sha256("artifact"),
                ZaloArtifactIdentity.sha256("artifact-changed"));
    }
}
