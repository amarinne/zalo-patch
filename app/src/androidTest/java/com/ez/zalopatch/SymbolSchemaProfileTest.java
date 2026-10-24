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
        assertEquals(21, active.schemaRevision);
        assertEquals(260602901, active.minCode);
        assertEquals("je1.c1", active.string("symbols.inbox.message_adapter_class", ""));
        assertEquals("a55d59581d4e4d038a28bc17a3d12237f519566ecfb7140eb445a6fa045eef34",
                active.string("artifact.base_apk_sha256", ""));
        assertEquals("device-verified", active.string("artifact.verification", ""));
        assertEquals("vd0.f0", active.string("symbols.chat.send_seen_manager_class", ""));
        assertEquals("yz.p", active.string("symbols.chat.message_repository_class", ""));
        assertPasscodeSymbols(active, "g40.r0", "j", "lz.j", "B3");
        assertWebviewSymbols(active, "com.zing.zalo.ui.zviews.ss", "j", "A8");
        assertHookIdentities(active);
    }

    public void testSelectsCurrentExactProfile() throws Exception {
        SymbolSchema.Active active = SymbolSchema.select(bundleJson, "Test", 260701901L);

        assertTrue(active.valid);
        assertEquals(22, active.schemaRevision);
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
        assertEquals("yd0.h0", active.string("symbols.chat.send_seen_manager_class", ""));
        assertEquals("c00.q", active.string("symbols.chat.message_repository_class", ""));
        assertPasscodeSymbols(active, "k40.q0", "k", "pz.j", "B3");
        assertWebviewSymbols(active, "com.zing.zalo.ui.zviews.xs", "j", "z8");

        assertHookIdentities(active);
        assertTrue(TweakHookInfo.forKey(Tweaks.KEY_DISABLE_EVENT_ANALYTICS, active)
                .path.contains("#z()"));
        assertTrue(TweakHookInfo.forKey(Tweaks.KEY_DISABLE_EVENT_ANALYTICS, active).driftProne);
        assertFalse(TweakHookInfo.forKey(Tweaks.KEY_DISABLE_CRASHLYTICS, active).driftProne);
    }

    public void testSelectsAugustExactProfile() throws Exception {
        SymbolSchema.Active active = SymbolSchema.select(bundleJson, "Test", 260801903L);

        assertTrue(active.valid);
        assertEquals(23, active.schemaRevision);
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
        assertEquals("je0.k0", active.string("symbols.chat.send_seen_manager_class", ""));
        assertEquals("l00.r", active.string("symbols.chat.message_repository_class", ""));
        assertPasscodeSymbols(active, "u40.p0", "X", "yz.j", "A3");
        assertWebviewSymbols(active, "com.zing.zalo.ui.zviews.xs", "j", "z8");
        assertHookIdentities(active);
    }

    public void testSelectsAugustBetaExactProfile() throws Exception {
        SymbolSchema.Active active = SymbolSchema.select(bundleJson, "Test", 260802903L);

        assertTrue(active.valid);
        assertEquals(25, active.schemaRevision);
        assertEquals(260802903, active.minCode);
        assertEquals("zf1.e1", active.string("symbols.inbox.message_adapter_class", ""));
        assertEquals("r00.c", active.string("symbols.inbox.normal_item_class", ""));
        assertEquals("tf1.w", active.strings(
                "symbols.bottom_tabs.current_state_classes").get(0));
        assertEquals("wf1.k", active.string("symbols.me.adapter_class", ""));
        assertEquals("f70.n", active.string("symbols.inbox.friend_manager_class", ""));
        assertEquals("n", active.string("symbols.inbox.friend_manager_instance_method", ""));
        assertEquals(java.util.Arrays.asList("A", "C"),
                active.strings("symbols.inbox.friend_manager_follow_methods"));
        assertEquals("5736dd272918b73d689d11ff62e9399d80effaad85b30f2180b24ab8809a09ed",
                active.string("artifact.base_apk_sha256", ""));
        assertEquals("device-verified", active.string("artifact.verification", ""));
        assertEquals("le0.m0", active.string("symbols.chat.send_seen_manager_class", ""));
        assertEquals("m00.q", active.string("symbols.chat.message_repository_class", ""));
        assertPasscodeSymbols(active, "v40.q0", "n", "zz.j", "B3");
        assertWebviewSymbols(active, "com.zing.zalo.ui.zviews.vt", "k", "A8");
        assertHookIdentities(active);
    }

    public void testUnknownVersionDoesNotUseNearestProfile() throws Exception {
        SymbolSchema.Active active = SymbolSchema.select(bundleJson, "Test", 260800000L);

        assertFalse(active.valid);
        assertTrue(active.bundleValid);
        assertTrue(active.validation.contains("No exact bundled symbol profile"));
        assertEquals("260602901, 260701901, 260801903, 260802903, 261001903, 261001905, "
                        + "261002901, 261002903",
                active.supportedVersionCodes);
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
        assertEquals(23, remote.schemaRevision);
        assertEquals("of1.h1", remote.string("symbols.inbox.message_adapter_class", ""));

        SymbolSchema.Active selected = SymbolProfileSelection.select(bundled, remote);
        assertEquals("Remote catalog 14", selected.source);
    }

    /**
     * A signed remote profile that names the bundled schema revision replaces the bundled exact
     * profile. The client keeps the bundled profile when the signed profile names no revision or
     * an older one.
     */
    public void testSignedRemoteSupersedesOnlyTheNamedBundledRevision() throws Exception {
        JSONObject bundle = new JSONObject(bundleJson);
        JSONArray profiles = bundle.getJSONArray("profiles");
        JSONObject bundledProfile = null;
        for (int index = 0; index < profiles.length(); index++) {
            JSONObject profile = profiles.getJSONObject(index);
            if (profile.getJSONObject("zalo_version").getLong("min_code") == 261001905L) {
                bundledProfile = profile;
            }
        }
        assertNotNull(bundledProfile);

        SymbolSchema.Active bundled = SymbolSchema.select(bundleJson, "Bundled", 261001905L);
        assertTrue(bundled.valid);
        int bundledRevision = bundled.schemaRevision;

        JSONObject correction = new JSONObject(bundledProfile.toString())
                .put("schema_revision", bundledRevision + 1)
                .put(SymbolProfileSelection.SUPERSEDES_KEY, bundledRevision);
        SymbolSchema.Active remote = SymbolSchema.select(correction.toString(),
                "Remote catalog 33", 261001905L);
        assertTrue(remote.valid);
        assertEquals("Remote catalog 33",
                SymbolProfileSelection.select(bundled, remote).source);

        JSONObject unnamed = new JSONObject(bundledProfile.toString())
                .put("schema_revision", bundledRevision + 1);
        SymbolSchema.Active unnamedRemote = SymbolSchema.select(unnamed.toString(),
                "Remote catalog 33", 261001905L);
        assertEquals("Bundled profile 261001905",
                SymbolProfileSelection.select(bundled, unnamedRemote).source);
    }

    public void testProbeProfileSelectsTheNewestRevisionForAnUnmappedVersion() throws Exception {
        SymbolSchema.Active probe = SymbolSchema.probeProfileForHooks(getContext());

        assertNotNull(probe);
        assertTrue(probe.valid);
        JSONArray profiles = new JSONObject(bundleJson).getJSONArray("profiles");
        int newestRevision = Integer.MIN_VALUE;
        int newestCode = -1;
        for (int index = 0; index < profiles.length(); index++) {
            JSONObject profile = profiles.getJSONObject(index);
            int revision = profile.getInt("schema_revision");
            if (revision > newestRevision) {
                newestRevision = revision;
                newestCode = profile.getJSONObject("zalo_version").getInt("min_code");
            }
        }
        assertEquals(newestRevision, probe.schemaRevision);
        assertEquals(newestCode, probe.minCode);
        assertTrue("probe source must be distinguishable from an armed profile",
                probe.source.startsWith("Probe"));
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

    public void testEmbeddedFallbackMatchesPackagedProfiles() {
        assertEquals(bundleJson, BundledSymbolSchemaJson.json());
    }

    /**
     * A resolved reaction-send overlay is the case that a bundled profile cannot cover:
     * every placeholder must be gone and the displayed path must still carry each drift
     * symbol. The anchor values are the independently inspected Zalo 26.10.02 evidence
     * recorded on {@link DexKitReactionSendFingerprint}; nothing here is added to a bundled
     * profile.
     */
    public void testResolvedReactionSendOverlayDropsPlaceholdersAndKeepsDriftSymbols()
            throws Exception {
        JSONObject profile = new JSONObject()
                .put("schema_version", 1)
                .put("schema_revision", 34)
                .put("zalo_package", "com.zing.zalo")
                .put("zalo_version", new JSONObject()
                        .put("min_code", 261002901L).put("max_code", 261002901L))
                .put("artifact", new JSONObject()
                        .put("base_apk_sha256",
                                "3bbe323515c5b68f86dd87e957202f5abdbfdf308dce429bd795632cfb46aa03")
                        .put("signer_sha256",
                                "d86efe151e09bf4ca8440cb3bfa0a81be2544f70c78587daf0266dfca2fa25df")
                        .put("hook_code_apk", "base")
                        .put("verification", "static-verified"))
                .put("symbols", new JSONObject().put("reaction_send", new JSONObject()
                        .put("listener_class", "lo1.r")
                        .put("callback_method", "Z")
                        .put("reaction_class", "m00.i")
                        .put("factory_method", "a")));

        SymbolSchema.Active active = SymbolSchema.select(
                profile.toString(), "Synthetic overlay", 261002901L);
        assertTrue(active.validation, active.valid);
        TweakHookInfo.Info info = TweakHookInfo.forKey(Tweaks.KEY_CUSTOM_REACTION, active);

        assertEquals("com.zing.zalo.ui.widget.reaction.ReactionPickerView#getListener() -> "
                + "lo1.r#Z(m00.i,String) + m00.i#a(int,String)", info.path);
        assertFalse("Resolved overlay must not display a placeholder", info.path.contains("<"));
        assertEquals(java.util.Arrays.asList(
                "com.zing.zalo.ui.widget.reaction.ReactionPickerView",
                "lo1.r",
                "#Z(m00.i,String)",
                "m00.i",
                "#a(int,String)"), info.driftSymbols);
        for (String symbol : info.driftSymbols) {
            assertFalse("Placeholder drift symbol " + symbol, symbol.contains("<"));
            assertTrue("Hook path omits drift symbol " + symbol, info.path.contains(symbol));
        }
    }

    public void testOctoberMaintenanceHasExactArtifactAndReviewedRoutes() throws Exception {
        SymbolSchema.Active active = SymbolSchema.select(bundleJson, "Test", 261001905L);
        assertEquals(32, active.schemaRevision);
        assertEquals("2fe8d3f2bbd3fc923bcf62e4b519e50661176ed79a51aac01be8b39c8ef3994c",
                active.string("artifact.base_apk_sha256", ""));
        assertHookIdentities(active);
        assertOctoberReviewedRoutes(active);
    }

    public void testOctoberExactProfileKeepsReviewedRoutesAndOptionalGaps() throws Exception {
        assertOctoberReviewedRoutes(SymbolSchema.select(bundleJson, "Test", 261001903L));
    }

    private void assertOctoberReviewedRoutes(SymbolSchema.Active active) throws Exception {
        assertTrue(active.validation, active.valid);
        assertEquals("fi1.w1", active.string("symbols.inbox.message_adapter_class", ""));
        assertEquals("f", active.string("symbols.inbox.conversation_field", ""));
        assertEquals("j", active.string("symbols.chat.reaction_long_press_method", ""));
        assertEquals("H5", active.string("symbols.chat.reaction_long_press_armed_field", ""));
        assertEquals("static-verified", active.string("artifact.verification", ""));
        assertEquals(java.util.Arrays.asList("zh1.b0"),
                active.strings("symbols.bottom_tabs.current_state_classes"));
        assertTrue(active.root.getJSONObject("symbols").getJSONObject("bottom_tabs")
                .getJSONArray("current_tab_symbols").getJSONObject(0)
                .getBoolean("preserve_icon_arrays"));
        assertEquals("ac2.p2", active.string("symbols.call_recording.callback_class", ""));
        assertEquals("kj.a", active.string("symbols.call_recording.peer_manager_class", ""));
        assertEquals("", active.string("symbols.call_recording.activity_ready_method", ""));
        assertEquals("V", active.string("symbols.chat.send_typing_method", ""));
        assertFalse(active.root.getJSONObject("symbols").getJSONObject("me")
                .getBoolean("zstyle_view_exclusive"));
    }

    public void testCatalogRetainsEveryExactVersionAndSymbolCoverage() {
        java.util.Map<Long, SymbolSchema.ProfileInfo> byVersion = new java.util.LinkedHashMap<>();
        for (SymbolSchema.ProfileInfo info : SymbolSchema.catalog(getContext())) {
            byVersion.put(info.versionCode, info);
        }

        // A stored remote catalog entry adds the installed release on top of the bundled maps,
        // so assert membership per version instead of a fixed size and order.
        assertTrue(byVersion.containsKey(260802903L));
        assertTrue(byVersion.containsKey(260801903L));
        assertTrue(byVersion.containsKey(260701901L));
        assertTrue(byVersion.containsKey(260602901L));
        assertTrue(byVersion.get(260802903L).symbolPaths.contains(
                "symbols.inbox.message_adapter_class = zf1.e1"));
        assertTrue(byVersion.get(260801903L).symbolPaths.contains(
                "symbols.inbox.message_adapter_class = of1.h1"));
        assertTrue(byVersion.get(260701901L).symbolPaths.contains(
                "symbols.inbox.message_adapter_class = se1.g1"));
        assertTrue(byVersion.get(260602901L).symbolPaths.contains(
                "symbols.inbox.message_adapter_class = je1.c1"));
    }

    public void testCatalogAddsRemoteOnlyProfileOnce() throws Exception {
        java.util.ArrayList<SymbolSchema.ProfileInfo> catalog =
                new java.util.ArrayList<>(SymbolSchema.catalog(getContext()));
        JSONObject profile = new JSONObject(bundleJson).getJSONArray("profiles")
                .getJSONObject(0);
        JSONObject version = profile.getJSONObject("zalo_version");
        version.put("min_code", 260902905);
        version.put("max_code", 260902905);
        SymbolSchema.Active remote = SymbolSchema.select(profile.toString(),
                "Remote catalog 25", 260902905L);

        int originalSize = catalog.size();
        SymbolSchema.addRemoteProfileIfMissing(catalog, remote);
        SymbolSchema.addRemoteProfileIfMissing(catalog, remote);

        assertEquals(originalSize + 1, catalog.size());
        SymbolSchema.ProfileInfo added = catalog.get(catalog.size() - 1);
        assertEquals(260902905L, added.versionCode);
        assertEquals("Remote catalog 25", added.source);
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
            valid.put("run_id", previousRunId == null || previousRunId.isEmpty()
                    ? java.util.UUID.randomUUID().toString() : previousRunId);
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

    private void assertWebviewSymbols(SymbolSchema.Active active, String companion,
                                      String dispatch, String redirect) {
        assertEquals("com.zing.zalo.ui.zviews.ZaloWebView",
                active.string("symbols.webview.zalo_web_view_class", ""));
        assertEquals(companion, active.string("symbols.webview.companion_class", ""));
        assertEquals(dispatch, active.string("symbols.webview.open_dispatch_method", ""));
        assertEquals(redirect, active.string("symbols.webview.redirect_transform_method", ""));
    }

    private void assertHookIdentities(SymbolSchema.Active active) {
        boolean backupMapped = active.minCode >= 260801903L;
        assertEquals(backupMapped,
                !active.string("symbols.backup.interval_reader_class", "").isEmpty());
        assertEquals(backupMapped,
                !active.string("symbols.backup.interval_reader_method", "").isEmpty());
        for (Tweaks.Item item : Tweaks.ITEMS) {
            TweakHookInfo.Info info = TweakHookInfo.forKey(item.key, active);
            assertNotNull("Missing hook metadata for " + item.key, info);
            assertFalse("Missing hook path for " + item.key, info.path.isEmpty());
            assertFalse("Placeholder hook path for " + item.key,
                    "No runtime hook".equals(info.path));
            if (Tweaks.KEY_BACKUP_FREQUENT_PUSH.equals(item.key) && !backupMapped) {
                assertTrue(info.path.contains("<Zalo preference helper>"));
                continue;
            }
            // Partial profiles expose missing bindings without inventing historical names.
            if (Tweaks.KEY_KEEP_EXPIRED_MEDIA.equals(item.key)
                    && active.string("symbols.media.state_classifier_class", "").isEmpty()) {
                assertEquals("", active.string("symbols.media.state_classifier_method", ""));
                assertTrue(info.path.contains("<media classifier>"));
                continue;
            }
            if (Tweaks.KEY_VIEW_STORIES_ANONYMOUSLY.equals(item.key)
                    && active.string(DexKitStoryFingerprint.ANCHOR_SEEN_CLASS, "").isEmpty()) {
                assertMissingAnchors(active, java.util.Arrays.asList(
                        DexKitStoryFingerprint.ANCHOR_SEEN_CLASS,
                        DexKitStoryFingerprint.ANCHOR_SEEN_METHOD,
                        DexKitStoryFingerprint.ANCHOR_SUGGESTED_CLASS,
                        DexKitStoryFingerprint.ANCHOR_SUGGESTED_METHOD));
                assertTrue(info.path.contains("<story request>"));
                continue;
            }
            if (Tweaks.KEY_PREFER_ORIGINAL_PHOTO.equals(item.key)
                    && active.string(DexKitOriginalPhotoFingerprint.PICKER, "").isEmpty()) {
                assertMissingAnchors(active, DexKitOriginalPhotoFingerprint.ANCHORS);
                assertEquals(DexKitOriginalPhotoFingerprint.MEDIA_ITEM
                        + "#<apply quality>(int) + " + DexKitOriginalPhotoFingerprint.SHARE_VIEW
                        + "#<photo share>(Bundle)", info.path);
                continue;
            }
            if (Tweaks.KEY_HIDE_STORY_ADS.equals(item.key)
                    && active.string(DexKitStoryAdFingerprint.ANCHOR_CLASS, "").isEmpty()) {
                assertMissingAnchors(active, java.util.Arrays.asList(
                        DexKitStoryAdFingerprint.ANCHOR_CLASS,
                        DexKitStoryAdFingerprint.ANCHOR_BIND));
                assertEquals("<story ad view>#<bind>() + constructors (after collapse)", info.path);
                continue;
            }
            if (Tweaks.KEY_DISABLE_ADTIMA_OFFLINE_TRACKING.equals(item.key)
                    && active.string(DexKitAdtimaFingerprint.OFFLINE_WINDOW_CLASS, "").isEmpty()) {
                assertMissingAnchors(active, java.util.Arrays.asList(
                        DexKitAdtimaFingerprint.OFFLINE_WINDOW_CLASS,
                        DexKitAdtimaFingerprint.OFFLINE_WINDOW_METHOD,
                        DexKitAdtimaFingerprint.TRACKING_GATE_CLASS,
                        DexKitAdtimaFingerprint.TRACKING_GATE_METHOD));
                assertEquals("<Adtima offline-window owner>#<offline window>()Z + "
                        + "<Adtima tracking owner>#<tracking gate>()Z", info.path);
                continue;
            }
            if (Tweaks.KEY_HIDE_VIDEO_ADS.equals(item.key)
                    && active.string(DexKitVideoAdsFingerprint.Route.OUTSTREAM
                            .anchor("view_class"), "").isEmpty()) {
                java.util.ArrayList<String> missingSymbols = new java.util.ArrayList<>();
                for (DexKitVideoAdsFingerprint.Route route
                        : DexKitVideoAdsFingerprint.Route.values()) {
                    assertMissingAnchors(active, java.util.Arrays.asList(route.anchors()));
                    missingSymbols.add("<" + route.id + ">");
                    missingSymbols.add("#<marker>()");
                }
                assertEquals("Qualified Zalo Video ad roots: constructor and native bind after-hooks",
                        info.path);
                assertEquals(missingSymbols, info.driftSymbols);
                continue;
            }
            if (Tweaks.KEY_BLOCK_TYPING_STATUS.equals(item.key)
                    && active.string("symbols.chat.send_typing_method", "").isEmpty()) {
                assertTrue(info.path.contains("<send typing>"));
                continue;
            }
            if ((Tweaks.KEY_HIDE_DISCOVERY_TAB.equals(item.key)
                    || Tweaks.KEY_HIDE_TIMELINE_TAB.equals(item.key)
                    || Tweaks.KEY_KEEP_GROUP_TAB.equals(item.key))
                    && active.strings("symbols.bottom_tabs.current_state_classes").isEmpty()) {
                assertTrue(info.path.contains("<bottom-tab state>"));
                continue;
            }
            // The reaction-send family is resolved at runtime, so no bundled profile carries
            // it. Absence of its first anchor must mean every anchor is absent, and the
            // displayed identity is the honest placeholder set instead of a guessed name.
            if (Tweaks.KEY_CUSTOM_REACTION.equals(item.key)
                    && active.string(DexKitReactionSendFingerprint.ANCHOR_LISTENER_CLASS,
                    "").isEmpty()) {
                assertMissingAnchors(active, DexKitReactionSendFingerprint.ANCHORS);
                assertEquals(DexKitReactionSendFingerprint.PICKER_CLASS + "#"
                        + DexKitReactionSendFingerprint.LISTENER_GETTER
                        + "() -> <listener>#<reaction callback>(<reaction>,String) + "
                        + "<reaction>#<factory>(int,String)", info.path);
                assertEquals(java.util.Arrays.asList(
                        DexKitReactionSendFingerprint.PICKER_CLASS,
                        "<listener>",
                        "#<reaction callback>(<reaction>,String)",
                        "<reaction>",
                        "#<factory>(int,String)"), info.driftSymbols);
                continue;
            }
            for (String symbol : info.driftSymbols) {
                assertFalse("Fallback identity displayed for " + item.key + ": " + symbol,
                        symbol.contains("<"));
                assertTrue("Displayed hook path omits drift symbol " + symbol + " for " + item.key,
                        info.path.contains(symbol));
            }
        }
    }

    private void assertMissingAnchors(SymbolSchema.Active active, Iterable<String> anchors) {
        for (String anchor : anchors) {
            assertEquals("Partial dynamic binding in bundled profile: " + anchor,
                    "", active.string(anchor, ""));
        }
    }

    private void assertPasscodeSymbols(SymbolSchema.Active active, String readerClass,
                                       String readerMethod, String setterClass,
                                       String setterMethod) {
        assertEquals(readerClass,
                active.string("symbols.passcode.prefs_int_reader_class", ""));
        assertEquals(readerMethod,
                active.string("symbols.passcode.prefs_int_reader_method", ""));
        assertEquals(setterClass,
                active.string("symbols.passcode.active_time_setter_class", ""));
        assertEquals(setterMethod,
                active.string("symbols.passcode.active_time_setter_method", ""));
        assertEquals("SaveActiveTimePasscodeSetting",
                active.string("symbols.passcode.active_time_pref_key", ""));
        TweakHookInfo.Info info = TweakHookInfo.forKey(Tweaks.KEY_PASSCODE_GRACE_MS, active);
        assertTrue(info.path.contains(readerClass + "#" + readerMethod + "(I,String,Z)I"));
        assertTrue(info.driftProne);
    }
}
