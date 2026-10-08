package com.ez.zalopatch;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests for the DexKit overlay merge and profile builder. */
public final class DexKitOverlayTest {
    @Test
    public void equallySparseNeighborCannotReplaceAuxiliaryBase() throws Exception {
        JSONObject exact = new JSONObject("{\"inbox\":{\"conversation_field\":\"f\"},"
                + "\"bottom_tabs\":{\"current_methods\":{}},"
                + "\"me\":{\"zstyle_view_exclusive\":false}}");
        JSONObject peer = new JSONObject(exact.toString());
        peer.getJSONObject("inbox").put("conversation_field", "old");
        peer.put("call_recording", new JSONObject()
                .put("callback_class", "").put("callbacks", new JSONArray()));
        assertFalse(DexKitOverlay.hasSupplementalSymbols(exact, peer));

        JSONObject auxiliary = new JSONObject(peer.toString());
        auxiliary.getJSONObject("call_recording").put("callback_class", "callback");
        assertTrue(DexKitOverlay.hasSupplementalSymbols(exact, auxiliary));
        JSONObject composed = DexKitOverlay.compose(exact, auxiliary,
                new HashMap<String, String>(), "", "");
        assertEquals("f", composed.getJSONObject("inbox").getString("conversation_field"));
        assertEquals("callback", composed.getJSONObject("call_recording")
                .getString("callback_class"));
    }

    @Test
    public void supplementalLeavesRespectFalseZeroAndAtomicLists() throws Exception {
        JSONObject exact = new JSONObject("{\"flags\":{\"enabled\":false,\"id\":0,"
                + "\"methods\":[\"exact\"]},\"missing\":{\"name\":\"\",\"ids\":[]}}");
        JSONObject candidate = new JSONObject("{\"flags\":{\"enabled\":true,\"id\":1,"
                + "\"methods\":[\"other\"]}}");
        assertFalse(DexKitOverlay.hasSupplementalSymbols(exact, candidate));
        candidate.put("missing", new JSONObject().put("name", "qualified"));
        assertTrue(DexKitOverlay.hasSupplementalSymbols(exact, candidate));
        candidate.getJSONObject("missing").put("name", JSONObject.NULL)
                .put("ids", new JSONArray().put(0));
        assertTrue(DexKitOverlay.hasSupplementalSymbols(exact, candidate));
    }

    @Test
    public void mergePrefersDexkitOverBaseOverStable() throws Exception {
        JSONObject base = new JSONObject(
                "{\"webview\":{\"companion_class\":\"com.zing.zalo.ui.zviews.old\","
                        + "\"open_dispatch_method\":\"old\"},"
                        + "\"inbox\":{\"message_adapter_class\":\"zf1.e1\"}}");
        Map<String, String> dexkit = new HashMap<>();
        dexkit.put("symbols.webview.open_dispatch_method", "k");
        dexkit.put("symbols.webview.redirect_transform_method", "A8");
        dexkit.put("symbols.webview.companion_class", "");
        JSONObject merged = DexKitOverlay.merge(base, dexkit, null, null);
        assertEquals("k", merged.getJSONObject("webview").getString("open_dispatch_method"));
        assertEquals("A8", merged.getJSONObject("webview").getString("redirect_transform_method"));
        assertEquals("com.zing.zalo.ui.zviews.old",
                merged.getJSONObject("webview").getString("companion_class"));
        assertEquals("zf1.e1",
                merged.getJSONObject("inbox").getString("message_adapter_class"));
        assertEquals("com.zing.zalo.ui.zviews.ZaloWebView",
                merged.getJSONObject("webview").getString("zalo_web_view_class"));
    }

