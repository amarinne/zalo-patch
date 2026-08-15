package com.ez.zalopatch;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Shared settings list behavior. Rows draw their own connected-group dividers, so the framework
 * list divider is removed here.
 */
public abstract class ZpPreferenceFragment extends PreferenceFragmentCompat {
    @Override
    public void onDisplayPreferenceDialog(@NonNull Preference preference) {
        if (!(preference instanceof ZpListPreference)) {
            super.onDisplayPreferenceDialog(preference);
            return;
        }
        ZpListPreference list = (ZpListPreference) preference;
        CharSequence[] entries = list.getEntries();
        CharSequence[] values = list.getEntryValues();
        if (entries == null || values == null) {
            super.onDisplayPreferenceDialog(preference);
            return;
        }
        CharSequence title = list.getDialogTitle() == null
                ? list.getTitle() : list.getDialogTitle();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setSingleChoiceItems(entries, list.findIndexOfValue(list.getValue()),
                        (dialog, which) -> {
                            String newValue = values[which].toString();
                            Preference.OnPreferenceChangeListener listener =
                                    list.getOnPreferenceChangeListener();
                            if (listener == null
                                    || listener.onPreferenceChange(list, newValue)) {
                                list.setValue(newValue);
                            }
                            dialog.dismiss();
                        })
                .setNegativeButton(R.string.zp_cancel, null)
                .show();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // A page transition must move the page, not each row. Slide is a Visibility transition and
        // descends into a ViewGroup's children unless the root is marked a transition group, which
        // is what made rows animate individually instead of the page sliding as one surface.
        if (view instanceof ViewGroup) {
            ((ViewGroup) view).setTransitionGroup(true);
        }
        setDivider(null);
        setDividerHeight(0);
        RecyclerView list = getListView();
        if (list == null) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        list.setClipToPadding(false);
        list.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        int horizontal = getResources().getDimensionPixelSize(R.dimen.zp_page_padding);
        list.setPadding(horizontal, Math.round(8 * density), horizontal, Math.round(16 * density));
        list.setItemAnimator(new DefaultItemAnimator());
    }
}
