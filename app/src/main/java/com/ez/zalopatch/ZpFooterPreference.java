package com.ez.zalopatch;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

/** Non-interactive page footer, following SettingsLib's dedicated footer-preference pattern. */
public final class ZpFooterPreference extends Preference {
    ZpFooterPreference(Context context) {
        super(context);
        setLayoutResource(androidx.preference.R.layout.preference_material);
        setIconSpaceReserved(false);
        setPersistent(false);
        setSelectable(false);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        Context context = holder.itemView.getContext();
        int horizontal = context.getResources().getDimensionPixelSize(
                R.dimen.zp_row_padding_horizontal);
        int top = context.getResources().getDimensionPixelSize(R.dimen.zp_page_padding);
        int bottom = context.getResources().getDimensionPixelSize(R.dimen.zp_section_spacing);
        holder.itemView.setMinimumHeight(0);
        holder.itemView.setPadding(horizontal, top, horizontal, bottom);
        holder.itemView.setBackground(new ColorDrawable(android.graphics.Color.TRANSPARENT));

        bindText(holder, android.R.id.title, context);
        bindText(holder, android.R.id.summary, context);
    }

    private static void bindText(
            PreferenceViewHolder holder, int id, Context context) {
        View view = holder.findViewById(id);
        if (!(view instanceof TextView)) {
            return;
        }
        TextView text = (TextView) view;
        boolean visible = text.getText().length() > 0;
        text.setVisibility(visible ? View.VISIBLE : View.GONE);
        text.setGravity(Gravity.CENTER_HORIZONTAL);
        text.setTextColor(ContextCompat.getColor(context, R.color.zp_text_secondary));
        TextViewCompat.setTextAppearance(text, R.style.TextAppearance_ZaloPatch_Footer);
    }
}
