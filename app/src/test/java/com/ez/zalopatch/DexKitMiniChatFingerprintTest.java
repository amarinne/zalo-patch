package com.ez.zalopatch;

import android.os.Bundle;
import android.content.Context;
import com.zing.zalo.data.chat.model.tabmessage.Conversation;
import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public final class DexKitMiniChatFingerprintTest {
    public static class Capability {
        public static boolean nativeHeads, bubbles;
        public boolean instance;
        public static int wrongType;
        public static final boolean constant = true;
    }
    public static class Config {
        public static boolean configured;
        public static void refresh() { }
        public void instanceRefresh() { }
    }
    public static class Controller { public static boolean eligible() { return false; } }
    public static class Task implements Runnable {
        public Task(Controller controller, Conversation conversation, boolean a, int b,
                    boolean c, CharSequence text, Bundle bundle, boolean d) { }
        public void run() { }
    }
    public static class OverlayManager { public OverlayManager(Context context) { } }
    public static class Overlay {
        public Overlay(Context context, OverlayManager manager, Controller controller) { }
    }
    public static class Host { public Host(Context context, Overlay overlay) { } }
    public static class AmbiguousHost {
        public AmbiguousHost(Context context, Overlay overlay) { }
        public AmbiguousHost(Context context, AnotherOverlay overlay) { }
    }
    public static class AnotherOverlay {
        public AnotherOverlay(Context context, OverlayManager manager, Controller controller) { }
    }
    private Map<String, String> route() {
        Map<String, String> result = new LinkedHashMap<>();
        String[] names = {"capability_class", "config_class", "controller_class", "task_class",
                "legacy_field", "config_field", "reload_method", "eligible_method"};
        String[] values = {Capability.class.getName(), Config.class.getName(), Controller.class.getName(),
                Task.class.getName(), "nativeHeads", "configured", "refresh", "eligible"};
        for (int i = 0; i < names.length; i++) result.put(DexKitMiniChatFingerprint.PREFIX + names[i], values[i]);
        return result;
    }
    private void rejected(Map<String, String> route) {
        assertThrows(Exception.class, () -> DexKitMiniChatFingerprint.bind(route, getClass().getClassLoader()));
    }
    @Test public void renamedFamilyBindsWithoutChangingHostFlags() throws Exception {
        Capability.nativeHeads = false; Capability.bubbles = true; Config.configured = true;
        DexKitMiniChatFingerprint.Binding bound = DexKitMiniChatFingerprint.bind(route(), getClass().getClassLoader());
        assertEquals(Task.class, bound.task);
        assertFalse(Capability.nativeHeads); assertTrue(Capability.bubbles); assertTrue(Config.configured);
    }
    @Test public void everyMissingLeafRejectsWholeRoute() {
        for (String path : DexKitMiniChatFingerprint.paths()) {
            Map<String, String> values = route(); values.remove(path); rejected(values);
        }
    }
    @Test public void wrongTypeOrInstanceFlagsAreRejected() {
        for (String field : new String[]{"wrongType", "instance"}) {
            Map<String, String> values = route();
            values.put(DexKitMiniChatFingerprint.PREFIX + "legacy_field", field); rejected(values);
        }
    }
    @Test public void artCapabilityMayBeStaticFinal() throws Exception {
        Map<String, String> values = route();
        values.put(DexKitMiniChatFingerprint.PREFIX + "legacy_field", "constant");
        assertTrue(java.lang.reflect.Modifier.isFinal(DexKitMiniChatFingerprint.bind(values,
                getClass().getClassLoader()).legacy.getModifiers()));
    }
    @Test public void wrongReloadAndControllerCannotBind() {
        Map<String, String> values = route();
        values.put(DexKitMiniChatFingerprint.PREFIX + "reload_method", "instanceRefresh"); rejected(values);
        values = route(); values.put(DexKitMiniChatFingerprint.PREFIX + "controller_class", Config.class.getName()); rejected(values);
    }
    @Test public void overlayConstructorTypesFollowTheNativeController() throws Exception {
        java.lang.reflect.Constructor<?>[] ctors = DexKitMiniChatFingerprint.overlayConstructors(
                Host.class, Controller.class);
        assertEquals(Overlay.class, ctors[0].getDeclaringClass());
        assertEquals(OverlayManager.class, ctors[1].getDeclaringClass());
        assertThrows(Exception.class, () -> DexKitMiniChatFingerprint.overlayConstructors(
                Host.class, Config.class));
        assertThrows(Exception.class, () -> DexKitMiniChatFingerprint.overlayConstructors(
                AmbiguousHost.class, Controller.class));
    }
}
