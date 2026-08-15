package com.ez.zalopatch;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

final class SymbolCatalogClient {
    private SymbolCatalogClient() {
    }

    static Result resolve(String endpoint, ZaloArtifactIdentity identity,
                          SymbolCatalogContract.Entry current) {
        HttpsURLConnection connection = null;
        try {
            URL url = new URL(endpoint);
            if (!"https".equals(url.getProtocol()) || url.getHost().isEmpty()
                    || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null
                    || !"/v1/resolve".equals(url.getPath())) {
                return Result.failure("invalid_endpoint");
            }
            JSONObject request = new JSONObject()
                    .put("protocolVersion", SymbolCatalogContract.PROTOCOL_VERSION)
                    .put("packageName", SymbolSchema.TARGET_PACKAGE)
                    .put("versionCode", identity.versionCode)
                    .put("versionName", identity.versionName)
                    .put("baseApkSha256", identity.baseApkSha256)
                    .put("signerSha256", identity.signerSha256)
                    .put("moduleVersionCode", BuildConfig.VERSION_CODE)
                    .put("currentCatalogSequence", current == null ? 0 : current.sequence)
                    .put("currentEntryDigest", current == null ? JSONObject.NULL : current.digest);
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            connection = (HttpsURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(10_000);
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
            if (status == HttpURLConnection.HTTP_OK) {
                return Result.available(readBounded(connection.getInputStream()));
            }
            if (status == HttpURLConnection.HTTP_NO_CONTENT) return Result.status("current");
            if (status == HttpURLConnection.HTTP_ACCEPTED) return Result.status("pending");
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return Result.status("unknown");
            if (status == HttpURLConnection.HTTP_GONE) return Result.status("revoked");
            if (status == 426) return Result.status("module_too_old");
            if (status == 429) return Result.status("rate_limited");
            if (status >= 300 && status <= 399) return Result.failure("redirect_rejected");
            if (status >= 500) return Result.failure("server_error");
            return Result.failure("invalid_response");
        } catch (Exception ignored) {
            return Result.failure("network_error");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readBounded(InputStream stream) throws Exception {
        try (InputStream input = stream;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > SymbolCatalogContract.MAX_ENTRY_BYTES) {
                    throw new IllegalArgumentException("catalog response too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    static final class Result {
        final String status;
        final byte[] envelope;

        private Result(String status, byte[] envelope) {
            this.status = status;
            this.envelope = envelope;
        }

        static Result available(byte[] envelope) {
            return new Result("available", envelope);
        }

        static Result status(String status) {
            return new Result(status, null);
        }

        static Result failure(String status) {
            return new Result(status, null);
        }
    }
}