    @Test
    public void unverifiedLeavesStayAbsentWithoutBase() throws Exception {
        // No-default-without-predicate: lists and ids are never injected. They stay
        // absent unless the neighbouring base or DexKit resolution supplies them.
        JSONObject merged = DexKitOverlay.merge(null, new HashMap<String, String>(), null, null);
        assertTrue(!merged.has("zinstant") || !merged.optJSONObject("zinstant").has("network_methods"));
        assertTrue(!merged.has("me") || !merged.optJSONObject("me").has("qr_wallet_item_id"));
        assertTrue(!merged.has("chat") || !merged.optJSONObject("chat").has("reaction_marker_texts"));
        JSONObject base = new JSONObject(
                "{\"me\":{\"qr_wallet_item_id\":6},"
                        + "\"zinstant\":{\"network_methods\":[\"get\"]}}");
        JSONObject withBase = DexKitOverlay.merge(base, new HashMap<String, String>(), null, null);
        assertEquals(6, withBase.getJSONObject("me").getInt("qr_wallet_item_id"));
        assertEquals("get", withBase.getJSONObject("zinstant")
                .getJSONArray("network_methods").getString(0));
    }

    @Test
    public void buildProfileCarriesArtifactIdentity() throws Exception {
        JSONObject symbols = DexKitOverlay.merge(null, new HashMap<String, String>(), null, null);
        String json = DexKitOverlay.buildProfile(260901903L,
                "eb41b236b129f8bac71b1df4ebe91c0b3e795376426468fb39b8bcfd3f0264c8",
                "d86efe151e09bf4ca8440cb3bfa0a81be2544f70c78587daf0266dfca2fa25df",
                symbols, "static-verified");
        JSONObject profile = new JSONObject(json);
        assertEquals(1, profile.getInt("schema_version"));
        assertEquals("com.zing.zalo", profile.getString("zalo_package"));
        assertEquals(260901903, profile.getJSONObject("zalo_version").getInt("min_code"));
        assertEquals(260901903, profile.getJSONObject("zalo_version").getInt("max_code"));
        assertEquals("eb41b236b129f8bac71b1df4ebe91c0b3e795376426468fb39b8bcfd3f0264c8",
                profile.getJSONObject("artifact").getString("base_apk_sha256"));
        assertEquals("base", profile.getJSONObject("artifact").getString("hook_code_apk"));
        assertEquals("static-verified",
                profile.getJSONObject("artifact").getString("verification"));
    }

    @Test
    public void bottomTabsSynthesisReplacesStaleTree() throws Exception {
        JSONObject base = new JSONObject(
                "{\"custom_main_tab_class\":\"com.zing.zalo.ui.maintab.widget.CustomMainTab\","
                        + "\"current_methods\":{\"rebuild\":\"p\"},"
                        + "\"current_tab_symbols\":[{\"state_class\":\"tf1.w\"}]}");
        JSONObject merged = DexKitOverlay.merge(base, tabLeaves(), null, null);
        JSONObject bottom = merged.getJSONObject("bottom_tabs");
        assertEquals("com.zing.zalo.ui.maintab.widget.CustomMainTab",
                bottom.getString("custom_main_tab_class"));
        assertEquals("p", bottom.getJSONObject("current_methods").getString("rebuild"));
        assertEquals("q", bottom.getJSONObject("current_methods").getString("refresh"));
        assertEquals("k", bottom.getJSONObject("current_methods").getString("message_index"));
        assertEquals(1, bottom.getJSONArray("current_tab_symbols").length());
        JSONObject definition = bottom.getJSONArray("current_tab_symbols").getJSONObject(0);
        assertEquals("oh1.w", definition.getString("state_class"));
        assertEquals("oh1.u", definition.getString("enum_class"));
        assertEquals("GROUP", definition.getString("group_tab_field"));
        assertEquals("b", definition.getJSONObject("index_fields").getString("message"));
        assertEquals("j", definition.getJSONObject("enabled_fields").getString("group"));
        assertEquals("p", definition.getString("icons_field"));
        assertFalse(merged.has("symbols"));
    }

