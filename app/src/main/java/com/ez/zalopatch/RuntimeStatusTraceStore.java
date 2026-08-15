package com.ez.zalopatch;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Bounded metadata-only history of runtime failures and stale anchors. */
final class RuntimeStatusTraceStore {
    private static final String PREFS = "runtime_status_trace";
    private static final String KEY_EVENTS = "events";
    private static final int MAX_EVENTS = 64;

    private RuntimeStatusTraceStore() {
    }

    static void record(Context context, String feature, ContentValues values) {
        if (context == null || values == null) return;
        String status = safe(values.getAsString("status"), 32);
        if (!"failed".equals(status) && !"stale".equals(status)) return;
        JSONObject event = new JSONObject();
        try {
            event.put("feature", safe(feature, 128));
            event.put("status", status);
            event.put("target", safe(values.getAsString("target"), 512));
            event.put("errorType", errorType(values.getAsString("error")));
            event.put("errorCode", errorCode(values.getAsString("error")));
            event.put("updatedAt", Math.max(0L, longValue(values, "updated_at")));
            event.put("moduleVersionCode", BuildConfig.VERSION_CODE);
            event.put("artifactGeneration", safe(
                    values.getAsString("artifact_generation"), 128));
            event.put("runId", safe(values.getAsString("run_id"), 128));
            SharedPreferences preferences = preferences(context);
            JSONArray current = parse(preferences.getString(KEY_EVENTS, "[]"));
            JSONArray next = new JSONArray();
            int start = Math.max(0, current.length() - (MAX_EVENTS - 1));
            for (int index = start; index < current.length(); index++) {
                next.put(current.get(index));
            }
            if (next.length() == 0 || !same(next.optJSONObject(next.length() - 1), event)) {
                next.put(event);
            }
            preferences.edit().putString(KEY_EVENTS, next.toString()).commit();
        } catch (Exception ignored) {
        }
    }

    static void archiveCurrent(Context context) {
        SharedPreferences preferences = TweakStore.preferences(context);
        Map<String, ?> all = preferences.getAll();
        HashSet<String> features = new HashSet<>();
        for (String key : all.keySet()) {
            if (!key.startsWith("selfcheck.") || !key.endsWith(".status")) continue;
            features.add(key.substring("selfcheck.".length(), key.length() - ".status".length()));
        }
        for (String feature : features) {
            String prefix = "selfcheck." + feature + ".";
            String status = preferences.getString(prefix + "status", "");
            if (!"failed".equals(status) && !"stale".equals(status)) continue;
            ContentValues values = new ContentValues();
            values.put("status", status);
            values.put("target", preferences.getString(prefix + "target", ""));
            values.put("error", preferences.getString(prefix + "error", ""));
            values.put("updated_at", preferences.getLong(prefix + "updated_at", 0L));
            values.put("artifact_generation", preferences.getString(
                    prefix + "artifact_generation", ""));
            values.put("run_id", preferences.getString(prefix + "run_id", ""));
            record(context, feature, values);
        }
    }

    static List<JSONObject> load(Context context) {
        ArrayList<JSONObject> events = new ArrayList<>();
        JSONArray array = parse(preferences(context).getString(KEY_EVENTS, "[]"));
        for (int index = 0; index < array.length(); index++) {
            JSONObject event = array.optJSONObject(index);
            if (event != null) events.add(event);
        }
        return events;
    }

    static String raw(Context context) {
        return preferences(context).getString(KEY_EVENTS, "[]");
    }

    static void restore(Context context, String raw) {
        preferences(context).edit().putString(KEY_EVENTS, raw == null ? "[]" : raw).commit();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONArray parse(String raw) {
        try {
            return new JSONArray(raw == null ? "[]" : raw);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static boolean same(JSONObject previous, JSONObject current) {
        return previous != null
                && previous.optString("feature").equals(current.optString("feature"))
                && previous.optString("status").equals(current.optString("status"))
                && previous.optString("target").equals(current.optString("target"))
                && previous.optString("errorType").equals(current.optString("errorType"))
                && previous.optString("errorCode").equals(current.optString("errorCode"))
                && previous.optString("runId").equals(current.optString("runId"));
    }

    private static long longValue(ContentValues values, String key) {
        Long value = values.getAsLong(key);
        return value == null ? 0L : value;
    }

    private static String errorType(String error) {
        if (error == null || error.isEmpty()) return "";
        int separator = error.indexOf(':');
        return safe(separator < 0 ? error : error.substring(0, separator), 96);
    }

    private static String errorCode(String error) {
        String lower = error == null ? "" : error.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("unknown authority")) return "unknown_authority";
        if (lower.contains("classnotfound")) return "class_not_found";
        if (lower.contains("nosuchmethod")) return "no_such_method";
        if (lower.contains("nosuchfield")) return "no_such_field";
        if (lower.contains("securityexception")) return "security_exception";
        if (lower.contains("nullpointer")) return "null_pointer";
        if (lower.contains("illegalargument")) return "illegal_argument";
        return error == null || error.isEmpty() ? "" : "other";
    }

    private static String safe(String value, int max) {
        if (value == null) return "";
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}
