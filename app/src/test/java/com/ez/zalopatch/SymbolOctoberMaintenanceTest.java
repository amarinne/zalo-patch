package com.ez.zalopatch;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SymbolOctoberMaintenanceTest {
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
        assertEquals(31, active.schemaRevision);
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
