package com.ez.zalopatch;

import java.util.Objects;

/**
 * Exact local identity for one message. Host {@code MessageId} equality accepts partial
 * matches and sender wildcards, so qualification reads every component explicitly.
 */
public final class RecallIdentity {
    public final String account;
    public final String client;
    public final String global;
    public final String owner;
    public final String sender;

    public RecallIdentity(String account, String client, String global, String owner,
                          String sender) {
        this.account = Objects.requireNonNull(account);
        this.client = Objects.requireNonNull(client);
        this.global = Objects.requireNonNull(global);
        this.owner = Objects.requireNonNull(owner);
        this.sender = Objects.requireNonNull(sender);
    }

    /** Account, conversation owner, sender, and at least one message id are mandatory. */
    public boolean complete() {
        return !account.isEmpty() && !owner.isEmpty() && !sender.isEmpty()
                && (!client.isEmpty() || !global.isEmpty());
    }

    /** Reads the semantically resolved host JSON supplier without coercion or aliases. */
    public static RecallIdentity parse(String account, String serialized) {
        try {
            if (serialized == null || serialized.length() > 2048) {
                return null;
            }
            org.json.JSONObject json = new org.json.JSONObject(serialized);
            String[] keys = {"clientMsgId", "globalMsgId", "ownerId", "senderId"};
            String[] values = new String[4];
            for (int index = 0; index < keys.length; index++) {
                Object value = json.get(keys[index]);
                if (!(value instanceof String) || ((String) value).length() > 256) {
                    return null;
                }
                values[index] = (String) value;
            }
            RecallIdentity id = new RecallIdentity(account, values[0], values[1],
                    values[2], values[3]);
            return id.complete() ? id : null;
        } catch (Exception error) {
            return null;
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof RecallIdentity)) {
            return false;
        }
        RecallIdentity id = (RecallIdentity) other;
        return account.equals(id.account) && client.equals(id.client)
                && global.equals(id.global) && owner.equals(id.owner)
                && sender.equals(id.sender);
    }

    @Override
    public int hashCode() {
        return Objects.hash(account, client, global, owner, sender);
    }
}
