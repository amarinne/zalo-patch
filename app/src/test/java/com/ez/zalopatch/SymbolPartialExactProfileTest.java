package com.ez.zalopatch;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Characterizes partial exact-profile admission, not complete route coverage. The separate
 * DexKitFamilyResolver exactValid short circuit still suppresses discovery for such a profile;
 * this bounded repair deliberately does not change that precedence policy.
 */
public final class SymbolPartialExactProfileTest {
    private static final long OCTOBER = 261001903L;

    @Test
    public void validExactProfileDoesNotEstablishMissingRouteCoverage() throws Exception {
        SymbolSchema.Active partial = SymbolSchema.select(profile().toString(),
                "Remote catalog 14", OCTOBER);
        assertTrue(partial.valid);
        assertEquals(OCTOBER, partial.minCode);
        assertEquals(OCTOBER, partial.maxCode);
        JSONObject inbox = partial.root.getJSONObject("symbols").getJSONObject("inbox");
        assertEquals("b", inbox.getString("conversation_uid_field"));
        // UID is not evidence of complete Conversation extraction.
        assertFalse(inbox.has("conversation_field"));
        assertFalse(inbox.has("category_int_field"));
        assertFalse(partial.root.getJSONObject("symbols").has("bottom_tabs"));
    }

    @Test
    public void partialProfileCannotAuthorizeAnotherArtifactVersion() throws Exception {
        SymbolSchema.Active wrongVersion = SymbolSchema.select(profile().toString(),
                "Remote catalog 14", OCTOBER + 1L);
        assertFalse(wrongVersion.valid);
    }

    private static JSONObject profile() throws Exception {
        return new JSONObject()
                .put("schema_version", 1)
                .put("schema_revision", 14)
                .put("zalo_package", "com.zing.zalo")
                .put("zalo_version", new JSONObject()
                        .put("min_code", OCTOBER).put("max_code", OCTOBER))
                .put("artifact", new JSONObject()
                        .put("base_apk_sha256", "3bbe323515c5b68f86dd87e957202f5abdbfdf308dce429bd795632cfb46aa03")
                        .put("signer_sha256", "d86efe151e09bf4ca8440cb3bfa0a81be2544f70c78587daf0266dfca2fa25df")
                        .put("hook_code_apk", "base")
                        .put("verification", "static-verified"))
                .put("symbols", new JSONObject().put("inbox", new JSONObject()
                        .put("conversation_uid_field", "b")));
    }
}
