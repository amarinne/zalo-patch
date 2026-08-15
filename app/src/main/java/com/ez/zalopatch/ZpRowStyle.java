package com.ez.zalopatch;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceViewHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared row appearance for connected preference groups.
 *
 * <p>One layout serves every position in a group. The group builder assigns the shape, and this
 * class applies the matching background, divider, trailing value, navigation indicator, status
 * dot, and status chips.
 */
final class ZpRowStyle {
    enum Shape {
        SINGLE,
        FIRST,
        MIDDLE,
        LAST
    }

    static final class Chip {
        final String text;
        final int colorRes;

        Chip(String text, @ColorRes int colorRes) {
            this.text = text;
            this.colorRes = colorRes;
        }
    }

    private Shape shape = Shape.SINGLE;

    private String value;
    private boolean chevron;
    private boolean showWidget;
    private boolean muted;
    private boolean destructive;
    private Integer dotColorRes;
    private final List<Chip> chips = new ArrayList<>();

    void setShape(Shape shape) {
        this.shape = shape;
    }

    void setValue(String value) {
        this.value = value;
    }

    void setChevron(boolean chevron) {
        this.chevron = chevron;
    }

    void setShowWidget(boolean showWidget) {
        this.showWidget = showWidget;
    }

    void setMuted(boolean muted) {
        this.muted = muted;
    }

    void setDestructive(boolean destructive) {
        this.destructive = destructive;
    }

    void setDot(@ColorRes int colorRes) {
        this.dotColorRes = colorRes;
    }

    void setChips(List<Chip> newChips) {
        chips.clear();
        if (newChips != null) {
            chips.addAll(newChips);
        }
    }

    void bind(PreferenceViewHolder holder) {
        Context context = holder.itemView.getContext();
        View container = holder.findViewById(R.id.zp_row_container);
        if (container != null) {
            // AndroidX puts a rectangular selectable background on the item view. Keep the item
            // clickable, but draw its pressed/ripple state only through the shaped inner card.
            holder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            container.setDuplicateParentStateEnabled(true);
            container.setBackgroundResource(backgroundRes());
        }

        View divider = holder.findViewById(R.id.zp_row_divider);
        if (divider != null) {
            divider.setVisibility(hasRowBelow() ? View.VISIBLE : View.GONE);
        }

        TextView title = (TextView) holder.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(ContextCompat.getColor(context, titleColorRes()));
        }

        TextView summary = (TextView) holder.findViewById(android.R.id.summary);
        if (summary != null) {
            summary.setVisibility(summary.getText().length() == 0 ? View.GONE : View.VISIBLE);
            summary.setTextColor(ContextCompat.getColor(context, summaryColorRes()));
        }

        TextView valueView = (TextView) holder.findViewById(R.id.zp_row_value);
        if (valueView != null) {
            boolean hasValue = value != null && !value.isEmpty();
            valueView.setVisibility(hasValue ? View.VISIBLE : View.GONE);
            valueView.setText(hasValue ? value : "");
        }

        View chevronView = holder.findViewById(R.id.zp_row_chevron);
        if (chevronView != null) {
            chevronView.setVisibility(chevron ? View.VISIBLE : View.GONE);
        }

        View widgetFrame = holder.findViewById(android.R.id.widget_frame);
        if (widgetFrame != null) {
            widgetFrame.setVisibility(showWidget ? View.VISIBLE : View.GONE);
        }

        View dot = holder.findViewById(R.id.zp_row_dot);
        if (dot != null) {
            if (dotColorRes == null) {
                dot.setVisibility(View.GONE);
            } else {
                dot.setVisibility(View.VISIBLE);
                dot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(context, dotColorRes)));
                dot.setContentDescription(context.getString(dotDescriptionRes()));
            }
        }

        View chipContainer = holder.findViewById(R.id.zp_row_chips);
        if (chipContainer instanceof ViewGroup) {
            bindChips(context, (ViewGroup) chipContainer);
        }
    }

    private void bindChips(Context context, ViewGroup container) {
        if (chips.isEmpty()) {
            container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);
        if (container.getChildCount() != chips.size()) {
            container.removeAllViews();
            for (Chip chip : chips) {
                container.addView(chipView(context, container, chip));
            }
            return;
        }
        for (int index = 0; index < chips.size(); index++) {
            View child = container.getChildAt(index);
            if (!(child instanceof TextView)) {
                container.removeAllViews();
                for (Chip chip : chips) {
                    container.addView(chipView(context, container, chip));
                }
                return;
            }
            Chip chip = chips.get(index);
            TextView view = (TextView) child;
            view.setText(chip.text);
            view.setTextColor(ContextCompat.getColor(context, chip.colorRes));
        }
    }

    private static TextView chipView(Context context, ViewGroup parent, Chip chip) {
        TextView view = (TextView) LayoutInflater.from(context)
                .inflate(R.layout.zp_status_chip, parent, false);
        view.setText(chip.text);
        view.setTextColor(ContextCompat.getColor(context, chip.colorRes));
        return view;
    }

    private int dotDescriptionRes() {
        if (dotColorRes != null && dotColorRes == R.color.zp_status_error) {
            return R.string.zp_accessibility_status_failed;
        }
        if (dotColorRes != null && dotColorRes == R.color.zp_status_warn) {
            return R.string.zp_accessibility_status_stale;
        }
        if (dotColorRes != null && dotColorRes == R.color.zp_status_active) {
            return R.string.zp_accessibility_status_active;
        }
        return R.string.zp_accessibility_status_neutral;
    }

    private boolean hasRowBelow() {
        return shape == Shape.FIRST || shape == Shape.MIDDLE;
    }

    private int backgroundRes() {
        switch (shape) {
            case FIRST:
                return R.drawable.zp_row_top;
            case MIDDLE:
                return R.drawable.zp_row_middle;
            case LAST:
                return R.drawable.zp_row_bottom;
            case SINGLE:
            default:
                return R.drawable.zp_row_single;
        }
    }

    private int titleColorRes() {
        if (destructive) {
            return R.color.zp_status_error;
        }
        if (muted) {
            return R.color.zp_text_secondary;
        }
        return R.color.zp_on_surface;
    }

    private int summaryColorRes() {
        return R.color.zp_text_secondary;
    }
}
