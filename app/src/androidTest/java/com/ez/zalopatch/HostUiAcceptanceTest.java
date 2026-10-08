package com.ez.zalopatch;

import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import android.util.AtomicFile;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Deliberate Fold acceptance. Excluded from the ordinary preservation test selection. */
public final class HostUiAcceptanceTest extends InstrumentationTestCase {
    private static final String HOST = "com.zing.zalo:id/";
    private static final String[] OWNED = {
            Tweaks.KEY_HIDE_DISCOVERY_TAB, Tweaks.KEY_HIDE_TIMELINE_TAB,
            Tweaks.KEY_KEEP_GROUP_TAB, Tweaks.KEY_FORCE_MESSAGES_AS_HOME,
            Tweaks.KEY_CATEGORY_GROUPS, Tweaks.KEY_CATEGORY_OA, Tweaks.KEY_CATEGORY_STRANGERS,
            Tweaks.KEY_DEFAULT_INBOX_FILTER, Tweaks.KEY_FILTER_POPOVER_CATEGORIES,
            Tweaks.KEY_HIDE_QR_WALLET, Tweaks.KEY_HIDE_ZCLOUD,
            Tweaks.KEY_HIDE_ZSTYLE, Tweaks.KEY_HIDE_ZBUSINESS, Tweaks.KEY_HIDE_PROMO_NOTIFICATIONS,
            Tweaks.KEY_RECORD_NOTIFICATION_HISTORY, Tweaks.KEY_AUTO_RECORD_CALLS,
            NotificationRuleStore.PREF_KEY
    };
    private Context context;
    private UiAutomation ui;
    private String runId;
    private String suite;
    private JSONObject inboxFixtures;

    private interface Check { void run() throws Exception; }
    private interface Condition { boolean matches(); }
    private static final class Blocked extends Exception {
        Blocked(String code) { super(code); }
    }

    private void initialize(String argument) throws Exception {
        context = getInstrumentation().getTargetContext();
        ui = getInstrumentation().getUiAutomation();
        android.accessibilityservice.AccessibilityServiceInfo service = ui.getServiceInfo();
        service.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        ui.setServiceInfo(service);
        Bundle args = ((InstrumentationTestRunner) getInstrumentation()).getArguments();
        runId = args.getString(argument, "");
        if (!runId.matches("[a-zA-Z0-9-]{1,60}")) throw new Blocked("invalid_run_identity");
        suite = args.getString("e2e_suite", "all");
        String fixtures = args.getString("e2e_fixtures", "");
        if (!fixtures.isEmpty()) inboxFixtures = new JSONObject(new String(
                android.util.Base64.decode(fixtures, android.util.Base64.DEFAULT), StandardCharsets.UTF_8));
        if (!Arrays.asList("all", "navigation", "inbox", "notifications", "me", "settings").contains(suite)) {
            throw new Blocked("invalid_suite");
        }
        if (!"q2q".equals(shell("getprop ro.product.device").trim())) {
            throw new Blocked("not_registered_fold");
        }
        PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        if (installed.getLongVersionCode() != BuildConfig.VERSION_CODE) {
            throw new Blocked("test_module_version_mismatch");
        }
    }

    public void testAcceptance() throws Exception {
        initialize("e2e_run_id");
        if (snapshotFile().exists()) throw new Blocked("pending_recovery_snapshot");
        if (SettingsChanges.pendingCount(context) > 0) throw new Blocked("unapplied_owner_settings");
        for (String property : new String[]{"debug.zalopatch", "debug.zalopatch.trace"}) {
            String value = shell("getprop " + property).trim();
            if (!value.isEmpty() && !"0".equals(value)) throw new Blocked("existing_debug_session");
        }
        String previousRun = shell("getprop debug.zalopatch.e2e_run").trim();
        if (!previousRun.isEmpty() && !"0".equals(previousRun)) throw new Blocked("existing_host_fixture_session");
        saveSnapshot();
        try {
            if (selected("settings")) settings();
            if (selected("navigation")) navigation();
            if (selected("inbox")) inbox();
            if (selected("me")) me();
            if (selected("notifications")) notifications();
        } finally {
            // Emit completion only after independently comparing restored owned values.
            restore();
        }
        check("compatibility.self_check", "clean_runtime_after_restoration", "module_ui", () -> {
            try (Cursor rows = context.getContentResolver().query(
                    Uri.parse("content://com.ez.zalopatch.config/self_check"), null, null, null, null)) {
                require(rows != null && rows.getCount() > 0, "runtime_rows_unavailable");
                while (rows.moveToNext()) {
                    String status = rows.getString(rows.getColumnIndexOrThrow("status"));
                    require(!"failed".equals(status) && !"stale".equals(status), "runtime_failure_after_restoration");
                }
            }
        });
        Bundle result = new Bundle();
        result.putString("e2e_complete", runId);
        getInstrumentation().sendStatus(2, result);
    }