    @Test
    public void bottomTabsSynthesisRequiresCompleteSet() throws Exception {
        Map<String, String> partial = tabLeaves();
        partial.remove("symbols.bottom_tabs.size_method");
        JSONObject merged = DexKitOverlay.merge(new JSONObject(), partial, null, null);
        assertTrue(!merged.has("bottom_tabs")
                || !merged.optJSONObject("bottom_tabs").has("current_tab_symbols"));
    }

    @Test
    public void compactBottomTabsReplaceStaleMethodsWithoutInventingRemovedGetters() throws Exception {
        Map<String, String> leaves = tabLeaves();
        leaves.put("symbols.bottom_tabs.tabs_field", "a");
        for (String role : new String[]{"refresh", "group_flag", "phonebook_index",
                "more_index", "me_index", "size"}) leaves.remove("symbols.bottom_tabs." + role + "_method");
        JSONObject merged = new JSONObject();
        merged.put("bottom_tabs", new JSONObject().put("current_methods", new JSONObject().put("refresh", "stale")));
        DexKitOverlay.synthesizeBottomTabs(merged, leaves);
        JSONObject bottom = merged.getJSONObject("bottom_tabs");
        assertTrue(!bottom.getJSONObject("current_methods").has("refresh"));
        assertTrue(!bottom.getJSONObject("current_methods").has("size"));
        JSONObject definition = bottom.getJSONArray("current_tab_symbols").getJSONObject(0);
        assertEquals("a", definition.getString("tabs_field"));
        assertTrue(definition.getBoolean("preserve_icon_arrays"));
    }

    @Test
    public void bottomTabsSynthesisEmitsCurrentStateClasses() throws Exception {
        JSONObject merged = DexKitOverlay.merge(new JSONObject(), tabLeaves(), null, null);
        JSONArray states = merged.getJSONObject("bottom_tabs")
                .getJSONArray("current_state_classes");
        assertEquals(1, states.length());
        assertEquals("oh1.w", states.getString(0));
    }

    @Test
    public void injectZinstantBindsAddsValidatedNames() throws Exception {
        JSONObject merged = DexKitOverlay.merge(null, new HashMap<String, String>(), null, null);
        DexKitOverlay.injectZinstantBinds(merged, "c", "c");
        assertEquals("c", merged.getJSONObject("zinstant").getString("ad_bind_method"));
        assertEquals("c", merged.getJSONObject("zinstant").getString("feed_bind_method"));
    }

    @Test
    public void injectZinstantBindsSkipsEmptyNames() throws Exception {
        JSONObject merged = DexKitOverlay.merge(null, new HashMap<String, String>(), null, null);
        DexKitOverlay.injectZinstantBinds(merged, "", "");
        assertTrue(!merged.optJSONObject("zinstant").has("ad_bind_method"));
        assertTrue(!merged.optJSONObject("zinstant").has("feed_bind_method"));
    }

    @Test
    public void neighbourBaseSuppliesInboxMeHardLeaves() throws Exception {
        // Catalog-28 hard leaves that checkInboxRows / checkMe present() require.
        // Pure overlay without base must leave them absent (no default without a
        // verified predicate); neighbour base + merge must supply every path.
        JSONObject base = new JSONObject(
                "{\"me\":{\"setting_id_field\":\"c\",\"setting_title_field\":\"e\","
                        + "\"setting_summary_field\":\"f\"},"
                        + "\"inbox\":{\"messages_view_adapter_field\":\"H2\","
                        + "\"top_out_field\":\"g\",\"top_out_value_field\":\"a\","
                        + "\"biz_box_item_class\":\"r00.a\"}}");
        JSONObject withBase = DexKitOverlay.merge(base, new HashMap<String, String>(), null, null);
        assertEquals("c", withBase.getJSONObject("me").getString("setting_id_field"));
        assertEquals("e", withBase.getJSONObject("me").getString("setting_title_field"));
        assertEquals("f", withBase.getJSONObject("me").getString("setting_summary_field"));
        assertEquals("H2",
                withBase.getJSONObject("inbox").getString("messages_view_adapter_field"));
        assertEquals("g", withBase.getJSONObject("inbox").getString("top_out_field"));
        assertEquals("a", withBase.getJSONObject("inbox").getString("top_out_value_field"));
        assertEquals("r00.a", withBase.getJSONObject("inbox").getString("biz_box_item_class"));

        JSONObject pure = DexKitOverlay.merge(null, new HashMap<String, String>(), null, null);
        assertTrue(!pure.has("me") || !pure.optJSONObject("me").has("setting_id_field"));
        assertTrue(!pure.has("inbox")
                || !pure.optJSONObject("inbox").has("messages_view_adapter_field"));
    }

