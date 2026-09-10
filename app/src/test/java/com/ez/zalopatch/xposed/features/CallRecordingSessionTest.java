package com.ez.zalopatch.xposed.features;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import org.junit.Test;

/** Exercises the recorder's actual session lifetime without invoking Android or ZRTC. */
public final class CallRecordingSessionTest {
    private static final Class<?> SESSION;
    static {
        try {
            SESSION = Class.forName(CallRecordingFeature.class.getName() + "$Session");
        } catch (ClassNotFoundException error) {
            throw new AssertionError(error);
        }
    }

    @Test
    public void deletedPeerCannotReachNativeStartThroughLateCallback() throws Exception {
        Object session = session(11L);
        set(session, "confirmed", true);
        set(session, "audioConnected", true);
        set(session, "deleted", true);
        // An unguarded start reaches Android context resolution and fails this JVM test.
        invoke("start", new Class<?>[]{SESSION, String.class}, session, "late_callback");
        assertFalse((Boolean) get(session, "started"));
        assertEquals(0, get(session, "startAttempts"));
    }

    @Test
    public void deletionRetiresCachedCallbackAndReleasesPeerBinding() throws Exception {
        Map<Long, Object> peers = map("SESSIONS_BY_PEER");
        Map<Object, Object> callbacks = map("SESSIONS");
        Object callback = new Object();
        Object old = session(12L);
        peers.put(12L, old);
        callbacks.put(callback, old);
        try {
            invoke("stopForPeer", new Class<?>[]{long.class, String.class},
                    12L, "PeerJNI#zrtc_peer_delete");
            assertTrue((Boolean) get(old, "deleted"));
            assertFalse(peers.containsKey(12L));
            // The tombstone prevents a late callback from resolving a freed pointer.
            assertSame(old, callbacks.get(callback));
            assertTrue(CallRecordingLifecycle.beginsCall("onMakeCall"));
            assertTrue(CallRecordingLifecycle.beginsCall("onIncomingCall"));
            assertFalse(CallRecordingLifecycle.beginsCall("onCallAudioState"));
        } finally {
            peers.remove(12L);
            callbacks.remove(callback);
        }
    }

    @Test
    public void terminalBeforeCaptureClearsConnectionEvidence() throws Exception {
        Object session = session(13L);
        set(session, "confirmed", true);
        set(session, "audioConnected", true);
        invoke("stop", new Class<?>[]{SESSION, String.class}, session, "onCallEnd");
        assertFalse((Boolean) get(session, "confirmed"));
        assertFalse((Boolean) get(session, "audioConnected"));
    }

    private static Object session(long peer) throws Exception {
        Constructor<?> constructor = SESSION.getDeclaredConstructor(long.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(peer, null);
    }

    private static Object get(Object session, String name) throws Exception {
        Field field = SESSION.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(session);
    }

    private static void set(Object session, String name, Object value) throws Exception {
        Field field = SESSION.getDeclaredField(name);
        field.setAccessible(true);
        field.set(session, value);
    }

    private static void invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = CallRecordingFeature.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(null, args);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> map(String name) throws Exception {
        Field field = CallRecordingFeature.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<K, V>) field.get(null);
    }
}
