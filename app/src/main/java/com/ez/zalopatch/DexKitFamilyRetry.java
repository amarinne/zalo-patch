package com.ez.zalopatch;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Artifact-bound recovery. Deterministic misses stay pinned; transient failures get three scans. */
public final class DexKitFamilyRetry {
    public static final int MAX_ATTEMPTS = 3;
    public static final List<String> FAMILIES = Collections.unmodifiableList(Arrays.asList(
            "zinstant", "webview", "passcode", "backup", "telemetry", "bottom_tabs",
            "me", "inbox", "chat", "call_recording"));
    private static final List<String> STATUSES = Arrays.asList(
            "resolved", "no_match", "ambiguous", "query_error", "preflight_rejected");

    private DexKitFamilyRetry() { }

    public static final class State {
        public final String status;
        public final int attempts;

        public State(String status, int attempts) {
            if (!STATUSES.contains(status) || attempts < 0 || attempts > MAX_ATTEMPTS) {
                throw new IllegalArgumentException("invalid family recovery state");
            }
            this.status = status;
            this.attempts = attempts;
        }
    }

    static Map<String, State> copyStates(Map<String, State> states) {
        Map<String, State> copy = new LinkedHashMap<>();
        if (states != null) {
            for (String family : FAMILIES) {
                if (states.get(family) != null) copy.put(family, states.get(family));
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    public static Set<String> pending(DexKitCache.Entry entry) {
        Set<String> pending = new LinkedHashSet<>();
        for (String family : FAMILIES) {
            State state = entry == null ? null : entry.families.get(family);
            if (state == null || (("query_error".equals(state.status)
                    || "preflight_rejected".equals(state.status))
                    && state.attempts < MAX_ATTEMPTS)) pending.add(family);
        }
        return pending;
    }

    public static State scanned(State previous, String status) {
        return new State(status, Math.min(MAX_ATTEMPTS,
                (previous == null ? 0 : previous.attempts) + 1));
    }

    public static String familyOf(String anchor) {
        for (String family : FAMILIES) {
            if (anchor.startsWith("symbols." + family + ".")) return family;
        }
        return "";
    }

    /** Query adapters emit semantic statuses plus diagnostic counts; only statuses decide retry. */
    public static String status(String family, Map<String, String> descriptors,
                                 Map<String, String> diagnostics) {
        boolean resolved = false;
        for (String anchor : descriptors.keySet()) {
            if (family.equals(familyOf(anchor))) resolved = true;
        }
        boolean ambiguous = false;
        boolean semantic = false;
        for (Map.Entry<String, String> item : diagnostics.entrySet()) {
            if (!family.equals(familyOf(item.getKey()))) continue;
            String key = item.getKey();
            if (key.endsWith("dump_errors")) {
                if (!"0".equals(item.getValue())) return "query_error";
                continue;
            }
            if (key.contains(".field_") || key.endsWith("candidates")
                    || key.endsWith("candidate_names") || key.endsWith("dump_hits")
                    || key.endsWith("selected") || key.endsWith("shapes")
                    || key.endsWith("number4_users") || key.contains(".layout_")
                    || key.endsWith("calibration") || key.endsWith("values")) continue;
            String value = item.getValue();
            if (value == null || value.isEmpty()) continue;
            if (value.startsWith("ambiguous") || value.equals("duplicate")
                    || value.equals("too_many_candidates")) {
                ambiguous = true;
            } else if (value.equals("calibration_contradicted")) {
                return "preflight_rejected";
            } else if (value.startsWith("no_") || value.startsWith("missing_")
                    || value.equals("confidence_below_threshold")
                    || value.equals("enum_group_missing")
                    || value.endsWith("_changed") || value.endsWith("mapping_failed")
                    || value.startsWith("resolved")) {
                semantic = true;
            } else {
                return "query_error";
            }
        }
        return resolved ? "resolved" : ambiguous ? "ambiguous" : semantic ? "no_match" : "query_error";
    }

    /** Preserve only the pilot descriptor whose query failed, after warm preflight approved it. */
    public static DexKitCache.Entry pilotResult(DexKitPilotPolicy.ScanInputs inputs,
                                               DexKitCache.Entry previous) {
        if (!inputs.adQueryError.isEmpty()) {
            inputs.adSymbol = previous == null ? "" : previous.adBind;
            inputs.adStatus = inputs.adSymbol.isEmpty() ? "query_error" : "resolved";
        }
        if (!inputs.feedQueryError.isEmpty()) {
            inputs.feedSymbol = previous == null ? "" : previous.feedBind;
            inputs.feedStatus = inputs.feedSymbol.isEmpty() ? "query_error" : "resolved";
        }
        inputs.adQueryError = "";
        inputs.feedQueryError = "";
        return DexKitPilotPolicy.resolveScanOutcome(inputs).entry;
    }

    /** A retry query error must not erase already validated descriptors in that family. */
    public static void mergeScan(Map<String, String> existing, Map<String, String> found,
                                 String family, String status) {
        if (!"query_error".equals(status)) {
            existing.keySet().removeIf(key -> family.equals(familyOf(key)));
        }
        for (Map.Entry<String, String> item : found.entrySet()) {
            if (family.equals(familyOf(item.getKey()))) existing.put(item.getKey(), item.getValue());
        }
    }

    /** Filters rejected descriptors immediately without resetting their recovery counters. */
    public static DexKitCache.Entry preflight(DexKitCache.Entry entry, Map<String, String> kept) {
        Map<String, State> states = new LinkedHashMap<>(entry.families);
        for (String anchor : entry.extended.keySet()) {
            if (kept.containsKey(anchor)) continue;
            String family = familyOf(anchor);
            if (family.isEmpty()) continue;
            State old = states.get(family);
            states.put(family, new State("preflight_rejected", old == null ? 0 : old.attempts));
        }
        return withStates(DexKitCache.replaceExtended(entry, kept), states);
    }

    public static DexKitCache.Entry withStates(DexKitCache.Entry entry, Map<String, State> states) {
        return new DexKitCache.Entry(entry.versionCode, entry.codeDigest, entry.lastUpdateTime,
                entry.apkSize, entry.queryRevision, entry.resolverFormat, entry.moduleVersion,
                entry.adBind, entry.feedBind, entry.negative, entry.reason, entry.matchAd,
                entry.matchFeed, entry.scanDurationMs, entry.scannedAt, entry.partialAttempts,
                entry.extended, states);
    }

    static String serialize(Map<String, State> states) {
        StringBuilder json = new StringBuilder("{");
        for (String family : FAMILIES) {
            State state = states.get(family);
            if (state == null) continue;
            if (json.length() > 1) json.append(',');
            json.append('"').append(family).append("\":{\"status\":\"")
                    .append(state.status).append("\",\"attempts\":")
                    .append(state.attempts).append('}');
        }
        return json.append('}').toString();
    }

    static Map<String, State> parse(JSONObject json) throws JSONException {
        Map<String, State> states = new LinkedHashMap<>();
        if (json == null) return states;
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String family = keys.next();
            if (!FAMILIES.contains(family)) throw new JSONException("unknown dexkit family");
            JSONObject value = json.getJSONObject(family);
            try {
                states.put(family, new State(value.getString("status"), value.getInt("attempts")));
            } catch (IllegalArgumentException error) {
                throw new JSONException(error.getMessage());
            }
        }
        return states;
    }
}
