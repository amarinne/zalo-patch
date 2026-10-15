package com.ez.zalopatch;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class OutcomeEvidenceTest {
    private JSONObject contradiction() throws Exception {
        return new JSONObject().put("outcome", "visible_after_hide").put("route", "me_full_zinstant")
                .put("marker_present", true).put("samples", 2).put("width", 960).put("height", 800);
    }

    @Test public void exportsOnlyFixedOutcomeMetadataAndDropsPayloadAndTransportDetail() throws Exception {
        String raw = contradiction().put("message", "secret message").put("account", "secret account").toString();
        JSONObject result = OutcomeEvidence.parse(raw + "\nconfig_source=private");
        assertNotNull(result);
        assertEquals(6, result.length());
        assertFalse(result.toString().contains("secret"));
        assertFalse(result.toString().contains("private"));
    }

    @Test public void rejectsUnobservedOrSingleSampleInsteadOfInventingFailure() throws Exception {
        assertNull(OutcomeEvidence.parse(contradiction().put("marker_present", false).toString()));
        assertNull(OutcomeEvidence.parse(contradiction().put("samples", 1).toString()));
        assertNull(OutcomeEvidence.parse(contradiction().put("width", 0).toString()));
        assertNull(OutcomeEvidence.parse(contradiction().put("height", -1).toString()));
    }

    @Test public void rejectsUnknownRouteAndMalformedOrUnboundedDetail() throws Exception {
        assertNull(OutcomeEvidence.parse(contradiction().put("route", "contact-name").toString()));
        assertNull(OutcomeEvidence.parse(contradiction().put("height", 32769).toString()));
        assertNull(OutcomeEvidence.parse("not json"));
        assertNull(OutcomeEvidence.parse("x".repeat(4097)));
    }
}
