package com.ez.zalopatch;

import android.content.SharedPreferences;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/** Private display metadata. It contains no message bodies or plain account/contact identifiers. */
public final class RecallMarkerStore {
    public static final String PREFERENCES = "zalopatch_recalled_text";
    static final int MAX_MARKS = 4096;
    private final SharedPreferences preferences;
    private final Map<String, Long> marks = new HashMap<>();

    public RecallMarkerStore(SharedPreferences preferences) {
        this.preferences = preferences;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().matches("[0-9a-f]{64}") && entry.getValue() instanceof Long
                    && (Long) entry.getValue() > 0) marks.put(entry.getKey(), (Long) entry.getValue());
        }
    }

    public synchronized boolean contains(RecallIdentity identity) {
        String key = key(identity);
        return !key.isEmpty() && marks.containsKey(key);
    }

    /** Called only after the recall gate has decided to keep this exact message. */
    public synchronized boolean remember(RecallIdentity identity, long now) {
        String key = key(identity);
        if (key.isEmpty()) return false;
        if (marks.containsKey(key)) return true;
        SharedPreferences.Editor editor = preferences.edit();
        String oldest = null;
        if (marks.size() >= MAX_MARKS) {
            oldest = marks.entrySet().stream().min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse(null);
            if (oldest != null) editor.remove(oldest);
        }
        long timestamp = Math.max(1L, now);
        if (!editor.putLong(key, timestamp).commit()) return false;
        if (oldest != null) marks.remove(oldest);
        marks.put(key, timestamp);
        return true;
    }

    /** Length framing keeps ambiguous concatenations and other accounts/messages separate. */
    public static String key(RecallIdentity identity) {
        if (identity == null || !identity.complete()) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[]{identity.account, identity.owner, identity.sender,
                    identity.client, identity.global}) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String timeLabel(String nativeTime, String label) {
        return nativeTime == null || nativeTime.isEmpty() ? label : nativeTime + " · " + label;
    }
}
