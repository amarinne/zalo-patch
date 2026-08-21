package com.ez.zalopatch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import android.view.Gravity;

import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.transition.Slide;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class StatusActivity extends ZpSettingsActivity {
    private static final String TAG = "ZaloPatch";
    static final String EXTRA_ROUTE = "settings.route";
    static final String ROUTE_DASHBOARD = "dashboard";
    static final String ROUTE_SECTION = "section";
    static final String ROUTE_SELF_CHECK = "self_check";
    static final String ROUTE_FILTER = "notification_filter";
    static final String ROUTE_HISTORY = "notification_history";
    static final String ROUTE_RULES = "notification_rules";
    static final String ROUTE_RECORDINGS = "call_recordings";
    static final String ROUTE_BACKUP = "settings_backup";
    static final String ROUTE_DIAGNOSTICS = "diagnostics";
    static final String INTERNAL_RESTART = "internal.restart_zalo";
    static final String INTERNAL_ROOT_ACCESS = "internal.root_access";
    static final String INTERNAL_ZALO_APP_INFO = "internal.zalo_app_info";
    private static final String ARG_PAGE_TITLE = "settings.page_title";

    private static final int REQUEST_SETTINGS_EXPORT = 2001;
    private static final int REQUEST_SETTINGS_IMPORT = 2002;
    private static final int REQUEST_HISTORY_EXPORT = 3001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSupportFragmentManager().addOnBackStackChangedListener(this::updateChrome);
        if (savedInstanceState == null) {
            openInitialRoute(getIntent());
        } else {
            updateChrome();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        getSupportFragmentManager().popBackStackImmediate(
                null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
        openInitialRoute(intent);
    }

    static Intent routeIntent(Context context, String route) {
        return new Intent(context, StatusActivity.class)
                .putExtra(EXTRA_ROUTE, route)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    void openPage(Fragment fragment, String title) {
        Bundle args = fragment.getArguments() == null
                ? new Bundle() : new Bundle(fragment.getArguments());
        args.putString(ARG_PAGE_TITLE, title);
        fragment.setArguments(args);
        Fragment current = getSupportFragmentManager()
                .findFragmentById(R.id.zp_settings_content);
        // Plain horizontal slide, the conventional settings drill-down. Deliberately not
        // MaterialSharedAxis: that pairs the slide with a fade-through, and fade-through
        // scales the page from 0.92, which reads as a morph on a dense list.
        // The outgoing page owns the start edge, the incoming page owns the end edge, so
        // forward and back are mirror images of each other.
        if (current != null) {
            current.setExitTransition(slide(Gravity.START));
            current.setReenterTransition(slide(Gravity.START));
        }
        fragment.setEnterTransition(slide(Gravity.END));
        fragment.setReturnTransition(slide(Gravity.END));
        getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.zp_settings_content, fragment)
                .addToBackStack(fragment.getClass().getName())
                .commit();
    }

    private static Slide slide(int edge) {
        Slide slide = new Slide(edge);
        slide.setDuration(220L);
        slide.setInterpolator(new FastOutSlowInInterpolator());
        // The toolbar and apply bar are outside the swapped container; only the page moves.
        return slide;
    }

    void exportSettings() {
        Intent intent = createJsonDocument("zalo-patch-settings.json");
        startActivityForResult(intent, REQUEST_SETTINGS_EXPORT);
    }

    void importSettings() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json");
        startActivityForResult(intent, REQUEST_SETTINGS_IMPORT);
    }

    void exportHistory() {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date());
        startActivityForResult(createJsonDocument(
                        "zalo-notification-history-" + timestamp + ".json"),
                REQUEST_HISTORY_EXPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        runDocumentOperation(requestCode, uri);
    }

    @Override
    protected void onRestartResult(ZaloRestart.Result result) {
        Fragment fragment = getSupportFragmentManager()
                .findFragmentById(R.id.zp_settings_content);
        if (fragment instanceof DashboardFragment) {
            ((DashboardFragment) fragment).refresh();
        }
    }

    @Override
    protected void onRestartStateChanged(boolean inFlight) {
        Fragment fragment = getSupportFragmentManager()
                .findFragmentById(R.id.zp_settings_content);
        if (fragment instanceof DashboardFragment) {
            ((DashboardFragment) fragment).setRestartEnabled(!inFlight);
        }
    }

    @Override
    protected void onRootAccessChanged(RootAccess.State state) {
        Fragment fragment = getSupportFragmentManager()
                .findFragmentById(R.id.zp_settings_content);
        if (fragment instanceof DashboardFragment) {
            ((DashboardFragment) fragment).refresh();
        }
    }

    private void openInitialRoute(Intent intent) {
        String route = intent == null ? null : intent.getStringExtra(EXTRA_ROUTE);
        if (route == null || ROUTE_DASHBOARD.equals(route)) {
            showDashboard();
            return;
        }
        showDashboard();
        if (ROUTE_SECTION.equals(route)) {
            String section = intent.getStringExtra(SectionActivity.EXTRA_SECTION);
            String title = intent.getStringExtra(SectionActivity.EXTRA_TITLE);
            if (Tweaks.SECTION_NOTIFICATIONS.equals(section)) {
                openPage(new NotificationFilterActivity.FilterFragment(),
                        getString(R.string.zp_notification_filter_title));
            } else {
                openPage(SectionActivity.SettingsFragment.forSection(section), title);
            }
        } else if (ROUTE_SELF_CHECK.equals(route)) {
            openPage(new SelfCheckActivity.SelfCheckFragment(), getString(R.string.zp_self_check_page_title));
        } else if (ROUTE_FILTER.equals(route)) {
            openPage(new NotificationFilterActivity.FilterFragment(), getString(R.string.zp_notification_filter_title));
        } else if (ROUTE_HISTORY.equals(route)) {
            openPage(NotificationHistoryActivity.HistoryFragment.forBucket(
                    intent.getStringExtra(NotificationHistoryActivity.EXTRA_BUCKET)),
                    getString(R.string.zp_notification_history_title));
        } else if (ROUTE_RULES.equals(route)) {
            NotificationRuleStore.Type type = NotificationRuleStore.Type.fromName(
                    intent.getStringExtra(NotificationRuleListActivity.EXTRA_TYPE));
            if (type != null) {
                openPage(NotificationRuleListActivity.RuleFragment.forType(type), type.title(this));
            }
        } else if (ROUTE_RECORDINGS.equals(route)) {
            openPage(new CallRecordingsActivity.RecordingsFragment(), getString(R.string.zp_call_recordings_title));
        } else if (ROUTE_BACKUP.equals(route)) {
            openPage(new SettingsBackupActivity.BackupFragment(), getString(R.string.zp_backup_title));
        } else if (ROUTE_DIAGNOSTICS.equals(route)) {
            openPage(new DiagnosticReportActivity.ReportFragment(),
                    getString(R.string.zp_diagnostic_title));
        }
    }

    private void showDashboard() {
        DashboardFragment fragment = new DashboardFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PAGE_TITLE, getString(R.string.zp_app_name));
        fragment.setArguments(args);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.zp_settings_content, fragment)
                .commitNow();
        updateChrome();
    }

    private void updateChrome() {
        int depth = getSupportFragmentManager().getBackStackEntryCount();
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(depth > 0);
        }
        Fragment fragment = getSupportFragmentManager()
                .findFragmentById(R.id.zp_settings_content);
        String title = null;
        if (fragment != null && fragment.getArguments() != null) {
            title = fragment.getArguments().getString(ARG_PAGE_TITLE);
        }
        setTitle(title == null ? getString(R.string.zp_app_name) : title);
    }

    private Intent createJsonDocument(String filename) {
        return new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, filename);
    }

    private void runDocumentOperation(int requestCode, Uri uri) {
        Context context = getApplicationContext();
        new Thread(() -> {
            try {
                if (requestCode == REQUEST_SETTINGS_EXPORT) {
                    writeText(context, uri, SettingsBackup.exportJson(context));
                    showToast(context.getString(R.string.zp_backup_exported), Toast.LENGTH_SHORT);
                } else if (requestCode == REQUEST_SETTINGS_IMPORT) {
                    try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                        if (input == null) {
                            throw new IllegalStateException(
                                    context.getString(R.string.zp_output_unavailable));
                        }
                        // importJson records each imported key with SettingsChanges itself.
                        int count = SettingsBackup.importJson(context, input);
                        showToast(context.getString(R.string.zp_backup_imported, count),
                                Toast.LENGTH_LONG);
                    }
                } else if (requestCode == REQUEST_HISTORY_EXPORT) {
                    writeText(context, uri, new NotificationHistoryStore(context).exportJson());
                    showToast(context.getString(R.string.zp_history_exported), Toast.LENGTH_SHORT);
                }
            } catch (Exception exception) {
                if (HookConfig.isDebugEnabled()) {
                    Log.w(TAG, "Document operation failed request=" + requestCode, exception);
                }
                showToast(context.getString(R.string.zp_operation_failed,
                        documentFailureMessage(context, requestCode, exception)), Toast.LENGTH_LONG);
            }
        }, "settings-document-io").start();
    }

    private static String documentFailureMessage(
            Context context, int requestCode, Exception exception) {
        String detail = exception.getMessage();
        if (exception instanceof IllegalArgumentException) {
            if ("Unsupported backup format".equals(detail)
                    || "Unsupported notification rule format".equals(detail)) {
                return context.getString(R.string.zp_failure_backup_unsupported);
            }
            if ("Missing settings object".equals(detail)
                    || "Missing notification rules object".equals(detail)) {
                return context.getString(R.string.zp_failure_backup_incomplete);
            }
            if (detail != null && detail.startsWith("Unknown setting:")) {
                return context.getString(R.string.zp_failure_backup_unknown_setting);
            }
            if (detail != null && (detail.startsWith("Expected boolean:")
                    || detail.startsWith("Expected integer:")
                    || detail.startsWith("Expected notification rule string"))) {
                return context.getString(R.string.zp_failure_backup_invalid_value);
            }
            return context.getString(R.string.zp_failure_backup_invalid);
        }
        if (exception instanceof org.json.JSONException
                || exception instanceof ClassCastException) {
            return context.getString(R.string.zp_failure_backup_malformed);
        }
        if (exception instanceof SecurityException) {
            return context.getString(R.string.zp_failure_document_access);
        }
        if (exception instanceof IOException) {
            if ("Preference write failed".equals(detail)) {
                return context.getString(R.string.zp_failure_settings_save);
            }
            return context.getString(requestCode == REQUEST_SETTINGS_IMPORT
                    ? R.string.zp_failure_document_read
                    : R.string.zp_failure_document_write);
        }
        if (exception instanceof IllegalStateException) {
            return context.getString(R.string.zp_failure_document_unavailable);
        }
        return context.getString(R.string.zp_failure_unexpected);
    }

    private static void writeText(Context context, Uri uri, String value) throws Exception {
        try (OutputStream output = context.getContentResolver().openOutputStream(uri, "w")) {
            if (output == null) {
                throw new IllegalStateException(context.getString(R.string.zp_output_unavailable));
            }
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void showToast(String message, int duration) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                Toast.makeText(getApplicationContext(), message, duration).show());
    }

    public static final class DashboardFragment extends ZpPreferenceFragment {
        private ZpRowPreference restart;
        private ZpRowPreference filter;
        private ZpRowPreference runtimeEnvironment;
        private ZpRowPreference rootAccess;
        private ZpRowPreference appInfo;
        private boolean restartAvailable = true;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            buildScreen();
        }

        @Override
        public void onResume() {
            super.onResume();
            refresh();
        }

        private StatusActivity host() {
            return (StatusActivity) requireActivity();
        }

        private void buildScreen() {
            Context context = requireContext();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
            addPrimaryActions(screen);
            addInterface(screen);
            addAdsAndNotifications(screen);
            addPrivacy(screen);
            addModule(screen);
            setPreferenceScreen(screen);
            refresh();
        }

        private void addPrimaryActions(PreferenceScreen screen) {
            Context context = requireContext();
            PreferenceCategory category = PreferenceUi.category(screen,
                    getString(R.string.zp_section_status));
            ZpSection section = ZpSection.in(category);
            restart = PreferenceUi.action(context,
                    getString(R.string.zp_restart_title), null);
            restart.setKey(INTERNAL_RESTART);
            restart.setOnPreferenceClickListener(preference -> {
                host().restartZalo();
                return true;
            });
            section.add(restart);
            rootAccess = PreferenceUi.action(context,
                    getString(R.string.zp_root_access_title), null);
            rootAccess.setKey(INTERNAL_ROOT_ACCESS);
            rootAccess.setOnPreferenceClickListener(preference -> {
                host().recheckRootAccess();
                return true;
            });
            section.add(rootAccess);
            appInfo = PreferenceUi.action(context,
                    getString(R.string.zp_open_zalo_app_info),
                    getString(R.string.zp_open_zalo_app_info_summary));
            appInfo.setKey(INTERNAL_ZALO_APP_INFO);
            appInfo.setOnPreferenceClickListener(preference -> {
                host().openZaloAppInfo();
                return true;
            });
            section.add(appInfo);
            runtimeEnvironment = PreferenceUi.info(context,
                    getString(R.string.zp_runtime_environment_title), null);
            section.add(runtimeEnvironment);
        }

        private String ruleCountValue(int total) {
            return total == 1
                    ? getString(R.string.zp_custom_rule_count_one)
                    : getString(R.string.zp_custom_rules_count, total);
        }

        private void addInterface(PreferenceScreen screen) {
            PreferenceCategory category = PreferenceUi.category(screen,
                    getString(R.string.zp_section_interface));
            ZpSection section = ZpSection.in(category);
            addSection(section, Tweaks.SECTION_NAVIGATION, getString(R.string.zp_tabs_title), null);
            addSection(section, Tweaks.SECTION_INBOX, getString(R.string.zp_inbox_title), null);
            addSection(section, Tweaks.SECTION_CHAT, getString(R.string.zp_chats_title), null);
            addSection(section, Tweaks.SECTION_ME, getString(R.string.zp_me_title), null);
        }

        private void addAdsAndNotifications(PreferenceScreen screen) {
            Context context = requireContext();
            PreferenceCategory category = PreferenceUi.category(screen,
                    getString(R.string.zp_section_ads_notifications));
            ZpSection section = ZpSection.in(category);
            addSection(section, Tweaks.SECTION_ADS, getString(R.string.zp_ads_title), null);
            filter = PreferenceUi.nav(context, getString(R.string.zp_notification_filter_title),
                    null);
            filter.setOnPreferenceClickListener(preference -> {
                host().openPage(new NotificationFilterActivity.FilterFragment(),
                        getString(R.string.zp_notification_filter_title));
                return true;
            });
            section.add(filter);
        }

        private void addPrivacy(PreferenceScreen screen) {
            Context context = requireContext();
            PreferenceCategory category = PreferenceUi.category(screen,
                    getString(R.string.zp_section_privacy));
            ZpSection section = ZpSection.in(category);
            section.add(navigationRow(Tweaks.SECTION_TELEMETRY,
                    getString(R.string.zp_telemetry_title), null));
            addSection(section, Tweaks.SECTION_CALLS, getString(R.string.zp_calls_title), null);
        }

        private void addModule(PreferenceScreen screen) {
            Context context = requireContext();
            PreferenceCategory category = PreferenceUi.category(screen,
                    getString(R.string.zp_section_module));
            ZpSection section = ZpSection.in(category);
            ZpListPreference language = new ZpListPreference(context);
            language.setKey(UiSettings.KEY_LANGUAGE);
            language.setTitle(R.string.zp_language_title);
            language.setEntries(R.array.zp_language_entries);
            language.setEntryValues(R.array.zp_language_values);
            language.setValue(UiSettings.language(context));
            language.setSummaryProvider(androidx.preference.ListPreference
                    .SimpleSummaryProvider.getInstance());
            language.setOnPreferenceChangeListener((preference, value) -> {
                String selected = String.valueOf(value);
                language.setValue(selected);
                UiSettings.setLanguage(context, selected);
                return true;
            });
            section.add(language);

            ZpRowPreference backup = PreferenceUi.nav(context,
                    getString(R.string.zp_backup_title), null);
            backup.setOnPreferenceClickListener(preference -> {
                host().openPage(new SettingsBackupActivity.BackupFragment(),
                        getString(R.string.zp_backup_title));
                return true;
            });
            section.add(backup);
            section.add(navigationRow(Tweaks.SECTION_DEVELOPER,
                    getString(R.string.zp_developer_tools_title),
                    getString(R.string.zp_developer_tools_summary)));
        }

        private void addSection(ZpSection section, String sectionKey, String title, String summary) {
            section.add(navigationRow(sectionKey, title, summary));
        }

        private ZpRowPreference navigationRow(String sectionKey, String title, String summary) {
            ZpRowPreference preference = PreferenceUi.nav(requireContext(), title, summary);
            preference.setOnPreferenceClickListener(clicked -> {
                host().openPage(SectionActivity.SettingsFragment.forSection(sectionKey), title);
                return true;
            });
            return preference;
        }

        private void refresh() {
            Context context = getContext();
            if (context == null || restart == null) return;

            RootAccess.State rootState = RootAccess.cached(context);
            restart.setEnabled(restartAvailable && rootState == RootAccess.State.GRANTED);
            restart.setSummary(rootState == RootAccess.State.GRANTED
                    ? null : getString(R.string.zp_restart_root_required_summary));
            restart.refreshStyle();
            if (rootAccess != null) {
                int rootSummary = rootState == RootAccess.State.GRANTED
                        ? R.string.zp_root_access_granted
                        : rootState == RootAccess.State.DENIED
                        ? R.string.zp_root_access_denied
                        : R.string.zp_root_access_absent;
                rootAccess.value(getString(rootSummary));
                rootAccess.refreshStyle();
            }
            if (runtimeEnvironment != null) {
                runtimeEnvironment.value(runtimeEnvironmentSummary(context));
                runtimeEnvironment.refreshStyle();
            }
            if (filter != null) {
                filter.value(ruleCountValue(NotificationRuleStore.load(context).total()));
                filter.refreshStyle();
            }
        }

        private void setRestartEnabled(boolean enabled) {
            restartAvailable = enabled;
            if (restart != null) {
                restart.setEnabled(enabled
                        && RootAccess.cached(requireContext()) == RootAccess.State.GRANTED);
            }
        }

        private String runtimeEnvironmentSummary(Context context) {
            RuntimeEnvironment.Snapshot snapshot = RuntimeEnvironment.current(context);
            if (!snapshot.reported) {
                return getString(R.string.zp_runtime_environment_pending);
            }
            int frameworkRes = snapshot.framework == RuntimeEnvironment.Framework.LSPOSED
                    ? R.string.zp_runtime_framework_lsposed
                    : snapshot.framework == RuntimeEnvironment.Framework.LSPATCH
                    ? R.string.zp_runtime_framework_lspatch
                    : R.string.zp_runtime_framework_unknown;
            return getString(R.string.zp_runtime_environment_summary,
                    getString(frameworkRes), getString(resourceHooksStatusRes(
                            snapshot.resourceHooks)));
        }

        private int resourceHooksStatusRes(RuntimeEnvironment.ResourceHooks status) {
            if (status == RuntimeEnvironment.ResourceHooks.OBSERVED) {
                return R.string.zp_capability_observed;
            }
            if (status == RuntimeEnvironment.ResourceHooks.UNAVAILABLE) {
                return R.string.zp_capability_unavailable;
            }
            return R.string.zp_capability_pending;
        }
    }
}
