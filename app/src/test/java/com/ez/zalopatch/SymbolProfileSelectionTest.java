package com.ez.zalopatch;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers the shared selection policy that lets a signed remote catalog profile correct a valid
 * bundled exact profile.
 *
 * <p>The bundled side of every case is the real shipped profile for Zalo 26.10.01 build 261001905
 * (schema revision 32), read through the production bundle parser. The remote side is the
 * production signed entry path, the production mirror path, and the production profile parser, so
 * the assertions cover the bytes a client would actually hold.
 */
public final class SymbolProfileSelectionTest {
    private static final long VERSION = 261001905L;
    private static final int BUNDLED_REVISION = 32;

    @Test
    public void signedExactRemoteSupersedesTheNamedBundledRevision() throws Exception {
        SymbolSchema.Active bundled = bundledActive();
        SymbolSchema.Active remote = remoteActive(BUNDLED_REVISION + 1, BUNDLED_REVISION,
                "Remote catalog 33");

        SymbolSchema.Active selected = SymbolProfileSelection.select(bundled, remote);

        assertTrue(selected.valid);
        assertEquals(BUNDLED_REVISION + 1, selected.schemaRevision);
        assertEquals("Remote catalog 33", selected.source);
    }

    @Test
    public void remoteWithoutTheSupersessionMetadataKeepsTheBundledProfile() throws Exception {
        SymbolSchema.Active bundled = bundledActive();
        SymbolSchema.Active remote = remoteActive(BUNDLED_REVISION + 1, null, "Remote catalog 33");

        assertEquals(bundled.schemaRevision,
                SymbolProfileSelection.select(bundled, remote).schemaRevision);
        assertEquals("Bundled profile " + VERSION,
                SymbolProfileSelection.select(bundled, remote).source);
    }

    @Test
    public void remoteNamingAnotherRevisionKeepsTheBundledProfile() throws Exception {
        SymbolSchema.Active bundled = bundledActive();
        SymbolSchema.Active remote = remoteActive(BUNDLED_REVISION + 1, BUNDLED_REVISION - 1,
                "Remote catalog 33");

        assertEquals(BUNDLED_REVISION,
                SymbolProfileSelection.select(bundled, remote).schemaRevision);
    }

    @Test
    public void equalOrOlderRemoteSchemaRevisionKeepsTheBundledProfile() throws Exception {
        SymbolSchema.Active bundled = bundledActive();

        assertEquals(BUNDLED_REVISION, SymbolProfileSelection.select(bundled,
                remoteActive(BUNDLED_REVISION, BUNDLED_REVISION, "Remote catalog 33"))
                .schemaRevision);
        assertEquals(BUNDLED_REVISION, SymbolProfileSelection.select(bundled,
                remoteActive(BUNDLED_REVISION - 1, BUNDLED_REVISION, "Remote catalog 33"))
                .schemaRevision);
    }

    @Test
    public void malformedSupersessionMetadataKeepsTheBundledProfile() throws Exception {
        SymbolSchema.Active bundled = bundledActive();

        for (Object value : new Object[]{"32", 32.5d, Boolean.TRUE, JSONObject.NULL}) {
            SymbolSchema.Active remote = remoteActive(BUNDLED_REVISION + 1, null,
                    "Remote catalog 33");
            remote.root.put(SymbolProfileSelection.SUPERSEDES_KEY, value);

            assertNull("metadata " + value + " must not name a revision",
                    SymbolProfileSelection.supersedesRevision(remote));
            assertEquals(BUNDLED_REVISION,
                    SymbolProfileSelection.select(bundled, remote).schemaRevision);
        }
    }

    @Test
    public void invalidRemoteNeverReplacesAValidBundledProfile() throws Exception {
        SymbolSchema.Active bundled = bundledActive();

        JSONObject ranged = remoteProfile(BUNDLED_REVISION + 1, BUNDLED_REVISION);
        ranged.getJSONObject("zalo_version").put("max_code", VERSION + 1L);
        SymbolSchema.Active rangedRemote = SymbolSchema.select(ranged.toString(),
                "Remote catalog 33", VERSION);
        assertFalse(rangedRemote.valid);

        JSONObject wrongVersion = remoteProfile(BUNDLED_REVISION + 1, BUNDLED_REVISION);
        wrongVersion.getJSONObject("zalo_version")
                .put("min_code", VERSION + 1L).put("max_code", VERSION + 1L);
        SymbolSchema.Active wrongVersionRemote = SymbolSchema.select(wrongVersion.toString(),
                "Remote catalog 33", VERSION);
        assertFalse(wrongVersionRemote.valid);

        assertEquals(BUNDLED_REVISION,
                SymbolProfileSelection.select(bundled, rangedRemote).schemaRevision);
        assertEquals(BUNDLED_REVISION,
                SymbolProfileSelection.select(bundled, wrongVersionRemote).schemaRevision);
        assertEquals(BUNDLED_REVISION,
                SymbolProfileSelection.select(bundled, null).schemaRevision);
    }

