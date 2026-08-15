package com.ez.zalopatch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared intake bounds plus the Zalo Patch product contract. */
final class DiagnosticReportContract {
    static final int DESCRIPTION_BYTES = 4_000;
    static final int CLIENT_BODY_BYTES = 384 * 1024;
    static final int LOGCAT_BYTES = 160 * 1024;
    static final int CRASH_BYTES = 64 * 1024;
    static final int LSPOSED_BYTES = 64 * 1024;
    static final long TTL_MS = 30L * 60L * 1000L;
    static final long COMMAND_TIMEOUT_MS = 5_000L;
    static final String PRODUCT = "zalo_patch";
    static final int PRODUCT_REPORT_VERSION = 1;

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> CATEGORIES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("compatibility", "hook_behavior", "ui_behavior", "notifications",
                    "call_recording", "crash_restart", "configuration", "other")));
    private static final Set<String> FORBIDDEN_KEYS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("notificationtitle", "sender", "messagebody", "notificationtext",
                    "notificationhistory", "recallmessages", "recordings", "customrules",
                    "notificationrules", "token", "cookie", "authorization", "androidid",
                    "serial", "imei", "ssid", "accountid")));
    private static final Pattern URI_PATTERN = Pattern.compile(
            "(?i)\\b[a-z][a-z0-9+.-]{1,15}://\\S+");
    private static final Pattern CREDENTIAL_PATTERN = Pattern.compile(
            "(?i)\\b(token|authorization|cookie|set-cookie)\\s*[=:]\\s*\\S+");
    private static final Pattern EXCEPTION_MESSAGE_PATTERN = Pattern.compile(
            "((?:(?:[A-Za-z_][A-Za-z0-9_$]*\\.)*[A-Za-z0-9_$]+"
                    + "(?:Exception|Error)))(?::[^\\n]*|\\s+[^\\n]*)?");

    private DiagnosticReportContract() {
    }

    static String newReportId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return reportIdFromBytes(bytes);
    }

    static String reportIdFromBytes(byte[] bytes) {
        if (bytes == null || bytes.length != 16) {
            throw new IllegalArgumentException("128-bit input required");
        }
        BigInteger value = new BigInteger(1, bytes);
        char[] encoded = new char[26];
        Arrays.fill(encoded, '0');
        BigInteger radix = BigInteger.valueOf(32L);
        for (int index = encoded.length - 1; index >= 0; index--) {
            BigInteger[] division = value.divideAndRemainder(radix);
            encoded[index] = ALPHABET.charAt(division[1].intValue());
            value = division[0];
        }
        return "R1-" + new String(encoded);
    }

    static boolean validReportId(String value) {
        if (value == null || value.length() != 29 || !value.startsWith("R1-")) {
            return false;
        }
        for (int index = 3; index < value.length(); index++) {
            if (ALPHABET.indexOf(value.charAt(index)) < 0) {
                return false;
            }
        }
        return true;
    }

    static boolean validCategory(String value) {
        return CATEGORIES.contains(value);
    }

    static boolean validDescription(String value) {
        return value != null && !value.trim().isEmpty()
                && utf8Bytes(value) <= DESCRIPTION_BYTES;
    }

    static int utf8Bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    static String utf8Prefix(String value, int maxBytes) {
        if (value == null || maxBytes <= 0) return "";
        int index = 0;
        int bytes = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            int nextBytes = utf8CodePointBytes(codePoint);
            if (bytes + nextBytes > maxBytes) break;
            bytes += nextBytes;
            index += Character.charCount(codePoint);
        }
        return value.substring(0, index);
    }

    static String utf8Suffix(String value, int maxBytes) {
        if (value == null || maxBytes <= 0) return "";
        int index = value.length();
        int bytes = 0;
        while (index > 0) {
            int codePoint = value.codePointBefore(index);
            int nextBytes = utf8CodePointBytes(codePoint);
            if (bytes + nextBytes > maxBytes) break;
            bytes += nextBytes;
            index -= Character.charCount(codePoint);
        }
        return value.substring(index);
    }

    static BoundedText truncateLines(String value, int maxBytes) {
        String source = value == null ? "" : value;
        int originalBytes = utf8Bytes(source);
        if (originalBytes <= maxBytes) {
            return new BoundedText(source, false);
        }
        String marker = "\n--- TRUNCATED: middle removed ---\n";
        if (maxBytes <= utf8Bytes(marker)) {
            return new BoundedText(utf8Prefix(source, maxBytes), true);
        }
        int budget = maxBytes - utf8Bytes(marker);
        int prefixBudget = budget / 4;
        String prefixCandidate = utf8Prefix(source, prefixBudget);
        int prefixNewline = prefixCandidate.lastIndexOf('\n');
        String prefix = prefixNewline >= 0
                ? prefixCandidate.substring(0, prefixNewline + 1) : prefixCandidate;
        String suffixCandidate = utf8Suffix(source, budget - prefixBudget);
        int suffixStart = source.length() - suffixCandidate.length();
        String suffix;
        if (suffixCandidate.isEmpty() || suffixStart == 0
                || source.charAt(suffixStart - 1) == '\n') {
            suffix = suffixCandidate;
        } else {
            int suffixNewline = suffixCandidate.indexOf('\n');
            suffix = suffixNewline >= 0 && suffixNewline + 1 < suffixCandidate.length()
                    ? suffixCandidate.substring(suffixNewline + 1) : "";
        }
        return new BoundedText(prefix + marker + suffix, true);
    }

    static String sanitizeLines(String value) {
        if (value == null || value.isEmpty()) return "";
        String[] lines = value.split("\\r?\\n", -1);
        StringBuilder sanitized = new StringBuilder(value.length());
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) sanitized.append('\n');
            sanitized.append(redactLine(lines[index]));
        }
        return sanitized.toString();
    }

    static String redactLine(String value) {
        String redacted = value == null ? "" : value;
        redacted = URI_PATTERN.matcher(redacted).replaceAll("<uri redacted>");
        redacted = CREDENTIAL_PATTERN.matcher(redacted).replaceAll("$1=<redacted>");
        return EXCEPTION_MESSAGE_PATTERN.matcher(redacted)
                .replaceAll("$1: <message redacted>");
    }

    static String boundedToken(String value, int maxBytes) {
        String bounded = utf8Prefix(redactLine(value == null ? "" : value), maxBytes);
        return bounded.replace('\n', ' ').replace('\r', ' ');
    }

    static boolean validDraftJson(String json) {
        if (json == null || utf8Bytes(json) > CLIENT_BODY_BYTES) return false;
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("envelopeVersion", -1) != 1
                    || !validReportId(root.optString("reportId", ""))
                    || !PRODUCT.equals(root.optString("product", ""))
                    || root.optInt("productReportVersion", -1) != PRODUCT_REPORT_VERSION
                    || !validCategory(root.optString("category", ""))
                    || !validDescription(root.optString("description", ""))
                    || root.optString("createdAtUtc", "").isEmpty()
                    || root.optString("createdAtUtc", "").length() > 64
                    || root.optJSONObject("commonMetadata") == null
                    || root.optJSONObject("productMetadata") == null
                    || root.optJSONObject("capture") == null) {
                return false;
            }
            JSONObject raw = root.optJSONObject("rawDiagnostics");
            if (raw == null
                    || utf8Bytes(raw.optString("diagnosticEventsAndLogs", "")) > LOGCAT_BYTES
                    || utf8Bytes(raw.optString("crashExcerpt", "")) > CRASH_BYTES
                    || utf8Bytes(raw.optString("lsposedModuleLines", "")) > LSPOSED_BYTES) {
                return false;
            }
            JSONObject settings = raw.optJSONObject("runtimeSettings");
            if (settings == null || settings.length() > 64) return false;
            return !containsForbiddenKey(root);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean containsForbiddenKey(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String normalized = key.replace("_", "").replace("-", "")
                        .toLowerCase(Locale.US);
                if (FORBIDDEN_KEYS.contains(normalized)) return true;
                if (containsForbiddenKey(object.opt(key))) return true;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                if (containsForbiddenKey(array.opt(index))) return true;
            }
        }
        return false;
    }

    private static int utf8CodePointBytes(int codePoint) {
        if (codePoint <= 0x7f) return 1;
        if (codePoint <= 0x7ff) return 2;
        if (codePoint <= 0xffff) return 3;
        return 4;
    }

    static final class BoundedText {
        final String text;
        final boolean truncated;

        BoundedText(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
    }
}
