package com.ez.zalopatch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class RecallIdentityTest {
    @Test
    public void everyComponentSeparatesIdentities() {
        RecallIdentity original = new RecallIdentity("account", "client", "global", "owner",
                "sender");
        assertEquals(original, new RecallIdentity("account", "client", "global", "owner",
                "sender"));
        assertNotEquals(original, new RecallIdentity("other", "client", "global", "owner",
                "sender"));
        assertNotEquals(original, new RecallIdentity("account", "other", "global", "owner",
                "sender"));
        assertNotEquals(original, new RecallIdentity("account", "client", "other", "owner",
                "sender"));
        assertNotEquals(original, new RecallIdentity("account", "client", "global", "other",
                "sender"));
        assertNotEquals(original, new RecallIdentity("account", "client", "global", "owner",
                "other"));
        assertNotEquals(original, new RecallIdentity("account", "client", "global", "owner", ""));
    }

    @Test
    public void missingComponentsNeverBecomeAliases() {
        assertNotEquals(new RecallIdentity("a", "c", "", "o", "s"),
                new RecallIdentity("a", "c", "g", "o", "s"));
        assertNotEquals(new RecallIdentity("a", "", "g", "o", "s"),
                new RecallIdentity("a", "c", "g", "o", "s"));
        assertTrue(new RecallIdentity("a", "c", "", "o", "s").complete());
        assertTrue(new RecallIdentity("a", "", "g", "o", "s").complete());
        assertFalse(new RecallIdentity("a", "", "", "o", "s").complete());
        assertFalse(new RecallIdentity("", "c", "g", "o", "s").complete());
        assertFalse(new RecallIdentity("a", "c", "g", "", "s").complete());
        assertFalse(new RecallIdentity("a", "c", "g", "o", "").complete());
    }

    @Test
    public void hostSerializationPreservesRawStringsAndRejectsCoercion() throws Exception {
        org.json.JSONObject json = new org.json.JSONObject().put("clientMsgId", "001")
                .put("globalMsgId", "").put("ownerId", "002").put("senderId", "003");
        assertEquals(new RecallIdentity("account", "001", "", "002", "003"),
                RecallIdentity.parse("account", json.toString()));
        assertNull(RecallIdentity.parse("account", json.put("clientMsgId", 1).toString()));
        json.put("clientMsgId", "001").remove("senderId");
        assertNull(RecallIdentity.parse("account", json.toString()));
        assertNull(RecallIdentity.parse("account", "broken"));
        assertNull(RecallIdentity.parse("account", null));
    }
}
