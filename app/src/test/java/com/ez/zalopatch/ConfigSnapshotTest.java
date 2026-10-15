package com.ez.zalopatch;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public final class ConfigSnapshotTest {
    @Test public void onlyValidOlderSnapshotsCanBeMigratedByTheModuleApp() throws Exception {
        String old = new ConfigSnapshot(Map.of("setting", "true"), "old", 4, 319).encode();
        assertNull(ConfigSnapshot.decode(old, 328));
        assertEquals("true", ConfigSnapshot.previous(old, 328).values.get("setting"));
        assertEquals(4, ConfigSnapshot.previous(old, 328).generation);
        assertNull(ConfigSnapshot.previous(old, 319));
        assertNull(ConfigSnapshot.previous(old, 318));
        assertNull(ConfigSnapshot.previous("garbage", 328));
        assertNull(ConfigSnapshot.previous(new ConfigSnapshot(Map.of(), "", 1, 319).encode(), 328));
    }

    @Test public void capabilityIsAnAdvertisedBitNotAFrameworkName() {
        assertFalse(ConfigSnapshot.remoteSupported(1, 2));
        assertTrue(ConfigSnapshot.remoteSupported(3, 2));
        assertTrue(ConfigSnapshot.remoteSupported(7, 2));
    }

    @Test public void remoteCommitFailureCannotPublishProviderSuccess() {
        AtomicBoolean localWritten = new AtomicBoolean();
        assertFalse(ConfigSnapshot.publish(() -> false, () -> { localWritten.set(true); return true; }));
        assertFalse(localWritten.get());
        assertTrue(ConfigSnapshot.publish(() -> true, () -> true));
        assertTrue(ConfigSnapshot.publish(null, () -> true));
        assertFalse(ConfigSnapshot.publish(null, () -> false));
    }

    @Test public void coldStartFreezesSettingsUntilANewSnapshotIsSelected() throws Exception {
        Map<String, String> frameworkMap = new HashMap<>();
        frameworkMap.put("ui.hide_discovery_tab", "true");
        ConfigSnapshot first = new ConfigSnapshot(frameworkMap, "first", 1, 319);
        ConfigSnapshot coldStart = ConfigSnapshot.decode(first.encode(), 319);
        frameworkMap.put("ui.hide_discovery_tab", "false");
        ConfigSnapshot second = new ConfigSnapshot(frameworkMap, "second", 2, 319);
        assertEquals("true", coldStart.values.get("ui.hide_discovery_tab"));
        assertEquals("false", ConfigSnapshot.decode(second.encode(), 319).values.get("ui.hide_discovery_tab"));
        assertFalse(second.acknowledgedBy("first", 2));
        assertFalse(second.acknowledgedBy("second", 3));
        assertTrue(second.acknowledgedBy("second", 2));
    }

    @Test public void authoritativeEmptyAndMissingValuesNeverRevivePriorProperties() {
        ConfigSnapshot snapshot = new ConfigSnapshot(Map.of("dexkit.cache", "",
                "ui.hide_discovery_tab", "false"), "new", 2, 319);
        assertEquals("false", ConfigSnapshot.value(snapshot, true, "ui.hide_discovery_tab", () -> "true"));
        assertEquals("", ConfigSnapshot.value(snapshot, true, "dexkit.cache", () -> "stale cache"));
        assertNull(ConfigSnapshot.value(snapshot, true, "catalog.profile", () -> "stale catalog"));
        assertNull(ConfigSnapshot.value(null, true, "artifact.status", () -> "ready"));
        assertEquals("legacy", ConfigSnapshot.value(null, false, "key", () -> "legacy"));
    }

    @Test public void artifactAndPayloadStateTravelTogetherWithoutShellEncodingLimits() throws Exception {
        Map<String, String> values = Map.of(ZaloArtifactState.KEY_STATUS, "ready",
                ZaloArtifactState.KEY_LIGHTWEIGHT, "exact-host", ZaloArtifactState.KEY_PROFILE_SHA256, "hash",
                NotificationRuleStore.MIRROR_KEY, "{\"keyword_blocklist\":[\"Khuyến mãi\"]}",
                DexKitMirror.MIRROR_CACHE_KEY, "cache JSON", DexKitMirror.MIRROR_BUDGET_KEY, "scope\n2\n100",
                SymbolCatalogMirror.MIRROR_KEY, "compressed profile");
        ConfigSnapshot decoded = ConfigSnapshot.decode(new ConfigSnapshot(values, "state", 0, 319).encode(), 319);
        assertEquals(values, decoded.values);
        assertEquals(2, DexKitMirror.decodeBudget(decoded.values.get(DexKitMirror.MIRROR_BUDGET_KEY)).failures);
        assertNull(ConfigSnapshot.decode(new ConfigSnapshot(values, "state", 0, 318).encode(), 319));
    }

    @Test public void corruptedPropertyChunksCannotAcknowledgeAMixedSnapshot() throws Exception {
        String rules = "{}";
        ConfigSnapshot snapshot = new ConfigSnapshot(Map.of("ui.hide_discovery_tab", "true",
                NotificationRuleStore.MIRROR_KEY, rules), "published", 1, 329);
        String encoded = snapshot.propertySnapshot().encode();
        assertNotNull(ConfigSnapshot.decodeProperty(encoded, 329, rules));
        org.json.JSONObject oldEncoding = new org.json.JSONObject(encoded);
        oldEncoding.remove("sha256");
        assertNotNull(ConfigSnapshot.decode(oldEncoding.toString(), 329));
        assertNull(ConfigSnapshot.decodeProperty(oldEncoding.toString(), 329, rules));
        assertNull(ConfigSnapshot.decode(encoded.replace("true", "false"), 329));
        assertNull(ConfigSnapshot.decode(encoded.replace("published", "other-id"), 329));
    }

    @Test public void propertySnapshotKeepsFullRuleCapacityAndRejectsMixedPayloads() throws Exception {
        String rules = "{\"keyword_blocklist\":[\"" + "x".repeat(6500) + "\"]}";
        ConfigSnapshot snapshot = new ConfigSnapshot(Map.of(Tweaks.KEY_HIDE_DISCOVERY_TAB, "true",
                NotificationRuleStore.MIRROR_KEY, rules), "large-rules", 2, 329);
        String compact = snapshot.propertySnapshot().encode();
        assertTrue(compact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 7680);
        assertFalse(compact.contains("x".repeat(100)));
        ConfigSnapshot loaded = ConfigSnapshot.decodeProperty(compact, 329, rules);
        assertNotNull(loaded);
        assertEquals(snapshot.settingsOnly().values, loaded.values);
        assertEquals(snapshot.id, loaded.id);
        assertNull(ConfigSnapshot.decodeProperty(compact, 329, "{}"));
        assertNull(ConfigSnapshot.decodeProperty(compact, 329, null));
    }

    @Test public void invalidOrUnavailableSnapshotFailsClosed() throws Exception {
        assertNull(ConfigSnapshot.decode(null, 319));
        assertNull(ConfigSnapshot.decode("garbage", 319));
        assertNull(ConfigSnapshot.decode("{\"format\":2}", 319));
        assertNull(ConfigSnapshot.decode(new ConfigSnapshot(Map.of(), "", 1, 319).encode(), 319));
        assertNull(ConfigSnapshot.decode(new ConfigSnapshot(Map.of(), "id", -1, 319).encode(), 319));
        try {
            new ConfigSnapshot(Map.of("payload", "x".repeat(ConfigSnapshot.MAX_BYTES)), "id", 1, 319).encode();
            fail("oversized snapshot accepted");
        } catch (IllegalArgumentException expected) { }
    }
}
