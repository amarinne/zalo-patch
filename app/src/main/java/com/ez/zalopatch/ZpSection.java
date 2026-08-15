package com.ez.zalopatch;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;

/**
 * Adds rows to one connected group.
 *
 * <p>Shapes are reassigned across the whole group on every add, so the group is always correctly
 * shaped and there is no separate build step to forget.
 */
final class ZpSection {
    private final PreferenceGroup group;
    private final java.util.List<ZpStyledPreference> rows = new java.util.ArrayList<>();

    private ZpSection(PreferenceGroup group) {
        this.group = group;
    }

    static ZpSection in(PreferenceGroup group) {
        return new ZpSection(group);
    }

    ZpSection add(Preference preference) {
        group.addPreference(preference);
        if (preference instanceof ZpStyledPreference) {
            rows.add((ZpStyledPreference) preference);
            applyShapes();
        }
        return this;
    }

    /**
     * Assigns first/middle/last across the group after every add, so the group is correctly shaped
     * at all times without a separate build step that can be forgotten.
     */
    void applyShapes() {
        java.util.List<ZpStyledPreference> visible = new java.util.ArrayList<>();
        for (ZpStyledPreference row : rows) {
            if (!(row instanceof Preference) || ((Preference) row).isVisible()) {
                visible.add(row);
            }
        }
        if (visible.isEmpty()) {
            return;
        }
        if (visible.size() == 1) {
            visible.get(0).rowStyle().setShape(ZpRowStyle.Shape.SINGLE);
            return;
        }
        for (int index = 0; index < visible.size(); index++) {
            ZpRowStyle.Shape shape;
            if (index == 0) {
                shape = ZpRowStyle.Shape.FIRST;
            } else if (index == visible.size() - 1) {
                shape = ZpRowStyle.Shape.LAST;
            } else {
                shape = ZpRowStyle.Shape.MIDDLE;
            }
            visible.get(index).rowStyle().setShape(shape);
        }
    }

    /** Dependency registration requires the preference to already belong to this hierarchy. */
    ZpSection addDependent(Preference preference, String dependencyKey) {
        add(preference);
        preference.setDependency(dependencyKey);
        return this;
    }

    static PreferenceCategory category(PreferenceGroup parent, String label) {
        PreferenceCategory category = new PreferenceCategory(parent.getContext());
        category.setTitle(label);
        category.setIconSpaceReserved(false);
        category.setLayoutResource(R.layout.zp_preference_category);
        parent.addPreference(category);
        return category;
    }
}
