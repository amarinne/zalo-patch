package com.ez.zalopatch;

import android.content.Context;
import android.test.AndroidTestCase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Explicit device test. Requires a previously fetched, signed catalog entry in module storage. */
public final class RemoteCatalogActivationTest extends AndroidTestCase {
    public void testCachedSignedProfileActivatesWithoutBundledExactProfile() throws Exception {
        Context context = getContext();
        ZaloArtifactIdentity identity = ZaloArtifactIdentity.capture(context, true);
        SymbolCatalogContract.Entry entry = SymbolCatalogCache.load(context, identity.versionCode);
        assertNotNull("No signed catalog entry cached for installed Zalo", entry);

        JSONObject bundle = new JSONObject(readBundledSchema(context));
        JSONArray profiles = bundle.getJSONArray("profiles");
        JSONArray remaining = new JSONArray();
        for (int index = 0; index < profiles.length(); index++) {
            JSONObject profile = profiles.getJSONObject(index);
            long versionCode = profile.getJSONObject("zalo_version").getLong("min_code");
            if (versionCode != identity.versionCode) {
                remaining.put(profile);
            }
        }
        bundle.put("profiles", remaining);

        SymbolSchema.Active bundled = SymbolSchema.select(
                bundle.toString(), "Bundled test", identity.versionCode);
        assertFalse(bundled.valid);

        SymbolSchema.Active remote = SymbolSchema.selectRemoteEntry(entry, identity.versionCode);
        assertNotNull(remote);
        assertTrue(remote.valid);
        assertEquals("Remote catalog " + entry.sequence, remote.source);
        // The entry names the container it was mapped from, which need not be the installed one:
        // lookup and verification bind versionCode alone (Decision 15). Assert the mapped identity
        // is carried, not that it equals this device's container.
        assertEquals(64, remote.string("artifact.base_apk_sha256", "").length());
        assertEquals(64, remote.string("artifact.signer_sha256", "").length());
    }

    private static String readBundledSchema(Context context) throws Exception {
        try (InputStream input = context.getAssets().open(SymbolSchema.ASSET_NAME);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
