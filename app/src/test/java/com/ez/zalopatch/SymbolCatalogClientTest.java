package com.ez.zalopatch;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The resolve request carries the frozen wire fields and admitted runtime evidence only. */
public final class SymbolCatalogClientTest {
    private static final long HOST_VERSION = 260801903L;
    private static final String BASE_HASH = "a".repeat(64);
    private static final String SIGNER_HASH = "b".repeat(64);
    private static final int MODULE_VERSION = 357;

    @Test
    public void sendsKnownRuntimeFramework() throws Exception {
        assertEquals("lsposed", body(RuntimeEnvironment.Framework.LSPOSED)
                .getString("runtimeFramework"));
        assertEquals("lspatch", body(RuntimeEnvironment.Framework.LSPATCH)
                .getString("runtimeFramework"));
    }

    /**
     * Missing or stale evidence arrives as the enum's unknown value. A build variant, a root grant
     * or a remembered framework name cannot reach the field through any other path.
     */
    @Test
    public void sendsUnknownForMissingOrStaleEvidence() throws Exception {
        assertEquals("unknown", body(RuntimeEnvironment.Framework.UNKNOWN)
                .getString("runtimeFramework"));
        assertEquals("unknown", body(null).getString("runtimeFramework"));
    }

    @Test
    public void keepsArtifactAndCatalogFields() throws Exception {
        JSONObject fresh = body(RuntimeEnvironment.Framework.LSPOSED);
        assertEquals(SymbolCatalogContract.PROTOCOL_VERSION,
                fresh.getInt("protocolVersion"));
        assertEquals(SymbolSchema.TARGET_PACKAGE, fresh.getString("packageName"));
        assertEquals(HOST_VERSION, fresh.getLong("versionCode"));
        assertEquals("261001903", fresh.getString("versionName"));
        assertEquals(BASE_HASH, fresh.getString("baseApkSha256"));
        assertEquals(SIGNER_HASH, fresh.getString("signerSha256"));
        assertEquals(MODULE_VERSION, fresh.getInt("moduleVersionCode"));
        assertEquals(0, fresh.getInt("currentCatalogSequence"));
        assertTrue(fresh.isNull("currentEntryDigest"));

        JSONObject cached = SymbolCatalogClient.requestBody(
                HOST_VERSION, "261001903", BASE_HASH, SIGNER_HASH, MODULE_VERSION,
                14, "entry-digest", RuntimeEnvironment.Framework.LSPATCH);
        assertEquals(14, cached.getInt("currentCatalogSequence"));
        assertEquals("entry-digest", cached.getString("currentEntryDigest"));
    }

    private static JSONObject body(RuntimeEnvironment.Framework framework) throws Exception {
        return SymbolCatalogClient.requestBody(
                HOST_VERSION, "261001903", BASE_HASH, SIGNER_HASH, MODULE_VERSION,
                0, null, framework);
    }
}
