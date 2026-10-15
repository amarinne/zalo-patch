package com.ez.zalopatch;

/** Pure capability decision table for user-facing settings rows. */
final class RequirementGate {
    private RequirementGate() {
    }

    static boolean isMet(Tweaks.Requirement requirement, RootAccess.State rootState,
                         RuntimeEnvironment.ResourceHooks resourceHooks, int sdkInt) {
        if (requirement == Tweaks.Requirement.ROOT) {
            return rootState == RootAccess.State.GRANTED;
        }
        if (requirement == Tweaks.Requirement.RESOURCE_HOOKS) {
            return resourceHooks != RuntimeEnvironment.ResourceHooks.UNAVAILABLE;
        }
        if (requirement == Tweaks.Requirement.SHARED_RECORDINGS) {
            return sdkInt >= android.os.Build.VERSION_CODES.Q;
        }
        return true;
    }

    static boolean isMet(android.content.Context context, Tweaks.Requirement requirement) {
        RuntimeEnvironment.Snapshot runtime = RuntimeEnvironment.current(context);
        return isMet(requirement, RootAccess.cached(context), runtime.resourceHooks,
                android.os.Build.VERSION.SDK_INT);
    }
}
