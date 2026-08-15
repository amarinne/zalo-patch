package com.ez.zalopatch;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreferenceCompat;

/** Prominent section-wide switch, following Android SettingsLib's main-switch pattern. */
public final class ZpMainSwitchPreference extends SwitchPreferenceCompat {
    ZpMainSwitchPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.zp_preference_main_switch);
        setIconSpaceReserved(false);
        setPersistent(false);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        Context context = holder.itemView.getContext();
        holder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        View container = holder.findViewById(R.id.zp_main_switch_container);
        if (container != null) {
            container.setDuplicateParentStateEnabled(true);
        }
        TextView title = (TextView) holder.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(ContextCompat.getColor(context, R.color.zp_main_switch_text));
        }
        TextView summary = (TextView) holder.findViewById(android.R.id.summary);
        if (summary != null) {
            boolean visible = summary.getText().length() > 0;
            summary.setVisibility(visible ? View.VISIBLE : View.GONE);
            summary.setTextColor(ContextCompat.getColor(
                    context, R.color.zp_main_switch_text_secondary));
        }
    }
}
