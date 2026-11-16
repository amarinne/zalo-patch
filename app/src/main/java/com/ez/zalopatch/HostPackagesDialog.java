package com.ez.zalopatch;

import android.content.Context;
import android.os.Bundle;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

final class HostPackagesDialog {
    private HostPackagesDialog() {}

    static String summary(Context context) {
        String packages = String.join(", ", HostPackages.additional(context));
        return packages.isEmpty() ? context.getString(R.string.zp_host_packages_none) : packages;
    }

    static void show(Context context, ZpRowPreference row) {
        show(context, row, null);
    }

    static void show(Context context, ZpRowPreference row, Runnable registrationChanged) {
        EditText input = new EditText(context);
        input.setHint("com.zing.zalo.clone");
        input.setText(String.join("\n", HostPackages.additional(context)));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int padding = Math.round(24 * context.getResources().getDisplayMetrics().density);
        android.widget.FrameLayout container = new android.widget.FrameLayout(context);
        container.setPadding(padding, 0, padding, 0);
        container.addView(input);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.zp_host_packages_title)
                .setMessage(R.string.zp_host_packages_explanation)
                .setView(container)
                .setNegativeButton(R.string.zp_cancel, null)
                .setPositiveButton(R.string.zp_save, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(button -> {
                    Bundle result = context.getContentResolver().call(ConfigProvider.URI,
                            "set_host_packages", input.getText().toString(), null);
                    if (result == null || !result.getBoolean("saved", false)) {
                        input.setError(context.getString(R.string.zp_host_packages_invalid));
                        return;
                    }
                    row.setSummary(summary(context));
                    if (registrationChanged != null) registrationChanged.run();
                    dialog.dismiss();
                }));
        dialog.show();
    }
}