    /** Recovery is callable even after the instrumentation process was terminated. */
    public void testRestore() throws Exception {
        initialize("e2e_recovery_run_id");
        restore();
        Bundle result = new Bundle();
        result.putString("e2e_restored", runId);
        getInstrumentation().sendStatus(2, result);
    }

    private boolean selected(String name) { return "all".equals(suite) || name.equals(suite); }

    private void settings() throws Exception {
        check("settings.presentation", "section_setting_inventory", "module_ui", () -> {
            StatusActivity activity = (StatusActivity) getInstrumentation().startActivitySync(
                    new Intent(context, StatusActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                for (String section : new String[]{Tweaks.SECTION_NAVIGATION, Tweaks.SECTION_ME,
                        Tweaks.SECTION_TELEMETRY, Tweaks.SECTION_INBOX, Tweaks.SECTION_CHAT,
                        Tweaks.SECTION_CALLS, Tweaks.SECTION_ADS, Tweaks.SECTION_NOTIFICATIONS,
                        Tweaks.SECTION_BACKUP, Tweaks.SECTION_SECURITY, Tweaks.SECTION_DEVELOPER}) {
                    SectionActivity.SettingsFragment fragment = SectionActivity.SettingsFragment.forSection(section);
                    onMain(() -> activity.getSupportFragmentManager()
                            .beginTransaction().replace(R.id.zp_settings_content, fragment).commitNowAllowingStateLoss());
                    getInstrumentation().waitForIdleSync();
                    onMain(() -> {
                        require(fragment.getView() != null && fragment.getView().isShown(), "settings_section_not_rendered");
                        for (Settings.Setting<?> setting : Settings.all()) {
                            if (!setting.visible || !section.equals(setting.section)) continue;
                            if (Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION.equals(setting.key)) continue;
                            androidx.preference.Preference preference = fragment.findPreference(setting.key);
                            require(preference != null && preference.getTitle() != null
                                    && preference.getTitle().length() > 0, "visible_setting_control_missing:" + setting.key);
                        }
                    });
                }
                NotificationFilterActivity.FilterFragment filter = new NotificationFilterActivity.FilterFragment();
                onMain(() -> activity.getSupportFragmentManager().beginTransaction()
                        .replace(R.id.zp_settings_content, filter).commitNowAllowingStateLoss());
                getInstrumentation().waitForIdleSync();
                onMain(() -> require(filter.getView() != null && filter.getView().isShown()
                        && filter.findPreference(Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION) != null,
                        "notification_retention_control_missing"));
            } finally {
                getInstrumentation().runOnMainSync(activity::finish);
            }
        });
    }

    private void navigation() throws Exception {
        check("navigation.bottom_tabs", "native_toggle_roundtrip", "host_ui", () -> {
            profile(false, false, false, false);
            require(tab("message") && tab("contact") && tab("metab"), "baseline_tabs_missing");
            boolean nativeGroup = tab("groups");
            profile(true, true, true, false);
            compactTabs();
            clickId("maintab_groups");
            require(waitFor(() -> activeTab("groups") && visibleId("root_backgroundmain") && visibleId("recycler_view")), "group_page_or_icon_missing");
            preservedPages();
            profile(true, true, false, false);
            require(tab("groups") == nativeGroup, "keep_group_off_does_not_restore_native_list");
            profile(false, false, false, false);
            require(tab("groups") == nativeGroup, "native_group_roundtrip_changed");
            require(tab("discovery") && tab("timeline"), "native_optional_tabs_missing");
        });
        check("navigation.bottom_tabs", "account_disabled_native_group", "host_ui", () -> {
            profile(false, false, false, false);
            if (tab("groups")) throw new Blocked("account_already_has_native_group");
            profile(true, true, true, false);
            compactTabs();
            clickId("maintab_groups");
            require(waitFor(() -> activeTab("groups") && visibleId("root_backgroundmain") && visibleId("recycler_view")), "native_group_destination_missing");
            preservedPages();
        });
        check("navigation.force_messages_home", "launch_messages_preserves_group", "host_ui", () -> {
            profile(true, true, true, true);
            compactTabs();
            require(waitFor(this::messagePage), "launch_did_not_select_messages");
            clickId("maintab_groups");
            require(waitFor(this::groupPage), "group_tap_redirected_to_messages");
            SystemClock.sleep(750);
            require(groupPage(), "late_launch_callback_overrode_group");
            // A normal launcher reopen must select Messages once from an actual
            // non-Message page. The following Group tap must still work.
            shell("input keyevent KEYCODE_HOME");
            shell("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                    + " -f 0x10200000 -n com.zing.zalo/com.zing.zalo.ui.ZaloLauncherActivity");
            require(waitFor(this::messagePage), "warm_launcher_did_not_select_messages");
            clickId("maintab_groups");
            require(waitFor(this::groupPage), "group_after_warm_launch_redirected");
            SystemClock.sleep(750);
            require(groupPage(), "late_warm_callback_overrode_group");
            // A targeted intent must cancel launch recovery and keep its native
            // destination. This local fixture identifies only a native tab.
            shell("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                    + " --ei TAB_ID 2 -n com.zing.zalo/com.zing.zalo.ui.ZaloLauncherActivity");
            SystemClock.sleep(750);
            require(groupPage(), "routed_intent_redirected_to_messages");
            preservedPages();
            restart();
            require(waitFor(this::messagePage), "cold_restart_did_not_select_messages");
        });
    }

    private void profile(boolean discovery, boolean timeline, boolean group, boolean home) throws Exception {
        set(Tweaks.KEY_HIDE_DISCOVERY_TAB, discovery);
        set(Tweaks.KEY_HIDE_TIMELINE_TAB, timeline);
        set(Tweaks.KEY_KEEP_GROUP_TAB, group);
        set(Tweaks.KEY_FORCE_MESSAGES_AS_HOME, home);
        restart();
        require(waitFor(() -> tab("message")), "host_main_page_unavailable");
    }

    private void compactTabs() throws Exception {
        List<String> actual = new ArrayList<>();
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        if (root != null) {
            collectTabs(root, actual);
            root.recycle();
        }
        require(actual.equals(Arrays.asList("message", "contact", "groups", "metab")), "compact_tab_order_or_count_wrong");
    }

    private void collectTabs(AccessibilityNodeInfo node, List<String> tabs) {
        String id = node.getViewIdResourceName();
        if (node.isVisibleToUser() && id != null && id.startsWith(HOST + "maintab_")) {
            String name = id.substring((HOST + "maintab_").length());
            if (Arrays.asList("message", "contact", "groups", "metab", "discovery", "timeline").contains(name)) tabs.add(name);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) { collectTabs(child, tabs); child.recycle(); }
        }
    }

    private void preservedPages() throws Exception {
        clickId("maintab_contact");
        require(waitFor(() -> activeTab("contact") && (visibleId("contactlisttab") || visibleId("contact_list") || visibleId("recycler_contacts"))), "contacts_redirected");
        clickId("maintab_metab");
        require(waitFor(() -> mePage()), "me_redirected");
        clickId("maintab_message");
        require(waitFor(this::messagePage), "messages_roundtrip_failed");
    }

    private void inbox() throws Exception {
        check("inbox.category_chips", "visible_chip_navigation", "host_ui", () -> {
            set(Tweaks.KEY_CATEGORY_GROUPS, true);
            set(Tweaks.KEY_CATEGORY_OA, true);
            set(Tweaks.KEY_CATEGORY_STRANGERS, true);
            set(Tweaks.KEY_FILTER_POPOVER_CATEGORIES, true);
            SettingsStore.putInt(context, Tweaks.KEY_DEFAULT_INBOX_FILTER, 0);
            restart();
            require(waitFor(this::messagePage), "inbox_unavailable");
            for (String label : new String[]{"All", "Chats", "Groups", "OA"}) {
                clickText(label);
                require(waitFor(this::messagePage), "chip_left_messages_page");
                require(waitFor(() -> selectedChip(label)), "chip_selection_not_applied");
            }
            clickText("All");
            require(waitFor(() -> selectedChip("All")), "all_chip_reset_failed");
            // This certifies clickable UI and destination only; row membership has a separate case.
        });
        check("inbox.category_chips", "strangers_native_roundtrip", "host_ui", () -> {
            if (!visibleText("Strangers")) throw new Blocked("native_strangers_route_unavailable");
            clickText("Strangers");
            require(waitFor(() -> visibleId("contactlist") && visibleId("multi_state")
                    && !visibleId("maintab_message")), "strangers_destination_not_opened");
            back();
            require(waitFor(this::messagePage), "strangers_back_did_not_restore_inbox");
        });
        check("inbox.category_chips", "controlled_row_membership", "host_ui", this::controlledRowMembership);
        check("inbox.category_chips", "strangers_fixture_membership", "host_ui", () -> {
            if (inboxFixtures == null) throw new Blocked("fold_fixture_manifest_missing");
            clickText("Strangers");
            try {
                require(waitFor(() -> visibleId("contactlist") && visibleId("multi_state")
                        && !visibleId("maintab_message")), "stranger_fixture_destination_missing");
                HashSet<String> expected = arraySet(inboxFixtures.getJSONObject("fixtures").getJSONArray("stranger"));
                HashSet<String> displayed = arraySet(inboxSnapshot("stranger").getJSONArray("displayed"));
                if (expected.isEmpty()) {
                    if (displayed.isEmpty()) throw new Blocked("native_stranger_empty_verified_positive_fixture_missing");
                    throw new Blocked("stranger_rows_need_independent_labels");
                }
                require(displayed.containsAll(expected), "native_stranger_fixture_missing");
                for (String category : new String[]{"dm", "group", "oa"}) {
                    for (String identity : arraySet(inboxFixtures.getJSONObject("fixtures").getJSONArray(category))) {
                        require(!displayed.contains(identity), "native_stranger_contains_wrong_fixture:" + category);
                    }
                }
            } finally {
                back();
                require(waitFor(this::messagePage), "stranger_fixture_back_failed");
            }
        });
        check("inbox.category_chips", "default_and_toggle_roundtrip", "host_ui", () -> {
            SettingsStore.putInt(context, Tweaks.KEY_DEFAULT_INBOX_FILTER, 1);
            restart();
            require(waitFor(() -> selectedChip("Chats")), "default_chats_not_selected");
            clickText("All");
            require(selectedChip("All"), "session_all_not_selected");
            restart();
            require(waitFor(() -> selectedChip("Chats")), "session_selection_persisted_instead_of_default");
            set(Tweaks.KEY_CATEGORY_GROUPS, false);
            set(Tweaks.KEY_CATEGORY_OA, false);
            set(Tweaks.KEY_CATEGORY_STRANGERS, false);
            SettingsStore.putInt(context, Tweaks.KEY_DEFAULT_INBOX_FILTER, 2);
            restart();
            require(!visibleText("Groups") && !visibleText("OA") && !visibleText("Strangers"), "disabled_chips_visible");
            require(selectedChip("All"), "disabled_default_category_not_reset");
        });
    }

    private void controlledRowMembership() throws Exception {
        if (inboxFixtures == null) throw new Blocked("fold_fixture_manifest_missing");
        JSONObject fixtures = inboxFixtures.getJSONObject("fixtures");
        HashSet<String> labelled = new HashSet<>();
        for (String category : new String[]{"dm", "group", "oa"}) {
            JSONArray values = fixtures.optJSONArray(category);
            if (values == null || values.length() == 0) throw new Blocked("verified_fixture_missing:" + category);
            for (String identity : arraySet(values)) require(labelled.add(identity), "fixture_labels_overlap");
        }
        set(Tweaks.KEY_CATEGORY_GROUPS, true);
        set(Tweaks.KEY_CATEGORY_OA, true);
        set(Tweaks.KEY_CATEGORY_STRANGERS, true);
        set(Tweaks.KEY_FILTER_POPOVER_CATEGORIES, true);
        SettingsStore.putInt(context, Tweaks.KEY_DEFAULT_INBOX_FILTER, 0);
        root("setprop debug.zalopatch 1");
        restart();
        require(waitFor(this::messagePage), "fixture_inbox_unavailable");
        clickText("All");
        JSONObject baseline = inboxSnapshot();
        require(arraySet(baseline.getJSONArray("source")).containsAll(labelled), "labelled_fixture_not_in_source");
        require(arraySet(baseline.getJSONArray("displayed")).containsAll(labelled), "all_excludes_labelled_fixture");
        for (String[] selection : new String[][]{{"Chats", "dm", "normal"}, {"Groups", "group", "groups"}, {"OA", "oa", "oa"}}) {
            clickText(selection[0]);
            require(waitFor(() -> selectedChip(selection[0])), "fixture_chip_not_selected");
            JSONObject observed = inboxSnapshot();
            require(selection[2].equals(observed.getString("category")), "native_snapshot_category_mismatch");
            HashSet<String> displayed = arraySet(observed.getJSONArray("displayed"));
            for (String category : new String[]{"dm", "group", "oa"}) {
                for (String identity : arraySet(fixtures.getJSONArray(category))) {
                    require(displayed.contains(identity) == selection[1].equals(category),
                            "fixture_membership_wrong:" + selection[0] + ":" + category);
                }
            }
        }
        clickText("All");
        require(arraySet(inboxSnapshot().getJSONArray("displayed")).containsAll(labelled), "all_did_not_restore_fixtures");
    }

    private JSONObject inboxSnapshot() throws Exception { return inboxSnapshot("inbox"); }

    private JSONObject inboxSnapshot(String surface) throws Exception {
        String request = java.util.UUID.randomUUID().toString();
        String target = "e2e:inbox:" + runId + ":" + request;
        shell("am broadcast -a com.ez.zalopatch.behave.INBOX_E2E -p com.zing.zalo"
                + " --es run_id " + runId + " --es request_id " + request
                + " --es salt " + inboxFixtures.getString("salt") + " --es surface " + surface);
        JSONObject result = hostProbeResult("inbox.e2e", target, 10000);
        if (result.has("blocked")) throw new Blocked(result.getString("blocked"));
        require(result.optBoolean("visible_adapter"), "fixture_adapter_not_visible");
        return result;
    }

    private JSONObject hostProbeResult(String feature, String target, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            try (Cursor rows = context.getContentResolver().query(
                    Uri.parse("content://com.ez.zalopatch.config/self_check/" + feature), null, null, null, null)) {
                if (rows != null && rows.moveToFirst()
                        && target.equals(rows.getString(rows.getColumnIndexOrThrow("target")))) {
                    require(!"failed".equals(rows.getString(rows.getColumnIndexOrThrow("status"))), "host_probe_failed:" + feature);
                    String detail = rows.getString(rows.getColumnIndexOrThrow("detail"));
                    int newline = detail.indexOf('\n');
                    return new JSONObject(newline < 0 ? detail : detail.substring(0, newline));
                }
            }
            SystemClock.sleep(100);
        }
        throw new Blocked("host_probe_result_missing:" + feature);
    }

    private void me() throws Exception {
        check("me.cleanup", "documents_preserved", "host_ui", () -> {
            for (String key : new String[]{Tweaks.KEY_HIDE_QR_WALLET, Tweaks.KEY_HIDE_ZCLOUD, Tweaks.KEY_HIDE_ZSTYLE, Tweaks.KEY_HIDE_ZBUSINESS}) set(key, true);
            restart();
            clickId("maintab_metab");
            require(waitFor(this::mePage), "me_page_missing");
            String documents = visibleText("My Documents") ? "My Documents" : "Cloud của tôi";
            if (!visibleText(documents)) throw new Blocked("documents_accessibility_card_unavailable");
            clickText(documents);
            require(waitFor(() -> visibleId("chatinputbar") || visibleId("chatinput_text")), "documents_chat_not_opened");
            back();
        });
        check("me.cleanup", "rendered_zstyle", "host_ui", () -> {
            for (String key : new String[]{Tweaks.KEY_HIDE_QR_WALLET, Tweaks.KEY_HIDE_ZCLOUD,
                    Tweaks.KEY_HIDE_ZSTYLE, Tweaks.KEY_HIDE_ZBUSINESS}) set(key, false);
            root("setprop debug.zalopatch 1");
            root("setprop debug.zalopatch.e2e_run " + runId);
            restart();
            clickId("maintab_metab");
            require(waitFor(this::mePage), "baseline_me_missing");
            JSONObject baseline = meSnapshot();
            if (!baseline.optBoolean("zstyle_marker")) throw new Blocked("native_zstyle_baseline_unavailable");
            require(!baseline.optBoolean("hide_zstyle") && baseline.optInt("visibility", -1) == 0
                    && baseline.optInt("height", 0) != 0 && baseline.optInt("measured_height", 0) > 0
                    && baseline.optBoolean("shown"), "native_zstyle_baseline_not_visible");
            set(Tweaks.KEY_HIDE_ZSTYLE, true);
            restart();
            clickId("maintab_metab");
            require(waitFor(this::mePage), "cleanup_me_missing");
            JSONObject hidden = meSnapshot();
            if (!hidden.optBoolean("zstyle_marker")) throw new Blocked("native_zstyle_current_binding_unavailable");
            require(hidden.optBoolean("hide_zstyle") && hidden.optBoolean("collapsed")
                    && hidden.optBoolean("attached") && hidden.optInt("height", -1) == 0
                    && hidden.optInt("visibility", -1) == 8,
                    "native_zstyle_geometry_not_collapsed");
        });
        final JSONObject[] lifecycle = {null};
        check("me.cleanup", "template_a_b_a", "host_ui", () -> {
            lifecycle[0] = meE2e("template_a_b_a", 15000);
            if (!lifecycle[0].optString("lifecycle_blocker").isEmpty()) throw new Blocked(lifecycle[0].getString("lifecycle_blocker"));
            require(lifecycle[0].optBoolean("lifecycle_complete") && lifecycle[0].optBoolean("lifecycle_passed"),
                    "native_template_lifecycle_failed:" + lifecycle[0].optString("lifecycle_error"));
            JSONObject evidence = lifecycle[0].getJSONObject("evidence");
            require(evidence.optBoolean("same_wrapper") && evidence.optBoolean("direct_a_to_b_verified")
                    && evidence.getInt("native_visibility") == evidence.getInt("restored_visibility")
                    && evidence.getInt("native_height") == evidence.getInt("restored_height")
                    && evidence.getInt("native_height") != 0 && evidence.getInt("b_measured_height") > 0,
                    "direct_native_restoration_not_verified");
        });
        check("me.cleanup", "late_callback", "host_ui", () -> {
            if (lifecycle[0] == null) throw new Blocked("native_lifecycle_driver_unavailable");
            if (!lifecycle[0].optString("lifecycle_blocker").isEmpty()) throw new Blocked(lifecycle[0].getString("lifecycle_blocker"));
            JSONObject evidence = lifecycle[0].optJSONObject("evidence");
            require(evidence != null && evidence.optBoolean("stale_callback_rejected")
                    && evidence.optBoolean("current_callback_applied"), "native_callback_order_not_verified");
        });
        meE2e("cancel", 10000);
    }

    private JSONObject meSnapshot() throws Exception {
        JSONObject observed = null;
        long deadline = SystemClock.elapsedRealtime() + 10000;
        do {
            observed = meE2e("snapshot", 10000);
            if (observed.optBoolean("zstyle_marker")) return observed;
            SystemClock.sleep(250);
        } while (SystemClock.elapsedRealtime() < deadline);
        return observed;
    }

    private JSONObject meE2e(String phase, long timeout) throws Exception {
        shell("am broadcast -a com.ez.zalopatch.behave.ME_E2E -p com.zing.zalo --es run_id " + runId
                + " --es phase " + phase);
        return hostProbeResult("me_cleanup.e2e", "e2e:me:" + runId + ":" + phase, timeout);
    }

    private void notifications() throws Exception {
        check("notifications.promo_filter", "os_posting_filter_off", "host_os", () -> notificationCase(false));
        check("notifications.promo_filter", "os_posting_filter_on", "host_os", () -> notificationCase(true));
    }

    private void notificationCase(boolean enabled) throws Exception {
        set(Tweaks.KEY_RECORD_NOTIFICATION_HISTORY, false);
        set(Tweaks.KEY_AUTO_RECORD_CALLS, false);
        set(Tweaks.KEY_HIDE_PROMO_NOTIFICATIONS, enabled);
        require(NotificationRuleStore.save(context, NotificationRuleStore.RuleSet.empty()), "rule_fixture_profile_failed");
        root("setprop debug.zalopatch 1");
        restart();
        String fixtureId = runId + (enabled ? "-on" : "-off");
        shell("am broadcast -a com.ez.zalopatch.behave.NOTIFICATION_E2E -p com.zing.zalo --es run_id " + fixtureId);
        JSONObject observed = null;
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < deadline) {
            try (Cursor rows = context.getContentResolver().query(Uri.parse("content://com.ez.zalopatch.config/self_check/notifications.observer"), null, null, null, null)) {
                if (rows != null && rows.moveToFirst() && ("e2e:notifications:" + fixtureId).equals(rows.getString(rows.getColumnIndexOrThrow("target")))) {
                    observed = new JSONObject(rows.getString(rows.getColumnIndexOrThrow("detail")));
                    break;
                }
            }
            SystemClock.sleep(250);
        }
        if (observed == null) throw new Blocked("notification_fixture_result_missing");
        require(fixtureId.equals(observed.optString("run_id")), "fixture_run_mismatch");
        require(observed.optBoolean("cleanup_ok") && observed.optBoolean("cleanup_verified"), "notification_cleanup_failed");
        if (!observed.isNull("error")) throw new Blocked(observed.optString("error", "notification_fixture_unavailable"));
        require(observed.optBoolean("stable") && observed.optBoolean("notifications_enabled"), "notification_snapshot_not_stable");
        require(arraySet(observed.getJSONArray("attempted")).equals(new HashSet<>(Arrays.asList("dm", "group", "promo", "call", "backup"))), "fixture_attempts_incomplete");
        HashSet<String> expected = new HashSet<>(Arrays.asList("dm", "group", "call", "backup"));
        if (!enabled) expected.add("promo");
        require(arraySet(observed.getJSONArray("active")).equals(expected), "os_notification_outcome_wrong");
    }

