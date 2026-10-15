package com.ez.zalopatch;

import org.json.JSONObject;

/** The only outcome metadata allowed in the private diagnostic envelope. */
public final class OutcomeEvidence {
    private OutcomeEvidence() {}

    public static JSONObject parse(String detail) {
        if (detail == null || detail.length() > 4096) return null;
        try {
            JSONObject raw = new JSONObject(detail.split("\n", 2)[0]);
            if (!"visible_after_hide".equals(raw.optString("outcome"))) return null;
            String route = raw.optString("route");
            if (!java.util.Arrays.asList("me_full_zinstant", "me_template", "me_exclusive",
                    "me_text_row", "inbox_banner", "inbox_media_box", "message_ad", "feed_ad").contains(route)) return null;
            if (!raw.getBoolean("marker_present") || raw.getInt("samples") != 2) return null;
            int width = raw.getInt("width"), height = raw.getInt("height");
            if (width <= 0 || height <= 0 || width > 32768 || height > 32768) return null;
            // Reconstruct the object. Never copy arbitrary keys, payloads, or text from detail.
            return new JSONObject().put("outcome", "visible_after_hide").put("route", route)
                    .put("marker_present", true).put("samples", 2).put("width", width).put("height", height);
        } catch (Exception invalid) {
            return null;
        }
    }
}
