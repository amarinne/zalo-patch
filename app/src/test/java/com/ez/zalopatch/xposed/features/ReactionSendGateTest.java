package com.ez.zalopatch.xposed.features;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests for the custom-reaction toggle gate and the send-outcome retention rules. */
public final class ReactionSendGateTest {

    @Test
    public void toggleGateIsOffByDefaultAndHideTakesPrecedence() {
        assertFalse(ReactionSendGate.enabled(false, false));
        assertTrue(ReactionSendGate.enabled(true, false));
        assertFalse("hide reaction row must disable the emoji button",
                ReactionSendGate.enabled(true, true));
        assertFalse(ReactionSendGate.enabled(false, true));
    }

    @Test
    public void constructionNeverCountsAsASend() {
        ReactionSendGate gate = ReactionSendGate.create();
        assertEquals(ReactionSendGate.STATUS_INSTALLED, gate.status());
        gate.buttonReady();
        assertEquals(ReactionSendGate.STATUS_INSTALLED, gate.status());
        assertFalse(gate.hasFailed());
    }

    @Test
    public void sendFailureSurvivesANewPickerAndGivesWayToSuccess() {
        ReactionSendGate gate = ReactionSendGate.create();
        gate.sendFailed("listener unavailable");
        assertEquals(ReactionSendGate.STATUS_FAILED, gate.status());
        assertTrue(gate.hasFailed());

        // A new picker/button must not mask the recorded failure.
        gate.buttonReady();
        assertEquals(ReactionSendGate.STATUS_FAILED, gate.status());
        assertEquals("listener unavailable", gate.failureReason());

        // Only a genuine successful send clears it.
        gate.sendSucceeded();
        assertEquals(ReactionSendGate.STATUS_ACTIVE, gate.status());
        assertFalse(gate.hasFailed());

        // A later failure is reported honestly.
        gate.sendFailed("dispatch failed");
        assertEquals(ReactionSendGate.STATUS_FAILED, gate.status());
    }

    @Test
    public void emptyFailureReasonStillMarksFailed() {
        ReactionSendGate gate = ReactionSendGate.create();
        gate.sendFailed("");
        assertEquals(ReactionSendGate.STATUS_FAILED, gate.status());
        assertFalse(gate.failureReason().isEmpty());
    }

    @Test
    public void delayedSelectionIsRejectedWhenThePickerClosedOrRebound() {
        assertTrue(ReactionSendGate.canSend(true, true));
        assertFalse("detached or hidden picker", ReactionSendGate.canSend(false, true));
        assertFalse("listener rebound to another message",
                ReactionSendGate.canSend(true, false));
        assertFalse(ReactionSendGate.canSend(false, false));

        // The routed native-expand path may send while detached, but only through the
        // exact listener captured at interception.
        assertTrue(ReactionSendGate.canSend(false, true, true));
        assertTrue(ReactionSendGate.canSend(true, true, true));
        assertFalse("rebound listener on the routed path",
                ReactionSendGate.canSend(false, false, true));
        assertFalse("detached without the routed path",
                ReactionSendGate.canSend(false, true, false));
    }
}