    private static HashSet<String> arraySet(JSONArray array) throws Exception {
        HashSet<String> values = new HashSet<>();
        for (int i = 0; i < array.length(); i++) values.add(array.getString(i));
        return values;
    }

    private void check(String feature, String scenario, String layer, Check check) throws Exception {
        try {
            check.run();
            emit(feature, scenario, "passed", layer, "observed_expected_outcome");
        } catch (Blocked blocked) {
            emit(feature, scenario, "blocked", layer, blocked.getMessage());
        } catch (AssertionError failure) {
            emit(feature, scenario, "failed", layer, failure.getMessage());
        } catch (Exception failure) {
            emit(feature, scenario, "failed", layer, "exception_" + failure.getClass().getSimpleName());
        }
    }

    private void emit(String feature, String scenario, String status, String layer, String detail) throws Exception {
        JSONObject value = new JSONObject();
        value.put("run_id", runId).put("feature_id", feature).put("scenario_id", scenario)
                .put("status", status).put("evidence_layer", layer).put("detail", detail);
        if ("failed".equals(status)) {
            java.util.TreeSet<String> ids = new java.util.TreeSet<>();
            AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            if (root != null) { collectIds(root, ids); root.recycle(); }
            value.put("visible_ids", new JSONArray(ids));
        }
        Bundle result = new Bundle();
        result.putString("e2e_case", value.toString());
        getInstrumentation().sendStatus(2, result);
    }

