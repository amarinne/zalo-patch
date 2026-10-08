package com.ez.zalopatch;

/** Recognizes ordinary app launches without overriding explicit routed destinations. */
public final class MessagesHomeLaunch {
    private MessagesHomeLaunch() {}

    public static boolean accepts(String action, boolean launcherCategory, boolean routed, boolean cold, boolean restored) {
        if (routed || restored) return false;
        if ("android.intent.action.MAIN".equals(action) && launcherCategory) return true;
        // The module restart boundary starts the resolved launcher component directly.
        return cold && action == null && !launcherCategory;
    }
}
