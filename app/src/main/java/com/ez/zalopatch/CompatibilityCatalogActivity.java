package com.ez.zalopatch;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CompatibilityCatalogActivity {
    private static final ExecutorService CHECK_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "zp-compatibility-check");
                thread.setDaemon(true);
                return thread;
            });

    private CompatibilityCatalogActivity() {
    }

    public static final class CatalogFragment extends ZpPreferenceFragment {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            render();
        }

        private void render() {
            Context context = requireContext();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
            long installed = SymbolSchema.installedZaloVersionCode(context);
            SymbolSchema.Active active = SymbolSchema.active(context);
            screen.addPreference(PreferenceUi.notice(context, active.valid
                    ? getString(R.string.zp_compatibility_catalog_mapped,
                            installed, active.source)
                    : getString(R.string.zp_compatibility_catalog_unmapped, installed)));

            PreferenceCategory updates = PreferenceUi.category(screen,
                    getString(R.string.zp_compatibility_catalog_updates));
            ZpSection updateSection = ZpSection.in(updates);
            ZpRowPreference check = PreferenceUi.action(context,
                    getString(R.string.zp_compatibility_catalog_check),
                    ZaloArtifactState.summary(context));
            check.setOnPreferenceClickListener(preference -> {
                check.setEnabled(false);
                check.setSummary(R.string.zp_compatibility_catalog_checking);
                Context applicationContext = context.getApplicationContext();
                CHECK_EXECUTOR.execute(() -> {
                    ZaloArtifactState.ManualCheckResult result =
                            ZaloArtifactState.checkNow(applicationContext);
                    androidx.fragment.app.FragmentActivity activity = getActivity();
                    if (activity == null) return;
                    activity.runOnUiThread(() -> {
                        if (!isAdded()) return;
                        Toast.makeText(requireContext(), resultMessage(result),
                                Toast.LENGTH_LONG).show();
                        render();
                    });
                });
                return true;
            });
            updateSection.add(check);

            PreferenceCategory profiles = PreferenceUi.category(screen,
                    getString(R.string.zp_compatibility_catalog_profiles));
            ZpSection section = ZpSection.in(profiles);
            List<SymbolSchema.ProfileInfo> catalog = SymbolSchema.catalog(context);
            for (SymbolSchema.ProfileInfo profile : catalog) {
                String title = getString(R.string.zp_compatibility_catalog_version,
                        profile.versionCode);
                ZpRowPreference row = PreferenceUi.action(context, title,
                        getString(R.string.zp_compatibility_catalog_profile_summary,
                                profile.source, profile.schemaRevision));
                if (profile.versionCode == installed) {
                    row.dot(R.color.zp_status_active);
                }
                row.setOnPreferenceClickListener(preference -> {
                    showProfile(profile);
                    return true;
                });
                section.add(row);
            }
            setPreferenceScreen(screen);
        }

        private String resultMessage(ZaloArtifactState.ManualCheckResult result) {
            switch (result.catalogStatus) {
                case "updated":
                    return getString(R.string.zp_compatibility_catalog_check_updated);
                case "current":
                    return getString(R.string.zp_compatibility_catalog_check_current);
                case "pending":
                    return getString(R.string.zp_compatibility_catalog_check_pending,
                            result.versionCode);
                case "unknown":
                    return getString(R.string.zp_compatibility_catalog_check_unknown,
                            result.versionCode);
                case "suppressed":
                    return getString(R.string.zp_compatibility_catalog_check_suppressed);
                default:
                    String detail = result.error.isEmpty()
                            ? result.catalogStatus : result.error;
                    return getString(R.string.zp_compatibility_catalog_check_failed, detail);
            }
        }

        private void showProfile(SymbolSchema.ProfileInfo profile) {
            TextView text = new TextView(requireContext());
            int padding = Math.round(16 * getResources().getDisplayMetrics().density);
            text.setPadding(padding, padding, padding, padding);
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextIsSelectable(true);
            StringBuilder value = new StringBuilder();
            value.append(profile.source).append(" · schema v1.")
                    .append(profile.schemaRevision).append("\n\n");
            if (!profile.notes.isEmpty()) value.append(profile.notes).append("\n\n");
            for (String path : profile.symbolPaths) value.append(path).append('\n');
            text.setText(value.toString());
            ScrollView scroll = new ScrollView(requireContext());
            scroll.addView(text);
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.zp_compatibility_catalog_version,
                            profile.versionCode))
                    .setView(scroll)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }
}