    private void set(String key, boolean value) {
        SettingsStore.putBoolean(context, key, value);
        SettingsChanges.markChanged(context, key);
    }
    private void collectIds(AccessibilityNodeInfo node, java.util.Set<String> ids) {
        String id = node.getViewIdResourceName();
        if (node.isVisibleToUser() && id != null && id.startsWith(HOST)) ids.add(id.substring(HOST.length()));
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) { collectIds(child, ids); child.recycle(); }
        }
    }

    private void restart() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        ZaloRestart.Result[] result = new ZaloRestart.Result[1];
        ZaloRestart.run(context, ZaloRestart.Target.single(0), value -> { result[0] = value; done.countDown(); });
        if (!done.await(45, TimeUnit.SECONDS) || result[0] != ZaloRestart.Result.SENT) throw new Blocked("restart_or_root_unavailable");
        if (!waitFor(() -> visibleId("maintab_message"))) throw new Blocked("host_main_ui_unavailable");
    }

    private boolean waitFor(Condition condition) {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        do {
            if (condition.matches()) return true;
            SystemClock.sleep(200);
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    private boolean groupPage() { return activeTab("groups") && visibleId("root_backgroundmain") && visibleId("recycler_view"); }
    private boolean mePage() { return activeTab("metab") && visibleId("root_backgroundmain") && visibleId("recyclerView"); }
    private boolean messagePage() { return activeTab("message") && visibleId("recycler_view_msgList"); }
    private boolean tab(String name) { return visibleId("maintab_" + name); }
    private boolean activeTab(String name) {
        AccessibilityNodeInfo node = find(HOST + "maintab_" + name, null);
        if (node == null) return false;
        boolean active = descendantId(node, HOST + "iconActive");
        node.recycle();
        return active;
    }
    private boolean descendantId(AccessibilityNodeInfo node, String id) {
        if (node.isVisibleToUser() && id.equals(node.getViewIdResourceName())) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean matches = descendantId(child, id);
                child.recycle();
                if (matches) return true;
            }
        }
        return false;
    }
    private boolean visibleId(String id) { return exists(HOST + id, null); }
    private boolean visibleText(String text) { return exists(null, text); }
    private boolean selectedChip(String text) {
        AccessibilityNodeInfo node = find(null, text);
        if (node == null) return false;
        boolean selected = node.isSelected();
        node.recycle();
        return selected;
    }
    private boolean exists(String id, String text) {
        AccessibilityNodeInfo node = find(id, text);
        if (node == null) return false;
        node.recycle();
        return true;
    }
    private AccessibilityNodeInfo find(String id, String text) {
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo result = findIn(root, id, text);
        root.recycle();
        return result;
    }
    private AccessibilityNodeInfo findIn(AccessibilityNodeInfo node, String id, String text) {
        if (node.isVisibleToUser() && "com.zing.zalo".contentEquals(node.getPackageName() == null ? "" : node.getPackageName())
                && (id != null ? id.equals(node.getViewIdResourceName()) : text.contentEquals(node.getText() == null ? "" : node.getText()))) {
            return AccessibilityNodeInfo.obtain(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo result = findIn(child, id, text);
                child.recycle();
                if (result != null) return result;
            }
        }
        return null;
    }
    private void clickId(String id) throws Exception { click(HOST + id, null); }
    private void clickText(String text) throws Exception { click(null, text); }
    private void click(String id, String text) throws Exception {
        require(waitFor(() -> exists(id, text)), "expected_control_missing");
        AccessibilityNodeInfo node = find(id, text);
        boolean clicked = false;
        while (node != null) {
            if (node.isClickable()) { clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK); node.recycle(); break; }
            AccessibilityNodeInfo parent = node.getParent();
            node.recycle();
            node = parent;
        }
        require(clicked, "accessibility_click_failed");
        SystemClock.sleep(400);
    }
    private void back() {
        require(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK),
                "accessibility_back_failed");
    }
    private void require(boolean condition, String code) { if (!condition) throw new AssertionError(code); }

    private File snapshotFile() { return new File(context.getFilesDir(), "version-e2e-recovery.json"); }
    private void saveSnapshot() throws Exception {
        JSONObject snapshot = new JSONObject();
        snapshot.put("run_id", runId);
        JSONObject settings = new JSONObject();
        Map<String, ?> current = TweakStore.preferences(context).getAll();
        for (String key : OWNED) settings.put(key, encodeValue(current.get(key)));
        snapshot.put("settings", settings);
        JSONObject pending = new JSONObject();
        for (Map.Entry<String, ?> entry : SettingsChanges.preferences(context).getAll().entrySet()) pending.put(entry.getKey(), encodeValue(entry.getValue()));
        snapshot.put("pending", pending);
        snapshot.put("debug", shell("getprop debug.zalopatch").trim());
        snapshot.put("trace", shell("getprop debug.zalopatch.trace").trim());
        snapshot.put("me_run", shell("getprop debug.zalopatch.e2e_run").trim());
        AtomicFile checkpoint = new AtomicFile(snapshotFile());
        FileOutputStream file = checkpoint.startWrite();
        try {
            file.write(snapshot.toString().getBytes(StandardCharsets.UTF_8));
            checkpoint.finishWrite(file);
        } catch (Exception failure) {
            checkpoint.failWrite(file);
            throw failure;
        }
    }
    private static JSONObject encodeValue(Object value) throws Exception {
        JSONObject out = new JSONObject();
        String type = value == null ? "absent" : value instanceof Boolean ? "boolean" : value instanceof Integer ? "int"
                : value instanceof Long ? "long" : value instanceof java.util.Set ? "set" : "string";
        out.put("type", type).put("value", value instanceof java.util.Set ? new JSONArray((java.util.Set<?>) value) : value);
        return out;
    }
    private static void putValue(SharedPreferences.Editor editor, String key, JSONObject value) throws Exception {
        switch (value.getString("type")) {
            case "absent": editor.remove(key); break;
            case "boolean": editor.putBoolean(key, value.getBoolean("value")); break;
            case "int": editor.putInt(key, value.getInt("value")); break;
            case "long": editor.putLong(key, value.getLong("value")); break;
            case "set": editor.putStringSet(key, arraySet(value.getJSONArray("value"))); break;
            case "string": editor.putString(key, value.getString("value")); break;
            default: throw new IllegalStateException("unsupported_snapshot_type");
        }
    }
    private void restore() throws Exception {
        JSONObject snapshot;
        try (FileInputStream input = new AtomicFile(snapshotFile()).openRead()) {
            snapshot = new JSONObject(readUtf8(input));
        }
        if (!runId.equals(snapshot.getString("run_id"))) throw new Blocked("recovery_identity_mismatch");
        SharedPreferences prefs = TweakStore.preferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        JSONObject original = snapshot.getJSONObject("settings");
        for (String key : OWNED) putValue(editor, key, original.getJSONObject(key));
        require(editor.commit(), "settings_restore_commit_failed");
        // Safety gates were off at entry. Preserve absent properties as the equivalent empty value.
        for (String name : new String[]{"debug", "trace"}) {
            String value = snapshot.getString(name);
            if (!value.isEmpty() && !"0".equals(value)) throw new Blocked("unsafe_original_debug_state");
            root("setprop debug.zalopatch" + ("trace".equals(name) ? ".trace" : "") + " '" + value + "'");
        }
        String meRun = snapshot.optString("me_run", "");
        if (!meRun.isEmpty() && !"0".equals(meRun)) throw new Blocked("unsafe_original_fixture_state");
        root("setprop debug.zalopatch.e2e_run '" + meRun + "'");
        restart();
        // The production sync initializes missing defaults. Preserve original absence locally
        // after mirroring its effective default into the restarted host.
        editor = prefs.edit();
        for (String key : OWNED) {
            if ("absent".equals(original.getJSONObject(key).getString("type"))) editor.remove(key);
        }
        require(editor.commit(), "absent_setting_restore_failed");
        SharedPreferences pending = SettingsChanges.preferences(context);
        SharedPreferences.Editor state = pending.edit().clear();
        JSONObject originalPending = snapshot.getJSONObject("pending");
        java.util.Iterator<String> keys = originalPending.keys();
        while (keys.hasNext()) { String key = keys.next(); putValue(state, key, originalPending.getJSONObject(key)); }
        require(state.commit(), "pending_state_restore_failed");
        Map<String, ?> restored = prefs.getAll();
        for (String key : OWNED) require(encodeValue(restored.get(key)).toString().equals(original.getJSONObject(key).toString()), "owned_setting_restore_mismatch");
        keys = originalPending.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            require(sameValue(pending.getAll().get(key), originalPending.getJSONObject(key)), "pending_restore_mismatch");
        }
        require(pending.getAll().size() == originalPending.length(), "pending_restore_extra_values");
        require(shell("getprop debug.zalopatch").trim().equals(snapshot.getString("debug")), "debug_restore_mismatch");
        require(shell("getprop debug.zalopatch.trace").trim().equals(snapshot.getString("trace")), "trace_restore_mismatch");
        require(shell("getprop debug.zalopatch.e2e_run").trim().equals(meRun), "fixture_gate_restore_mismatch");
        long cleanupDeadline = SystemClock.elapsedRealtime() + 10000;
        boolean journalClear = false;
        while (SystemClock.elapsedRealtime() < cleanupDeadline) {
            String path = "/data/user/0/com.zing.zalo/shared_prefs/com.ez.zalopatch.notification_e2e.xml";
            String probe = "if [ ! -e " + path + " ]; then echo JOURNAL_CLEAR; else grep -q 'name=\"orphan\"' "
                    + path + "; probe=$?; if [ $probe -eq 1 ]; then echo JOURNAL_CLEAR; fi; fi";
            journalClear = rootOutput(probe).contains("JOURNAL_CLEAR");
            if (journalClear) break;
            SystemClock.sleep(250);
        }
        require(journalClear, "notification_orphan_cleanup_unverified");
        require(snapshotFile().delete(), "recovery_snapshot_delete_failed");
    }
    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = ui.executeShellCommand(command);
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
            return readUtf8(input);
        }
    }
    private static String readUtf8(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toString("UTF-8");
    }
    private static boolean sameValue(Object actual, JSONObject expected) throws Exception {
        if ("set".equals(expected.getString("type"))) return arraySet(expected.getJSONArray("value")).equals(actual);
        return encodeValue(actual).toString().equals(expected.toString());
    }
    private void root(String command) throws Exception {
        rootOutput(command);
    }
    private String rootOutput(String command) throws Exception {
        DiagnosticRootProcessRunner.Result result = new DiagnosticRootProcessRunner().run(command, 15000);
        if (!result.successful()) throw new Blocked("root_command_failed");
        return result.output;
    }
    private void onMain(Runnable action) throws Exception {
        Throwable[] failure = new Throwable[1];
        getInstrumentation().runOnMainSync(() -> {
            try { action.run(); } catch (Throwable caught) { failure[0] = caught; }
        });
        if (failure[0] instanceof AssertionError) throw (AssertionError) failure[0];
        if (failure[0] != null) throw new Exception("main_thread_check_failed", failure[0]);
    }
}
