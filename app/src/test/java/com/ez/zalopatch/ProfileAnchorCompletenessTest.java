package com.ez.zalopatch;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * D5: an exact bundled profile must not silently drop a DexKit anchor.
 *
 * <p>The profile generator drops every value that fails static comparison, and a dropped
 * anchor only shows up as a stale row after a device install. This test binds the bundled
 * profile to the declared anchor vocabulary so a generation regression fails in the JVM
 * suite instead.
 *
 * <p>Absence is tolerated only for anchors whose behaviour has a documented symbol-free or
 * reader-only fallback; everything else must be present.
 */
public final class ProfileAnchorCompletenessTest {
    /** Anchors whose absence a feature already survives, each with the fallback. */
    private static final Map<String, String> OPTIONAL = new LinkedHashMap<>();

    static {
        OPTIONAL.put("symbols.passcode.active_time_setter_class",
                "reader-only arming plus the hook key guard carry the write path");
        OPTIONAL.put("symbols.passcode.active_time_setter_method",
                "reader-only arming plus the hook key guard carry the write path");
        OPTIONAL.put("symbols.inbox.media_box_item_class",
                "the item_channel_media_box layout route needs no symbol");
        OPTIONAL.put("symbols.original_photo.picker_camera_method",
                "retired from the bindings; the picker reads native saved state");
    }

    @Test
    public void newestExactProfileCarriesEveryRequiredAnchor() throws Exception {
        String bundle = BundledSymbolSchemaJson.json();
        JSONObject root = root();
        SymbolSchema.Active active = SymbolSchema.select(bundle, "Test", newestCode(root));
        assertTrue(active.validation, active.valid);
        JSONObject symbols = active.root.getJSONObject("symbols");
        List<String> missing = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        for (DexKitAnchors.Anchor anchor : DexKitAnchors.all()) {
            if (OPTIONAL.containsKey(anchor.path)) {
                continue;
            }
            Object value = at(symbols, anchor.path);
            if (value == null) {
                missing.add(anchor.path);
            } else if (value instanceof String && ((String) value).isEmpty()) {
                empty.add(anchor.path);
            } else if (value instanceof JSONArray && ((JSONArray) value).length() == 0) {
                empty.add(anchor.path);
            }
        }
        assertEquals("anchors dropped from the exact profile: " + missing,
                0, missing.size());
        assertEquals("anchors left empty in the exact profile: " + empty,
                0, empty.size());
    }

    @Test
    public void optionalAnchorsStayDeclaredAndJustified() {
        List<String> declared = DexKitAnchors.paths();
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, String> entry : OPTIONAL.entrySet()) {
            if (!declared.contains(entry.getKey())) {
                unknown.add(entry.getKey());
            }
            assertTrue("optional anchor without a documented fallback: " + entry.getKey(),
                    entry.getValue() != null && !entry.getValue().isEmpty());
        }
        assertTrue("optional anchors no longer declared: " + unknown, unknown.isEmpty());
    }

    @Test
    public void profileIsTheNewestSupportedVersion() throws Exception {
        JSONObject root = root();
        JSONArray profiles = root.getJSONArray("profiles");
        assertEquals(newestCode(root), profiles.getJSONObject(profiles.length() - 1)
                .getJSONObject("zalo_version").getLong("max_code"));
    }

    private static JSONObject root() throws Exception {
        return new JSONObject(BundledSymbolSchemaJson.json());
    }

    private static long newestCode(JSONObject root) throws Exception {
        JSONArray profiles = root.getJSONArray("profiles");
        long newest = 0;
        for (int index = 0; index < profiles.length(); index++) {
            long max = profiles.getJSONObject(index)
                    .getJSONObject("zalo_version").getLong("max_code");
            newest = Math.max(newest, max);
        }
        return newest;
    }

    /** Dotted schema path lookup; returns null when any segment is absent. */
    private static Object at(JSONObject symbols, String path) {
        String[] parts = path.split("\\.");
        Object current = symbols;
        for (int index = 1; index < parts.length; index++) {
            if (!(current instanceof JSONObject)) {
                return null;
            }
            JSONObject object = (JSONObject) current;
            if (!object.has(parts[index])) {
                return null;
            }
            current = object.opt(parts[index]);
        }
        return current;
    }
}
