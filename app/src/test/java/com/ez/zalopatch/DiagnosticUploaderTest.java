package com.ez.zalopatch;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Synthetic receipts only. These tests never open a network connection. */
public final class DiagnosticUploaderTest {
    private static final String REPORT_ID = "R1-00000000000000000000000001";
    private static final String RECEIVED = "2026-10-13T07:00:00Z";
    private static final String EXPIRES = "2027-01-11T07:00:00Z";

    @Test
    public void createdReceiptPreservesTheServiceExpiry() throws Exception {
        DiagnosticUploader.Result result = response(201, receipt("finite", EXPIRES));

        assertTrue(result.successful());
        assertFalse(result.idempotentRetry);
        assertNull(result.failure);
        assertEquals(REPORT_ID, result.receipt.reportId);
        assertEquals(RECEIVED, result.receipt.receivedAtUtc);
        assertEquals(EXPIRES, result.receipt.rawExpiresAtUtc);
        assertEquals("finite", result.receipt.retentionPolicy);
    }

    @Test
    public void retryReceiptDoesNotDependOnTheDeviceClock() throws Exception {
        JSONObject json = receipt("finite", "2001-01-02T00:00:00Z")
                .put("receivedAtUtc", "2001-01-01T00:00:00Z");
        DiagnosticUploader.Result result = response(200, json);

        assertTrue(result.successful());
        assertTrue(result.idempotentRetry);
        assertEquals("2001-01-02T00:00:00Z", result.receipt.rawExpiresAtUtc);
    }

    @Test
    public void legacyIndefiniteReceiptsRemainSuccessful() throws Exception {
        for (int status : new int[]{200, 201}) {
            DiagnosticUploader.Result result = response(status,
                    receipt("indefinite", JSONObject.NULL));

            assertTrue(result.successful());
            assertEquals(status == 200, result.idempotentRetry);
            assertNull(result.receipt.rawExpiresAtUtc);
            assertEquals("indefinite", result.receipt.retentionPolicy);
        }
    }

    @Test
    public void utcOffsetsAndFractionalSecondsAreValidatedAsInstants() throws Exception {
        String expires = "2026-10-13T07:00:00.000000002+00:00";
        JSONObject json = receipt("finite", expires)
                .put("receivedAtUtc", "2026-10-13T07:00:00.000000001Z");

        DiagnosticUploader.Result result = response(201, json);

        assertTrue(result.successful());
        assertEquals(expires, result.receipt.rawExpiresAtUtc);
    }

    @Test
    public void finiteExpiryDoesNotInventAPolicyNameOrNinetyDayLimit() throws Exception {
        // This label is a fixture, not a claim about the deployed service vocabulary.
        DiagnosticUploader.Result result = response(201,
                receipt("service_policy_v2", "2027-10-13T07:00:00Z"));

        assertTrue(result.successful());
        assertEquals("service_policy_v2", result.receipt.retentionPolicy);
        assertEquals("2027-10-13T07:00:00Z", result.receipt.rawExpiresAtUtc);
    }

    @Test
    public void nullExpiryRequiresTheLegacyIndefinitePolicy() throws Exception {
        assertInvalid(receipt("finite", JSONObject.NULL));
        assertInvalid(receipt("unknown_policy", JSONObject.NULL));
    }

    @Test
    public void indefinitePolicyCannotAlsoClaimAnExpiry() throws Exception {
        assertInvalid(receipt("indefinite", EXPIRES));
    }

    @Test
    public void receiptFieldsCannotBeOmitted() throws Exception {
        for (String field : new String[]{"reportId", "receivedAtUtc", "rawExpiresAtUtc",
                "retentionPolicy"}) {
            JSONObject json = receipt("finite", EXPIRES);
            json.remove(field);
            assertInvalid(json);
        }
    }

    @Test
    public void timestampAndPolicyFieldsMustBeBoundedStrings() throws Exception {
        for (String field : new String[]{"receivedAtUtc", "rawExpiresAtUtc", "retentionPolicy"}) {
            for (Object value : new Object[]{JSONObject.NULL, 123, true,
                    new JSONObject(), new JSONArray(), "", " ", "x".repeat(65), "line\nbreak"}) {
                assertInvalid(receipt("finite", EXPIRES).put(field, value));
            }
        }
    }

    @Test
    public void timestampsMustBeRealUtcDates() throws Exception {
        for (String value : new String[]{"not-a-date", "2026-02-29T07:00:00Z",
                "2026-10-32T07:00:00Z", "2026-10-13T24:00:00Z", "2026-10-13T07:00:60Z",
                "2026-10-13", "2026-10-13T07:00:00", "2026-10-13T07:00:00+07:00",
                "2026-10-13T07:00:00Zjunk", " 2026-10-13T07:00:00Z"}) {
            assertInvalid(receipt("finite", EXPIRES).put("receivedAtUtc", value));
            assertInvalid(receipt("finite", value));
        }
        assertInvalid(receipt("indefinite", JSONObject.NULL)
                .put("receivedAtUtc", "not-a-date"));
    }

    @Test
    public void expiryMustBeAfterReceiptTime() throws Exception {
        assertInvalid(receipt("finite", RECEIVED));
        assertInvalid(receipt("finite", "2026-10-13T07:00:00+00:00"));
        assertInvalid(receipt("finite", "2026-10-12T07:00:00Z"));
    }

    @Test
    public void receiptMustMatchTheSubmittedReport() throws Exception {
        assertInvalid(receipt("finite", EXPIRES)
                .put("reportId", "R1-00000000000000000000000002"));
        JSONObject json = receipt("finite", EXPIRES).put("reportId", "invalid");
        DiagnosticUploader.Result result = DiagnosticUploader.mapResponse(
                201, json.toString(), "invalid");
        assertEquals(DiagnosticUploader.Kind.INVALID_RESPONSE, result.failure);
    }

    @Test
    public void malformedJsonIsAnInvalidReceipt() {
        for (String body : new String[]{null, "", "{", "[]", "null"}) {
            DiagnosticUploader.Result result = DiagnosticUploader.mapResponse(201, body, REPORT_ID);
            assertFalse(result.successful());
            assertEquals(DiagnosticUploader.Kind.INVALID_RESPONSE, result.failure);
        }
    }

    private static JSONObject receipt(String policy, Object expires) throws Exception {
        return new JSONObject().put("reportId", REPORT_ID).put("receivedAtUtc", RECEIVED)
                .put("rawExpiresAtUtc", expires).put("retentionPolicy", policy);
    }

    private static DiagnosticUploader.Result response(int status, JSONObject json) {
        return DiagnosticUploader.mapResponse(status, json.toString(), REPORT_ID);
    }

    private static void assertInvalid(JSONObject json) {
        DiagnosticUploader.Result result = response(201, json);
        assertFalse(json.toString(), result.successful());
        assertNull(result.receipt);
        assertEquals(DiagnosticUploader.Kind.INVALID_RESPONSE, result.failure);
    }
}
