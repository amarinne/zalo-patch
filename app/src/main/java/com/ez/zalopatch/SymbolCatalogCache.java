package com.ez.zalopatch;

import android.content.Context;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

final class SymbolCatalogCache {
    private static final String FILE_NAME = "zalo-symbol-catalog-v1.json";

    private SymbolCatalogCache() {
    }

    static SymbolCatalogContract.Entry load(Context context, long versionCode,
                                            String baseHash, String signerHash) {
        if (context == null || versionCode <= 0L || baseHash.isEmpty() || signerHash.isEmpty()) {
            return null;
        }
        try {
            AtomicFile file = atomicFile(context);
            byte[] envelope = readBounded(file.openRead(), SymbolCatalogContract.MAX_ENTRY_BYTES);
            return SymbolCatalogContract.verify(envelope, publicKey(context), versionCode,
                    baseHash, signerHash, BuildConfig.VERSION_CODE);
        } catch (Exception ignored) {
            return null;
        }
    }

    static boolean save(Context context, ZaloArtifactIdentity identity, byte[] envelope) {
        if (context == null || identity == null) return false;
        try {
            SymbolCatalogContract.verify(envelope, publicKey(context), identity.versionCode,
                    identity.baseApkSha256, identity.signerSha256, BuildConfig.VERSION_CODE);
            AtomicFile file = atomicFile(context);
            FileOutputStream output = file.startWrite();
            try {
                output.write(envelope);
                output.flush();
                file.finishWrite(output);
                SymbolSchema.invalidate();
                return true;
            } catch (Throwable throwable) {
                file.failWrite(output);
                throw throwable;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void clear(Context context) {
        if (context == null) return;
        atomicFile(context).delete();
        SymbolSchema.invalidate();
    }

    private static AtomicFile atomicFile(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), FILE_NAME));
    }

    private static byte[] publicKey(Context context) throws Exception {
        return readBounded(context.getResources().openRawResource(
                R.raw.zp_catalog_public_key), 8 * 1024);
    }

    private static byte[] readBounded(InputStream input, int limit) throws Exception {
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new IllegalArgumentException("catalog entry too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
}
