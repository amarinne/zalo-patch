package com.ez.zalopatch;

import android.content.Context;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import java.util.Arrays;
import java.util.List;

/**
 * Navigation, info, and notice row for connected groups. Layout choice decides the
 * variant; the shared {@link ZpRowStyle} decides the position shape and the optional trailing
 * value, chevron, dot, and chips.
 */
public class ZpRowPreference extends Preference implements ZpStyledPreference {
    private final ZpRowStyle style = new ZpRowStyle();

    ZpRowPreference(Context context) {
        this(context, R.layout.zp_preference_row);
    }

    ZpRowPreference(Context context, int layoutResource) {
        super(context);
        setLayoutResource(layoutResource);
        setIconSpaceReserved(false);
        setPersistent(false);
    }

    @Override
    public ZpRowStyle rowStyle() {
        return style;
    }

    ZpRowPreference value(String value) {
        style.setValue(value);
        return this;
    }

    ZpRowPreference chevron(boolean chevron) {
        style.setChevron(chevron);
        return this;
    }

    ZpRowPreference dot(@ColorRes int colorRes) {
        style.setDot(colorRes);
        return this;
    }

    ZpRowPreference muted(boolean muted) {
        style.setMuted(muted);
        return this;
    }

    ZpRowPreference destructive(boolean destructive) {
        style.setDestructive(destructive);
        return this;
    }

    ZpRowPreference chips(ZpRowStyle.Chip... chips) {
        List<ZpRowStyle.Chip> values = chips == null ? null : Arrays.asList(chips);
        style.setChips(values);
        return this;
    }

    void refreshStyle() {
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        // AndroidX does not reliably clear a recycled summary TextView when the next Preference
        // has a null summary. Bind the current value explicitly before deciding visibility.
        android.widget.TextView summary =
                (android.widget.TextView) holder.findViewById(android.R.id.summary);
        if (summary != null) {
            CharSequence current = getSummary();
            summary.setText(current == null ? "" : current);
        }
        style.bind(holder);
    }
}
