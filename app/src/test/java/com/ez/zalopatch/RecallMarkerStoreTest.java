package com.ez.zalopatch;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RecallMarkerStoreTest {
    private final Map<String, Object> disk = new HashMap<>();
    private boolean commitSucceeds = true;

    private SharedPreferences preferences() {
        return (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getAll")) return new HashMap<>(disk);
                    if (!method.getName().equals("edit")) throw new AssertionError(method);
                    Map<String, Object> next = new HashMap<>(disk);
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editor, edit, values) -> {
                                switch (edit.getName()) {
                                    case "putLong": next.put((String) values[0], values[1]); return editor;
                                    case "remove": next.remove(values[0]); return editor;
                                    case "commit":
                                        if (!commitSucceeds) return false;
                                        disk.clear(); disk.putAll(next); return true;
                                    default: throw new AssertionError(edit);
                                }
                            });
                });
    }

    private RecallIdentity id(String account, String client, String global, String owner) {
        return new RecallIdentity(account, client, global, owner, "peer");
    }

    @Test public void aRecordedSuppressionSurvivesStoreRecreation() {
        RecallIdentity message = id("account", "client", "global", "conversation");
        RecallMarkerStore store = new RecallMarkerStore(preferences());
        assertFalse(store.contains(message));
        assertTrue(store.remember(message, 100));
        assertTrue(new RecallMarkerStore(preferences()).contains(message));
        assertEquals(1, disk.size());
        assertTrue(disk.keySet().iterator().next().matches("[0-9a-f]{64}"));
        assertEquals(100L, disk.values().iterator().next());
    }

    @Test public void accountsConversationsAndExactMessagesDoNotBorrowLabels() {
        RecallMarkerStore store = new RecallMarkerStore(preferences());
        assertTrue(store.remember(id("a", "c", "g", "o"), 100));
        assertFalse(store.contains(id("other-account", "c", "g", "o")));
        assertFalse(store.contains(id("a", "other-client", "g", "o")));
        assertFalse(store.contains(id("a", "c", "other-global", "o")));
        assertFalse(store.contains(id("a", "c", "g", "other-owner")));
        assertNotEquals(RecallMarkerStore.key(id("a", "x", "g", "bc")),
                RecallMarkerStore.key(id("ab", "x", "g", "c")));
    }

    @Test public void missingIdentityAndFailedPersistenceDoNotClaimAMarker() {
        RecallMarkerStore store = new RecallMarkerStore(preferences());
        assertFalse(store.remember(null, 1));
        assertFalse(store.remember(id("", "c", "g", "o"), 1));
        commitSucceeds = false;
        RecallIdentity message = id("a", "c", "g", "o");
        assertFalse(store.remember(message, 100));
        assertFalse(store.contains(message));
        assertTrue(disk.isEmpty());
    }

    @Test public void repeatingAnObservedRecallDoesNotDuplicateTheRecord() {
        RecallMarkerStore store = new RecallMarkerStore(preferences());
        RecallIdentity message = id("a", "c", "g", "o");
        assertTrue(store.remember(message, 100));
        assertTrue(store.remember(message, 200));
        assertEquals(1, disk.size());
        assertEquals(100L, disk.values().iterator().next());
    }

    @Test public void theDerivedMarkerIndexRemainsBounded() {
        for (int i = 0; i < RecallMarkerStore.MAX_MARKS; i++) {
            disk.put(String.format("%064x", i), (long) i + 1);
        }
        RecallMarkerStore store = new RecallMarkerStore(preferences());
        RecallIdentity message = id("a", "c", "g", "o");
        assertTrue(store.remember(message, 10000));
        assertEquals(RecallMarkerStore.MAX_MARKS, disk.size());
        assertFalse(disk.containsKey("0".repeat(64)));
        assertTrue(store.contains(message));
    }

    @Test public void theDisplayNotePreservesNativeTimeInEitherLanguage() {
        assertEquals("18:25 · Recalled", RecallMarkerStore.timeLabel("18:25", "Recalled"));
        assertEquals("18:25 · Đã thu hồi", RecallMarkerStore.timeLabel("18:25", "Đã thu hồi"));
        assertEquals("Recalled", RecallMarkerStore.timeLabel("", "Recalled"));
    }
}
