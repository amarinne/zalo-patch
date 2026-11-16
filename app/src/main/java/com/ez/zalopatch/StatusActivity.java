package com.ez.zalopatch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.transition.Slide;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    static final String INTERNAL_IDENTITY = "internal.dashboard_identity";
    static final String INTERNAL_INSTANCE = "internal.dashboard_instance";
    static final String INTERNAL_RUNTIME_STATUS = "internal.dashboard_runtime_status";
    static final String INTERNAL_SEARCH = "internal.dashboard_search";
    private static final String ARG_PAGE_TITLE = "settings.page_title";
    private static final String STATE_SELECTED_PACKAGE = "settings.selected_package";
    private static final String STATE_DOCUMENT_PACKAGE = "settings.document_package";

    private static final int REQUEST_SETTINGS_EXPORT = 2001;
    private static final int REQUEST_SETTINGS_IMPORT = 2002;
    private static final int REQUEST_HISTORY_EXPORT = 3001;

    private String selectedPackage = HostPackages.ZALO;
    /** Captured when a document picker opens; selection changes never retarget that picker. */
    String pendingDocumentPackage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        selectedPackage = InstanceSelection.restore(savedInstanceState == null ? null
                : savedInstanceState.getString(STATE_SELECTED_PACKAGE),
                HostPackages.additional(getApplicationContext()));
        pendingDocumentPackage = savedInstanceState == null ? null
                : savedInstanceState.getString(STATE_DOCUMENT_PACKAGE);
        super.onCreate(savedInstanceState);
        getSupportFragmentManager().addOnBackStackChangedListener(this::updateChrome);
        if (savedInstanceState == null) {
            openInitialRoute(getIntent());
        } else {
            updateChrome();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putString(STATE_SELECTED_PACKAGE, selectedPackage);
        if (pendingDocumentPackage != null) {
            outState.putString(STATE_DOCUMENT_PACKAGE, pendingDocumentPackage);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        if (!InstanceSelection.valid(selectedPackage, HostPackages.additional(moduleContext()))) {
            selectedPackage = HostPackages.ZALO;
            rebuildSelectedTarget();
        }
        super.onResume();
    }

    @Override
    Context settingsContext() {
        return HostPackages.scoped(moduleContext(), selectedPackage);
    }

    @Override
    String selectedPackageName() {
        return selectedPackage;
    }

    String selectedInstancePackage() {
        return selectedPackage;
    }

    /** One scoped context captured before work; null when the package is no longer registered. */
    Context targetContext(String packageName) {
        return InstanceSelection.valid(packageName, HostPackages.additional(moduleContext()))
                ? HostPackages.scoped(moduleContext(), packageName) : null;
    }

    void selectInstance(String packageName) {
        if (restartInFlight()
                || !InstanceSelection.valid(packageName, HostPackages.additional(moduleContext()))
                || selectedPackage.equals(packageName)) {
            return;
        }
        selectedPackage = packageName;
        rebuildSelectedTarget();
    }

    void onRegistrationChanged() {
        if (!InstanceSelection.valid(selectedPackage, HostPackages.additional(moduleContext()))) {
            selectedPackage = HostPackages.ZALO;
            rebuildSelectedTarget();
            return;
        }
        Fragment fragment = getSupportFragmentManager()
                .findFragmentById(R.id.zp_settings_content);
        if (fragment instanceof DashboardFragment) {
            ((DashboardFragment) fragment).refresh();
        }
    }

    /**
     * A selection change invalidates every page that captured the previous target. Drop the
     * back stack so a retained listener cannot write the old scope, then rebuild the dashboard
     * and its pending bar from the new scoped context.
     */
    private void rebuildSelectedTarget() {
        // A newly selected scope starts from its own defaults and schema migration, never the
        // original's values. Re-initializing an already used scope is idempotent.
        TweakStore.initialize(settingsContext());
        getSupportFragmentManager().popBackStackImmediate(
                null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
        showDashboard();
        unbindSettingsPreferences();
        bindSettingsPreferences();
        refreshApplyBar(false);
        ZaloArtifactState.schedule(settingsContext());
    }

    void showInstanceDialog() {
        if (restartInFlight()) return;
        Set<String> registered = HostPackages.additional(moduleContext());
        if (registered.isEmpty()) return;
        List<String> candidates = InstanceSelection.candidates(registered);
        PackageManager manager = getPackageManager();
        CharSequence[] labels = new CharSequence[candidates.size()];
        int checked = 0;
        for (int index = 0; index < candidates.size(); index++) {
            String packageName = candidates.get(index);
            boolean installed = InstanceSelection.installed(manager, packageName);
            labels[index] = instanceOption(packageName, installed);
            if (packageName.equals(selectedPackage)) checked = index;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.zp_instance_choose)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    String packageName = candidates.get(which);
                    boolean installed = InstanceSelection.installed(manager, packageName);
                    if (!installed) return;
                    dialog.dismiss();
                    selectInstance(packageName);
                })
                .setNegativeButton(R.string.zp_cancel, null)
                .show();
    }

    private CharSequence instanceOption(String packageName, boolean installed) {
        String identity = HostPackages.ZALO.equals(packageName)
                ? getString(R.string.zp_instance_original_item, packageName) : packageName;
        return installed ? identity
                : getString(R.string.zp_instance_unavailable_option, identity);
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
        openPage(fragment, title, null);
    }

    void openPage(Fragment fragment, String title, String focusKey) {
        Bundle args = fragment.getArguments() == null
                ? new Bundle() : new Bundle(fragment.getArguments());
        args.putString(ARG_PAGE_TITLE, title);
        if (focusKey != null && !focusKey.isEmpty()) {
            args.putString(ZpPreferenceFragment.ARG_FOCUS_KEY, focusKey);
        }
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
        slide.setDuration(UiMotion.PAGE_MS);
        slide.setInterpolator(UiMotion.FAST_OUT_SLOW_IN);
        // The toolbar and apply bar are outside the swapped container; only the page moves.
        return slide;
    }

    void exportSettings() {
        captureDocumentTarget();
        Intent intent = createJsonDocument("zalo-patch-settings.json");
        startActivityForResult(intent, REQUEST_SETTINGS_EXPORT);
    }

    void importSettings() {
        captureDocumentTarget();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json");
        startActivityForResult(intent, REQUEST_SETTINGS_IMPORT);
    }

    void exportHistory() {
        captureDocumentTarget();
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date());
        startActivityForResult(createJsonDocument(
                        "zalo-notification-history-" + timestamp + ".json"),
                REQUEST_HISTORY_EXPORT);
    }

    /** Called before a picker opens so recreation or a later selection cannot retarget it. */
    void captureDocumentTarget() {
        pendingDocumentPackage = selectedPackage;
    }

    String pendingDocumentPackage() {
        return pendingDocumentPackage;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        String capturedPackage = pendingDocumentPackage;
        pendingDocumentPackage = null;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        runDocumentOperation(requestCode, uri, capturedPackage);
    }

    @Override
    protected void onRestartResult(ZaloRestart.Result result) {
        Fragment fragment = getSupportFragmentManager()
                .findFragmentById(R.id.zp_settings_content);
        if (fragment instanceof DashboardFragment) {
            ((DashboardFragment) fragment).refresh();
        } else if (fragment instanceof SectionActivity.SettingsFragment) {
            ((SectionActivity.SettingsFragment) fragment).refreshDeveloperEnvironment();
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
        } else if (fragment instanceof SectionActivity.SettingsFragment) {
            ((SectionActivity.SettingsFragment) fragment).refreshDeveloperEnvironment();
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

    private void runDocumentOperation(int requestCode, Uri uri, String capturedPackage) {
        Context context = targetContext(capturedPackage);
        if (context == null) {
            showToast(getString(R.string.zp_operation_failed,
                    getString(R.string.zp_failure_document_unavailable)), Toast.LENGTH_LONG);
            return;
        }
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
                    try (NotificationHistoryStore history = new NotificationHistoryStore(context);
                            OutputStream output = context.getContentResolver().openOutputStream(uri, "w")) {
                        if (output == null) {
                            throw new IllegalStateException(
                                    context.getString(R.string.zp_output_unavailable));
                        }
                        try (Writer writer = new BufferedWriter(
                                new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
                            history.exportJson(writer);
                        }
                    }
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
        private ZpRowPreference identity;
        private ZpRowPreference instance;
        private ZpRowPreference runtimeStatus;
        private ZpRowPreference restart;
        private ZpRowPreference filter;
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
            addStatus(screen);
            addTweaks(screen);
            addModule(screen);
            setPreferenceScreen(screen);
            refresh();
        }

        private void addStatus(PreferenceScreen screen) {
            Context context = requireContext();
            PreferenceCategory category = PreferenceUi.category(screen,
                    getString(R.string.zp_section_status));
            ZpSection section = ZpSection.in(category);
            identity = PreferenceUi.info(context,
                    getString(R.string.zp_about_version, BuildConfig.VERSION_NAME), null);
            identity.setKey(INTERNAL_IDENTITY);
            section.add(identity);

            instance = PreferenceUi.nav(context,
                    getString(R.string.zp_instance_title), null);
            instance.setKey(INTERNAL_INSTANCE);
            instance.setOnPreferenceClickListener(preference -> {
                host().showInstanceDialog();
                return true;
            });
            section.add(instance);

            runtimeStatus = PreferenceUi.nav(context,
                    getString(R.string.zp_runtime_status_title), null);
            runtimeStatus.setIcon(R.drawable.ic_zp_heart_pulse);
            runtimeStatus.setKey(INTERNAL_RUNTIME_STATUS);
            runtimeStatus.setOnPreferenceClickListener(preference -> {
                host().openPage(new SelfCheckActivity.SelfCheckFragment(),
                        getString(R.string.zp_self_check_page_title));
                return true;
            });
            section.add(runtimeStatus);

            restart = PreferenceUi.action(context,
                    getString(R.string.zp_restart_title), null);
            restart.setIcon(R.drawable.ic_zp_refresh_cw);
            restart.setKey(INTERNAL_RESTART);
            restart.setOnPreferenceClickListener(preference -> {
                host().restartOrOpenZaloAppInfo();
                return true;
            });
            section.add(restart);
        }

        private String ruleCountValue(int total) {
            return total == 1
                    ? getString(R.string.zp_custom_rule_count_one)
                    : getString(R.string.zp_custom_rules_count, total);
        }

        private void addTweaks(PreferenceScreen screen) {
            Context context = requireContext();
            PreferenceCategory category = PreferenceUi.category(screen,
                    getString(R.string.zp_section_tweaks));
            ZpSection section = ZpSection.in(category);
            Preference search = new SearchEntryPreference(context);
            search.setTitle(R.string.zp_search_title);
            search.setIcon(R.drawable.ic_zp_search);
            search.setKey(INTERNAL_SEARCH);
            search.setOnPreferenceClickListener(preference -> {
                host().openPage(new SearchFragment(), getString(R.string.zp_search_title));
                return true;
            });
            category.addPreference(search);

            addSection(section, Tweaks.SECTION_NAVIGATION,
                    getString(R.string.zp_tabs_title), null,
                    R.drawable.ic_zp_panel_bottom);
            addSection(section, Tweaks.SECTION_INBOX,
                    getString(R.string.zp_inbox_title), null,
                    R.drawable.ic_zp_inbox);
            addSection(section, Tweaks.SECTION_CHAT,
                    getString(R.string.zp_chats_title), null,
                    R.drawable.ic_zp_messages_square);
            addSection(section, Tweaks.SECTION_ME,
                    getString(R.string.zp_me_title), null,
                    R.drawable.ic_zp_circle_user_round);
            addSection(section, Tweaks.SECTION_ADS, getString(R.string.zp_ads_title), null,
                    R.drawable.ic_zp_ad);

            filter = PreferenceUi.nav(context, getString(R.string.zp_notification_filter_title),
                    null);
            filter.setIcon(R.drawable.ic_zp_funnel);
            filter.setOnPreferenceClickListener(preference -> {
                host().openPage(new NotificationFilterActivity.FilterFragment(),
                        getString(R.string.zp_notification_filter_title));
                return true;
            });
            section.add(filter);

            addSection(section, Tweaks.SECTION_TELEMETRY, getString(R.string.zp_telemetry_title),
                    null, R.drawable.ic_zp_eye_off);
            addSection(section, Tweaks.SECTION_CALLS, getString(R.string.zp_calls_title), null,
                    R.drawable.ic_zp_phone);
            addSection(section, Tweaks.SECTION_BACKUP,
                    getString(R.string.zp_backup_push_title), null,
                    R.drawable.ic_zp_database_backup);
            addSection(section, Tweaks.SECTION_SECURITY,
                    getString(R.string.zp_security_title), null,
                    R.drawable.ic_zp_key_round);
        }

        private void addModule(PreferenceScreen screen) {
            Context context = requireContext();
            PreferenceCategory category = PreferenceUi.category(screen,
                    getString(R.string.zp_section_module));
            ZpSection section = ZpSection.in(category);
            ZpListPreference language = new ZpListPreference(context);
            language.setKey(UiSettings.KEY_LANGUAGE);
            language.setTitle(R.string.zp_language_title);
            language.setIcon(R.drawable.ic_zp_languages);
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
            backup.setIcon(R.drawable.ic_zp_save);
            backup.setOnPreferenceClickListener(preference -> {
                host().openPage(new SettingsBackupActivity.BackupFragment(),
                        getString(R.string.zp_backup_title));
                return true;
            });
            section.add(backup);
            section.add(navigationRow(Tweaks.SECTION_DEVELOPER,
                    getString(R.string.zp_developer_tools_title), null,
                    R.drawable.ic_zp_square_terminal));
        }

        private void addSection(ZpSection section, String sectionKey, String title, String summary,
                int iconRes) {
            section.add(navigationRow(sectionKey, title, summary, iconRes));
        }

        private ZpRowPreference navigationRow(String sectionKey, String title, String summary,
                int iconRes) {
            ZpRowPreference preference = PreferenceUi.nav(requireContext(), title, summary);
            if (iconRes != 0) {
                preference.setIcon(iconRes);
            }
            preference.setOnPreferenceClickListener(clicked -> {
                host().openPage(SectionActivity.SettingsFragment.forSection(sectionKey), title);
                return true;
            });
            return preference;
        }

        private void refresh() {
            Context context = getContext();
            if (context == null || restart == null) return;

            Context target = host().settingsContext();
            RootAccess.State rootState = RootAccess.cached(context);
            restart.setEnabled(restartAvailable);
            restart.setSummary(rootState == RootAccess.State.GRANTED
                    ? null : getString(R.string.zp_restart_manual_fallback_summary));
            restart.refreshStyle();
            refreshIdentity(target);
            refreshInstance(target);
            refreshRuntimeStatus(target);
            if (filter != null) {
                // The custom-rule total needs a JSON parse off the main thread; the value is
                // posted back to the row like the other async page loads. The target context is
                // captured here, so a later selection cannot retarget this read.
                Context appContext = target.getApplicationContext();
                new Thread(() -> {
                    int total = NotificationRuleStore.load(appContext).total();
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        if (!isAdded() || filter == null) {
                            return;
                        }
                        filter.value(ruleCountValue(total));
                        filter.refreshStyle();
                    });
                }, "dashboard-rule-count").start();
            }
        }

        /**
         * One compact row, shown only when at least one clone is explicitly registered. The value
         * is the package identity. Missing runtime is "Not checked"; an invisible or uninstalled
         * package is "Unavailable". Neither state claims that hooks are active.
         */
        private void refreshInstance(Context target) {
            if (instance == null) return;
            Set<String> registered = HostPackages.additional(host().moduleContext());
            instance.setVisible(!registered.isEmpty());
            instance.setEnabled(restartAvailable);
            if (registered.isEmpty()) return;
            String packageName = host().selectedInstancePackage();
            boolean installed = InstanceSelection.installed(host().getPackageManager(), packageName);
            boolean runtimeObserved = SelfCheckData.hasHostRuntime(SelfCheckData.load(target));
            InstanceSelection.State state = InstanceSelection.state(
                    InstanceSelection.valid(packageName, registered), installed, runtimeObserved);
            instance.value(packageName);
            instance.setSummary(state == InstanceSelection.State.UNAVAILABLE
                    ? getString(R.string.zp_instance_unavailable)
                    : state == InstanceSelection.State.NOT_CHECKED
                    ? getString(R.string.zp_instance_not_checked) : null);
            instance.refreshStyle();
        }

        /**
         * The identity row reports only saved-intent state and admitted runtime identity. It never
         * turns an installed switch or a successful restart command into an Active claim.
         */
        private void refreshIdentity(Context context) {
            if (identity == null) return;
            int pending = SettingsChanges.pendingCount(context);
            ConfigSnapshot active = SettingsChanges.active(context);
            boolean prepare = active == null
                    || active.moduleVersion != BuildConfig.VERSION_CODE;
            String chip;
            int color;
            if (prepare) {
                chip = getString(R.string.zp_dashboard_restart_required_chip);
                color = R.color.zp_status_warn;
            } else if (pending > 0) {
                chip = getResources().getQuantityString(
                        R.plurals.zp_dashboard_pending_chip, pending, pending);
                color = R.color.zp_status_warn;
            } else {
                chip = getString(R.string.zp_dashboard_no_pending_chip);
                color = R.color.zp_status_neutral;
            }
            identity.chips(new ZpRowStyle.Chip(chip, color));
            long zaloVersion = SymbolSchema.installedZaloVersionCode(context);
            if (zaloVersion <= 0L) {
                identity.setSummary(getString(R.string.zp_dashboard_zalo_missing));
            } else {
                RuntimeEnvironment.Snapshot environment =
                        RuntimeEnvironment.current(context);
                String runtime = environment.reported
                        ? getString(frameworkTitle(environment.framework))
                        : getString(R.string.zp_dashboard_runtime_unobserved);
                identity.setSummary(getString(
                        R.string.zp_dashboard_identity_summary, zaloVersion, runtime));
            }
            identity.refreshStyle();
        }

        /**
         * Evidence chips come from admitted self-check rows only. Installed state, a switch value,
         * or an install receipt cannot produce an Active chip.
         */
        private void refreshRuntimeStatus(Context context) {
            if (runtimeStatus == null) return;
            SelfCheckData.Counts counts = SelfCheckData.counts(SelfCheckData.load(context));
            List<ZpRowStyle.Chip> chips = new ArrayList<>();
            if (counts.failed > 0) {
                chips.add(new ZpRowStyle.Chip(
                        getString(R.string.zp_status_failed, counts.failed),
                        R.color.zp_status_error));
            }
            if (counts.stale > 0) {
                chips.add(new ZpRowStyle.Chip(
                        getString(R.string.zp_status_stale, counts.stale),
                        R.color.zp_status_warn));
            }
            if (counts.active > 0) {
                chips.add(new ZpRowStyle.Chip(
                        getString(R.string.zp_status_active, counts.active),
                        R.color.zp_status_active));
            }
            if (counts.installedNoHits > 0) {
                chips.add(new ZpRowStyle.Chip(
                        getString(R.string.zp_status_installed, counts.installedNoHits),
                        R.color.zp_status_neutral));
            }
            if (counts.disabled > 0) {
                chips.add(new ZpRowStyle.Chip(
                        getString(R.string.zp_status_disabled, counts.disabled),
                        R.color.zp_status_neutral));
            }
            if (counts.other > 0) {
                chips.add(new ZpRowStyle.Chip(
                        getString(R.string.zp_status_other, counts.other),
                        R.color.zp_status_neutral));
            }
            if (chips.isEmpty()) {
                chips.add(new ZpRowStyle.Chip(
                        getString(R.string.zp_state_not_observed),
                        R.color.zp_status_neutral));
            }
            runtimeStatus.chips(chips.toArray(new ZpRowStyle.Chip[0]));
            runtimeStatus.setSummary(counts.total() == 0
                    ? getString(R.string.zp_runtime_status_empty) : null);
            runtimeStatus.dot(runtimeDotColor(counts));
            runtimeStatus.refreshStyle();
        }

        private int frameworkTitle(RuntimeEnvironment.Framework framework) {
            if (framework == RuntimeEnvironment.Framework.LSPOSED) {
                return R.string.zp_runtime_framework_lsposed;
            }
            if (framework == RuntimeEnvironment.Framework.LSPATCH) {
                return R.string.zp_runtime_framework_lspatch;
            }
            return R.string.zp_runtime_framework_unknown;
        }

        private int runtimeDotColor(SelfCheckData.Counts counts) {
            String status = SelfCheckData.representativeStatus(
                    counts.failed, counts.stale, counts.active,
                    counts.installedNoHits, counts.disabled);
            if ("failed".equals(status)) return R.color.zp_status_error;
            if ("stale".equals(status)) return R.color.zp_status_warn;
            if ("active".equals(status)) return R.color.zp_status_active;
            return R.color.zp_status_neutral;
        }

        private void setRestartEnabled(boolean enabled) {
            restartAvailable = enabled;
            if (restart != null) {
                restart.setEnabled(enabled);
            }
            if (instance != null) {
                instance.setEnabled(enabled);
            }
        }
    }

    /**
     * Plain filtered directory over the existing tweak groups. Results are navigation rows, not
     * duplicate controls: every result opens its real section and lands on the matched row.
     */
    public static final class SearchFragment extends ZpPreferenceFragment {
        private static final String ARG_QUERY = "search.query";

        private final List<SearchEntry> entries = new ArrayList<>();
        private SearchFieldPreference searchField;
        private PreferenceCategory results;
        private String query = "";

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            if (savedInstanceState != null) {
                query = savedInstanceState.getString(ARG_QUERY, "");
            }
            buildScreen();
        }

        @Override
        public void onSaveInstanceState(@NonNull Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putString(ARG_QUERY, query);
        }

        private void buildScreen() {
            Context context = requireContext();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
            searchField = new SearchFieldPreference(context);
            searchField.query(query);
            searchField.listener(value -> {
                query = value;
                renderResults();
            });
            screen.addPreference(searchField);
            results = PreferenceUi.category(screen, getString(R.string.zp_search_all_tweaks));
            setPreferenceScreen(screen);

            entries.clear();
            for (Tweaks.Item item : Tweaks.ITEMS) {
                entries.add(new SearchEntry(item.key, item.titleRes, item.summaryRes,
                        item.section));
            }
            // Controls that are built by their section rather than declared as a Tweaks.Item.
            entries.add(new SearchEntry(Tweaks.KEY_DEFAULT_INBOX_FILTER,
                    R.string.zp_default_inbox_filter, 0, Tweaks.SECTION_INBOX));
            entries.add(new SearchEntry(Tweaks.KEY_BACKUP_PUSH_INTERVAL,
                    R.string.zp_backup_interval_title, 0, Tweaks.SECTION_BACKUP));
            entries.add(new SearchEntry(Tweaks.KEY_PASSCODE_GRACE_MS,
                    R.string.zp_passcode_grace_title, 0, Tweaks.SECTION_SECURITY));
            entries.add(new SearchEntry(Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION,
                    R.string.zp_history_retention_title, 0, Tweaks.SECTION_NOTIFICATIONS));
            Collections.sort(entries, (left, right) ->
                    Integer.compare(sectionRank(left.section), sectionRank(right.section)));
            renderResults();
        }

        private void renderResults() {
            if (results == null || getContext() == null) {
                return;
            }
            results.removeAll();
            results.setTitle(getString(query.trim().isEmpty()
                    ? R.string.zp_search_all_tweaks : R.string.zp_search_results));
            List<SearchEntry> matches = new ArrayList<>();
            for (SearchEntry entry : entries) {
                String title = getString(entry.titleRes);
                String summary = entry.summaryRes == 0 ? "" : getString(entry.summaryRes);
                String sectionTitle = getString(sectionTitleRes(entry.section));
                if (PreferenceUi.matchesSearch(query, title, summary, sectionTitle)) {
                    matches.add(entry);
                }
            }
            if (matches.isEmpty()) {
                results.addPreference(PreferenceUi.info(requireContext(),
                        getString(R.string.zp_search_no_results), null));
                return;
            }
            ZpSection resultSection = ZpSection.in(results);
            for (SearchEntry entry : matches) {
                String sectionTitle = getString(sectionTitleRes(entry.section));
                ZpRowPreference row = PreferenceUi.nav(requireContext(),
                        getString(entry.titleRes), sectionTitle);
                row.setKey("search." + entry.key);
                row.setOnPreferenceClickListener(clicked -> {
                    openResult(entry);
                    return true;
                });
                resultSection.add(row);
            }
        }

        private void openResult(SearchEntry entry) {
            View focus = requireActivity().getCurrentFocus();
            if (focus != null) {
                InputMethodManager keyboard = (InputMethodManager) requireContext()
                        .getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) {
                    keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
                }
            }
            String sectionTitle = getString(sectionTitleRes(entry.section));
            if (Tweaks.SECTION_NOTIFICATIONS.equals(entry.section)) {
                host().openPage(new NotificationFilterActivity.FilterFragment(),
                        sectionTitle, entry.key);
            } else {
                host().openPage(SectionActivity.SettingsFragment.forSection(entry.section),
                        sectionTitle, entry.key);
            }
        }

        private StatusActivity host() {
            return (StatusActivity) requireActivity();
        }

        private static int sectionTitleRes(String section) {
            if (Tweaks.SECTION_NAVIGATION.equals(section)) return R.string.zp_tabs_title;
            if (Tweaks.SECTION_INBOX.equals(section)) return R.string.zp_inbox_title;
            if (Tweaks.SECTION_CHAT.equals(section)) return R.string.zp_chats_title;
            if (Tweaks.SECTION_ME.equals(section)) return R.string.zp_me_title;
            if (Tweaks.SECTION_ADS.equals(section)) return R.string.zp_ads_title;
            if (Tweaks.SECTION_NOTIFICATIONS.equals(section)) {
                return R.string.zp_notification_filter_title;
            }
            if (Tweaks.SECTION_TELEMETRY.equals(section)) return R.string.zp_telemetry_title;
            if (Tweaks.SECTION_CALLS.equals(section)) return R.string.zp_calls_title;
            if (Tweaks.SECTION_BACKUP.equals(section)) return R.string.zp_backup_push_title;
            if (Tweaks.SECTION_SECURITY.equals(section)) return R.string.zp_security_title;
            if (Tweaks.SECTION_DEVELOPER.equals(section)) {
                return R.string.zp_developer_tools_title;
            }
            return R.string.zp_app_name;
        }

        private static int sectionRank(String section) {
            if (Tweaks.SECTION_NAVIGATION.equals(section)) return 0;
            if (Tweaks.SECTION_INBOX.equals(section)) return 1;
            if (Tweaks.SECTION_CHAT.equals(section)) return 2;
            if (Tweaks.SECTION_ME.equals(section)) return 3;
            if (Tweaks.SECTION_ADS.equals(section)) return 4;
            if (Tweaks.SECTION_NOTIFICATIONS.equals(section)) return 5;
            if (Tweaks.SECTION_TELEMETRY.equals(section)) return 6;
            if (Tweaks.SECTION_CALLS.equals(section)) return 7;
            if (Tweaks.SECTION_BACKUP.equals(section)) return 8;
            if (Tweaks.SECTION_SECURITY.equals(section)) return 9;
            if (Tweaks.SECTION_DEVELOPER.equals(section)) return 10;
            return 11;
        }

        private static final class SearchEntry {
            final String key;
            final int titleRes;
            final int summaryRes;
            final String section;

            SearchEntry(String key, int titleRes, int summaryRes, String section) {
                this.key = key;
                this.titleRes = titleRes;
                this.summaryRes = summaryRes;
                this.section = section;
            }
        }
    }

    /** Standalone search affordance, separate from the connected settings group. */
    private static class SearchEntryPreference extends Preference {
        SearchEntryPreference(Context context) {
            super(context);
            setLayoutResource(R.layout.zp_preference_search_entry);
            setIconSpaceReserved(false);
            setPersistent(false);
        }

        @Override
        public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            holder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            holder.setDividerAllowedAbove(false);
            holder.setDividerAllowedBelow(false);
        }
    }

    /** Inline single-line search field. Results update in place, so focus stays in the field. */
    private static final class SearchFieldPreference extends SearchEntryPreference {
        interface Listener {
            void onQueryChanged(String query);
        }

        private String query = "";
        private Listener listener;
        private boolean focusOnBind = true;

        SearchFieldPreference(Context context) {
            super(context);
            setLayoutResource(R.layout.zp_preference_search);
            setIconSpaceReserved(false);
            setPersistent(false);
            setSelectable(false);
        }

        void query(String value) {
            query = value == null ? "" : value;
            notifyChanged();
        }

        void listener(Listener value) {
            listener = value;
        }

        @Override
        public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            holder.itemView.setFocusable(false);
            holder.itemView.setFocusableInTouchMode(false);
            if (holder.itemView instanceof ViewGroup) {
                ((ViewGroup) holder.itemView).setDescendantFocusability(
                        ViewGroup.FOCUS_AFTER_DESCENDANTS);
            }
            EditText input = (EditText) holder.findViewById(R.id.zp_search_input);
            if (input == null) {
                return;
            }
            Object oldTag = input.getTag();
            if (oldTag instanceof TextWatcher) {
                input.removeTextChangedListener((TextWatcher) oldTag);
            }
            if (!query.contentEquals(input.getText())) {
                input.setText(query);
                input.setSelection(input.length());
            }
            TextWatcher watcher = new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence text, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable editable) {
                    query = editable.toString();
                    if (listener != null) {
                        listener.onQueryChanged(query);
                    }
                }
            };
            input.addTextChangedListener(watcher);
            input.setTag(watcher);
            if (focusOnBind) {
                focusOnBind = false;
                // Wait for RecyclerView to attach the editor. InsetsController also waits for
                // window focus, so opening search needs no second tap or fixed keyboard delay.
                input.post(() -> {
                    if (!input.isAttachedToWindow() || !input.requestFocus()) return;
                    WindowInsetsControllerCompat keyboard =
                            ViewCompat.getWindowInsetsController(input);
                    if (keyboard != null) keyboard.show(WindowInsetsCompat.Type.ime());
                });
            }
        }
    }
}
