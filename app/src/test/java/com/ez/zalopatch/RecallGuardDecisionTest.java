package com.ez.zalopatch;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RecallGuardDecisionTest {
    private static final String ACCOUNT = "1000";
    private static final String PEER = "2000";
    private static final String DIRECT = "2000";
    private static final String GROUP = "group_123";

    @Test
    public void ordinaryIncomingTextQualifies() {
        assertTrue(RecallGuardDecision.qualifies(PEER, ACCOUNT, DIRECT,
                RecallGuardDecision.TEXT_TYPE, "hello"));
    }

    @Test
    public void observedNativeTextTypeZeroQualifies() {
        assertTrue("live incoming recall carries type 0", RecallGuardDecision.qualifies(
                PEER, ACCOUNT, DIRECT, 0, "synthetic received text"));
        assertFalse("type 1 is not ordinary text", RecallGuardDecision.qualifies(
                PEER, ACCOUNT, DIRECT, 1, "synthetic other content"));
    }

    @Test
    public void ownMessagesStayNative() {
        assertFalse(RecallGuardDecision.qualifies(ACCOUNT, ACCOUNT, ACCOUNT,
                RecallGuardDecision.TEXT_TYPE, "hello"));
    }

    @Test
    public void groupsStayNative() {
        assertFalse(RecallGuardDecision.qualifies(PEER, ACCOUNT, GROUP,
                RecallGuardDecision.TEXT_TYPE, "hello"));
        assertFalse(RecallGuardDecision.directConversation(GROUP));
        assertTrue(RecallGuardDecision.directConversation(DIRECT));
    }

    @Test
    public void nonTextAndEmptyContentStayNative() {
        assertFalse(RecallGuardDecision.qualifies(PEER, ACCOUNT, DIRECT, 2, "photo"));
        assertFalse(RecallGuardDecision.qualifies(PEER, ACCOUNT, DIRECT,
                RecallGuardDecision.TEXT_TYPE, ""));
        assertFalse(RecallGuardDecision.qualifies(PEER, ACCOUNT, DIRECT,
                RecallGuardDecision.TEXT_TYPE, null));
    }

    @Test
    public void missingIdentityStaysNative() {
        assertFalse(RecallGuardDecision.qualifies("", ACCOUNT, DIRECT,
                RecallGuardDecision.TEXT_TYPE, "hello"));
        assertFalse(RecallGuardDecision.qualifies(null, ACCOUNT, DIRECT,
                RecallGuardDecision.TEXT_TYPE, "hello"));
        assertFalse(RecallGuardDecision.qualifies(PEER, "", DIRECT,
                RecallGuardDecision.TEXT_TYPE, "hello"));
        assertFalse(RecallGuardDecision.qualifies(PEER, null, DIRECT,
                RecallGuardDecision.TEXT_TYPE, "hello"));
        assertFalse(RecallGuardDecision.qualifies(PEER, ACCOUNT, "",
                RecallGuardDecision.TEXT_TYPE, "hello"));
        assertFalse(RecallGuardDecision.qualifies(PEER, ACCOUNT, null,
                RecallGuardDecision.TEXT_TYPE, "hello"));
        assertFalse(RecallGuardDecision.directConversation(""));
        assertFalse(RecallGuardDecision.directConversation(null));
    }
}
