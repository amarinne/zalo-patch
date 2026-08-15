package com.ez.zalopatch;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class NotificationRuleListActivity {
    public static final String EXTRA_TYPE = "type";
    private NotificationRuleListActivity() {
    }

    public static final class RuleFragment extends ZpPreferenceFragment {
        private static final String ARG_TYPE = "type";
        private NotificationRuleStore.Type type;

        static RuleFragment forType(NotificationRuleStore.Type type) {
            RuleFragment fragment = new RuleFragment();
            Bundle args = new Bundle();
            args.putString(ARG_TYPE, type.name());
            fragment.setArguments(args);
            return fragment;
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            type = NotificationRuleStore.Type.fromName(requireArguments().getString(ARG_TYPE));
            buildScreen();
        }

        private void buildScreen() {
            Context context = requireContext();
            NotificationRuleStore.RuleSet rules = NotificationRuleStore.load(context);
            List<String> values = rules.list(type);
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);

            screen.addPreference(PreferenceUi.notice(context, isAccountType()
                    ? getString(R.string.zp_rule_account_notice)
                    : getString(R.string.zp_rule_keyword_notice)));

            PreferenceCategory current = PreferenceUi.category(screen,
                    getResources().getQuantityString(
                            R.plurals.zp_rule_items_count, values.size(), values.size()));
            ZpSection section = ZpSection.in(current);
            if (values.isEmpty()) {
                section.add(PreferenceUi.info(context, getString(R.string.zp_rule_no_items),
                        getString(R.string.zp_rule_no_items_summary)));
            } else {
                for (String value : values) {
                    ZpRowPreference row = PreferenceUi.action(context, value);
                    row.setOnPreferenceClickListener(preference -> {
                        showEditDialog(value);
                        return true;
                    });
                    section.add(row);
                }
            }
            ZpRowPreference add = PreferenceUi.action(context,
                    getString(R.string.zp_rule_add_item),
                    getString(R.string.zp_rule_add_summary));
            add.setOnPreferenceClickListener(preference -> {
                showAddDialog();
                return true;
            });
            section.add(add);

            setPreferenceScreen(screen);
        }

        private void showAddDialog() {
            EditText input = input("");
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.zp_rule_add_to,
                            type.title(requireContext()).toLowerCase(java.util.Locale.US)))
                    .setView(input)
                    .setNegativeButton(R.string.zp_cancel, null)
                    .setPositiveButton(R.string.zp_add, (dialog, which) -> {
                        List<String> additions = Arrays.asList(input.getText().toString().split("[,\\n]"));
                        ArrayList<String> updated = new ArrayList<>(
                                NotificationRuleStore.load(requireContext()).list(type));
                        updated.addAll(additions);
                        save(updated);
                    })
                    .show();
        }

        private void showEditDialog(String oldValue) {
            EditText input = input(oldValue);
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.zp_rule_edit_item)
                    .setView(input)
                    .setNeutralButton(R.string.zp_cancel, null)
                    .setNegativeButton(R.string.zp_delete, (dialog, which) -> {
                        ArrayList<String> updated = currentValues();
                        updated.remove(oldValue);
                        save(updated);
                    })
                    .setPositiveButton(R.string.zp_save, (dialog, which) -> {
                        ArrayList<String> updated = currentValues();
                        int index = updated.indexOf(oldValue);
                        if (index >= 0) {
                            updated.set(index, input.getText().toString());
                        }
                        save(updated);
                    })
                    .show();
        }

        private EditText input(String value) {
            EditText input = new EditText(requireContext());
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            input.setSingleLine(false);
            input.setText(value);
            input.setSelection(input.length());
            int padding = Math.round(24 * getResources().getDisplayMetrics().density);
            input.setPadding(padding, padding / 2, padding, padding / 2);
            return input;
        }

        private ArrayList<String> currentValues() {
            return new ArrayList<>(NotificationRuleStore.load(requireContext()).list(type));
        }

        private void save(List<String> values) {
            NotificationRuleStore.RuleSet rules = NotificationRuleStore.load(requireContext());
            if (!NotificationRuleStore.save(requireContext(), rules.with(type, values))) {
                Toast.makeText(requireContext(), R.string.zp_rule_save_failed,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            SettingsChanges.markChanged(requireContext(), "notifications.custom_rules");
            buildScreen();
        }

        private boolean isAccountType() {
            return type == NotificationRuleStore.Type.ACCOUNT_BLOCKLIST
                    || type == NotificationRuleStore.Type.ACCOUNT_EXCEPTIONS;
        }
    }
}
