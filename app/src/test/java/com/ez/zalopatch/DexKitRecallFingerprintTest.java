package com.ez.zalopatch;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DexKitRecallFingerprintTest {
    public static class NativeAdapter {
        int notifications;
        public final void renamedNotify() { notifications++; }
        public void notFinal() { throw new AssertionError(); }
        public static final void staticDecoy() { throw new AssertionError(); }
        public final int query() { return 0; }
        public final void withArgument(int position) { throw new AssertionError(); }
    }
    public static final class ConcreteAdapter extends NativeAdapter {
        public final void unrelatedAction() { throw new AssertionError(); }
    }
    public static final class AmbiguousAdapter {
        public final void first() { }
        public final void second() { }
    }

    @Test public void nativeAdapterOwnerAvoidsConcreteSubclassDecoys() throws Exception {
        // The stable RecyclerView getter's return type supplies NativeAdapter, not ConcreteAdapter.
        java.lang.reflect.Method notify = DexKitRecallFingerprint.adapterRefresh(NativeAdapter.class);
        assertEquals("renamedNotify", notify.getName());
        ConcreteAdapter instance = new ConcreteAdapter();
        notify.invoke(instance);
        assertEquals(1, instance.notifications);
    }

    @Test public void missingOrAmbiguousAdapterRefreshDoesNotGuess() {
        assertEquals(null, DexKitRecallFingerprint.adapterRefresh(Object.class));
        assertEquals(null, DexKitRecallFingerprint.adapterRefresh(AmbiguousAdapter.class));
    }

    private DexKitRecallFingerprint.MethodShape shape(String owner, String name, String result,
                                                      String... params) {
        return new DexKitRecallFingerprint.MethodShape(owner, name, result,
                Arrays.asList(params), false);
    }

    private DexKitRecallFingerprint.Route route(String prefix, boolean edge) {
        return new DexKitRecallFingerprint.Route(
                shape(prefix + ".Dao", "write", "void", prefix + ".Message", "boolean",
                        prefix + ".Undo"),
                shape(prefix + ".Dao", "update", DexKitRecallFingerprint.API_CODE,
                        prefix + ".Record", DexKitRecallFingerprint.MESSAGE_ID, "boolean"),
                shape(prefix + ".Handler", "consume", "void", "java.lang.Object"),
                shape(prefix + ".Handler", "preview", "void", DexKitRecallFingerprint.MESSAGE_ID,
                        "boolean", prefix + ".Undo", "boolean"),
                shape(prefix + ".Events", "undo", "void", prefix + ".Message"),
                "identity", edge, true, true, true);
    }

    @Test
    public void renamedRouteResolvesWithoutObfuscatedDefaults() {
        DexKitRecallFingerprint.Resolution result = DexKitRecallFingerprint.evaluate(
                Collections.singletonList(route("renamed", true)));
        assertEquals("resolved", result.status);
        assertEquals("renamed.Dao", result.descriptors.get("symbols.recall.writer_class"));
        assertEquals("renamed.Events",
                result.descriptors.get("symbols.recall.undo_handler_class"));
        assertEquals(12, result.descriptors.size());
    }

    @Test
    public void absentAndBrokenRoutesStayUnavailable() {
        assertTrue(DexKitRecallFingerprint.evaluate(Collections.emptyList())
                .descriptors.isEmpty());
        assertTrue(DexKitRecallFingerprint.evaluate(Collections.singletonList(
                route("broken", false))).descriptors.isEmpty());
    }

    @Test
    public void decoyDoesNotHideUniqueRouteAndDuplicatesReject() {
        assertEquals("resolved", DexKitRecallFingerprint.evaluate(Arrays.asList(
                route("decoy", false), route("real", true))).status);
        assertEquals("ambiguous_route", DexKitRecallFingerprint.evaluate(Arrays.asList(
                route("a", true), route("b", true))).status);
    }

    @Test
    public void identicalRouteRepeatsResolve() {
        DexKitRecallFingerprint.Route route = route("renamed", true);
        assertEquals("resolved", DexKitRecallFingerprint.evaluate(
                Arrays.asList(route, route)).status);
    }

    @Test
    public void wrongWriterShapeRejects() {
        assertFalse(DexKitRecallFingerprint.writerShape(shape("other.Dao", "write", "void",
                "other.Message", "int", "other.Undo")));
        assertFalse(DexKitRecallFingerprint.writerShape(shape("other.Dao", "write", "void",
                "other.Message", "boolean", "other.Undo", "other.Extra")));
    }

    @Test
    public void defaultPackageClassNamesAreValidDescriptors() {
        DexKitAnchors.Anchor anchor = DexKitAnchors.find("symbols.recall.model_class");
        assertTrue(DexKitAnchors.validDescriptor(anchor, "vv3"));
        assertTrue(DexKitAnchors.validDescriptor(anchor, "com.zing.zalo.SomeClass"));
        assertFalse(DexKitAnchors.validDescriptor(anchor, ""));
        assertFalse(DexKitAnchors.validDescriptor(anchor, "vv3;evil"));
    }

    @Test
    public void defaultPackageRouteResolves() {
        DexKitRecallFingerprint.Route route = new DexKitRecallFingerprint.Route(
                shape("Dao", "write", "void", "Message", "boolean", "Undo"),
                shape("Dao", "update", DexKitRecallFingerprint.API_CODE,
                        "Record", DexKitRecallFingerprint.MESSAGE_ID, "boolean"),
                shape("Handler", "consume", "void", "java.lang.Object"),
                shape("Handler", "preview", "void", DexKitRecallFingerprint.MESSAGE_ID,
                        "boolean", "Undo", "boolean"),
                shape("Events", "undo", "void", "Message"),
                "identity", true, true, true, true);
        DexKitRecallFingerprint.Resolution result =
                DexKitRecallFingerprint.evaluate(Collections.singletonList(route));
        assertEquals("resolved", result.status);
        assertEquals("Dao", result.descriptors.get("symbols.recall.writer_class"));
        assertEquals("Message", result.descriptors.get("symbols.recall.model_class"));
    }

    @Test
    public void missingOrWrongUndoHandlerRejects() {
        DexKitRecallFingerprint.Route missing = new DexKitRecallFingerprint.Route(
                shape("Dao", "write", "void", "Message", "boolean", "Undo"),
                shape("Dao", "update", DexKitRecallFingerprint.API_CODE,
                        "Record", DexKitRecallFingerprint.MESSAGE_ID, "boolean"),
                shape("Handler", "consume", "void", "java.lang.Object"),
                shape("Handler", "preview", "void", DexKitRecallFingerprint.MESSAGE_ID,
                        "boolean", "Undo", "boolean"),
                null, "identity", true, true, true, true);
        assertEquals("no_coherent_route",
                DexKitRecallFingerprint.evaluate(Collections.singletonList(missing)).status);
        DexKitRecallFingerprint.Route wrong = new DexKitRecallFingerprint.Route(
                shape("Dao", "write", "void", "Message", "boolean", "Undo"),
                shape("Dao", "update", DexKitRecallFingerprint.API_CODE,
                        "Record", DexKitRecallFingerprint.MESSAGE_ID, "boolean"),
                shape("Handler", "consume", "void", "java.lang.Object"),
                shape("Handler", "preview", "void", DexKitRecallFingerprint.MESSAGE_ID,
                        "boolean", "Undo", "boolean"),
                shape("Events", "undo", "void", "java.lang.Object"),
                "identity", true, true, true, true);
        assertEquals("no_coherent_route",
                DexKitRecallFingerprint.evaluate(Collections.singletonList(wrong)).status);
    }

    @Test
    public void neighboringRecallIsNeverInherited() throws Exception {
        org.json.JSONObject neighbor = new org.json.JSONObject()
                .put("recall", new org.json.JSONObject().put("writer_class", "unsafe.Dao"));
        org.json.JSONObject composed = DexKitOverlay.compose(null, neighbor,
                Collections.emptyMap(), "", "");
        assertFalse(composed.has("recall"));
        composed = DexKitOverlay.compose(null, neighbor,
                DexKitRecallFingerprint.evaluate(Collections.singletonList(route("safe", true)))
                        .descriptors, "", "");
        assertEquals("safe.Dao", composed.getJSONObject("recall").getString("writer_class"));
    }
}
