package com.ez.zalopatch.xposed.features;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class InboxNativeRouteTest {
    private static final String PACKAGE = "com.ez.zalopatch.xposed.features.";

    @Test
    public void opensNativeDestinationThroughUniqueHostRoute() throws Exception {
        View view = new View();
        InboxNativeRoute route = InboxNativeRoute.find(View.class, Bundle.class, PACKAGE);
        assertNotNull(route);
        assertTrue(route.open(view, Destination.class));
        assertEquals(Destination.class, view.host.manager.destination);
        assertNull(view.host.manager.arguments);
        assertEquals(1, view.host.manager.mode);
        assertTrue(view.host.manager.animate);
    }

    @Test
    public void failsClosedForMultipleHostRoutes() {
        assertNull(InboxNativeRoute.find(AmbiguousView.class, Bundle.class, PACKAGE));
    }

    @Test
    public void failsClosedForMultipleManagerGetters() {
        assertNull(InboxNativeRoute.find(AmbiguousGetterView.class, Bundle.class, PACKAGE));
    }

    @Test
    public void detachedViewDoesNotNavigate() throws Exception {
        View view = new View();
        view.connection = null;
        InboxNativeRoute route = InboxNativeRoute.find(View.class, Bundle.class, PACKAGE);
        assertNotNull(route);
        assertFalse(route.open(view, Destination.class));
    }

    public static class View {
        final HostImpl host = new HostImpl();
        Host connection = host;
    }
    public static final class AmbiguousView extends View {
        Host another;
    }
    public static final class AmbiguousGetterView {
        AmbiguousHost connection;
    }
    public interface Host {
        Navigator manager();
    }
    public interface AmbiguousHost {
        Navigator first();
        Navigator second();
    }
    public static final class HostImpl implements Host {
        final Navigator manager = new Navigator();
        public Navigator manager() { return manager; }
    }
    public static final class Navigator {
        Class<?> destination;
        Bundle arguments;
        int mode;
        boolean animate;
        public void open(Class<?> type, Bundle bundle, int value, boolean animated) {
            destination = type;
            arguments = bundle;
            mode = value;
            animate = animated;
        }
    }
    public static final class Bundle {}
    public static final class Destination {}
}
