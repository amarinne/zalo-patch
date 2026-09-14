package com.ez.zalopatch;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;

/**
 * DexKit pilot cache policy and codec.
 *
 * <p>Dependency-free (no Android, no DexKit) so the binding and invalidation rules are
 * JVM-tested. Android persistence lives in {@code DexKitStore}; hook-side selection lives in
 * {@code DexKitZinstantResolver}.
 *
 * <p>Binding: an entry is usable only when installed {@code versionCode}, host code digest
 * (base APK SHA-256), fingerprint {@link DexKitZinstantFingerprint#QUERY_REVISION}, this
 * {@link #RESOLVER_FORMAT}, and the writing module version all match. Package version alone
 * never identifies identical code. Positive and negative entries share the binding, so any
 * code, query, format, or module change invalidates both.
 *
 * <p>Reflection objects are never persisted: only descriptor strings plus metadata.
 */
public final class DexKitCache {
    /**
     * Bumped whenever the serialized shape or the resolution policy changes.
     * Revision 2 adds the base APK size, so a same-timestamp file replacement cannot reuse
     * an entry without a rescan. Revision 3 adds the partial-entry retry counter.
     */
    public static final int RESOLVER_FORMAT = 3;
    public static final int MAX_ENTRY_BYTES = 8 * 1024;
    static final int MAX_REASON_BYTES = 256;

    private DexKitCache() {
    }

    public static final class Entry {
        public final long versionCode;
        public final String codeDigest;
        public final long lastUpdateTime;
        public final long apkSize;
        public final int queryRevision;
        public final int resolverFormat;
        public final int moduleVersion;
        public final String adBind;
        public final String feedBind;
        public final boolean negative;
        public final String reason;
        public final int matchAd;
        public final int matchFeed;
        public final long scanDurationMs;
        public final long scannedAt;
        public final int partialAttempts;

        public Entry(long versionCode, String codeDigest, long lastUpdateTime, long apkSize,
              int queryRevision, int resolverFormat, int moduleVersion, String adBind,
              String feedBind, boolean negative, String reason, int matchAd, int matchFeed,
              long scanDurationMs, long scannedAt, int partialAttempts) {
            this.versionCode = versionCode;
            this.codeDigest = codeDigest == null ? "" : codeDigest;
            this.lastUpdateTime = lastUpdateTime;
            this.apkSize = apkSize;
            this.queryRevision = queryRevision;
            this.resolverFormat = resolverFormat;
            this.moduleVersion = moduleVersion;
            this.adBind = adBind == null ? "" : adBind;
            this.feedBind = feedBind == null ? "" : feedBind;
            this.negative = negative;
            this.reason = reason == null ? "" : reason;
            this.matchAd = matchAd;
            this.matchFeed = matchFeed;
            this.scanDurationMs = scanDurationMs;
            this.scannedAt = scannedAt;
            this.partialAttempts = partialAttempts;
        }
    }

    /**
     * True when the entry was written for this exact host code, query, format, and module.
     *
     * <p>The digest is trusted (recomputing it every launch would defeat the lightweight
     * check); the size is the cheap file-identity tripwire beside {@code lastUpdateTime}.
     * A same-timestamp replacement of identical size remains an accepted residual risk:
     * live preflight stays the load-bearing check for that case.
     */
    public static boolean bindsTo(Entry entry, long versionCode, String codeDigest,
                                  long lastUpdateTime, long apkSize, int queryRevision,
                                  int resolverFormat, int moduleVersion) {
        if (entry == null || versionCode <= 0L || codeDigest == null || codeDigest.isEmpty()) {
            return false;
        }
        return entry.versionCode == versionCode
                && codeDigest.equals(entry.codeDigest)
                && entry.lastUpdateTime == lastUpdateTime
                && entry.apkSize == apkSize
                && entry.queryRevision == queryRevision
                && entry.resolverFormat == resolverFormat
                && entry.moduleVersion == moduleVersion;
    }

    /** True when both anchors resolved to usable descriptors. */
    public static boolean fullyResolved(Entry entry) {
        return entry != null && !entry.negative
                && isMethodName(entry.adBind) && isMethodName(entry.feedBind);
    }

    public static boolean isMethodName(String value) {
        return value != null && !value.isEmpty() && value.length() <= 128
                && value.matches("[A-Za-z_$][A-Za-z0-9_$]*");
    }

