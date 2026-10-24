package com.ez.zalopatch;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** JVM tests for the native emoji reaction send fingerprint and its live shape gate. */
public final class DexKitReactionSendFingerprintTest {
    // ---- Fixture shapes (stand-ins for the obfuscated host chain).

    public interface Listener {
        void Z(Reaction reaction, String entryPoint);

        void unrelated();
    }

    public interface RenamedListener {
        void renamedCallback(Reaction reaction, String entryPoint);
    }

    public interface AmbiguousListener {
        void first(Reaction reaction, String entryPoint);

        void second(Reaction reaction, String entryPoint);
    }

    public interface WrongParamListener {
        void callback(String text, String entryPoint);
    }

    public interface NotACallbackListener {
        void callback(Reaction reaction);
    }

    public interface ExpandListener {
        void Z(Reaction reaction, String entryPoint);

        void expand(String entryPoint);
    }

    public interface AmbiguousExpandListener {
        void first(String entryPoint);

        void second(String entryPoint);
    }

    public static final class Reaction {
        public static final Factory Companion = new Factory();

        final int type;
        final String icon;

        Reaction(int type, String icon) {
            this.type = type;
            this.icon = icon;
        }
    }

    public static final class Factory {
        public static Reaction a(int type, String icon) {
            return new Reaction(type, icon);
        }

        public static Reaction b(int type, int uid, String icon, long timestamp) {
            return new Reaction(type, icon);
        }
    }

    public static final class AmbiguousReaction {
        public static final AmbiguousFactory Companion = new AmbiguousFactory();
    }

    public static final class AmbiguousFactory {
        public static AmbiguousReaction a(int type, String icon) {
            return null;
        }

        public static AmbiguousReaction other(int type, String icon) {
            return null;
        }
    }

    public static final class WrongReceiverReaction {
        public static final ChildFactory Companion = new ChildFactory();
    }

    public static class BaseFactory {
        public WrongReceiverReaction a(int type, String icon) {
            return null;
        }
    }

    public static final class ChildFactory extends BaseFactory {
    }

    public static final class NoFactoryReaction {
        public static final NoFactoryCompanion Companion = new NoFactoryCompanion();
    }

    public static final class NoFactoryCompanion {
        public static NoFactoryReaction a(int type, int uid, String icon, long timestamp) {
            return null;
        }
    }

    private static DexKitReactionSendFingerprint.MethodHit hit(String owner, String name,
                                                               String returnType,
                                                               String... params) {
        return new DexKitReactionSendFingerprint.MethodHit(owner, name, returnType,
                Arrays.asList(params));
    }

    private static List<String> errors() {
        return new ArrayList<String>();
    }

    // ---- Callback fingerprint.

    @Test
    public void callbackFollowsTheRenameRatherThanTheOldName() {
        List<DexKitReactionSendFingerprint.MethodHit> hits = Collections.singletonList(
                hit("lo1.r", "renamedCallback", "void", Reaction.class.getName(),
                        "java.lang.String"));
        DexKitReactionSendFingerprint.CallbackResolution resolution =
                DexKitReactionSendFingerprint.evaluateCallback(hits, "lo1.r");
        assertTrue(resolution.status, resolution.resolved());
        assertEquals("renamedCallback", resolution.callbackMethod);
        assertEquals(Reaction.class.getName(), resolution.reactionClass);
    }

    @Test
    public void ambiguousCallbacksStayUnavailable() {
        List<DexKitReactionSendFingerprint.MethodHit> hits = Arrays.asList(
                hit("lo1.r", "first", "void", Reaction.class.getName(), "java.lang.String"),
                hit("lo1.r", "second", "void", Reaction.class.getName(), "java.lang.String"));
        DexKitReactionSendFingerprint.CallbackResolution resolution =
                DexKitReactionSendFingerprint.evaluateCallback(hits, "lo1.r");
        assertFalse(resolution.resolved());
        assertEquals("ambiguous_callback", resolution.status);
    }

