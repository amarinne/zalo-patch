package com.ez.zalopatch;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

/** Inline multiline diagnostic description field matching the shared report flow. */
final class ZpDiagnosticDescriptionPreference extends Preference
        implements ZpStyledPreference {
    interface Listener {
        void onValueChanged(String value);
    }

    private final ZpRowStyle style = new ZpRowStyle();
    private String value = "";
    private boolean inputEnabled = true;
    private Listener listener;

    ZpDiagnosticDescriptionPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.zp_preference_diagnostic_description);
        setIconSpaceReserved(false);
        setPersistent(false);
        setSelectable(false);
    }

    @Override
    public ZpRowStyle rowStyle() {
        return style;
    }

    void value(String next) {
        value = next == null ? "" : next;
        notifyChanged();
    }

    void inputEnabled(boolean enabled) {
        inputEnabled = enabled;
        notifyChanged();
    }

    void listener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        style.bind(holder);
        holder.itemView.setFocusable(false);
        holder.itemView.setFocusableInTouchMode(false);
        if (holder.itemView instanceof ViewGroup) {
            ((ViewGroup) holder.itemView).setDescendantFocusability(
                    ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
        EditText input = (EditText) holder.findViewById(R.id.zp_diagnostic_description_input);
        if (input == null) return;

        Object oldTag = input.getTag();
        if (oldTag instanceof TextWatcher) input.removeTextChangedListener((TextWatcher) oldTag);
        if (!value.contentEquals(input.getText())) {
            input.setText(value);
            input.setSelection(input.length());
        }
        input.setEnabled(inputEnabled);
        input.setFocusableInTouchMode(inputEnabled);
        input.setClickable(inputEnabled);
        boolean[] editing = new boolean[1];
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable editable) {
                if (editing[0]) return;
                String entered = editable.toString();
                String bounded = DiagnosticReportContract.utf8Prefix(
                        entered, DiagnosticReportContract.DESCRIPTION_BYTES);
                if (!bounded.equals(entered)) {
                    editing[0] = true;
                    input.setText(bounded);
                    input.setSelection(input.length());
                    editing[0] = false;
                }
                value = bounded;
                if (listener != null) listener.onValueChanged(bounded);
            }
        };
        input.addTextChangedListener(watcher);
        input.setTag(watcher);
    }

}
