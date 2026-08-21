package com.ez.zalopatch;

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
import static org.junit.Assert.assertThrows;

public final class SymbolCatalogContractTest {
    private static final long VERSION = 260801903L;
    private static final String BASE_HASH = "a".repeat(64);
    private static final String SIGNER_HASH = "b".repeat(64);

    /**
     * The payload names the container the profile was mapped from; the installed artifact need not
     * be that container, so verification binds versionCode only. An entry therefore stays usable on
     * a Play bundle variant (Decision 14) and on a re-signed redistribution (Decision 15). Which
     * tier the artifact matched on is decided in ZaloArtifactState, not here.
     */
    @Test
    public void verifiesSignedExactArtifactEnvelope() throws Exception {
        Fixture fixture = fixture(152);
        SymbolCatalogContract.Entry entry = SymbolCatalogContract.verify(
                fixture.envelope, fixture.publicKeyPem, VERSION, 152);
        assertEquals(14, entry.sequence);
        assertEquals(fixture.digest, entry.digest);
        assertEquals(14, new JSONObject(entry.profileJson).getInt("schema_revision"));
    }

    @Test
    public void wrongVersionSignatureAndMinimumModuleFailClosed() throws Exception {
        Fixture fixture = fixture(153);
        assertThrows(IllegalArgumentException.class, () -> SymbolCatalogContract.verify(
                fixture.envelope, fixture.publicKeyPem, VERSION + 1L, 153));
        assertThrows(IllegalArgumentException.class, () -> SymbolCatalogContract.verify(
                fixture.envelope, fixture.publicKeyPem, VERSION, 152));
        byte[] changed = fixture.envelope.clone();
        changed[changed.length - 8] ^= 1;
        assertThrows(Exception.class, () -> SymbolCatalogContract.verify(
                changed, fixture.publicKeyPem, VERSION, 153));
    }

    @Test
    public void base64DecoderRejectsMalformedPadding() {
        assertThrows(IllegalArgumentException.class,
                () -> SymbolCatalogContract.decodeBase64("AA=A"));
    }

    private static Fixture fixture(int minimumModule) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        JSONObject profile = new JSONObject()
                .put("schema_version", 1)
                .put("schema_revision", 14)
                .put("zalo_package", SymbolSchema.TARGET_PACKAGE);
        JSONObject payload = new JSONObject()
                .put("protocolVersion", 1)
                .put("catalogSequence", 14)
                .put("packageName", SymbolSchema.TARGET_PACKAGE)
                .put("versionCode", VERSION)
                .put("versionName", "26.08.01")
                .put("baseApkSha256", BASE_HASH)
                .put("signerSha256", SIGNER_HASH)
                .put("minimumModuleVersionCode", minimumModule)
                .put("profile", profile);
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
                publicPem.getBytes(StandardCharsets.US_ASCII), digest);
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (byte item : value) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }

    private static final class Fixture {
        final byte[] envelope;
        final byte[] publicKeyPem;
        final String digest;

        Fixture(byte[] envelope, byte[] publicKeyPem, String digest) {
            this.envelope = envelope;
            this.publicKeyPem = publicKeyPem;
            this.digest = digest;
        }
    }
}