    @Test
    public void callbackWithWrongShapeIsRejected() {
        List<DexKitReactionSendFingerprint.MethodHit> hits = Arrays.asList(
                hit("lo1.r", "callback", "void", "java.lang.String", "java.lang.String"),
                hit("lo1.r", "callback", "void", Reaction.class.getName()));
        assertFalse(DexKitReactionSendFingerprint.evaluateCallback(hits, "lo1.r").resolved());
        assertEquals("no_callback",
                DexKitReactionSendFingerprint.evaluateCallback(hits, "lo1.r").status);
    }

    @Test
    public void callbackOnAnotherOwnerIsIgnored() {
        List<DexKitReactionSendFingerprint.MethodHit> hits = Collections.singletonList(
                hit("other.Owner", "callback", "void", Reaction.class.getName(),
                        "java.lang.String"));
        assertEquals("no_callback",
                DexKitReactionSendFingerprint.evaluateCallback(hits, "lo1.r").status);
        assertEquals("no_listener",
                DexKitReactionSendFingerprint.evaluateCallback(hits, "").status);
    }

    // ---- Factory fingerprint.

    @Test
    public void factoryFollowsTheRenameAndStillRejectsAmbiguity() {
        List<DexKitReactionSendFingerprint.MethodHit> renamed = Collections.singletonList(
                hit(Factory.class.getName(), "renamedFactory", Reaction.class.getName(),
                        "int", "java.lang.String"));
        DexKitReactionSendFingerprint.FactoryResolution resolution =
                DexKitReactionSendFingerprint.evaluateFactory(renamed, Reaction.class.getName(),
                        Factory.class.getName());
        assertTrue(resolution.status, resolution.resolved());
        assertEquals("renamedFactory", resolution.factoryMethod);

        List<DexKitReactionSendFingerprint.MethodHit> ambiguous = Arrays.asList(
                hit(Factory.class.getName(), "a", Reaction.class.getName(),
                        "int", "java.lang.String"),
                hit(Factory.class.getName(), "b", Reaction.class.getName(),
                        "int", "java.lang.String"));
        assertEquals("ambiguous_factory", DexKitReactionSendFingerprint.evaluateFactory(
                ambiguous, Reaction.class.getName(), Factory.class.getName()).status);
    }

    @Test
    public void factoryWithWrongReceiverOrSignatureIsRejected() {
        List<DexKitReactionSendFingerprint.MethodHit> wrongReceiver = Collections.singletonList(
                hit(BaseFactory.class.getName(), "a", WrongReceiverReaction.class.getName(),
                        "int", "java.lang.String"));
        assertEquals("no_factory", DexKitReactionSendFingerprint.evaluateFactory(wrongReceiver,
                WrongReceiverReaction.class.getName(), ChildFactory.class.getName()).status);

        List<DexKitReactionSendFingerprint.MethodHit> wrongSignature = Collections.singletonList(
                hit(Factory.class.getName(), "b", Reaction.class.getName(),
                        "int", "int", "java.lang.String", "long"));
        assertEquals("no_factory", DexKitReactionSendFingerprint.evaluateFactory(wrongSignature,
                Reaction.class.getName(), Factory.class.getName()).status);
    }

    // ---- Live shape gate (cached descriptors are re-validated, not trusted).

    @Test
    public void shapeGateAcceptsTheUniqueChain() {
        assertTrue(DexKitReactionSendFingerprint.checkShape(getClass().getClassLoader(),
                Listener.class.getName(), "Z", Reaction.class.getName(),
                "a", errors()));
    }

    @Test
    public void shapeGateFollowsRenames() {
        assertTrue(DexKitReactionSendFingerprint.checkShape(getClass().getClassLoader(),
                RenamedListener.class.getName(), "renamedCallback", Reaction.class.getName(),
                "a", errors()));
    }

