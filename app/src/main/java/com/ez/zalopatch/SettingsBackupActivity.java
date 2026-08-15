package com.ez.zalopatch;

import android.content.Context;
import android.os.Bundle;

import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class SettingsBackupActivity {
    private SettingsBackupActivity() {
    }

    public static final class BackupFragment extends ZpPreferenceFragment {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context context = requireContext();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);

            PreferenceCategory actions = PreferenceUi.category(screen,
                    getString(R.string.zp_backup_actions));
            ZpSection section = ZpSection.in(actions);

            ZpRowPreference export = PreferenceUi.action(context,
                    getString(R.string.zp_backup_export));
            export.setOnPreferenceClickListener(preference -> {
                ((StatusActivity) requireActivity()).exportSettings();
                return true;
            });
            section.add(export);

            ZpRowPreference importSettings = PreferenceUi.action(context,
                    getString(R.string.zp_backup_import));
            importSettings.setOnPreferenceClickListener(preference -> {
                confirmImport();
                return true;
            });
            section.add(importSettings);

            screen.addPreference(PreferenceUi.footer(context,
                    getString(R.string.zp_backup_footer), ""));
            setPreferenceScreen(screen);
        }

        private void confirmImport() {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.zp_backup_import_dialog_title)
                    .setMessage(R.string.zp_backup_import_dialog_message)
                    .setNegativeButton(R.string.zp_cancel, null)
                    .setPositiveButton(R.string.zp_choose_file, (dialog, which) ->
                            ((StatusActivity) requireActivity()).importSettings())
                    .show();
        }
    }
}