    @Test
    public void validExactRemoteIsAcceptedWhenNoBundledProfileExists() throws Exception {
        SymbolSchema.Active bundled = SymbolSchema.select(bundleWithoutVersion(),
                "Bundled", VERSION);
        assertFalse(bundled.valid);

        SymbolSchema.Active remote = remoteActive(BUNDLED_REVISION + 1, null,
                "Remote catalog 33");

        SymbolSchema.Active selected = SymbolProfileSelection.select(bundled, remote);

        assertTrue(selected.valid);
        assertEquals("Remote catalog 33", selected.source);
    }

    @Test
    public void bothInvalidProfilesKeepTheSafeDiagnosticResult() throws Exception {
        SymbolSchema.Active bundled = SymbolSchema.select(bundleWithoutVersion(),
                "Bundled", VERSION);
        SymbolSchema.Active remote = SymbolSchema.select(
                remoteProfile(BUNDLED_REVISION + 1, BUNDLED_REVISION).toString(),
                "Remote catalog 33", VERSION + 1L);

        SymbolSchema.Active selected = SymbolProfileSelection.select(bundled, remote);

        assertFalse(selected.valid);
        assertEquals(bundled, selected);
        assertTrue(selected.validation.contains("No exact bundled symbol profile"));
    }

    @Test
    public void newerModuleBundleDoesNotAcceptARemoteTargetingAnOlderBundle() throws Exception {
        // The client ships a newer bundled revision than the one the signed remote was cut for.
        SymbolSchema.Active bundled = SymbolSchema.select(
                bundledProfileObject().put("schema_revision", BUNDLED_REVISION + 2).toString(),
                "Bundled", VERSION);
        assertTrue(bundled.valid);
        SymbolSchema.Active remote = remoteActive(BUNDLED_REVISION + 3, BUNDLED_REVISION,
                "Remote catalog 33");

        assertFalse(SymbolProfileSelection.supersedes(bundled, remote));
        assertEquals(BUNDLED_REVISION + 2,
                SymbolProfileSelection.select(bundled, remote).schemaRevision);
    }

    @Test
    public void theCatalogSequenceStaysAnIndependentAntiRollbackCounter() {
        SymbolCatalogContract.Entry current = new SymbolCatalogContract.Entry(
                33, "digest", "{}", new byte[0]);
        SymbolCatalogContract.Entry older = new SymbolCatalogContract.Entry(
                32, "digest", "{}", new byte[0]);

        assertFalse(SymbolCatalogCache.canReplace(current, older));
        assertTrue(SymbolCatalogCache.canReplace(current,
                new SymbolCatalogContract.Entry(34, "digest", "{}", new byte[0])));
        assertTrue(SymbolCatalogCache.canReplace(current,
                new SymbolCatalogContract.Entry(33, "digest", "{}", new byte[0])));
    }

    @Test
    public void theSchemaRuleDoesNotReadTheCatalogSequence() throws Exception {
        SymbolSchema.Active bundled = bundledActive();

        // Same signed profile, different catalog sequence in the source label. The selection
        // follows the named schema revision, never the sequence carried in the label.
        SymbolSchema.Active olderSequence = remoteActive(BUNDLED_REVISION + 1, BUNDLED_REVISION,
                "Remote catalog 7");
        SymbolSchema.Active newerSequence = remoteActive(BUNDLED_REVISION + 1, BUNDLED_REVISION,
                "Remote catalog 41");

        assertEquals(BUNDLED_REVISION + 1,
                SymbolProfileSelection.select(bundled, olderSequence).schemaRevision);
        assertEquals(BUNDLED_REVISION + 1,
                SymbolProfileSelection.select(bundled, newerSequence).schemaRevision);
        assertNotEquals(olderSequence.source, newerSequence.source);
    }

    /**
     * The signed entry, the module selection path, the reconciliation path, and the hook mirror
     * path all read the same profile bytes, so they select the same profile.
     */
    @Test
    public void moduleHookAndReconcilePathsSelectTheSameProfile() throws Exception {
        SymbolSchema.Active bundled = bundledActive();
        Fixture fixture = signedFixture(remoteProfile(BUNDLED_REVISION + 1, BUNDLED_REVISION));

        SymbolCatalogContract.Entry entry = SymbolCatalogContract.verify(
                fixture.envelope, fixture.publicKeyPem, VERSION, 340);
        assertEquals(33, entry.sequence);
        assertEquals(BUNDLED_REVISION, new JSONObject(entry.profileJson)
                .getInt(SymbolProfileSelection.SUPERSEDES_KEY));

        // Reconciliation path.
        SymbolSchema.Active reconcileRemote = SymbolSchema.selectRemoteEntry(entry, VERSION);
        assertNotNull(reconcileRemote);
        // Module selection path.
        SymbolSchema.Active moduleRemote = SymbolSchema.select(entry.profileJson,
                "Remote catalog " + entry.sequence, VERSION);
        // Hook mirror path.
        String encoded = SymbolCatalogMirror.encode(entry.sequence, entry.profileJson);
        SymbolSchema.Active hookRemote = SymbolCatalogMirror.select(encoded, "ready",
                ZaloArtifactIdentity.sha256(entry.profileJson), VERSION);
        assertNotNull(hookRemote);

        for (SymbolSchema.Active remote : new SymbolSchema.Active[]{
                reconcileRemote, moduleRemote, hookRemote}) {
            assertTrue(remote.valid);
            SymbolSchema.Active selected = SymbolProfileSelection.select(bundled, remote);
            assertTrue(selected.valid);
            assertEquals(BUNDLED_REVISION + 1, selected.schemaRevision);
        }
    }

