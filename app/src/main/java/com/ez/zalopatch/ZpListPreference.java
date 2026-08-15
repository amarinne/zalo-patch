package com.ez.zalopatch;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceViewHolder;

/** Selector row for connected groups. Keeps the AndroidX list dialog behavior. */
public final class ZpListPreference extends ListPreference implements ZpStyledPreference {
    private final ZpRowStyle style = new ZpRowStyle();

    ZpListPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.zp_preference_row);
        setIconSpaceReserved(false);
        setPersistent(false);
        style.setChevron(true);
    }

    @Override
    public ZpRowStyle rowStyle() {
        return style;
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        style.bind(holder);
    }
}
