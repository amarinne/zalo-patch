package com.ez.zalopatch.xposed.features;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class InboxListUpdateTest {
    @Test
    public void replaysObservedOverloadWithItsOriginalExtraArguments() throws Exception {
        Adapter adapter = new Adapter();
        Method observed = Adapter.class.getDeclaredMethod("update", List.class, boolean.class);
        Object[] arguments = {new ArrayList<>(Arrays.asList("chat", "group")), true};
        InboxListUpdate update = new InboxListUpdate(observed, adapter, arguments);
        arguments[1] = false;
        List<String> groups = Arrays.asList("group");

        update.method.invoke(update.adapter, update.withItems(groups));

        assertEquals(groups, adapter.items);
        assertTrue(adapter.animated);
        assertEquals(0, adapter.otherSetterCalls);
    }

    @Test
    public void allRestoresOriginalSourceAfterInputAndFilteredListsChange() throws Exception {
        Adapter adapter = new Adapter();
        List<String> incoming = new ArrayList<>(Arrays.asList("chat", "group"));
        InboxListUpdate update = new InboxListUpdate(
                Adapter.class.getDeclaredMethod("update", List.class, boolean.class),
                adapter, new Object[]{incoming, true});
        incoming.clear();
        Object[] filtered = update.withItems(Arrays.asList("group"));
        filtered[1] = false;
        update.method.invoke(update.adapter, filtered);

        update.method.invoke(update.adapter, update.withItems(new ArrayList<>(update.source)));

        assertEquals(Arrays.asList("chat", "group"), adapter.items);
        assertTrue(adapter.animated);
    }

    @Test
    public void acceptsInstanceUpdatesAndRejectsUnrelatedListMethods() throws Exception {
        assertTrue(InboxListUpdate.accepts(
                Adapter.class.getDeclaredMethod("update", List.class, boolean.class)));
        assertTrue(InboxListUpdate.accepts(
                Adapter.class.getDeclaredMethod("other", List.class)));
        assertFalse(InboxListUpdate.accepts(
                Adapter.class.getDeclaredMethod("query", List.class)));
        assertFalse(InboxListUpdate.accepts(
                Adapter.class.getDeclaredMethod("staticUpdate", List.class)));
        assertFalse(InboxListUpdate.accepts(
                Adapter.class.getDeclaredMethod("notifyUpdate")));
    }

    public static final class Adapter {
        List<?> items;
        boolean animated;
        int otherSetterCalls;

        public void update(List<?> value, boolean animate) {
            items = value;
            animated = animate;
        }

        public void other(List<?> value) {
            otherSetterCalls++;
        }

        public List<?> query(List<?> value) {
            return value;
        }

        public static void staticUpdate(List<?> value) {
        }

        public void notifyUpdate() {
        }
    }
}
