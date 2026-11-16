package com.zing.zalo.data.entity.chat.message;

/** Shape fixture. It carries no host implementation or message data. */
public final class MessageId {
    public String owner;
    public int wrongOwner;

    public String serialize() {
        return "";
    }
}
