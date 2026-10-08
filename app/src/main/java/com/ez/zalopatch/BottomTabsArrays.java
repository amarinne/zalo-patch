package com.ez.zalopatch;

import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;

/** Keeps host icon and preload arrays paired with retained tab enum values. */
public final class BottomTabsArrays {
    private BottomTabsArrays() {}

    public static final class Result {
        public final List<Object> tabs;
        public final int[] icons;
        public final boolean[] preloaded;
        Result(List<?> tabs, int[] icons, boolean[] preloaded) {
            this.tabs = new ArrayList<>(tabs);
            this.icons = icons;
            this.preloaded = preloaded;
        }
    }

    public static Result filter(List<?> original, List<?> retained, int[] icons,
                                boolean[] preloaded) {
        if (original == null || retained == null || icons == null || preloaded == null
                || icons.length != original.size() || preloaded.length != original.size()
                || new HashSet<>(original).size() != original.size()
                || new HashSet<>(retained).size() != retained.size()) {
            throw new IllegalArgumentException("tab arrays do not match unique tab list");
        }
        int[] filteredIcons = new int[retained.size()];
        boolean[] filteredPreloaded = new boolean[retained.size()];
        for (int i = 0; i < retained.size(); i++) {
            int source = original.indexOf(retained.get(i));
            if (source < 0) throw new IllegalArgumentException("tab has no host icon");
            filteredIcons[i] = icons[source];
            filteredPreloaded[i] = preloaded[source];
        }
        return new Result(retained, filteredIcons, filteredPreloaded);
    }

    /** Restores the native Group tab while preserving every existing host array entry. */
    public static Result configure(List<?> original, int[] icons, boolean[] preloaded,
                                   boolean hideDiscovery, boolean hideTimeline,
                                   boolean keepGroup, Object groupTab, int groupIcon) {
        List<Object> retained = new ArrayList<>(original);
        retained.removeIf(tab -> (hideDiscovery && "DISCOVERY".equals(String.valueOf(tab)))
                || (hideTimeline && "TIMELINE".equals(String.valueOf(tab))));
        Result filtered = filter(original, retained, icons, preloaded);
        if (!keepGroup || retained.stream().anyMatch(tab -> "GROUP".equals(String.valueOf(tab)))) {
            return filtered;
        }
        if (groupTab == null || !"GROUP".equals(String.valueOf(groupTab)) || groupIcon == 0) {
            throw new IllegalArgumentException("native Group tab or icon unavailable");
        }
        int position = Math.min(2, retained.size());
        retained.add(position, groupTab);
        int[] expandedIcons = new int[retained.size()];
        boolean[] expandedPreloaded = new boolean[retained.size()];
        System.arraycopy(filtered.icons, 0, expandedIcons, 0, position);
        System.arraycopy(filtered.preloaded, 0, expandedPreloaded, 0, position);
        expandedIcons[position] = groupIcon;
        // Native rebuild preloads only Messages. Group uses the host's lazy page route.
        System.arraycopy(filtered.icons, position, expandedIcons, position + 1, filtered.icons.length - position);
        System.arraycopy(filtered.preloaded, position, expandedPreloaded, position + 1, filtered.preloaded.length - position);
        return new Result(retained, expandedIcons, expandedPreloaded);
    }
}
