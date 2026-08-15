package com.ez.zalopatch;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreferenceCompat;

import java.util.Arrays;

/** Toggle row for connected groups. Status chips stay inside the row instead of a second row. */
public final class ZpSwitchPreference extends SwitchPreferenceCompat implements ZpStyledPreference {
    private final ZpRowStyle style = new ZpRowStyle();

    ZpSwitchPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.zp_preference_row);
        setIconSpaceReserved(false);
        setPersistent(false);
        style.setShowWidget(true);
    }

    @Override
    public ZpRowStyle rowStyle() {
        return style;
    }

    ZpSwitchPreference chips(ZpRowStyle.Chip... chips) {
        style.setChips(chips == null ? null : Arrays.asList(chips));
        return this;
    }

    void refreshStyle() {
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        style.bind(holder);
    }
}
