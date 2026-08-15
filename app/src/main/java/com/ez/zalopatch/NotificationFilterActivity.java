package com.ez.zalopatch;

import android.content.Context;
import android.os.Bundle;

import androidx.preference.ListPreference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

public final class NotificationFilterActivity {
    private NotificationFilterActivity() {
    }

    public static final class FilterFragment extends ZpPreferenceFragment {
        private ZpRowPreference suppressed;
        private ZpRowPreference historyPage;
        private final java.util.EnumMap<NotificationRuleStore.Type, ZpRowPreference> listRows =
                new java.util.EnumMap<>(NotificationRuleStore.Type.class);
        private final java.util.Map<String, ZpSwitchPreference> toggles = new java.util.HashMap<>();

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            buildScreen();
        }

        private void buildScreen() {
            Context context = requireContext();
            NotificationRuleStore.RuleSet rules = NotificationRuleStore.load(context);
            NotificationHistoryStore history = new NotificationHistoryStore(context);
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
            setPreferenceScreen(screen);

            screen.addPreference(PreferenceUi.notice(context,
                    getString(R.string.zp_filter_scope_notice)));

            PreferenceCategory controls = PreferenceUi.category(screen,
                    getString(R.string.zp_filter_controls));
            ZpSection controlSection = ZpSection.in(controls);
            controlSection.add(toggle(context, Tweaks.KEY_HIDE_PROMO_NOTIFICATIONS,
                    getString(R.string.zp_filter_master_title),
                    getString(R.string.zp_filter_master_summary)));
            controlSection.add(toggle(context, Tweaks.KEY_RECORD_NOTIFICATION_HISTORY,
                    getString(R.string.zp_history_record_title),
                    getString(R.string.zp_history_record_summary)));
            ListPreference retention = retentionPreference(context, history);
            controlSection.addDependent(retention, Tweaks.KEY_RECORD_NOTIFICATION_HISTORY);
            historyPage = PreferenceUi.nav(context,
                    getString(R.string.zp_notification_history_title));
            historyPage.setKey("notifications.history_page");
            historyPage.value(getString(R.string.zp_stored_count, history.count()));
            historyPage.setOnPreferenceClickListener(preference -> {
                ((StatusActivity) requireActivity()).openPage(
                        NotificationHistoryActivity.HistoryFragment.forBucket(null),
                        getString(R.string.zp_notification_history_title));
                return true;
            });
            controlSection.add(historyPage);

            PreferenceCategory lists = PreferenceUi.category(screen,
                    getString(R.string.zp_filter_custom_lists));
            ZpSection listSection = ZpSection.in(lists);
            addList(listSection, rules, NotificationRuleStore.Type.KEYWORD_BLOCKLIST);
            addList(listSection, rules, NotificationRuleStore.Type.KEYWORD_EXCEPTIONS);
            addList(listSection, rules, NotificationRuleStore.Type.ACCOUNT_BLOCKLIST);
            addList(listSection, rules, NotificationRuleStore.Type.ACCOUNT_EXCEPTIONS);

            PreferenceCategory builtIn = PreferenceUi.category(screen,
                    getString(R.string.zp_filter_built_in));
            ZpSection builtInSection = ZpSection.in(builtIn);
            builtInSection.add(PreferenceUi.info(context,
                    getString(R.string.zp_filter_built_in_title),
                    getString(R.string.zp_filter_built_in_summary)));

            PreferenceCategory review = PreferenceUi.category(screen,
                    getString(R.string.zp_filter_review));
            ZpSection reviewSection = ZpSection.in(review);
            suppressed = PreferenceUi.nav(context,
                    getString(R.string.zp_filter_suppressed_title));
            int suppressedCount = history.count(NotificationHistoryStore.Bucket.SUPPRESSED);
            suppressed.value(getString(R.string.zp_stored_count, suppressedCount));
            suppressed.setOnPreferenceClickListener(preference -> {
                ((StatusActivity) requireActivity()).openPage(
                        NotificationHistoryActivity.HistoryFragment.forBucket(
                                NotificationHistoryStore.Bucket.SUPPRESSED.name()),
                        getString(R.string.zp_notification_history_title));
                return true;
            });
            reviewSection.add(suppressed);

            screen.addPreference(PreferenceUi.footer(context,
                    getString(R.string.zp_filter_footer), ""));
        }

