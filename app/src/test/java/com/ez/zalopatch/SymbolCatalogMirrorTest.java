package com.ez.zalopatch;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class SymbolCatalogMirrorTest {
    private static final long VERSION = 260901903L;

    @Test
    public void roundTripsOnlyTheValidatedProfileAndSequence() throws Exception {
        String profile = profile(VERSION);
        String encoded = SymbolCatalogMirror.encode(28, profile);

        SymbolSchema.Active active = SymbolCatalogMirror.select(encoded, "ready",
                ZaloArtifactIdentity.sha256(profile), VERSION);

        assertNotNull(active);
        assertTrue(active.valid);
        assertEquals("Remote catalog 28 mirror", active.source);
        assertEquals(VERSION, active.minCode);
    }

    @Test
    public void rejectsUnreadyOrMismatchedMirrorState() throws Exception {
        String profile = profile(VERSION);
        String encoded = SymbolCatalogMirror.encode(28, profile);
        String hash = ZaloArtifactIdentity.sha256(profile);

        assertNull(SymbolCatalogMirror.select(encoded, "pending", hash, VERSION));
        assertNull(SymbolCatalogMirror.select(encoded, "ready", "0".repeat(64), VERSION));
        assertNull(SymbolCatalogMirror.select(encoded, "ready", hash, VERSION + 1L));
    }

    @Test
    public void rejectsMalformedAndOversizedValues() {
        assertNull(SymbolCatalogMirror.select("not-a-profile", "ready", "hash", VERSION));
        assertNull(SymbolCatalogMirror.select("", "ready", "hash", VERSION));
    }

    @Test
    public void rejectsInvalidSequencesAndProfiles() throws Exception {
        String profile = profile(VERSION);
        assertThrowsIllegalArgument(() -> SymbolCatalogMirror.encode(0, profile));
        assertThrowsIllegalArgument(() -> SymbolCatalogMirror.encode(28, "x".repeat(256 * 1024)));
    }

    private static String profile(long versionCode) throws Exception {
        return new JSONObject()
                .put("schema_version", 1)
                .put("schema_revision", 28)
                .put("zalo_package", SymbolSchema.TARGET_PACKAGE)
                .put("zalo_version", new JSONObject()
                        .put("min_code", versionCode)
                        .put("max_code", versionCode))
                .put("artifact", new JSONObject()
                        .put("base_apk_sha256", "a".repeat(64))
                        .put("signer_sha256", "b".repeat(64))
                        .put("hook_code_apk", "base")
                        .put("verification", "device-verified"))
                .toString();
    }

    private static void assertThrowsIllegalArgument(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (IllegalArgumentException expected) {
            return;
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