    public static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    /** Fixed key order, so the same entry always serializes byte-identically. */
    public static String serialize(Entry entry) {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        field(json, "version_code", Long.toString(entry.versionCode), false);
        field(json, "code_digest", quote(entry.codeDigest), false);
        field(json, "last_update_time", Long.toString(entry.lastUpdateTime), false);
        field(json, "apk_size", Long.toString(entry.apkSize), false);
        field(json, "query_revision", Integer.toString(entry.queryRevision), false);
        field(json, "resolver_format", Integer.toString(entry.resolverFormat), false);
        field(json, "module_version", Integer.toString(entry.moduleVersion), false);
        field(json, "ad_bind", quote(entry.adBind), false);
        field(json, "feed_bind", quote(entry.feedBind), false);
        field(json, "negative", entry.negative ? "true" : "false", false);
        field(json, "reason", quote(bound(entry.reason, MAX_REASON_BYTES)), false);
        field(json, "match_ad", Integer.toString(entry.matchAd), false);
        field(json, "match_feed", Integer.toString(entry.matchFeed), false);
        field(json, "scan_duration_ms", Long.toString(entry.scanDurationMs), false);
        field(json, "scanned_at", Long.toString(entry.scannedAt), false);
        field(json, "partial_attempts", Integer.toString(entry.partialAttempts), true);
        json.append('}');
        return json.toString();
    }

    public static Entry parse(String json) throws JSONException {
        if (json == null || json.isEmpty() || json.length() > MAX_ENTRY_BYTES) {
            throw new JSONException("dexkit cache entry missing or oversized");
        }
        JSONObject root = new JSONObject(json);
        assertNoUnknownKeys(root);
        Entry entry = new Entry(
                root.getLong("version_code"),
                root.getString("code_digest"),
                root.getLong("last_update_time"),
                root.optLong("apk_size", -1L),
                root.getInt("query_revision"),
                root.getInt("resolver_format"),
                root.getInt("module_version"),
                root.optString("ad_bind", ""),
                root.optString("feed_bind", ""),
                root.optBoolean("negative", false),
                root.optString("reason", ""),
                root.optInt("match_ad", 0),
                root.optInt("match_feed", 0),
                root.optLong("scan_duration_ms", 0L),
                root.optLong("scanned_at", 0L),
                root.optInt("partial_attempts", 0));
        if (entry.versionCode <= 0L || !isSha256(entry.codeDigest)) {
            throw new JSONException("dexkit cache entry has invalid artifact binding");
        }
        if (entry.partialAttempts < 0) {
            throw new JSONException("dexkit cache entry has invalid retry counter");
        }
        if (entry.negative) {
            if (!entry.adBind.isEmpty() || !entry.feedBind.isEmpty()) {
                throw new JSONException("negative dexkit cache entry must not carry descriptors");
            }
        } else if (!entry.adBind.isEmpty() && !isMethodName(entry.adBind)
                || !entry.feedBind.isEmpty() && !isMethodName(entry.feedBind)) {
            throw new JSONException("dexkit cache entry has invalid descriptors");
        }
        return entry;
    }

    private static void assertNoUnknownKeys(JSONObject root) throws JSONException {
        String[] known = {"version_code", "code_digest", "last_update_time", "apk_size",
                "query_revision", "resolver_format", "module_version", "ad_bind", "feed_bind",
                "negative", "reason", "match_ad", "match_feed", "scan_duration_ms",
                "scanned_at", "partial_attempts"};
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            boolean found = false;
            for (String candidate : known) {
                if (candidate.equals(key)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new JSONException("unknown dexkit cache key: " + key);
            }
        }
    }

    private static void field(StringBuilder json, String key, String value, boolean last) {
        json.append('"').append(key).append("\":").append(value);
        if (!last) {
            json.append(',');
        }
    }

    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 2);
        quoted.append('"');
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"':
                    quoted.append("\\\"");
                    break;
                case '\\':
                    quoted.append("\\\\");
                    break;
                default:
                    if (c < 0x20) {
                        quoted.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                    break;
            }
        }
        quoted.append('"');
        return quoted.toString();
    }

    private static String bound(String value, int maxBytes) {
        if (value.length() <= maxBytes) {
            return value;
        }
        return value.substring(0, maxBytes);
    }
}
