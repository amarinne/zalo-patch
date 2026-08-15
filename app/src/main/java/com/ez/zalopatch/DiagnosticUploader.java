package com.ez.zalopatch;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HttpsURLConnection;

/** One-shot shared-intake uploader. Redirects and automatic retries are disabled. */
final class DiagnosticUploader {
    private final String endpoint;

    DiagnosticUploader(String endpoint) {
        this.endpoint = endpoint;
    }

    Result upload(DiagnosticReportFactory.Draft draft) {
        if (draft == null || !DiagnosticReportContract.validDraftJson(draft.json)) {
            return Result.failure(Kind.INVALID_REPORT);
        }
        byte[] bytes = draft.json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > DiagnosticReportContract.CLIENT_BODY_BYTES) {
            return Result.failure(Kind.REQUEST_TOO_LARGE);
        }
        URL url;
        try {
            url = new URL(endpoint);
        } catch (Exception ignored) {
            return Result.failure(Kind.INVALID_REPORT);
        }
        if (!"https".equals(url.getProtocol()) || url.getHost() == null
                || url.getHost().isEmpty() || url.getUserInfo() != null
                || url.getQuery() != null || url.getRef() != null
                || !REPORT_PATH.equals(url.getPath())) {
            return Result.failure(Kind.INVALID_REPORT);
        }
        return executeWithDeadline(url, bytes, draft.reportId);
    }

    private Result executeWithDeadline(URL url, byte[] bytes, String reportId) {
        AtomicReference<HttpsURLConnection> connectionRef = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ZaloPatchDiagnosticUpload");
            thread.setDaemon(true);
            return thread;
        });
        Future<Result> future = executor.submit(() ->
                executeRequest(url, bytes, reportId, connectionRef));
        try {
            return future.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
            HttpsURLConnection connection = connectionRef.get();
            if (connection != null) connection.disconnect();
            future.cancel(true);
            return Result.failure(Kind.TIMEOUT);
        } catch (Exception ignored) {
            return Result.failure(Kind.NETWORK);
        } finally {
            executor.shutdownNow();
        }
    }

    private Result executeRequest(URL url, byte[] bytes, String reportId,
                                  AtomicReference<HttpsURLConnection> connectionRef) {
        HttpsURLConnection connection = null;
        try {
            java.net.URLConnection opened = url.openConnection();
            if (!(opened instanceof HttpsURLConnection)) {
                return Result.failure(Kind.INVALID_REPORT);
            }
            connection = (HttpsURLConnection) opened;
            connectionRef.set(connection);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Connection", "close");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            String response = readResponseBody(connection, status);
            return mapResponse(status, response, reportId);
        } catch (SocketTimeoutException ignored) {
            return Result.failure(Kind.TIMEOUT);
        } catch (IOException ignored) {
            return Result.failure(Kind.NETWORK);
        } catch (Exception ignored) {
            return Result.failure(Kind.NETWORK);
        } finally {
            connectionRef.compareAndSet(connection, null);
            if (connection != null) connection.disconnect();
        }
    }

    static Result mapResponse(int status, String body, String expectedReportId) {
        if (status == HttpURLConnection.HTTP_CREATED || status == HttpURLConnection.HTTP_OK) {
            Receipt receipt = parseReceipt(body, expectedReportId);
            return receipt == null ? Result.failure(Kind.INVALID_RESPONSE)
                    : Result.success(receipt, status == HttpURLConnection.HTTP_OK);
        }
        return Result.failure(mapStatus(status));
    }

    static Kind mapStatus(int status) {
        if (status == HttpURLConnection.HTTP_BAD_REQUEST) return Kind.INVALID_REPORT;
        if (status == HttpURLConnection.HTTP_CONFLICT) return Kind.REPORT_ID_COLLISION;
        if (status == HttpURLConnection.HTTP_ENTITY_TOO_LARGE) return Kind.REQUEST_TOO_LARGE;
        if (status == 429) return Kind.RATE_LIMITED;
        if (status == HttpURLConnection.HTTP_UNAVAILABLE) return Kind.STORAGE_UNAVAILABLE;
        if (status >= 300 && status <= 399) return Kind.REDIRECT_REJECTED;
        if (status >= 500 && status <= 599) return Kind.SERVER_ERROR;
        return Kind.INVALID_RESPONSE;
    }

    private static Receipt parseReceipt(String body, String expectedReportId) {
        try {
            JSONObject json = new JSONObject(body);
            String reportId = json.getString("reportId");
            String received = json.getString("receivedAtUtc");
            if (!json.has("rawExpiresAtUtc") || !json.isNull("rawExpiresAtUtc")) return null;
            String retention = json.getString("retentionPolicy");
            if (!expectedReportId.equals(reportId)
                    || !DiagnosticReportContract.validReportId(reportId)
                    || received.isEmpty() || received.length() > 64
                    || !"indefinite".equals(retention)) {
                return null;
            }
            return new Receipt(reportId, received, retention);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readResponseBody(HttpsURLConnection connection, int status)
            throws IOException {
        InputStream stream = status >= 200 && status <= 299
                ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) return "";
        try (InputStream input = stream;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4 * 1024];
            int total = 0;
            while (true) {
                int count = input.read(buffer);
                if (count < 0) break;
                total += count;
                if (total > RESPONSE_LIMIT_BYTES) return "";
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    enum Kind {
        INVALID_REPORT,
        REPORT_ID_COLLISION,
        REQUEST_TOO_LARGE,
        RATE_LIMITED,
        STORAGE_UNAVAILABLE,
        SERVER_ERROR,
        REDIRECT_REJECTED,
        TIMEOUT,
        NETWORK,
        INVALID_RESPONSE
    }

    static final class Receipt {
        final String reportId;
        final String receivedAtUtc;
        final String retentionPolicy;

        Receipt(String reportId, String receivedAtUtc, String retentionPolicy) {
            this.reportId = reportId;
            this.receivedAtUtc = receivedAtUtc;
            this.retentionPolicy = retentionPolicy;
        }
    }

    static final class Result {
        final Receipt receipt;
        final boolean idempotentRetry;
        final Kind failure;

        private Result(Receipt receipt, boolean idempotentRetry, Kind failure) {
            this.receipt = receipt;
            this.idempotentRetry = idempotentRetry;
            this.failure = failure;
        }

        static Result success(Receipt receipt, boolean idempotentRetry) {
            return new Result(receipt, idempotentRetry, null);
        }

        static Result failure(Kind kind) {
            return new Result(null, false, kind);
        }

        boolean successful() {
            return receipt != null;
        }
    }

    private static final String REPORT_PATH = "/v1/reports";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final long REQUEST_TIMEOUT_MS = 15_000L;
    private static final int RESPONSE_LIMIT_BYTES = 32 * 1024;
}
