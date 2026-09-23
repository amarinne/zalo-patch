package com.ez.zalopatch;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests for the chat seen/typing repository fingerprint. */
public final class DexKitChatFingerprintTest {
    private static DexKitChatFingerprint.MethodHit hit(String owner, String name,
                                                       List<String> params) {
        return new DexKitChatFingerprint.MethodHit(owner, name, params);
    }

    private static final List<String> ACK =
            Arrays.asList("java.util.List", "boolean", "boolean", "boolean");
    private static final List<String> TYPING =
            Arrays.asList("java.lang.String", "int", "boolean", "boolean");

    @Test
    public void resolvesUniqueRepository() {
        List<DexKitChatFingerprint.MethodHit> hits = Arrays.asList(
                hit("c20.r", "Q", ACK),
                hit("c20.r", "S", TYPING),
                hit("other.x", "j", Arrays.asList("java.util.List", "boolean", "boolean",
                        "boolean")));
        DexKitChatFingerprint.Resolution resolution = DexKitChatFingerprint.evaluate(hits);
        assertTrue(resolution.resolved());
        assertEquals("c20.r", resolution.repositoryClass);
        assertEquals("Q", resolution.ackMethod);
        assertEquals("S", resolution.typingMethod);
    }

    @Test
    public void ambiguousWhenSeveralOwnersMatch() {
        List<DexKitChatFingerprint.MethodHit> hits = Arrays.asList(
                hit("a.a", "Q", ACK),
                hit("a.a", "S", TYPING),
                hit("b.b", "Q", ACK),
                hit("b.b", "S", TYPING));
        DexKitChatFingerprint.Resolution resolution = DexKitChatFingerprint.evaluate(hits);
        assertFalse(resolution.resolved());
        assertEquals("ambiguous_repository", resolution.status);
    }

    @Test
    public void ackOnlyStaysUnavailable() {
        List<DexKitChatFingerprint.MethodHit> hits = Arrays.asList(
                hit("a.a", "Q", ACK));
        DexKitChatFingerprint.Resolution resolution = DexKitChatFingerprint.evaluate(hits);
        assertFalse(resolution.resolved());
        assertEquals("no_repository", resolution.status);
    }

    @Test
    public void duplicateAckMethodStaysUnavailable() {
        List<DexKitChatFingerprint.MethodHit> hits = Arrays.asList(
                hit("a.a", "Q", ACK),
                hit("a.a", "R", ACK),
                hit("a.a", "S", TYPING));
        DexKitChatFingerprint.Resolution resolution = DexKitChatFingerprint.evaluate(hits);
        assertFalse(resolution.resolved());
        assertEquals("ambiguous_ack_method", resolution.status);
    }

    @Test
    public void emptyStaysUnavailable() {
        assertFalse(DexKitChatFingerprint.evaluate(null).resolved());
        assertFalse(DexKitChatFingerprint.evaluate(Arrays.asList()).resolved());
    }
    static class Ack { int renamedType; int other; static int shared; }
    static class Repository { Ack latest(String uid) { return null; } }
    static class Manager {
        Repository repository;
        java.util.ArrayList<Ack> queue;
        synchronized void enqueue(Ack ack) { }
        synchronized void enqueueAll(java.util.ArrayList<Ack> acks) { }
    }
    static class UnsynchronizedManager {
        Repository repository;
        java.util.ArrayList<Ack> queue;
        void enqueue(Ack ack) { }
        synchronized void enqueueAll(java.util.ArrayList<Ack> acks) { }
    }
    static class WrongRepositoryManager {
        Object repository;
        java.util.ArrayList<Ack> queue;
        synchronized void enqueue(Ack ack) { }
        synchronized void enqueueAll(java.util.ArrayList<Ack> acks) { }
    }

    @Test public void queueRequiresSynchronizedMethodsAndRepositoryLink() {
        java.util.Map<String, String> found = DexKitChatFingerprint.queueShape(
                Manager.class, Repository.class);
        assertEquals("enqueue", found.get(DexKitChatFingerprint.ANCHOR_SINGLE));
        assertEquals("enqueueAll", found.get(DexKitChatFingerprint.ANCHOR_BATCH));
        assertEquals(Ack.class.getName(), found.get(DexKitChatFingerprint.ANCHOR_ACK_CLASS));
        assertTrue(DexKitChatFingerprint.queueShape(
                UnsynchronizedManager.class, Repository.class).isEmpty());
        assertTrue(DexKitChatFingerprint.queueShape(
                WrongRepositoryManager.class, Repository.class).isEmpty());
    }

    @Test public void ackFieldFollowsSemanticGuardRatherThanName() {
        assertEquals("renamedType", DexKitChatFingerprint.ackTypeField(Ack.class,
                Arrays.asList(Ack.class.getName() + "#renamedType", "unrelated.Owner#other")));
        assertEquals("", DexKitChatFingerprint.ackTypeField(Ack.class,
                Arrays.asList(Ack.class.getName() + "#renamedType", Ack.class.getName() + "#other")));
        assertEquals("", DexKitChatFingerprint.ackTypeField(Ack.class,
                Arrays.asList(Ack.class.getName() + "#shared")));
    }

}
