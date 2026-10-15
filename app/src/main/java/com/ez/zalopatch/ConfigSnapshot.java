package com.ez.zalopatch;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** One atomic, restart-applied configuration, including explicit empty payloads. */
final class ConfigSnapshot {
    static final String GROUP = "hook_config_v1";
    static final String KEY = "internal.config_snapshot";
    static final String LEGACY_KEY = "internal.settings_snapshot";
    private static final String RULES_DIGEST = "internal.rules_sha256";
    static final int MAX_BYTES = 256 * 1024;
    final Map<String, String> values;
    final String id;
    final long generation;
    final int moduleVersion;

    ConfigSnapshot(Map<String, String> values, String id, long generation, int moduleVersion) {
        this.values = Collections.unmodifiableMap(new TreeMap<>(values));
        this.id = id;
        this.generation = generation;
        this.moduleVersion = moduleVersion;
    }

    String encode() throws Exception {
        JSONObject json = new JSONObject();
        json.put("format", 1);
        json.put("id", id);
        json.put("generation", generation);
        json.put("module", moduleVersion);
        json.put("values", new JSONObject(values));
        json.put("sha256", digest());
        String encoded = json.toString();
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("configuration snapshot too large");
        }
        return encoded;
    }

    static ConfigSnapshot decode(String encoded, int moduleVersion) {
        try {
            if (encoded == null || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                return null;
            }
            JSONObject json = new JSONObject(encoded);
            if (json.getInt("format") != 1 || json.getInt("module") != moduleVersion
                    || json.getLong("generation") < 0 || json.getString("id").isEmpty()) {
                return null;
            }
            JSONObject entries = json.getJSONObject("values");
            Map<String, String> values = new TreeMap<>();
            java.util.Iterator<String> keys = entries.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = entries.get(key);
                if (!(value instanceof String)) return null;
                values.put(key, (String) value);
            }
            ConfigSnapshot snapshot = new ConfigSnapshot(values, json.getString("id"),
                    json.getLong("generation"), moduleVersion);
            if (json.has("sha256") && !snapshot.digest().equals(json.getString("sha256"))) return null;
            return snapshot;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Bind the separate rule blob without reducing its existing transport size limit. */
    ConfigSnapshot propertySnapshot() throws Exception {
        Map<String, String> compact = new TreeMap<>(settingsOnly().values);
        String rules = compact.remove(NotificationRuleStore.MIRROR_KEY);
        if (rules == null) throw new IllegalArgumentException("Missing notification rules");
        compact.put(RULES_DIGEST, sha256(rules));
        return new ConfigSnapshot(compact, id, generation, moduleVersion);
    }

    static ConfigSnapshot decodeProperty(String encoded, int moduleVersion, String rules) {
        try {
            if (encoded == null || rules == null || !new JSONObject(encoded).has("sha256")) return null;
            ConfigSnapshot compact = decode(encoded, moduleVersion);
            if (compact == null || !sha256(rules).equals(compact.values.get(RULES_DIGEST))) return null;
            Map<String, String> values = new TreeMap<>(compact.values);
            values.remove(RULES_DIGEST);
            values.put(NotificationRuleStore.MIRROR_KEY, rules);
            return new ConfigSnapshot(values, compact.id, compact.generation, moduleVersion);
        } catch (Exception invalid) { return null; }
    }

    private String digest() throws Exception {
        StringBuilder material = new StringBuilder(id).append(':').append(moduleVersion)
                .append(':').append(generation);
        values.forEach((key, value) -> material.append(':').append(key.length()).append(':')
                .append(key).append(':').append(value.length()).append(':').append(value));
        return sha256(material.toString());
    }

    private static String sha256(String material) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(
                material.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte value : hash) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }

    ConfigSnapshot settingsOnly() {
        Map<String, String> selected = new TreeMap<>();
        for (Settings.Setting<?> setting : Settings.all()) {
            selected.put(setting.key, values.getOrDefault(setting.key, String.valueOf(setting.defaultValue)));
        }
        if (values.containsKey(NotificationRuleStore.MIRROR_KEY)) {
            selected.put(NotificationRuleStore.MIRROR_KEY, values.get(NotificationRuleStore.MIRROR_KEY));
        }
        return new ConfigSnapshot(selected, id, generation, moduleVersion);
    }

    /** Read an older local snapshot only so the module app can republish it for this version. */
    static ConfigSnapshot previous(String encoded, int currentVersion) {
        try {
            if (encoded == null || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return null;
            int version = new JSONObject(encoded).getInt("module");
            return version > 0 && version < currentVersion ? decode(encoded, version) : null;
        } catch (Exception invalid) {
            return null;
        }
    }

    static String value(ConfigSnapshot snapshot, boolean authoritative, String key,
                        java.util.function.Supplier<String> fallback) {
        return authoritative ? (snapshot == null ? null : snapshot.values.get(key)) : fallback.get();
    }

    static boolean publish(java.util.function.BooleanSupplier remoteCommit,
                           java.util.function.BooleanSupplier localCommit) {
        return (remoteCommit == null || remoteCommit.getAsBoolean()) && localCommit.getAsBoolean();
    }

    boolean acknowledgedBy(String snapshotId, long currentGeneration) {
        return id.equals(snapshotId) && generation == currentGeneration;
    }

    static boolean remoteSupported(long properties, long remoteCapability) {
        return (properties & remoteCapability) != 0;
    }
}
