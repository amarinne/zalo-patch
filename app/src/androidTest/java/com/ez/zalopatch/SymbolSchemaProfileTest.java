package com.ez.zalopatch;

import android.content.Context;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.test.AndroidTestCase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class SymbolSchemaProfileTest extends AndroidTestCase {
    private String bundleJson;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Context context = getContext();
        try (InputStream input = context.getAssets().open(SymbolSchema.ASSET_NAME)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            bundleJson = output.toString(StandardCharsets.UTF_8.name());
        }
    }

    public void testSelectsOldExactProfile() throws Exception {
        SymbolSchema.Active active = SymbolSchema.select(bundleJson, "Test", 260602901L);

        assertTrue(active.valid);
        assertEquals(12, active.schemaRevision);
        assertEquals(260602901, active.minCode);
        assertEquals("je1.c1", active.string("symbols.inbox.message_adapter_class", ""));
        assertEquals("a55d59581d4e4d038a28bc17a3d12237f519566ecfb7140eb445a6fa045eef34",
                active.string("artifact.base_apk_sha256", ""));
        assertEquals("device-verified", active.string("artifact.verification", ""));
        assertHookIdentities(active);
    }

    public void testSelectsCurrentExactProfile() throws Exception {
        SymbolSchema.Active active = SymbolSchema.select(bundleJson, "Test", 260701901L);

        assertTrue(active.valid);
        assertEquals(13, active.schemaRevision);
        assertEquals(260701901, active.minCode);
        assertEquals("se1.g1", active.string("symbols.inbox.message_adapter_class", ""));
        assertEquals("z", active.string("symbols.telemetry.analytics_event_accessor", ""));
        assertEquals("A", active.string("symbols.telemetry.analytics_screen_accessor", ""));
        assertEquals("B", active.string("symbols.telemetry.analytics_session_accessor", ""));
        assertEquals("C", active.string("symbols.telemetry.analytics_view_accessor", ""));
        assertEquals("c", active.string("symbols.zinstant.ad_bind_method", ""));
        assertEquals("c", active.string("symbols.zinstant.feed_bind_method", ""));
        assertEquals("5be67ca6d6becfc48e9b6d7c8410bd12b7c125d235b5112616831f342e5b1e5e",
                active.string("artifact.base_apk_sha256", ""));
        assertEquals("device-smoke-tested", active.string("artifact.verification", ""));

        assertHookIdentities(active);
        assertTrue(TweakHookInfo.forKey(Tweaks.KEY_DISABLE_EVENT_ANALYTICS, active)
                .path.contains("#z()"));
        assertTrue(TweakHookInfo.forKey(Tweaks.KEY_DISABLE_EVENT_ANALYTICS, active).driftProne);
        assertFalse(TweakHookInfo.forKey(Tweaks.KEY_DISABLE_CRASHLYTICS, active).driftProne);
    }

    public void testSelectsAugustExactProfile() throws Exception {
        SymbolSchema.Active active = SymbolSchema.select(bundleJson, "Test", 260801903L);

        assertTrue(active.valid);
        assertEquals(14, active.schemaRevision);
        assertEquals(260801903, active.minCode);
        assertEquals("of1.h1", active.string("symbols.inbox.message_adapter_class", ""));
        assertEquals("q00.c", active.string("symbols.inbox.normal_item_class", ""));
        assertEquals("if1.y", active.strings(
                "symbols.bottom_tabs.current_state_classes").get(0));
        assertEquals("lf1.k", active.string("symbols.me.adapter_class", ""));
        assertEquals("z", active.string("symbols.telemetry.analytics_event_accessor", ""));
        assertEquals("afd9aa96e7f4beb772ad1632d17f5fe4a6bd12c1e3ce5978a6b2ec43ac9d2a57",
                active.string("artifact.base_apk_sha256", ""));
        assertEquals("static-verified", active.string("artifact.verification", ""));
        assertHookIdentities(active);
    }

    public void testUnknownVersionDoesNotUseNearestProfile() throws Exception {
        SymbolSchema.Active active = SymbolSchema.select(bundleJson, "Test", 260800000L);

        assertFalse(active.valid);
        assertTrue(active.bundleValid);
        assertTrue(active.validation.contains("No exact bundled symbol profile"));
        assertEquals("260602901, 260701901, 260801903", active.supportedVersionCodes);
    }

    public void testRemoteProfileSelectedWhenBundledExactProfileMissing() throws Exception {
        JSONObject bundle = new JSONObject(bundleJson);
        JSONArray profiles = bundle.getJSONArray("profiles");
        JSONObject remoteProfile = null;
        JSONArray remaining = new JSONArray();
        for (int index = 0; index < profiles.length(); index++) {
            JSONObject profile = profiles.getJSONObject(index);
            long versionCode = profile.getJSONObject("zalo_version").getLong("min_code");
            if (versionCode == 260801903L) {
                remoteProfile = profile;
            } else {
                remaining.put(profile);
            }
        }
        assertNotNull(remoteProfile);
        bundle.put("profiles", remaining);

        SymbolSchema.Active bundled = SymbolSchema.select(
                bundle.toString(), "Bundled test", 260801903L);
        assertFalse(bundled.valid);

        SymbolCatalogContract.Entry entry = new SymbolCatalogContract.Entry(
                14, "test-digest", remoteProfile.toString(), new byte[0]);
        SymbolSchema.Active remote = SymbolSchema.selectRemoteEntry(entry, 260801903L);
        assertNotNull(remote);
        assertTrue(remote.valid);
        assertEquals("Remote catalog 14", remote.source);
        assertEquals(14, remote.schemaRevision);
        assertEquals("of1.h1", remote.string("symbols.inbox.message_adapter_class", ""));

        SymbolSchema.Active selected = bundled.valid ? bundled : remote;
        assertEquals("Remote catalog 14", selected.source);
    }

    public void testProfileWithoutArtifactMetadataIsInvalid() throws Exception {
        JSONObject bundle = new JSONObject(bundleJson);
        bundle.getJSONArray("profiles").getJSONObject(1).remove("artifact");

        SymbolSchema.Active active = SymbolSchema.select(
                bundle.toString(), "Test", 260701901L);

        assertFalse(active.valid);
        assertTrue(active.validation.contains("artifact metadata missing"));
    }

    public void testProfileWithMalformedArtifactHashIsInvalid() throws Exception {
        JSONObject bundle = new JSONObject(bundleJson);
        bundle.getJSONArray("profiles").getJSONObject(1)
                .getJSONObject("artifact").put("base_apk_sha256", "wrong");

        SymbolSchema.Active active = SymbolSchema.select(
                bundle.toString(), "Test", 260701901L);

        assertFalse(active.valid);
        assertTrue(active.validation.contains("base APK SHA-256 invalid"));
    }

    public void testEveryBundledProfileHasDisplayableHookIdentities() throws Exception {
        JSONArray profiles = new JSONObject(bundleJson).getJSONArray("profiles");
        for (int index = 0; index < profiles.length(); index++) {
            JSONObject version = profiles.getJSONObject(index).getJSONObject("zalo_version");
            int versionCode = version.getInt("min_code");
            SymbolSchema.Active active = SymbolSchema.select(bundleJson, "Test", versionCode);
            assertTrue("Invalid bundled profile for " + versionCode, active.valid);
            assertHookIdentities(active);
        }
    }

    public void testCatalogRetainsEveryExactVersionAndSymbolCoverage() {
        java.util.List<SymbolSchema.ProfileInfo> catalog = SymbolSchema.catalog(getContext());

        assertEquals(3, catalog.size());
        assertEquals(260801903L, catalog.get(0).versionCode);
        assertEquals(260701901L, catalog.get(1).versionCode);
        assertEquals(260602901L, catalog.get(2).versionCode);
        assertTrue(catalog.get(0).symbolPaths.contains(
                "symbols.inbox.message_adapter_class = of1.h1"));
        assertTrue(catalog.get(1).symbolPaths.contains(
                "symbols.inbox.message_adapter_class = se1.g1"));
        assertTrue(catalog.get(2).symbolPaths.contains(
                "symbols.inbox.message_adapter_class = je1.c1"));
    }

    public void testInstalledZaloArtifactMatchesExactProfile() {
        ZaloArtifactState.Result result = ZaloArtifactState.reconcile(getContext());
        SymbolSchema.Active active = SymbolSchema.active(getContext());

        assertEquals(result.error, active.valid ? "ready" : "unsupported", result.status);
        assertFalse(result.lightweightKey.isEmpty());
        assertFalse(result.generation.isEmpty());
    }

    public void testSelfCheckRejectsMissingOrWrongArtifactEvidence() {
        Context context = getContext();
        ZaloArtifactState.Result artifact = ZaloArtifactState.reconcile(context);
        SymbolSchema.Active active = SymbolSchema.active(context);
        assertEquals(artifact.error, active.valid ? "ready" : "unsupported", artifact.status);
        String feature = "test.artifact_epoch";
        String prefix = "selfcheck." + feature + ".";
        SharedPreferences preferences = TweakStore.preferences(context);
        String previousGeneration = preferences.getString("internal.selfcheck_generation", null);
        String previousRunId = preferences.getString("internal.selfcheck_run_id", null);
        removePrefix(preferences, prefix);
        try {
            ContentValues missing = selfCheckValues();
            assertEquals(-2, updateSelfCheck(context, feature, missing));
            assertFalse(preferences.contains(prefix + "status"));

            ContentValues wrong = selfCheckValues();
            addEvidence(wrong, context);
            wrong.put("profile_sha256", "wrong");
            assertEquals(-2, updateSelfCheck(context, feature, wrong));
            assertFalse(preferences.contains(prefix + "status"));

            ContentValues wrongGeneration = selfCheckValues();
            addEvidence(wrongGeneration, context);
            wrongGeneration.put("artifact_generation", "wrong");
            assertEquals(-2, updateSelfCheck(context, feature, wrongGeneration));
            assertFalse(preferences.contains(prefix + "status"));

            ContentValues wrongLightweight = selfCheckValues();
            addEvidence(wrongLightweight, context);
            wrongLightweight.put("artifact_lightweight", "wrong");
            assertEquals(-2, updateSelfCheck(context, feature, wrongLightweight));
            assertFalse(preferences.contains(prefix + "status"));

            ContentValues missingRun = selfCheckValues();
            addEvidence(missingRun, context);
            assertEquals(-3, updateSelfCheck(context, feature, missingRun));
            assertFalse(preferences.contains(prefix + "status"));

            ContentValues valid = selfCheckValues();
            addEvidence(valid, context);
            valid.put("run_id", java.util.UUID.randomUUID().toString());
            assertEquals(1, updateSelfCheck(context, feature, valid));
            assertEquals("installed_no_hits", preferences.getString(prefix + "status", ""));

        } finally {
            removePrefix(preferences, prefix);
            SharedPreferences.Editor editor = preferences.edit();
            restoreString(editor, "internal.selfcheck_generation", previousGeneration);
            restoreString(editor, "internal.selfcheck_run_id", previousRunId);
            editor.commit();
        }
    }

    private static ContentValues selfCheckValues() {
        ContentValues values = new ContentValues();
        values.put("status", "installed_no_hits");
        values.put("updated_at", System.currentTimeMillis());
        return values;
    }

    private static void addEvidence(ContentValues values, Context context) {
        Intent evidence = new Intent();
        ZaloArtifactState.addEvidence(evidence, context);
        values.put("artifact_lightweight", evidence.getStringExtra("artifact_lightweight"));
        values.put("artifact_generation", evidence.getStringExtra("artifact_generation"));
        values.put("module_version_code", evidence.getIntExtra("module_version_code", -1));
        values.put("profile_sha256", evidence.getStringExtra("profile_sha256"));
    }

    private static int updateSelfCheck(Context context, String feature, ContentValues values) {
        return context.getContentResolver().update(Uri.parse(
                "content://com.ez.zalopatch.config/self_check/" + feature),
                values, null, null);
    }

    private static void removePrefix(SharedPreferences preferences, String prefix) {
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(prefix)) editor.remove(key);
        }
        editor.commit();
    }

    private static void restoreString(SharedPreferences.Editor editor, String key, String value) {
        if (value == null) {
            editor.remove(key);
        } else {
            editor.putString(key, value);
        }
    }

    private void assertHookIdentities(SymbolSchema.Active active) {
        for (Tweaks.Item item : Tweaks.ITEMS) {
            TweakHookInfo.Info info = TweakHookInfo.forKey(item.key, active);
            assertNotNull("Missing hook metadata for " + item.key, info);
            assertFalse("Missing hook path for " + item.key, info.path.isEmpty());
            assertFalse("Placeholder hook path for " + item.key,
                    "No runtime hook".equals(info.path));
            for (String symbol : info.driftSymbols) {
                assertFalse("Fallback identity displayed for " + item.key + ": " + symbol,
                        symbol.contains("<"));
                assertTrue("Displayed hook path omits drift symbol " + symbol + " for " + item.key,
                        info.path.contains(symbol));
            }
        }
    }
}