    @Test
    public void shapeGateRejectsCachedDescriptorAmbiguity() {
        List<String> callbackErrors = errors();
        assertFalse(DexKitReactionSendFingerprint.checkShape(getClass().getClassLoader(),
                AmbiguousListener.class.getName(), "first", Reaction.class.getName(),
                "a", callbackErrors));
        assertFalse(callbackErrors.isEmpty());

        List<String> factoryErrors = errors();
        assertFalse(DexKitReactionSendFingerprint.checkShape(getClass().getClassLoader(),
                Listener.class.getName(), "Z", AmbiguousReaction.class.getName(),
                "a", factoryErrors));
        assertFalse(factoryErrors.isEmpty());
    }

    @Test
    public void shapeGateRejectsWrongShapeAndMissingGroup() {
        assertFalse(DexKitReactionSendFingerprint.checkShape(getClass().getClassLoader(),
                WrongParamListener.class.getName(), "callback", Reaction.class.getName(),
                "a", errors()));
        assertFalse(DexKitReactionSendFingerprint.checkShape(getClass().getClassLoader(),
                NotACallbackListener.class.getName(), "callback", Reaction.class.getName(),
                "a", errors()));
        assertFalse(DexKitReactionSendFingerprint.checkShape(getClass().getClassLoader(),
                Listener.class.getName(), "Z", WrongReceiverReaction.class.getName(),
                "a", errors()));
        assertFalse(DexKitReactionSendFingerprint.checkShape(getClass().getClassLoader(),
                Listener.class.getName(), "Z", NoFactoryReaction.class.getName(),
                "a", errors()));
    }

    @Test
    public void incompleteGroupStaysUnavailable() {
        List<String> missing = errors();
        assertFalse(DexKitReactionSendFingerprint.checkShape(getClass().getClassLoader(),
                "", "", "", "", missing));
        assertFalse(missing.isEmpty());
        assertFalse(DexKitReactionSendFingerprint.complete(null));
        Map<String, String> partial = new LinkedHashMap<>();
        partial.put(DexKitReactionSendFingerprint.ANCHOR_LISTENER_CLASS, Listener.class.getName());
        assertFalse(DexKitReactionSendFingerprint.complete(partial));
        Map<String, String> full = new LinkedHashMap<>();
        for (String anchor : DexKitReactionSendFingerprint.ANCHORS) {
            full.put(anchor, "value");
        }
        assertTrue(DexKitReactionSendFingerprint.complete(full));
    }

    @Test
    public void resolutionIsNullSafeAndReactionParameterRuleIsExplicit() {
        assertFalse(DexKitReactionSendFingerprint.evaluateCallback(null, null).resolved());
        assertFalse(DexKitReactionSendFingerprint.evaluateFactory(null, null, null).resolved());
        assertEquals("", DexKitReactionSendFingerprint.listenerClassOf(null));
        assertEquals("", DexKitReactionSendFingerprint.companionClassOf(null, "a.B"));
        assertEquals("expand",
                DexKitReactionSendFingerprint.expandMethodName(ExpandListener.class));
        assertEquals("", DexKitReactionSendFingerprint.expandMethodName(
                AmbiguousExpandListener.class));
        assertEquals("", DexKitReactionSendFingerprint.expandMethodName(Listener.class));
        assertEquals("", DexKitReactionSendFingerprint.expandMethodName(null));
        assertTrue(DexKitReactionSendFingerprint.isReactionParameter(Reaction.class.getName()));
        assertFalse(DexKitReactionSendFingerprint.isReactionParameter("java.lang.String"));
        assertFalse(DexKitReactionSendFingerprint.isReactionParameter("int"));
        assertFalse(DexKitReactionSendFingerprint.isReactionParameter(""));
        assertNotEquals("", DexKitReactionSendFingerprint.ANCHOR_LISTENER_CLASS);
    }
}
