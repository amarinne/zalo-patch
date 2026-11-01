package com.ez.zalopatch;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

/** Uploads bounded on-device symbol evidence after an exact catalog lookup misses. */
final class SymbolResourceUploader {
    private static final int MAX_BODY_BYTES = 128 * 1024;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    private SymbolResourceUploader() {
    }

    static void markPending(Context context) {
        if (context == null) return;
        TweakStore.preferences(context).edit()
                .putBoolean(ZaloArtifactState.KEY_REMAP_UPLOAD_PENDING, true).commit();
    }

    static boolean isPending(Context context) {
        return context != null && TweakStore.preferences(context)
                .getBoolean(ZaloArtifactState.KEY_REMAP_UPLOAD_PENDING, false);
    }

    /** Called from the module job, never from the Zalo process. */
    static boolean uploadIfPending(Context context, ZaloArtifactIdentity identity) {
        if (!isPending(context) || context == null || identity == null) return false;
        JSONObject resources;
        try {
            resources = resources(context, identity);
        } catch (Exception ignored) {
            return false;
        }
        if (!hasEvidence(resources)) return false;
        try {
            URL url = remapUrl(BuildConfig.SYMBOL_CATALOG_URL);
            String resourceDigestInput = identity.versionCode + "\n" + identity.baseApkSha256
                    + "\n" + identity.signerSha256 + "\n" + canonical(resources);
            JSONObject request = new JSONObject()
                    .put("protocolVersion", SymbolCatalogContract.PROTOCOL_VERSION)
                    .put("packageName", SymbolSchema.TARGET_PACKAGE)
                    .put("resourceId", ZaloArtifactIdentity.sha256(resourceDigestInput))
                    .put("versionCode", identity.versionCode)
                    .put("versionName", identity.versionName)
                    .put("baseApkSha256", identity.baseApkSha256)
                    .put("signerSha256", identity.signerSha256)
                    .put("moduleVersionCode", BuildConfig.VERSION_CODE)
                    .put("resources", resources);
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            if (body.length > MAX_BODY_BYTES) return false;
            HttpsURLConnection connection = null;
            try {
                connection = (HttpsURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setUseCaches(false);
                connection.setDoInput(true);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "ZaloPatch/" + BuildConfig.VERSION_CODE);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
                int status = connection.getResponseCode();
                if (status != HttpURLConnection.HTTP_CREATED && status != HttpURLConnection.HTTP_OK) {
                    return false;
                }
                JSONObject response = new JSONObject(readBounded(connection.getInputStream()));
                String resourceId = request.getString("resourceId");
                if (!response.optBoolean("accepted", false)
                        || !resourceId.equals(response.optString("resourceId", ""))) {
                    return false;
                }
                TweakStore.preferences(context).edit()
                        .putBoolean(ZaloArtifactState.KEY_REMAP_UPLOAD_PENDING, false).commit();
                return true;
            } finally {
                if (connection != null) connection.disconnect();
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private static JSONObject resources(Context context, ZaloArtifactIdentity identity)
            throws Exception {
        long versionCode = identity.versionCode;
        RemapEvidenceStore.Snapshot evidence = RemapEvidenceStore.load(context);
        boolean evidenceMatches = evidence.versionCode == versionCode;
        JSONObject discovery = new JSONObject()
                .put("versionCode", versionCode)
                .put("observedVersionCode", evidence.versionCode)
                .put("completed", evidenceMatches
                        && DiagnosticsState.runtimeDiscoveryLastVersionCode(context) == versionCode)
                .put("candidates", array(evidenceMatches ? evidence.candidates : null))
                .put("surfaces", array(evidenceMatches ? evidence.surfaces : null));
        DexKitCache.Entry cached = DexKitStore.load(context);
        // A cache from a previous Zalo artifact is not evidence for this request. The cache
        // codec is host-bound, but recheck the two cheap identity fields before exporting it.
        DexKitCache.Entry cache = cached != null && cached.versionCode == identity.versionCode
                && identity.baseApkSha256.equals(cached.codeDigest) ? cached : null;
        JSONObject cacheJson = cache == null
                ? new JSONObject() : new JSONObject(DexKitCache.serialize(cache));
        return new JSONObject()
                .put("runtimeDiscovery", discovery)
                .put("dexkitCache", cacheJson);
    }

    private static JSONArray array(java.util.List<String> values) {
        JSONArray result = new JSONArray();
        if (values != null) {
            for (String value : values) result.put(value);
        }
        return result;
    }

    private static boolean hasEvidence(JSONObject resources) {
        JSONObject discovery = resources.optJSONObject("runtimeDiscovery");
        JSONObject cache = resources.optJSONObject("dexkitCache");
        return (discovery != null && (discovery.optJSONArray("candidates").length() > 0
                || discovery.optJSONArray("surfaces").length() > 0))
                || (cache != null && cache.length() > 0);
    }

    /** Matches the server's sorted compact JSON digest, independent of JSONObject map order. */
    private static String canonical(Object value) throws Exception {
        if (value == JSONObject.NULL || value == null) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            StringBuilder result = new StringBuilder("{");
            for (String key : keys) {
                if (result.length() > 1) result.append(',');
                result.append(JSONObject.quote(key)).append(':')
                        .append(canonical(object.opt(key)));
            }
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder result = new StringBuilder("[");
            for (int index = 0; index < array.length(); index++) {
                if (index > 0) result.append(',');
                result.append(canonical(array.opt(index)));
            }
            return result.append(']').toString();
        }
        if (value instanceof String) return JSONObject.quote((String) value);
        return String.valueOf(value);
    }

    private static URL remapUrl(String endpoint) throws Exception {
        URL resolve = new URL(endpoint);
        if (!"https".equals(resolve.getProtocol()) || resolve.getHost().isEmpty()
                || resolve.getUserInfo() != null || resolve.getQuery() != null
                || resolve.getRef() != null || !"/v1/resolve".equals(resolve.getPath())) {
            throw new IllegalArgumentException("invalid catalog endpoint");
        }
        return new URL(resolve.getProtocol(), resolve.getHost(), resolve.getPort(),
                "/v1/remap-resources");
    }

    private static String readBounded(InputStream stream) throws Exception {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_RESPONSE_BYTES) {
                    throw new IllegalArgumentException("response too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