    @Test
    public void partialExactDoesNotSuppressOtherDiscoveredFamilies() throws Exception {
        JSONObject exact = new JSONObject("{\"inbox\":{\"uid_field\":\"b\"},"
                + "\"webview\":{\"open_dispatch_method\":\"\"}}");
        JSONObject neighbor = new JSONObject("{\"inbox\":{\"uid_field\":\"old\"},"
                + "\"webview\":{\"open_dispatch_method\":\"old\"}}");
        Map<String, String> discovered = new HashMap<>();
        discovered.put("symbols.inbox.uid_field", "new");
        discovered.put("symbols.webview.open_dispatch_method", "dispatch");
        discovered.put("symbols.webview.companion_class", "companion");
        JSONObject composed = DexKitOverlay.compose(exact, neighbor, discovered, "", "");
        assertEquals("b", composed.getJSONObject("inbox").getString("uid_field"));
        assertEquals("dispatch", composed.getJSONObject("webview")
                .getString("open_dispatch_method"));
        assertEquals("companion", composed.getJSONObject("webview")
                .getString("companion_class"));
        assertEquals("old", neighbor.getJSONObject("webview").getString("open_dispatch_method"));
        assertEquals("", exact.getJSONObject("webview").getString("open_dispatch_method"));
    }

    @Test
    public void partialExactRetainsNeighborAuxiliaryLeavesWithoutReplacingExact() throws Exception {
        JSONObject exact = new JSONObject("{\"inbox\":{\"uid_field\":\"b\"}}");
        JSONObject neighbor = new JSONObject("{\"inbox\":{\"uid_field\":\"old\","
                + "\"messages_view_adapter_field\":\"H2\"},"
                + "\"chat\":{\"reaction_marker_texts\":[\"reaction\"]},"
                + "\"bottom_tabs\":{\"viewpager_field\":\"pager\"}}");
        Map<String, String> discovered = new HashMap<>();
        discovered.put("symbols.inbox.uid_field", "new");
        discovered.put("symbols.webview.open_dispatch_method", "dispatch");
        JSONObject composed = DexKitOverlay.compose(exact, neighbor, discovered, "", "");
        assertEquals("b", composed.getJSONObject("inbox").getString("uid_field"));
        assertEquals("H2", composed.getJSONObject("inbox")
                .getString("messages_view_adapter_field"));
        assertEquals("reaction", composed.getJSONObject("chat")
                .getJSONArray("reaction_marker_texts").getString(0));
        assertEquals("pager", composed.getJSONObject("bottom_tabs").getString("viewpager_field"));
        assertEquals("dispatch", composed.getJSONObject("webview").getString("open_dispatch_method"));
        assertEquals("old", neighbor.getJSONObject("inbox").getString("uid_field"));
    }

    @Test
    public void exactZinstantBindWinsOverDedicatedCacheFields() throws Exception {
        JSONObject exact = new JSONObject("{\"zinstant\":{\"ad_bind_method\":\"exact\"}}");
        JSONObject composed = DexKitOverlay.compose(exact, null,
                new HashMap<String, String>(), "discovered", "feed");
        assertEquals("exact", composed.getJSONObject("zinstant").getString("ad_bind_method"));
        assertEquals("feed", composed.getJSONObject("zinstant").getString("feed_bind_method"));
    }

