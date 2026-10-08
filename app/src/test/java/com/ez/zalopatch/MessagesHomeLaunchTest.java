package com.ez.zalopatch;

import org.junit.Test;
import static org.junit.Assert.*;

public class MessagesHomeLaunchTest {
    @Test public void launcherColdAndWarmStartsAreAccepted() {
        assertTrue(MessagesHomeLaunch.accepts("android.intent.action.MAIN", true, false, true, false));
        assertTrue(MessagesHomeLaunch.accepts("android.intent.action.MAIN", true, false, false, false));
    }
    @Test public void coldComponentRestartIsAccepted() {
        assertTrue(MessagesHomeLaunch.accepts(null, false, false, true, false));
    }
    @Test public void ordinaryWarmInternalReentryIsNotALaunch() {
        assertFalse(MessagesHomeLaunch.accepts(null, false, false, false, false));
    }
    @Test public void routedNotificationLaunchCannotOverrideItsDestination() {
        assertFalse(MessagesHomeLaunch.accepts("android.intent.action.MAIN", true, true, true, false));
        assertFalse(MessagesHomeLaunch.accepts("android.intent.action.MAIN", true, true, false, false));
    }
    @Test public void deepLinkAndShareKeepTheirNativeDestinations() {
        assertFalse(MessagesHomeLaunch.accepts("android.intent.action.VIEW", false, true, true, false));
        assertFalse(MessagesHomeLaunch.accepts("android.intent.action.SEND", false, true, true, false));
    }
    @Test public void mainActionWithoutLauncherCategoryIsNotAnIconLaunch() {
        assertFalse(MessagesHomeLaunch.accepts("android.intent.action.MAIN", false, false, false, false));
    }
    @Test public void savedActivityRecreationIsNotANewLaunch() {
        assertFalse(MessagesHomeLaunch.accepts("android.intent.action.MAIN", true, false, true, true));
        assertFalse(MessagesHomeLaunch.accepts(null, false, false, true, true));
    }

}
