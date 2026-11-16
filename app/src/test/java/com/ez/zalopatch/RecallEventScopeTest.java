package com.ez.zalopatch;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RecallEventScopeTest {
    @Test
    public void scopeIsNestedAndClearedOnExit() {
        assertFalse(RecallEventScope.inUndo());
        RecallEventScope.enterUndo();
        assertTrue(RecallEventScope.inUndo());
        RecallEventScope.enterUndo();
        assertTrue(RecallEventScope.inUndo());
        RecallEventScope.leaveUndo();
        assertTrue(RecallEventScope.inUndo());
        RecallEventScope.leaveUndo();
        assertFalse(RecallEventScope.inUndo());
        RecallEventScope.leaveUndo();
        assertFalse(RecallEventScope.inUndo());
    }

    @Test
    public void scopeDoesNotLeakAcrossThreads() throws Exception {
        RecallEventScope.enterUndo();
        AtomicBoolean other = new AtomicBoolean(true);
        Thread thread = new Thread(() -> other.set(RecallEventScope.inUndo()));
        thread.start();
        thread.join();
        try {
            assertFalse(other.get());
            assertTrue(RecallEventScope.inUndo());
        } finally {
            RecallEventScope.leaveUndo();
        }
        assertFalse(RecallEventScope.inUndo());
    }
}