    @Test
    public void exactCandidateArrayIsPreservedAsAWhole() throws Exception {
        JSONObject exact = new JSONObject("{\"bottom_tabs\":{\"current_tab_symbols\":["
                + "{\"state_class\":\"exact.State\",\"enum_class\":\"exact.Enum\"}]}}");
        JSONObject composed = DexKitOverlay.compose(exact, null, tabLeaves(), "", "");
        JSONArray definitions = composed.getJSONObject("bottom_tabs")
                .getJSONArray("current_tab_symbols");
        assertEquals(1, definitions.length());
        assertEquals("exact.State", definitions.getJSONObject(0).getString("state_class"));
        assertEquals("exact.Enum", definitions.getJSONObject(0).getString("enum_class"));
    }

    @Test
    public void nullAndEmptyExactLeavesDoNotEraseValidatedDiscovery() throws Exception {
        JSONObject exact = new JSONObject("{\"webview\":{\"companion_class\":null},"
                + "\"bottom_tabs\":{\"current_state_classes\":[]}}");
        Map<String, String> discovered = tabLeaves();
        discovered.put("symbols.webview.companion_class", "companion");
        JSONObject composed = DexKitOverlay.compose(exact, null, discovered, "", "");
        assertEquals("companion", composed.getJSONObject("webview").getString("companion_class"));
        assertEquals("oh1.w", composed.getJSONObject("bottom_tabs")
                .getJSONArray("current_state_classes").getString(0));
    }

    private Map<String, String> tabLeaves() {
        Map<String, String> leaves = new HashMap<>();
        leaves.put("symbols.bottom_tabs.state_class", "oh1.w");
        leaves.put("symbols.bottom_tabs.enum_class", "oh1.u");
        leaves.put("symbols.bottom_tabs.singleton_method", "h");
        leaves.put("symbols.bottom_tabs.icon_resolver_method", "g");
        leaves.put("symbols.bottom_tabs.rebuild_method", "p");
        leaves.put("symbols.bottom_tabs.refresh_method", "q");
        leaves.put("symbols.bottom_tabs.hide_discovery_method", "a");
        leaves.put("symbols.bottom_tabs.group_flag_method", "b");
        String[] roles = {"message", "phonebook", "group", "discovery", "timeline",
                "more", "me"};
        String[] methods = {"k", "l", "f", "e", "n", "j", "i"};
        String[] fields = {"b", "c", "d", "e", "f", "g", "h"};
        for (int index = 0; index < roles.length; index++) {
            leaves.put("symbols.bottom_tabs." + roles[index] + "_index_method",
                    methods[index]);
            leaves.put("symbols.bottom_tabs." + roles[index] + "_index_field",
                    fields[index]);
        }
        leaves.put("symbols.bottom_tabs.size_method", "m");
        leaves.put("symbols.bottom_tabs.size_index_field", "i");
        String[] enabled = {"group", "timeline", "discovery", "more", "me"};
        String[] enabledFields = {"j", "k", "l", "m", "n"};
        for (int index = 0; index < enabled.length; index++) {
            leaves.put("symbols.bottom_tabs." + enabled[index] + "_enabled_field",
                    enabledFields[index]);
        }
        leaves.put("symbols.bottom_tabs.icons_field", "p");
        leaves.put("symbols.bottom_tabs.preloaded_field", "o");
        return leaves;
    }

    @Test
    public void countResolvedCountsAppliedDescriptors() throws Exception {        Map<String, String> dexkit = new HashMap<>();
        dexkit.put("symbols.webview.open_dispatch_method", "k");
        dexkit.put("symbols.webview.redirect_transform_method", "");
        JSONObject merged = DexKitOverlay.merge(null, dexkit, null, null);
        assertEquals(1, DexKitOverlay.countResolved(merged, dexkit));
    }
}
