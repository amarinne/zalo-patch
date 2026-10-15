package com.ez.zalopatch;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.test.AndroidTestCase;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/** Uses only a disposable directory and an in-memory signing key; no installed Zalo or network. */
public final class SymbolCatalogCacheStorageTest extends AndroidTestCase {
    private static final long VERSION = 260801903L;
    private File directory;
    private Context context;
    private KeyPair keyPair;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        directory = File.createTempFile("catalog-test-", "", getContext().getCacheDir());
        assertTrue(directory.delete());
        assertTrue(directory.mkdir());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = generator.generateKeyPair();
        byte[] publicKey = ("-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n").getBytes(StandardCharsets.US_ASCII);
        Resources original = getContext().getResources();
        Resources resources = new Resources(original.getAssets(), original.getDisplayMetrics(),
                original.getConfiguration()) {
            @Override
            public InputStream openRawResource(int id) {
                assertEquals(R.raw.zp_catalog_public_key, id);
                return new ByteArrayInputStream(publicKey);
            }
        };
        context = new ContextWrapper(getContext()) {
            @Override
            public File getFilesDir() {
                return directory;
            }

            @Override
            public Resources getResources() {
                return resources;
            }
        };
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (directory != null) {
                File[] files = directory.listFiles();
                if (files != null) for (File file : files) assertTrue(file.delete());
                assertTrue(directory.delete());
            }
        } finally {
            super.tearDown();
        }
    }

    public void testCacheLoadsOfflineAndIsBoundToExactVersion() throws Exception {
        assertTrue(SymbolCatalogCache.save(context, VERSION, envelope(14, profile())));
        SymbolSchema.invalidate();
        SymbolCatalogContract.Entry entry = SymbolCatalogCache.load(context, VERSION);
        assertNotNull(entry);
        assertEquals(14, entry.sequence);
        assertNotNull(SymbolSchema.selectRemoteEntry(entry, VERSION));
        assertNull(SymbolCatalogCache.load(context, VERSION + 1));
    }

    public void testRollbackAndRevisionConflictKeepLastGoodCache() throws Exception {
        byte[] current = envelope(15, profile());
        assertTrue(SymbolCatalogCache.save(context, VERSION, current));
        String digest = SymbolCatalogCache.load(context, VERSION).digest;
        assertFalse(SymbolCatalogCache.save(context, VERSION, envelope(14, profile())));
        assertFalse(SymbolCatalogCache.save(context, VERSION,
                envelope(15, profile().put("schema_revision", 16))));
        assertEquals(digest, SymbolCatalogCache.load(context, VERSION).digest);
        assertTrue(SymbolCatalogCache.save(context, VERSION, current));
        assertTrue(SymbolCatalogCache.save(context, VERSION, envelope(16, profile())));
        assertEquals(16, SymbolCatalogCache.load(context, VERSION).sequence);
    }

    public void testRejectedDownloadsKeepLastGoodCache() throws Exception {
        assertTrue(SymbolCatalogCache.save(context, VERSION, envelope(14, profile())));
        String digest = SymbolCatalogCache.load(context, VERSION).digest;
        assertFalse(SymbolCatalogCache.save(context, VERSION,
                envelope(15, profile().put("schema_version", 2))));
        assertFalse(SymbolCatalogCache.save(context, VERSION,
                envelope(15, profile().put("zalo_version", new JSONObject()
                        .put("min_code", VERSION + 1).put("max_code", VERSION + 1)))));
        assertFalse(SymbolCatalogCache.save(context, VERSION, new byte[]{'{', '}'}));
        assertFalse(SymbolCatalogCache.save(context, VERSION,
                new byte[SymbolCatalogContract.MAX_ENTRY_BYTES + 1]));
        assertEquals(digest, SymbolCatalogCache.load(context, VERSION).digest);
    }

    public void testAtomicWriteFailureRestoresLastGoodCache() throws Exception {
        assertTrue(SymbolCatalogCache.save(context, VERSION, envelope(14, profile())));
        String digest = SymbolCatalogCache.load(context, VERSION).digest;
        AtomicFile file = new AtomicFile(cacheFile());
        FileOutputStream output = file.startWrite();
        try {
            output.write('{');
        } finally {
            file.failWrite(output);
        }
        assertEquals(digest, SymbolCatalogCache.load(context, VERSION).digest);
    }

    public void testCorruptOrClearedCacheCannotActivate() throws Exception {
        assertTrue(SymbolCatalogCache.save(context, VERSION, envelope(14, profile())));
        try (FileOutputStream output = new FileOutputStream(cacheFile())) {
            output.write('{');
        }
        assertNull(SymbolCatalogCache.load(context, VERSION));
        assertTrue(SymbolCatalogCache.save(context, VERSION, envelope(15, profile())));
        // This exercises local eviction, not authentication of the HTTP 410 revocation signal.
        SymbolCatalogCache.clear(context);
        assertNull(SymbolCatalogCache.load(context, VERSION));
    }

    private File cacheFile() {
        return new File(directory, "zalo-symbol-catalog-v1.json");
    }

    private JSONObject profile() throws Exception {
        return new JSONObject().put("schema_version", 1).put("schema_revision", 14)
                .put("zalo_package", SymbolSchema.TARGET_PACKAGE)
                .put("zalo_version", new JSONObject().put("min_code", VERSION).put("max_code", VERSION))
                .put("artifact", new JSONObject().put("base_apk_sha256",
                                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                        .put("signer_sha256",
                                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                        .put("hook_code_apk", "base")
                        .put("verification", "static-verified"));
    }

    private byte[] envelope(int sequence, JSONObject profile) throws Exception {
        byte[] payload = new JSONObject().put("protocolVersion", 1)
                .put("catalogSequence", sequence).put("packageName", SymbolSchema.TARGET_PACKAGE)
                .put("versionCode", VERSION).put("minimumModuleVersionCode", BuildConfig.VERSION_CODE)
                .put("profile", profile).toString().getBytes(StandardCharsets.UTF_8);
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(payload);
        StringBuilder digest = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(payload)) {
            digest.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        }
        return new JSONObject().put("protocolVersion", 1).put("keyId", SymbolCatalogContract.KEY_ID)
                .put("entryDigest", digest.toString())
                .put("payload", Base64.getEncoder().encodeToString(payload))
                .put("signature", Base64.getEncoder().encodeToString(signature.sign()))
                .toString().getBytes(StandardCharsets.UTF_8);
    }
}