        @Override
        public void onResume() {
            super.onResume();
            if (getPreferenceScreen() == null) {
                return;
            }
            NotificationRuleStore.RuleSet rules = NotificationRuleStore.load(requireContext());
            for (NotificationRuleStore.Type type : NotificationRuleStore.Type.values()) {
                ZpRowPreference row = listRows.get(type);
                if (row != null) {
                    int count = rules.list(type).size();
                    row.value(count == 1
                            ? getString(R.string.zp_filter_item_count_one)
                            : getString(R.string.zp_filter_item_count, count));
                    row.refreshStyle();
                }
            }
            if (suppressed != null) {
                NotificationHistoryStore history = new NotificationHistoryStore(requireContext());
                int count = history.count(NotificationHistoryStore.Bucket.SUPPRESSED);
                suppressed.value(getString(R.string.zp_stored_count, count));
                suppressed.refreshStyle();
                historyPage.value(getString(R.string.zp_stored_count, history.count()));
                historyPage.refreshStyle();
            }
            for (java.util.Map.Entry<String, ZpSwitchPreference> entry : toggles.entrySet()) {
                entry.getValue().chips(notificationTrackingChips(requireContext(), entry.getKey()));
                entry.getValue().refreshStyle();
            }
        }

        private ZpSwitchPreference toggle(
                Context context, String key, String title, String summary) {
            ZpSwitchPreference preference = PreferenceUi.toggle(context, key, title, summary);
            preference.setChecked(TweakStore.isEnabled(context, key));
            preference.chips(notificationTrackingChips(context, key));
            toggles.put(key, preference);
            preference.setOnPreferenceChangeListener((changed, newValue) -> {
                TweakStore.setEnabled(context, key, (Boolean) newValue);
                SettingsChanges.markChanged(context, key);
                return true;
            });
            return preference;
        }

        private ZpRowStyle.Chip[] notificationTrackingChips(Context context, String key) {
            java.util.Map<String, SelfCheckData.Row> rows =
                    SelfCheckData.byFeature(SelfCheckData.load(context));
            String[] features = Tweaks.KEY_RECORD_NOTIFICATION_HISTORY.equals(key)
                    ? new String[]{"notifications.history"}
                    : new String[]{"notifications.promo", "notifications.listener"};
            SelfCheckData.Counts counts = new SelfCheckData.Counts();
            for (String feature : features) {
                SelfCheckData.Row row = rows.get(feature);
                if (row == null) {
                    continue;
                }
                if ("failed".equals(row.status)) counts.failed++;
                else if ("stale".equals(row.status)) counts.stale++;
                else if ("active".equals(row.status)) counts.active++;
                else if ("installed_no_hits".equals(row.status)) counts.installedNoHits++;
                else if ("disabled".equals(row.status)) counts.disabled++;
                else counts.other++;
            }
            if (counts.total() == 0) {
                return new ZpRowStyle.Chip[0];
            }
            String status = SelfCheckData.representativeStatus(
                    counts.failed, counts.stale, counts.active,
                    counts.installedNoHits, counts.disabled);
            return new ZpRowStyle.Chip[]{new ZpRowStyle.Chip(
                    SelfCheckData.statusTitle(context, status), statusColor(status))};
        }

        private int statusColor(String status) {
            if ("failed".equals(status)) return R.color.zp_status_error;
            if ("stale".equals(status)) return R.color.zp_status_warn;
            if ("active".equals(status)) return R.color.zp_status_active;
            return R.color.zp_status_neutral;
        }

        private ListPreference retentionPreference(
                Context context, NotificationHistoryStore store) {
            ZpListPreference retention = new ZpListPreference(context);
            retention.setKey(Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION);
            retention.setTitle(R.string.zp_history_retention_title);
            retention.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            retention.setEntries(R.array.zp_history_retention_entries);
            retention.setEntryValues(R.array.zp_history_retention_values);
            retention.setValue(String.valueOf(store.retentionLimit()));
            retention.setOnPreferenceChangeListener((preference, newValue) -> {
                int value = Integer.parseInt(String.valueOf(newValue));
                SettingsStore.putInt(context, Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION, value);
                store.enforceRetention();
                SettingsChanges.markChanged(context, Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION);
                return true;
            });
            return retention;
        }

        private void addList(
                ZpSection section,
                NotificationRuleStore.RuleSet rules,
                NotificationRuleStore.Type type) {
            int count = rules.list(type).size();
            ZpRowPreference preference = PreferenceUi.nav(requireContext(), type.title(requireContext()));
            preference.value(count == 1
                    ? getString(R.string.zp_filter_item_count_one)
                    : getString(R.string.zp_filter_item_count, count));
            preference.setOnPreferenceClickListener(clicked -> {
                ((StatusActivity) requireActivity()).openPage(
                        NotificationRuleListActivity.RuleFragment.forType(type),
                        type.title(requireContext()));
                return true;
            });
            listRows.put(type, preference);
            section.add(preference);
        }
    }
}
