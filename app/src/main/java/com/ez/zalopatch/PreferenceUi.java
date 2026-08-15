package com.ez.zalopatch;

import android.content.Context;

import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;

/**
 * Factory for the connected-group row system. Every settings page builds rows from here so that
 * shape, spacing, and navigation affordances stay consistent.
 */
final class PreferenceUi {
    private PreferenceUi() {
    }

    /** Compact section label. */
    static PreferenceCategory category(PreferenceGroup parent, String label) {
        return ZpSection.category(parent, label);
    }

    /** Row that opens another page and needs no description. */
    static ZpRowPreference nav(Context context, String title) {
        return nav(context, title, null);
    }

    /** Row that opens another page. */
    static ZpRowPreference nav(Context context, String title, String summary) {
        ZpRowPreference preference = new ZpRowPreference(context);
        preference.setTitle(title);
        preference.setSummary(summary);
        return preference.chevron(true);
    }

    /** Row that runs an action and needs no description. */
    static ZpRowPreference action(Context context, String title) {
        return action(context, title, null);
    }

    /** Row that runs an action on this page. */
    static ZpRowPreference action(Context context, String title, String summary) {
        ZpRowPreference preference = new ZpRowPreference(context);
        preference.setTitle(title);
        preference.setSummary(summary);
        return preference;
    }

    /** Read-only row inside a group. */
    static ZpRowPreference info(Context context, String title, String summary) {
        ZpRowPreference preference = new ZpRowPreference(context);
        preference.setTitle(title);
        preference.setSummary(summary);
        preference.setSelectable(false);
        return preference;
    }

    /** Row for a control that the current build cannot support. */
    static ZpRowPreference unavailable(Context context, String title, String summary) {
        ZpRowPreference preference = info(context, title, summary);
        preference.muted(true);
        return preference;
    }

    /** Scope or privacy notice. Not a card and not a navigation row. */
    static ZpRowPreference notice(Context context, String text) {
        ZpRowPreference preference = new ZpRowPreference(context, R.layout.zp_preference_notice);
        preference.setTitle(text);
        preference.setSelectable(false);
        return preference;
    }

    /** Runtime count row for Self-check. Wrapping status chips, no metric grid. */
    static ZpRowPreference metrics(Context context, String title, String summary) {
        ZpRowPreference preference = new ZpRowPreference(context, R.layout.zp_preference_metrics);
        preference.setTitle(title);
        preference.setSummary(summary);
        preference.setSelectable(false);
        return preference;
    }

    /** Compact page footer text. */
    static ZpFooterPreference footer(Context context, String title, String summary) {
        ZpFooterPreference preference = new ZpFooterPreference(context);
        preference.setTitle(title);
        preference.setSummary(summary);
        return preference;
    }

    static ZpMainSwitchPreference mainSwitch(
            Context context, String key, String title, String summary) {
        ZpMainSwitchPreference preference = new ZpMainSwitchPreference(context);
        preference.setKey(key);
        preference.setTitle(title);
        preference.setSummary(summary);
        return preference;
    }

    static ZpSwitchPreference toggle(Context context, String key, String title, CharSequence summary) {
        ZpSwitchPreference preference = new ZpSwitchPreference(context);
        preference.setKey(key);
        preference.setTitle(title);
        preference.setSummary(summary);
        return preference;
    }
}
