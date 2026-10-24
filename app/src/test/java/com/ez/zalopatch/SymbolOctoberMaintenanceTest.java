package com.ez.zalopatch;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SymbolOctoberMaintenanceTest {
    /** Slots that were already unavailable in the 261002901 baseline and stay absent. */
    private static final String[] OPTIONAL_SLOTS = {
            "inbox.friend_manager_class",
            "inbox.friend_manager_follow_methods",
            "inbox.friend_manager_instance_method",
            "inbox.group_flag_method",
            "inbox.profile_field",
            "inbox.profile_name_fields",
            "me.zinstant_item_class",
            "me.zstyle_item_classes",
            "me.zstyle_view_class"};

    @Test
    public void partialOctoberPeersKeepAuxiliaryCallbackCoverage() throws Exception {
        JSONObject older = symbols(261001903L);
        JSONObject maintenance = symbols(261001905L);
        JSONObject auxiliary = symbols(260802903L);
        older.remove("call_recording");
        maintenance.remove("call_recording");
        assertFalse(DexKitOverlay.hasSupplementalSymbols(older, maintenance));
        assertFalse(DexKitOverlay.hasSupplementalSymbols(maintenance, older));
        for (JSONObject exact : new JSONObject[]{older, maintenance}) {
            assertTrue(DexKitOverlay.hasSupplementalSymbols(exact, auxiliary));
            JSONObject composed = DexKitOverlay.compose(exact, auxiliary,
                    new HashMap<String, String>(), "", "");
            assertEquals(auxiliary.getJSONObject("call_recording").getString("callback_class"),
                    composed.getJSONObject("call_recording").getString("callback_class"));
            assertEquals(exact.getJSONObject("inbox").getString("conversation_field"),
                    composed.getJSONObject("inbox").getString("conversation_field"));
        }
    }

    @Test
    public void maintenanceProfileKeepsReviewedRoutesWithoutAnotherVersionFallback() throws Exception {
        String bundle = BundledSymbolSchemaJson.json();
        SymbolSchema.Active active = SymbolSchema.select(bundle, "Test", 261001905L);
        assertTrue(active.validation, active.valid);
        assertEquals(32, active.schemaRevision);
        assertEquals(261001905L, active.minCode);
        assertEquals(261001905L, active.maxCode);
        assertEquals("2fe8d3f2bbd3fc923bcf62e4b519e50661176ed79a51aac01be8b39c8ef3994c",
                active.root.getJSONObject("artifact").getString("base_apk_sha256"));
        JSONObject symbols = active.root.getJSONObject("symbols");
        assertEquals("zh1.b0", symbols.getJSONObject("bottom_tabs")
                .getJSONArray("current_state_classes").getString(0));
        assertEquals("h", symbols.getJSONObject("bottom_tabs")
                .getJSONObject("current_methods").getString("rebuild"));
        JSONObject tab = symbols.getJSONObject("bottom_tabs")
                .getJSONArray("current_tab_symbols").getJSONObject(0);
        assertEquals("zh1.z", tab.getString("enum_class"));
        assertTrue(tab.getBoolean("preserve_icon_arrays"));
        assertEquals("fi1.w1", symbols.getJSONObject("inbox").getString("message_adapter_class"));
        assertEquals("ac2.p2", symbols.getJSONObject("call_recording").getString("callback_class"));
        assertFalse(symbols.getJSONObject("call_recording").has("activity_ready_method"));
        assertFalse(symbols.getJSONObject("me").getBoolean("zstyle_view_exclusive"));
        assertFalse(SymbolSchema.select(bundle, "Test", 261001904L).valid);
        assertFalse(SymbolSchema.select(bundle, "Test", 261001906L).valid);
    }
    @Test
    public void nativeMeLayoutBindingIsRestrictedToReviewedOctoberArtifacts() throws Exception {
        for (long version : new long[]{261001903L, 261001905L}) {
            assertEquals("O6", symbols(version).getJSONObject("me")
                    .getString("full_zinstant_predicate_method"));
        }
        assertFalse(symbols(260802903L).getJSONObject("me")
                .has("full_zinstant_predicate_method"));
    }

    /**
     * Golden anchor for Zalo 26.10.02 build 261002903.
     *
     * <p>The expected values are the accepted per-entry static evidence for that exact artifact
     * (Level 4), not a runtime observation. The test keeps the profile selectable for that one
     * versionCode, keeps the reviewed recording, inbox and bottom-tab routes, keeps the explicit
     * false zStyle guard, and leaves the nine baseline-unavailable slots absent. Neighbouring
     * version codes stay unresolved, and build 261001905 keeps its own independent profile.
     */
    @Test
    public void exactTwentySixTenZeroTwoProfileKeepsReviewedAnchors() throws Exception {
        String bundle = BundledSymbolSchemaJson.json();
        SymbolSchema.Active active = SymbolSchema.select(bundle, "Test", 261002903L);
        assertTrue(active.validation, active.valid);
        assertEquals(34, active.schemaRevision);
        assertEquals(261002903L, active.minCode);
        assertEquals(261002903L, active.maxCode);

        JSONObject artifact = active.root.getJSONObject("artifact");
        assertEquals("20f9f4eb76393af3b2a73d6d483ad93b6f69703d645f362c79ddafe592cfacff",
                artifact.getString("base_apk_sha256"));
        assertEquals("d86efe151e09bf4ca8440cb3bfa0a81be2544f70c78587daf0266dfca2fa25df",
                artifact.getString("signer_sha256"));
        assertEquals("base", artifact.getString("hook_code_apk"));
        assertEquals("static-verified", artifact.getString("verification"));

        JSONObject symbols = active.root.getJSONObject("symbols");
        JSONObject recording = symbols.getJSONObject("call_recording");
        assertEquals("md2.r1", recording.getString("callback_class"));
        assertEquals("kj.a", recording.getString("peer_manager_class"));
        assertEquals("a", recording.getString("peer_manager_instance_method"));
        assertEquals("a", recording.getString("peer_container_field"));
        assertEquals("e", recording.getString("peer_handle_field"));
        assertEquals("qj1.g1", symbols.getJSONObject("inbox").getString("message_adapter_class"));

        JSONObject bottomTabs = symbols.getJSONObject("bottom_tabs");
        assertEquals("kj1.v", bottomTabs.getJSONArray("current_state_classes").getString(0));
        assertEquals("p", bottomTabs.getJSONObject("current_methods").getString("rebuild"));
        JSONObject tab = bottomTabs.getJSONArray("current_tab_symbols").getJSONObject(0);
        assertEquals("kj1.v", tab.getString("state_class"));
        assertEquals("kj1.t", tab.getString("enum_class"));
        assertEquals("kj1.e0", bottomTabs.getJSONArray("consumer_adapter_classes").getString(0));

        assertFalse(symbols.getJSONObject("me").getBoolean("zstyle_view_exclusive"));

        JSONObject baseline = symbols(261002901L);
        for (String path : OPTIONAL_SLOTS) {
            assertFalse("optional slot must stay absent: " + path, hasSymbolPath(symbols, path));
            assertTrue("baseline slot was already unavailable: " + path,
                    hasSymbolPath(baseline, path));
        }

        assertFalse(SymbolSchema.select(bundle, "Test", 261002902L).valid);
        assertFalse(SymbolSchema.select(bundle, "Test", 261002904L).valid);
        SymbolSchema.Active previous = SymbolSchema.select(bundle, "Test", 261001905L);
        assertTrue(previous.valid);
        assertEquals(32, previous.schemaRevision);
    }

    private static boolean hasSymbolPath(JSONObject symbols, String path) {
        String[] parts = path.split("\\.");
        JSONObject node = symbols;
        for (int index = 0; index < parts.length - 1; index++) {
            node = node.optJSONObject(parts[index]);
            if (node == null) {
                return false;
            }
        }
        return node.has(parts[parts.length - 1]);
    }

    private static JSONObject symbols(long versionCode) throws Exception {
        org.json.JSONArray profiles = new JSONObject(BundledSymbolSchemaJson.json())
                .getJSONArray("profiles");
        for (int index = 0; index < profiles.length(); index++) {
            JSONObject profile = profiles.getJSONObject(index);
            if (profile.getJSONObject("zalo_version").getLong("min_code") == versionCode) {
                return profile.getJSONObject("symbols");
            }
        }
        throw new AssertionError("Missing profile " + versionCode);
    }
}
