package com.ez.zalopatch;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class SymbolCatalogMirror {
    private static final String MIRROR_KEY = "catalog.profile";
    private static final int MAX_PROFILE_BYTES = 256 * 1024;

    private SymbolCatalogMirror() {
    }

    static void sync(Context context, long versionCode) {
        try {
            SymbolCatalogContract.Entry entry = SymbolCatalogCache.load(context, versionCode);
            String value = entry == null ? "-" : encode(entry.sequence, entry.profileJson);
            if (value.equals(SettingsPropertyMirror.readBlob(MIRROR_KEY))) {
                return;
            }
            if (!SettingsPropertyMirror.writeBlob(MIRROR_KEY, value)) {
                Log.i("ZaloPatch", "Catalog profile mirror write failed");
            }
        } catch (Throwable throwable) {
            Log.i("ZaloPatch", "Catalog profile mirror sync failed "
                    + throwable.getClass().getSimpleName());
        }
    }

    static SymbolSchema.Active read(long versionCode) {
        return select(SettingsPropertyMirror.readBlob(MIRROR_KEY),
                HookConfig.getRawString(ZaloArtifactState.KEY_STATUS, ""),
                HookConfig.getRawString(ZaloArtifactState.KEY_PROFILE_SHA256, ""),
                versionCode);
    }

    static SymbolSchema.Active select(String value, String status, String profileHash,
                                       long versionCode) {
        if (!"ready".equals(status) || profileHash == null || profileHash.isEmpty()) {
            return null;
        }
        try {
            String decoded = decode(value);
            int separator = decoded.indexOf('\n');
            if (separator <= 0 || separator == decoded.length() - 1) {
                return null;
            }
            int sequence = Integer.parseInt(decoded.substring(0, separator));
            if (sequence <= 0) {
                return null;
            }
            String profileJson = decoded.substring(separator + 1);
            if (!profileHash.equals(ZaloArtifactIdentity.sha256(profileJson))) {
                return null;
            }
            SymbolSchema.Active active = SymbolSchema.select(profileJson,
                    "Remote catalog " + sequence + " mirror", versionCode);
            return active.valid ? active : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static String encode(int sequence, String profileJson) throws Exception {
        byte[] raw = (sequence + "\n" + profileJson).getBytes(StandardCharsets.UTF_8);
        if (sequence <= 0 || raw.length > MAX_PROFILE_BYTES) {
            throw new IllegalArgumentException("invalid catalog mirror profile");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream compressed = new GZIPOutputStream(output)) {
            compressed.write(raw);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray());
    }

    private static String decode(String value) throws Exception {
        if (value == null || value.isEmpty() || value.length() > MAX_PROFILE_BYTES) {
            throw new IllegalArgumentException("invalid catalog mirror size");
        }
        byte[] compressed = Base64.getUrlDecoder().decode(value);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_PROFILE_BYTES) {
                    throw new IllegalArgumentException("catalog mirror profile too large");
                }
                output.write(buffer, 0, count);
            }
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }
}
