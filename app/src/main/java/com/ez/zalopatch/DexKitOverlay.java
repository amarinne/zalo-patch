package com.ez.zalopatch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the DexKit overlay profile: DexKit-resolved descriptors merged over stable
 * defaults (and optionally a neighbouring profile's symbols), shaped as an exact
 * symbol profile the hook process can adopt for one Zalo process.
 *
 * <p>Dependency-free except {@code org.json} (present in unit tests): every merge
 * rule below is JVM-tested. Nothing here touches the classloader; structural
 * validation stays in {@code SymbolPreflight}, which runs against the adopted
 * overlay before any hook installs.
 *
 * <p>Merge precedence per leaf path: DexKit-resolved descriptors first, then the
 * base symbols (neighbouring profile when one was adopted), then the stable
 * defaults. Unresolvable paths stay absent, and their features fail soft to
 * {@code stale} exactly as they do without an overlay.
 */
public final class DexKitOverlay {
    /**
     * Stable symbol defaults copied from bundled profile 260802903. These are full
     * class names, method-name lists, and integer ids that do not rotate with
     * obfuscation. If Zalo renames one, the overlay preflight fails that family and
     * it stays stale — fail-closed, never silently rebound.
     */
    public static Map<String, String> stableStrings() {
        LinkedHashMap<String, String> strings = new LinkedHashMap<>();
        strings.put("symbols.zinstant.ad_item_view_class",
                "com.zing.zalo.ui.widget.ZinstantAdItemView");
        strings.put("symbols.zinstant.feed_ads_class",
                "com.zing.zalo.social.presentation.timeline.components.ads.FeedItemZInstantAds");
        strings.put("symbols.zinstant.communicator_class",
                "com.zing.zalo.zinstant.utils.ZinstantCommunicatorHelper");
        strings.put("symbols.zinstant.script_helper_class",
                "com.zing.zalo.zinstant.utils.ScriptHelperImpl");
        strings.put("symbols.me.tab_me_class",
                "com.zing.zalo.ui.maintab.me.TabMeView");
        strings.put("symbols.me.zinstant_view_class",
                "com.zing.zalo.ui.maintab.me.TabMeZinstantView");
        strings.put("symbols.me.zstyle_view_class",
                "com.zing.zalo.uicontrol.zinstant.ZinstantTabMeItem");
        strings.put("symbols.inbox.message_view_class",
                "com.zing.zalo.ui.maintab.msg.MessagesView");
        strings.put("symbols.inbox.conversation_class",
                "com.zing.zalo.data.chat.model.tabmessage.Conversation");
        strings.put("symbols.inbox.stranger_messages_view_class",
                "com.zing.zalo.ui.zviews.StrangerMessagesView");
        strings.put("symbols.bottom_tabs.custom_main_tab_class",
                "com.zing.zalo.ui.maintab.widget.CustomMainTab");
        strings.put("symbols.bottom_tabs.main_tab_view_class",
                "com.zing.zalo.ui.maintab.MainTabView");
        strings.put("symbols.telemetry.analytics_db_class",
                "com.zing.zalo.analytics.db.AnalyticsRoomDatabase_Impl");
        strings.put("symbols.webview.zalo_web_view_class",
                "com.zing.zalo.ui.zviews.ZaloWebView");
        strings.put("symbols.passcode.active_time_pref_key", "SaveActiveTimePasscodeSetting");
        return strings;
    }

    private DexKitOverlay() {
    }

    /**
     * Merges descriptor sources into one symbols tree. The base (neighbouring profile
     * symbols, may be null) is copied first so inherited anchors survive; stable
     * class-name defaults fill gaps; DexKit-resolved descriptors win per leaf path.
     * Empty values never override real ones.
     *
     * <p>No string-list or integer defaults are injected: an anchor with no
     * independent runtime verification predicate (structural preflight shape or a
     * hook-side guard) may not receive a default — a wrong default passes the green
     * gate and misbehaves with no stale signal, which is strictly worse than staying
     * stale. Lists and ids therefore come only from the neighbouring base or DexKit
     * resolution.
     */
    public static JSONObject merge(JSONObject baseSymbols, Map<String, String> dexkit,
                                   Map<String, List<String>> dexkitLists,
                                   Map<String, Integer> dexkitIntegers) {
        // JSONException is impossible here (all puts are guarded), but Android's
        // org.json declares it checked, so contain it at the boundary.
        try {
            return mergeOrThrow(baseSymbols, dexkit, dexkitLists, dexkitIntegers);
        } catch (org.json.JSONException exception) {
            throw new IllegalStateException("dexkit overlay merge failed", exception);
        }
    }

    private static JSONObject mergeOrThrow(JSONObject baseSymbols, Map<String, String> dexkit,
                                   Map<String, List<String>> dexkitLists,
                                   Map<String, Integer> dexkitIntegers)
            throws org.json.JSONException {
        JSONObject merged = baseSymbols == null ? new JSONObject()
                : new JSONObject(baseSymbols.toString());
        putStrings(merged, stableStrings(), false);
        putStrings(merged, dexkit, true);
        putLists(merged, dexkitLists, true);
        putIntegers(merged, dexkitIntegers, true);
        synthesizeBottomTabs(merged, dexkit);
        return merged;
    }

    /**
     * Writes validated pilot bind names into the merged symbols as
     * {@code zinstant.ad_bind_method} / {@code zinstant.feed_bind_method}.
     * The cache carries those names as dedicated entry fields, not anchor
     * paths, so a pure overlay never sees them without this inject. Empty
     * names are skipped; DexKit-resolved names win over any base value.
     */
    public static void injectZinstantBinds(JSONObject merged, String adBind, String feedBind) {
        boolean hasAd = adBind != null && !adBind.isEmpty();
        boolean hasFeed = feedBind != null && !feedBind.isEmpty();
        if (merged == null || (!hasAd && !hasFeed)) {
            return;
        }
        try {
            JSONObject zinstant = merged.optJSONObject("zinstant");
            if (zinstant == null) {
                zinstant = new JSONObject();
                merged.put("zinstant", zinstant);
            }
            if (hasAd) {
                zinstant.put("ad_bind_method", adBind);
            }
            if (hasFeed) {
                zinstant.put("feed_bind_method", feedBind);
            }
        } catch (org.json.JSONException exception) {
            throw new IllegalStateException("dexkit zinstant bind inject failed", exception);
        }
    }

    /**
     * Builds {@code symbols.bottom_tabs.current_tab_symbols[0]},
     * {@code symbols.bottom_tabs.current_methods}, and
     * {@code symbols.bottom_tabs.current_state_classes} from a complete set of
     * DexKit bottom-tab leaves, replacing the neighbouring base's entries. Only
     * the DexKit map counts toward completeness: mixing resolved names with
     * stale base names would arm a half-rotated family. Other
     * {@code bottom_tabs} keys (legacy entries, stable view classes, pager
     * anchors) are preserved.
     */
    static void synthesizeBottomTabs(JSONObject merged, Map<String, String> dexkit)
            throws org.json.JSONException {
        if (merged == null || dexkit == null) {
            return;
        }
        String[] methods = {"singleton", "icon_resolver", "rebuild", "refresh",
                "hide_discovery", "group_flag", "message_index", "phonebook_index",
                "group_index", "discovery_index", "timeline_index", "more_index",
                "me_index", "size"};
        String[] indexFields = {"message", "phonebook", "group", "discovery", "timeline",
                "more", "me", "size"};
        String[] enabledFields = {"group", "timeline", "discovery", "more", "me"};
        Map<String, String> leaves = new LinkedHashMap<>();
        if (!take(dexkit, leaves, "symbols.bottom_tabs.state_class")
                || !take(dexkit, leaves, "symbols.bottom_tabs.enum_class")) {
            return;
        }
        for (String role : methods) {
            if (!take(dexkit, leaves, "symbols.bottom_tabs." + role + "_method")) {
                return;
            }
        }
        for (String role : indexFields) {
            if (!take(dexkit, leaves, "symbols.bottom_tabs." + role + "_index_field")) {
                return;
            }
        }
        for (String role : enabledFields) {
            if (!take(dexkit, leaves, "symbols.bottom_tabs." + role + "_enabled_field")) {
                return;
            }
        }
        if (!take(dexkit, leaves, "symbols.bottom_tabs.icons_field")
                || !take(dexkit, leaves, "symbols.bottom_tabs.preloaded_field")) {
            return;
        }
        JSONObject bottom = merged.optJSONObject("bottom_tabs");
        if (bottom == null) {
            bottom = new JSONObject();
            merged.put("bottom_tabs", bottom);
        }
        JSONObject currentMethods = new JSONObject();
        for (String role : methods) {
            currentMethods.put(role, leaves.get("symbols.bottom_tabs." + role + "_method"));
        }
        bottom.put("current_methods", currentMethods);
        JSONObject enabled = new JSONObject();
        for (String role : enabledFields) {
            enabled.put(role, leaves.get("symbols.bottom_tabs." + role + "_enabled_field"));
        }
        JSONObject index = new JSONObject();
        for (String role : indexFields) {
            index.put(role, leaves.get("symbols.bottom_tabs." + role + "_index_field"));
        }
        JSONObject definition = new JSONObject();
        definition.put("state_class", leaves.get("symbols.bottom_tabs.state_class"));
        definition.put("enum_class", leaves.get("symbols.bottom_tabs.enum_class"));
        definition.put("group_tab_field", "GROUP");
        definition.put("enabled_fields", enabled);
        definition.put("index_fields", index);
        definition.put("icons_field", leaves.get("symbols.bottom_tabs.icons_field"));
        definition.put("preloaded_field", leaves.get("symbols.bottom_tabs.preloaded_field"));
        JSONArray array = new JSONArray();
        array.put(definition);
        bottom.put("current_tab_symbols", array);
        JSONArray stateClasses = new JSONArray();
        stateClasses.put(leaves.get("symbols.bottom_tabs.state_class"));
        bottom.put("current_state_classes", stateClasses);
        // The flat leaves served synthesis; remove them so no consumer mistakes them
        // for schema paths the hooks read.
        for (String path : leaves.keySet()) {
            removeLeaf(merged, path);
        }
    }

    private static boolean take(Map<String, String> source, Map<String, String> output,
                                String path) {
        String value = source.get(path);
        if (value == null || value.isEmpty()) {
            return false;
        }
        output.put(path, value);
        return true;
    }

    private static void removeLeaf(JSONObject root, String path) {
        String[] parts = path.split("\\.");
        if (parts.length < 2 || !"symbols".equals(parts[0])) {
            return;
        }
        JSONObject node = root;
        for (int index = 1; index < parts.length - 1; index++) {
            node = node.optJSONObject(parts[index]);
            if (node == null) {
                return;
            }
        }
        node.remove(parts[parts.length - 1]);
    }

    /**
     * Builds an exact-profile JSON for the installed version carrying the merged
     * symbols. The artifact block records the scanned host identity. Verification
     * is caller-supplied and must describe the actual proof: DexKit overlays are
     * static resolution plus structural preflight, so {@code static-verified} is the
     * honest label until named behavior is exercised on the build and device.
     */
    public static String buildProfile(long versionCode, String baseApkSha256,
                                      String signerSha256, JSONObject symbols,
                                      String verification) {
        try {
            return buildProfileOrThrow(versionCode, baseApkSha256, signerSha256, symbols,
                    verification);
        } catch (org.json.JSONException exception) {
            throw new IllegalStateException("dexkit overlay build failed", exception);
        }
    }

    private static String buildProfileOrThrow(long versionCode, String baseApkSha256,
                                      String signerSha256, JSONObject symbols,
                                      String verification)
            throws org.json.JSONException {
        JSONObject profile = new JSONObject();
        profile.put("schema_version", 1);
        profile.put("schema_revision", 0);
        profile.put("zalo_package", "com.zing.zalo");
        JSONObject version = new JSONObject();
        version.put("min_code", versionCode);
        version.put("max_code", versionCode);
        version.put("name", "DexKit overlay " + versionCode);
        version.put("notes", "On-device DexKit resolution; process-local, never persisted.");
        profile.put("zalo_version", version);
        JSONObject artifact = new JSONObject();
        artifact.put("base_apk_sha256", baseApkSha256 == null ? "" : baseApkSha256);
        artifact.put("signer_sha256", signerSha256 == null ? "" : signerSha256);
        artifact.put("hook_code_apk", "base");
        artifact.put("verification", verification == null || verification.isEmpty()
                ? "static-verified" : verification);
        profile.put("artifact", artifact);
        profile.put("symbols", symbols == null ? new JSONObject() : symbols);
        return profile.toString();
    }

    /** Counts resolved DexKit string anchors present in the merged tree. */
    public static int countResolved(JSONObject merged, Map<String, String> dexkit) {
        if (merged == null || dexkit == null) {
            return 0;
        }
        int count = 0;
        for (Map.Entry<String, String> item : dexkit.entrySet()) {
            if (item.getValue() != null && !item.getValue().isEmpty()
                    && item.getValue().equals(stringAt(merged, item.getKey()))) {
                count++;
            }
        }
        return count;
    }

    static String stringAt(JSONObject root, String path) {
        if (root == null || path == null) {
            return "";
        }
        String[] parts = path.split("\\.");
        // Trees built by putLeaf store the symbols content without the leading
        // "symbols" root; skip the prefix when present.
        int start = parts.length > 1 && "symbols".equals(parts[0]) ? 1 : 0;
        JSONObject node = root;
        for (int index = start; index < parts.length - 1; index++) {
            node = node.optJSONObject(parts[index]);
            if (node == null) {
                return "";
            }
        }
        Object leaf = node.opt(parts[parts.length - 1]);
        return leaf instanceof String ? (String) leaf : "";
    }

    private static void putStrings(JSONObject symbols, Map<String, String> values,
                                   boolean override) throws org.json.JSONException {
        if (values == null) {
            return;
        }
        for (Map.Entry<String, String> item : values.entrySet()) {
            if (item.getValue() == null || item.getValue().isEmpty()) {
                continue;
            }
            if (!override && !stringAt(symbols, item.getKey()).isEmpty()) {
                continue;
            }
            putLeaf(symbols, item.getKey(), item.getValue());
        }
    }

    private static void putLists(JSONObject symbols, Map<String, List<String>> values,
                                 boolean override) throws org.json.JSONException {
        if (values == null) {
            return;
        }
        for (Map.Entry<String, List<String>> item : values.entrySet()) {
            if (item.getValue() == null || item.getValue().isEmpty()) {
                continue;
            }
            if (!override && symbolsHas(symbols, item.getKey())) {
                continue;
            }
            JSONArray array = new JSONArray();
            for (String value : item.getValue()) {
                array.put(value);
            }
            putLeaf(symbols, item.getKey(), array);
        }
    }

    private static void putIntegers(JSONObject symbols, Map<String, Integer> values,
                                    boolean override) throws org.json.JSONException {
        if (values == null) {
            return;
        }
        for (Map.Entry<String, Integer> item : values.entrySet()) {
            if (item.getValue() == null) {
                continue;
            }
            if (!override && symbolsHas(symbols, item.getKey())) {
                continue;
            }
            putLeaf(symbols, item.getKey(), item.getValue());
        }
    }

    private static boolean symbolsHas(JSONObject symbols, String path) {
        String[] parts = path.split("\\.");
        if (parts.length < 2 || !"symbols".equals(parts[0])) {
            return false;
        }
        JSONObject node = symbols;
        for (int index = 1; index < parts.length - 1; index++) {
            node = node.optJSONObject(parts[index]);
            if (node == null) {
                return false;
            }
        }
        return node.has(parts[parts.length - 1]);
    }

    private static void putLeaf(JSONObject symbols, String path, Object value) throws org.json.JSONException {
        String[] parts = path.split("\\.");
        if (parts.length < 2 || !"symbols".equals(parts[0])) {
            return;
        }
        JSONObject node = symbols;
        for (int index = 1; index < parts.length - 1; index++) {
            JSONObject child = node.optJSONObject(parts[index]);
            if (child == null) {
                child = new JSONObject();
                node.put(parts[index], child);
            }
            node = child;
        }
        node.put(parts[parts.length - 1], value);
    }
}
