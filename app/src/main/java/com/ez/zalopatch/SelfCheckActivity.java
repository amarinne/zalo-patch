package com.ez.zalopatch;

import android.content.Context;
import android.os.Bundle;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import java.util.ArrayList;
import java.util.List;

public final class SelfCheckActivity {
    private SelfCheckActivity() {
    }

    public static final class SelfCheckFragment extends ZpPreferenceFragment {
        private ZpRowPreference metrics;
        private PreferenceCategory failedCategory;
        private PreferenceCategory staleCategory;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            buildScreen();
        }

        @Override
        public void onResume() {
            super.onResume();
            refresh();
        }

        private void buildScreen() {
            Context context = requireContext();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);

            SymbolSchema.Active schema = SymbolSchema.active(context);
            screen.addPreference(PreferenceUi.info(context,
                    getString(R.string.zp_about_version, BuildConfig.VERSION_NAME),
                    getString(R.string.zp_about_meta, BuildConfig.VERSION_CODE,
                            schema.schemaVersion, schema.schemaRevision, schema.source)));
            Preference spacer = new Preference(context);
            spacer.setLayoutResource(R.layout.zp_preference_spacer);
            spacer.setSelectable(false);
            spacer.setPersistent(false);
            spacer.setIconSpaceReserved(false);
            screen.addPreference(spacer);

            metrics = PreferenceUi.metrics(context,
                    getString(R.string.zp_self_check_heading), null);
            screen.addPreference(metrics);

            failedCategory = PreferenceUi.category(screen, "");
            staleCategory = PreferenceUi.category(screen, "");

            screen.addPreference(PreferenceUi.footer(context,
                    getString(R.string.zp_self_check_footer), ""));
            setPreferenceScreen(screen);
            refresh();
        }

        private void refresh() {
            if (metrics == null) {
                return;
            }
            List<SelfCheckData.Row> rows = SelfCheckData.load(requireContext());
            SelfCheckData.Counts counts = SelfCheckData.counts(rows);
            String runtime = rows.isEmpty()
                    ? getString(R.string.zp_self_check_empty_summary)
                    : headline(counts);
            metrics.setSummary(runtime);
            metrics.chips(rows.isEmpty() ? new ZpRowStyle.Chip[0] : countChips(counts));
            metrics.refreshStyle();
            refreshAttentionCategory(failedCategory, rows, "failed");
            refreshAttentionCategory(staleCategory, rows, "stale");
        }

        private String headline(SelfCheckData.Counts counts) {
            int attention = counts.failed + counts.stale;
            if (attention == 0) {
                return getString(R.string.zp_self_check_no_attention);
            }
            return getResources().getQuantityString(
                    R.plurals.zp_self_check_attention, attention, attention);
        }

        private ZpRowStyle.Chip[] countChips(SelfCheckData.Counts counts) {
            List<ZpRowStyle.Chip> chips = new ArrayList<>();
            if (counts.failed > 0) {
                chips.add(new ZpRowStyle.Chip(getString(R.string.zp_status_failed, counts.failed),
                        R.color.zp_status_error));
            }
            if (counts.stale > 0) {
                chips.add(new ZpRowStyle.Chip(getString(R.string.zp_status_stale, counts.stale),
                        R.color.zp_status_warn));
            }
            chips.add(new ZpRowStyle.Chip(getString(R.string.zp_status_active, counts.active),
                    R.color.zp_status_active));
            chips.add(new ZpRowStyle.Chip(
                    getString(R.string.zp_status_installed, counts.installedNoHits),
                    R.color.zp_status_neutral));
            chips.add(new ZpRowStyle.Chip(getString(R.string.zp_status_disabled, counts.disabled),
                    R.color.zp_status_neutral));
            chips.add(new ZpRowStyle.Chip(getString(R.string.zp_status_total, counts.total()),
                    R.color.zp_status_neutral));
            return chips.toArray(new ZpRowStyle.Chip[0]);
        }

        private void refreshAttentionCategory(
                PreferenceCategory category, List<SelfCheckData.Row> rows, String status) {
            List<SelfCheckData.Row> statusRows = SelfCheckData.rowsForStatus(rows, status);
            category.removeAll();
            category.setVisible(!statusRows.isEmpty());
            category.setTitle(getString(R.string.zp_self_check_status_count,
                    SelfCheckData.statusTitle(requireContext(), status), statusRows.size()));
            ZpSection section = ZpSection.in(category);
            int color = "failed".equals(status) ? R.color.zp_status_error : R.color.zp_status_warn;
            for (SelfCheckData.Row row : statusRows) {
                ZpRowPreference preference = PreferenceUi.action(requireContext(), row.feature,
                        row.listSummary(requireContext()));
                preference.dot(color);
                preference.setOnPreferenceClickListener(clicked -> {
                    showDetails(row);
                    return true;
                });
                section.add(preference);
            }
        }

        private void showDetails(SelfCheckData.Row row) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(row.feature)
                    .setMessage(row.debugDetails(requireContext()))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }
}
