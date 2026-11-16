package com.ez.zalopatch;

import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * UI-only same-user instance selection. The selected package chooses a scoped settings context;
 * it never becomes a provider, worker or restart target outside the caller that captured it.
 */
final class InstanceSelection {
    /** How a candidate can be described without claiming that hooks are active. */
    enum State {
        UNAVAILABLE,
        NOT_CHECKED,
        OBSERVED
    }

    private InstanceSelection() {
    }

    /** Restores a selection only while it is still registered; revoked scopes return to original. */
    static String restore(String saved, Set<String> registered) {
        return valid(saved, registered) ? saved : HostPackages.ZALO;
    }

    static boolean valid(String packageName, Set<String> registered) {
        return HostPackages.ZALO.equals(packageName)
                || (packageName != null && registered != null && registered.contains(packageName));
    }

    /** The original first, then explicitly registered same-user packages in stable order. */
    static List<String> candidates(Set<String> registered) {
        List<String> values = new ArrayList<>();
        values.add(HostPackages.ZALO);
        if (registered != null) {
            values.addAll(new TreeSet<>(registered));
        }
        return values;
    }

    /**
     * Availability is only installation visibility. Runtime observation comes from admitted
     * evidence, and a missing report is "Not checked", never a provider refusal.
     */
    static State state(boolean registered, boolean installed, boolean runtimeObserved) {
        if (!registered || !installed) return State.UNAVAILABLE;
        return runtimeObserved ? State.OBSERVED : State.NOT_CHECKED;
    }

    static boolean installed(PackageManager manager, String packageName) {
        if (manager == null || packageName == null) return false;
        try {
            manager.getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return false;
        }
    }
}
