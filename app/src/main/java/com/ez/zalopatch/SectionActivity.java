package com.ez.zalopatch;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public final class SectionActivity {
    public static final String EXTRA_SECTION = "section";
    public static final String EXTRA_TITLE = "title";
    private SectionActivity() {
    }

    public static final class SettingsFragment extends ZpPreferenceFragment {
        private static final String ARG_SECTION = "section";
        private static final String MASTER_TELEMETRY = "internal.telemetry_master";
        static final String DEVELOPER_REPORT = "internal.developer_report";
        private Map<String, SelfCheckData.Row> selfCheckRows = new HashMap<>();
        private final Map<String, ZpSwitchPreference> switches = new HashMap<>();
        private final Map<ZpRowPreference, String[]> pageStatuses = new HashMap<>();
        private ZpRowPreference developerRuntimeStatus;
        private ZpRowPreference recordingsRow;
        private ZpMainSwitchPreference telemetryMaster;
        private boolean recordingCountLoading;
        private final ActivityResultLauncher<String> recordingNotificationPermissionLauncher =
                registerForActivityResult(
                        new ActivityResultContracts.RequestPermission(), granted -> {
                            if (!granted) {
                                return;
                            }
                            TweakStore.setEnabled(requireContext(),
                                    Tweaks.KEY_CALL_RECORDING_NOTIFICATIONS, true);
                            SettingsChanges.markChanged(requireContext(),
                                    Tweaks.KEY_CALL_RECORDING_NOTIFICATIONS);
                            ZpSwitchPreference preference = switches.get(
                                    Tweaks.KEY_CALL_RECORDING_NOTIFICATIONS);
                            if (preference != null) {
                                preference.setChecked(true);
                            }
                        });

        static SettingsFragment forSection(String section) {
            SettingsFragment fragment = new SettingsFragment();
            Bundle args = new Bundle();
            args.putString(ARG_SECTION, section);
            fragment.setArguments(args);
            return fragment;
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            buildScreen();
        }

        @Override
        public void onResume() {
            super.onResume();
            selfCheckRows = SelfCheckData.byFeature(SelfCheckData.load(requireContext()));
            for (Map.Entry<String, ZpSwitchPreference> entry : switches.entrySet()) {
                entry.getValue().setChecked(TweakStore.isEnabled(requireContext(), entry.getKey()));
                entry.getValue().chips(trackingChips(entry.getKey()));
                entry.getValue().refreshStyle();
            }
            for (Map.Entry<ZpRowPreference, String[]> entry : pageStatuses.entrySet()) {
                entry.getKey().chips(trackingChipsForFeatures(entry.getValue()));
                entry.getKey().refreshStyle();
            }
            if (telemetryMaster != null) {
                telemetryMaster.setChecked(allTelemetryDisabled(requireContext()));
            }
            refreshRecordingCount();
            refreshDeveloperRuntimeStatus();
        }

        private void buildScreen() {
            Context context = requireContext();
            String section = requireArguments().getString(ARG_SECTION);
            SymbolSchema.Active schema = SymbolSchema.active(context);
            selfCheckRows = SelfCheckData.byFeature(SelfCheckData.load(context));

            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
            setPreferenceScreen(screen);
            for (Tweaks.Group group : Tweaks.groups(section)) {
                PreferenceCategory category = PreferenceUi.category(screen, getString(group.titleRes));
                ZpSection zpSection = ZpSection.in(category);
                switch (group.specialBlock) {
                    case TELEMETRY:
                        zpSection.add(telemetryMasterPreference(context));
                        zpSection.add(pageStatusPreference(context,
                                getString(R.string.zp_runtime_status_title),
                                "telemetry.analytics_db", "telemetry.firebase",
                                "telemetry.ad_id", "telemetry.measurement_bind"));
                        break;
                    case BOTTOM_TABS_STATUS:
                        zpSection.add(pageStatusPreference(context,
                                getString(R.string.zp_runtime_status_title),
                                "bottom_tabs.state", "bottom_tabs.consumers"));
                        break;
                    case DEVELOPER_TOOLS:
                        addDeveloperTools(zpSection, context);
                        break;
                    case NONE:
                        break;
                }
                for (String key : group.keys) {
                    addRowsForKey(zpSection, schema, key);
                }
            }
        }

        private void addDeveloperTools(ZpSection section, Context context) {
            ZpSwitchPreference displayHookDetail = PreferenceUi.toggle(context,
                    UiSettings.KEY_DISPLAY_MODE,
                    getString(R.string.zp_display_hook_detail_title), null);
            displayHookDetail.setChecked(UiSettings.isDetailed(context));
            displayHookDetail.setOnPreferenceChangeListener((preference, value) -> {
                UiSettings.setDisplayMode(context, (Boolean) value
                        ? UiSettings.MODE_DETAILED
                        : UiSettings.MODE_SIMPLIFIED);
                return true;
            });
            section.add(displayHookDetail);

            SelfCheckData.Counts counts = SelfCheckData.counts(SelfCheckData.load(context));
            developerRuntimeStatus = PreferenceUi.nav(context,
                    getString(R.string.zp_runtime_status_title), runtimeSummary(counts));
            developerRuntimeStatus.setKey("internal.developer_runtime_status");
            developerRuntimeStatus.dot(runtimeDotColor(counts));
            developerRuntimeStatus.setOnPreferenceClickListener(preference -> {
                ((StatusActivity) requireActivity()).openPage(
                        new SelfCheckActivity.SelfCheckFragment(),
                        getString(R.string.zp_self_check_page_title));
                return true;
            });
            section.add(developerRuntimeStatus);

            ZpRowPreference catalog = PreferenceUi.nav(context,
                    getString(R.string.zp_compatibility_catalog_title),
                    compatibilityCatalogSummary(context));
            catalog.setKey("internal.developer_compatibility_catalog");
            catalog.setOnPreferenceClickListener(preference -> {
                ((StatusActivity) requireActivity()).openPage(
                        new CompatibilityCatalogActivity.CatalogFragment(),
                        getString(R.string.zp_compatibility_catalog_title));
                return true;
            });
            section.add(catalog);

            ZpRowPreference report = PreferenceUi.nav(context,
                    getString(R.string.zp_diagnostic_title),
                    SymbolSchema.active(context).valid
                            ? null
                            : getString(R.string.zp_diagnostic_remap_summary));
            report.setKey(DEVELOPER_REPORT);
            report.setOnPreferenceClickListener(preference -> {
                ((StatusActivity) requireActivity()).openPage(
                        new DiagnosticReportActivity.ReportFragment(),
                        getString(R.string.zp_diagnostic_title));
                return true;
            });
            section.add(report);
        }

        private void refreshDeveloperRuntimeStatus() {
            if (developerRuntimeStatus == null || !isAdded()) return;
            SelfCheckData.Counts counts = SelfCheckData.counts(SelfCheckData.load(requireContext()));
            developerRuntimeStatus.setSummary(runtimeSummary(counts));
            developerRuntimeStatus.dot(runtimeDotColor(counts));
            developerRuntimeStatus.refreshStyle();
        }

        private String runtimeSummary(SelfCheckData.Counts counts) {
            if (counts.total() == 0) return getString(R.string.zp_runtime_status_empty);
            return getString(R.string.zp_runtime_status_counts,
                    counts.active, counts.failed, counts.stale);
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

        private String compatibilityCatalogSummary(Context context) {
            SymbolSchema.Active active = SymbolSchema.active(context);
            return active.valid
                    ? null
                    : getString(R.string.zp_compatibility_catalog_summary_unmapped,
                    active.installedVersionCode, active.profileCount);
        }

        private void addRowsForKey(ZpSection section, SymbolSchema.Active schema, String key) {
            Context context = requireContext();
            if (Tweaks.KEY_DEFAULT_INBOX_FILTER.equals(key)) {
                section.addDependent(defaultInboxFilterPreference(context),
                        Tweaks.KEY_FILTER_POPOVER_CATEGORIES);
                return;
            }
            Tweaks.Item item = itemFor(key);
            if (item == null) {
                return;
            }
            Preference row = item.implemented
                    ? implementedPreference(context, schema, item)
                    : plannedPreference(context, schema, item);
            String dependency = null;
            if (Tweaks.KEY_CATEGORY_GROUPS.equals(item.key)
                    || Tweaks.KEY_CATEGORY_STRANGERS.equals(item.key)
                    || Tweaks.KEY_CATEGORY_OA.equals(item.key)) {
                dependency = Tweaks.KEY_FILTER_POPOVER_CATEGORIES;
            } else if (Tweaks.KEY_CALL_RECORDING_NOTIFICATIONS.equals(item.key)) {
                dependency = Tweaks.KEY_AUTO_RECORD_CALLS;
            }
            if (dependency == null) section.add(row);
            else section.addDependent(row, dependency);
            if (Tweaks.KEY_AUTO_RECORD_CALLS.equals(key)) {
                recordingsRow = PreferenceUi.nav(context,
                        getString(R.string.zp_call_recordings_title));
                recordingsRow.value(getString(R.string.zp_loading));
                recordingsRow.setOnPreferenceClickListener(preference -> {
                    ((StatusActivity) requireActivity()).openPage(
                            new CallRecordingsActivity.RecordingsFragment(),
                            getString(R.string.zp_call_recordings_title));
                    return true;
                });
                section.add(recordingsRow);
                refreshRecordingCount();
            }
        }

        private Preference defaultInboxFilterPreference(Context context) {
            ZpListPreference preference = new ZpListPreference(context);
            preference.setKey(Tweaks.KEY_DEFAULT_INBOX_FILTER);
            preference.setTitle(R.string.zp_default_inbox_filter);
            preference.setEntries(R.array.zp_inbox_filter_entries);
            preference.setEntryValues(R.array.zp_inbox_filter_values);
            preference.setValue(String.valueOf(
                    SettingsStore.getInt(context, Tweaks.KEY_DEFAULT_INBOX_FILTER)));
            preference.setSummaryProvider(androidx.preference.ListPreference
                    .SimpleSummaryProvider.getInstance());
            preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
                int value;
                try {
                    value = Integer.parseInt(String.valueOf(newValue));
                } catch (NumberFormatException ignored) {
                    return false;
                }
                SettingsStore.putInt(context, Tweaks.KEY_DEFAULT_INBOX_FILTER, value);
                SettingsChanges.markChanged(context, Tweaks.KEY_DEFAULT_INBOX_FILTER);
                return true;
            });
            return preference;
        }

        private static Tweaks.Item itemFor(String key) {
            for (Tweaks.Item item : Tweaks.ITEMS) {
                if (item.key.equals(key)) {
                    return item;
                }
            }
            return null;
        }

        private void refreshRecordingCount() {
            if (recordingsRow == null) {
                return;
            }
            if (recordingCountLoading || !isAdded()) {
                return;
            }
            recordingCountLoading = true;
            Context context = requireContext().getApplicationContext();
            new Thread(() -> {
                int count = CallRecordingStore.list(context).size();
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    recordingCountLoading = false;
                    if (!isAdded() || recordingsRow == null) {
                        return;
                    }
                    recordingsRow.value(getString(R.string.zp_stored_count, count));
                    recordingsRow.refreshStyle();
                });
            }, "call-recording-count").start();
        }

        private Preference telemetryMasterPreference(Context context) {
            telemetryMaster = PreferenceUi.mainSwitch(context, MASTER_TELEMETRY,
                    getString(R.string.zp_telemetry_master_title),
                    null);
            telemetryMaster.setChecked(allTelemetryDisabled(context));
            telemetryMaster.setOnPreferenceChangeListener((changedPreference, newValue) -> {
                boolean disabled = (Boolean) newValue;
                for (String key : Tweaks.TELEMETRY_KEYS) {
                    if (TweakStore.isEnabled(context, key) != disabled) {
                        SettingsChanges.markChanged(context, key);
                    }
                    TweakStore.setEnabled(context, key, disabled);
                    ZpSwitchPreference child = switches.get(key);
                    if (child != null) {
                        child.setChecked(disabled);
                    }
                }
                return true;
            });
            return telemetryMaster;
        }

        private ZpRowPreference pageStatusPreference(
                Context context, String title, String... features) {
            ZpRowPreference preference = PreferenceUi.info(context, title, null);
            preference.chips(trackingChipsForFeatures(features));
            pageStatuses.put(preference, features);
            return preference;
        }

        private boolean allTelemetryDisabled(Context context) {
            for (String key : Tweaks.TELEMETRY_KEYS) {
                if (!TweakStore.isEnabled(context, key)) {
                    return false;
                }
            }
            return true;
        }

        private Preference implementedPreference(Context context, SymbolSchema.Active schema, Tweaks.Item item) {
            TweakHookInfo.Info hookInfo = TweakHookInfo.forKey(item.key, schema);
            String behaviorSummary = item.summaryRes == 0 ? "" : getString(item.summaryRes);
            String hookSummary = hookInfo.driftProne && !schema.valid
                    ? getString(R.string.zp_hook_path_unavailable)
                    : getString(R.string.zp_hook_path, hookInfo.path);
            CharSequence summary = behaviorSummary;
            if (UiSettings.isDetailed(context)) {
                String detailed = behaviorSummary.isEmpty()
                        ? hookSummary
                        : behaviorSummary + "\n" + hookSummary;
                summary = highlightDriftSymbols(context, detailed, hookInfo, schema.valid);
            }
            ZpSwitchPreference preference = PreferenceUi.toggle(context, item.key,
                    getString(item.titleRes),
                    summary);
            preference.setChecked(TweakStore.isEnabled(context, item.key));
            preference.chips(trackingChips(item.key));
            switches.put(item.key, preference);
            preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
                boolean enabled = (Boolean) newValue;
                if (Tweaks.KEY_CALL_RECORDING_NOTIFICATIONS.equals(item.key)
                        && enabled && !notificationPermissionGranted(context)) {
                    recordingNotificationPermissionLauncher.launch(
                            Manifest.permission.POST_NOTIFICATIONS);
                    return false;
                }
                TweakStore.setEnabled(context, item.key, enabled);
                SettingsChanges.markChanged(context, item.key);
                if (telemetryMaster != null && item.key.startsWith("telemetry.")) {
                    telemetryMaster.setChecked(allTelemetryDisabled(context));
                }
                return true;
            });
            return preference;
        }

        private static CharSequence highlightDriftSymbols(
                Context context, String summary, TweakHookInfo.Info hookInfo, boolean schemaValid) {
            if (!schemaValid || hookInfo.driftSymbols.isEmpty()) {
                return summary;
            }
            SpannableString styled = new SpannableString(summary);
            int hookStart = summary.indexOf(hookInfo.path);
            if (hookStart < 0) {
                return styled;
            }
            int color = ContextCompat.getColor(context, R.color.zp_status_warn);
            for (String symbol : hookInfo.driftSymbols) {
                int start = summary.indexOf(symbol, hookStart);
                while (start >= 0) {
                    styled.setSpan(new ForegroundColorSpan(color), start, start + symbol.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    start = summary.indexOf(symbol, start + symbol.length());
                }
            }
            return styled;
        }

        private static boolean notificationPermissionGranted(Context context) {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }

        private Preference plannedPreference(Context context, SymbolSchema.Active schema, Tweaks.Item item) {
            String summary = item.summaryRes == 0 ? "" : getString(item.summaryRes);
            return PreferenceUi.unavailable(context,
                    getString(item.titleRes),
                    summary.isEmpty() ? getString(R.string.zp_unavailable)
                            : getString(R.string.zp_unavailable_with_summary, summary));
        }

        /** Compact per-row runtime state. Replaces the old second tracking row under each toggle. */
        private ZpRowStyle.Chip[] trackingChips(String key) {
            ArrayList<ZpRowStyle.Chip> chips = new ArrayList<>();
            if (!key.startsWith("telemetry.")
                    && !Tweaks.KEY_HIDE_DISCOVERY_TAB.equals(key)
                    && !Tweaks.KEY_HIDE_TIMELINE_TAB.equals(key)
                    && !Tweaks.KEY_KEEP_GROUP_TAB.equals(key)) {
                ZpRowStyle.Chip[] runtime = trackingChipsForFeatures(relatedFeatures(key));
                java.util.Collections.addAll(chips, runtime);
            }
            return chips.toArray(new ZpRowStyle.Chip[0]);
        }

        private ZpRowStyle.Chip[] trackingChipsForFeatures(String[] features) {
            if (features.length == 0) {
                return new ZpRowStyle.Chip[0];
            }
            TrackingCounts counts = trackingCounts(features);
            if (counts.total() == 0) {
                return new ZpRowStyle.Chip[0];
            }
            return new ZpRowStyle.Chip[]{
                    new ZpRowStyle.Chip(counts.status(requireContext()), counts.statusColorRes())};
        }

        private TrackingCounts trackingCounts(String[] features) {
            TrackingCounts counts = new TrackingCounts();
            for (String feature : features) {
                SelfCheckData.Row row = selfCheckRows.get(feature);
                if (row == null) {
                    continue;
                }
                if ("failed".equals(row.status)) {
                    counts.failed++;
                } else if ("stale".equals(row.status)) {
                    counts.stale++;
                } else if ("active".equals(row.status)) {
                    counts.active++;
                } else if ("installed_no_hits".equals(row.status)) {
                    counts.installed++;
                } else if ("disabled".equals(row.status)) {
                    counts.disabled++;
                }
            }
            return counts;
        }

        private String[] relatedFeatures(String key) {
            if (Tweaks.KEY_FORCE_MESSAGES_AS_HOME.equals(key)) {
                return new String[]{"bottom_tabs.force_home"};
            }
            if (Tweaks.KEY_FILTER_POPOVER_CATEGORIES.equals(key)) {
                return new String[]{"inbox.filter_bar", "inbox.filter"};
            }
            if (Tweaks.KEY_HIDE_REACTION_ROW.equals(key)) {
                return new String[]{"messages.reaction_row"};
            }
            if (Tweaks.KEY_CALL_RECORDING_PROBE.equals(key)) {
                return new String[]{"calls.recording_probe.lifecycle",
                        "calls.recording_probe.stream_registration", "calls.recording_probe.audio"};
            }
            if (Tweaks.KEY_AUTO_RECORD_CALLS.equals(key)) {
                return new String[]{"calls.auto_record.hooks", "calls.auto_record.native",
                        "calls.auto_record.storage", "calls.auto_record.video_state",
                        "calls.auto_record.metadata"};
            }
            if (Tweaks.KEY_CALL_RECORDING_NOTIFICATIONS.equals(key)) {
                return new String[]{"calls.auto_record.notifications"};
            }
            if (Tweaks.KEY_HIDE_MEDIA_BOX.equals(key)) {
                return new String[]{"inbox.media_box"};
            }
            if (Tweaks.KEY_HIDE_QR_WALLET.equals(key)) {
                return new String[]{"me_cleanup.qr_wallet"};
            }
            if (Tweaks.KEY_HIDE_ZCLOUD.equals(key)) {
                return new String[]{"me_cleanup.zcloud"};
            }
            if (Tweaks.KEY_HIDE_ZSTYLE.equals(key)) {
                return new String[]{"me_cleanup.zstyle"};
            }
            if (Tweaks.KEY_HIDE_ZBUSINESS.equals(key)) {
                return new String[]{"me_cleanup.zbusiness"};
            }
            if (Tweaks.KEY_HIDE_PROMO_NOTIFICATIONS.equals(key)) {
                return new String[]{"notifications.promo", "notifications.listener"};
            }
            if (Tweaks.KEY_RECORD_NOTIFICATION_HISTORY.equals(key)) {
                return new String[]{"notifications.history"};
            }
            if (Tweaks.KEY_HIDE_MESSAGE_ADS.equals(key)) {
                return new String[]{"zinstant.message_ad"};
            }
            if (Tweaks.KEY_HIDE_FEED_ADS.equals(key)) {
                return new String[]{"zinstant.feed_ad"};
            }
            if (Tweaks.KEY_HIDE_PROMO_SERVICES.equals(key)) {
                return new String[]{"zinstant.network", "zinstant.script"};
            }
            return new String[0];
        }

        private static final class TrackingCounts {
            int failed;
            int stale;
            int active;
            int installed;
            int disabled;
            int total() {
                return failed + stale + active + installed + disabled;
            }

            String status(Context context) {
                String status = SelfCheckData.representativeStatus(
                        failed, stale, active, installed, disabled);
                if ("installed_no_hits".equals(status)) {
                    return context.getString(R.string.zp_state_installed);
                }
                return SelfCheckData.statusTitle(context, status);
            }

            int statusColorRes() {
                String status = SelfCheckData.representativeStatus(
                        failed, stale, active, installed, disabled);
                if ("failed".equals(status)) return R.color.zp_status_error;
                if ("stale".equals(status)) return R.color.zp_status_warn;
                if ("active".equals(status)) return R.color.zp_status_active;
                return R.color.zp_status_neutral;
            }
        }
    }
}