    @Test
    public void aSignedRemoteWithoutTheMetadataKeepsTheBundledProfile() throws Exception {
        SymbolSchema.Active bundled = bundledActive();
        Fixture fixture = signedFixture(remoteProfile(BUNDLED_REVISION + 1, null));

        SymbolCatalogContract.Entry entry = SymbolCatalogContract.verify(
                fixture.envelope, fixture.publicKeyPem, VERSION, 340);

        assertEquals(BUNDLED_REVISION, SymbolProfileSelection.select(bundled,
                SymbolSchema.selectRemoteEntry(entry, VERSION)).schemaRevision);
    }

    private static SymbolSchema.Active bundledActive() throws Exception {
        SymbolSchema.Active bundled = SymbolSchema.select(BundledSymbolSchemaJson.json(),
                "Bundled", VERSION);
        assertTrue(bundled.valid);
        assertEquals(BUNDLED_REVISION, bundled.schemaRevision);
        return bundled;
    }

    private static SymbolSchema.Active remoteActive(int schemaRevision, Integer supersedes,
                                                    String source) throws Exception {
        SymbolSchema.Active remote = SymbolSchema.select(
                remoteProfile(schemaRevision, supersedes).toString(), source, VERSION);
        assertTrue(remote.valid);
        return remote;
    }

    private static JSONObject remoteProfile(int schemaRevision, Integer supersedes)
            throws Exception {
        JSONObject profile = bundledProfileObject();
        profile.put("schema_revision", schemaRevision);
        if (supersedes != null) {
            profile.put(SymbolProfileSelection.SUPERSEDES_KEY, supersedes);
        }
        return profile;
    }

    private static JSONObject bundledProfileObject() throws Exception {
        JSONArray profiles = new JSONObject(BundledSymbolSchemaJson.json())
                .getJSONArray("profiles");
        for (int index = 0; index < profiles.length(); index++) {
            JSONObject profile = profiles.getJSONObject(index);
            if (profile.getJSONObject("zalo_version").getLong("min_code") == VERSION) {
                return profile;
            }
        }
        throw new AssertionError("No bundled profile for Zalo " + VERSION);
    }

    private static String bundleWithoutVersion() throws Exception {
        JSONObject bundle = new JSONObject(BundledSymbolSchemaJson.json());
        JSONArray profiles = bundle.getJSONArray("profiles");
        JSONArray remaining = new JSONArray();
        for (int index = 0; index < profiles.length(); index++) {
            JSONObject profile = profiles.getJSONObject(index);
            if (profile.getJSONObject("zalo_version").getLong("min_code") != VERSION) {
                remaining.put(profile);
            }
        }
        bundle.put("profiles", remaining);
        return bundle.toString();
    }

    private static Fixture signedFixture(JSONObject profile) throws Exception {
        JSONObject payload = new JSONObject()
                .put("protocolVersion", 1)
                .put("catalogSequence", 33)
                .put("packageName", SymbolSchema.TARGET_PACKAGE)
                .put("versionCode", VERSION)
                .put("versionName", "26.10.01")
                .put("baseApkSha256", profile.getJSONObject("artifact")
                        .getString("base_apk_sha256"))
                .put("signerSha256", profile.getJSONObject("artifact")
                        .getString("signer_sha256"))
                .put("minimumModuleVersionCode", 340)
                .put("profile", profile);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        byte[] payloadBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(payloadBytes);
        String digest = hex(MessageDigest.getInstance("SHA-256").digest(payloadBytes));
        JSONObject envelope = new JSONObject()
                .put("protocolVersion", 1)
                .put("keyId", SymbolCatalogContract.KEY_ID)
                .put("entryDigest", digest)
                .put("payload", Base64.getEncoder().encodeToString(payloadBytes))
                .put("signature", Base64.getEncoder().encodeToString(signer.sign()));
        String publicPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(
                        pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        return new Fixture(envelope.toString().getBytes(StandardCharsets.UTF_8),
                publicPem.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (byte item : value) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }

    private static final class Fixture {
        final byte[] envelope;
        final byte[] publicKeyPem;

        Fixture(byte[] envelope, byte[] publicKeyPem) {
            this.envelope = envelope;
            this.publicKeyPem = publicKeyPem;
        }
    }
}
