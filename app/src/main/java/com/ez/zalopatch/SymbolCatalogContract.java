package com.ez.zalopatch;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

final class SymbolCatalogContract {
    static final int PROTOCOL_VERSION = 1;
    static final int MAX_ENTRY_BYTES = 256 * 1024;
    static final String KEY_ID = "catalog-2026-01";

    private SymbolCatalogContract() {
    }

    /**
     * Verifies a signed catalog entry against the installed artifact.
     *
     * <p>Binding is on exact {@code versionCode}. The container hashes carried in the payload are
     * the identity of the artifact the profile was mapped from, which the module reads to report a
     * match tier; they are deliberately not compared against the installed artifact here. The base
     * APK hash identifies a download rather than a build (Decision 14), and re-signing rezips
     * without rebuilding dex (Decision 15), so comparing either made a correctly signed entry
     * unusable on a container carrying identical code.
     *
     * <p>Entry authenticity is unaffected: it rests on the pinned catalog public key, and
     * {@code versionCode} stays in the binding, so an entry for another release is still refused.
     */
    static Entry verify(byte[] envelopeBytes, byte[] publicKeyPem, long versionCode,
                        int moduleVersionCode)
            throws Exception {
        if (envelopeBytes == null || envelopeBytes.length == 0
                || envelopeBytes.length > MAX_ENTRY_BYTES) {
            throw new IllegalArgumentException("invalid catalog entry size");
        }
        JSONObject envelope = new JSONObject(new String(envelopeBytes, StandardCharsets.UTF_8));
        if (envelope.optInt("protocolVersion", -1) != PROTOCOL_VERSION
                || !KEY_ID.equals(envelope.optString("keyId", ""))) {
            throw new IllegalArgumentException("unsupported catalog envelope");
        }
        byte[] payload = decodeBase64(envelope.optString("payload", ""));
        byte[] signatureBytes = decodeBase64(envelope.optString("signature", ""));
        String digest = sha256(payload);
        if (!digest.equals(envelope.optString("entryDigest", ""))) {
            throw new IllegalArgumentException("catalog digest mismatch");
        }
        PublicKey publicKey = parsePublicKey(publicKeyPem);
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initVerify(publicKey);
        signature.update(payload);
        if (!signature.verify(signatureBytes)) {
            throw new IllegalArgumentException("catalog signature invalid");
        }
        JSONObject root = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        int sequence = root.optInt("catalogSequence", -1);
        int minimumModule = root.optInt("minimumModuleVersionCode", Integer.MAX_VALUE);
        JSONObject profile = root.optJSONObject("profile");
        if (root.optInt("protocolVersion", -1) != PROTOCOL_VERSION
                || sequence <= 0
                || !SymbolSchema.TARGET_PACKAGE.equals(root.optString("packageName", ""))
                || root.optLong("versionCode", -1L) != versionCode
                || minimumModule > moduleVersionCode
                || profile == null) {
            throw new IllegalArgumentException("catalog artifact mismatch");
        }
        return new Entry(sequence, digest, profile.toString(), envelopeBytes);
    }

    private static PublicKey parsePublicKey(byte[] pemBytes) throws Exception {
        String pem = new String(pemBytes, StandardCharsets.US_ASCII)
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(decodeBase64(pem)));
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            result.append(String.format(java.util.Locale.US, "%02x", item & 0xff));
        }
        return result.toString();
    }

    static byte[] decodeBase64(String value) {
        if (value == null) throw new IllegalArgumentException("invalid base64");
        String compact = value.replaceAll("\\s", "");
        if (compact.isEmpty() || compact.length() % 4 != 0) {
            throw new IllegalArgumentException("invalid base64");
        }
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        for (int offset = 0; offset < compact.length(); offset += 4) {
            int a = base64Value(compact.charAt(offset));
            int b = base64Value(compact.charAt(offset + 1));
            char cChar = compact.charAt(offset + 2);
            char dChar = compact.charAt(offset + 3);
            int c = cChar == '=' ? 0 : base64Value(cChar);
            int d = dChar == '=' ? 0 : base64Value(dChar);
            output.write((a << 2) | (b >> 4));
            if (cChar != '=') output.write(((b & 15) << 4) | (c >> 2));
            if (dChar != '=') output.write(((c & 3) << 6) | d);
            if (cChar == '=' && dChar != '=') throw new IllegalArgumentException("invalid base64");
        }
        return output.toByteArray();
    }

    private static int base64Value(char value) {
        if (value >= 'A' && value <= 'Z') return value - 'A';
        if (value >= 'a' && value <= 'z') return value - 'a' + 26;
        if (value >= '0' && value <= '9') return value - '0' + 52;
        if (value == '+') return 62;
        if (value == '/') return 63;
        throw new IllegalArgumentException("invalid base64");
    }

    static final class Entry {
        final int sequence;
        final String digest;
        final String profileJson;
        final byte[] envelopeBytes;

        Entry(int sequence, String digest, String profileJson, byte[] envelopeBytes) {
            this.sequence = sequence;
            this.digest = digest;
            this.profileJson = profileJson;
            this.envelopeBytes = envelopeBytes;
        }
    }
}
