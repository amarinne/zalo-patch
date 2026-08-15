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

public final class CompatibilityCatalogActivity {
    private CompatibilityCatalogActivity() {
    }

    public static final class CatalogFragment extends ZpPreferenceFragment {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
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
                boolean scheduled = ZaloArtifactState.schedule(context, true);
                Toast.makeText(context, scheduled
                                ? R.string.zp_compatibility_catalog_check_scheduled
                                : R.string.zp_compatibility_catalog_check_failed,
                        Toast.LENGTH_SHORT).show();
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
                String summary = getString(R.string.zp_compatibility_catalog_profile_summary,
                        profile.schemaRevision, profile.verification, profile.symbolPaths.size());
                ZpRowPreference row = PreferenceUi.action(context, title, summary);
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

        private void showProfile(SymbolSchema.ProfileInfo profile) {
            TextView text = new TextView(requireContext());
            int padding = Math.round(16 * getResources().getDisplayMetrics().density);
            text.setPadding(padding, padding, padding, padding);
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextIsSelectable(true);
            StringBuilder value = new StringBuilder();
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
